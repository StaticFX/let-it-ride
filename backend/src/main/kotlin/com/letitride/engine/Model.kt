package com.letitride.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// ─── Cards ───

@Serializable
enum class CardKind {
    @SerialName("number")
    NUMBER,

    @SerialName("action")
    ACTION,

    @SerialName("passive")
    PASSIVE,

    /**
     * A gambler card — rolling rules only. Drawn like anything else, but it goes
     * into the hidden hand rather than onto the table, and its holder chooses
     * when to spend it.
     *
     * A fourth kind rather than an action card with a different catalog, because
     * the deck builder, the wire and the client all key on the kind already, and
     * a second dispatch hidden behind the first is how a card ends up resolving
     * as the wrong thing.
     */
    @SerialName("gambler")
    GAMBLER,
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
    /**
     * The gambler hand — rolling rules only. Survives the round, unlike the two
     * piles above it, and no ordinary card can reach it: every primitive that
     * moves a card between players reads `hand + passives` and nothing else, so
     * "a player's second hand is their protected good" costs no code at all.
     *
     * Never serialised. Which faces you may see is a fact about the *viewer*,
     * and a viewer-dependent fact must not ride on a type every viewer is sent —
     * see `GameStateView.myGamblers` and `gamblerCounts`. A `@Transient` is a
     * fact the type carries; stripping it in `toView` would be a habit somebody
     * eventually drops.
     */
    @Transient
    val gamblers: List<Card> = emptyList(),
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
    /**
     * The gambler cards shuffled in among the rest. Kept sparse on purpose —
     * drawing one has to stay a treat, and the dealer's stock for the shop comes
     * out of a reserve rather than out of here.
     */
    val gamblerCards: List<String> = emptyList(),
)

/**
 * Which game a table is playing.
 *
 * A mode rather than a house rule, and the difference is not pedantry: a rule
 * changes a number the engine reads, while a mode changes the screens you see
 * and the things you can do between rounds. Putting rolling rules in `ruleIds`
 * would have hidden two whole screens in a row of small toggles.
 */
@Serializable
enum class GameMode {
    @SerialName("classic")
    CLASSIC,

    /**
     * A second hand nobody else can see, and points you can spend. Always
     * underlays "extreme" — see `Room.sanitize`, which puts it there whatever
     * the host sent.
     */
    @SerialName("rollingRules")
    ROLLING_RULES,
}

@Serializable
data class GameConfig(
    val deckPresetId: String = "letitride",
    val deck: DeckConfig,
    /** Which game this is. Older clients omit it, and the game is the classic one. */
    val mode: GameMode = GameMode.CLASSIC,
    val ruleIds: List<String> = emptyList(),
    val winCondition: WinCondition = WinCondition.ROUNDS,
    val totalRounds: Int = 5,
    val targetScore: Int = 200,
    val turnTimeSeconds: Int = 30,
    /**
     * How long the shop stays open between rounds — rolling rules.
     *
     * A ceiling rather than a schedule: the window shuts the moment every seat
     * says it is finished, which at a table with bots on it is almost at once.
     */
    val shopSeconds: Int = 120,
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

/**
 * A gambler card has been played out of a hidden hand and wants a target, an
 * answer, or a handful of cards.
 *
 * Its own phase rather than [PHASE_PLAY] because the two are answered by
 * different code — the card is looked up in a different catalog — and because
 * the client says something different about it: nobody drew this, somebody
 * chose it.
 */
const val PHASE_GAMBLER = "gambler"

/**
 * A card has been turned over and its drawer is deciding what becomes of it —
 * see the "redirect" and "second opinion" gambler cards.
 *
 * These two are the only prompts in the game where the card being held out is
 * the card being *asked about* rather than the card doing the asking, so they
 * settle by placing it rather than by spending it.
 */
const val PHASE_REDIRECT = "redirect"
const val PHASE_SECOND_OPINION = "secondOpinion"

/**
 * A card that has already been watched is now doing what it does — see
 * [PendingOutcome]. Nothing is being asked of anybody; the table is only being
 * told what the coin it just watched land was worth.
 */
const val PHASE_OUTCOME = "outcome"

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

/**
 * A card that has landed but has not done anything yet.
 *
 * Some cards *are* their animation. A coin is thrown, turns over, and lands on
 * a face; a bottle spins and stops on somebody. Resolving those in the same
 * breath as announcing them means the client is handed the answer and the
 * question together — the seat reads "bust!" while the coin is still in the air,
 * and there is nothing the client can honestly do about it, because the state it
 * has been sent is true.
 *
 * So the card announces itself, the room waits out the animation exactly as it
 * waits out every other one, and the outcome arrives in a batch of its own. It
 * is the same shape the slot machine already had: say what is coming, then let
 * it come.
 */
@Serializable
data class PendingOutcome(
    val cardDefId: String,
    /** Whoever played it. */
    val playerId: String,
    /** Whoever it landed on. */
    val targetId: String,
    /**
     * Everyone it landed on, when it landed on more than one — an "all in"
     * settles with the whole table at once, and asking the same question of
     * four seats one after another would be four waits for one moment. Empty
     * for the ordinary case, which is [targetId] alone.
     */
    val targetIds: List<String> = emptyList(),
    /**
     * What the card worked out while it was being announced — the face the coin
     * came down on. Decided when the card was played, so the announcement and
     * the outcome cannot disagree, and carried here rather than rolled again.
     */
    val result: String? = null,
    /** The drawer's own answer, if the card asked for one. */
    val choice: String? = null,
)

/**
 * The window between rounds: a shop, and later an auction house.
 *
 * Its cards are **minted**, not dealt. A shop's stock is the dealer's, not the
 * table's — "if you need a card that was never dealt, mint it with a `tmp-` id"
 * is the rule this is written to, and it is why there is no second physical pile
 * to keep honest. A minted gambler card is the one exception to `tmp-` meaning
 * "gone at the end of the round": `nextRound` sweeps the hand and the modifier
 * row and deliberately never touches the gambler zone, so a bought card stays
 * bought. What it does still mean is that the card is out of the deck's
 * accounting entirely, which is exactly right — the deck never had it.
 */
@Serializable
data class Interlude(
    /** Each seat's own shelf, rolled when the window opened. Nobody sees anybody else's. */
    val stock: Map<String, List<Offer>> = emptyMap(),
    /** Which offers each seat has already taken, by offer id. */
    val bought: Map<String, List<String>> = emptyMap(),
    /** What each came in holding, so the window can say what the evening cost. */
    val openingScore: Map<String, Int> = emptyMap(),
    /** Who has said they are finished — see [GameState.waitingOnShop]. */
    val done: List<String> = emptyList(),
    /** The one jackpot on the block, or null when there was none to sell. */
    val lot: Offer? = null,
    /**
     * The sealed bids.
     *
     * Server-side only, exactly as `PendingAction.answers` is and for the same
     * reason: the auction stays secret without any per-viewer filtering, because
     * there is nothing on the wire to leak.
     */
    val bids: Map<String, Int> = emptyMap(),
    /** How it came out, once it has. Null while the window is open. */
    val sale: Sale? = null,
    /**
     * True once the window has shut and whatever it settled is being watched.
     * The window is over; the round has not started. Kept rather than clearing
     * the whole thing so the room still knows where it is.
     */
    val closed: Boolean = false,
)

/**
 * How an auction came out.
 *
 * [bids] is every bid, and it exists only here — which is to say only after the
 * hammer. While the window is open the bids are in `Interlude.bids` and never
 * go anywhere.
 */
@Serializable
data class Sale(
    val card: Card,
    val bids: Map<String, Int> = emptyMap(),
    val winnerId: String? = null,
    val price: Int = 0,
    /** Anybody whose "rigged bid" fired after the close, in the order they fired. */
    val rigged: List<String> = emptyList(),
)

/**
 * One gambler card in flight, and everything needed to unwind it.
 *
 * A card is on the stack from the moment it is aimed until it either resolves or
 * is countered. While it is here it is *nobody's* — it has left its owner's
 * hidden hand and has not reached the discard pile — which is why `allCardIds()`
 * counts it, the same way it counts the card a prompt is holding out.
 *
 * A plain list rather than the current-plus-stack shape [ForcedDraws] uses: a
 * forced draw is mutated in place as it counts down, and a frame is not.
 */
@Serializable
data class StackFrame(
    /** Unique per frame, so an answer cannot be replayed against a later one. */
    val id: Long,
    val cardDefId: String,
    /** The physical card, so it can be spent or handed back. */
    val card: Card,
    val playerId: String,
    /**
     * Which catalog [cardDefId] is in, and therefore what unwinding this frame
     * runs.
     *
     * A nullify says "stop a card aimed at you" and a deflect says "a card
     * somebody uses on you goes back at them" — *card*, not *gambler card*. A
     * freeze pointed at your seat is exactly the thing both of them are for, and
     * the stack has to be able to hold one for either to reach it. What stays
     * gambler-only is the pair whose text says so: "nahhh" answers a gambler
     * card, and a copycat can only copy one.
     */
    val kind: StackKind = StackKind.GAMBLER,
    /** Who it was aimed at, which is what a counter reads to know if it may answer. */
    val targetId: String,
    val choice: String? = null,
    val cards: List<String> = emptyList(),
    /**
     * Everybody who has been asked whether they want to answer this and has not
     * said yet. The window *is* this list: it shuts when the list empties, and
     * it is never opened at all when it starts empty — which is what makes the
     * pause silent at a table where nobody is holding a counter.
     *
     * Only the top frame is ever waiting. A frame underneath one has had its
     * window; it does not get a second.
     */
    val awaiting: List<String> = emptyList(),
    /** Everybody who was asked, so the table can be told how many are left. */
    val responders: List<String> = emptyList(),
    val cancelled: Boolean = false,
    /** True when the card that cancelled it hands it back rather than spending it. */
    val returned: Boolean = false,
)

@Serializable
enum class StackKind { GAMBLER, ACTION }

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
    /**
     * The shop and the auction house, between rounds — rolling rules only.
     *
     * On the state rather than on the room for one reason: a player who reloads
     * mid-shop has to come back to the same shelf with the same cards already
     * taken off it, and a room field would be a shelf that only existed in
     * whatever tabs happened to be open when it was rolled.
     */
    val interlude: Interlude? = null,
    val pendingAction: PendingAction? = null,
    /**
     * Cards that have landed and are waiting to take effect, in the order they
     * landed — see [PendingOutcome]. A list because "double it!" throws the coin
     * twice and spins the bottle twice, and each of them is watched and settled
     * in its own turn rather than the second quietly replacing the first.
     */
    val pendingOutcomes: List<PendingOutcome> = emptyList(),
    val forcedDraws: ForcedDraws? = null,
    val forcedDrawStack: List<ForcedDraws> = emptyList(),
    /**
     * Gambler cards in flight, oldest first — see [StackFrame]. Cards answer
     * cards, so a counter goes on top of what it is answering and the whole pile
     * unwinds innermost-first.
     *
     * Separate from [pendingAction] rather than a generalisation of it. That
     * field is answered by one code path, projected by another, named by the
     * room's pacer and rested on by two test files; turning it into a list would
     * touch all of that and buy this nothing.
     */
    val responseStack: List<StackFrame> = emptyList(),
    /** Frames minted this game, so no two ever share an id. Same reason as [minted]. */
    val stackCounter: Long = 0,
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
    /**
     * How much of [roundAdjustments] was taken by a toll rather than spent or
     * given up — see [Ctx.transferPoints]. Held apart from the rest for one
     * reason: a round can never leave a player worse off than they started it,
     * and a toll is the exception. It may take you into the red, by exactly what
     * it took and no further. See `Engine.floorFor`.
     */
    val roundTolls: Map<String, Int> = emptyMap(),
    /**
     * How many cards this game has minted — see [Ctx.mint]. It lives here rather
     * than in the transition that mints them because a transition is one moment
     * and the game is many: a counter that started again at nought every time
     * handed the same id to two different cards.
     */
    val minted: Int = 0,
) {
    fun player(id: String): Player? = players.firstOrNull { it.id == id }

    val currentPlayer: Player? get() = players.getOrNull(turnIndex)

    /** True while the engine is waiting on something other than the current player's move. */
    val isInterrupted: Boolean
        get() = pendingAction != null ||
            pendingOutcomes.isNotEmpty() ||
            forcedDraws != null ||
            responseStack.isNotEmpty()

    /** The card in flight the table is waiting on an answer to, if there is one. */
    val openResponse: StackFrame?
        get() = responseStack.lastOrNull()?.takeIf { it.awaiting.isNotEmpty() }

    /**
     * Who the shop window is still waiting on.
     *
     * A seat that has gone is not waited for. A tab that closed cannot press a
     * button, and holding the window for it would make pulling the cable the way
     * to stall a game.
     */
    val waitingOnShop: List<String>
        get() = interlude?.let { shop ->
            if (shop.closed) emptyList()
            else players.filter { it.id !in shop.done && (it.isBot || it.connected) }.map { it.id }
        } ?: emptyList()

    /**
     * Gambler cards somebody is holding that were minted rather than dealt — the
     * shop's. Published so a client counting the deck can leave them out of it.
     */
    val mintedGamblers: Int
        get() = players.sumOf { p -> p.gamblers.count { it.isEphemeral } }
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

    /**
     * Offers a gambler card out of a hidden hand. Rolling rules.
     *
     * It carries no target, and that is the design rather than an omission.
     * Playing one is two steps: this puts the card on the table, and if it needs
     * a seat, a question or a handful of cards the engine raises an ordinary
     * [PendingAction] for it — which every picker the client already has knows
     * how to answer. One message, no second picking mechanism, and the server
     * stays the only thing that decides what a legal target is.
     *
     * Named by card id rather than definition id: you can be holding two
     * nullifies, and the room has to spend the right one.
     */
    @Serializable
    @SerialName("PLAY_GAMBLER")
    data class PlayGambler(val playerId: String, val cardId: String) : GameAction()

    /**
     * Declines an open response window — "let it stand".
     *
     * Its own action rather than a [PlayAction] with a magic target, because it
     * reads as what it is on both sides. Silence does the same thing when the
     * clock runs out; this is only the way to say it sooner.
     */
    @Serializable
    @SerialName("PASS_RESPONSE")
    data class PassResponse(val playerId: String) : GameAction()

    /** Takes one card off your own shelf, between rounds. */
    @Serializable
    @SerialName("BUY")
    data class Buy(val playerId: String, val offerId: String) : GameAction()

    /**
     * "I'm finished", and the sealed bid — because they are one act.
     *
     * A bid you could still place after the shop shut would be a bid placed
     * against a purse you had already spent. [bid] is ignored until the auction
     * lands; it is on the wire from the start so the shape does not change under
     * anybody.
     */
    @Serializable
    @SerialName("FINISH_SHOPPING")
    data class FinishShopping(val playerId: String, val bid: Int? = null) : GameAction()

    /** Shuts the shop and settles whatever it has to. Sent by the room, never a client. */
    @Serializable
    @SerialName("CLOSE_INTERLUDE")
    data object CloseInterlude : GameAction()

    /** Ends the interlude and deals the next round. Sent by the room, never a client. */
    @Serializable
    @SerialName("OPEN_ROUND")
    data object OpenRound : GameAction()

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

    /**
     * Applies a card that has already landed — see [PendingOutcome]. Sent by the
     * room once the table has finished watching it, never by a client.
     */
    @Serializable
    @SerialName("RESOLVE_OUTCOME")
    data object ResolveOutcome : GameAction()

    /** The turn clock ran out for [playerId]. */
    @Serializable
    @SerialName("TIMEOUT")
    data class Timeout(val playerId: String) : GameAction()

    @Serializable
    @SerialName("NEXT_ROUND")
    data object NextRound : GameAction()
}
