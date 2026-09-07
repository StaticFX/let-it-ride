package com.letitride.engine

/** Deterministic across the whole suite so a failure is always reproducible. */
fun testRng(seed: Long = 20260902L) = Rng(seed)

val DEFAULT_RNG = testRng()

fun t(state: GameState, action: GameAction, rng: Rng = DEFAULT_RNG): GameState =
    Engine.transition(state, action, rng).state

fun tr(state: GameState, action: GameAction, rng: Rng = DEFAULT_RNG): TransitionResult =
    Engine.transition(state, action, rng)

fun num(value: Int, label: String = value.toString(), id: String = "n-$label-$value"): Card =
    Card(id = id, kind = CardKind.NUMBER, label = label, value = value)

fun action(defId: String, id: String = "a-$defId"): Card =
    Card(id = id, kind = CardKind.ACTION, label = defId, value = 0, defId = defId)

fun passive(defId: String, id: String = "p-$defId"): Card =
    Card(id = id, kind = CardKind.PASSIVE, label = defId, value = 0, defId = defId)

fun gambler(defId: String, id: String = "g-$defId"): Card =
    Card(id = id, kind = CardKind.GAMBLER, label = defId, value = 0, defId = defId)

/** Puts a gambler card straight into a hidden hand, the way the shop will. */
fun GameState.holding(playerId: String, vararg cards: Card): GameState =
    copy(players = players.map { if (it.id == playerId) it.copy(gamblers = it.gamblers + cards) else it })

fun config(
    deck: DeckConfig = DeckPresets.PURE.deck,
    rules: List<String> = emptyList(),
    winCondition: WinCondition = WinCondition.ROUNDS,
    totalRounds: Int = 3,
    targetScore: Int = 200,
) = GameConfig(
    deckPresetId = "test",
    deck = deck,
    ruleIds = rules,
    winCondition = winCondition,
    totalRounds = totalRounds,
    targetScore = targetScore,
)

fun lobby(config: GameConfig = config(), players: List<String> = listOf("a", "b")): GameState {
    var state = Engine.newGame(config)
    for (id in players) state = t(state, GameAction.AddPlayer(id, id))
    return state
}

/** Starts a game and hands the caller a deck they control completely. */
fun started(
    config: GameConfig = config(),
    players: List<String> = listOf("a", "b"),
    deck: List<Card>? = null,
): GameState {
    val state = t(lobby(config, players), GameAction.StartGame)
    return if (deck != null) state.copy(deck = deck) else state
}

/**
 * Applies every card that has landed and is waiting to take effect — see
 * [PendingOutcome]. The room does this once the table has watched the coin come
 * down; a test that only cares what the coin was worth says so here.
 */
fun settle(state: GameState, rng: Rng = DEFAULT_RNG): GameState {
    var current = state
    var guard = 0
    while (current.pendingOutcomes.isNotEmpty() && guard++ < 16) {
        current = t(current, GameAction.ResolveOutcome, rng)
    }
    return current
}

/** ...and the same, keeping the events the outcome produced. */
fun settled(result: TransitionResult, rng: Rng = DEFAULT_RNG): TransitionResult {
    var state = result.state
    val events = result.events.toMutableList()
    var guard = 0
    while (state.pendingOutcomes.isNotEmpty() && guard++ < 16) {
        val next = tr(state, GameAction.ResolveOutcome, rng)
        state = next.state
        events += next.events
    }
    return TransitionResult(state, events)
}

/**
 * Walks a rolling-rules table through the shop without buying anything.
 *
 * "Next round" opens a window rather than dealing one in that mode, so a test
 * that only wants the round after this has to say so.
 */
fun throughInterlude(state: GameState, rng: Rng = DEFAULT_RNG): GameState {
    var current = state
    var guard = 0
    while (current.interlude != null && guard++ < 64) {
        val waiting = current.waitingOnShop.firstOrNull()
        current = when {
            current.interlude?.closed == true -> t(current, GameAction.OpenRound, rng)
            waiting != null -> t(current, GameAction.FinishShopping(waiting), rng)
            else -> t(current, GameAction.CloseInterlude, rng)
        }
    }
    return current
}

/** Runs the opening deal to completion. */
fun finishDeal(state: GameState): GameState {
    var current = state
    var guard = 0
    while (current.dealQueue.isNotEmpty() && guard++ < 32) {
        val next = current.dealQueue.first()
        val after = t(current, GameAction.DealTo(next))
        if (after == current) break
        current = after
    }
    return current
}

/** Deals from a rigged deck so every player opens with a known, distinct card. */
fun startedAndDealt(
    config: GameConfig = config(),
    players: List<String> = listOf("a", "b"),
    openingCards: List<Card> = players.indices.map { num(it + 1) },
    rest: List<Card> = emptyList(),
): GameState = finishDeal(started(config, players, openingCards + rest))

fun GameState.hand(id: String): List<Card> = player(id)!!.hand

fun GameState.status(id: String): PlayerStatus = player(id)!!.status

/**
 * Every card that exists anywhere in the game right now, including the one
 * being held out while its owner picks a target.
 *
 * The gambler zones are in here for the same reason everything else is: a
 * gambler card comes off the same deck, so it has to be counted wherever it has
 * got to — a hidden hand, the dealer's reserve — and the total must not move
 * when it goes from one to the other. A zone missing from this list is a zone
 * cards can quietly vanish into while the suite stays green, which is the one
 * failure mode this function exists to prevent.
 */
fun GameState.allCardIds(): List<String> =
    (
        deck + discard +
            players.flatMap { it.hand + it.passives + it.gamblers } +
            listOfNotNull(pendingAction?.card) +
            // A card in flight has left its owner's hand and has not reached the
            // discard pile, exactly like the one a prompt is holding out.
            responseStack.map { it.card }
        )
        .filterNot { it.isEphemeral }
        .map { it.id }
