package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckConfig
import com.letitride.engine.DeckPreset
import com.letitride.engine.DeckPresets
import com.letitride.engine.ForcedDraws
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.LobbyRules
import com.letitride.engine.PassiveScoring
import com.letitride.engine.Player
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════
// State pushed to clients
// ═══════════════════════════════════════════

@Serializable
data class PendingActionView(
    val cardDefId: String,
    val playerId: String,
    /**
     * The physical card's id. The client keys its "already picked" state on
     * this: two strikes in one round share a cardDefId, and keying on that
     * left the second one unclickable.
     */
    val cardId: String,
    /** The only seats this card may be pointed at; the picker offers no others. */
    val validTargets: List<String>,
)

/**
 * A batch of events the client is still animating. Nothing else happens at the
 * table until the animation reports itself finished, which is what stops a card
 * landing on top of a bust or a freeze that is still playing.
 *
 * Exactly one client owns each gate. Durations live entirely in the client —
 * the server never guesses how long a bust takes, it only refuses to move until
 * it is told, and gives up at [timeoutAt] so a hung tab cannot own the room.
 */
@Serializable
data class AnimationGateView(
    val id: Long,
    /** The one client whose ack releases the table; everyone else just watches. */
    val ackPlayerId: String,
    /** Epoch millis the server stops waiting and steps anyway. */
    val timeoutAt: Long,
)

/**
 * The client's view of a game. The deck is deliberately reduced to a count —
 * the server is the only thing that ever knows what is coming next, and the
 * flying-card animation is driven by draw events instead.
 */
@Serializable
data class GameStateView(
    val roomCode: String,
    val hostId: String?,
    val phase: GamePhase,
    val round: Int,
    val players: List<Player>,
    val turnIndex: Int,
    val roundStartPlayer: Int,
    val config: GameConfig,
    val deckCount: Int,
    val discardCount: Int,
    val pendingAction: PendingActionView? = null,
    val forcedDraws: ForcedDraws? = null,
    val dealQueue: List<String> = emptyList(),
    val roundWinnerId: String? = null,
    val gameWinnerId: String? = null,
    val flip7PlayerId: String? = null,
    val roundDeltas: Map<String, Int> = emptyMap(),
    /** Epoch millis the current actor's clock runs out, or null when nothing is timed. */
    val turnDeadline: Long? = null,
    /**
     * Epoch millis the round's title card stops showing. Nothing is dealt until
     * it passes, so the client and the deal cannot drift apart.
     */
    val roundIntroUntil: Long? = null,
    /** Epoch millis the round's closing card appears, after the last animation. */
    val roundOutroFrom: Long? = null,
    /** Epoch millis the closing card gives way to the scoreboard. */
    val roundOutroUntil: Long? = null,
    /** The animation the table is currently held on, if any. */
    val animationGate: AnimationGateView? = null,
)

fun GameState.toView(
    roomCode: String,
    hostId: String?,
    turnDeadline: Long?,
    roundIntroUntil: Long? = null,
    roundOutroFrom: Long? = null,
    roundOutroUntil: Long? = null,
    animationGate: AnimationGateView? = null,
) = GameStateView(
    roomCode = roomCode,
    hostId = hostId,
    phase = phase,
    round = round,
    players = players,
    turnIndex = turnIndex,
    roundStartPlayer = roundStartPlayer,
    config = config,
    deckCount = deck.size,
    discardCount = discard.size,
    pendingAction = pendingAction?.let {
        PendingActionView(it.cardDefId, it.playerId, it.card.id, it.validTargets)
    },
    forcedDraws = forcedDraws,
    dealQueue = dealQueue,
    roundWinnerId = roundWinnerId,
    gameWinnerId = gameWinnerId,
    flip7PlayerId = flip7PlayerId,
    roundDeltas = roundDeltas,
    turnDeadline = turnDeadline,
    roundIntroUntil = roundIntroUntil,
    roundOutroFrom = roundOutroFrom,
    roundOutroUntil = roundOutroUntil,
    animationGate = animationGate,
)

// ═══════════════════════════════════════════
// Socket protocol
// ═══════════════════════════════════════════

@Serializable
sealed class ClientMessage {
    @Serializable
    @SerialName("HIT")
    data object Hit : ClientMessage()

    @Serializable
    @SerialName("STAY")
    data object Stay : ClientMessage()

    @Serializable
    @SerialName("PLAY_ACTION")
    data class PlayAction(val targetPlayerId: String, val cardDefId: String) : ClientMessage()

    @Serializable
    @SerialName("SET_CONFIG")
    data class SetConfig(val config: GameConfig) : ClientMessage()

    @Serializable
    @SerialName("START_GAME")
    data object StartGame : ClientMessage()

    @Serializable
    @SerialName("NEXT_ROUND")
    data object NextRound : ClientMessage()

    @Serializable
    @SerialName("KICK")
    data class Kick(val playerId: String) : ClientMessage()

    @Serializable
    @SerialName("ADD_BOT")
    data object AddBot : ClientMessage()

    @Serializable
    @SerialName("PING")
    data object Ping : ClientMessage()

    /**
     * The client finished animating the batch [gateId] was opened for. Only the
     * gate's own [AnimationGateView.ackPlayerId] releases it; a stale or
     * forwarded id is ignored, so a client cannot skip somebody else's
     * animation by guessing.
     */
    @Serializable
    @SerialName("ANIM_DONE")
    data class AnimationDone(val gateId: Long) : ClientMessage()
}

@Serializable
sealed class ServerMessage {
    @Serializable
    @SerialName("WELCOME")
    data class Welcome(
        val playerId: String,
        val roomCode: String,
        val isHost: Boolean,
    ) : ServerMessage()

    @Serializable
    @SerialName("STATE")
    data class State(
        val state: GameStateView,
        val events: List<GameEvent> = emptyList(),
    ) : ServerMessage()

    @Serializable
    @SerialName("ERROR")
    data class Error(val message: String) : ServerMessage()

    @Serializable
    @SerialName("KICKED")
    data object Kicked : ServerMessage()

    @Serializable
    @SerialName("PONG")
    data object Pong : ServerMessage()
}

// ═══════════════════════════════════════════
// REST payloads
// ═══════════════════════════════════════════

@Serializable
data class CreateRoomRequest(
    val name: String,
    /**
     * Fixes the room's shuffles so a run can be replayed card for card. Ignored
     * unless the server was started with test hooks on — see [TEST_HOOKS_ENV].
     */
    val seed: Long? = null,
)

/**
 * Set to `1`/`true` to let clients pin a room's seed. Only the end-to-end suite
 * turns this on: a public server that honoured it would let anyone deal
 * themselves a known deck.
 */
const val TEST_HOOKS_ENV = "LETITRIDE_TEST_HOOKS"

fun testHooksEnabled(env: (String) -> String? = System::getenv): Boolean =
    env(TEST_HOOKS_ENV)?.lowercase() in setOf("1", "true", "yes")

@Serializable
data class CreateRoomResponse(val roomCode: String, val playerId: String)

@Serializable
data class JoinRoomRequest(val name: String, val roomCode: String)

@Serializable
data class JoinRoomResponse(val roomCode: String, val playerId: String)

@Serializable
data class RoomInfoResponse(val roomCode: String, val players: Int, val phase: GamePhase, val joinable: Boolean)

@Serializable
data class ApiError(val error: String)

// ═══════════════════════════════════════════
// Catalog — everything the UI needs to draw cards it does not own the rules for
// ═══════════════════════════════════════════

@Serializable
data class ActionCardInfo(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val selfTarget: Boolean,
)

@Serializable
data class PassiveCardInfo(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val bonusPoints: Int,
    val scoring: String,
)

@Serializable
data class LobbyRuleInfo(val id: String, val name: String, val description: String)

/** One row of a deck listing: a card face plus how many copies are in the deck. */
@Serializable
data class DeckEntryInfo(val card: Card, val count: Int)

@Serializable
data class DeckPresetInfo(
    val id: String,
    val name: String,
    val description: String,
    val cardCount: Int,
    val deck: DeckConfig,
    val contents: List<DeckEntryInfo>,
)

@Serializable
data class CatalogResponse(
    val actions: List<ActionCardInfo>,
    val passives: List<PassiveCardInfo>,
    val rules: List<LobbyRuleInfo>,
    val decks: List<DeckPresetInfo>,
    val flip7Bonus: Int,
    val flip7Target: Int,
    val minPlayers: Int,
    val maxPlayers: Int,
)

private fun DeckPreset.contents(): List<DeckEntryInfo> {
    val entries = mutableListOf<DeckEntryInfo>()

    for (entry in deck.numberCards) {
        val label = entry.label ?: entry.value.toString()
        entries += DeckEntryInfo(
            card = Card(
                id = "preview-n-$label",
                kind = CardKind.NUMBER,
                label = label,
                value = entry.value,
                suit = entry.suits?.firstOrNull(),
            ),
            count = entry.count,
        )
    }

    for ((defId, count) in deck.actionCards.groupingBy { it }.eachCount()) {
        val def = Catalog.action(defId) ?: continue
        entries += DeckEntryInfo(
            card = Card(id = "preview-a-$defId", kind = CardKind.ACTION, label = def.name, value = 0, defId = defId),
            count = count,
        )
    }

    for ((defId, count) in deck.passiveCards.groupingBy { it }.eachCount()) {
        val def = Catalog.passive(defId) ?: continue
        entries += DeckEntryInfo(
            card = Card(id = "preview-p-$defId", kind = CardKind.PASSIVE, label = def.name, value = 0, defId = defId),
            count = count,
        )
    }

    return entries
}

fun buildCatalog(): CatalogResponse = CatalogResponse(
    actions = Catalog.actions.values.map {
        ActionCardInfo(it.id, it.name, it.description, it.sigil, it.selfTarget)
    },
    passives = Catalog.passives.values.map {
        PassiveCardInfo(
            it.id, it.name, it.description, it.sigil, it.bonusPoints,
            when (it.scoring) {
                PassiveScoring.FLAT -> "flat"
                PassiveScoring.DOUBLE_NUMBERS -> "double"
                PassiveScoring.NONE -> "none"
            },
        )
    },
    rules = LobbyRules.all.map { LobbyRuleInfo(it.id, it.name, it.description) },
    decks = DeckPresets.all.map {
        DeckPresetInfo(it.id, it.name, it.description, it.cardCount, it.deck, it.contents())
    },
    flip7Bonus = com.letitride.engine.FLIP7_BONUS,
    flip7Target = com.letitride.engine.FLIP7_TARGET,
    minPlayers = com.letitride.engine.MIN_PLAYERS,
    maxPlayers = com.letitride.engine.MAX_PLAYERS,
)
