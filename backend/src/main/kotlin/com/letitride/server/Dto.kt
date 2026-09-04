package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckConfig
import com.letitride.engine.DeckLimits
import com.letitride.engine.DeckPreset
import com.letitride.engine.DeckPresets
import com.letitride.engine.ForcedDraws
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.LobbyRules
import com.letitride.engine.PassiveScoring
import com.letitride.engine.PickKind
import com.letitride.engine.Player
import com.letitride.engine.RuleSet
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
    /**
     * The question the card asks its drawer — "heads"/"tails", "left"/"right".
     * Non-empty means the client has to send a `choice` back with the pick, and
     * the table is waiting on the answer even when there is only one seat to
     * point at. Empty for every card that only wants a target.
     */
    val options: List<String> = emptyList(),
    /**
     * What the drawer is pointing at — a seat, or cards off the table. The
     * client picks its picker from this.
     */
    val kind: PickKind = PickKind.PLAYER,
    /** The cards that may be picked, when [kind] is [PickKind.CARD]. */
    val validCards: List<String> = emptyList(),
    /** How many picks are owed before the card resolves. */
    val picks: Int = 1,
    /** What is for sale, when [kind] is `catalog`. Priced by the server. */
    val offers: List<com.letitride.engine.Offer> = emptyList(),
    /**
     * Why the table is stopped — see [com.letitride.engine.PHASE_PLAY]. Anything
     * other than "play" is a question something set up earlier, which arrives
     * with no card being drawn and so needs saying out loud.
     */
    val phase: String = com.letitride.engine.PHASE_PLAY,
    /**
     * Everybody who owes an answer. One name for nearly every prompt; the
     * handful that ask the table at once name everybody.
     */
    val responders: List<String> = emptyList(),
    /**
     * Who has answered so far — the names only. What they said is never sent
     * while the prompt is open, which is what makes a simultaneous prompt
     * secret without any per-viewer filtering: there is nothing here to leak.
     */
    val answered: List<String> = emptyList(),
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
    /**
     * Unique cards this room's rules actually play to — 7 normally, 9 with
     * "flip 9" on. The catalog's copy is only the default, so anything that
     * counts down to the flip has to read it from here.
     */
    val flip7Target: Int,
    val roundDeltas: Map<String, Int> = emptyMap(),
    /**
     * Points moved during the round by something other than hand scoring, so a
     * player who scored nothing can be told why rather than shown a bare zero.
     * Already folded into [roundDeltas]; this is the itemisation.
     */
    val roundAdjustments: Map<String, Int> = emptyMap(),
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
    /**
     * Epoch millis the next round deals itself, under the host's autostart
     * setting. Null when the table is waiting to be told — and always null once
     * the game is settled.
     */
    val nextRoundAt: Long? = null,
    /**
     * The next few cards off the deck, for the testing panel to show and stack
     * — see [DevMode]. Null on every real server, and the field then does not
     * go out at all: knowing what is coming is the one thing a player must not
     * be able to find out.
     */
    val devDeck: List<Card>? = null,
)

fun GameState.toView(
    roomCode: String,
    hostId: String?,
    turnDeadline: Long?,
    roundIntroUntil: Long? = null,
    roundOutroFrom: Long? = null,
    roundOutroUntil: Long? = null,
    animationGate: AnimationGateView? = null,
    nextRoundAt: Long? = null,
    devDeck: List<Card>? = null,
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
        // Named, because the list has grown past the point where the order of
        // it means anything to a reader.
        PendingActionView(
            cardDefId = it.cardDefId,
            playerId = it.playerId,
            cardId = it.card.id,
            validTargets = it.validTargets,
            options = it.options,
            kind = it.kind,
            validCards = it.validCards,
            picks = it.picks,
            offers = it.offers,
            phase = it.phase,
            responders = it.respondents,
            // The names of who has answered, never what any of them said.
            answered = it.answers.keys.toList(),
        )
    },
    forcedDraws = forcedDraws,
    dealQueue = dealQueue,
    roundWinnerId = roundWinnerId,
    gameWinnerId = gameWinnerId,
    flip7PlayerId = flip7PlayerId,
    flip7Target = RuleSet.of(config).flipTarget,
    roundDeltas = roundDeltas,
    roundAdjustments = roundAdjustments,
    turnDeadline = turnDeadline,
    roundIntroUntil = roundIntroUntil,
    roundOutroFrom = roundOutroFrom,
    roundOutroUntil = roundOutroUntil,
    animationGate = animationGate,
    nextRoundAt = nextRoundAt,
    devDeck = devDeck,
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

    /**
     * [choice] answers the card's [PendingActionView.options]. It defaults to
     * null so a client that never sends one still decodes — the engine falls
     * back to the card's first option rather than refusing the play.
     */
    @Serializable
    @SerialName("PLAY_ACTION")
    data class PlayAction(
        val targetPlayerId: String,
        val cardDefId: String,
        val choice: String? = null,
        /**
         * The cards picked, for a card that points at cards rather than a seat.
         * Empty for every card that only wants a target.
         */
        val cards: List<String> = emptyList(),
    ) : ClientMessage()

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

    /**
     * Writes a state onto the table and says which cards come next — see
     * [DevSetup]. Ignored outright unless the server was started with test
     * hooks on, so it does not exist as far as a real game is concerned.
     */
    @Serializable
    @SerialName("DEV")
    data class Dev(val setup: DevSetup) : ClientMessage()
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
    /**
     * Cards to deal off the top, in order, ahead of whatever the shuffle put
     * there. Each entry names a card by what is printed on it ("7") or by its
     * definition ("swapCards", "plus4"); anything the deck does not hold is
     * skipped.
     *
     * This is what a spec should reach for when it wants a particular round.
     * A seed can do the same thing but only by accident — you search for one
     * that happens to deal what you wanted, and it stops meaning that the
     * moment the deck's contents change. A stack says what it wants.
     *
     * Nothing is added or removed: the named cards are lifted out of the
     * shuffled deck and put on top of it, so the deck is still the deck.
     *
     * Ignored unless the server was started with test hooks on — see
     * [TEST_HOOKS_ENV].
     */
    val stack: List<String>? = null,
)

/**
 * Set to `1`/`true` to let clients pin a room's seed. Only the end-to-end suite
 * turns this on: a public server that honoured it would let anyone deal
 * themselves a known deck.
 */
const val TEST_HOOKS_ENV = "LETITRIDE_TEST_HOOKS"

fun testHooksEnabled(env: (String) -> String? = System::getenv): Boolean =
    env(TEST_HOOKS_ENV)?.lowercase() in setOf("1", "true", "yes")

/**
 * Scales every pace the game keeps — the title card, the deal, bots thinking,
 * the beat an animation is given. Set to 0.25 and a round plays out in a
 * quarter of the time.
 *
 * This exists for the end-to-end suite, which spends nearly all of its time
 * waiting for a table that is deliberately unhurried, and it is gated behind
 * the test hooks for the same reason they are: a public server must not be
 * able to have the pacing pulled out from under its players.
 *
 * Only ever speeds things up. Slowing a table down is not something a client
 * or an operator has any business doing by accident.
 */
const val PACE_ENV = "LETITRIDE_PACE"

fun pacingFactor(env: (String) -> String? = System::getenv): Double {
    if (!testHooksEnabled(env)) return 1.0
    return env(PACE_ENV)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.05, 1.0) ?: 1.0
}

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
    /** The question this card asks its drawer, if any — see [PendingActionView.options]. */
    val options: List<String> = emptyList(),
    /**
     * False for a definition that is not a card at all — a house rule asking a
     * question. It ships so the client can draw the prompt, but nothing may
     * list it among the cards or put it in a deck.
     */
    val deckable: Boolean = true,
    /** What it costs to buy outright — see the "mutate" card. */
    val price: Int = 0,
)

@Serializable
data class PassiveCardInfo(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val bonusPoints: Int,
    val scoring: String,
    /** The ink this card prints in, and the stamp its sigil is struck in. */
    val accent: String,
    val seal: String,
    /** What it costs to buy outright — see the "mutate" card. Nought is not for sale. */
    val price: Int = 0,
    /**
     * What the holder pays anybody who plays an action card on them — see the
     * "discordia" card. Nought for every card that is simply worth having.
     */
    val spite: Int = 0,
    /**
     * False for a card no deck may contain: an effect minted by whatever causes
     * it, which ships so the client can draw the face and is never dealt.
     */
    val deckable: Boolean = true,
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

/**
 * What a deck somebody builds has to be before a table will play it. Shipped so
 * the builder can say the same thing the server would, rather than the two of
 * them keeping their own copy of the rules and drifting apart.
 */
@Serializable
data class DeckLimitsInfo(
    val minNumberCards: Int,
    val maxCards: Int,
    val maxCopies: Int,
    val maxSpecials: Int,
    val minNumberShare: Double,
)

@Serializable
data class CatalogResponse(
    val actions: List<ActionCardInfo>,
    val passives: List<PassiveCardInfo>,
    val rules: List<LobbyRuleInfo>,
    val decks: List<DeckPresetInfo>,
    val flip7Bonus: Int,
    /**
     * What a table with no house rules plays to. A room can raise it, so
     * anything showing a live game's progress wants [GameStateView.flip7Target]
     * instead — this one is for the rules page, which has no room to speak of.
     */
    val flip7Target: Int,
    val minPlayers: Int,
    val maxPlayers: Int,
    val deckLimits: DeckLimitsInfo,
    /**
     * How fast this server is running the table, as a multiplier on every
     * animation the client times. 1.0 always, except under the end-to-end
     * suite — see [PACE_ENV].
     */
    val pace: Double = 1.0,
    /**
     * Whether this server takes dev commands — see [TEST_HOOKS_ENV]. False on
     * anything published, and the testing panel is not built into the page at
     * all when it is: there is nothing to find and nothing to send.
     */
    val testHooks: Boolean = false,
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
        ActionCardInfo(
            it.id, it.name, it.description, it.sigil, it.selfTarget, it.options, it.deckable, it.price,
        )
    },
    passives = Catalog.passives.values.map {
        PassiveCardInfo(
            it.id, it.name, it.description, it.sigil, it.bonusPoints,
            when (it.scoring) {
                PassiveScoring.FLAT -> "flat"
                PassiveScoring.DOUBLE_NUMBERS -> "double"
                PassiveScoring.NONE -> "none"
                PassiveScoring.VOID_UNLESS_FLIP -> "voidUnlessFlip"
                PassiveScoring.HALVE -> "halve"
            },
            it.accent,
            it.seal.name.lowercase(),
            it.price,
            it.spite,
            it.deckable,
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
    deckLimits = DeckLimitsInfo(
        minNumberCards = DeckLimits.MIN_NUMBER_CARDS,
        maxCards = DeckLimits.MAX_CARDS,
        maxCopies = DeckLimits.MAX_COPIES,
        maxSpecials = DeckLimits.MAX_SPECIALS,
        minNumberShare = DeckLimits.MIN_NUMBER_SHARE,
    ),
    pace = pacingFactor(),
    testHooks = testHooksEnabled(),
)
