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

/**
 * How many seats a table has.
 *
 * The client is told this in the catalog rather than knowing it, and the felt
 * lays its seats out on an arc worked out from how many there are — so this is
 * the only place the number lives, and moving it moves the table.
 */
const val MAX_PLAYERS = 10

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

    // ─── Queries ───

    fun player(id: String): Player? = state.player(id)

    fun hasPassive(playerId: String, defId: String): Boolean =
        player(playerId)?.passives?.any { it.defId == defId } == true

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

    /**
     * An id for a card that was never dealt — a prompt's stand-in, a bought
     * card, an effect card. `tmp-` is what [Card.isEphemeral] reads, and it is
     * what keeps these out of the discard pile and off the table at the end of
     * the round.
     *
     * The counter is [GameState.minted] rather than a field on this object,
     * because this object is one transition and a game is many of them. It used
     * to start again at nought every transition, so two ×2s minted a turn apart
     * were both `tmp-doublePoints-0` — and anything keying on a card id then had
     * two cards it could not tell apart. That is what stopped a second mutate
     * from ever opening its shop: the client still had the answer it had given
     * the first one, filed under the same id.
     */
    fun mint(prefix: String): String {
        val next = state.minted
        state = state.copy(minted = next + 1)
        return "tmp-$prefix-$next"
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
     * The card is spent as it fires, so a table of bombers taking each other
     * out terminates: each one goes off once. When a prompt is already open the
     * bomb cannot stop the table again — that bust happened while another was
     * being answered — so it picks for itself rather than being lost.
     */
    private fun detonate(playerId: String) {
        if (consumePassive(playerId, BOMBER.id) == null) return
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
        oneCardPerSeat: Boolean = true,
        offers: List<Offer> = emptyList(),
    ) {
        if (targets.isEmpty() && options.isEmpty() && cards.isEmpty() && offers.isEmpty()) return
        // Minted into a local first: `mint` moves the state on, and a `copy` on
        // the receiver read before it would throw the new counter away.
        val stand = Card(
            id = mint(defId),
            kind = CardKind.ACTION,
            label = defId,
            value = 0,
            defId = defId,
        )
        state = state.copy(
            pendingAction = PendingAction(
                cardDefId = defId,
                playerId = playerId,
                card = stand,
                validTargets = targets,
                options = options,
                kind = kind,
                validCards = cards,
                picks = picks,
                oneCardPerSeat = oneCardPerSeat,
                phase = phase,
                responders = responders,
                offers = offers,
            ),
        )
    }

    /**
     * Announces a card now and applies it in a moment — see [PendingOutcome].
     *
     * For a card that *is* its animation: emit the event that describes what
     * happened, hand the outcome to this, and the table gets to watch the coin
     * come down before it is told what the coin did. The room steps it once the
     * client says the animation is over, which is the same gate everything else
     * already waits on.
     */
    fun land(
        defId: String,
        playerId: String,
        targetId: String,
        result: String? = null,
        choice: String? = null,
        targets: List<String> = emptyList(),
    ) {
        state = state.copy(
            pendingOutcomes = state.pendingOutcomes + PendingOutcome(
                cardDefId = defId,
                playerId = playerId,
                targetId = targetId,
                targetIds = targets,
                result = result,
                choice = choice,
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
            // Priced at nothing means it is not for sale. Nobody would buy a
            // discordia, and a shop that offered one would be offering a way to
            // hurt yourself for free.
            if (def.price <= 0 || def.price > purse) continue
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
        val card = offer.card.copy(id = mint("buy"))
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
     * Moves points from one player to another mid-round.
     *
     * Both halves ride in [GameState.roundAdjustments], so a transfer shows up
     * on the summary as a line rather than as two scores that quietly moved,
     * and — with "extreme" off — the floor at scoring time is what stops it
     * putting anybody in the red. The event is only the announcement; the
     * points have already moved by the time it goes out.
     */
    fun transferPoints(fromId: String, toId: String, points: Int) {
        if (points <= 0 || fromId == toId) return
        if (player(fromId) == null || player(toId) == null) return
        adjust(fromId, -points)
        adjust(toId, points)
        // Recorded again, on its own, so the scoring floor knows how much of
        // this round was taken rather than simply not earned.
        val owed = (state.roundTolls[fromId] ?: 0) - points
        state = state.copy(roundTolls = state.roundTolls + (fromId to owed))
        emit(GameEvent.PointsTransferred(fromId, toId, points))
    }

    /**
     * Hands a player one of the effect cards — see [NO_FLIP] and the rest.
     * Giving one twice is a no-op rather than an error: "double it!" fires
     * every effect twice, and the second one has nothing left to do.
     */
    fun grantEffect(playerId: String, defId: String) {
        if (hasPassive(playerId, defId)) return
        grantEphemeralPassive(playerId, defId)
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

    /**
     * Takes one card at random off another player — hand or modifier row alike,
     * and it lands in whichever of the two it belongs in.
     *
     * Everything on the table is a card, so a steal reaches all of it. That
     * includes the cards nobody wants: reach into the hand of the player
     * carrying a discordia and you may come away with the discordia. Which is
     * the card doing its job — a seat holding something horrible is a seat worth
     * attacking and worth being careful about, and both of those at once is the
     * most interesting a target can be.
     */
    fun stealRandom(fromId: String, toId: String): Card? {
        val from = player(fromId) ?: return null
        player(toId) ?: return null
        val card = rng.pick(from.hand + from.passives) ?: return null
        val isNumber = card.kind == CardKind.NUMBER
        update(fromId) { p ->
            withHand(
                p.copy(passives = p.passives.filterNot { it.id == card.id }),
                p.hand.filterNot { it.id == card.id },
            )
        }
        update(toId) { p ->
            if (isNumber) withHand(p, p.hand + card) else p.copy(passives = p.passives + card)
        }
        emit(GameEvent.Steal(fromId, toId, card))
        return card
    }

    /**
     * Moves one named card from one seat to another — hand or modifier row
     * alike, and it lands in whichever of the two it belongs in.
     *
     * [stealRandom] is this with the choosing done by the dice, and [swapCards]
     * is two of these crossing. What is different about a card that is *handed
     * over* is only who picked it — see the two circlejerks, where one of them
     * is the giver's choice and the other is the taker's — so the event is the
     * same one a steal sends: a card crossing the table is a card crossing the
     * table, and the client draws it the same way whoever asked for it.
     *
     * Returns the card, or null when nobody was holding it. The caller re-checks
     * whoever received it: a hand that took on a card can be holding a duplicate
     * now, which is half the reason to play either card.
     */
    fun handOver(fromId: String, toId: String, cardId: String): Card? {
        if (fromId == toId) return null
        val from = player(fromId) ?: return null
        player(toId) ?: return null
        val card = (from.hand + from.passives).firstOrNull { it.id == cardId } ?: return null
        update(fromId) { p ->
            withHand(
                p.copy(passives = p.passives.filterNot { it.id == card.id }),
                p.hand.filterNot { it.id == card.id },
            )
        }
        update(toId) { p ->
            if (card.kind == CardKind.NUMBER) withHand(p, p.hand + card) else p.copy(passives = p.passives + card)
        }
        emit(GameEvent.Steal(fromId, toId, card))
        return card
    }

    /**
     * Slides everything in front of every seat one place around the table, in
     * seat order — the hand and the modifier row both. "right" moves each of
     * them to the next seat, "left" to the previous one.
     *
     * The row travels with the hand for the same reason [swapHands] takes it:
     * everything in this game is a card, so what is in front of you is one
     * thing rather than two piles that some cards reach and others do not. A
     * spin that left the ×2 and the antimatter behind was picking and choosing
     * which cards count — and it was already a lie on screen, because the
     * client draws the row inside the hand it slides.
     *
     * Every seat takes part, whatever became of its round. A busted hand is
     * still a hand, and pushing one onto the player in front — who is holding
     * the round they had already banked — is the reason to play this card at
     * all. It costs the busted seat nothing and it can cost everybody else the
     * round, which is exactly the trade the card is offering.
     *
     * Whole hands move intact, so nobody is handed a card that clashes with one
     * they kept — but the hand that arrives can be over the threshold, or be a
     * busted hand holding the duplicate that killed it. What protects it moves
     * too: the cooler that made a duplicate survivable and the second life that
     * would have saved the seat both go with the hand rather than staying to
     * cover whatever arrives. The caller re-checks every seat this returns.
     */
    fun rotateHands(direction: String): List<String> {
        val participants = state.players
        if (participants.size < 2) return emptyList()
        // Both piles are read before anything moves, so the rotation happens all
        // at once rather than cascading through the seats one at a time.
        val hands = participants.map { it.hand }
        val rows = participants.map { it.passives }
        val size = participants.size
        for (index in participants.indices) {
            // Who this seat receives from: the seat behind it when spinning
            // right, the seat ahead of it when spinning left.
            val donor = if (direction == SPIN_LEFT) (index + 1) % size else (index + size - 1) % size
            update(participants[index].id) { withHand(it.copy(passives = rows[donor]), hands[donor]) }
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

    /**
     * Trades everything two players are holding — the hand and the modifier row
     * both. Everything in this game is a card, so what is in front of you is
     * one thing rather than two piles that some cards reach and others do not.
     *
     * Returns both seats for the caller to re-check.
     */
    fun swapHands(aId: String, bId: String): List<String> {
        val a = player(aId) ?: return emptyList()
        val b = player(bId) ?: return emptyList()
        if (a.id == b.id) return emptyList()
        update(aId) { withHand(it.copy(passives = b.passives), b.hand) }
        update(bId) { withHand(it.copy(passives = a.passives), a.hand) }
        emit(GameEvent.Swap(aId, bId))
        return listOf(aId, bId)
    }

    /** Spends a passive: it leaves the player and lands on the discard pile. */
    /**
     * Writes a player's banked score outright.
     *
     * The scoreboard, not the round — [adjust] rides in `roundAdjustments` until
     * the round is scored, and a card that says "everything you have, gone" has
     * to mean the total on the board rather than what this round was going to
     * pay. [swapScores] is the only other thing in the engine that reaches this
     * far, and for the same reason.
     */
    fun setScore(playerId: String, score: Int) {
        update(playerId) { it.copy(score = score) }
    }

    /** Shuffles what is left of the deck. Deliberately does not fold the discard
     * pile back in: that is [drawRaw]'s business when the deck runs dry, and
     * doing it here would change how many cards are left to draw, which is not
     * what anybody playing this card asked for. */
    fun shuffleDeck() {
        state = state.copy(deck = rng.shuffled(state.deck))
        emit(GameEvent.DeckShuffled(state.deck.size))
    }

    /**
     * Lifts the first gambler card out of the deck, or out of the discard pile
     * if the deck has none left.
     *
     * Moved, never minted — a gambler card is a real card off the same deck as
     * everything else, and conjuring one would put the game one card up on
     * itself for the rest of the evening. Null when there is genuinely none
     * anywhere, which the caller has to have an answer for.
     */
    fun takeGamblerFromDeck(): Card? {
        val fromDeck = state.deck.indexOfFirst { it.kind == CardKind.GAMBLER }
        if (fromDeck >= 0) {
            val card = state.deck[fromDeck]
            state = state.copy(deck = state.deck.filterIndexed { i, _ -> i != fromDeck })
            return card
        }
        val fromDiscard = state.discard.indexOfFirst { it.kind == CardKind.GAMBLER }
        if (fromDiscard >= 0) {
            val card = state.discard[fromDiscard]
            state = state.copy(discard = state.discard.filterIndexed { i, _ -> i != fromDiscard })
            return card
        }
        return null
    }

    /**
     * Puts a gambler card into a player's hidden hand, or into the discard pile
     * when there is no room for it. Returns whether it was kept.
     */
    fun giveGambler(playerId: String, card: Card): Boolean {
        val player = player(playerId) ?: return false
        if (player.gamblers.size >= Engine.gamblerLimitFor(state, playerId)) {
            emit(GameEvent.GamblerDrawn(playerId, card, kept = false))
            toDiscard(card)
            return false
        }
        update(playerId) { it.copy(gamblers = it.gamblers + card) }
        emit(GameEvent.GamblerDrawn(playerId, card))
        return true
    }

    /**
     * Moves points on the scoreboard itself, now.
     *
     * Not [adjust]. An adjustment rides in `roundAdjustments` until the round is
     * scored, and between rounds there is no round to score — a purchase held
     * there would land at the end of the *next* one, the number the player is
     * reading while they shop would be a lie until then, and the scoring floor
     * would quietly refund it. [swapScores] is the only other thing in the
     * engine that reaches this far, and for the same reason.
     *
     * Silent, the way [adjust] is: every caller says what it was for.
     */
    fun bank(playerId: String, delta: Int) {
        if (delta == 0) return
        update(playerId) { it.copy(score = it.score + delta) }
    }

    /** A card anywhere on the table, by id. */
    fun cardById(cardId: String): Card? =
        state.players.firstNotNullOfOrNull { p -> (p.hand + p.passives).firstOrNull { it.id == cardId } }
            ?: state.deck.firstOrNull { it.id == cardId }
            ?: state.discard.firstOrNull { it.id == cardId }

    /**
     * Lifts a card out of the deck and puts it back on top of it.
     *
     * What "stacked deck" comes to once the reordering is a single choice: you
     * are shown the next few and say which of them comes next.
     */
    fun putOnTopOfDeck(cardId: String) {
        val rest = state.deck.toMutableList()
        val index = rest.indexOfFirst { it.id == cardId }
        if (index < 0) return
        val card = rest.removeAt(index)
        state = state.copy(deck = listOf(card) + rest)
    }

    // ─── Answering a card with a card ───
    //
    // A counter's own frame has already been taken off the stack by the time its
    // effect runs, so what it is answering is simply whatever is on top now.
    // That is the only thing these three need to know, which is why none of them
    // takes a frame.

    /** The card this one was played in answer to, if there is one. */
    fun answeredFrame(): StackFrame? = state.responseStack.lastOrNull()

    /**
     * Stops the card underneath this one.
     *
     * [spent] is the whole difference between the two commons that do it:
     * nullify takes a card off the table for good, and "nahhh" only buys a round
     * — the card goes back to the hand it came out of and can be played again.
     */
    fun cancelAnsweredFrame(spent: Boolean) {
        val stack = state.responseStack
        val below = stack.lastOrNull() ?: return
        state = state.copy(
            responseStack = stack.dropLast(1) + below.copy(cancelled = true, returned = !spent),
        )
    }

    /**
     * Plays a copy of the card underneath this one, for [byPlayerId].
     *
     * The copy is minted rather than found: it is a card that never existed, and
     * `Card.isEphemeral` keeps it out of the discard pile so the deck is not one
     * card up on itself afterwards. It goes on the stack like anything else, so
     * a copy can be nullified in its own right — which is the stack being a
     * stack rather than a special case.
     *
     * It keeps the original's target, unless the card resolves on whoever played
     * it, in which case it resolves on the copier. Copying "shuffle" shuffles;
     * copying a card aimed at somebody hits the same somebody, twice.
     */
    fun copyAnsweredFrame(byPlayerId: String) {
        val frame = state.responseStack.lastOrNull() ?: return
        val def = Catalog.gambler(frame.cardDefId) ?: return
        val copy = Card(
            id = mint("copy"),
            kind = CardKind.GAMBLER,
            label = def.name,
            value = 0,
            defId = def.id,
        )
        emit(GameEvent.GamblerPlayed(byPlayerId, copy))
        Engine.pushFrame(
            this,
            def,
            copy,
            byPlayerId,
            if (def.selfTarget) byPlayerId else frame.targetId,
            frame.choice,
            frame.cards,
        )
    }

    /** Points the card underneath this one somewhere else — see "deflect". */
    fun retargetAnsweredFrame(to: String) {
        val stack = state.responseStack
        val below = stack.lastOrNull() ?: return
        if (player(to) == null) return
        state = state.copy(responseStack = stack.dropLast(1) + below.copy(targetId = to))
        emit(GameEvent.GamblerDeflected(below.playerId, to, below.card))
    }

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
            id = mint(defId),
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
     * (a steal, a swap, a spin). Returns true if the player busted.
     *
     * [finishedToo] reaches a seat that is already out. A whole hand can land on
     * one now — see [rotateHands] — and a banked hand holding two of the same
     * card is a bust however quietly it came by them.
     */
    fun resolveBustAfterGain(playerId: String, finishedToo: Boolean = false): Boolean {
        val player = player(playerId) ?: return false
        if (player.status == PlayerStatus.BUST) return false
        if (player.status != PlayerStatus.ACTIVE && !finishedToo) return false
        val result = checkBust(player) ?: return false
        if (result.reason == BUST_DUPLICATE && result.duplicate != null && hasPassive(playerId, SECOND_LIFE.id)) {
            consumeSecondChance(playerId, result.duplicate, result.matched)
            return false
        }
        bust(playerId, result.reason, result.duplicate, result.matched)
        return true
    }

    fun consumeSecondChance(playerId: String, duplicate: Card, matched: Card? = null) {
        // Kept rather than dropped: the card is off the modifier row and into
        // the discard pile by the end of this line, and the state that goes out
        // with the event is the state after it. Nothing downstream could name
        // what was spent unless the event carries it.
        val saver = consumePassive(playerId, SECOND_LIFE.id)
        discardFromHand(playerId, duplicate)
        emit(GameEvent.SecondChance(playerId, duplicate, matched, saver))
    }

    // ─── Bust rules ───

    data class BustResult(val reason: String, val duplicate: Card? = null, val matched: Card? = null)

    fun checkBust(player: Player): BustResult? {
        // "The cooler revive" hands a hand back with its duplicates in it, so
        // its holder cannot be busted by one for the rest of the round —
        // otherwise `resolveBustAfterGain` would bust them again the instant
        // anything touched their hand, and the card would do nothing at all.
        val coolerHeld = player.passives.any { it.defId == COOLER.id }
        val seen = mutableMapOf<String, Card>()
        for (card in player.hand) {
            val clash = seen.put(card.label, card)
            if (clash != null && !coolerHeld) return BustResult(BUST_DUPLICATE, card, clash)
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
            is GameAction.PlayGambler -> playGambler(ctx, action.playerId, action.cardId)
            is GameAction.PassResponse -> passResponse(ctx, action.playerId)
            is GameAction.Buy -> buy(ctx, action.playerId, action.offerId)
            is GameAction.FinishShopping -> finishShopping(ctx, action.playerId, action.bid)
            GameAction.CloseInterlude -> closeInterlude(ctx)
            GameAction.OpenRound -> openRound(ctx)
            GameAction.ForcedDraw -> forcedDraw(ctx)
            GameAction.ResolveOutcome -> resolveOutcome(ctx)
            is GameAction.Timeout -> timeout(ctx, action.playerId)
            GameAction.NextRound -> nextRound(ctx)
            GameAction.PlayAgain -> playAgain(ctx)
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
            interlude = null,
            responseStack = emptyList(),
            pendingAction = null,
            pendingOutcomes = emptyList(),
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            roundWinnerId = null,
            gameWinnerId = null,
            flip7PlayerId = null,
            roundDeltas = emptyMap(),
            roundAdjustments = emptyMap(),
            roundTolls = emptyMap(),
            players = state.players.map {
                it.copy(
                    hand = emptyList(), passives = emptyList(), handValue = 0,
                    // The gambler hand survives a round; it must not survive a
                    // *game*. A new game builds a new deck, so a card carried
                    // over from the last one would exist twice before anybody
                    // had taken a turn.
                    gamblers = emptyList(),
                    status = PlayerStatus.ACTIVE, score = 0, bustReason = null, skipNextTurn = false,
                )
            },
            dealQueue = dealOrder(state.players.map { it.id }, 0),
        )
    }

    /**
     * Takes a finished game back to its lobby with the same table still sitting
     * at it — see [GameAction.PlayAgain].
     *
     * Built out of [newGame] rather than by copying the finished state and
     * clearing the fields that must not survive. A copy keeps whatever is added
     * to [GameState] next, and "the field nobody remembered to clear" is the
     * exact bug this function exists to not have: `startGame` right above has a
     * page of them written out by hand and is only correct because it runs on a
     * state that was never played. Everything carried over here is carried over
     * on purpose, and there are three of them.
     *
     * The seats are rebuilt from the constructor for the same reason. [Player]
     * has a dozen fields and all but three of them — who you are, what you are
     * called, and whether anybody is home — want their default: a score, a
     * status, a bust reason and a hand from last game are all things a new game
     * must not start with.
     */
    private fun playAgain(ctx: Ctx) {
        val state = ctx.state
        if (state.phase != GamePhase.GAME_END) return

        ctx.state = newGame(state.config).copy(
            // A seat whose tab has gone is not carried into the next game. It
            // would be dealt cards nobody plays and hold the turn clock for its
            // whole length every round — `deadlineFor` asks whether a seat is a
            // bot, not whether anybody is behind it — and it would count
            // against the room filling up. Same rule the shop already
            // uses in `GameState.waitingOnShop`: a tab that closed cannot press
            // a button. Somebody who comes back walks into a lobby and simply
            // sits down again.
            players = state.players
                .filter { it.isBot || it.connected }
                .map { Player(id = it.id, name = it.name, isBot = it.isBot) },
            // Two counters that must keep climbing for the life of the room
            // rather than for the life of a game. Nothing minted survives a
            // restart, but an id handed out twice is a card that two things
            // believe they are holding, and that is not a bug worth risking to
            // save a number — see [Ctx.mint] and [GameState.stackCounter].
            minted = state.minted,
            stackCounter = state.stackCounter,
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

        // "Draw 2", spent at the top of the turn it was played for rather than
        // when it was played: the card promises a card *next turn*, and a turn
        // is the only moment that can honestly be called that.
        val extra = if (ctx.consumePassive(playerId, DRAW_TWO_ARMED.id) != null) 1 else 0

        var drawn = 0
        while (drawn < ctx.rules.drawsPerTurn + extra) {
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
        if (!canStay(ctx.state, current)) return
        ctx.update(playerId) { it.copy(status = PlayerStatus.STAYED) }
        ctx.emit(GameEvent.Stay(playerId))
        advanceAndCheck(ctx)
    }

    /**
     * What the number cards in front of this player are worth *to them* — the
     * same total the seat has always shown, with its sign.
     *
     * [Player.handValue] is the physical sum and stays that way: it is what the
     * bust threshold counts, and a hand cannot stop being twenty-two just
     * because it is worth minus twenty-two. So the seat is told the signed
     * figure separately rather than the client working out which cards turn a
     * total over — see [ANTIMATTER], and `GameStateView.handWorth`.
     */
    fun handWorth(player: Player): Int {
        val total = player.hand.sumOf { it.value }
        return if (negatesHand(player)) -total else total
    }

    /**
     * Whether every number in front of this player counts the wrong way — see
     * [ANTIMATTER].
     *
     * Four things read this and all four have to give the same answer, which is
     * why it is one function rather than the same `any` written out four times:
     * what the seat shows ([handWorth]), what the round pays ([roundScore]), how
     * far down that round may leave them ([floorFor]), and whether busting is a
     * way out of it at all.
     */
    fun negatesHand(player: Player): Boolean =
        player.passives.any { Catalog.passive(it.defId)?.scoring == PassiveScoring.NEGATE }

    /**
     * Whether a bust still costs this player their hand.
     *
     * A bust writes a round off for everybody else: the hand scatters and the
     * round is worth nothing. An antimatter holder does not get that. The card
     * says you may not stop, and a bust that wiped the debt would make busting
     * the way to stop — draw until the duplicate comes and walk away owing
     * nothing, which is a *better* round than the one the card was pushing you
     * into. So the hole stays: the seat that busts carrying one takes every
     * number in front of it, the one that killed it included.
     *
     * The same card answers this and [negatesHand] today, and it is still two
     * functions: "your numbers are negative" and "your bust is not a way out"
     * are two things to know about a seat, and the view asks the second one.
     * Public for the same reason [canStay] is — the client is told rather than
     * working it out, because a seat struck through has to stop reading
     * "cancelled" for the one player it was not cancelled for.
     */
    fun bustStillCounts(player: Player): Boolean = negatesHand(player)

    /**
     * Whether this player's hand is face down to everybody but themselves — see
     * the "redacted" card.
     *
     * The single place that question is answered, and it has to stay that way:
     * three things read it and all three have to agree — the projection that
     * blanks the cards (`Player.hiddenFrom`), the map that says what each hand
     * is worth, and `Room.redactFor`, which cuts the face out of every event
     * that would otherwise show one. A leak here has no symptom.
     *
     * The modifier row is deliberately not covered. The card doing the hiding
     * lies in it, and a table that could not see *that* would only see a bug —
     * "you cannot read my hand" is a bluff worth having, and "you cannot see
     * why" is not a rule anybody could play against.
     *
     * Only while their round is still running, too. A hand that has gone out is
     * scored in front of everybody, and a bust has to be able to show the card
     * that did it: the seat turns its cards over at exactly the moment every
     * other seat does.
     */
    fun handIsHidden(player: Player): Boolean =
        player.status == PlayerStatus.ACTIVE && player.passives.any { it.defId == REDACTED.id }

    /**
     * Whether a card in front of this player forbids them to stop — see
     * [ANTIMATTER]. Told apart from [canStay] because the two mean different
     * things to the clock: a player who has simply not drawn anything yet is
     * timed out the way they always were, and one who is *forbidden* to stop
     * cannot be let out by saying nothing.
     */
    fun mayNotStop(player: Player): Boolean =
        player.passives.any { Catalog.passive(it.defId)?.allowsStaying == false }

    /**
     * Whether this player may choose to stop.
     *
     * Everyone takes at least one card each round unless the host disabled it —
     * and a player holding an antimatter may not stop at all, whatever else is
     * in front of them. Public because the room's bots have to know too: a bot
     * that kept offering to go out at a table that will not let it would hold
     * the turn for ever.
     */
    fun canStay(state: GameState, player: Player): Boolean {
        if (mayNotStop(player)) return false
        val rules = RuleSet.of(state.config)
        return rules.allowStayWithEmptyHand || player.hand.isNotEmpty() || player.passives.isNotEmpty()
    }

    private fun timeout(ctx: Ctx, playerId: String) {
        val state = ctx.state
        if (state.phase != GamePhase.PLAYING) return

        // A response window shut by the clock. Silence is a pass — there is
        // nothing to fill in, only somebody who did not answer — and everybody
        // outstanding passes at once, because one clock covers the window and a
        // player who walked away must not hold the table for the rest of it.
        val window = state.openResponse
        if (window != null) {
            if (playerId !in window.awaiting) return
            ctx.emit(GameEvent.Timeout(playerId))
            replaceTop(ctx) { it.copy(awaiting = emptyList()) }
            resolveStack(ctx)
            return
        }

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

        // "When the timer is gone, the user just goes out" — even with an empty
        // hand. Unless going out is not something this player may do, in which
        // case the clock takes the only decision they had left and draws for
        // them: waiting quietly must not be a way off a card that says you
        // cannot stop.
        ctx.emit(GameEvent.Timeout(playerId))
        if (mayNotStop(current)) {
            hit(ctx, playerId)
            return
        }
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
    private fun resolveDrawnCard(ctx: Ctx, playerId: String, card: Card, depth: Int = 0): DrawOutcome {
        // Everything but a gambler card is turned face up as it comes off the
        // deck. A gambler card announces itself instead — see [resolveGambler].
        if (card.kind != CardKind.GAMBLER) {
            ctx.emit(GameEvent.Draw(playerId, card))

            // Rolling rules: cards armed earlier that are about *this* card.
            // They are asked here rather than inside a kind, because what makes
            // them worth playing is that they work on whatever comes off the
            // deck. Redirect goes first: it decides whose card this is, and a
            // second opinion is a question you only get about your own.
            if (ctx.hasPassive(playerId, REDIRECT_ARMED.id)) return askRedirect(ctx, playerId, card)
            if (card.kind == CardKind.NUMBER && ctx.hasPassive(playerId, SECOND_OPINION_ARMED.id)) {
                return askSecondOpinion(ctx, playerId, card)
            }
        }

        return placeDrawnCard(ctx, playerId, card, depth)
    }

    /**
     * Puts a card where it goes, having already been announced.
     *
     * Split from [resolveDrawnCard] so a card that was turned over and then
     * argued about — redirected, or thrown back — can be placed without being
     * announced a second time. The table saw it the first time.
     */
    private fun placeDrawnCard(ctx: Ctx, playerId: String, card: Card, depth: Int = 0): DrawOutcome =
        when (card.kind) {
            CardKind.GAMBLER -> resolveGambler(ctx, playerId, card, depth)
            CardKind.PASSIVE -> resolvePassive(ctx, playerId, card)
            CardKind.ACTION -> resolveAction(ctx, playerId, card)
            CardKind.NUMBER -> resolveNumber(ctx, playerId, card)
        }

    /** Stops the table on a drawn card and asks its drawer whether they want it. */
    private fun askRedirect(ctx: Ctx, playerId: String, card: Card): DrawOutcome {
        ctx.consumePassive(playerId, REDIRECT_ARMED.id)
        val others = ctx.activePlayers().filter { it.id != playerId }.map { it.id }
        // Nobody to hand it to — the card was armed when there was, and a round
        // can empty out while it waits. It is simply the drawer's, as it was.
        if (others.isEmpty()) return placeDrawnCard(ctx, playerId, card)

        ctx.state = ctx.state.copy(
            pendingAction = PendingAction(
                cardDefId = REDIRECT_ID,
                playerId = playerId,
                card = card,
                validTargets = others,
                options = listOf(TAKE_IT, PASS_IT_ON),
                phase = PHASE_REDIRECT,
            ),
        )
        return DrawOutcome.PAUSED
    }

    /** ...and asks whether they would rather have the next one. */
    private fun askSecondOpinion(ctx: Ctx, playerId: String, card: Card): DrawOutcome {
        ctx.consumePassive(playerId, SECOND_OPINION_ARMED.id)
        ctx.state = ctx.state.copy(
            pendingAction = PendingAction(
                cardDefId = SECOND_OPINION_ID,
                playerId = playerId,
                card = card,
                validTargets = listOf(playerId),
                options = listOf(KEEP_IT, THROW_IT_BACK),
                phase = PHASE_SECOND_OPINION,
            ),
        )
        return DrawOutcome.PAUSED
    }

    /**
     * Settles a card that was turned over and then argued about.
     *
     * The drawn card is what the prompt was holding, so it is placed rather than
     * discarded — unlike every other prompt, where the card being held is the one
     * that asked the question.
     */
    private fun resolveDrawnCardPrompt(ctx: Ctx, pending: PendingAction) {
        val drawerId = pending.playerId
        val own = pending.answers[drawerId] ?: Answer()
        val card = pending.card
        ctx.state = ctx.state.copy(pendingAction = null)

        when (pending.phase) {
            PHASE_REDIRECT -> {
                val others = pending.validTargets.filter { ctx.player(it) != null && it != drawerId }
                val push = own.choice == PASS_IT_ON && others.isNotEmpty()
                val receiver = if (!push) drawerId else own.targetId?.takeIf { it in others } ?: others.first()
                if (push) ctx.emit(GameEvent.Redirected(drawerId, receiver, card))
                placeDrawnCard(ctx, receiver, card)
            }

            PHASE_SECOND_OPINION -> {
                if (own.choice == THROW_IT_BACK) {
                    ctx.toDiscard(card)
                    ctx.emit(GameEvent.Discard(drawerId, card))
                    // ...and take another, which is the whole of the card.
                    if (ctx.player(drawerId)?.status == PlayerStatus.ACTIVE) ctx.pushForcedDraws(drawerId, 1)
                } else {
                    placeDrawnCard(ctx, drawerId, card)
                }
            }
        }
        afterAction(ctx)
    }

    /**
     * How many gambler cards a draw may chain before the table gives up.
     *
     * A gambler card does not cost you a card, so you draw again — and
     * [Ctx.drawRaw] folds the discard pile back in when the deck runs dry, so a
     * table whose remaining cards are all gambler cards would draw for ever.
     * Sanitising the deck keeps that from being reachable by configuration;
     * this keeps it from being reachable at all.
     */
    private const val MAX_GAMBLER_REDRAWS = 8

    /**
     * How deep the response stack may go before the engine stops unwinding it.
     *
     * A backstop, not a rule: a copycat copying a copycat is a real and legal
     * thing, and five gambler cards a player is as deep as a table can actually
     * go. This is here so that a card written wrong — one that answers itself —
     * fails loudly at the top of the next transition instead of hanging a room.
     */
    private const val MAX_STACK_DEPTH = 64

    /**
     * Puts a drawn gambler card into its drawer's hidden hand, and draws again.
     *
     * Drawing one is not spending your draw on it: the spec's rule is that you
     * keep drawing until you turn over an ordinary card, which is what makes a
     * gambler card a bonus rather than a wasted turn.
     */
    private fun resolveGambler(ctx: Ctx, playerId: String, card: Card, depth: Int): DrawOutcome {
        // No room means the discard pile rather than a refusal — a card left on
        // top of the deck is a card the next player draws, and the one after.
        ctx.giveGambler(playerId, card)

        if (depth >= MAX_GAMBLER_REDRAWS) return DrawOutcome.CONTINUE
        val next = ctx.drawRaw() ?: return DrawOutcome.CONTINUE
        return resolveDrawnCard(ctx, playerId, next, depth + 1)
    }

    /**
     * How many gambler cards [playerId] may hold. Five, plus room made by any
     * "pouch" they are carrying.
     *
     * Public because three things have to agree on it — the draw above, the shop
     * between rounds, and the view that greys out a full tray — and a rule
     * answered in three places is a rule answered three different ways.
     */
    fun gamblerLimitFor(state: GameState, playerId: String): Int {
        val player = state.player(playerId) ?: return GAMBLER_HAND_LIMIT
        val pouches = player.gamblers.count { Catalog.gambler(it.defId)?.id == POUCH_ID }
        return GAMBLER_HAND_LIMIT + pouches * POUCH_SLOTS
    }

    /**
     * Whether [playerId] may play a card in [window] at this exact moment.
     *
     * The single place the six windows are read. The client is *told* the answer
     * in `GameStateView.playableGamblers` and never works it out — a window is a
     * rule, and a rule the client keeps its own copy of is a rule that will one
     * day disagree with the one that counts.
     */
    fun windowOpen(state: GameState, playerId: String, window: PlayWindow): Boolean {
        // The one window that is not about a round at all. Asked first, because
        // every check below it is about a table that is playing.
        if (window == PlayWindow.INTERLUDE) {
            val shop = state.interlude ?: return false
            return !shop.closed && playerId !in shop.done && state.player(playerId) != null
        }

        // A card is never played into a table that is already stopped for
        // something. The one exception is a response window, and that is not
        // asked through here — the stack asks whoever it is waiting on.
        if (state.phase != GamePhase.PLAYING || state.isInterrupted) return false
        if (state.dealQueue.isNotEmpty()) return false
        val player = state.player(playerId) ?: return false

        return when (window) {
            // It works by being held; there is nothing to play.
            PlayWindow.PASSIVE -> false
            // Only into an open window, which asks separately.
            PlayWindow.IN_RESPONSE -> false
            PlayWindow.ALWAYS -> true
            PlayWindow.ON_TURN ->
                state.currentPlayer?.id == playerId && player.status == PlayerStatus.ACTIVE

            PlayWindow.ON_OTHER_TURN -> state.currentPlayer?.id != playerId
            PlayWindow.ON_OUT -> player.status != PlayerStatus.ACTIVE
            PlayWindow.INTERLUDE -> false // handled above
        }
    }

    /**
     * Every gambler card [playerId] could play right now, by card id.
     *
     * Three things have to be true and all three are the server's to know: the
     * window is open, the card is affordable, and there is somebody to point it
     * at. That last one is why a gambler card does not fizzle the way a drawn
     * action card does — a card off the deck was never chosen, so replacing it
     * is fair, but spending one out of your own hand on nothing is a decision
     * you would not have made. So a card with no target is simply not offered.
     */
    /**
     * The cards off the top of the deck [playerId] has been shown, if any.
     *
     * The second thing the per-viewer projection carries, after the hidden hand
     * itself — and it is the same rule stated about a different pile: what you
     * may see is a fact about you, so it cannot ride on a field everybody gets.
     */
    fun foresightFor(state: GameState, playerId: String): List<Card> {
        val player = state.player(playerId) ?: return emptyList()
        val holdsForesight = player.passives.any { it.defId == FORESIGHT.id }
        val stacking = state.pendingAction
            ?.takeIf { it.phase == PHASE_GAMBLER && it.playerId == playerId && it.cardDefId == "stackedDeck" }
        return when {
            stacking != null -> state.deck.take(STACKED_DECK_CARDS)
            holdsForesight -> state.deck.take(FORESIGHT_CARDS)
            else -> emptyList()
        }
    }

    fun playableGamblers(state: GameState, playerId: String): List<String> =
        onOwnInitiative(state, playerId) + respondableGamblers(state, playerId)

    /**
     * ...the ones playable on their own account, as opposed to in answer to
     * something. The two are disjoint: a window makes the table interrupted, and
     * an interrupted table opens no ordinary window.
     */
    private fun onOwnInitiative(state: GameState, playerId: String): List<String> {
        val player = state.player(playerId) ?: return emptyList()
        val purse = player.score + (state.roundAdjustments[playerId] ?: 0)
        return player.gamblers.filter { card ->
            val def = Catalog.gambler(card.defId) ?: return@filter false
            if (!windowOpen(state, playerId, def.window)) return@filter false
            if (def.cost > purse) return@filter false
            if (def.picksCards) def.cardTargets(state, playerId).size >= def.picks
            else def.validTargets(state, playerId).isNotEmpty()
        }.map { it.id }
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
            if (def.skipHolding?.let { ctx.hasPassive(playerId, it) } == true) {
                return fizzle(ctx, def, card, playerId)
            }
            runAction(ctx, def, card, playerId, playerId, choice = null)
            return when {
                ctx.player(playerId)?.status == PlayerStatus.BUST -> DrawOutcome.BUSTED
                ctx.state.isInterrupted -> DrawOutcome.PAUSED
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
        player.hand.size >= ctx.rules.flipTarget && player.passives.none { it.defId == NO_FLIP.id }

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

    // ═══════════════════════════════════════════
    // Gambler cards
    // ═══════════════════════════════════════════

    /**
     * Plays a gambler card out of its owner's hidden hand.
     *
     * The legality check is [playableGamblers] and nothing else — the same
     * predicate the client was handed, so what it lit up and what the server
     * allows cannot drift apart. A card that is not in that list is dropped
     * silently, exactly as a mistimed hit is: an offer that arrived a moment too
     * late is not an error, it is a moment that passed.
     */
    private fun playGambler(ctx: Ctx, playerId: String, cardId: String) {
        // A window being open changes which of your cards are legal, not how you
        // play one — so the same message serves both and the client needs no
        // second way to click a card.
        if (respondWithGambler(ctx, playerId, cardId)) return

        val state = ctx.state
        if (cardId !in playableGamblers(state, playerId)) return
        val player = state.player(playerId) ?: return
        val card = player.gamblers.firstOrNull { it.id == cardId } ?: return
        val def = Catalog.gambler(card.defId) ?: return

        // Out of the hand the moment it is offered, whether it resolves now or
        // waits on a pick. A card being answered is not a card you are still
        // holding, and while it waits it is counted on the prompt instead.
        ctx.update(playerId) { p -> p.copy(gamblers = p.gamblers.filterNot { it.id == cardId }) }
        ctx.emit(GameEvent.GamblerPlayed(playerId, card))
        if (def.cost > 0) ctx.adjust(playerId, -def.cost)

        val cards = if (def.picksCards) def.cardTargets(state, playerId) else emptyList()

        // A card that points at nobody and asks nothing goes straight onto the
        // stack. One that has to be aimed is aimed *first* — a counter reads who
        // a card is pointed at to know whether it may answer, so the window
        // cannot open until the question has been asked.
        if (def.selfTarget && !def.needsChoice && !def.picksCards) {
            pushFrame(ctx, def, card, playerId, playerId, choice = null)
            resolveStack(ctx)
            return
        }

        ctx.state = ctx.state.copy(
            pendingAction = PendingAction(
                cardDefId = def.id,
                playerId = playerId,
                card = card,
                validTargets = def.validTargets(ctx.state, playerId),
                options = def.options,
                kind = def.pickKind,
                validCards = cards,
                picks = def.picks,
                phase = PHASE_GAMBLER,
            ),
        )
    }

    /**
     * Answers into an open response window with a card of your own.
     *
     * The same message as playing one off your own bat, because from the
     * player's side it is the same act — a card comes out of your hand. What is
     * different is which cards are legal, and that is `respondableGamblers`.
     */
    private fun respondWithGambler(ctx: Ctx, playerId: String, cardId: String): Boolean {
        val state = ctx.state
        val frame = state.openResponse ?: return false
        if (playerId !in frame.awaiting) return false
        if (cardId !in respondableGamblers(state, playerId)) return false
        val card = state.player(playerId)?.gamblers?.firstOrNull { it.id == cardId } ?: return false
        val def = Catalog.gambler(card.defId) ?: return false

        ctx.update(playerId) { p -> p.copy(gamblers = p.gamblers.filterNot { it.id == cardId }) }
        ctx.emit(GameEvent.GamblerPlayed(playerId, card))
        if (def.cost > 0) ctx.adjust(playerId, -def.cost)

        // Answering shuts the window on the frame below: the question was "does
        // anybody want to stop this", and somebody did. Whoever else was still
        // thinking does not get to pile on afterwards — their moment is now the
        // window that opens over *this* card.
        replaceTop(ctx) { it.copy(awaiting = emptyList()) }
        pushFrame(ctx, def, card, playerId, frame.playerId, choice = null)
        resolveStack(ctx)
        return true
    }

    /**
     * Every card [playerId] could answer the open window with, by card id.
     *
     * Empty when there is no window, when they are not one of the people being
     * asked, or when nothing they hold speaks to this particular card — which is
     * most of the time, and is why being asked at all is not a tell.
     */
    fun respondableGamblers(state: GameState, playerId: String): List<String> {
        val frame = state.openResponse ?: return emptyList()
        if (playerId !in frame.awaiting) return emptyList()
        val player = state.player(playerId) ?: return emptyList()
        val purse = player.score + (state.roundAdjustments[playerId] ?: 0)
        return player.gamblers.filter { card ->
            val def = Catalog.gambler(card.defId) ?: return@filter false
            def.window == PlayWindow.IN_RESPONSE &&
                def.cost <= purse &&
                def.counters?.invoke(state, frame, playerId) == true
        }.map { it.id }
    }

    /** Says "no" to an open window. Everybody saying no is what shuts it. */
    private fun passResponse(ctx: Ctx, playerId: String) {
        val frame = ctx.state.openResponse ?: return
        if (playerId !in frame.awaiting) return
        replaceTop(ctx) { it.copy(awaiting = it.awaiting - playerId) }
        resolveStack(ctx)
    }

    /** Rewrites the frame on top of the stack. */
    private fun replaceTop(ctx: Ctx, transform: (StackFrame) -> StackFrame) {
        val stack = ctx.state.responseStack
        if (stack.isEmpty()) return
        ctx.state = ctx.state.copy(responseStack = stack.dropLast(1) + transform(stack.last()))
    }

    /**
     * Puts an aimed card on the stack and opens a window over it, if there is
     * anybody who could answer.
     */
    internal fun pushFrame(
        ctx: Ctx,
        def: GamblerCardDef,
        card: Card,
        playerId: String,
        targetId: String,
        choice: String?,
        cards: List<String> = emptyList(),
    ) = pushFrame(ctx, StackKind.GAMBLER, def.id, card, playerId, targetId, choice, cards)

    private fun pushFrame(
        ctx: Ctx,
        kind: StackKind,
        cardDefId: String,
        card: Card,
        playerId: String,
        targetId: String,
        choice: String?,
        cards: List<String> = emptyList(),
    ) {
        val id = ctx.state.stackCounter + 1
        val bare = StackFrame(
            id = id,
            cardDefId = cardDefId,
            card = card,
            playerId = playerId,
            kind = kind,
            targetId = targetId,
            choice = choice,
            cards = cards,
        )
        // Whether anybody *could* answer decides whether anybody *is* asked.
        // Once it opens, everybody else is asked — not just the holders — or
        // "waiting on one more" plus a seat that always gets asked would tell
        // the table exactly who is carrying a nullify, and the hidden hand would
        // leak straight back out through the prompt.
        val holders = ctx.state.players.any { canAnswer(ctx.state, bare, it.id) }
        val asked = if (!holders) emptyList() else ctx.state.players.map { it.id } - playerId
        val frame = bare.copy(awaiting = asked, responders = asked)

        ctx.state = ctx.state.copy(
            responseStack = ctx.state.responseStack + frame,
            stackCounter = id,
        )
        // Deliberately does *not* unwind. A copycat pushes a frame from inside
        // an effect that [resolveStack] is already running, and having this
        // recurse gave the two of them a fresh loop counter each and a stack
        // overflow rather than the depth guard they both share. Pushing is
        // pushing; the one loop that is already turning picks it up on its next
        // pass, and the entry points below start it.
    }

    private fun canAnswer(state: GameState, frame: StackFrame, playerId: String): Boolean {
        if (playerId == frame.playerId) return false
        val player = state.player(playerId) ?: return false
        val purse = player.score + (state.roundAdjustments[playerId] ?: 0)
        return player.gamblers.any { card ->
            val def = Catalog.gambler(card.defId) ?: return@any false
            def.window == PlayWindow.IN_RESPONSE &&
                def.cost <= purse &&
                def.counters?.invoke(state, frame, playerId) == true
        }
    }

    /**
     * Unwinds the stack, innermost first, for as long as the top of it is not
     * waiting on anybody.
     *
     * Written as a loop rather than by recursion because an effect can push
     * another frame — a copycat is a card played *by* a card — and a loop makes
     * the ordering something you can read rather than infer.
     */
    private fun resolveStack(ctx: Ctx) {
        // An action card reached the stack by being drawn and aimed on somebody's
        // turn, so whatever becomes of it that turn is over and the table has to
        // move on. A gambler card is played out of band and moves nothing. When a
        // stack holds both — a nullify answering a freeze — the action frame is
        // the one at the bottom, and it is the one that decides.
        var ranAction = false
        var guard = 0
        while (guard++ < MAX_STACK_DEPTH) {
            val top = ctx.state.responseStack.lastOrNull() ?: break
            // Somebody is still thinking. The table waits.
            if (top.awaiting.isNotEmpty()) return

            ctx.state = ctx.state.copy(responseStack = ctx.state.responseStack.dropLast(1))
            if (top.kind == StackKind.ACTION) ranAction = true

            if (top.cancelled) {
                ctx.emit(GameEvent.GamblerCountered(top.playerId, top.card, top.returned))
                disposeFrame(ctx, top)
                continue
            }

            val cards = top.cards.mapNotNull(ctx::cardById)
            if (top.kind == StackKind.ACTION) {
                val def = Catalog.action(top.cardDefId)
                if (def == null) {
                    ctx.toDiscard(top.card)
                    continue
                }
                // The target may have been turned round by a deflect on the way
                // here, so the card is aimed by the frame rather than by the
                // answer that first pointed it.
                runAction(ctx, def, top.card, top.playerId, top.targetId, top.choice, cards)
                continue
            }

            val def = Catalog.gambler(top.cardDefId)
            if (def == null) {
                ctx.toDiscard(top.card)
                continue
            }
            runGambler(ctx, def, top.card, top.playerId, top.targetId, top.choice, cards)
        }
        if (ranAction) afterAction(ctx) else afterGambler(ctx)
    }

    /**
     * Puts a frame's card where it ends up: the discard pile, or back in the
     * hand it came out of.
     *
     * A card handed home may take its owner over the cap, and is allowed to. A
     * card coming back is not a card you gained — you had room for it when you
     * played it — so it goes back, and the *next* card that would take you over
     * is the one refused instead.
     */
    /**
     * Empties the response stack, putting anything still in flight in the
     * discard pile.
     *
     * A frame outliving the round it was played in should not be reachable — the
     * table is interrupted while one is open, so nothing else moves — but a card
     * that is nobody's when a round turns over is a card the deck has lost, and
     * that is the one thing here worth being defensive about.
     */
    private fun clearResponseStack(ctx: Ctx) {
        if (ctx.state.responseStack.isEmpty()) return
        for (frame in ctx.state.responseStack) ctx.toDiscard(frame.card)
        ctx.state = ctx.state.copy(responseStack = emptyList())
    }

    private fun disposeFrame(ctx: Ctx, frame: StackFrame) {
        if (!frame.returned) {
            ctx.toDiscard(frame.card)
            return
        }
        ctx.update(frame.playerId) { it.copy(gamblers = it.gamblers + frame.card) }
        ctx.emit(GameEvent.GamblerReturned(frame.playerId, frame.card))
    }

    /** Answers a prompt a gambler card raised. The mirror of [resolvePendingAction]. */
    private fun resolveGamblerPending(ctx: Ctx, pending: PendingAction) {
        val fromId = pending.playerId
        val own = pending.answers[fromId] ?: Answer()
        val def = Catalog.gambler(pending.cardDefId)
        ctx.state = ctx.state.copy(pendingAction = null)

        if (def == null) {
            ctx.toDiscard(pending.card)
            afterGambler(ctx)
            return
        }

        // The card was only offered because it had somewhere to go, but a seat
        // can leave between the question and the answer. As everywhere else, an
        // illegal pick falls back to a legal one rather than stranding the
        // table; with nothing left at all the card is simply spent.
        val allowed = pending.validTargets.filter { ctx.player(it) != null }
        if (allowed.isEmpty()) {
            ctx.toDiscard(pending.card)
            ctx.emit(GameEvent.Fizzled(def.id, fromId))
            afterGambler(ctx)
            return
        }
        val target = own.targetId?.takeIf { it in allowed } ?: allowed.first()

        val choice = when {
            pending.options.isEmpty() -> null
            own.choice in pending.options -> own.choice
            else -> pending.options.first()
        }

        // A gambler card's picks are validated against its own offer and nothing
        // else. `legalPicks` additionally enforces one card per *owner*, which
        // is right for a trade between two hands and meaningless for a card
        // that points at the top of the deck — where nobody owns anything.
        val cards =
            if (!def.picksCards) emptyList()
            else {
                val offered = def.cardTargets(ctx.state, fromId).toSet()
                own.cards.filter { it in offered }.distinct().take(def.picks)
                    .ifEmpty { offered.take(def.picks) }
                    .mapNotNull { ctx.cardById(it) }
            }
        if (def.picksCards && cards.size < def.picks) {
            ctx.toDiscard(pending.card)
            ctx.emit(GameEvent.Fizzled(def.id, fromId))
            afterGambler(ctx)
            return
        }

        // Aimed at last — now it can go on the stack and be answered.
        pushFrame(ctx, def, pending.card, fromId, target, choice, cards.map { it.id })
        resolveStack(ctx)
    }

    private fun runGambler(
        ctx: Ctx,
        def: GamblerCardDef,
        card: Card?,
        fromId: String,
        targetId: String,
        choice: String?,
        cards: List<Card> = emptyList(),
        phase: String = PHASE_GAMBLER,
        answers: Map<String, Answer> = emptyMap(),
    ) {
        payGamblerToll(ctx, fromId, targetId)
        // Deliberately not repeated under "double it!". That rule doubles what
        // the deck throws at you, which is a thing happening *to* the table; a
        // gambler card is a decision somebody made once.
        val from = ctx.player(fromId)
        val target = ctx.player(targetId)
        if (from != null && target != null) {
            def.onPlay(
                ctx,
                Play(from = from, target = target, choice = choice, cards = cards, phase = phase, answers = answers),
            )
        }

        // Spent *after* the effect, where an action card is spent before it.
        //
        // The difference is not tidiness. "Cheating" reaches into the deck and
        // the discard pile for a gambler card, and discarding first put the card
        // being played onto the top of the pile — where it promptly found itself
        // and handed itself back, for a hundred points and no cards used. Any
        // card that reads the pile has the same hole. A card is spent once its
        // effect has happened, which is also simply what "spent" means.
        card?.let { ctx.toDiscard(it) }
    }

    /** A discordia charges for a gambler card the same way it charges for any other. */
    private fun payGamblerToll(ctx: Ctx, fromId: String, targetId: String) {
        if (fromId == targetId) return
        val toll = ctx.player(targetId)?.passives.orEmpty()
            .mapNotNull { Catalog.passive(it.defId) }
            .sumOf { it.spite }
        if (toll > 0) ctx.transferPoints(targetId, fromId, toll)
    }

    /**
     * What a gambler card leaves behind.
     *
     * Deliberately not [afterAction]. A card played out of turn is not a turn,
     * and advancing the table because somebody answered a question would hand
     * the move to whoever spoke last. The flip is still checked — a gambler card
     * can push somebody over the line — and the round can still end, because a
     * card played by a seat that is already out can be the last thing that
     * settles it, and a table where everybody is finished would otherwise sit
     * there for ever.
     */
    private fun afterGambler(ctx: Ctx) {
        anyFlip7(ctx)?.let {
            endRoundByFlip7(ctx, it)
            advanceAndCheck(ctx)
            return
        }
        if (ctx.state.isInterrupted) return
        if (ctx.state.phase == GamePhase.PLAYING && ctx.activePlayers().isEmpty()) enterRoundEnd(ctx)
        // The turn stays exactly where it was. Nothing here moves it.
    }

    private fun resolvePendingAction(ctx: Ctx, pending: PendingAction) {
        // A gambler card is looked up in its own catalog and settles on its own
        // terms — routed on the phase rather than on a lookup, so the path below
        // never has to ask a question it did not use to ask.
        if (pending.phase == PHASE_GAMBLER) {
            resolveGamblerPending(ctx, pending)
            return
        }
        // ...and a prompt about a card that was *drawn* holds that card rather
        // than the one that asked, so it is placed rather than spent.
        if (pending.phase == PHASE_REDIRECT || pending.phase == PHASE_SECOND_OPINION) {
            resolveDrawnCardPrompt(ctx, pending)
            return
        }

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

        val cards =
            if (!def.picksCards) emptyList()
            else legalPicks(
                ctx,
                def.cardTargets(ctx.state, fromId).toSet(),
                def.picks,
                requestedCards,
                pending.oneCardPerSeat,
            )
        if (def.picksCards && cards.size < def.picks) {
            fizzle(ctx, def, pending.card, fromId)
            afterAction(ctx)
            return
        }

        // Everything about the play is settled — who, at whom, with what — which
        // is the first moment a counter could be asked a question it can answer.
        // If anybody can, the card goes on the stack instead of going off, and
        // the stack is what runs it (or does not).
        if (offerToCounter(ctx, def, pending, fromId, resolvedTarget, choice, cards)) return

        runAction(ctx, def, pending.card, fromId, resolvedTarget, choice, cards, pending.phase, pending.answers)
        afterAction(ctx)
    }

    /**
     * Puts an action card aimed at somebody else on the response stack, if there
     * is anybody holding something that could answer it.
     *
     * Returns whether it took the card over. A table where nobody can counter —
     * every classic game, and most rolling-rules ones — takes the `false` arm and
     * runs exactly the path it always ran, which is the point: the detour costs
     * nothing when there is nothing to detour for.
     *
     * Only a card being *played* is offered. A prompt raised later by an effect —
     * a bomb going off, an anti-flip being aimed — is already mid-resolution, and
     * a counter that could unpick one of those would be answering a question
     * about a card that has already been spent. Only a card pointed at somebody
     * else, too: "a card aimed at you" is what both counters say, and a card you
     * played on yourself is not aimed at anybody.
     */
    private fun offerToCounter(
        ctx: Ctx,
        def: ActionCardDef,
        pending: PendingAction,
        fromId: String,
        targetId: String,
        choice: String?,
        cards: List<Card>,
    ): Boolean {
        if (pending.phase != PHASE_PLAY) return false
        if (targetId == fromId) return false
        val card = pending.card

        val bare = StackFrame(
            id = ctx.state.stackCounter + 1,
            cardDefId = def.id,
            card = card,
            playerId = fromId,
            kind = StackKind.ACTION,
            targetId = targetId,
            choice = choice,
            cards = cards.map { it.id },
        )
        if (ctx.state.players.none { canAnswer(ctx.state, bare, it.id) }) return false

        pushFrame(ctx, StackKind.ACTION, def.id, card, fromId, targetId, choice, cards.map { it.id })
        resolveStack(ctx)
        return true
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
    /**
     * The picks a card actually gets, out of what its owner asked for.
     *
     * Takes the offer and the count rather than a definition, because two kinds
     * of card ask for cards — one off the deck, one out of a hidden hand — and
     * the rules about what may be picked are the same for both.
     */
    private fun legalPicks(
        ctx: Ctx,
        offered: Set<String>,
        picks: Int,
        requested: List<String>,
        oneCardPerSeat: Boolean = true,
    ): List<Card> {
        val picked = mutableListOf<Card>()
        val owners = mutableSetOf<String>()

        fun take(cardId: String): Boolean {
            if (cardId !in offered) return false
            if (picked.any { it.id == cardId }) return false
            val owner = ctx.ownerOf(cardId) ?: return false
            if (oneCardPerSeat && owner.id in owners) return false
            val card = (owner.hand + owner.passives).firstOrNull { it.id == cardId } ?: return false
            picked += card
            owners += owner.id
            return true
        }

        for (cardId in requested) {
            if (picked.size >= picks) break
            take(cardId)
        }
        // Short of a full pick — a client that sent one card, or two off the
        // same seat — the rest is filled in from what was on offer.
        for (cardId in offered) {
            if (picked.size >= picks) break
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
        payToll(ctx, def, fromId, targetId)
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

    /**
     * "Discordia": aiming a card at the seat carrying it costs them — see
     * [DISCORDIA]. Read off the cards the target is holding rather than written
     * into any of the fourteen cards that can trigger it, because the card that
     * charges the toll is the one being aimed *at*.
     *
     * Paid once however many times "double it!" fires the effect: what is being
     * resented is being played on, not what the play then did. Paid before the
     * effect for the same reason — the card has changed hands by then, and a
     * freeze that ends the round must not swallow the toll it earned.
     *
     * A house rule asking a question is not a card being played, so nothing is
     * owed for one — that is what [ActionCardDef.deckable] is saying here.
     */
    private fun payToll(ctx: Ctx, def: ActionCardDef, fromId: String, targetId: String) {
        if (!def.deckable || fromId == targetId) return
        val toll = ctx.player(targetId)?.passives.orEmpty()
            .mapNotNull { Catalog.passive(it.defId) }
            .sumOf { it.spite }
        if (toll > 0) ctx.transferPoints(targetId, fromId, toll)
    }

    /**
     * Applies a card the table has finished watching — see [PendingOutcome].
     *
     * The card itself was discarded when it was played, so there is nothing to
     * move; this is only the effect, run in a phase of its own so a card can
     * tell the announcement apart from the consequence.
     */
    private fun resolveOutcome(ctx: Ctx) {
        val outcome = ctx.state.pendingOutcomes.firstOrNull() ?: return
        ctx.state = ctx.state.copy(pendingOutcomes = ctx.state.pendingOutcomes.drop(1))
        // Two kinds of card settle through this one queue and neither knows
        // about the other, so the lookup asks both rather than the caller
        // having to say which it was.
        val action = Catalog.action(outcome.cardDefId)
        val gambler = if (action == null) Catalog.gambler(outcome.cardDefId) else null
        val effect = action?.onPlay ?: gambler?.onPlay
        val from = ctx.player(outcome.playerId)
        val target = ctx.player(outcome.targetId)
        if (effect != null && from != null && target != null) {
            effect(
                ctx,
                Play(
                    from = from,
                    target = target,
                    choice = outcome.choice,
                    phase = PHASE_OUTCOME,
                    result = outcome.result,
                    targets = outcome.targetIds.mapNotNull { ctx.player(it) },
                ),
            )
        }
        // A gambler card settling is still not a turn — see [afterGambler].
        if (gambler != null) afterGambler(ctx) else afterAction(ctx)
    }

    private fun afterAction(ctx: Ctx) {
        anyFlip7(ctx)?.let {
            endRoundByFlip7(ctx, it)
            advanceAndCheck(ctx)
            return
        }
        if (ctx.state.isInterrupted) return
        advanceAndCheck(ctx)
    }

    // ═══════════════════════════════════════════
    // Forced draws
    // ═══════════════════════════════════════════

    private fun forcedDraw(ctx: Ctx) {
        if (ctx.state.pendingAction != null || ctx.state.pendingOutcomes.isNotEmpty()) return
        val forced = ctx.state.forcedDraws
        if (forced == null) {
            advanceAndCheck(ctx)
            return
        }
        processOneForcedDraw(ctx, forced)
        if (ctx.state.isInterrupted) return
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
        clearResponseStack(ctx)
        ctx.state = ctx.state.copy(
            flip7PlayerId = playerId,
            pendingAction = null,
            pendingOutcomes = emptyList(),
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
     * modifiers, then the Flip 7 bonus, then anything that takes it away.
     * Busting scores nothing — unless it is a bust that still counts, which is
     * the antimatter and is scored the whole way down like any other hand.
     *
     * Everything here is read off the cards the player is holding, which is why
     * a round can be ruined by something somebody handed you — see the effect
     * cards in `CardDefs`.
     */
    fun roundScore(player: Player, flip7PlayerId: String?): Int {
        if (player.status == PlayerStatus.BUST && !bustStillCounts(player)) return 0
        val defs = player.passives.mapNotNull { Catalog.passive(it.defId) }
        // "Unlucky 7": the hand is only worth something if it went all the way.
        if (defs.any { it.scoring == PassiveScoring.VOID_UNLESS_FLIP } && player.id != flip7PlayerId) return 0
        var total = player.hand.sumOf { it.value }
        // "Antimatter": every number in front of you counts the wrong way. First
        // of all, so a x2 doubles the hole rather than digging a second one, and
        // asked once however many of them are on the table — two would cancel
        // out, which is a joke this card is not making.
        if (defs.any { it.scoring == PassiveScoring.NEGATE }) total = -total
        for (def in defs) if (def.scoring == PassiveScoring.DOUBLE_NUMBERS) total *= 2
        for (def in defs) if (def.scoring == PassiveScoring.FLAT) total += def.bonusPoints
        if (player.id == flip7PlayerId) total += FLIP7_BONUS
        // "All in": whoever bet the highest or the lowest keeps half of it.
        // Last, so it takes half of everything the round was actually worth.
        for (def in defs) if (def.scoring == PassiveScoring.HALVE) total /= 2
        return total
    }

    private fun enterRoundEnd(ctx: Ctx) {
        clearResponseStack(ctx)
        val state = ctx.state
        // Hand scoring first, then anything moved by other means during the
        // round, then the bounty — all of it in the deltas rather than in the
        // banked score, so the summary shows what the round actually paid.
        //
        // A round normally costs a player their whole hand at worst and never
        // puts them in the red. "Extreme" is what lifts that floor.
        val scored = state.players.associate { it.id to roundScore(it, state.flip7PlayerId) }
        val adjusted = scored.mapValues { (id, points) ->
            (points + (state.roundAdjustments[id] ?: 0)).coerceAtLeast(floorFor(ctx, state, state.player(id)))
        }
        // ...and then the gambler cards that are about what a round *pays*
        // rather than about what is in a hand. Same shape as the bounty below:
        // a delta map in, a delta map out.
        val settled = settleGamblerEffects(ctx, state, adjusted)
        val deltas = payBounty(ctx, state, settled)

        val winner = state.players
            .filter { it.status != PlayerStatus.BUST }
            .sortedWith(
                compareByDescending<Player> { deltas[it.id] ?: 0 }.thenBy { it.hand.size },
            )
            .firstOrNull()

        // Built from `ctx.state` rather than from the `state` read at the top of
        // this function. Everything above computes deltas from a snapshot, which
        // is right — but `settleGamblerEffects` also *moves cards*, and it
        // collects the IOU a loan left in a tray. Copying the old snapshot over
        // the top put the debt straight back, paid.
        val current = ctx.state
        val ended = current.copy(
            phase = GamePhase.ROUND_END,
            players = current.players.map { it.copy(score = it.score + (deltas[it.id] ?: 0)) },
            roundDeltas = deltas,
            roundWinnerId = winner?.id,
            pendingAction = null,
            pendingOutcomes = emptyList(),
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
     * How far a round may leave a player down.
     *
     * Nought for everybody, normally: a round costs you your hand at worst.
     * Three things lift that. "Extreme" lifts it for the whole table. An
     * antimatter lifts it for whoever is holding one — a card whose whole claim
     * is that a 13 is worth minus thirteen has to mean it, and rounding it back
     * up to nothing would leave it saying nothing at all.
     *
     * And a toll lifts it by exactly what the toll took. "Anyone who plays an
     * action card on you takes 10 points off you" is read by everybody as ten
     * points, not as ten points or whatever this round happened to be worth,
     * whichever is less — a toll charged on a bad round used to be worth almost
     * nothing. What a player *earns* still cannot put them in the red; what was
     * taken from them can, and only that far.
     */
    private fun floorFor(ctx: Ctx, state: GameState, player: Player?): Int {
        if (ctx.rules.allowsNegative) return Int.MIN_VALUE
        if (player != null && negatesHand(player)) return Int.MIN_VALUE
        return minOf(0, state.roundTolls[player?.id] ?: 0)
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
    /**
     * The gambler cards that change what a round pays rather than what a hand
     * is worth: a doubling, a tithe, a boost, a debt coming due.
     *
     * A delta map in and a delta map out, the same shape [payBounty] has, and
     * for the same reason: none of these are about the cards in front of
     * anybody, so none of them belong in [roundScore].
     *
     * The order is the order the cards read in. Doubling first, because "all
     * points you receive or lose this round" plainly means the round's total;
     * then the boost, which is a share of what you made; then the tithe, which
     * is a share of what somebody else made; then the debt, which is owed
     * whatever happened.
     */
    private fun settleGamblerEffects(ctx: Ctx, state: GameState, deltas: Map<String, Int>): Map<String, Int> {
        var out = deltas

        // "Double down": everything this round counted twice, up or down.
        out = out.mapValues { (id, points) ->
            if (ctx.hasPassive(id, DOUBLE_DOWN_ARMED.id)) points * 2 else points
        }

        // "Already down": the further behind the leader you are, the more a good
        // round is worth. The leader is read off banked scores *before* this
        // round is paid, which is what "how far behind you are" means.
        val leader = state.players.maxOfOrNull { it.score } ?: 0
        out = out.mapValues { (id, points) ->
            if (!ctx.hasPassive(id, ALREADY_DOWN_ARMED.id) || points <= 0) return@mapValues points
            val mine = state.player(id)?.score ?: 0
            // Guarded, because the formula divides by the leader's score — which
            // is nought at the start of a game and can be negative under
            // "extreme". No leader worth catching means no boost.
            if (leader <= 0 || mine >= leader) return@mapValues points
            val share = ALREADY_DOWN_MAX * (1.0 - mine.toDouble() / leader)
            points + (points * share.coerceIn(0.0, ALREADY_DOWN_MAX)).toInt()
        }

        // "Taxes": a tithe on everybody else's good round, into the holder's.
        for (collector in state.players.filter { ctx.hasPassive(it.id, TAXES_ARMED.id) }) {
            var taken = 0
            out = out.mapValues { (id, points) ->
                if (id == collector.id || points <= 0) return@mapValues points
                val tithe = points * TAXES_PERCENT / 100
                taken += tithe
                points - tithe
            }
            if (taken > 0) {
                out = out + (collector.id to (out[collector.id] ?: 0) + taken)
                ctx.emit(GameEvent.Taxed(collector.id, taken))
            }
        }

        // "Loan": the debt comes due, whatever the round did. It is a card in
        // the borrower's own tray — see [LOAN] — so it is spent here.
        for (player in state.players) {
            val debt = player.gamblers.firstOrNull { it.defId == LOAN_DEBT_ID } ?: continue
            ctx.update(player.id) { it.copy(gamblers = it.gamblers.filterNot { c -> c.id == debt.id }) }
            ctx.toDiscard(debt)
            out = out + (player.id to (out[player.id] ?: 0) - LOAN_REPAYMENT)
            ctx.emit(GameEvent.LoanRepaid(player.id, LOAN_REPAYMENT))
        }

        return out
    }

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

    /**
     * What "next round" means, which is not always the next round.
     *
     * The results screen stays first and must stay first: a game that has been
     * won does not open a shop on the way out. Everything below that is the
     * mode's, and nobody may move this check under it.
     */
    private fun nextRound(ctx: Ctx) {
        val state = ctx.state
        if (state.phase != GamePhase.ROUND_END) return
        // Already between rounds. The host's button does nothing here on
        // purpose: cutting a window short that other people are spending points
        // in is a grief, and it shuts on its own when nobody is using it.
        if (state.interlude != null) return

        if (state.gameWinnerId != null) {
            ctx.state = state.copy(phase = GamePhase.GAME_END)
            return
        }

        if (state.config.mode == GameMode.ROLLING_RULES) {
            openInterlude(ctx)
            return
        }
        dealNextRound(ctx)
    }

    /**
     * Opens the shop.
     *
     * Shelves are rolled in seat order so a replay from the seed produces the
     * same ones. Every card on them is minted — the dealer's stock is not the
     * table's deck, and a shop that dealt out of the deck would thin the pile
     * everybody else is drawing from.
     */
    private fun openInterlude(ctx: Ctx) {
        val stock = ctx.state.players.associate { player ->
            player.id to rollShelfDefs(ctx.rng).mapIndexed { slot, def ->
                Offer(
                    id = offerIdForGambler(slot, def.id),
                    price = def.price,
                    card = Card(
                        id = ctx.mint("shop"),
                        kind = CardKind.GAMBLER,
                        label = def.name,
                        value = 0,
                        defId = def.id,
                    ),
                )
            }
        }
        val lot = rollLotDef(ctx.rng)?.let { def ->
            Offer(
                id = "lot:${'$'}{def.id}",
                price = def.price,
                card = Card(
                    id = ctx.mint("lot"),
                    kind = CardKind.GAMBLER,
                    label = def.name,
                    value = 0,
                    defId = def.id,
                ),
            )
        }
        ctx.state = ctx.state.copy(
            interlude = Interlude(
                stock = stock,
                openingScore = ctx.state.players.associate { it.id to it.score },
                lot = lot,
            ),
        )
        ctx.emit(GameEvent.ShopOpened(lot?.card))
    }

    /** Shuts the shop. Whatever it settles is settled here; the round deals after. */
    private fun closeInterlude(ctx: Ctx) {
        val shop = ctx.state.interlude ?: return
        if (shop.closed) return
        ctx.state = ctx.state.copy(interlude = shop.copy(closed = true, done = emptyList()))
        val settled = settleAuction(ctx, ctx.state.interlude!!)
        ctx.state = ctx.state.copy(interlude = settled)
    }

    /** Ends the interlude and deals. */
    private fun openRound(ctx: Ctx) {
        if (ctx.state.interlude == null) return
        ctx.state = ctx.state.copy(interlude = null)
        dealNextRound(ctx)
    }

    /** Takes one card off a player's own shelf, and takes the price off their score. */
    private fun buy(ctx: Ctx, playerId: String, offerId: String) {
        val shop = ctx.state.interlude ?: return
        if (shop.closed || playerId in shop.done) return
        val player = ctx.player(playerId) ?: return
        // Their own shelf, and only their own. That one line is the whole of the
        // private-stock rule.
        val offer = shop.stock[playerId]?.firstOrNull { it.id == offerId } ?: return
        if (offerId in shop.bought[playerId].orEmpty()) return
        if (offer.price > player.score) return
        if (player.gamblers.size >= gamblerLimitFor(ctx.state, playerId)) return

        ctx.bank(playerId, -offer.price)
        ctx.update(playerId) { it.copy(gamblers = it.gamblers + offer.card) }
        ctx.state = ctx.state.copy(
            interlude = shop.copy(
                bought = shop.bought + (playerId to shop.bought[playerId].orEmpty() + offerId),
            ),
        )
        ctx.emit(GameEvent.Bought(playerId, offer.card, offer.price, hidden = true))
    }

    /**
     * "I'm finished", and the sealed bid with it.
     *
     * One act, and bundling them is not a convenience — it removes a race. Were
     * bidding and shopping separate you could bid a hundred and then spend
     * sixty, and the auction would settle a bid you cannot pay.
     *
     * A bid over the purse is *clamped* rather than refused, which is the
     * contract everywhere else in this engine: an illegal target is replaced, a
     * short pick is filled in, an absent answer is invented. A client that lies
     * cannot win a lot it cannot pay for.
     *
     * Nought is not a bid. It collapses "nobody wanted it" and "everybody
     * shrugged" into one outcome with one code path, and it stops a table of
     * shrugs handing somebody a free jackpot.
     */
    private fun finishShopping(ctx: Ctx, playerId: String, bid: Int?) {
        val shop = ctx.state.interlude ?: return
        if (shop.closed || playerId in shop.done) return
        val player = ctx.player(playerId) ?: return
        val sealed = bid?.coerceIn(0, player.score)?.takeIf { it > 0 && shop.lot != null }
        ctx.state = ctx.state.copy(
            interlude = shop.copy(
                done = shop.done + playerId,
                bids = if (sealed == null) shop.bids else shop.bids + (playerId to sealed),
            ),
        )
    }

    /**
     * Reads the bids and hands over the lot.
     *
     * The hammer, and then the payment separately: sixty points coming off a
     * seat while the bids are still turning over hands the table the answer over
     * the top of the question, which is exactly what [PendingOutcome] exists to
     * stop. So the reveal goes out here and the money moves in a batch of its
     * own once it has been watched.
     */
    private fun settleAuction(ctx: Ctx, shop: Interlude): Interlude {
        val lot = shop.lot ?: return shop
        val seatOf = { id: String -> ctx.state.players.indexOfFirst { it.id == id } }

        fun canTake(id: String, price: Int): Boolean {
            val player = ctx.player(id) ?: return false
            return player.score >= price &&
                player.gamblers.size < gamblerLimitFor(ctx.state, id)
        }

        val ranked = shop.bids.entries
            .filter { it.value > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    // A tie goes to whoever is worse off — after the shop, which
                    // is who is actually poorer when the hammer falls...
                    .thenBy { ctx.player(it.key)?.score ?: 0 }
                    // ...and a tie in *that* goes by seat, so a replay from the
                    // seed settles it the same way twice. Nothing here rolls.
                    .thenBy { seatOf(it.key) },
            )

        // The first claim that can pay and has somewhere to put it. Somebody who
        // filled their last slot in the shop after bidding does not deadlock the
        // auction; the lot falls to the next one down.
        val won = ranked.firstOrNull { canTake(it.key, it.value) }
        var winner = won?.key
        var price = won?.value ?: 0

        // ...and then the rigged bids, in seat order, each one over the last.
        val rigged = mutableListOf<String>()
        if (winner != null) {
            for (player in ctx.state.players.filter { p -> p.gamblers.any { it.defId == RIGGED_BID.id } }) {
                val claim = (price * RIGGED_BID_PERCENT + 99) / 100
                if (player.id == winner || !canTake(player.id, claim)) continue
                val card = player.gamblers.first { it.defId == RIGGED_BID.id }
                ctx.update(player.id) { it.copy(gamblers = it.gamblers.filterNot { c -> c.id == card.id }) }
                ctx.toDiscard(card)
                winner = player.id
                price = claim
                rigged += player.id
            }
        }

        ctx.emit(GameEvent.AuctionClosed(lot.card, shop.bids, winner, price, rigged))
        if (winner == null) return shop.copy(sale = Sale(lot.card, shop.bids))
        // Watched first, paid for after — see [AUCTION_ID].
        ctx.land(AUCTION_ID, winner, winner)
        return shop.copy(sale = Sale(lot.card, shop.bids, winner, price, rigged))
    }

    private fun dealNextRound(ctx: Ctx) {
        val state = ctx.state

        // Everything on the table goes back to the discard pile, minus the
        // cards that were minted mid-round and never belonged to the deck.
        //
        // The gambler hand is deliberately not in here, and deliberately not
        // cleared below. That is the whole of what makes it a hand you keep: a
        // card bought two rounds ago is still yours, and tidying this up to
        // match the two piles either side of it would empty everybody's tray
        // between every round.
        val returned = state.players
            .flatMap { it.hand + it.passives }
            .filterNot { it.isEphemeral }

        val players = state.players.map {
            it.copy(
                hand = emptyList(), passives = emptyList(), handValue = 0,
                status = PlayerStatus.ACTIVE, bustReason = null, skipNextTurn = false,
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
            roundTolls = emptyMap(),
            pendingAction = null,
            pendingOutcomes = emptyList(),
            // Already empty — the round could not have ended with a card in
            // flight — and cleared here for the same reason everything else on
            // this list is: a new round starts from a table with nothing on it.
            responseStack = emptyList(),
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            dealQueue = dealOrder(players.map { it.id }, nextStart),
        )
    }
}
