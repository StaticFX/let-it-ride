package com.letitride.engine

/** Points awarded on top of the hand for collecting seven unique number cards. */
const val FLIP7_BONUS = 15

/** Number of unique number cards that ends the round instantly. */
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

    fun bust(playerId: String, reason: String) {
        val player = player(playerId) ?: return
        if (player.status == PlayerStatus.BUST) return
        update(playerId) { it.copy(status = PlayerStatus.BUST, bustReason = reason) }
        emit(GameEvent.Bust(playerId, reason))
    }

    fun skip(playerId: String) {
        update(playerId) { it.copy(skipNextTurn = true) }
        emit(GameEvent.Skip(playerId))
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

    fun pushForcedDraws(playerId: String, count: Int) {
        val previous = state.forcedDraws
        state = state.copy(
            forcedDraws = ForcedDraws(playerId, count),
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
            consumeSecondChance(playerId, result.duplicate)
            return false
        }
        bust(playerId, result.reason)
        return true
    }

    fun consumeSecondChance(playerId: String, duplicate: Card) {
        consumePassive(playerId, SECOND_LIFE.id)
        discardFromHand(playerId, duplicate)
        emit(GameEvent.SecondChance(playerId, duplicate))
    }

    // ─── Bust rules ───

    data class BustResult(val reason: String, val duplicate: Card? = null)

    fun checkBust(player: Player): BustResult? {
        val seen = mutableSetOf<String>()
        for (card in player.hand) {
            if (!seen.add(card.label)) return BustResult(BUST_DUPLICATE, card)
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
            players = state.players.map {
                it.copy(
                    hand = emptyList(), passives = emptyList(), handValue = 0,
                    status = PlayerStatus.ACTIVE, score = 0, bustReason = null, skipNextTurn = false,
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
            // "When it's an action card, a random player gets it."
            val candidates = ctx.activePlayers().filter { it.id != playerId }
            val target = ctx.rng.pick(candidates)?.id ?: playerId
            ctx.emit(GameEvent.Timeout(playerId))
            resolvePendingAction(ctx, pending, playerId, target)
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

        if (def.selfTarget || ctx.rules.forceSelfTarget) {
            runAction(ctx, def, card, playerId, playerId)
            return when {
                ctx.player(playerId)?.status == PlayerStatus.BUST -> DrawOutcome.BUSTED
                ctx.state.forcedDraws != null || ctx.state.pendingAction != null -> DrawOutcome.PAUSED
                anyFlip7(ctx) != null -> DrawOutcome.FLIP7
                else -> DrawOutcome.CONTINUE
            }
        }

        ctx.state = ctx.state.copy(
            pendingAction = PendingAction(cardDefId = def.id, playerId = playerId, card = card),
        )
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
                ctx.consumeSecondChance(playerId, duplicate)
            } else {
                ctx.bust(playerId, bust.reason)
                return DrawOutcome.BUSTED
            }
        }

        val after = ctx.player(playerId) ?: return DrawOutcome.CONTINUE
        if (after.hand.size >= FLIP7_TARGET) return DrawOutcome.FLIP7
        return DrawOutcome.CONTINUE
    }

    // ═══════════════════════════════════════════
    // Action cards
    // ═══════════════════════════════════════════

    private fun playPendingAction(ctx: Ctx, action: GameAction.PlayAction) {
        val pending = ctx.state.pendingAction ?: return
        if (pending.playerId != action.fromPlayerId) return
        if (pending.cardDefId != action.cardDefId) return
        resolvePendingAction(ctx, pending, action.fromPlayerId, action.targetPlayerId)
    }

    private fun resolvePendingAction(ctx: Ctx, pending: PendingAction, fromId: String, requestedTargetId: String) {
        val def = Catalog.action(pending.cardDefId)
        ctx.state = ctx.state.copy(pendingAction = null)

        if (def == null) {
            ctx.toDiscard(pending.card)
            afterAction(ctx)
            return
        }

        // "Womp womp" overrides the pick; otherwise the target must still be in the round.
        val targetId = if (ctx.rules.forceSelfTarget) fromId else requestedTargetId
        val target = ctx.player(targetId)
        val resolvedTarget = when {
            target == null -> fromId
            target.status != PlayerStatus.ACTIVE && target.id != fromId -> fromId
            else -> target.id
        }

        runAction(ctx, def, pending.card, fromId, resolvedTarget)
        afterAction(ctx)
    }

    private fun runAction(ctx: Ctx, def: ActionCardDef, card: Card?, fromId: String, targetId: String) {
        card?.let { ctx.toDiscard(it) }
        ctx.emit(GameEvent.ActionPlayed(def.id, fromId, targetId))
        // "Double it!" fires the same effect twice.
        repeat(ctx.rules.actionRepeat) {
            val from = ctx.player(fromId) ?: return@repeat
            val target = ctx.player(targetId) ?: return@repeat
            def.onPlay(ctx, from, target)
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
        ctx.state.players.firstOrNull { it.status == PlayerStatus.ACTIVE && it.hand.size >= FLIP7_TARGET }?.id

    /** Seven unique cards ends the round for everyone, right now. */
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
        var total = player.hand.sumOf { it.value }
        for (card in player.passives) {
            if (Catalog.passive(card.defId)?.scoring == PassiveScoring.DOUBLE_NUMBERS) total *= 2
        }
        for (card in player.passives) {
            val def = Catalog.passive(card.defId) ?: continue
            if (def.scoring == PassiveScoring.FLAT) total += def.bonusPoints
        }
        if (player.id == flip7PlayerId) total += FLIP7_BONUS
        return total
    }

    private fun enterRoundEnd(ctx: Ctx) {
        val state = ctx.state
        val deltas = state.players.associate { it.id to roundScore(it, state.flip7PlayerId) }

        val winner = state.players
            .filter { it.status != PlayerStatus.BUST }
            .sortedWith(
                compareByDescending<Player> { deltas[it.id] ?: 0 }.thenBy { it.hand.size },
            )
            .firstOrNull()

        val scored = state.copy(
            phase = GamePhase.ROUND_END,
            players = state.players.map { it.copy(score = it.score + (deltas[it.id] ?: 0)) },
            roundDeltas = deltas,
            roundWinnerId = winner?.id,
            pendingAction = null,
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            dealQueue = emptyList(),
        )
        ctx.state = scored
        ctx.emit(GameEvent.RoundScored(deltas, winner?.id))

        // The winner is recorded now but the summary screen still shows first;
        // NEXT_ROUND is what actually moves the game to GAME_END.
        gameWinner(scored)?.let { ctx.state = ctx.state.copy(gameWinnerId = it) }
    }

    private fun gameWinner(state: GameState): String? {
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
            pendingAction = null,
            forcedDraws = null,
            forcedDrawStack = emptyList(),
            dealQueue = dealOrder(players.map { it.id }, nextStart),
        )
    }
}
