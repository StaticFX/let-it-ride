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
}

/** Who a card is allowed to be pointed at. */
enum class TargetRule {
    /** Anyone still in the round, including the player who drew it. */
    ANY_ACTIVE,

    /** Anyone still in the round who actually has cards to lose. */
    ACTIVE_WITH_CARDS,

    /** Someone else, still in the round, who actually has cards. */
    OTHER_ACTIVE_WITH_CARDS,

    /**
     * Anyone at the table at all — busted, gone out, or still playing. The only
     * rule that reaches a seat which is already finished with the round.
     */
    ANY_PLAYER,

    /** Resolves on the drawer; no picker is shown. */
    SELF,
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
    fun validTargets(state: GameState, fromId: String): List<String> {
        // "Extreme": a card that takes something away reaches a seat that is
        // already out, because what it takes is points and those are still on
        // the board. The rules are read off the state rather than passed in —
        // a game knows what it is being played under, and threading a rule set
        // through every call site would only carry the same answer by hand.
        val active =
            if (RuleSet.of(state.config).reachesFinished) state.players
            else state.players.filter { it.status == PlayerStatus.ACTIVE }
        val byRule = when (targetRule) {
            TargetRule.SELF -> listOf(fromId)
            TargetRule.ANY_ACTIVE -> active.map { it.id }
            TargetRule.ACTIVE_WITH_CARDS -> active.filter { it.hand.isNotEmpty() }.map { it.id }
            TargetRule.OTHER_ACTIVE_WITH_CARDS ->
                active.filter { it.id != fromId && it.hand.isNotEmpty() }.map { it.id }

            TargetRule.ANY_PLAYER -> state.players.map { it.id }
        }
        val held = skipHolding ?: return byRule
        return byRule.filterNot { id ->
            state.player(id)?.passives?.any { it.defId == held } == true
        }
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
)

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
    description = "target loses their highest card",
    sigil = "✗",
    targetRule = TargetRule.ACTIVE_WITH_CARDS,
    price = 15,
) { ctx, play ->
    val fresh = ctx.player(play.target.id) ?: return@ActionCardDef
    // A banked hand is still worth points, so under "extreme" striking one is
    // the whole idea. Whether this seat may be aimed at at all was settled by
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
    description = "take a random card from target",
    sigil = "◈",
    targetRule = TargetRule.OTHER_ACTIVE_WITH_CARDS,
    price = 20,
) { ctx, play ->
    if (play.from.id == play.target.id) return@ActionCardDef
    val card = ctx.stealRandom(play.target.id, play.from.id)
    // The stolen card can duplicate something the thief already holds.
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
    targetRule = TargetRule.OTHER_ACTIVE_WITH_CARDS,
    price = 20,
) { ctx, play ->
    if (play.from.id == play.target.id) return@ActionCardDef
    // Whole rows move, so no duplicate can appear — but "blackjacking" caps the
    // total, and under "extreme" the hand coming back can be a busted one.
    for (id in ctx.swapHands(play.from.id, play.target.id)) {
        ctx.resolveBustAfterGain(id, finishedToo = true)
    }
}

/**
 * Every card lying face up in front of somebody still in the round — hands and
 * modifiers alike. What [SWAP_CARDS] is allowed to reach for.
 */
private fun cardsOnTable(state: GameState): List<Card> {
    // Under "extreme" a hand that has already been banked is still on the table
    // as far as this card is concerned — see [ActionCardDef.validTargets].
    val holders =
        if (RuleSet.of(state.config).reachesFinished) state.players
        else state.players.filter { it.status == PlayerStatus.ACTIVE }
    return holders.flatMap { it.hand + it.passives }
}

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
    for (id in ctx.swapCards(first.id, second.id)) ctx.resolveBustAfterGain(id)
}

/** The two faces of the coin, as offered to the drawer and sent back. */
const val COIN_HEADS = "heads"
const val COIN_TAILS = "tails"

/**
 * Call it in the air: a correct call is worth a ×2, a wrong one busts you.
 * Replaces double-or-nothing, which flipped the same coin without asking.
 */
val COIN_FLIP = ActionCardDef(
    id = "coinFlip",
    name = "coin flip",
    description = "call heads or tails: right doubles your cards, wrong busts you",
    sigil = "⌾",
    targetRule = TargetRule.SELF,
    options = listOf(COIN_HEADS, COIN_TAILS),
    price = 15,
) { ctx, play ->
    // Under "double it!" the second flip is for a player who may already be
    // out; a coin is not thrown for someone who is no longer in the round.
    val target = play.target
    if (target.status == PlayerStatus.ACTIVE) {
        val call = play.choice ?: COIN_HEADS
        val landed = if (ctx.rng.nextBoolean()) COIN_HEADS else COIN_TAILS
        // Announced before the consequence so the coin can land on the called
        // face first and the payout or the bust follows it.
        ctx.emit(GameEvent.CoinFlip(target.id, call, landed))
        if (call == landed) {
            ctx.grantEphemeralPassive(target.id, DOUBLE_POINTS.id)
        } else {
            ctx.bust(target.id, "coin flip")
        }
    }
}

/** Which way the table turns, as offered to the drawer and sent back. */
const val SPIN_LEFT = "left"
const val SPIN_RIGHT = "right"

/**
 * Every hand at the table slides one seat the way the drawer called — busted
 * seats and banked ones included. See [Ctx.rotateHands].
 */
val SPIN_TABLE = ActionCardDef(
    id = "spinTable",
    name = "spin the table",
    description = "every hand at the table slides one seat left or right — busted ones too",
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
    id = "assassination",
    name = "assassination",
    description = "a spinning bottle picks a player at random — they bust",
    sigil = "⚱",
    targetRule = TargetRule.SELF,
    price = 40,
) { ctx, _ ->
    // The drawer is in the running too, and under "double it!" the bottle is
    // spun twice: two spins, two victims.
    val victim = ctx.rng.pick(ctx.activePlayers()) ?: return@ActionCardDef
    ctx.emit(GameEvent.BottleSpin(victim.id))
    ctx.bust(victim.id, "assassination")
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

    val challenger = play.from.id
    val leader = play.answers.keys.firstOrNull { it != challenger } ?: return@ActionCardDef
    val mine = play.answers[challenger]?.choice ?: THROW_ROCK
    val theirs = play.answers[leader]?.choice ?: THROW_ROCK
    val won = BEATS[mine] == theirs
    ctx.emit(GameEvent.Throws(challenger, mine, leader, theirs, won))
    // A draw is a draw. Throwing again would need the table to remember how
    // many times it already had, and "you both threw rock" is a fine ending.
    if (won) ctx.swapScores(challenger, leader)
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
    for (id in paying) ctx.grantEffect(id, HALVED.id)
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

object Catalog {
    val actions: Map<String, ActionCardDef> = listOf(
        FREEZE, DRAW_THREE, STRIKE, STEAL, HEX, SWAP, SWAP_CARDS, SLOTS,
        COIN_FLIP, SPIN_TABLE, ASSASSINATION, DONT_CARE,
        JUST_ONE_MORE, UNLUCKY_SEVEN, SUICIDE_BOMBER, ANTI_FLIP,
        COMEBACK, ALL_IN, MUTATE,
    ).associateBy { it.id }

    val passives: Map<String, PassiveCardDef> = listOf(
        SECOND_LIFE, ARMOR, DOUBLE_POINTS, DISCORDIA,
        PLUS_TWO, PLUS_FOUR, PLUS_SIX, PLUS_EIGHT, PLUS_TEN,
        // The effect cards. Never dealt — minted by whatever causes them — but
        // they are cards on the table like any other, so the client has to be
        // able to draw a face for them.
        NO_FLIP, MUST_FLIP, HALVED, BOMBER,
    ).associateBy { it.id }

    /** Only the cards a deck may actually contain — see [ActionCardDef.deckable]. */
    val deckableActions: List<ActionCardDef> = actions.values.filter { it.deckable }

    fun action(id: String?): ActionCardDef? = id?.let { actions[it] }

    fun passive(id: String?): PassiveCardDef? = id?.let { passives[it] }
}
