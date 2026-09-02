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
 */
fun GameState.allCardIds(): List<String> =
    (deck + discard + players.flatMap { it.hand + it.passives } + listOfNotNull(pendingAction?.card))
        .filterNot { it.isEphemeral }
        .map { it.id }
