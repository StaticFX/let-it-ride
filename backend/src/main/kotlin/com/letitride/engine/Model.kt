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
    /**
     * Effects this player is under for the rest of the round — see [MarkDef].
     * A mark is not a card: it cannot be stolen, swapped or scored, and it is
     * wiped when the round is dealt again.
     */
    val marks: Set<String> = emptySet(),
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
    /**
     * Seconds the scoreboard is left up before the next round deals itself, or
     * null to wait for the host. The countdown is the server's — five browsers
     * each running their own would drift — and pressing the button early always
     * wins, so it is a floor rather than a gate.
     */
    val autoNextRoundSeconds: Int? = null,
)

// ─── Game state ───

@Serializable
enum class GamePhase { LOBBY, PLAYING, ROUND_END, GAME_END }

/**
 * Why the table is stopped.
 *
 * Nearly always [PHASE_PLAY]: a card was drawn and is being pointed somewhere.
 * Anything else is a question something set up earlier — a bomb going off long
 * after the card that armed it was spent — and those carry their own targets,
 * because the card's target rule described how it was played rather than what
 * is being asked now.
 */
const val PHASE_PLAY = "play"

/** A player who was carrying a bomb busted, and is taking somebody with them. */
const val PHASE_BUST = "bust"

/** Somebody flipped out under "anti flip" and is deciding what to do with it. */
const val PHASE_FLIP_CHOICE = "flipChoice"

/** ...and chose to spend it, so now they are picking who pays. */
const val PHASE_FLIP_TARGET = "flipTarget"

/** Two players are throwing against each other, at the same time. */
const val PHASE_THROW = "throw"

/** The whole table is betting a card face down. */
const val PHASE_BET = "bet"

/** Somebody is buying a card out of their own score. */
const val PHASE_BUY = "buy"

/** What a card asks its drawer to point at. */
@Serializable
enum class PickKind {
    /** A seat at the table. */
    @SerialName("player")
    PLAYER,

    /** Cards lying on the table, whoever is holding them. */
    @SerialName("card")
    CARD,

    /**
     * A card that is not in play at all — one the deck could deal, chosen from
     * a list of what it holds and what each would cost. The pick comes back in
     * the same field a card pick does; what it names is an offer rather than a
     * card on the table.
     */
    @SerialName("catalog")
    CATALOG,
}

/**
 * One card on sale, and what it costs. The server prices it and decides who can
 * afford it — the client only has to draw the face and the number under it.
 */
@Serializable
data class Offer(
    /** Names a card the deck could deal — see `offerIdFor`. */
    val id: String,
    val price: Int,
    /** A face to draw. Not a card in the game; nothing is holding it. */
    val card: Card,
)

/**
 * One responder's answer to a prompt.
 *
 * Answers are held on the server and never sent anywhere while the prompt is
 * open — not even to the player who gave one. That is what makes a simultaneous
 * prompt secret without any per-viewer projection: there is nothing to leak,
 * because nothing is transmitted. What everybody threw is announced by the
 * event the resolution emits, all at once, after it is too late to change.
 */
@Serializable
data class Answer(
    val targetId: String? = null,
    val choice: String? = null,
    val cards: List<String> = emptyList(),
)

@Serializable
data class PendingAction(
    val cardDefId: String,
    val playerId: String,
    /** The physical card, so it can be moved to the discard pile once resolved. */
    val card: Card,
    /** Who this card could actually be played on, worked out when it was drawn. */
    val validTargets: List<String> = emptyList(),
    /**
     * The question the drawer has to answer, if the card asks one — heads or
     * tails, left or right. Empty for every card that only needs a target.
     */
    val options: List<String> = emptyList(),
    /** What is being pointed at. Nearly every card points at a seat. */
    val kind: PickKind = PickKind.PLAYER,
    /** The cards that may be picked, when [kind] is [PickKind.CARD]. */
    val validCards: List<String> = emptyList(),
    /** How many picks are owed before the card resolves. Swapping wants two. */
    val picks: Int = 1,
    /** Why the table is stopped — see [PHASE_PLAY]. */
    val phase: String = PHASE_PLAY,
    /** What is for sale, when [kind] is [PickKind.CATALOG]. */
    val offers: List<Offer> = emptyList(),
    /**
     * Everybody who owes an answer before this resolves. Empty means [playerId]
     * alone, which is every prompt but the handful that ask the table at once —
     * so the common case says nothing and costs nothing.
     */
    val responders: List<String> = emptyList(),
    /** What each responder has said. Server-side only — see [Answer]. */
    val answers: Map<String, Answer> = emptyMap(),
) {
    /** Everyone who has to answer, with the single-responder case spelled out. */
    val respondents: List<String> get() = responders.ifEmpty { listOf(playerId) }

    /** Who has not answered yet. */
    val waitingOn: List<String> get() = respondents.filterNot { it in answers }

    val allAnswered: Boolean get() = waitingOn.isEmpty()
}

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
    /**
     * Points added or taken away during the round that are not hand scoring —
     * an anti-flip deduction, and later a purchase or a penalty. Folded into the
     * deltas when the round is scored, and wiped with everything else when the
     * next one is dealt.
     */
    val roundAdjustments: Map<String, Int> = emptyMap(),
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
        /** The drawer's answer to [PendingAction.options]; null when none was asked. */
        val choice: String? = null,
        /**
         * The cards picked, for a card that points at cards rather than a seat.
         * Empty for every card that only wants a target — which is nearly all
         * of them, so it stays out of the way of the common case.
         */
        val cards: List<String> = emptyList(),
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
