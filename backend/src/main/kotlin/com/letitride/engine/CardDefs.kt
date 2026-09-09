package com.letitride.engine

/** How a passive card contributes to the round score. */
enum class PassiveScoring {
    /** Adds [PassiveCardDef.bonusPoints] after the number cards are totalled. */
    FLAT,

    /** Doubles the number-card total (Flip 7's ×2). Applied before flat bonuses. */
    DOUBLE_NUMBERS,

    /** Scores nothing — it is a protection card, or a card that does its damage elsewhere. */
    NONE,

    /** The whole round is worth nothing unless its holder ended it on the flip. */
    VOID_UNLESS_FLIP,

    /** Halves whatever the round came to, last of all. */
    HALVE,

    /**
     * Every number card counts against its holder instead of for them — a 13 is
     * worth minus thirteen. Applied to the hand total before anything else, so
     * a x2 doubles the hole and a +10 fills a little of it back in.
     */
    NEGATE,
}

/**
 * Who a card is allowed to be pointed at.
 *
 * [reachesFinished] is a fact about what the card *takes*, which is why it is
 * written on the rule rather than left to the house rules. A strike takes a
 * card, a steal takes a card, a swap takes a whole row — and every one of those
 * is still lying face up in front of a seat that has banked or busted, still
 * worth points to whoever ends up holding it. A freeze takes the rest of
 * somebody's round, and a seat that is out has no rest of the round to take;
 * offering one is a card spent for nothing, which is the thing `fizzle` and
 * [ActionCardDef.skipHolding] exist to prevent.
 *
 * Two cards had already worked this out for themselves — `swapCards` reads
 * every card on the table and the spin turns every seat — so the rules that
 * point at what somebody is holding are simply being brought in line with the
 * two that point at the cards directly.
 */
enum class TargetRule(
    /** Whether this rule reaches a seat that is already finished with the round. */
    val reachesFinished: Boolean,
) {
    /** Anyone still in the round, including the player who drew it. */
    ANY_ACTIVE(reachesFinished = false),

    /**
     * Anyone holding cards, whatever became of their round. A banked hand is
     * points that are still on the board and a busted one is the duplicate that
     * killed it — a card somebody could be made to carry.
     */
    ANYONE_WITH_CARDS(reachesFinished = true),

    /**
     * Someone else who is holding anything at all — a hand, a modifier row, or
     * both. What a card that moves everything in front of you has to ask for: a
     * player sitting behind nothing but a ×2 has plenty worth swapping for, and
     * [ANYONE_WITH_CARDS] would not offer them.
     */
    OTHER_WITH_ANYTHING(reachesFinished = true),

    /** Anyone at the table at all — busted, gone out, or still playing. */
    ANY_PLAYER(reachesFinished = true),

    /** Resolves on the drawer; no picker is shown. */
    SELF(reachesFinished = false),
}

/**
 * Everything a card's effect gets to look at when it resolves.
 *
 * A record rather than a parameter list because the things a card can be asked
 * for keep growing — a seat, an answer, a handful of cards — and every new one
 * would otherwise be a fourth, fifth, sixth parameter on all thirteen cards.
 */
data class Play(
    /** Whoever drew it. */
    val from: Player,
    /** The seat it was pointed at, or the drawer for a card that points at nobody. */
    val target: Player,
    /** The drawer's answer to [ActionCardDef.options], already validated against it. */
    val choice: String? = null,
    /** The cards picked, for a card that points at cards instead of a seat. */
    val cards: List<Card> = emptyList(),
    /**
     * Whether this is the card being played, or a question it set up earlier
     * being answered — see [PHASE_PLAY]. A card that does two different things
     * at two different moments reads them apart here.
     */
    val phase: String = PHASE_PLAY,
    /**
     * Everybody's answer, for a card that asked the whole table at once. The
     * single-answer fields above are the drawer's own, pulled out because that
     * is what nearly every card wants.
     */
    val answers: Map<String, Answer> = emptyMap(),
    /**
     * What the card settled on while it was being announced, handed back to it
     * when the table has finished watching — the face the coin came down on.
     * Only ever set in [PHASE_OUTCOME]; see [PendingOutcome].
     */
    val result: String? = null,
    /**
     * Every seat the outcome landed on, for a card that settles with more than
     * one at a time. Empty everywhere else, where [target] is the whole of it.
     */
    val targets: List<Player> = emptyList(),
) {
    /** What [from] sent back, unresolved — an offer id, rather than a card. */
    val picked: List<String> get() = answers[from.id]?.cards ?: emptyList()
}

data class ActionCardDef(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val targetRule: TargetRule = TargetRule.ANY_ACTIVE,
    /**
     * A question the drawer has to answer before the card resolves — heads or
     * tails, left or right. A card with options always pauses the table for a
     * decision, even when it targets nobody but the drawer, and the answer is
     * handed to [onPlay]. Empty means the card asks nothing.
     */
    val options: List<String> = emptyList(),
    /**
     * A passive that makes a seat pointless to aim at, because it is already
     * holding one. A card whose whole effect is to hand somebody that passive
     * would otherwise be spent for nothing — so those seats are not offered,
     * and a card with no seat left to hit fizzles and is replaced.
     */
    val skipHolding: String? = null,
    /**
     * What the drawer is asked to point at. A card that points at cards names
     * them with [cardTargets] instead of [targetRule], and resolves on the
     * drawer's own seat.
     */
    val pickKind: PickKind = PickKind.PLAYER,
    /** How many picks the card wants. Only ever more than one for cards. */
    val picks: Int = 1,
    /**
     * Every card on the table this one could be pointed at, when [pickKind] is
     * [PickKind.CARD]. An empty list means there is nothing to point at and the
     * card fizzles, exactly as an empty target list does.
     */
    val cardTargets: (GameState, String) -> List<String> = { _, _ -> emptyList() },
    /**
     * Whether a deck may contain this. False for a definition that only ever
     * exists to put a question on the table — a house rule asking something,
     * with no card behind it. It still ships in the catalog, because the client
     * has to be able to draw the prompt; it is simply never dealt, never
     * listed as a card, and never buildable into a deck.
     */
    val deckable: Boolean = true,
    /**
     * What this costs to buy outright — see [MUTATE]. Priced by what it does to
     * a round rather than by how often it turns up, so a card that ends
     * somebody's round costs about what a round is worth.
     */
    val price: Int = 20,
    /** The effect. */
    val onPlay: (Ctx, Play) -> Unit,
) {
    val selfTarget: Boolean get() = targetRule == TargetRule.SELF

    /** True when the table has to stop and ask the drawer something. */
    val needsChoice: Boolean get() = options.isNotEmpty()

    /** True when the drawer picks cards off the table rather than a seat. */
    val picksCards: Boolean get() = pickKind == PickKind.CARD

    /**
     * Everyone this card could meaningfully be played on right now. An empty
     * list means the card cannot do anything and should not be sitting there
     * waiting for a pick it will never get.
     */
    fun validTargets(state: GameState, fromId: String): List<String> =
        targetsFor(targetRule, state, fromId, skipHolding)
}

/**
 * Everyone a card played by [fromId] under [rule] could meaningfully be pointed
 * at right now, minus anybody already holding [skipHolding].
 *
 * A free function rather than a method because two kinds of card ask the same
 * question — an action card off the deck and a gambler card out of a hidden
 * hand — and two copies of a rule this fiddly would come apart the first time
 * one of them was corrected.
 */
internal fun targetsFor(
    rule: TargetRule,
    state: GameState,
    fromId: String,
    skipHolding: String? = null,
): List<String> {
    // Two ways a seat that is out is still worth pointing at. The card's own —
    // what it takes is lying in front of them and stays worth having wherever
    // it ends up, see [TargetRule.reachesFinished] — and "extreme", which
    // widens the rest of them on top of that. The house rules are read off the
    // state rather than passed in: a game knows what it is being played under,
    // and threading a rule set through every call site would only carry the
    // same answer by hand.
    val pool =
        if (rule.reachesFinished || RuleSet.of(state.config).reachesFinished) state.players
        else state.players.filter { it.status == PlayerStatus.ACTIVE }
    val byRule = when (rule) {
        TargetRule.SELF -> listOf(fromId)
        TargetRule.ANY_ACTIVE -> pool.map { it.id }
        TargetRule.ANYONE_WITH_CARDS -> pool.filter { it.hand.isNotEmpty() }.map { it.id }

        TargetRule.OTHER_WITH_ANYTHING ->
            pool.filter { it.id != fromId && (it.hand.isNotEmpty() || it.passives.isNotEmpty()) }
                .map { it.id }

        TargetRule.ANY_PLAYER -> state.players.map { it.id }
    }
    val held = skipHolding ?: return byRule
    return byRule.filterNot { id ->
        state.player(id)?.passives?.any { it.defId == held } == true
    }
}

/**
 * The stamp a passive's sigil is struck in. Shape carries meaning before the
 * writing is read: a shield guards, a token pays, a scalloped stamp is the one
 * on a keepsake, and a spiked one is a card nobody wants.
 */
enum class SealShape { CIRCLE, HEXAGON, SHIELD, SCALLOP, SPIKE }

data class PassiveCardDef(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val bonusPoints: Int = 0,
    val scoring: PassiveScoring = PassiveScoring.NONE,
    /**
     * The ink this card is printed in. Lives here beside [sigil] for the same
     * reason: the client owns no rules, so everything it needs to draw a face
     * it has never seen comes down with the catalog.
     */
    val accent: String = "#4a6852",
    val seal: SealShape = SealShape.CIRCLE,
    /**
     * What this costs to buy outright — see [MUTATE]. Zero means it is not for
     * sale: nobody would buy [DISCORDIA], and the effect cards below are minted
     * by the cards that cause them rather than ever being on a shelf.
     */
    val price: Int = 20,
    /**
     * What the holder pays anybody who plays an action card on them — see
     * [DISCORDIA]. Zero for every card that is simply worth having.
     */
    val spite: Int = 0,
    /**
     * Whether a deck may contain this. False for the effect cards below, which
     * are minted by whatever causes them: one shuffled into the deck would be a
     * "you score nothing" card sitting there to be drawn by accident, and would
     * outlive the round it was meant to last.
     */
    val deckable: Boolean = true,
    /**
     * Whether its holder may choose to go out. False for [ANTIMATTER]: the only
     * ways off a card that counts your hand against you are to flip, to give it
     * to somebody else, or to be sent out by somebody — a freeze still works,
     * and is worth playing on a friend for once. Busting is not one of them,
     * which is the whole reason an antimatter bust still costs its holder the
     * hand: otherwise the deck would be handing the stop back.
     */
    val allowsStaying: Boolean = true,
) {
    /**
     * A card that keeps taking from you for as long as you are holding it, as
     * opposed to one that writes off the round it arrived in. The difference
     * matters to a deck: a curse has to be passable, or it is a penalty on
     * whoever drew it rather than a card — see `DeckTest`.
     */
    val isCurse: Boolean
        get() = spite > 0 || scoring == PassiveScoring.NEGATE || !allowsStaying
}

/** The ink every card you would rather not be holding is printed in. */
private const val SOUR = "#8f3b2e"

// ═══════════════════════════════════════════════
// Effect cards
// ═══════════════════════════════════════════════

/**
 * Effects a player carries for the rest of the round.
 *
 * These used to be marks — a set of ids on the player, deliberately not cards,
 * so that nothing could take one off you. Rule one of this game is that
 * everything is a card, so they are cards: they lie in the modifier row with
 * everything else, which means they can be swapped, traded, and pushed onto
 * somebody who does not want them. That is the point rather than a side effect
 * — a bad card you can hand to a neighbour is a better card than a bad rule.
 *
 * None of them is ever shuffled into a deck. They are minted when whatever
 * causes them resolves, and [Card.isEphemeral] drops them at the end of the
 * round exactly where a mark used to be wiped, so the deck never grows.
 */

/** The flip is off the table: this hand has no ceiling but the duplicate. */
val NO_FLIP = PassiveCardDef(
    id = "noFlip",
    name = "just one more",
    description = "you cannot flip out this round — keep drawing",
    sigil = "∞",
    accent = "#4a6b82",
    seal = SealShape.HEXAGON,
    price = 0,
    deckable = false,
)

/** Nothing this hand holds is worth anything unless it flips out. */
val MUST_FLIP = PassiveCardDef(
    id = "mustFlip",
    name = "unlucky 7",
    description = "scores nothing this round without the flip",
    sigil = "7",
    scoring = PassiveScoring.VOID_UNLESS_FLIP,
    accent = SOUR,
    seal = SealShape.SPIKE,
    price = 0,
    deckable = false,
)

/** Went furthest either way on an "all in", and pays for it at the end. */
// ─── Armed by a gambler card, spent by the next draw ───
//
// Rolling rules has a family of cards whose whole promise is about the *next*
// card you turn over, and every one of them is the same shape: play it now, and
// it sits in your modifier row until a draw uses it up. They are cards rather
// than flags on the player for the reason everything else here is — you can see
// one across the table, and a swap can hand it to somebody else.

val REDIRECT_ARMED = PassiveCardDef(
    id = "redirectArmed",
    name = "redirect",
    description = "the next card you draw is yours to keep or to hand on",
    sigil = "⤳",
    accent = GAMBLER_INK,
    seal = SealShape.SHIELD,
    price = 0,
    deckable = false,
)

val DRAW_TWO_ARMED = PassiveCardDef(
    id = "drawTwoArmed",
    name = "draw 2",
    description = "you take an extra card on your next turn",
    sigil = "❊",
    accent = GAMBLER_INK,
    seal = SealShape.SCALLOP,
    price = 0,
    deckable = false,
)

val SECOND_OPINION_ARMED = PassiveCardDef(
    id = "secondOpinionArmed",
    name = "second opinion",
    description = "you may throw the next card you draw back and take another",
    sigil = "⚖",
    accent = GAMBLER_INK,
    seal = SealShape.SCALLOP,
    price = 0,
    deckable = false,
)

// ─── Armed by a gambler card, read when the round is paid ───
//
// These three are about what a round *pays* rather than what a hand is worth,
// so `Engine.settleGamblerEffects` reads them off the modifier row after the
// hands have been totalled. They are cards for rule one's reason and for a
// second one: a swap can take a "double down" off you before it pays out, which
// is a far better card than a flag on a player would have been.

val DOUBLE_DOWN_ARMED = PassiveCardDef(
    id = "doubleDownArmed",
    name = "double down",
    description = "everything this round pays you, or costs you, counted twice",
    sigil = "⧉",
    accent = GAMBLER_INK,
    seal = SealShape.HEXAGON,
    price = 0,
    deckable = false,
)

val TAXES_ARMED = PassiveCardDef(
    id = "taxesArmed",
    name = "taxes",
    description = "a tenth of everybody else's round, into yours",
    sigil = "§",
    accent = GAMBLER_INK,
    seal = SealShape.HEXAGON,
    price = 0,
    deckable = false,
)

val ALREADY_DOWN_ARMED = PassiveCardDef(
    id = "alreadyDownArmed",
    name = "already down",
    description = "the further behind the leader you are, the more this round is worth",
    sigil = "↗",
    accent = GAMBLER_INK,
    seal = SealShape.SCALLOP,
    price = 0,
    deckable = false,
)

/**
 * The one card that cannot bust and cannot be swapped away: a hand of cards you
 * are not allowed to lose, for the round after a "cooler revive".
 */
val FORESIGHT = PassiveCardDef(
    id = "foresight",
    name = "foreseer",
    description = "you can see the next few cards in the deck",
    sigil = "◉",
    accent = GAMBLER_INK,
    seal = SealShape.SCALLOP,
    price = 0,
    deckable = false,
)

val COOLER = PassiveCardDef(
    id = "cooler",
    name = "the cooler",
    description = "duplicates do not bust you for the rest of this round",
    sigil = "❄",
    accent = GAMBLER_INK,
    seal = SealShape.SHIELD,
    price = 0,
    deckable = false,
)

val HALVED = PassiveCardDef(
    id = "halved",
    name = "all in",
    description = "scores half this round — you bet the highest or the lowest",
    sigil = "½",
    scoring = PassiveScoring.HALVE,
    accent = SOUR,
    seal = SealShape.SPIKE,
    price = 0,
    deckable = false,
)

/** Armed: this player does not go out alone. Spent the moment it fires. */
val BOMBER = PassiveCardDef(
    id = "bomber",
    name = "suicide bomber",
    description = "when you bust, you pick a player to go with you",
    sigil = "☠",
    accent = SOUR,
    seal = SealShape.SPIKE,
    price = 0,
    deckable = false,
)


// ═══════════════════════════════════════════════
// Action cards
// ═══════════════════════════════════════════════

/** Flip 7's "Freeze": the target banks what they have and is out for the round. */
val FREEZE = ActionCardDef(
    id = "freeze",
    name = "freeze",
    description = "force a player to go out this round",
    sigil = "❄",
    price = 20,
) { ctx, play ->
    if (play.target.status == PlayerStatus.ACTIVE) {
        ctx.update(play.target.id) { it.copy(status = PlayerStatus.STAYED) }
        ctx.emit(GameEvent.Freeze(play.target.id))
    }
}

/** Flip 7's "Flip Three": the target immediately draws three cards. */
val DRAW_THREE = ActionCardDef(
    id = "drawThree",
    name = "draw 3!",
    description = "force a player to draw 3 cards",
    sigil = "3↓",
    price = 15,
) { ctx, play ->
    if (play.target.status == PlayerStatus.ACTIVE) ctx.pushForcedDraws(play.target.id, 3)
}

val STRIKE = ActionCardDef(
    id = "strike",
    name = "strike",
    description = "target loses their highest card, in the round or out of it",
    sigil = "✗",
    targetRule = TargetRule.ANYONE_WITH_CARDS,
    price = 15,
) { ctx, play ->
    val fresh = ctx.player(play.target.id) ?: return@ActionCardDef
    // A banked hand is still worth points, so striking one is the whole idea.
    // Whether this seat may be aimed at at all was settled by
    // [ActionCardDef.validTargets] before it got here.
    if (fresh.hand.isEmpty()) return@ActionCardDef
    if (ctx.hasPassive(fresh.id, ARMOR.id)) {
        ctx.consumePassive(fresh.id, ARMOR.id)
        return@ActionCardDef
    }
    ctx.discardHighest(fresh.id)
}

val STEAL = ActionCardDef(
    id = "steal",
    name = "steal",
    description = "take a random card from target — a modifier as easily as a number",
    sigil = "◈",
    targetRule = TargetRule.OTHER_WITH_ANYTHING,
    price = 20,
) { ctx, play ->
    if (play.from.id == play.target.id) return@ActionCardDef
    val card = ctx.stealRandom(play.target.id, play.from.id)
    // The stolen card can duplicate something the thief already holds — and it
    // can be something they would much rather have left where it was.
    if (card != null) ctx.resolveBustAfterGain(play.from.id)
}

/** Named `hex` on the wire — deck presets and saved configs key on the id. */
val HEX = ActionCardDef(
    id = "hex",
    name = "skip",
    description = "target skips their next turn",
    sigil = "⏭",
    price = 10,
) { ctx, play ->
    if (play.target.status == PlayerStatus.ACTIVE) ctx.skip(play.target.id)
}

/**
 * Everything in front of you changes places with everything in front of them —
 * the hand and the modifier row both. Everything is a card in this game, so
 * "your hand" is everything you are holding, and a swap that left the ×2 behind
 * would be picking and choosing which cards count.
 */
val SWAP = ActionCardDef(
    id = "swap",
    name = "swap hands",
    description = "trade everything you are holding with another player — modifiers too",
    sigil = "⇄",
    targetRule = TargetRule.OTHER_WITH_ANYTHING,
    price = 20,
) { ctx, play ->
    if (play.from.id == play.target.id) return@ActionCardDef
    // Whole rows move, so no duplicate can appear — but "blackjacking" caps the
    // total, and the hand coming back can be a busted one: a seat that is out
    // is still holding cards, and taking them off it is the point.
    for (id in ctx.swapHands(play.from.id, play.target.id)) {
        ctx.resolveBustAfterGain(id, finishedToo = true)
    }
}

/**
 * Every card lying face up anywhere on the table — hands and modifiers alike,
 * whatever became of the player in front of them. What [SWAP_CARDS] is allowed
 * to reach for.
 *
 * A seat that has busted or gone out is still holding its cards, and they are
 * still worth something: a banked hand is points, and a modifier row is a bomb
 * or a discordia somebody would dearly like to be rid of. Leaving those out
 * meant a card whose whole purpose is to be passed on could not be passed to
 * two thirds of the table by the end of a round.
 */
private fun cardsOnTable(state: GameState): List<Card> =
    state.players.flatMap { it.hand + it.passives }

/**
 * Pick any two cards on the table and trade their places. Unlike [SWAP], which
 * moves whole hands and so can never create a duplicate, this deals cards into
 * hands one at a time — which is exactly how it busts people, and the point of
 * playing it.
 *
 * Both cards have to belong to different players. Two cards changing places
 * inside one hand is a hand that has not changed, and a card that resolves to
 * nothing is worse than no card at all.
 */
val SWAP_CARDS = ActionCardDef(
    id = "swapCards",
    name = "swap cards",
    description = "trade any two cards on the table — hands or modifiers",
    sigil = "↔",
    // The drawer picks cards, not a seat, so it resolves on their own.
    targetRule = TargetRule.SELF,
    pickKind = PickKind.CARD,
    picks = 2,
    cardTargets = { state, _ ->
        // Nothing to trade unless at least two seats are holding something.
        val onTable = cardsOnTable(state).map { it.id }.toSet()
        val owners = state.players.count { p -> (p.hand + p.passives).any { it.id in onTable } }
        if (owners < 2) emptyList() else onTable.toList()
    },
    price = 25,
) { ctx, play ->
    val (first, second) = play.cards.take(2).let { it.getOrNull(0) to it.getOrNull(1) }
    if (first == null || second == null) return@ActionCardDef
    // Either seat can be one that already finished its round — see
    // [cardsOnTable] — and a banked hand handed a duplicate is a bust like any
    // other.
    for (id in ctx.swapCards(first.id, second.id)) ctx.resolveBustAfterGain(id, finishedToo = true)
}

/** The two faces of the coin, as offered to the drawer and sent back. */
const val COIN_HEADS = "heads"
const val COIN_TAILS = "tails"

/** Their own ids, so the outcome each of them defers can name the card. */
const val COIN_FLIP_ID = "coinFlip"
const val ASSASSINATION_ID = "assassination"

/**
 * Call it in the air: a correct call is worth a ×2, a wrong one busts you.
 * Replaces double-or-nothing, which flipped the same coin without asking.
 */
val COIN_FLIP = ActionCardDef(
    id = COIN_FLIP_ID,
    name = "coin flip",
    description = "call heads or tails: right doubles your cards, wrong busts you",
    sigil = "⌾",
    targetRule = TargetRule.SELF,
    options = listOf(COIN_HEADS, COIN_TAILS),
    price = 15,
) { ctx, play ->
    val target = play.target
    if (play.phase == PHASE_OUTCOME) {
        // The coin has come down. Whether it is still worth anything is asked
        // again here: the table moved on while it was in the air.
        if (target.status != PlayerStatus.ACTIVE) return@ActionCardDef
        if (play.choice == play.result) {
            ctx.grantEphemeralPassive(target.id, DOUBLE_POINTS.id)
        } else {
            ctx.bust(target.id, "coin flip")
        }
        return@ActionCardDef
    }

    // Under "double it!" the second flip is for a player who may already be
    // out; a coin is not thrown for someone who is no longer in the round.
    if (target.status == PlayerStatus.ACTIVE) {
        val call = play.choice ?: COIN_HEADS
        val landed = if (ctx.rng.nextBoolean()) COIN_HEADS else COIN_TAILS
        // Thrown now and settled in a moment. Both faces travel on the event so
        // the coin can land on the right one, and the payout or the bust waits
        // until it has — see [PendingOutcome]. Resolving here instead meant the
        // seat read "bust!" while the coin was still turning over.
        ctx.emit(GameEvent.CoinFlip(target.id, call, landed))
        ctx.land(COIN_FLIP_ID, play.from.id, target.id, result = landed, choice = call)
    }
}

/** Which way the table turns, as offered to the drawer and sent back. */
const val SPIN_LEFT = "left"
const val SPIN_RIGHT = "right"

/**
 * Everything in front of every seat — the hand and the modifier row both —
 * slides one place the way the drawer called, busted seats and banked ones
 * included. See [Ctx.rotateHands].
 */
val SPIN_TABLE = ActionCardDef(
    id = "spinTable",
    name = "spin the table",
    description = "everything on the table slides one seat left or right — modifiers and busted hands too",
    sigil = "↻",
    targetRule = TargetRule.SELF,
    options = listOf(SPIN_LEFT, SPIN_RIGHT),
    price = 15,
) { ctx, play ->
    val direction = if (play.choice == SPIN_LEFT) SPIN_LEFT else SPIN_RIGHT
    // Hands move whole, so nobody is handed a card that clashes with one they
    // kept — but the hand that arrives can be a busted one, and a busted hand
    // is holding the duplicate that killed it. Every seat is re-checked,
    // whatever became of its round: catching one is the point of the card.
    for (id in ctx.rotateHands(direction)) ctx.resolveBustAfterGain(id, finishedToo = true)
}

/**
 * A bottle spins on the table and stops on somebody. The server picks — four
 * clients rolling their own would show four different bottles.
 */
val ASSASSINATION = ActionCardDef(
    id = ASSASSINATION_ID,
    name = "assassination",
    description = "a spinning bottle picks a player at random — they bust",
    sigil = "⚱",
    targetRule = TargetRule.SELF,
    price = 40,
) { ctx, play ->
    if (play.phase == PHASE_OUTCOME) {
        // The bottle has stopped. Whoever it stopped on may have gone out while
        // it was still turning, and [Ctx.bust] is a no-op on a seat already out.
        ctx.bust(play.target.id, "assassination")
        return@ActionCardDef
    }

    // The drawer is in the running too, and under "double it!" the bottle is
    // spun twice: two spins, two victims. Nobody who already has a bottle
    // coming is in the running for the second one — the first has not busted
    // them yet, because neither bust happens until both bottles have been
    // watched, and two bottles stopping on the same seat is one wasted spin.
    val alreadyGoing = ctx.state.pendingOutcomes.map { it.targetId }.toSet()
    val victim = ctx.rng.pick(ctx.activePlayers().filterNot { it.id in alreadyGoing }) ?: return@ActionCardDef
    ctx.emit(GameEvent.BottleSpin(victim.id))
    ctx.land(ASSASSINATION_ID, play.from.id, victim.id)
}

/** The one card that reaches a player who is already finished with the round. */
val DONT_CARE = ActionCardDef(
    id = "dontCare",
    name = "don't care + ratio",
    description = "bust any player, even one who already went out",
    sigil = "⌁",
    targetRule = TargetRule.ANY_PLAYER,
    price = 40,
) { ctx, play ->
    ctx.bust(play.target.id, "ratio")
}

/** Marks a forced draw as coming from the slot machine. */
const val SLOTS_SOURCE = "slots"

/** Spin for one extra card. The draw itself runs through the normal forced-draw path. */
val SLOTS = ActionCardDef(
    id = "slots",
    name = "slots",
    description = "spin the slots for a random card",
    sigil = "🎰",
    targetRule = TargetRule.SELF,
    price = 15,
) { ctx, play ->
    // Peeking the top card is safe: the table is paused on this player's forced
    // draw, so nothing else can take it before the spin resolves.
    ctx.emit(GameEvent.Slots(play.target.id, ctx.state.deck.firstOrNull()))
    // Tagged so the room holds the card back while the reels spin.
    if (play.target.status == PlayerStatus.ACTIVE) {
        ctx.pushForcedDraws(play.target.id, 1, source = SLOTS_SOURCE)
    }
}

/**
 * The flip stops being reachable for the player who drew it, so the only thing
 * that can end their hand is a duplicate. Every card after this one is worth
 * keeping and worth nothing if the next one collides — which is the whole card.
 */
val JUST_ONE_MORE = ActionCardDef(
    id = "justOneMore",
    name = "just one more",
    description = "you can no longer flip out — the only way to stop is to go out",
    sigil = "∞",
    targetRule = TargetRule.SELF,
    skipHolding = NO_FLIP.id,
    price = 20,
) { ctx, play ->
    ctx.grantEffect(play.target.id, NO_FLIP.id)
}

/** What the bomb writes on its victim's bust. */
const val BUST_BOMBER = "taken down"

/**
 * Arms the drawer. Nothing happens until they bust — and then the table stops
 * and asks them who is going with them, long after the card itself was spent.
 *
 * The two halves are told apart by [Play.phase]: the card being played arms it,
 * the prompt it raised later sets it off.
 */
val SUICIDE_BOMBER = ActionCardDef(
    id = "suicideBomber",
    name = "suicide bomber",
    description = "when you bust, you take a player down with you",
    sigil = "☠",
    targetRule = TargetRule.SELF,
    skipHolding = BOMBER.id,
    price = 20,
) { ctx, play ->
    if (play.phase == PHASE_BUST) {
        ctx.bust(play.target.id, BUST_BOMBER)
    } else {
        ctx.grantEffect(play.from.id, BOMBER.id)
    }
}

/** The two ways to take a flip under "anti flip", as offered and sent back. */
const val ANTI_FLIP_KEEP = "bank it"
const val ANTI_FLIP_SPEND = "take it off someone"

/** Its own id, so the second half can raise a prompt against the first. */
const val ANTI_FLIP_ID = "antiFlip"

/**
 * Not a card — the "anti flip" house rule asking its question. Nothing is drawn
 * for it and no deck contains it, but the table stops for it exactly the way it
 * stops for a card, so it is written as one.
 *
 * Two prompts rather than one: a single prompt carrying both the choice and the
 * seats would ask for a seat even from a player who is about to say "bank it",
 * and there is no seat that answer belongs to.
 */
val ANTI_FLIP = ActionCardDef(
    id = ANTI_FLIP_ID,
    name = "anti flip",
    description = "bank the flip bonus, or take the same off another player",
    sigil = "⇅",
    targetRule = TargetRule.SELF,
    options = listOf(ANTI_FLIP_KEEP, ANTI_FLIP_SPEND),
    deckable = false,
) { ctx, play ->
    when (play.phase) {
        PHASE_FLIP_CHOICE -> if (play.choice == ANTI_FLIP_SPEND) {
            // Everyone else is on the hook, whatever became of their round —
            // the points come off the scoreboard, not off the hand.
            val victims = ctx.state.players.map { it.id }.filterNot { it == play.from.id }
            ctx.raisePrompt(ANTI_FLIP_ID, play.from.id, PHASE_FLIP_TARGET, victims)
        }

        PHASE_FLIP_TARGET -> {
            // Either/or: spending the bonus means giving it up, so the flip is
            // worth the hand alone and the victim is down the same again.
            ctx.adjust(play.from.id, -FLIP7_BONUS)
            ctx.adjust(play.target.id, -FLIP7_BONUS)
            ctx.emit(GameEvent.AntiFlip(play.from.id, play.target.id, FLIP7_BONUS))
        }
    }
}

/** The hand is worthless unless it goes all the way. */
val UNLUCKY_SEVEN = ActionCardDef(
    id = "unluckySeven",
    name = "unlucky 7",
    description = "target scores nothing this round unless they flip out",
    sigil = "7?",
    skipHolding = MUST_FLIP.id,
    price = 30,
) { ctx, play ->
    // No status check: a hand banked without the flip is exactly what this is
    // for under "extreme", and with the rule off no finished seat is ever
    // offered in the first place.
    ctx.grantEffect(play.target.id, MUST_FLIP.id)
}

// ═══════════════════════════════════════════════
// Buying a card
// ═══════════════════════════════════════════════

const val MUTATE_ID = "mutate"

/**
 * What a number card costs to buy: what it is worth, and five for the privilege
 * of choosing it. A card you pick is worth more than one you are dealt — it is
 * never a duplicate, and it is always the step you needed.
 */
fun priceOfNumber(value: Int): Int = value + 5

/** Names a number card the deck could deal. */
fun offerIdForNumber(label: String): String = "num:$label"

/** Names a modifier the deck could deal. */
fun offerIdForPassive(defId: String): String = "passive:$defId"

/**
 * Buy a card out of your own score.
 *
 * Only what the table's own deck holds is on sale — a friendly table cannot buy
 * an assassination that was never in it — and only what the buyer can actually
 * afford. The price comes off the round rather than off the scoreboard, so it
 * shows up on the summary as a line rather than as a number that quietly moved.
 *
 * Number cards and modifiers only. Buying an action card would mean playing one,
 * which is a different card and a prompt inside a prompt; a card you buy is one
 * you hold.
 */
val MUTATE = ActionCardDef(
    id = MUTATE_ID,
    name = "mutate",
    description = "buy a card out of this deck, and pay for it out of your score",
    sigil = "⟡",
    targetRule = TargetRule.SELF,
    price = 35,
) { ctx, play ->
    if (play.phase == PHASE_PLAY) {
        val offers = ctx.offersFor(play.from.id)
        if (offers.isEmpty()) {
            ctx.wasted(MUTATE_ID, play.from.id)
            return@ActionCardDef
        }
        ctx.raisePrompt(
            defId = MUTATE_ID,
            playerId = play.from.id,
            phase = PHASE_BUY,
            targets = listOf(play.from.id),
            kind = PickKind.CATALOG,
            offers = offers,
        )
        return@ActionCardDef
    }

    // Re-priced rather than trusted: what somebody could afford when they were
    // asked is not necessarily what they can afford now, and a pick that is no
    // longer on the list falls back to one that is.
    val offers = ctx.offersFor(play.from.id)
    val wanted = play.picked.firstOrNull()
    val bought = offers.firstOrNull { it.id == wanted } ?: offers.firstOrNull() ?: return@ActionCardDef
    ctx.buy(play.from.id, bought)
}

// ═══════════════════════════════════════════════
// Cards that ask the whole table
// ═══════════════════════════════════════════════

/** Their own ids, so the second half can raise a prompt against the first. */
const val COMEBACK_ID = "comeback"
const val ALL_IN_ID = "allIn"

/** How a comeback came out, carried from the throw to the settling. */
const val COMEBACK_WON = "won"
const val COMEBACK_LOST = "lost"

const val THROW_ROCK = "rock"
const val THROW_PAPER = "paper"
const val THROW_SCISSORS = "scissors"

private val BEATS = mapOf(
    THROW_ROCK to THROW_SCISSORS,
    THROW_PAPER to THROW_ROCK,
    THROW_SCISSORS to THROW_PAPER,
)

/** Whoever is furthest behind on the scoreboard, or null when it is shared. */
private fun outrightLast(state: GameState): String? = extremeOfScore(state, lowest = true)

/** Whoever is furthest ahead, or null when it is shared. */
private fun outrightLeader(state: GameState): String? = extremeOfScore(state, lowest = false)

/**
 * The one player at the top or the bottom of the scoreboard. Null on a tie:
 * nobody is *the* player in that spot, which is the same test the bounty uses.
 */
private fun extremeOfScore(state: GameState, lowest: Boolean): String? {
    if (state.players.size < 2) return null
    val ranked = state.players.sortedBy { if (lowest) it.score else -it.score }
    if (ranked[0].score == ranked[1].score) return null
    return ranked[0].id
}

/**
 * Only the player at the bottom of the scoreboard may use this, and only
 * against the one at the top: they throw at the same time, and winning trades
 * the two scores outright.
 *
 * Drawn by anybody else it fizzles and is replaced. The alternative — keeping
 * it out of the deck unless the trailing player is drawing — would make what is
 * in the deck depend on the scoreboard, and [Deck.build] is a pure function of
 * the config for a reason.
 */
val COMEBACK = ActionCardDef(
    id = COMEBACK_ID,
    name = "comeback",
    description = "last place only: throw against the leader, win and you trade scores",
    sigil = "⇄!",
    // Nothing is asked when it is drawn: whether it does anything at all
    // depends on the scoreboard, and asking a player to throw for a card that
    // is about to fizzle would be asking them for nothing.
    targetRule = TargetRule.SELF,
    price = 30,
) { ctx, play ->
    if (play.phase == PHASE_PLAY) {
        // Raised rather than resolved: the leader has to throw too, and neither
        // of them may see the other's hand first.
        val last = outrightLast(ctx.state)
        val leader = outrightLeader(ctx.state)
        if (last == null || leader == null || last != play.from.id) {
            ctx.wasted(COMEBACK_ID, play.from.id)
            return@ActionCardDef
        }
        ctx.raisePrompt(
            defId = COMEBACK_ID,
            playerId = last,
            phase = PHASE_THROW,
            targets = listOf(last),
            options = listOf(THROW_ROCK, THROW_PAPER, THROW_SCISSORS),
            responders = listOf(last, leader),
        )
        return@ActionCardDef
    }

    if (play.phase == PHASE_OUTCOME) {
        // Both throws have been turned over and read. Only now do the scores
        // move: watching your own total change while the hands are still being
        // shown is being told the answer over the top of the question.
        if (play.result == COMEBACK_WON) ctx.swapScores(play.from.id, play.target.id)
        return@ActionCardDef
    }

    val challenger = play.from.id
    val leader = play.answers.keys.firstOrNull { it != challenger } ?: return@ActionCardDef
    val mine = play.answers[challenger]?.choice ?: THROW_ROCK
    val theirs = play.answers[leader]?.choice ?: THROW_ROCK
    val won = BEATS[mine] == theirs
    ctx.emit(GameEvent.Throws(challenger, mine, leader, theirs, won))
    // A draw is a draw. Throwing again would need the table to remember how
    // many times it already had, and "you both threw rock" is a fine ending.
    ctx.land(COMEBACK_ID, challenger, leader, result = if (won) COMEBACK_WON else COMEBACK_LOST)
}

/** How much of the round the two ends of an "all in" keep. */
const val ALL_IN_MIN_BETTORS = 3

/**
 * Everybody with a hand bets one card of it, face down. The highest and the
 * lowest bet both score half the round; everyone else is untouched.
 *
 * The cards are only shown, never lost — a bet that changed hands would have to
 * be re-checked for duplicates on four seats at once, and the reveal is the
 * moment this card is for.
 */
val ALL_IN = ActionCardDef(
    id = ALL_IN_ID,
    name = "all in",
    description = "everyone bets a card face down — highest and lowest score half the round",
    sigil = "◎",
    // Drawn plainly; it is the prompt it raises that asks for cards.
    targetRule = TargetRule.SELF,
    price = 25,
) { ctx, play ->
    if (play.phase == PHASE_PLAY) {
        val bettors = ctx.state.players.filter { it.status == PlayerStatus.ACTIVE && it.hand.isNotEmpty() }
        // With two bettors the same player would be both the highest and the
        // lowest, which is a rule that reads as broken however it is resolved.
        if (bettors.size < ALL_IN_MIN_BETTORS) {
            ctx.wasted(ALL_IN_ID, play.from.id)
            return@ActionCardDef
        }
        ctx.raisePrompt(
            defId = ALL_IN_ID,
            playerId = play.from.id,
            phase = PHASE_BET,
            targets = listOf(play.from.id),
            responders = bettors.map { it.id },
            kind = PickKind.CARD,
            cards = bettors.flatMap { p -> p.hand.map { it.id } },
        )
        return@ActionCardDef
    }

    if (play.phase == PHASE_OUTCOME) {
        // The bets have been turned over and read; now they are paid for. The
        // card lands on the two ends of the table after the reveal rather than
        // underneath it — a modifier arriving while everybody is still reading
        // the cards is a modifier nobody saw arrive.
        for (loser in play.targets) ctx.grantEffect(loser.id, HALVED.id)
        return@ActionCardDef
    }

    // Every bet, resolved back to the card it names. A pick that is not the
    // player's own to bet — a clock that ran out, a hand that changed under
    // them — falls back to a card that is, so nobody is left out of the
    // reckoning for not having answered tidily.
    val bets = play.answers.mapNotNull { (playerId, answer) ->
        val hand = ctx.player(playerId)?.hand.orEmpty()
        if (hand.isEmpty()) return@mapNotNull null
        val bet = hand.firstOrNull { it.id == answer.cards.firstOrNull() } ?: hand.first()
        playerId to bet
    }.toMap()
    if (bets.size < ALL_IN_MIN_BETTORS) return@ActionCardDef

    val high = bets.values.maxOf { it.value }
    val low = bets.values.minOf { it.value }
    // Everyone tied at either end pays: nobody is spared for having company.
    val paying = bets.filterValues { it.value == high || it.value == low }.keys
    ctx.emit(GameEvent.AllIn(bets.mapValues { it.value }, paying.toList()))
    ctx.land(ALL_IN_ID, play.from.id, play.from.id, targets = paying.toList())
}

// ═══════════════════════════════════════════════
// Cards that go round the table
// ═══════════════════════════════════════════════

/** Their own ids, so the second half can raise a prompt against the first. */
const val CIRCLEJERK_ID = "circlejerk"
const val REVERSE_CIRCLEJERK_ID = "reverseCirclejerk"

/**
 * The seats either side of [playerId], in the order the turn goes round: the
 * one on their left first.
 *
 * "Left" is the next seat in the player list, which is the next seat to play
 * and the first of the `others` the client seats round its arc — so the order
 * this returns is the order the table reads from that player outwards, and a
 * card handed out in it lands where everybody watching expects.
 *
 * At a table of two the two neighbours are the same person, and there is one of
 * them. Whatever became of a neighbour's round they are still a neighbour: a
 * banked hand is points somebody can be given a duplicate for, and a busted one
 * is somewhere to put a card nobody wants — see [TargetRule.reachesFinished],
 * which is the same argument about the same kind of card.
 */
internal fun neighboursOf(state: GameState, playerId: String): List<String> {
    val players = state.players
    val seat = players.indexOfFirst { it.id == playerId }
    if (seat < 0 || players.size < 2) return emptyList()
    val left = players[(seat + 1) % players.size].id
    val right = players[(seat + players.size - 1) % players.size].id
    return if (left == right) listOf(left) else listOf(left, right)
}

/** Everything [playerId] has in front of them, hand and modifier row alike. */
private fun holdings(state: GameState, playerId: String): List<Card> =
    state.player(playerId)?.let { it.hand + it.passives }.orEmpty()

/**
 * One of your cards to each of the players either side of you, and you say
 * which.
 *
 * A giving card, which is the thing this deck did not have: everything else
 * that moves a card between seats either takes one or trades one, so the only
 * way to be rid of a discordia was to make somebody agree to the swap or to
 * spin the whole table. This hands two of them out at once and asks nothing in
 * return, which makes it the most generous card in the game and the nastiest,
 * depending entirely on what you choose to be generous with.
 *
 * The picks go round in the order [neighboursOf] returns them — the seat on
 * your left first — because the wire carries a list of cards and not a list of
 * pairs, and a card that asked "and who gets this one?" for every pick would be
 * two prompts to answer one question. The description says which way it goes,
 * so the choice is still a choice.
 */
val CIRCLEJERK = ActionCardDef(
    id = CIRCLEJERK_ID,
    name = "circlejerk",
    description = "give the players either side of you one of your cards each — you pick, left first",
    sigil = "↤↦",
    // Nothing is aimed: the neighbours are whoever is sitting there, and the
    // only decision is which cards leave. So it resolves on its drawer and the
    // prompt it raises does the asking.
    targetRule = TargetRule.SELF,
    price = 20,
) { ctx, play ->
    if (play.phase == PHASE_PLAY) {
        val neighbours = neighboursOf(ctx.state, play.from.id)
        val mine = holdings(ctx.state, play.from.id)
        // Nothing to give, or nobody to give it to — the opening deal, mostly.
        if (neighbours.isEmpty() || mine.isEmpty()) {
            ctx.wasted(CIRCLEJERK_ID, play.from.id)
            return@ActionCardDef
        }
        ctx.raisePrompt(
            defId = CIRCLEJERK_ID,
            playerId = play.from.id,
            phase = PHASE_GIVE,
            targets = listOf(play.from.id),
            kind = PickKind.CARD,
            cards = mine.map { it.id },
            // One card per neighbour, or as many as are actually in front of
            // you: a player holding a single card gives that one card away
            // rather than being told the whole thing fizzled.
            picks = minOf(neighbours.size, mine.size),
            // Every pick is off the same seat — the drawer's own — which is the
            // one card in the game the usual rule would refuse outright.
            oneCardPerSeat = false,
        )
        return@ActionCardDef
    }

    val neighbours = neighboursOf(ctx.state, play.from.id)
    val holding = holdings(ctx.state, play.from.id).map { it.id }
    // Re-read rather than trusted, and short answers filled in from what is
    // actually in front of them — the same contract `legalPicks` keeps for the
    // cards that go through it.
    val given = (play.picked.filter { it in holding } + holding).distinct().take(neighbours.size)
    for ((index, cardId) in given.withIndex()) ctx.handOver(play.from.id, neighbours[index], cardId)
    // A neighbour who is already out can be handed a duplicate, and a banked
    // hand holding two of the same card is a bust however quietly it came by
    // them. That is most of the reason to play this on the seat next to you.
    for (id in neighbours) ctx.resolveBustAfterGain(id, finishedToo = true)
}

/**
 * ...and the same card pointing the other way: a card from each neighbour, and
 * *they* say which.
 *
 * Which is what makes it a different card rather than the same one in reverse.
 * You are taking two cards you did not choose from two people who would rather
 * be rid of something, so the hand you end up with is the hand they decided you
 * should have — and it is your own hand that has to survive both of them
 * arriving at once.
 *
 * Both neighbours are asked together, the way an "all in" asks the table: an
 * answer given in reply to somebody else's is not the same decision.
 */
val REVERSE_CIRCLEJERK = ActionCardDef(
    id = REVERSE_CIRCLEJERK_ID,
    name = "reverse circlejerk",
    description = "the players either side of you each hand you a card — they pick which",
    sigil = "↦↤",
    targetRule = TargetRule.SELF,
    price = 25,
) { ctx, play ->
    if (play.phase == PHASE_PLAY) {
        val givers = neighboursOf(ctx.state, play.from.id)
            .filter { holdings(ctx.state, it).isNotEmpty() }
        if (givers.isEmpty()) {
            ctx.wasted(REVERSE_CIRCLEJERK_ID, play.from.id)
            return@ActionCardDef
        }
        ctx.raisePrompt(
            defId = REVERSE_CIRCLEJERK_ID,
            playerId = play.from.id,
            phase = PHASE_HANDOVER,
            targets = listOf(play.from.id),
            responders = givers,
            kind = PickKind.CARD,
            // Everybody's, because the wire carries one list — each giver is
            // only offered their own, which is the client's reading of a prompt
            // that asks more than one player at once.
            cards = givers.flatMap { id -> holdings(ctx.state, id).map { it.id } },
        )
        return@ActionCardDef
    }

    for (giverId in neighboursOf(ctx.state, play.from.id)) {
        val holding = holdings(ctx.state, giverId)
        if (holding.isEmpty()) continue
        // A pick that is not theirs to give — a clock that ran out, a hand that
        // changed under them — falls back to one that is, so nobody is left out
        // of it for not having answered tidily.
        val wanted = play.answers[giverId]?.cards?.firstOrNull()
        val card = holding.firstOrNull { it.id == wanted } ?: holding.first()
        ctx.handOver(giverId, play.from.id, card.id)
    }
    // Two cards arriving at once, from two people who chose them. Checked after
    // both have landed rather than between them: one bust, whichever of them
    // did it.
    ctx.resolveBustAfterGain(play.from.id)
}

// ═══════════════════════════════════════════════
// Passive cards
// ═══════════════════════════════════════════════

/** Flip 7's "Second Chance". */
val SECOND_LIFE = PassiveCardDef(
    id = "secondLife",
    name = "second life",
    description = "survive one duplicate card",
    sigil = "♡",
    accent = "#a3566a",
    seal = SealShape.SCALLOP,
    price = 25,
)

val ARMOR = PassiveCardDef(
    id = "armor",
    name = "armor",
    description = "blocks the next strike against you",
    sigil = "◇",
    accent = "#4a6b82",
    seal = SealShape.SHIELD,
    price = 15,
)

/** Flip 7's "×2" — doubles the number-card total only. */
val DOUBLE_POINTS = PassiveCardDef(
    id = "doublePoints",
    name = "double points",
    description = "double your number cards",
    sigil = "×2",
    scoring = PassiveScoring.DOUBLE_NUMBERS,
    accent = "#8a6a2f",
    seal = SealShape.HEXAGON,
    price = 30,
)

/**
 * Your hand is yours to know. Nobody else at the table may look at it for as
 * long as you are still in the round.
 *
 * The one card in the classic game that changes what a *viewer* is told rather
 * than what the engine does, which is why the rule it needs is a single
 * predicate — `Engine.handIsHidden` — read by the projection, by the map of
 * what each hand is worth, and by `Room.redactFor`. Everything else about it is
 * an ordinary modifier: it is dealt, it lies in the row in front of you, and it
 * can be stolen, swapped or spun away, at which point the hand it was hiding is
 * face up again and somebody else's is not.
 *
 * It scores nothing on purpose. What it is worth is that nobody can count how
 * close you are to the flip, or read whether the card that just landed on you
 * was the one that busted you — which is worth more in the last round of a game
 * than any bonus on this list.
 *
 * The card itself stays face up. A hand hidden for no visible reason is a bug
 * as far as anybody watching is concerned; hidden by something they can see and
 * could take off you is a card.
 */
val REDACTED = PassiveCardDef(
    id = "redacted",
    name = "redacted",
    description = "nobody but you can see your hand while you are still in the round",
    sigil = "▬",
    accent = "#3a4249",
    seal = SealShape.SHIELD,
    price = 25,
)

/** What being aimed at costs the player carrying [DISCORDIA]. */
const val DISCORDIA_TOLL = 10

/**
 * The card nobody wants and everybody can give away.
 *
 * Whoever is holding it pays for being interesting: play a freeze, a strike, a
 * skip — anything at all — on the seat carrying discordia and [DISCORDIA_TOLL]
 * points come off them and land on whoever played it. So it is worth attacking
 * its holder, and it is worth not being its holder, and the way out is to trade
 * it to somebody else. It is dealt from the deck like any other card, which is
 * what makes getting rid of it a move rather than a wish.
 */
val DISCORDIA = PassiveCardDef(
    id = "discordia",
    name = "discordia",
    description = "anyone who plays an action card on you takes $DISCORDIA_TOLL points off you",
    sigil = "☍",
    accent = SOUR,
    seal = SealShape.SPIKE,
    price = 0,
    spite = DISCORDIA_TOLL,
)

/**
 * Every number card in front of you counts the wrong way, you may not stop, and
 * busting is not a way out either.
 *
 * The three halves are one card. A hand that is worth minus something is a hand
 * you would put down at once, so the card does not let you — and a bust that
 * wiped the debt would hand the stop back under another name: draw until the
 * duplicate comes and walk away owing nothing, which is a *better* round than
 * the one the card was pushing you into. So the hole stays, and it deepens with
 * every card you are made to take.
 *
 * What is left is to run all the way to the flip and hope the bonus covers it,
 * or to find somebody to trade it to — which is the move the card is really
 * asking for, and why it is dealt into decks that can move a modifier. Being
 * *sent* out is the other way: a freeze still works, and is worth playing on a
 * friend for once.
 *
 * It lifts the floor under its own holder, "extreme" or not — see
 * `Engine.enterRoundEnd`. A card that says a 13 is worth minus thirteen has to
 * mean it, and every other table rounds a bad round up to nothing.
 */
val ANTIMATTER = PassiveCardDef(
    id = "antimatter",
    name = "antimatter",
    description = "your number cards count against you. you cannot go out, and busting will not save you",
    sigil = "∓",
    scoring = PassiveScoring.NEGATE,
    accent = SOUR,
    seal = SealShape.SPIKE,
    price = 0,
    allowsStaying = false,
)

/**
 * The bonus cards stay one family: the house green and the plain round stamp,
 * every one of them. What separates a +2 from a +10 is how hard it was struck,
 * which the client reads off [PassiveCardDef.bonusPoints] — five colours here
 * would break up the one group on the table that should read as a group.
 */
private fun plus(n: Int) = PassiveCardDef(
    id = "plus$n",
    name = "+$n",
    description = "+$n bonus points",
    sigil = "+$n",
    bonusPoints = n,
    scoring = PassiveScoring.FLAT,
    // What it pays, plus a little: buying points outright should never be the
    // cheapest way to have them.
    price = n + 5,
)

val PLUS_TWO = plus(2)
val PLUS_FOUR = plus(4)
val PLUS_SIX = plus(6)
val PLUS_EIGHT = plus(8)
val PLUS_TEN = plus(10)

// ═══════════════════════════════════════════════
// Catalog
// ═══════════════════════════════════════════════

const val AUCTION_ID = "auction"

/**
 * Not a card, but the table stops for it exactly the way it stops for one.
 *
 * Written the way [ANTI_FLIP] is — `deckable = false`, so no deck may hold it —
 * and registered among the actions so that `Engine.resolveOutcome` finds it when
 * the auction's reveal has been watched and the money has to move. It only ever
 * runs in [PHASE_OUTCOME]; there is no "playing" it.
 *
 * The price is read back off `Interlude.sale` rather than stuffed into
 * `PendingOutcome.result`, which is a field for tokens — a coin's face, a
 * winner's name — and a number in it would be the one stringly-typed thing in
 * the engine.
 */
val AUCTION = ActionCardDef(
    id = AUCTION_ID,
    name = "sold!",
    description = "the highest sealed bid takes the lot",
    sigil = "⚑",
    targetRule = TargetRule.SELF,
    deckable = false,
    price = 0,
) { ctx, play ->
    if (play.phase != PHASE_OUTCOME) return@ActionCardDef
    val sale = ctx.state.interlude?.sale ?: return@ActionCardDef
    val winner = sale.winnerId ?: return@ActionCardDef
    ctx.bank(winner, -sale.price)
    ctx.update(winner) { it.copy(gamblers = it.gamblers + sale.card) }
    ctx.emit(GameEvent.Bought(winner, sale.card, sale.price, hidden = true))
}

object Catalog {
    val actions: Map<String, ActionCardDef> = listOf(
        FREEZE, DRAW_THREE, STRIKE, STEAL, HEX, SWAP, SWAP_CARDS, SLOTS,
        COIN_FLIP, SPIN_TABLE, ASSASSINATION, DONT_CARE,
        JUST_ONE_MORE, UNLUCKY_SEVEN, SUICIDE_BOMBER, ANTI_FLIP,
        COMEBACK, ALL_IN, CIRCLEJERK, REVERSE_CIRCLEJERK, MUTATE, AUCTION,
    ).associateBy { it.id }

    val passives: Map<String, PassiveCardDef> = listOf(
        SECOND_LIFE, ARMOR, DOUBLE_POINTS, DISCORDIA, ANTIMATTER, REDACTED,
        PLUS_TWO, PLUS_FOUR, PLUS_SIX, PLUS_EIGHT, PLUS_TEN,
        // The effect cards. Never dealt — minted by whatever causes them — but
        // they are cards on the table like any other, so the client has to be
        // able to draw a face for them.
        NO_FLIP, MUST_FLIP, HALVED, BOMBER,
        REDIRECT_ARMED, DRAW_TWO_ARMED, SECOND_OPINION_ARMED,
        DOUBLE_DOWN_ARMED, TAXES_ARMED, ALREADY_DOWN_ARMED, COOLER, FORESIGHT,
    ).associateBy { it.id }

    /**
     * The gambler cards, for rolling rules — see [GamblerCatalog].
     *
     * Held here as well so that everything which resolves a `Card.defId` has one
     * place to look. Their ids must not collide with an action's or a passive's:
     * the testing panel, the stacked deck and every card on the table are named
     * by a bare id across all three catalogs, so two cards sharing one would
     * silently be the same card in some places and not others. `CatalogTest`
     * holds that line.
     */
    val gamblers: Map<String, GamblerCardDef> = GamblerCatalog.byId

    /** Only the cards a deck may actually contain — see [ActionCardDef.deckable]. */
    val deckableActions: List<ActionCardDef> = actions.values.filter { it.deckable }

    fun action(id: String?): ActionCardDef? = id?.let { actions[it] }

    fun passive(id: String?): PassiveCardDef? = id?.let { passives[it] }

    fun gambler(id: String?): GamblerCardDef? = id?.let { gamblers[it] }
}
