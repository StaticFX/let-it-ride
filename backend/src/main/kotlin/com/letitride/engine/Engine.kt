package com.letitride.engine

/** Points awarded on top of the hand for collecting seven unique number cards. */
const val FLIP7_BONUS = 15

/**
 * Default number of unique number cards that ends the round instantly. House
 * rules can move the bar, so the engine reads [RuleSet.flipTarget] rather than
 * this constant — it is only the value a table with no rules on plays to.
 */
const val FLIP7_TARGET = 7

const val MIN_PLAYERS = 2
const val MAX_PLAYERS = 5

/** What happened to the player who just drew a card. */
enum class DrawOutcome {
    /** Nothing is blocking; the caller may keep going. */
    CONTINUE,

    /** Waiting on a target pick or a nested forced draw. */
    PAUSED,

    BUSTED,

    FLIP7,
}

/**
 * The mutable working surface for one transition. Card effects and lobby rules
 * talk to the game exclusively through this, and every command records an event
 * so the client can replay the transition as animation.
 */
class Ctx(state: GameState, val rng: Rng) {
    var state: GameState = state
        internal set

    val events = mutableListOf<GameEvent>()

    val rules: RuleSet = RuleSet.of(state.config)

    private var ephemeralCounter = 0

    // ─── Queries ───

    fun player(id: String): Player? = state.player(id)

    fun hasPassive(playerId: String, defId: String): Boolean =
        player(playerId)?.passives?.any { it.defId == defId } == true

    fun hasMark(playerId: String, markId: String): Boolean =
        player(playerId)?.marks?.contains(markId) == true

    fun activePlayers(): List<Player> = state.players.filter { it.status == PlayerStatus.ACTIVE }

    // ─── Mutation primitives ───

    fun update(id: String, transform: (Player) -> Player) {
        state = state.copy(players = state.players.map { if (it.id == id) transform(it) else it })
    }

    fun emit(event: GameEvent) {
        events += event
    }

    private fun withHand(player: Player, hand: List<Card>) =
        player.copy(hand = hand, handValue = hand.sumOf { it.value })

    // ─── Deck ───

    /**
     * Pops the top card, reshuffling the discard pile back in when the deck runs
     * dry. Returns null only when there is genuinely no card left anywhere.
     */
    fun drawRaw(): Card? {
        if (state.deck.isEmpty()) {
            val reusable = state.discard.filterNot { it.isEphemeral }
            if (reusable.isEmpty()) return null
            state = state.copy(deck = rng.shuffled(reusable), discard = emptyList())
            emit(GameEvent.DeckReshuffled(state.deck.size))
        }
        val card = state.deck.first()
        state = state.copy(deck = state.deck.drop(1))
        return card
    }

    fun toDiscard(card: Card) {
        // Minted cards were never part of the deck, so they must not be able to
        // get shuffled back into it.
        if (card.isEphemeral) return
        state = state.copy(discard = state.discard + card)
    }

    // ─── Commands available to card effects ───

    fun bust(playerId: String, reason: String, card: Card? = null, matched: Card? = null) {
        val player = player(playerId) ?: return
        if (player.status == PlayerStatus.BUST) return
        update(playerId) { it.copy(status = PlayerStatus.BUST, bustReason = reason) }
        emit(GameEvent.Bust(playerId, reason, card, matched))
        detonate(playerId)
    }

    /**
     * A player carrying a bomb does not go out alone. Every bust in the game
     * runs through [bust], so this covers duplicates, the threshold, a coin
     * called wrong, the bottle and a ratio without any of them knowing about it.
     *
     * The mark is spent as it fires, so a table of bombers taking each other
     * out terminates: each one goes off once. When a prompt is already open the
     * bomb cannot stop the table again — that bust happened while another was
     * being answered — so it picks for itself rather than being lost.
     */
    private fun detonate(playerId: String) {
        if (!hasMark(playerId, BOMBER.id)) return
        update(playerId) { it.copy(marks = it.marks - BOMBER.id) }
        val victims = activePlayers().map { it.id }
        if (victims.isEmpty()) return
        if (state.pendingAction != null) {
            rng.pick(victims)?.let { bust(it, BUST_BOMBER) }
            return
        }
        raisePrompt(SUICIDE_BOMBER.id, playerId, PHASE_BUST, victims)
    }

    /**
     * Stops the table on a question no card was just drawn for. The prompt
     * carries its own targets: the card's target rule described how it was
     * played, which is not what is being asked now.
     *
     * The card behind it is minted rather than real — the one that set this up
     * was spent long ago — and a minted card never reaches the discard pile, so
     * the deck stays honest.
     */
    fun raisePrompt(
        defId: String,
        playerId: String,
        phase: String,
        targets: List<String>,
        options: List<String> = emptyList(),
        responders: List<String> = emptyList(),
        kind: PickKind = PickKind.PLAYER,
        cards: List<String> = emptyList(),
        picks: Int = 1,
        offers: List<Offer> = emptyList(),
    ) {
        if (targets.isEmpty() && options.isEmpty() && cards.isEmpty() && offers.isEmpty()) return
        state = state.copy(
            pendingAction = PendingAction(
                cardDefId = defId,
                playerId = playerId,
                card = Card(
                    id = "tmp-$defId-${ephemeralCounter++}",
                    kind = CardKind.ACTION,
                    label = defId,
                    value = 0,
                    defId = defId,
                ),
                validTargets = targets,
                options = options,
                kind = kind,
                validCards = cards,
                picks = picks,
                phase = phase,
                responders = responders,
                offers = offers,
            ),
        )
    }

    /**
     * Everything this player could buy right now, priced.
     *
     * Only what the table's own deck holds — a friendly table cannot buy an
     * assassination that was never in it — and only what they can actually pay
     * for out of what the round has left them. Nothing on this list can put
     * anybody below zero, "extreme" or not: a round may cost you more than it
     * paid, but not because you chose to spend money you did not have.
     */
    fun offersFor(playerId: String): List<Offer> {
        val player = player(playerId) ?: return emptyList()
        val purse = player.score + (state.roundAdjustments[playerId] ?: 0)
        if (purse <= 0) return emptyList()
        val deck = state.config.deck
        val offers = mutableListOf<Offer>()

        for (entry in deck.numberCards) {
            val label = entry.label ?: entry.value.toString()
            val price = priceOfNumber(entry.value)
            if (price > purse) continue
            offers += Offer(
                id = offerIdForNumber(label),
                price = price,
                card = Card(
                    id = "offer-${offerIdForNumber(label)}",
                    kind = CardKind.NUMBER,
                    label = label,
                    value = entry.value,
                    suit = entry.suits?.firstOrNull(),
                ),
            )
        }

        for (defId in deck.passiveCards.distinct()) {
            val def = Catalog.passive(defId) ?: continue
            if (def.price > purse) continue
            offers += Offer(
                id = offerIdForPassive(defId),
                price = def.price,
                card = Card(
                    id = "offer-${offerIdForPassive(defId)}",
                    kind = CardKind.PASSIVE,
                    label = def.name,
                    value = 0,
                    defId = defId,
                ),
            )
        }

        return offers
    }

    /**
     * Hands over a bought card and takes the price out of the round.
     *
     * The card is minted rather than dealt: buying one must not thin the deck
     * everybody else is drawing from, and a minted card never reaches the
     * discard pile. A number card can still collide with something already in
     * hand, so the buy is re-checked like any other way of gaining one — though
     * nobody would knowingly buy a duplicate.
     */
    fun buy(playerId: String, offer: Offer) {
        val player = player(playerId) ?: return
        val card = offer.card.copy(id = "tmp-buy-${ephemeralCounter++}")
        adjust(playerId, -offer.price)
        if (card.kind == CardKind.PASSIVE) {
            update(playerId) { it.copy(passives = it.passives + card) }
            emit(GameEvent.PassiveGained(playerId, card))
        } else {
            update(playerId) { p ->
                val hand = p.hand + card
                p.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
            }
        }
        emit(GameEvent.Bought(playerId, card, offer.price))
        if (player.status == PlayerStatus.ACTIVE) resolveBustAfterGain(playerId)
    }

    /**
     * Trades two players' banked scores outright. The round's own points are
     * not involved: this is the scoreboard changing hands, not a hand.
     */
    fun swapScores(aId: String, bId: String) {
        val a = player(aId) ?: return
        val b = player(bId) ?: return
        if (a.id == b.id) return
        val aScore = a.score
        update(aId) { it.copy(score = b.score) }
        update(bId) { it.copy(score = aScore) }
        emit(GameEvent.ScoresSwapped(aId, b.score, bId, aScore))
    }

    /**
     * The card turned out to do nothing, and its drawer is owed another. By the
     * time an effect runs the card itself has already been discarded, so this
     * is only the replacement and the note that it happened — the same thing
     * the engine does for a card with nobody to hit.
     */
    fun wasted(defId: String, playerId: String) {
        emit(GameEvent.Fizzled(defId, playerId))
        if (player(playerId)?.status != PlayerStatus.ACTIVE) return
        pushForcedDraws(playerId, 1)
    }

    fun skip(playerId: String) {
        update(playerId) { it.copy(skipNextTurn = true) }
        emit(GameEvent.Skip(playerId))
    }

    /**
     * Moves points that are not hand scoring — a deduction now, a purchase
     * later. They ride in [GameState.roundAdjustments] until the round is
     * scored, so the summary shows them alongside everything else it paid
     * rather than as a score that silently changed.
     */
    fun adjust(playerId: String, points: Int) {
        if (points == 0 || player(playerId) == null) return
        val current = state.roundAdjustments[playerId] ?: 0
        state = state.copy(roundAdjustments = state.roundAdjustments + (playerId to current + points))
    }

    /**
     * Puts a player under an effect for the rest of the round. Marking twice is
     * a no-op rather than an error — "double it!" fires every effect twice, and
     * the second one has nothing left to do.
     */
    fun mark(playerId: String, markId: String) {
        val player = player(playerId) ?: return
        if (markId in player.marks) return
        update(playerId) { it.copy(marks = it.marks + markId) }
        emit(GameEvent.Marked(playerId, markId))
    }

    /** Removes a specific card from a hand and puts it on the discard pile. */
    fun discardFromHand(playerId: String, card: Card) {
        val player = player(playerId) ?: return
        if (player.hand.none { it.id == card.id }) return
        update(playerId) { withHand(it, it.hand.filterNot { c -> c.id == card.id }) }
        toDiscard(card)
        emit(GameEvent.Discard(playerId, card))
    }

    fun discardHighest(playerId: String): Card? {
        val player = player(playerId) ?: return null
        val highest = player.hand.maxByOrNull { it.value } ?: return null
        discardFromHand(playerId, highest)
        return highest
    }

    fun stealRandom(fromId: String, toId: String): Card? {
        val from = player(fromId) ?: return null
        val to = player(toId) ?: return null
        if (from.hand.isEmpty()) return null
        val card = rng.pick(from.hand) ?: return null
        update(fromId) { withHand(it, it.hand.filterNot { c -> c.id == card.id }) }
        update(toId) { withHand(it, it.hand + card) }
        emit(GameEvent.Steal(fromId, toId, card))
        return card
    }

    /**
     * Slides every hand one seat around the table, in seat order. "right" moves
     * each hand to the next participant, "left" to the previous one.
     *
     * Only players still in the round take part: a hand landing on someone who
     * already busted or went out would rewrite a score that is already settled,
     * and nothing would ever re-check it for legality. Whole hands move intact,
     * so no duplicate can appear — but a hand can be heavier than the one it
     * replaced, so the caller still has to re-check the threshold.
     *
     * Returns the participating seats in order, or an empty list when there was
     * nobody to rotate between.
     */
    fun rotateHands(direction: String): List<String> {
        val participants = activePlayers()
        if (participants.size < 2) return emptyList()
        val hands = participants.map { it.hand }
        val size = participants.size
        for (index in participants.indices) {
            // Who this seat receives from: the seat behind it when spinning
            // right, the seat ahead of it when spinning left.
            val donor = if (direction == SPIN_LEFT) (index + 1) % size else (index + size - 1) % size
            update(participants[index].id) { withHand(it, hands[donor]) }
        }
        val ids = participants.map { it.id }
        emit(GameEvent.TableSpun(direction, ids))
        return ids
    }

    /** Whoever is holding [cardId] right now, hand or modifier row alike. */
    fun ownerOf(cardId: String): Player? =
        state.players.firstOrNull { p -> (p.hand + p.passives).any { it.id == cardId } }

    /**
     * Trades two cards between their owners. Each card lands in the pile it
     * belongs in — a number card in a hand, a modifier in the row in front —
     * so a swap can hand somebody a +4 for a 7 without a modifier ending up
     * counted as a card towards the flip.
     *
     * Returns the seats that gained something, for the caller to re-check: a
     * hand that took on a card can be holding a duplicate now, which is the
     * whole reason to play this.
     */
    fun swapCards(firstId: String, secondId: String): List<String> {
        if (firstId == secondId) return emptyList()
        val a = ownerOf(firstId) ?: return emptyList()
        val b = ownerOf(secondId) ?: return emptyList()
        // Two cards changing places inside one hand is a hand that has not
        // changed; there is nothing here to re-check and nothing to animate.
        if (a.id == b.id) return emptyList()
        val cardA = (a.hand + a.passives).first { it.id == firstId }
        val cardB = (b.hand + b.passives).first { it.id == secondId }

        fun give(playerId: String, taken: Card, given: Card) = update(playerId) { p ->
            val hand = p.hand.filterNot { it.id == taken.id } + if (given.kind == CardKind.NUMBER) listOf(given) else emptyList()
            val passives = p.passives.filterNot { it.id == taken.id } +
                if (given.kind != CardKind.NUMBER) listOf(given) else emptyList()
            p.copy(hand = hand, passives = passives, handValue = hand.sumOf { it.value })
        }

        give(a.id, cardA, cardB)
        give(b.id, cardB, cardA)
        emit(GameEvent.CardsSwapped(a.id, cardA, b.id, cardB))
        return listOf(a.id, b.id)
    }

    fun swapHands(aId: String, bId: String) {
        val a = player(aId) ?: return
        val b = player(bId) ?: return
        val aHand = a.hand
        val bHand = b.hand
        update(aId) { withHand(it, bHand) }
        update(bId) { withHand(it, aHand) }
        emit(GameEvent.Swap(aId, bId))
    }

    /** Spends a passive: it leaves the player and lands on the discard pile. */
    fun consumePassive(playerId: String, defId: String): Card? {
        val player = player(playerId) ?: return null
        val card = player.passives.firstOrNull { it.defId == defId } ?: return null
        update(playerId) { it.copy(passives = it.passives.filterNot { c -> c.id == card.id }) }
        toDiscard(card)
        return card
    }

    /**
     * Mints a passive that was not dealt from the deck (double-or-nothing's
     * reward). Ephemeral cards are dropped at round end instead of joining the
     * discard pile, so the deck never grows.
     */
    fun grantEphemeralPassive(playerId: String, defId: String) {
        val def = Catalog.passive(defId) ?: return
        val card = Card(
            id = "tmp-$defId-${ephemeralCounter++}",
            kind = CardKind.PASSIVE,
            label = def.name,
            value = 0,
            defId = def.id,
        )
        update(playerId) { it.copy(passives = it.passives + card) }
        emit(GameEvent.PassiveGained(playerId, card))
    }

    fun pushForcedDraws(playerId: String, count: Int, source: String? = null) {
        val previous = state.forcedDraws
        state = state.copy(
            forcedDraws = ForcedDraws(playerId, count, source),
            forcedDrawStack = if (previous != null) state.forcedDrawStack + previous else state.forcedDrawStack,
        )
    }

    fun popForcedDraws() {
        val stack = state.forcedDrawStack
        state = if (stack.isNotEmpty()) {
            state.copy(forcedDraws = stack.last(), forcedDrawStack = stack.dropLast(1))
        } else {
            state.copy(forcedDraws = null, forcedDrawStack = emptyList())
        }
    }

    fun clearForcedDraws() {
        state = state.copy(forcedDraws = null, forcedDrawStack = emptyList())
    }

    /**
     * Re-checks a hand after it gained a card by other means than a draw
     * (a steal or a swap). Returns true if the player busted.
     */
    fun resolveBustAfterGain(playerId: String): Boolean {
        val player = player(playerId) ?: return false
        if (player.status != PlayerStatus.ACTIVE) return false
        val result = checkBust(player) ?: return false
        if (result.reason == BUST_DUPLICATE && result.duplicate != null && hasPassive(playerId, SECOND_LIFE.id)) {
            consumeSecondChance(playerId, result.duplicate, result.matched)
            return false
        }
        bust(playerId, result.reason, result.duplicate, result.matched)
        return true
    }

    fun consumeSecondChance(playerId: String, duplicate: Card, matched: Card? = null) {
        consumePassive(playerId, SECOND_LIFE.id)
        discardFromHand(playerId, duplicate)
        emit(GameEvent.SecondChance(playerId, duplicate, matched))
    }

    // ─── Bust rules ───

    data class BustResult(val reason: String, val duplicate: Card? = null, val matched: Card? = null)

    fun checkBust(player: Player): BustResult? {
        val seen = mutableMapOf<String, Card>()
        for (card in player.hand) {
            val clash = seen.put(card.label, card)
            if (clash != null) return BustResult(BUST_DUPLICATE, card, clash)
        }
        val threshold = rules.bustThreshold
        if (threshold != null && player.handValue > threshold) return BustResult(BUST_THRESHOLD)
        return null
    }

    companion object {
        const val BUST_DUPLICATE = "duplicate"
        const val BUST_THRESHOLD = "threshold"
    }
}

data class TransitionResult(val state: GameState, val events: List<GameEvent>)

object Engine {

    fun newGame(config: GameConfig = defaultGameConfig()): GameState = GameState(config = config)

    fun transition(state: GameState, action: GameAction, rng: Rng): TransitionResult {
        val ctx = Ctx(state, rng)
        apply(ctx, action)
        return TransitionResult(ctx.state, ctx.events)
    }

    // ═══════════════════════════════════════════
    // Action dispatch
    // ═══════════════════════════════════════════

    private fun apply(ctx: Ctx, action: GameAction) {
        when (action) {
            is GameAction.AddPlayer -> addPlayer(ctx, action)
            is GameAction.RemovePlayer -> removePlayer(ctx, action)
            is GameAction.SetConnected -> ctx.update(action.playerId) { it.copy(connected = action.connected) }
            is GameAction.SetConfig -> if (ctx.state.phase == GamePhase.LOBBY) {
                ctx.state = ctx.state.copy(config = action.config)
            }

            GameAction.StartGame -> startGame(ctx)
            is GameAction.DealTo -> dealTo(ctx, action.playerId)
            is GameAction.Hit -> hit(ctx, action.playerId)
            is GameAction.Stay -> stay(ctx, action.playerId)
            is GameAction.PlayAction -> playPendingAction(ctx, action)
            GameAction.ForcedDraw -> forcedDraw(ctx)
            is GameAction.Timeout -> timeout(ctx, action.playerId)
            GameAction.NextRound -> nextRound(ctx)
        }
    }

    // ═══════════════════════════════════════════
    // Lobby
    // ═══════════════════════════════════════════

    private fun addPlayer(ctx: Ctx, action: GameAction.AddPlayer) {
        val state = ctx.state
        if (state.phase != GamePhase.LOBBY) return
        if (state.players.size >= MAX_PLAYERS) return
        if (state.player(action.playerId) != null) return
        ctx.state = state.copy(
            players = state.players + Player(id = action.playerId, name = action.name, isBot = action.isBot),
        )
    }

    private fun removePlayer(ctx: Ctx, action: GameAction.RemovePlayer) {
        val state = ctx.state
        if (state.phase == GamePhase.LOBBY) {
            ctx.state = state.copy(players = state.players.filterNot { it.id == action.playerId })
            return
        }
        // Mid-game a leaver is folded rather than removed, so seat indices hold.
        val player = state.player(action.playerId) ?: return
        ctx.update(player.id) { it.copy(connected = false) }
        if (player.status == PlayerStatus.ACTIVE) {
            ctx.update(player.id) { it.copy(status = PlayerStatus.STAYED) }
            ctx.emit(GameEvent.Stay(player.id))
            val pending = state.pendingAction
            if (pending?.playerId == player.id) {
                ctx.toDiscard(pending.card)
                ctx.state = ctx.state.copy(pendingAction = null)
            }
            if (ctx.state.forcedDraws?.playerId == player.id) ctx.popForcedDraws()
            advanceAndCheck(ctx)
        }
    }

    private fun startGame(ctx: Ctx) {
        val state = ctx.state
        if (state.phase != GamePhase.LOBBY || state.players.size < MIN_PLAYERS) return
        val deck = ctx.rng.shuffled(Deck.build(state.config.deck))
        ctx.state = state.copy(
            phase = GamePhase.PLAYING,
            round = 1,
            turnIndex = 0,
            roundStartPlayer = 0,
            deck = deck,
            discard = emptyList(),
            pendingAction = null,
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            roundWinnerId = null,
            gameWinnerId = null,
            flip7PlayerId = null,
            roundDeltas = emptyMap(),
            roundAdjustments = emptyMap(),
            players = state.players.map {
                it.copy(
                    hand = emptyList(), passives = emptyList(), handValue = 0,
                    status = PlayerStatus.ACTIVE, score = 0, bustReason = null, skipNextTurn = false,
                    marks = emptySet(),
                )
            },
            dealQueue = dealOrder(state.players.map { it.id }, 0),
        )
    }

    private fun dealOrder(ids: List<String>, startIndex: Int): List<String> =
        ids.indices.map { ids[(startIndex + it) % ids.size] }

    // ═══════════════════════════════════════════
    // Opening deal
    // ═══════════════════════════════════════════

    /**
     * Gives one player their opening card of the round. Never advances the turn:
     * the room paces the deal so the client can animate each card.
     */
    private fun dealTo(ctx: Ctx, playerId: String) {
        val state = ctx.state
        if (state.phase != GamePhase.PLAYING || state.isInterrupted) return
        if (state.dealQueue.firstOrNull() != playerId) return
        val player = state.player(playerId) ?: return
        val seat = state.players.indexOfFirst { it.id == playerId }

        // Parking the turn marker on whoever is being dealt to means that once
        // the queue drains, the normal advance lands on the round's starter.
        ctx.state = state.copy(dealQueue = state.dealQueue.drop(1), turnIndex = seat)

        if (player.status == PlayerStatus.ACTIVE && player.hand.isEmpty() && player.passives.isEmpty()) {
            val card = ctx.drawRaw()
            if (card != null && resolveDrawnCard(ctx, playerId, card) == DrawOutcome.FLIP7) {
                endRoundByFlip7(ctx, playerId)
            }
        }
        advanceAndCheck(ctx)
    }

    // ═══════════════════════════════════════════
    // Turn actions
    // ═══════════════════════════════════════════

    private fun hit(ctx: Ctx, playerId: String) {
        val state = ctx.state
        if (state.phase != GamePhase.PLAYING || state.isInterrupted || state.dealQueue.isNotEmpty()) return
        val current = state.currentPlayer ?: return
        if (current.id != playerId || current.status != PlayerStatus.ACTIVE) return

        var drawn = 0
        while (drawn < ctx.rules.drawsPerTurn) {
            val card = ctx.drawRaw()
            if (card == null) {
                // Nothing left to draw anywhere — going out beats deadlocking.
                if (drawn == 0) {
                    ctx.update(playerId) { it.copy(status = PlayerStatus.STAYED) }
                    ctx.emit(GameEvent.Stay(playerId))
                }
                break
            }
            drawn++
            when (resolveDrawnCard(ctx, playerId, card)) {
                DrawOutcome.PAUSED -> return
                DrawOutcome.BUSTED -> break
                DrawOutcome.FLIP7 -> {
                    endRoundByFlip7(ctx, playerId)
                    break
                }

                DrawOutcome.CONTINUE -> if (ctx.player(playerId)?.status != PlayerStatus.ACTIVE) break
            }
        }
        advanceAndCheck(ctx)
    }

    private fun stay(ctx: Ctx, playerId: String) {
        val state = ctx.state
        if (state.phase != GamePhase.PLAYING || state.isInterrupted || state.dealQueue.isNotEmpty()) return
        val current = state.currentPlayer ?: return
        if (current.id != playerId || current.status != PlayerStatus.ACTIVE) return
        if (!canStay(ctx, current)) return
        ctx.update(playerId) { it.copy(status = PlayerStatus.STAYED) }
        ctx.emit(GameEvent.Stay(playerId))
        advanceAndCheck(ctx)
    }

    /** Everyone takes at least one card each round unless the host disabled it. */
    private fun canStay(ctx: Ctx, player: Player): Boolean =
        ctx.rules.allowStayWithEmptyHand || player.hand.isNotEmpty() || player.passives.isNotEmpty()

    private fun timeout(ctx: Ctx, playerId: String) {
        val state = ctx.state
        if (state.phase != GamePhase.PLAYING) return

        val pending = state.pendingAction
        if (pending != null) {
            if (pending.playerId != playerId) return
            // One clock covers the whole prompt, so when it runs out every
            // answer still outstanding is filled in at once — otherwise one
            // player who walked away holds the table for everybody. "When it's
            // an action card, a random player gets it" is what that comes to
            // for the ordinary single-answer case.
            ctx.emit(GameEvent.Timeout(playerId))
            var filled: PendingAction = pending
            for (absent in pending.waitingOn) {
                filled = filled.copy(answers = filled.answers + (absent to answerForAbsent(ctx, filled, absent)))
            }
            ctx.state = ctx.state.copy(pendingAction = filled)
            resolvePendingAction(ctx, filled)
            return
        }

        if (state.forcedDraws != null || state.dealQueue.isNotEmpty()) return
        val current = state.currentPlayer ?: return
        if (current.id != playerId || current.status != PlayerStatus.ACTIVE) return

        // "When the timer is gone, the user just goes out" — even with an empty hand.
        ctx.emit(GameEvent.Timeout(playerId))
        ctx.update(playerId) { it.copy(status = PlayerStatus.STAYED) }
        ctx.emit(GameEvent.Stay(playerId))
        advanceAndCheck(ctx)
    }

    // ═══════════════════════════════════════════
    // Drawing
    // ═══════════════════════════════════════════

    /**
     * Places a freshly drawn card and resolves everything it triggers. This is
     * the single path every draw goes through — opening deal, voluntary hit,
     * forced draw and the double-draw house rule alike.
     */
    private fun resolveDrawnCard(ctx: Ctx, playerId: String, card: Card): DrawOutcome {
        ctx.emit(GameEvent.Draw(playerId, card))
        return when (card.kind) {
            CardKind.PASSIVE -> resolvePassive(ctx, playerId, card)
            CardKind.ACTION -> resolveAction(ctx, playerId, card)
            CardKind.NUMBER -> resolveNumber(ctx, playerId, card)
        }
    }

    private fun resolvePassive(ctx: Ctx, playerId: String, card: Card): DrawOutcome {
        // "Womp womp": whatever you pick up, someone else keeps.
        if (ctx.rules.passivesToRandomOther) {
            val others = ctx.activePlayers().filter { it.id != playerId }
            val receiver = ctx.rng.pick(others)
            if (receiver != null) {
                ctx.update(receiver.id) { it.copy(passives = it.passives + card) }
                ctx.emit(GameEvent.PassiveGained(receiver.id, card))
                return DrawOutcome.CONTINUE
            }
        }

        // Flip 7: a second "second chance" must be passed on, never hoarded.
        if (card.defId == SECOND_LIFE.id && ctx.hasPassive(playerId, SECOND_LIFE.id)) {
            val receiver = ctx.rng.pick(
                ctx.activePlayers().filter { it.id != playerId && it.passives.none { p -> p.defId == SECOND_LIFE.id } },
            )
            if (receiver != null) {
                ctx.update(receiver.id) { it.copy(passives = it.passives + card) }
                ctx.emit(GameEvent.SecondChancePassed(playerId, receiver.id))
                ctx.emit(GameEvent.PassiveGained(receiver.id, card))
            } else {
                ctx.toDiscard(card)
            }
            return DrawOutcome.CONTINUE
        }

        ctx.update(playerId) { it.copy(passives = it.passives + card) }
        ctx.emit(GameEvent.PassiveGained(playerId, card))
        return DrawOutcome.CONTINUE
    }

    private fun resolveAction(ctx: Ctx, playerId: String, card: Card): DrawOutcome {
        val def = Catalog.action(card.defId)
        if (def == null) {
            ctx.toDiscard(card)
            return DrawOutcome.CONTINUE
        }

        // "Womp womp" points every card at its drawer, which is the same shape
        // as a card that was self-targeting to begin with.
        val resolvesOnDrawer = def.selfTarget || ctx.rules.forceSelfTarget

        // A card that points at cards has to stop and ask however it targets:
        // "womp womp" can force whose seat it resolves on, never which cards.
        val cards = if (def.picksCards) def.cardTargets(ctx.state, playerId) else emptyList()
        if (def.picksCards && cards.size < def.picks) return fizzle(ctx, def, card, playerId)

        // A card that needs no pick resolves on the spot — unless it asks a
        // question, and only the drawer can answer that.
        if (resolvesOnDrawer && !def.needsChoice && !def.picksCards) {
            // A card whose whole effect is a mark the drawer already carries
            // would be spent for nothing. Bin it and deal a replacement, the
            // same way a card with nobody to hit is binned below.
            if (def.skipMarked?.let { ctx.hasMark(playerId, it) } == true) {
                return fizzle(ctx, def, card, playerId)
            }
            runAction(ctx, def, card, playerId, playerId, choice = null)
            return when {
                ctx.player(playerId)?.status == PlayerStatus.BUST -> DrawOutcome.BUSTED
                ctx.state.forcedDraws != null || ctx.state.pendingAction != null -> DrawOutcome.PAUSED
                anyFlip7(ctx) != null -> DrawOutcome.FLIP7
                else -> DrawOutcome.CONTINUE
            }
        }

        // A steal with nobody holding cards — most often on the opening deal —
        // has nothing it could possibly do. Rather than parking the table on a
        // pick that changes nothing, bin it and deal a replacement.
        val targets = if (resolvesOnDrawer) listOf(playerId) else def.validTargets(ctx.state, playerId)
        if (targets.isEmpty()) return fizzle(ctx, def, card, playerId)

        ctx.state = ctx.state.copy(
            pendingAction = PendingAction(
                cardDefId = def.id,
                playerId = playerId,
                card = card,
                validTargets = targets,
                options = def.options,
                kind = def.pickKind,
                validCards = cards,
                picks = def.picks,
            ),
        )
        return DrawOutcome.PAUSED
    }

    /** Discards a card that cannot do anything and gives the drawer another one. */
    private fun fizzle(ctx: Ctx, def: ActionCardDef, card: Card, playerId: String): DrawOutcome {
        ctx.toDiscard(card)
        ctx.emit(GameEvent.Fizzled(def.id, playerId))
        if (ctx.player(playerId)?.status != PlayerStatus.ACTIVE) return DrawOutcome.CONTINUE
        ctx.pushForcedDraws(playerId, 1)
        return DrawOutcome.PAUSED
    }

    private fun resolveNumber(ctx: Ctx, playerId: String, card: Card): DrawOutcome {
        ctx.update(playerId) { p ->
            val hand = p.hand + card
            p.copy(hand = hand, handValue = hand.sumOf { it.value })
        }
        val player = ctx.player(playerId) ?: return DrawOutcome.CONTINUE

        val bust = ctx.checkBust(player)
        if (bust != null) {
            val duplicate = bust.duplicate
            // Second chance only ever covers duplicates, never a threshold bust.
            if (bust.reason == Ctx.BUST_DUPLICATE && duplicate != null && ctx.hasPassive(playerId, SECOND_LIFE.id)) {
                ctx.consumeSecondChance(playerId, duplicate, bust.matched)
            } else {
                ctx.bust(playerId, bust.reason, duplicate ?: card, bust.matched)
                return DrawOutcome.BUSTED
            }
        }

        val after = ctx.player(playerId) ?: return DrawOutcome.CONTINUE
        if (canFlip(ctx, after)) return DrawOutcome.FLIP7
        return DrawOutcome.CONTINUE
    }

    /**
     * Whether this hand ends the round right now. "Just one more" takes the flip
     * off the table for its holder, so the count keeps climbing past the target
     * and only a duplicate can stop it.
     */
    private fun canFlip(ctx: Ctx, player: Player): Boolean =
        player.hand.size >= ctx.rules.flipTarget && NO_FLIP.id !in player.marks

    // ═══════════════════════════════════════════
    // Action cards
    // ═══════════════════════════════════════════

    /**
     * Takes one responder's answer. Most prompts have exactly one and resolve on
     * the spot; the ones that ask the table at once collect until everybody is
     * in and only then resolve, so nobody can answer in reply to somebody else.
     */
    private fun playPendingAction(ctx: Ctx, action: GameAction.PlayAction) {
        val pending = ctx.state.pendingAction ?: return
        if (pending.cardDefId != action.cardDefId) return
        val actor = action.fromPlayerId
        // Somebody who was never asked, or who has already answered — a second
        // click, or a client trying to answer for a neighbour.
        if (actor !in pending.respondents || actor in pending.answers) return

        val answer = Answer(action.targetPlayerId, action.choice, action.cards)
        val collected = pending.copy(answers = pending.answers + (actor to answer))
        ctx.state = ctx.state.copy(pendingAction = collected)
        if (!collected.allAnswered) return

        resolvePendingAction(ctx, collected)
    }

    /**
     * Fills in whatever a responder never said, so a prompt cannot be left open
     * by somebody who walked away. Everything is picked from what that player
     * was actually offered, so an answer nobody gave is still a legal one.
     */
    private fun answerForAbsent(ctx: Ctx, pending: PendingAction, playerId: String): Answer {
        // A card that asked a question has to have one answered for it, or the
        // clock runs out again on the same unflipped coin, forever.
        val choice = ctx.rng.pick(pending.options)
        // Shuffled rather than taken off the top, so a clock that runs out does
        // not always trade the same two seats' first cards — or buy the same
        // cheapest thing on the shelf every single time.
        val cards = when (pending.kind) {
            PickKind.CARD -> ctx.rng.shuffled(pending.validCards)
            PickKind.CATALOG -> listOfNotNull(ctx.rng.pick(pending.offers)?.id)
            PickKind.PLAYER -> emptyList()
        }
        // Somebody other than themselves where there is a choice, the way a
        // person would.
        val target = ctx.rng.pick(pending.validTargets.filter { it != playerId })
            ?: pending.validTargets.firstOrNull()
            ?: playerId
        return Answer(targetId = target, choice = choice, cards = cards)
    }

    private fun resolvePendingAction(ctx: Ctx, pending: PendingAction) {
        val fromId = pending.playerId
        val own = pending.answers[fromId] ?: Answer()
        val requestedTargetId = own.targetId ?: fromId
        val requestedChoice = own.choice
        val requestedCards = own.cards

        val def = Catalog.action(pending.cardDefId)
        ctx.state = ctx.state.copy(pendingAction = null)

        if (def == null) {
            ctx.toDiscard(pending.card)
            afterAction(ctx)
            return
        }

        // "Womp womp" overrides the pick. Otherwise the pick has to be one the
        // card could actually be played on — a client asking for anything else
        // gets the first legal target instead.
        val allowed = when {
            // A prompt raised outside the card's own play brought its own
            // targets. Asking the card's target rule would answer for how it
            // was played, which is a different question — and a stricter filter
            // here would be wrong too: a bomb still goes off on a seat that
            // went out while it was being aimed, and anti flip is asked when
            // the whole table is already out. Only a seat that has left the
            // game entirely is dropped; what to do with the rest is the
            // effect's own business.
            pending.phase != PHASE_PLAY -> pending.validTargets.filter { ctx.player(it) != null }

            ctx.rules.forceSelfTarget -> listOf(fromId)
            else -> def.validTargets(ctx.state, fromId)
        }
        if (allowed.isEmpty()) {
            fizzle(ctx, def, pending.card, fromId)
            afterAction(ctx)
            return
        }
        val resolvedTarget = if (requestedTargetId in allowed) requestedTargetId else allowed.first()

        // An answer the card never offered — or none at all, from a client that
        // does not know the card asks — falls back to the first option, exactly
        // as an illegal target falls back to the first legal seat.
        //
        // Asked of the prompt rather than of the card: a card that asks a
        // question when it is played may ask nothing at all the second time it
        // stops the table, and the prompt is the one that knows which it is.
        val choice = when {
            pending.options.isEmpty() -> null
            requestedChoice in pending.options -> requestedChoice
            else -> pending.options.first()
        }

        val cards = if (!def.picksCards) emptyList() else legalPicks(ctx, def, fromId, requestedCards)
        if (def.picksCards && cards.size < def.picks) {
            fizzle(ctx, def, pending.card, fromId)
            afterAction(ctx)
            return
        }

        runAction(ctx, def, pending.card, fromId, resolvedTarget, choice, cards, pending.phase, pending.answers)
        afterAction(ctx)
    }

    /**
     * The cards a card-picking play actually resolves on.
     *
     * Same contract as an illegal target: a pick the card could not have been
     * pointed at is replaced rather than refused, because refusing strands the
     * table on a prompt only the sender can clear. Two picks on the same seat
     * would trade a hand with itself and change nothing, so each one after the
     * first has to come from an owner not already picked.
     */
    private fun legalPicks(
        ctx: Ctx,
        def: ActionCardDef,
        fromId: String,
        requested: List<String>,
    ): List<Card> {
        val offered = def.cardTargets(ctx.state, fromId).toSet()
        val picked = mutableListOf<Card>()
        val owners = mutableSetOf<String>()

        fun take(cardId: String): Boolean {
            if (cardId !in offered) return false
            if (picked.any { it.id == cardId }) return false
            val owner = ctx.ownerOf(cardId) ?: return false
            if (owner.id in owners) return false
            val card = (owner.hand + owner.passives).firstOrNull { it.id == cardId } ?: return false
            picked += card
            owners += owner.id
            return true
        }

        for (cardId in requested) {
            if (picked.size >= def.picks) break
            take(cardId)
        }
        // Short of a full pick — a client that sent one card, or two off the
        // same seat — the rest is filled in from what was on offer.
        for (cardId in offered) {
            if (picked.size >= def.picks) break
            take(cardId)
        }
        return picked
    }

    private fun runAction(
        ctx: Ctx,
        def: ActionCardDef,
        card: Card?,
        fromId: String,
        targetId: String,
        choice: String?,
        cards: List<Card> = emptyList(),
        phase: String = PHASE_PLAY,
        answers: Map<String, Answer> = emptyMap(),
    ) {
        card?.let { ctx.toDiscard(it) }
        ctx.emit(GameEvent.ActionPlayed(def.id, fromId, targetId))
        // "Double it!" fires the same effect twice.
        repeat(ctx.rules.actionRepeat) {
            val from = ctx.player(fromId) ?: return@repeat
            val target = ctx.player(targetId) ?: return@repeat
            def.onPlay(
                ctx,
                Play(
                    from = from, target = target, choice = choice,
                    cards = cards, phase = phase, answers = answers,
                ),
            )
        }
    }

    private fun afterAction(ctx: Ctx) {
        anyFlip7(ctx)?.let {
            endRoundByFlip7(ctx, it)
            advanceAndCheck(ctx)
            return
        }
        if (ctx.state.forcedDraws != null || ctx.state.pendingAction != null) return
        advanceAndCheck(ctx)
    }

    // ═══════════════════════════════════════════
    // Forced draws
    // ═══════════════════════════════════════════

    private fun forcedDraw(ctx: Ctx) {
        if (ctx.state.pendingAction != null) return
        val forced = ctx.state.forcedDraws
        if (forced == null) {
            advanceAndCheck(ctx)
            return
        }
        processOneForcedDraw(ctx, forced)
        if (ctx.state.pendingAction != null || ctx.state.forcedDraws != null) return
        advanceAndCheck(ctx)
    }

    private fun processOneForcedDraw(ctx: Ctx, forced: ForcedDraws) {
        if (forced.remaining <= 0) {
            ctx.popForcedDraws()
            return
        }
        val target = ctx.player(forced.playerId)
        if (target == null || target.status != PlayerStatus.ACTIVE) {
            ctx.popForcedDraws()
            return
        }

        val card = ctx.drawRaw()
        if (card == null) {
            ctx.popForcedDraws()
            return
        }

        ctx.state = ctx.state.copy(forcedDraws = forced.copy(remaining = forced.remaining - 1))

        when (resolveDrawnCard(ctx, forced.playerId, card)) {
            // Busting out cancels the rest of this player's forced draws.
            DrawOutcome.BUSTED -> ctx.popForcedDraws()
            DrawOutcome.FLIP7 -> endRoundByFlip7(ctx, forced.playerId)
            DrawOutcome.PAUSED -> Unit
            DrawOutcome.CONTINUE ->
                if ((ctx.state.forcedDraws?.remaining ?: 0) <= 0) ctx.popForcedDraws()
        }
    }

    // ═══════════════════════════════════════════
    // Flip 7
    // ═══════════════════════════════════════════

    private fun anyFlip7(ctx: Ctx): String? =
        ctx.state.players.firstOrNull { it.status == PlayerStatus.ACTIVE && canFlip(ctx, it) }?.id

    /**
     * A full set of unique cards ends the round for everyone, right now. Under
     * "flip 9" it ends the game too, but that is recorded the same way every
     * other win is — [gameWinner] picks it up at round end so the summary
     * screen still gets to show before NEXT_ROUND moves to GAME_END.
     */
    private fun endRoundByFlip7(ctx: Ctx, playerId: String) {
        if (ctx.state.flip7PlayerId != null) return
        ctx.emit(GameEvent.Flip7(playerId))
        ctx.clearForcedDraws()
        ctx.state = ctx.state.copy(
            flip7PlayerId = playerId,
            pendingAction = null,
            dealQueue = emptyList(),
            players = ctx.state.players.map {
                if (it.status == PlayerStatus.ACTIVE) it.copy(status = PlayerStatus.STAYED) else it
            },
        )

        // "Anti flip": the round is over for everybody, but it does not score
        // until the player who ended it says what the bonus is for. Nothing
        // moves while a prompt is open, so the scoring simply waits.
        if (ctx.rules.antiFlip && ctx.state.players.size > 1) {
            ctx.raisePrompt(
                defId = ANTI_FLIP_ID,
                playerId = playerId,
                phase = PHASE_FLIP_CHOICE,
                targets = listOf(playerId),
                options = ANTI_FLIP.options,
            )
        }
    }

    // ═══════════════════════════════════════════
    // Turn order
    // ═══════════════════════════════════════════

    private fun allResolved(state: GameState): Boolean =
        state.players.none { it.status == PlayerStatus.ACTIVE }

    private fun advanceAndCheck(ctx: Ctx) {
        if (ctx.state.phase != GamePhase.PLAYING) return
        if (ctx.state.isInterrupted) return
        if (allResolved(ctx.state)) {
            enterRoundEnd(ctx)
            return
        }
        // Mid-deal the room, not the turn order, decides who acts next.
        if (ctx.state.dealQueue.isNotEmpty()) return
        nextActivePlayer(ctx)
        if (allResolved(ctx.state)) enterRoundEnd(ctx)
    }

    private fun nextActivePlayer(ctx: Ctx) {
        val players = ctx.state.players
        val count = players.size
        if (count == 0) return

        for (offset in 1..count) {
            val index = (ctx.state.turnIndex + offset) % count
            val player = ctx.state.players[index]
            if (player.status != PlayerStatus.ACTIVE) continue
            if (player.skipNextTurn) {
                ctx.update(player.id) { it.copy(skipNextTurn = false) }
                continue
            }
            ctx.state = ctx.state.copy(turnIndex = index)
            return
        }

        // Every remaining player was skipping. Their flags are cleared now, so
        // hand the turn to the first of them instead of stalling on someone
        // who is already out.
        val fallback = ctx.state.players.indexOfFirst { it.status == PlayerStatus.ACTIVE }
        if (fallback >= 0) ctx.state = ctx.state.copy(turnIndex = fallback)
    }

    // ═══════════════════════════════════════════
    // Scoring
    // ═══════════════════════════════════════════

    /**
     * Flip 7 scoring order: total the number cards, apply ×2, add the flat
     * modifiers, then the Flip 7 bonus. Busting scores nothing.
     */
    fun roundScore(player: Player, flip7PlayerId: String?): Int {
        if (player.status == PlayerStatus.BUST) return 0
        // "Unlucky 7": the hand is only worth something if it went all the way.
        if (MUST_FLIP.id in player.marks && player.id != flip7PlayerId) return 0
        var total = player.hand.sumOf { it.value }
        for (card in player.passives) {
            if (Catalog.passive(card.defId)?.scoring == PassiveScoring.DOUBLE_NUMBERS) total *= 2
        }
        for (card in player.passives) {
            val def = Catalog.passive(card.defId) ?: continue
            if (def.scoring == PassiveScoring.FLAT) total += def.bonusPoints
        }
        if (player.id == flip7PlayerId) total += FLIP7_BONUS
        // "All in": whoever bet the highest or the lowest keeps half of it.
        // Last, so it takes half of everything the round was actually worth.
        if (HALVED.id in player.marks) total /= 2
        return total
    }

    private fun enterRoundEnd(ctx: Ctx) {
        val state = ctx.state
        // Hand scoring first, then anything moved by other means during the
        // round, then the bounty — all of it in the deltas rather than in the
        // banked score, so the summary shows what the round actually paid.
        //
        // A round normally costs a player their whole hand at worst and never
        // puts them in the red. "Extreme" is what lifts that floor.
        val floor = if (ctx.rules.allowsNegative) Int.MIN_VALUE else 0
        val scored = state.players.associate { it.id to roundScore(it, state.flip7PlayerId) }
        val adjusted = scored.mapValues { (id, points) ->
            (points + (state.roundAdjustments[id] ?: 0)).coerceAtLeast(floor)
        }
        val deltas = payBounty(ctx, state, adjusted)

        val winner = state.players
            .filter { it.status != PlayerStatus.BUST }
            .sortedWith(
                compareByDescending<Player> { deltas[it.id] ?: 0 }.thenBy { it.hand.size },
            )
            .firstOrNull()

        val ended = state.copy(
            phase = GamePhase.ROUND_END,
            players = state.players.map { it.copy(score = it.score + (deltas[it.id] ?: 0)) },
            roundDeltas = deltas,
            roundWinnerId = winner?.id,
            pendingAction = null,
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            dealQueue = emptyList(),
        )
        ctx.state = ended
        ctx.emit(GameEvent.RoundScored(deltas, winner?.id))

        // The winner is recorded now but the summary screen still shows first;
        // NEXT_ROUND is what actually moves the game to GAME_END.
        gameWinner(ended, ctx.rules)?.let { ctx.state = ctx.state.copy(gameWinnerId = it) }
    }

    /**
     * "Bounty": the player who came into the round in front is worth something
     * dead. Ranking is on the banked scores — [state] is still the pre-banking
     * snapshot — so the price is on the leader everybody could see all round.
     *
     * Only an outright leader carries a bounty. A tie means nobody is *the*
     * player in front, which also stops round one, where the whole table is on
     * zero, from paying out on the first bust. Because the leader busted, they
     * are already out of the round-winner running, so every player still in it
     * collects the same 10 — the payout cannot reorder the round.
     */
    private fun payBounty(ctx: Ctx, state: GameState, deltas: Map<String, Int>): Map<String, Int> {
        val payout = ctx.rules.bountyPoints
        if (payout <= 0 || state.players.size < 2) return deltas
        val ranked = state.players.sortedByDescending { it.score }
        val leader = ranked[0]
        if (ranked[1].score == leader.score) return deltas
        if (leader.status != PlayerStatus.BUST) return deltas

        val collectors = state.players.map { it.id }.filterNot { it == leader.id }
        ctx.emit(GameEvent.BountyPaid(leader.id, collectors, payout))
        return deltas.mapValues { (id, points) -> if (id == leader.id) points else points + payout }
    }

    private fun gameWinner(state: GameState, rules: RuleSet): String? {
        // "Flip 9" is a knockout: getting there takes the game on the spot,
        // whatever the scoreboard says.
        if (rules.flipWinsGame && state.flip7PlayerId != null) return state.flip7PlayerId
        val config = state.config
        if (config.winCondition == WinCondition.FIRST_TO_SCORE) {
            if (state.players.none { it.score >= config.targetScore }) return null
            // Several players can cross the line in the same round — highest wins.
            return state.players.maxByOrNull { it.score }?.id
        }
        if (state.round < config.totalRounds) return null
        return state.players.maxByOrNull { it.score }?.id
    }

    private fun nextRound(ctx: Ctx) {
        val state = ctx.state
        if (state.phase != GamePhase.ROUND_END) return

        if (state.gameWinnerId != null) {
            ctx.state = state.copy(phase = GamePhase.GAME_END)
            return
        }

        // Everything on the table goes back to the discard pile, minus the
        // cards that were minted mid-round and never belonged to the deck.
        val returned = state.players
            .flatMap { it.hand + it.passives }
            .filterNot { it.isEphemeral }

        val players = state.players.map {
            it.copy(
                hand = emptyList(), passives = emptyList(), handValue = 0,
                status = PlayerStatus.ACTIVE, bustReason = null, skipNextTurn = false,
                marks = emptySet(),
            )
        }

        val nextStart = (state.roundStartPlayer + 1) % players.size
        ctx.state = state.copy(
            phase = GamePhase.PLAYING,
            round = state.round + 1,
            players = players,
            discard = state.discard + returned,
            turnIndex = nextStart,
            roundStartPlayer = nextStart,
            roundWinnerId = null,
            flip7PlayerId = null,
            roundDeltas = emptyMap(),
            roundAdjustments = emptyMap(),
            pendingAction = null,
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            dealQueue = dealOrder(players.map { it.id }, nextStart),
        )
    }
}
