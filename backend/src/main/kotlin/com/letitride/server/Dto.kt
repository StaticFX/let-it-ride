package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckConfig
import com.letitride.engine.DeckLimits
import com.letitride.engine.DeckPreset
import com.letitride.engine.DeckPresets
import com.letitride.engine.Engine
import com.letitride.engine.ForcedDraws
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GamblerCatalog
import com.letitride.engine.Interlude
import com.letitride.engine.Offer
import com.letitride.engine.Sale
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.LobbyRules
import com.letitride.engine.PassiveScoring
import com.letitride.engine.PendingOutcome
import com.letitride.engine.PickKind
import com.letitride.engine.PlayWindow
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
    /**
     * Whether each pick has to come off a different seat — see
     * [com.letitride.engine.PendingAction.oneCardPerSeat]. True for a card that
     * trades two; false for one that gives cards of your own away, where every
     * pick is necessarily yours.
     */
    val oneCardPerSeat: Boolean = true,
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
    /**
     * Cards that have landed and are about to take effect — see
     * [com.letitride.engine.PendingOutcome]. The table is busy for as long as
     * there is one, exactly as it is for a run of forced draws.
     */
    val pendingOutcomes: List<PendingOutcome> = emptyList(),
    val forcedDraws: ForcedDraws? = null,
    val dealQueue: List<String> = emptyList(),
    /**
     * What each player's number cards are worth to them as it stands. The same
     * total the seat has always shown, except that a card can turn it over —
     * see the "antimatter" card. `Player.handValue` is the physical sum and is
     * what the bust threshold counts; this is what the player wants to know.
     *
     * A seat in [hiddenHandIds] is simply not in here. A total is the one number
     * that gives a hidden hand away entirely.
     */
    val handWorth: Map<String, Int> = emptyMap(),
    /**
     * Seats whose hand this viewer is not being shown — see the "redacted" card.
     *
     * Their cards are still in the player list, still countable and still
     * pickable, but every one of them arrives face down (`Card.hidden`). This
     * list is what lets the seat say *why*: a total it cannot print, rather than
     * a total of nothing. Never contains the viewer's own seat, and on the
     * public view — a caller with nobody in particular in mind — contains every
     * hidden hand there is.
     */
    val hiddenHandIds: List<String> = emptyList(),
    /**
     * Seats a card forbids to go out — see the "antimatter" card. Not the same
     * as a seat that has simply drawn nothing yet, which is the opening rule and
     * says something else on the button. The client hides a button; the rule
     * itself stays here, worked out once by the only thing entitled to work it
     * out rather than kept in a second copy.
     */
    val cannotStayIds: List<String> = emptyList(),
    /**
     * Seats a bust does not write off — see the "antimatter" card. Everywhere
     * else in the client a busted seat is struck through and shown its hand as a
     * dead number, which is the truth for every player but this one: the seat
     * carrying an antimatter takes that hole whether it stopped, flipped or
     * busted. Sent rather than worked out, for the reason [cannotStayIds] is.
     */
    val bustStillCountsIds: List<String> = emptyList(),
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
     * How many gambler cards each seat is carrying — rolling rules. Public on
     * every seat, and only ever a number: the cap is public information, so a
     * player has to be able to see that somebody is full, and the faces are the
     * one thing in this game nobody else may see.
     */
    val gamblerCounts: Map<String, Int> = emptyMap(),
    /** How many each seat *could* carry. Five, plus whatever a "pouch" makes room for. */
    val gamblerLimits: Map<String, Int> = emptyMap(),
    /**
     * The viewer's own gambler cards, face up. Empty in every other seat's copy
     * of this state — which is what makes it worth encoding the view once per
     * connection instead of once per room.
     */
    val myGamblers: List<Card> = emptyList(),
    /**
     * Which of [myGamblers] the viewer may play right now, by card id.
     *
     * Whether a window is open is a rule, and rules are the server's — exactly
     * as [cannotStayIds] is. The client lights the cards in this list and works
     * nothing out; without it, it would be deciding what "on your turn" means.
     */
    val playableGamblers: List<String> = emptyList(),
    /**
     * Gambler cards somebody is holding that the shop minted rather than the
     * deck dealt. A count, so a client counting the deck can leave them out of
     * it — they were never in it.
     */
    val mintedGamblers: Int = 0,
    /**
     * The cards off the top of the deck this viewer has been shown — see the
     * "foreseer" and "stacked deck" cards. Empty for everybody else, always:
     * this is the same per-viewer rule as the hidden hand, said about the deck.
     */
    val foresight: List<Card> = emptyList(),
    /** The shop between rounds, while it is open. Projected per viewer. */
    val interlude: InterludeView? = null,
    /** Epoch millis the shop shuts. Null when no window is open. */
    val interludeUntil: Long? = null,
    /** Gambler cards in flight, oldest first. Empty whenever nothing is. */
    val responseStack: List<ResponseFrameView> = emptyList(),
    /** The open question, when the table is stopped on one. */
    val responseWindow: ResponseWindowView? = null,
    /**
     * The next few cards off the deck, for the testing panel to show and stack
     * — see [DevMode]. Null on every real server, and the field then does not
     * go out at all: knowing what is coming is the one thing a player must not
     * be able to find out.
     */
    val devDeck: List<Card>? = null,
    /**
     * Every seat's hidden tray, for the testing panel to show and write — see
     * [DevMode]. Null on every real server, exactly as [devDeck] is, and for a
     * sharper reason: this is the one field in the game whose entire purpose is
     * to defeat the projection the rest of the view exists to do.
     */
    val devGamblers: Map<String, List<Card>>? = null,
)

/**
 * Projects the state for one viewer.
 *
 * [viewerId] is first because it is the most important thing about the result:
 * this is not "the state" any more, it is one player's view of it, and every
 * field added from here has to be thought about in those terms. Null gets the
 * fully public view — a test, or a caller with nobody in particular in mind.
 */
/**
 * This seat as [viewerId] is entitled to see it — see the "redacted" card.
 *
 * The cards stay, with their faces cut off: the count is public (you can see
 * how many cards somebody is holding across any table), a card still has to be
 * animated into the hand and picked out of it by a swap, and the id is what all
 * of that is keyed on. What goes is anything anybody could read, and the total,
 * which is the same thing said in one number.
 */
private fun Player.hiddenFrom(viewerId: String?): Player =
    if (id == viewerId || !Engine.handIsHidden(this)) this
    else copy(hand = hand.map { it.faceDown() }, handValue = 0)

fun GameState.toView(
    viewerId: String?,
    roomCode: String,
    hostId: String?,
    turnDeadline: Long?,
    roundIntroUntil: Long? = null,
    roundOutroFrom: Long? = null,
    roundOutroUntil: Long? = null,
    animationGate: AnimationGateView? = null,
    nextRoundAt: Long? = null,
    interludeUntil: Long? = null,
    devDeck: List<Card>? = null,
    devGamblers: Map<String, List<Card>>? = null,
) = GameStateView(
    roomCode = roomCode,
    hostId = hostId,
    phase = phase,
    round = round,
    players = players.map { it.hiddenFrom(viewerId) },
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
            oneCardPerSeat = it.oneCardPerSeat,
            offers = it.offers,
            phase = it.phase,
            responders = it.respondents,
            // The names of who has answered, never what any of them said.
            answered = it.answers.keys.toList(),
        )
    },
    pendingOutcomes = pendingOutcomes,
    forcedDraws = forcedDraws,
    dealQueue = dealQueue,
    handWorth = players.filterNot { it.id != viewerId && Engine.handIsHidden(it) }
        .associate { it.id to Engine.handWorth(it) },
    hiddenHandIds = players.filter { it.id != viewerId && Engine.handIsHidden(it) }.map { it.id },
    cannotStayIds = players.filter { Engine.mayNotStop(it) }.map { it.id },
    bustStillCountsIds = players.filter { Engine.bustStillCounts(it) }.map { it.id },
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
    gamblerCounts = players.associate { it.id to it.gamblers.size },
    gamblerLimits = players.associate { it.id to Engine.gamblerLimitFor(this, it.id) },
    myGamblers = viewerId?.let { player(it)?.gamblers }.orEmpty(),
    playableGamblers = viewerId?.let { Engine.playableGamblers(this, it) }.orEmpty(),
    mintedGamblers = mintedGamblers,
    foresight = viewerId?.let { Engine.foresightFor(this, it) }.orEmpty(),
    interlude = interlude?.let { shop ->
        InterludeView(
            offers = viewerId?.let { shop.stock[it] }.orEmpty(),
            bought = viewerId?.let { shop.bought[it] }.orEmpty(),
            openingScore = viewerId?.let { shop.openingScore[it] } ?: 0,
            slotsFree = viewerId
                ?.let { (Engine.gamblerLimitFor(this, it) - (player(it)?.gamblers?.size ?: 0)).coerceAtLeast(0) }
                ?: 0,
            done = shop.done,
            lot = shop.lot,
            myBid = viewerId?.let { shop.bids[it] },
            sale = shop.sale?.let { SaleView(it.card, it.bids, it.winnerId, it.price, it.rigged) },
            closed = shop.closed,
        )
    },
    interludeUntil = interludeUntil,
    responseStack = responseStack.map {
        ResponseFrameView(it.id, it.cardDefId, it.card, it.playerId, it.targetId, it.cancelled)
    },
    responseWindow = openResponse?.let { ResponseWindowView(it.id, it.responders, it.awaiting) },
    devDeck = devDeck,
    devGamblers = devGamblers,
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
     * Offers a gambler card out of the sender's own hidden hand. Rolling rules.
     *
     * No target: if the card needs one the server comes straight back with an
     * ordinary prompt, which the client answers the way it answers every other
     * prompt. See [com.letitride.engine.GameAction.PlayGambler].
     */
    @Serializable
    @SerialName("PLAY_GAMBLER")
    data class PlayGambler(val cardId: String) : ClientMessage()

    /**
     * Declines an open response window — "let it stand".
     *
     * Every other prompt in this game is answered by picking something, so
     * passing needed a message of its own rather than a [PlayAction] pointed at
     * nobody. Saying nothing does the same thing when the clock runs out; this
     * is only the way to say it sooner.
     */
    @Serializable
    @SerialName("PASS")
    data object Pass : ClientMessage()

    /** Takes one card off your own shelf. Ignored outside the shop. */
    @Serializable
    @SerialName("BUY")
    data class Buy(val offerId: String) : ClientMessage()

    /**
     * "I'm finished" — and, once the auction lands, the sealed bid with it,
     * because they are one act. A bid you could still place after the shop shut
     * would be a bid placed against a purse you had already spent.
     */
    @Serializable
    @SerialName("SHOP_DONE")
    data class ShopDone(val bid: Int? = null) : ClientMessage()

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

    /**
     * "Again!" from the results screen — the host's, like every other message
     * that decides something for the whole table. Takes the room back to its
     * lobby with everybody still in it; see [com.letitride.engine.GameAction.PlayAgain].
     */
    @Serializable
    @SerialName("PLAY_AGAIN")
    data object PlayAgain : ClientMessage()

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
    /** False for a card whose holder may not choose to go out — see "antimatter". */
    val allowsStaying: Boolean = true,
)

/**
 * The shop between rounds, as one player sees it.
 *
 * The shelf is the viewer's own and nobody else's is sent — private stock is
 * private on the wire, not just in the rules. [done] is names only, which is the
 * same discipline a simultaneous prompt keeps.
 */
@Serializable
data class InterludeView(
    /** The viewer's own shelf, priced. */
    val offers: List<Offer> = emptyList(),
    /** Which of them they have taken. */
    val bought: List<String> = emptyList(),
    /** What they came in holding, so the window can say what the evening cost. */
    val openingScore: Int = 0,
    /** Free gambler slots, so a full tray can be greyed out and say *why*. */
    val slotsFree: Int = 0,
    /** Who has finished. Names only. */
    val done: List<String> = emptyList(),
    /** The jackpot on the block, with its list price as a guide. */
    val lot: Offer? = null,
    /** What this viewer bid, once they have. Nobody else's, ever, until the hammer. */
    val myBid: Int? = null,
    /** How it came out. Null while the window is open — the bids are sealed until then. */
    val sale: SaleView? = null,
    /** True once the window has shut and the next round is on its way. */
    val closed: Boolean = false,
)

/** How an auction came out. Only ever sent after the hammer. */
@Serializable
data class SaleView(
    val card: Card,
    val bids: Map<String, Int> = emptyMap(),
    val winnerId: String? = null,
    val price: Int = 0,
    val rigged: List<String> = emptyList(),
)

/**
 * One gambler card in flight, oldest first — see
 * [com.letitride.engine.StackFrame].
 *
 * The whole pile goes out, not just the top of it, because "counters counter
 * counters" is only legible if you can see the pile getting taller. Every card
 * on it is face up: it was turned over when it was played.
 */
@Serializable
data class ResponseFrameView(
    val id: Long,
    val cardDefId: String,
    val card: Card,
    val playerId: String,
    val targetId: String,
    /** Struck through — the card above it stopped it. */
    val cancelled: Boolean = false,
)

/**
 * The question the table is stopped on: does anybody want to answer the card on
 * top of the stack?
 *
 * [responders] is everybody who was asked and [awaiting] is who has not spoken
 * yet — names only, exactly as a simultaneous prompt sends names and never
 * answers. Everybody still in is asked whenever the window opens at all, so
 * being on this list says nothing about what anybody is holding.
 */
@Serializable
data class ResponseWindowView(
    val frameId: Long,
    val responders: List<String> = emptyList(),
    val awaiting: List<String> = emptyList(),
)

/**
 * A gambler card's face — rolling rules. Its own list rather than more entries
 * among the passives: a gambler card is not a modifier, nothing about the deck
 * builder or the rules book should treat it as one, and folding it in would
 * change what every existing reader of `passives` is looking at.
 */
@Serializable
data class GamblerCardInfo(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    /** "common", "rare" or "jackpot". */
    val rarity: String,
    /** When it may be played — see `PlayWindow`. The client shows it; the server enforces it. */
    val window: String,
    val accent: String,
    val seal: String,
    /** What the shop charges, and seven tenths of what the dealer pays to take it back. */
    val price: Int,
    /** What playing it costs its own player, on top of what it cost to buy. Usually nothing. */
    val cost: Int = 0,
    val selfTarget: Boolean = true,
    val options: List<String> = emptyList(),
    /**
     * Whether a deck may hold it and a shop may sell it — `deckable`, said about
     * a gambler card. The IOU a loan leaves behind is the only one that is not:
     * it is minted into the tray of whoever took the loan, and it is not a thing
     * anybody chooses to be carrying.
     */
    val obtainable: Boolean = true,
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
    /** Gambler cards a built deck may hold, which is far fewer — see [DeckLimits]. */
    val maxGamblers: Int = 0,
    val minNumberShare: Double,
)

@Serializable
data class CatalogResponse(
    val actions: List<ActionCardInfo>,
    val passives: List<PassiveCardInfo>,
    val rules: List<LobbyRuleInfo>,
    val decks: List<DeckPresetInfo>,
    /**
     * The gambler cards, for rolling rules. Empty on a server that has no such
     * mode, and on this one until the first card is written.
     */
    val gamblers: List<GamblerCardInfo> = emptyList(),
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

    for ((defId, count) in deck.gamblerCards.groupingBy { it }.eachCount()) {
        val def = Catalog.gambler(defId) ?: continue
        entries += DeckEntryInfo(
            card = Card(id = "preview-g-$defId", kind = CardKind.GAMBLER, label = def.name, value = 0, defId = defId),
            count = count,
        )
    }

    return entries
}

/**
 * The wire name for a play window. Written out rather than lowercased, because
 * these are camel-cased on the wire and the enum names are not — and a name the
 * client does not recognise is a card it cannot say anything about.
 */
private fun windowName(window: PlayWindow): String = when (window) {
    PlayWindow.ON_TURN -> "onTurn"
    PlayWindow.ON_OTHER_TURN -> "onOtherTurn"
    PlayWindow.ALWAYS -> "always"
    PlayWindow.ON_OUT -> "onOut"
    PlayWindow.IN_RESPONSE -> "inResponse"
    PlayWindow.PASSIVE -> "passive"
    PlayWindow.INTERLUDE -> "interlude"
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
                PassiveScoring.NEGATE -> "negate"
            },
            it.accent,
            it.seal.name.lowercase(),
            it.price,
            it.spite,
            it.deckable,
            it.allowsStaying,
        )
    },
    gamblers = GamblerCatalog.all.map {
        GamblerCardInfo(
            it.id, it.name, it.description, it.sigil,
            it.rarity.name.lowercase(),
            // The wire name, not the constant's — `PlayWindow`'s @SerialName is
            // what the client's own union is written against.
            windowName(it.window),
            it.accent,
            it.seal.name.lowercase(),
            it.price,
            it.cost,
            it.selfTarget,
            it.options,
            it.obtainable,
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
        maxGamblers = DeckLimits.MAX_GAMBLERS,
        minNumberShare = DeckLimits.MIN_NUMBER_SHARE,
    ),
    pace = pacingFactor(),
    testHooks = testHooksEnabled(),
)
