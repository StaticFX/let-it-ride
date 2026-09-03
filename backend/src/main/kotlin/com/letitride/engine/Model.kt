package com.letitride.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Cards ───

@Serializable
enum class CardKind {
    @SerialName("number")
    NUMBER,

    @SerialName("action")
    ACTION,

    @SerialName("passive")
    PASSIVE,
}

/**
 * A physical card in the game.
 *
 * [label] is what is printed on the card and is also the bust-matching key:
 * two number cards with the same label are duplicates. For the numeric decks
 * that is the value ("7"), for the classic 52-card deck it is the rank ("K").
 */
@Serializable
data class Card(
    val id: String,
    val kind: CardKind,
    val label: String,
    val value: Int,
    val defId: String? = null,
    val suit: String? = null,
) {
    /** Minted mid-round (e.g. by double-or-nothing); never returns to the deck. */
    val isEphemeral: Boolean get() = id.startsWith("tmp-")
}

// ─── Players ───

@Serializable
enum class PlayerStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("stayed")
    STAYED,

    @SerialName("bust")
    BUST,
}

@Serializable
data class Player(
    val id: String,
    val name: String,
    val hand: List<Card> = emptyList(),
    val passives: List<Card> = emptyList(),
    val handValue: Int = 0,
    val status: PlayerStatus = PlayerStatus.ACTIVE,
    val score: Int = 0,
    val bustReason: String? = null,
    val skipNextTurn: Boolean = false,
    val connected: Boolean = true,
    val isBot: Boolean = false,
)

// ─── Config ───

@Serializable
enum class WinCondition {
    @SerialName("rounds")
    ROUNDS,

    @SerialName("first_to_score")
    FIRST_TO_SCORE,
}

/** `count` copies of a number card worth `value`, printed as `label`. */
@Serializable
data class NumberCardEntry(
    val value: Int,
    val count: Int,
    val label: String? = null,
    val suits: List<String>? = null,
)

/**
 * Action and passive cards are listed as definition ids, repeated once per
 * physical copy — so `["freeze", "freeze", "freeze"]` puts three freezes in
 * the deck. Keeping the deck as plain ids is what makes a game config
 * round-trippable over the wire.
 */
@Serializable
data class DeckConfig(
    val numberCards: List<NumberCardEntry> = emptyList(),
    val actionCards: List<String> = emptyList(),
    val passiveCards: List<String> = emptyList(),
)

@Serializable
data class GameConfig(
    val deckPresetId: String = "letitride",
    val deck: DeckConfig,
    val ruleIds: List<String> = emptyList(),
    val winCondition: WinCondition = WinCondition.ROUNDS,
    val totalRounds: Int = 5,
    val targetScore: Int = 200,
    val turnTimeSeconds: Int = 30,
)

// ─── Game state ───

@Serializable
enum class GamePhase { LOBBY, PLAYING, ROUND_END, GAME_END }

@Serializable
data class PendingAction(
    val cardDefId: String,
    val playerId: String,
    /** The physical card, so it can be moved to the discard pile once resolved. */
    val card: Card,
    /** Who this card could actually be played on, worked out when it was drawn. */
    val validTargets: List<String> = emptyList(),
)

@Serializable
data class ForcedDraws(
    val playerId: String,
    val remaining: Int,
    /** Which card queued these, so the room can pace its animation. */
    val source: String? = null,
)

@Serializable
data class GameState(
    val phase: GamePhase = GamePhase.LOBBY,
    val round: Int = 0,
    val players: List<Player> = emptyList(),
    val turnIndex: Int = 0,
    val roundStartPlayer: Int = 0,
    val config: GameConfig,
    val deck: List<Card> = emptyList(),
    val discard: List<Card> = emptyList(),
    val pendingAction: PendingAction? = null,
    val forcedDraws: ForcedDraws? = null,
    val forcedDrawStack: List<ForcedDraws> = emptyList(),
    /** Players still owed their opening card this round, in dealing order. */
    val dealQueue: List<String> = emptyList(),
    val roundWinnerId: String? = null,
    val gameWinnerId: String? = null,
    val flip7PlayerId: String? = null,
    /** Points each player banked in the round that just ended, for the summary screen. */
    val roundDeltas: Map<String, Int> = emptyMap(),
) {
    fun player(id: String): Player? = players.firstOrNull { it.id == id }

    val currentPlayer: Player? get() = players.getOrNull(turnIndex)

    /** True while the engine is waiting on something other than the current player's move. */
    val isInterrupted: Boolean get() = pendingAction != null || forcedDraws != null
}

// ─── Actions ───

@Serializable
sealed class GameAction {
    @Serializable
    @SerialName("ADD_PLAYER")
    data class AddPlayer(val playerId: String, val name: String, val isBot: Boolean = false) : GameAction()

    @Serializable
    @SerialName("REMOVE_PLAYER")
    data class RemovePlayer(val playerId: String) : GameAction()

    @Serializable
    @SerialName("SET_CONNECTED")
    data class SetConnected(val playerId: String, val connected: Boolean) : GameAction()

    @Serializable
    @SerialName("SET_CONFIG")
    data class SetConfig(val config: GameConfig) : GameAction()

    @Serializable
    @SerialName("START_GAME")
    data object StartGame : GameAction()

    @Serializable
    @SerialName("DEAL_TO")
    data class DealTo(val playerId: String) : GameAction()

    @Serializable
    @SerialName("HIT")
    data class Hit(val playerId: String) : GameAction()

    @Serializable
    @SerialName("STAY")
    data class Stay(val playerId: String) : GameAction()

    @Serializable
    @SerialName("PLAY_ACTION")
    data class PlayAction(
        val fromPlayerId: String,
        val targetPlayerId: String,
        val cardDefId: String,
    ) : GameAction()

    @Serializable
    @SerialName("FORCED_DRAW")
    data object ForcedDraw : GameAction()

    /** The turn clock ran out for [playerId]. */
    @Serializable
    @SerialName("TIMEOUT")
    data class Timeout(val playerId: String) : GameAction()

    @Serializable
    @SerialName("NEXT_ROUND")
    data object NextRound : GameAction()
}
