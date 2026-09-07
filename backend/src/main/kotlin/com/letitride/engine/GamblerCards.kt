package com.letitride.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rolling rules: the cards a player keeps in the hand nobody else can see.
 *
 * They are ordinary cards in every way that matters to the engine — dealt off
 * the same deck, conserved like everything else, discarded when they are spent.
 * What is different is where they sit and who decides when they go off. An
 * action card resolves the moment it is drawn, which is what makes it a hazard;
 * a gambler card waits in your hand until you choose a moment, which is what
 * makes it a plan.
 */

/**
 * How rare a card is, and — because the shop rolls against these weights — how
 * much of the evening it takes to get one.
 *
 * It is not a power level. A jackpot is not strictly better than a rare; it is
 * rarer, and rarity is what the shop and the auction are priced against.
 */
@Serializable
enum class Rarity {
    @SerialName("common")
    COMMON,

    @SerialName("rare")
    RARE,

    @SerialName("jackpot")
    JACKPOT,
}

/**
 * When a card may be played.
 *
 * The spec names six. There is a seventh, [INTERLUDE], and it is here because
 * one card in the set is about the shop rather than about a round — bending
 * "always" to cover it would have made "always" mean "except between rounds,
 * unless".
 */
@Serializable
enum class PlayWindow {
    /** Only on your own turn, while you are still in the round. */
    @SerialName("onTurn")
    ON_TURN,

    /** Only while somebody else has the turn. */
    @SerialName("onOtherTurn")
    ON_OTHER_TURN,

    /** Any time the round is running. */
    @SerialName("always")
    ALWAYS,

    /** Only once you are out of the round — banked or busted. */
    @SerialName("onOut")
    ON_OUT,

    /**
     * Only into an open response window, answering a card already in flight.
     * Never playable on its own initiative, which is why [windowOpen] returns
     * false for it and the response stack asks separately.
     */
    @SerialName("inResponse")
    IN_RESPONSE,

    /**
     * Never played at all. It works by being held — a pouch that makes room, a
     * rigged bid the auction consumes on its own. The cost of one is the slot it
     * occupies, which is the whole of its balance.
     */
    @SerialName("passive")
    PASSIVE,

    /** Only between rounds, with the shop open — see "back to the shop". */
    @SerialName("interlude")
    INTERLUDE,
}

/**
 * What a gambler card is and does.
 *
 * A type of its own rather than an [ActionCardDef] with a flag, because the two
 * differ in when they resolve and who decides — but it carries the very same
 * `onPlay` and the very same [Play], so every primitive on [Ctx] works untouched
 * and a card body here reads exactly like one two files over.
 */
data class GamblerCardDef(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val rarity: Rarity,
    val window: PlayWindow,
    /** The ink it prints in. The client draws faces from the catalog and decides nothing. */
    val accent: String = GAMBLER_INK,
    val seal: SealShape = SealShape.HEXAGON,
    /**
     * What the shop charges for it, and what the dealer pays to take it back.
     * Priced by what it does to a game rather than by how hard it is to find.
     */
    val price: Int,
    /**
     * What playing it costs its own player, taken before the effect runs. Nearly
     * always nothing: a gambler card's price is paid at the shop, and a card that
     * charges twice needs a reason. "Cheating" has one.
     */
    val cost: Int = 0,
    /**
     * Whether this card can be come by at all — dealt, bought, or won.
     *
     * False for one that only ever exists because something else made it: the
     * IOU a loan leaves in your tray. It still ships in the catalog, because the
     * client has to be able to draw a face for a card sitting in a hand; it is
     * simply never in a deck and never on a shelf. The same thing
     * `ActionCardDef.deckable` means, said about three places instead of one.
     */
    val obtainable: Boolean = true,
    val targetRule: TargetRule = TargetRule.SELF,
    val options: List<String> = emptyList(),
    val pickKind: PickKind = PickKind.PLAYER,
    val picks: Int = 1,
    val cardTargets: (GameState, String) -> List<String> = { _, _ -> emptyList() },
    /**
     * Whether this card may answer [frame], asked of the player holding it.
     *
     * Only ever consulted for an [PlayWindow.IN_RESPONSE] card, and it is what
     * lets the response window be *silent*: the server reads every hand, so a
     * table where nobody is holding anything that could answer never pauses at
     * all, and nobody learns that anybody was asked.
     */
    val counters: ((GameState, StackFrame, String) -> Boolean)? = null,
    val onPlay: (Ctx, Play) -> Unit = { _, _ -> },
) {
    val selfTarget: Boolean get() = targetRule == TargetRule.SELF

    val needsChoice: Boolean get() = options.isNotEmpty()

    val picksCards: Boolean get() = pickKind == PickKind.CARD

    /** Everyone this card could meaningfully be pointed at right now. */
    fun validTargets(state: GameState, fromId: String): List<String> =
        targetsFor(targetRule, state, fromId)
}

/** The house ink for a gambler card, when a card does not name its own. */
const val GAMBLER_INK = "#6a4a82"

/** How many gambler cards a player may hold, before a "pouch" makes room. */
const val GAMBLER_HAND_LIMIT = 5

/**
 * The "pouch" card, named here rather than where it is defined because the draw
 * path has to know the limit long before the card that changes it is written.
 */
const val POUCH_ID = "pouch"

/** How much room a pouch makes. It occupies one of the slots it creates. */
const val POUCH_SLOTS = 2

// ═══════════════════════════════════════════
// The cards
// ═══════════════════════════════════════════

const val REDIRECT_ID = "redirect"
const val SECOND_OPINION_ID = "secondOpinion"
const val FUCK_IT_ID = "fuckIt"

/** The two answers to "the next card you draw" — used by redirect and second opinion. */
const val TAKE_IT = "take it"
const val PASS_IT_ON = "pass it on"
const val KEEP_IT = "keep it"
const val THROW_IT_BACK = "throw it back"

/**
 * Armed before you draw, and that is the whole card.
 *
 * The spec's window for it is "on play", which is not one of the six. Read
 * either way round it is a card about a draw, and arming it first is the reading
 * that makes it a decision: you spend it *before* you know what is coming, and
 * then choose. Resolving it after the fact would mean undoing a card that has
 * already gone off, which is a different and much worse card.
 */
val REDIRECT = GamblerCardDef(
    id = REDIRECT_ID,
    name = "redirect",
    description = "before you draw: the next card is yours to keep, or to hand to somebody else",
    sigil = "⤳",
    rarity = Rarity.COMMON,
    window = PlayWindow.ON_TURN,
    seal = SealShape.SHIELD,
    price = 30,
) { ctx, play ->
    ctx.grantEffect(play.from.id, REDIRECT_ARMED.id)
}

val SHUFFLE = GamblerCardDef(
    id = "shuffle",
    name = "shuffle",
    description = "shuffle the deck — whatever anybody was counting on, they are not any more",
    sigil = "⁂",
    rarity = Rarity.COMMON,
    window = PlayWindow.ALWAYS,
    seal = SealShape.CIRCLE,
    price = 15,
) { ctx, _ ->
    ctx.shuffleDeck()
}

val DRAW_TWO = GamblerCardDef(
    id = "drawTwo",
    name = "draw 2",
    description = "take an extra card on your next turn",
    sigil = "❊",
    rarity = Rarity.COMMON,
    window = PlayWindow.ALWAYS,
    seal = SealShape.SCALLOP,
    price = 25,
) { ctx, play ->
    ctx.grantEffect(play.from.id, DRAW_TWO_ARMED.id)
}

val SECOND_OPINION = GamblerCardDef(
    id = SECOND_OPINION_ID,
    name = "second opinion",
    description = "on your next turn, throw the first card back if you do not like it and take another",
    sigil = "⚖",
    rarity = Rarity.COMMON,
    window = PlayWindow.ALWAYS,
    seal = SealShape.SCALLOP,
    price = 30,
) { ctx, play ->
    ctx.grantEffect(play.from.id, SECOND_OPINION_ARMED.id)
}

/**
 * A hundred points for a card out of the deck, sight unseen.
 *
 * The spec has it paying out on your next turn. It pays out now instead: the
 * wait added nothing but a wait, and a card you have spent a hundred points on
 * should put something in your hand while you can still feel the hundred.
 */
val CHEATING = GamblerCardDef(
    id = "cheating",
    name = "cheating",
    description = "pay $CHEATING_COST and take a gambler card straight out of the deck",
    sigil = "✧",
    rarity = Rarity.COMMON,
    window = PlayWindow.ALWAYS,
    seal = SealShape.SPIKE,
    price = 40,
    cost = CHEATING_COST,
) { ctx, play ->
    val card = ctx.takeGamblerFromDeck()
    if (card == null) {
        // Nothing left to cheat with. The hundred goes back — a card that could
        // not do the one thing it does has not been played, and this is the only
        // card in the set that takes money up front.
        ctx.adjust(play.from.id, CHEATING_COST)
        // Fizzled, but not `Ctx.wasted` — that deals its player a replacement,
        // which is right for a card off the deck that could not do anything and
        // wrong for one somebody chose to spend. There is nothing to replace.
        ctx.emit(GameEvent.Fizzled("cheating", play.from.id))
        return@GamblerCardDef
    }
    ctx.giveGambler(play.from.id, card)
}

/**
 * Everything you have, gone.
 *
 * It is exactly what it says, and it is a joke card on purpose — the shrug at
 * the end of a bad evening, drawn far more often than it is played. Kept as
 * written because a set of twenty-five cards that are all sensible is a set
 * nobody tells a story about.
 */
val FUCK_IT = GamblerCardDef(
    id = FUCK_IT_ID,
    name = "fuck it",
    description = "played when you are out: your score goes to nothing",
    sigil = "∅",
    rarity = Rarity.COMMON,
    window = PlayWindow.ON_OUT,
    accent = "#8f3b2e",
    seal = SealShape.SPIKE,
    price = 10,
) { ctx, play ->
    if (play.phase == PHASE_OUTCOME) {
        ctx.setScore(play.target.id, 0)
        return@GamblerCardDef
    }
    // Announced, then settled — the table watches a scoreboard go to nothing
    // rather than being handed the number and the reason at once.
    ctx.land(FUCK_IT_ID, play.from.id, play.from.id)
}

/** What "cheating" charges. Steep on purpose: it is the only card that buys another. */
const val CHEATING_COST = 100

// ═══════════════════════════════════════════
// Cards that answer cards
// ═══════════════════════════════════════════
//
// All four are `IN_RESPONSE`, including the two the spec files under other
// windows. "Nahhh" is written there as "always" and "deflect" as "on other
// turn", but read what they do: both are pure reactions, and a card that can
// only ever be played *at* something is not a card you play whenever you like.
//
// Each names, in `counters`, which frames it may answer. That predicate is what
// lets the window be silent — the server reads every hand, so a table where
// nobody could answer never pauses at all, and being asked is not a tell.

val NULLIFY = GamblerCardDef(
    id = "nullify",
    name = "nullify",
    description = "stop a card aimed at you. it is spent, and nothing happens",
    sigil = "⊘",
    rarity = Rarity.COMMON,
    window = PlayWindow.IN_RESPONSE,
    seal = SealShape.SHIELD,
    price = 35,
    counters = { _, frame, me -> frame.targetId == me },
) { ctx, _ ->
    ctx.cancelAnsweredFrame(spent = true)
}

/**
 * The one that answers anything, anywhere — and only buys a round.
 *
 * As the spec writes it this cancels any card and "the player keeps the card",
 * which made it strictly better than a nullify at the same rarity: wider reach,
 * and it costs the other player nothing. Keeping the reach and keeping the *card
 * going home* is what makes the pair make sense — a nullify wins the exchange,
 * a nahhh only delays it, and delaying somebody else's card while you are not
 * even the target is worth a common all on its own.
 */
val NAHHH = GamblerCardDef(
    id = "nahhh",
    name = "nahhh",
    description = "stop any card, wherever it is pointed. it goes back to the hand that played it",
    sigil = "✖",
    rarity = Rarity.COMMON,
    window = PlayWindow.IN_RESPONSE,
    seal = SealShape.SHIELD,
    price = 30,
    // Gambler cards only, and this is the one place the set draws that line: the
    // spec says "when another player wants to play a gamblers card", where a
    // nullify and a deflect both say "card". A nahhh answers anything a *person*
    // chose to play; a freeze somebody drew and was made to aim is not that.
    counters = { _, frame, me -> frame.playerId != me && frame.kind == StackKind.GAMBLER },
) { ctx, _ ->
    ctx.cancelAnsweredFrame(spent = false)
}

/**
 * Copy the card being played and have it too.
 *
 * The best test the stack will ever get: a copycat answering a nullify is a copy
 * of a counter, countering the counter. The copy is minted with a `tmp-` id
 * rather than conjured out of the deck — it is a card that never existed, and
 * `Card.isEphemeral` keeps it out of the discard pile so the deck stays honest.
 */
val COPYCAT = GamblerCardDef(
    id = "copycat",
    name = "copycat",
    description = "copy the card being played, and have it happen for you too",
    sigil = "❐",
    rarity = Rarity.RARE,
    window = PlayWindow.IN_RESPONSE,
    seal = SealShape.SCALLOP,
    price = 65,
    // Anything but your own, and only something there is a copy to make of — the
    // copy is minted as a gambler card and run as one, so an action card on the
    // stack is not something this can reach.
    counters = { _, frame, me ->
        frame.playerId != me && frame.kind == StackKind.GAMBLER && Catalog.gambler(frame.cardDefId) != null
    },
) { ctx, play ->
    ctx.copyAnsweredFrame(play.from.id)
}

/**
 * Every gambler card in the game.
 *
 * The rarity split the shop rolls against is 60 / 30 / 10, and it is expressed
 * as how many of each the dealer stocks rather than as weights on this list —
 * see the shop. What this is, is the set of faces that exist.
 */
// ═══════════════════════════════════════════
// The rest of the set
// ═══════════════════════════════════════════

/**
 * A slot spent to make slots. It is a net gain of one, which is thin for a rare
 * — and the reason to keep it that way is that the tray is the mode's whole
 * economy of scarcity: a card that made room cheaply would take the decision out
 * of every other one.
 */
val POUCH = GamblerCardDef(
    id = POUCH_ID,
    name = "pouch",
    description = "hold $POUCH_SLOTS more gambler cards, for as long as you keep this one",
    sigil = "◫",
    rarity = Rarity.RARE,
    window = PlayWindow.PASSIVE,
    seal = SealShape.SCALLOP,
    price = 55,
)

/**
 * Nobody flips out this round.
 *
 * Minted onto every seat rather than held as a rule, so it is a card on each
 * table like anything else — visible, and swappable off you by somebody who
 * would rather you could not.
 */
val NOT_THIS_TIME = GamblerCardDef(
    id = "notThisTime",
    name = "not this time",
    description = "nobody can flip out this round — the table plays on",
    sigil = "⊗",
    rarity = Rarity.JACKPOT,
    window = PlayWindow.ALWAYS,
    seal = SealShape.SHIELD,
    price = 140,
) { ctx, _ ->
    for (player in ctx.state.players) ctx.grantEffect(player.id, NO_FLIP.id)
}

/**
 * Half of what your hand is worth, banked out of reach of a bust.
 *
 * An elegant reuse rather than new machinery: `roundAdjustments` already
 * survives a bust — only `roundScore` is zeroed — and `HALVED` already exists
 * for the card that takes half a hand. Together they are exactly what the spec
 * describes, and neither had to be touched.
 */
val SPLIT_THE_POT = GamblerCardDef(
    id = "splitThePot",
    name = "split the pot",
    description = "bank half your hand now, safe from busting. the rest still rides",
    sigil = "◑",
    rarity = Rarity.RARE,
    window = PlayWindow.ON_TURN,
    seal = SealShape.HEXAGON,
    price = 70,
) { ctx, play ->
    val worth = Engine.handWorth(play.from)
    if (worth <= 0) {
        ctx.emit(GameEvent.Fizzled("splitThePot", play.from.id))
        return@GamblerCardDef
    }
    ctx.adjust(play.from.id, worth / 2)
    ctx.grantEffect(play.from.id, HALVED.id)
}

/**
 * Back into a round you were out of, with nothing.
 *
 * Every card that leaves has to *reach* the discard pile — a hand emptied
 * without one is a hand the deck has lost, which is the failure the whole
 * conservation suite exists to catch.
 */
val REVIVE = GamblerCardDef(
    id = "revive",
    name = "revive",
    description = "played when you are out: back into the round, with nothing in front of you",
    sigil = "↺",
    rarity = Rarity.RARE,
    window = PlayWindow.ON_OUT,
    seal = SealShape.SHIELD,
    price = 75,
) { ctx, play ->
    val player = ctx.player(play.from.id) ?: return@GamblerCardDef
    if (ctx.state.phase != GamePhase.PLAYING) return@GamblerCardDef
    for (card in player.hand + player.passives) ctx.toDiscard(card)
    ctx.update(play.from.id) {
        it.copy(
            hand = emptyList(),
            passives = emptyList(),
            handValue = 0,
            status = PlayerStatus.ACTIVE,
            bustReason = null,
        )
    }
    ctx.emit(GameEvent.Revived(play.from.id))
}

/**
 * Rare, because turning an attack round beats stopping it: a nullify costs its
 * player a card to make nothing happen, and this makes the thing happen to
 * somebody who chose it.
 *
 * It could not be written until there was a gambler card whose effect actually
 * reads who it is pointed at — for the whole of M2 every one of them resolved on
 * whoever played it, so re-pointing a frame changed nothing and the card was
 * held back rather than shipped inert.
 */
val DEFLECT = GamblerCardDef(
    id = "deflect",
    name = "deflect",
    description = "a card aimed at you goes back at whoever threw it",
    sigil = "↩",
    rarity = Rarity.RARE,
    window = PlayWindow.IN_RESPONSE,
    seal = SealShape.SHIELD,
    price = 60,
    counters = { _, frame, me -> frame.targetId == me && frame.targetId != frame.playerId },
) { ctx, _ ->
    val frame = ctx.answeredFrame() ?: return@GamblerCardDef
    ctx.retargetAnsweredFrame(frame.playerId)
}

/** The most "already down" will add to a round, as a share of it. */
const val ALREADY_DOWN_MAX = 0.10

/** What "taxes" takes out of everybody else's round. */
const val TAXES_PERCENT = 10

/** What "loan" hands over, and what it wants back at the end of the round. */
const val LOAN_ADVANCE = 200
const val LOAN_REPAYMENT = 300

/**
 * The debt a loan leaves behind, held as a card in the borrower's own tray.
 *
 * A card rather than a `GameState.debts` map, and the slot it occupies is not
 * an accident of the implementation — it is the second half of the price. You
 * are two hundred up and one card down until the round ends.
 */
const val LOAN_DEBT_ID = "loanDebt"

val DOUBLE_DOWN = GamblerCardDef(
    id = "doubleDown",
    name = "double down",
    description = "everything this round pays you, or costs you, counted twice",
    sigil = "⧉",
    rarity = Rarity.RARE,
    window = PlayWindow.ALWAYS,
    seal = SealShape.HEXAGON,
    price = 65,
) { ctx, play ->
    ctx.grantEffect(play.from.id, DOUBLE_DOWN_ARMED.id)
}

val TAXES = GamblerCardDef(
    id = "taxes",
    name = "taxes",
    description = "take a tenth of what everybody else makes this round",
    sigil = "§",
    rarity = Rarity.RARE,
    window = PlayWindow.ON_TURN,
    seal = SealShape.HEXAGON,
    price = 70,
) { ctx, play ->
    ctx.grantEffect(play.from.id, TAXES_ARMED.id)
}

/**
 * The spec has this lasting the rest of the *game*; it lasts the round.
 *
 * Every lasting effect in this set is a card in the modifier row that goes when
 * the round does, and one exception would be a second lifetime rule for a ten
 * per cent boost. It would also compound: a permanent edge for being behind,
 * granted once, is a card that quietly wins long games.
 */
val ALREADY_DOWN = GamblerCardDef(
    id = "alreadyDown",
    name = "already down",
    description = "the further behind the leader you are, the more this round pays you",
    sigil = "↗",
    rarity = Rarity.RARE,
    window = PlayWindow.ON_TURN,
    seal = SealShape.SCALLOP,
    price = 50,
) { ctx, play ->
    ctx.grantEffect(play.from.id, ALREADY_DOWN_ARMED.id)
}

/**
 * Two hundred now, three hundred at the end of the round.
 *
 * The debt is minted as a gambler card into the borrower's own hidden hand,
 * which keeps rule one intact, needs no new field on the state, and costs a slot
 * — which is the nicest thing about the card. Taking a loan with a full tray is
 * therefore not possible, and that is the right answer rather than an oversight:
 * there is nowhere to put the IOU.
 */
val LOAN = GamblerCardDef(
    id = "loan",
    name = "loan",
    description = "$LOAN_ADVANCE from the dealer now. $LOAN_REPAYMENT back when the round is paid",
    sigil = "₪",
    rarity = Rarity.RARE,
    window = PlayWindow.ON_TURN,
    seal = SealShape.HEXAGON,
    price = 45,
) { ctx, play ->
    val debt = Card(
        id = ctx.mint(LOAN_DEBT_ID),
        kind = CardKind.GAMBLER,
        label = "the debt",
        value = 0,
        defId = LOAN_DEBT_ID,
    )
    if (!ctx.giveGambler(play.from.id, debt)) {
        // Nowhere to put the IOU. Nothing is advanced, because a debt that
        // cannot be recorded is a debt that never comes due.
        ctx.emit(GameEvent.Fizzled("loan", play.from.id))
        return@GamblerCardDef
    }
    ctx.adjust(play.from.id, LOAN_ADVANCE)
}

/**
 * The IOU. Never dealt, never bought, never played — it sits in a tray taking up
 * room until the round is paid and `Engine.settleGamblerEffects` collects it.
 */
val LOAN_DEBT = GamblerCardDef(
    id = LOAN_DEBT_ID,
    name = "the debt",
    description = "$LOAN_REPAYMENT owed to the dealer when this round is paid",
    sigil = "₪",
    rarity = Rarity.COMMON,
    window = PlayWindow.PASSIVE,
    accent = "#8f3b2e",
    seal = SealShape.SPIKE,
    price = 1,
    obtainable = false,
)

/** How many cards a "foreseer" shows you, and how many a "stacked deck" does. */
const val FORESIGHT_CARDS = 3
const val STACKED_DECK_CARDS = 5

/** What the dealer pays to take a card back. The dealer keeps the rounding. */
const val BUYBACK_PERCENT = 70

fun buybackPrice(def: GamblerCardDef): Int = def.price * BUYBACK_PERCENT / 100

/**
 * The next three cards, to you and nobody else.
 *
 * The second consumer of the per-viewer projection built for the hidden hand,
 * and the cheapest possible proof that it works: no engine risk at all, and if
 * the projection were wrong this card would show everybody the deck.
 */
val FORESEER = GamblerCardDef(
    id = "foreseer",
    name = "foreseer",
    description = "see the next $FORESIGHT_CARDS cards in the deck, for the rest of the round",
    sigil = "◉",
    rarity = Rarity.RARE,
    window = PlayWindow.ALWAYS,
    seal = SealShape.SCALLOP,
    price = 60,
) { ctx, play ->
    ctx.grantEffect(play.from.id, FORESIGHT.id)
}

/**
 * The spec has this reordering five cards. It shows you five and lets you say
 * which one comes next, which is the same decision with one answer instead of
 * a permutation — and a permutation is a prompt this game has no picker for.
 */
val STACKED_DECK = GamblerCardDef(
    id = "stackedDeck",
    name = "stacked deck",
    description = "look at the next $STACKED_DECK_CARDS cards and choose which one comes off next",
    sigil = "☰",
    rarity = Rarity.JACKPOT,
    window = PlayWindow.ON_TURN,
    seal = SealShape.HEXAGON,
    price = 130,
    pickKind = PickKind.CARD,
    picks = 1,
    cardTargets = { state, _ -> state.deck.take(STACKED_DECK_CARDS).map { it.id } },
) { ctx, play ->
    val chosen = play.cards.firstOrNull() ?: return@GamblerCardDef
    ctx.putOnTopOfDeck(chosen.id)
}

/**
 * Everything in your tray, for the same number of new ones.
 *
 * The new cards are minted, like the shop's — the dealer's stock is not the
 * table's deck. The old ones go to the discard pile if they came off the deck,
 * and simply cease if they were minted, which `toDiscard` already handles.
 */
val TRADE_IN = GamblerCardDef(
    id = "tradeIn",
    name = "trade in",
    description = "hand the dealer every gambler card you are holding, and take that many new ones",
    sigil = "⇄",
    rarity = Rarity.JACKPOT,
    window = PlayWindow.ON_TURN,
    seal = SealShape.SCALLOP,
    price = 120,
) { ctx, play ->
    // Read before anything moves: the card doing this has already left the tray.
    val held = ctx.player(play.from.id)?.gamblers.orEmpty()
    if (held.isEmpty()) {
        ctx.emit(GameEvent.Fizzled("tradeIn", play.from.id))
        return@GamblerCardDef
    }
    for (card in held) ctx.toDiscard(card)
    ctx.update(play.from.id) { it.copy(gamblers = emptyList()) }
    for (def in rollShelfDefs(ctx.rng, held.size)) {
        ctx.giveGambler(
            play.from.id,
            Card(
                id = ctx.mint("trade"),
                kind = CardKind.GAMBLER,
                label = def.name,
                value = 0,
                defId = def.id,
            ),
        )
    }
}

/**
 * Back into the round with the hand you busted on, duplicates and all.
 *
 * More expensive than it looks, and the expensive part is not the revival: a
 * hand with a duplicate in it busts again the moment anything touches it, so
 * this has to hand its holder a card that stops that happening — see [COOLER]
 * and `Ctx.checkBust`.
 */
val COOLER_REVIVE = GamblerCardDef(
    id = "coolerRevive",
    // Short enough to fit on its own card: the face sets the name above the
    // seal, and "the cooler revive" wrapped to three lines and was struck
    // through by its own stamp.
    name = "cooler revive",
    description = "played when you are out: back into the round with your hand as it was, duplicates and all",
    sigil = "❆",
    rarity = Rarity.JACKPOT,
    window = PlayWindow.ON_OUT,
    seal = SealShape.SHIELD,
    price = 150,
) { ctx, play ->
    if (ctx.state.phase != GamePhase.PLAYING) return@GamblerCardDef
    ctx.update(play.from.id) { it.copy(status = PlayerStatus.ACTIVE, bustReason = null) }
    ctx.grantEffect(play.from.id, COOLER.id)
    ctx.emit(GameEvent.Revived(play.from.id))
}

/**
 * Sell the rest of your hand back, between rounds.
 *
 * The one card in the set that is about the shop rather than about a round,
 * which is why there is a seventh window. It sells *everything* rather than
 * asking which: a picker over a hidden hand is a prompt only one person can see
 * the contents of, and "clear the shelf" is the decision anybody playing this
 * card has already made.
 */
val BACK_TO_THE_SHOP = GamblerCardDef(
    id = "backToTheShop",
    name = "back to the shop",
    description = "between rounds: sell every other gambler card you hold for $BUYBACK_PERCENT% of its price",
    sigil = "⤶",
    rarity = Rarity.COMMON,
    window = PlayWindow.INTERLUDE,
    seal = SealShape.HEXAGON,
    price = 20,
) { ctx, play ->
    val held = ctx.player(play.from.id)?.gamblers.orEmpty()
    if (held.isEmpty()) {
        ctx.emit(GameEvent.Fizzled("backToTheShop", play.from.id))
        return@GamblerCardDef
    }
    var paid = 0
    for (card in held) {
        paid += Catalog.gambler(card.defId)?.let { buybackPrice(it) } ?: 0
        ctx.toDiscard(card)
    }
    ctx.update(play.from.id) { it.copy(gamblers = emptyList()) }
    // Through `bank`, not `adjust`: between rounds there is no round to score.
    ctx.bank(play.from.id, paid)
    // Face up as they go. Selling is information you paid for by giving the
    // cards up, and a hidden sale would be a score jump nobody could account for.
    ctx.emit(GameEvent.SoldBack(play.from.id, held, paid))
}

/**
 * Everything on the table is worth the opposite of what it says, for everybody.
 *
 * The spec offers three sub-modes and asks the player to pick one: duplicates
 * do not bust but nobody may stay, everyone must stay after four cards, or all
 * point values are inverted. It ships with the third.
 *
 * The other two want a *mid-round mutable rule set*, and `RuleSet` is a pure
 * function of `GameConfig` — built fresh in `Ctx.<init>`, in `validTargets`, in
 * the bots' `shouldHit`, in `toView`, freely and cheaply, precisely because it
 * cannot change under anybody. Making it mutable for one card would be the
 * largest single risk in the whole set, and it would buy one card.
 *
 * The third sub-mode, meanwhile, already exists: it is exactly what `ANTIMATTER`
 * does, and minting one onto every seat costs nothing and behaves correctly with
 * everything else — a ×2 doubles the hole, a +10 fills a little of it back in,
 * and holding one lifts the floor under your own round. Which is also what makes
 * it a jackpot: it does not hurt somebody, it turns the whole table over.
 *
 * Every seat still in a round, that is. A seat that has already busted is on
 * nothing, and turning nothing over is not turning it over — it is a fresh
 * penalty on the one player at the table who can no longer do anything about it.
 * That used to be free to ignore, because a bust scored zero however it was
 * decorated; now that an antimatter bust is *paid*, the grant has to say so.
 * "The rest of this round" is exactly the phrase: a seat that is out has no rest
 * of the round.
 *
 * If the three-way choice turns out to be the point of the card in play, that is
 * the moment to pay for a mutable rule set — not before.
 */
val HOUSE_RULES = GamblerCardDef(
    id = "houseRules",
    name = "house rules",
    description = "for the rest of this round, every number on the table is worth the opposite",
    sigil = "⚖",
    rarity = Rarity.JACKPOT,
    window = PlayWindow.ON_TURN,
    accent = "#8f3b2e",
    seal = SealShape.SPIKE,
    price = 160,
) { ctx, _ ->
    for (player in ctx.state.players) {
        if (player.status == PlayerStatus.BUST) continue
        ctx.grantEffect(player.id, ANTIMATTER.id)
    }
}

/** What a rigged bid pays: a tenth over whatever the winning bid was. */
const val RIGGED_BID_PERCENT = 110

/**
 * Outbid the winner, after the hammer.
 *
 * Held rather than played — the auction consumes it — so its cost is the slot it
 * takes up until one comes along. It does nothing at all when nobody bid: the
 * card's promise is to *outbid the winner*, and with no winner there is nothing
 * to outbid. The alternative, a free jackpot, would make holding one strictly
 * better than bidding and every table would converge on nobody bidding at all.
 */
val RIGGED_BID = GamblerCardDef(
    id = "riggedBid",
    name = "rigged bid",
    description = "at the next auction, take the lot for a tenth over the winning bid",
    sigil = "✦",
    rarity = Rarity.RARE,
    window = PlayWindow.PASSIVE,
    seal = SealShape.SPIKE,
    price = 80,
)

/** How many cards a shelf holds. */
const val SHOP_OFFERS = 4

/**
 * Rolls a rarity, 60 / 30 / 10, as the spec prints them.
 *
 * Off the room's own [Rng], like every other roll in the game: a room is
 * replayable from its seed alone, and the shop is not an exception to that.
 */
internal fun rollRarity(rng: Rng): Rarity {
    val roll = rng.nextInt(100)
    return when {
        roll < 60 -> Rarity.COMMON
        roll < 90 -> Rarity.RARE
        else -> Rarity.JACKPOT
    }
}

/**
 * One shelf: [count] gambler cards, rarity-weighted.
 *
 * Never the same card twice on one shelf — four copies of the cheapest common is
 * not a choice — but two shelves may hold the same card, because the stock is
 * private and nothing is being taken off anybody.
 *
 * A rarity that has nothing left to offer falls back to whatever is available,
 * so a thin catalog gives a short shelf rather than an empty one.
 */
internal fun rollShelfDefs(rng: Rng, count: Int = SHOP_OFFERS): List<GamblerCardDef> {
    val taken = mutableListOf<GamblerCardDef>()
    var guard = 0
    while (taken.size < count && guard++ < count * 12) {
        val wanted = GamblerCatalog.forSale(rollRarity(rng)).filterNot { it in taken }
        val pool = wanted.ifEmpty { GamblerCatalog.forSale().filterNot { it in taken } }
        taken += rng.pick(pool) ?: break
    }
    return taken
}

/** Names one card on one shelf. The slot is in the id because the slot is what is bought. */
fun offerIdForGambler(slot: Int, defId: String): String = "gambler:$slot:$defId"

/**
 * The one jackpot on the block, or null when this build has none to sell.
 *
 * Rolled blind of what anybody is holding. A lot that avoided the cards already
 * in your tray would be a window into the one thing this mode keeps secret.
 */
internal fun rollLotDef(rng: Rng): GamblerCardDef? =
    rng.pick(GamblerCatalog.forSale(Rarity.JACKPOT))

object GamblerCatalog {
    val all: List<GamblerCardDef> = listOf(
        REDIRECT, SHUFFLE, DRAW_TWO, SECOND_OPINION, CHEATING, FUCK_IT,
        NULLIFY, NAHHH, DEFLECT, COPYCAT,
        POUCH, SPLIT_THE_POT, REVIVE, DOUBLE_DOWN, TAXES, ALREADY_DOWN, LOAN,
        FORESEER, BACK_TO_THE_SHOP,
        RIGGED_BID,
        NOT_THIS_TIME, STACKED_DECK, TRADE_IN, COOLER_REVIVE, HOUSE_RULES,
        // Never dealt, never bought, never played — it exists so a face can be
        // drawn for the IOU a loan leaves in your tray.
        LOAN_DEBT,
    )

    val byId: Map<String, GamblerCardDef> = all.associateBy { it.id }

    fun byRarity(rarity: Rarity): List<GamblerCardDef> = all.filter { it.rarity == rarity }

    /** Only what a deck may hold or a shop may sell — see [GamblerCardDef.obtainable]. */
    fun forSale(rarity: Rarity? = null): List<GamblerCardDef> =
        all.filter { it.obtainable && (rarity == null || it.rarity == rarity) }
}
