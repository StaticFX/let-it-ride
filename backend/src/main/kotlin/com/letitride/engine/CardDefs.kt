package com.letitride.engine

/** How a passive card contributes to the round score. */
enum class PassiveScoring {
    /** Adds [PassiveCardDef.bonusPoints] after the number cards are totalled. */
    FLAT,

    /** Doubles the number-card total (Flip 7's ×2). Applied before flat bonuses. */
    DOUBLE_NUMBERS,

    /** Scores nothing — it is a protection card. */
    NONE,
}

/** Who a card is allowed to be pointed at. */
enum class TargetRule {
    /** Anyone still in the round, including the player who drew it. */
    ANY_ACTIVE,

    /** Anyone still in the round who actually has cards to lose. */
    ACTIVE_WITH_CARDS,

    /** Someone else, still in the round, who actually has cards. */
    OTHER_ACTIVE_WITH_CARDS,

    /** Resolves on the drawer; no picker is shown. */
    SELF,
}

data class ActionCardDef(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val targetRule: TargetRule = TargetRule.ANY_ACTIVE,
    val onPlay: (Ctx, Player, Player) -> Unit,
) {
    val selfTarget: Boolean get() = targetRule == TargetRule.SELF

    /**
     * Everyone this card could meaningfully be played on right now. An empty
     * list means the card cannot do anything and should not be sitting there
     * waiting for a pick it will never get.
     */
    fun validTargets(state: GameState, fromId: String): List<String> {
        val active = state.players.filter { it.status == PlayerStatus.ACTIVE }
        return when (targetRule) {
            TargetRule.SELF -> listOf(fromId)
            TargetRule.ANY_ACTIVE -> active.map { it.id }
            TargetRule.ACTIVE_WITH_CARDS -> active.filter { it.hand.isNotEmpty() }.map { it.id }
            TargetRule.OTHER_ACTIVE_WITH_CARDS ->
                active.filter { it.id != fromId && it.hand.isNotEmpty() }.map { it.id }
        }
    }
}

data class PassiveCardDef(
    val id: String,
    val name: String,
    val description: String,
    val sigil: String,
    val bonusPoints: Int = 0,
    val scoring: PassiveScoring = PassiveScoring.NONE,
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
) { ctx, _, target ->
    if (target.status == PlayerStatus.ACTIVE) {
        ctx.update(target.id) { it.copy(status = PlayerStatus.STAYED) }
        ctx.emit(GameEvent.Freeze(target.id))
    }
}

/** Flip 7's "Flip Three": the target immediately draws three cards. */
val DRAW_THREE = ActionCardDef(
    id = "drawThree",
    name = "draw 3!",
    description = "force a player to draw 3 cards",
    sigil = "3↓",
) { ctx, _, target ->
    if (target.status == PlayerStatus.ACTIVE) ctx.pushForcedDraws(target.id, 3)
}

val STRIKE = ActionCardDef(
    id = "strike",
    name = "strike",
    description = "target loses their highest card",
    sigil = "✗",
    targetRule = TargetRule.ACTIVE_WITH_CARDS,
) { ctx, _, target ->
    val fresh = ctx.player(target.id) ?: return@ActionCardDef
    if (fresh.status != PlayerStatus.ACTIVE || fresh.hand.isEmpty()) return@ActionCardDef
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
) { ctx, from, target ->
    if (from.id == target.id) return@ActionCardDef
    val card = ctx.stealRandom(target.id, from.id)
    // The stolen card can duplicate something the thief already holds.
    if (card != null) ctx.resolveBustAfterGain(from.id)
}

val HEX = ActionCardDef(
    id = "hex",
    name = "hex",
    description = "target skips their next turn",
    sigil = "⌥",
) { ctx, _, target ->
    if (target.status == PlayerStatus.ACTIVE) ctx.skip(target.id)
}

val SWAP = ActionCardDef(
    id = "swap",
    name = "swap",
    description = "swap your hand with another player",
    sigil = "⇄",
    targetRule = TargetRule.OTHER_ACTIVE_WITH_CARDS,
) { ctx, from, target ->
    if (from.id == target.id) return@ActionCardDef
    ctx.swapHands(from.id, target.id)
}

/** 50/50: double your round score, or bust out of the round entirely. */
val DOUBLE_OR_NOTHING = ActionCardDef(
    id = "doubleOrNothing",
    name = "double or nothing",
    description = "50/50: double your points or bust",
    sigil = "⚂",
    targetRule = TargetRule.SELF,
) { ctx, _, target ->
    if (ctx.rng.nextBoolean()) {
        ctx.grantEphemeralPassive(target.id, DOUBLE_POINTS.id)
        ctx.emit(GameEvent.DoubleOrNothing(target.id, won = true))
    } else {
        ctx.bust(target.id, "double or nothing")
        ctx.emit(GameEvent.DoubleOrNothing(target.id, won = false))
    }
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
) { ctx, _, target ->
    // Peeking the top card is safe: the table is paused on this player's forced
    // draw, so nothing else can take it before the spin resolves.
    ctx.emit(GameEvent.Slots(target.id, ctx.state.deck.firstOrNull()))
    // Tagged so the room holds the card back while the reels spin.
    if (target.status == PlayerStatus.ACTIVE) ctx.pushForcedDraws(target.id, 1, source = SLOTS_SOURCE)
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
)

val ARMOR = PassiveCardDef(
    id = "armor",
    name = "armor",
    description = "blocks the next strike against you",
    sigil = "◇",
)

/** Flip 7's "×2" — doubles the number-card total only. */
val DOUBLE_POINTS = PassiveCardDef(
    id = "doublePoints",
    name = "double points",
    description = "double your number cards",
    sigil = "×2",
    scoring = PassiveScoring.DOUBLE_NUMBERS,
)

val BOUNTY = PassiveCardDef(
    id = "bounty",
    name = "bounty",
    description = "+10 points at round end",
    sigil = "✦",
    bonusPoints = 10,
    scoring = PassiveScoring.FLAT,
)

private fun plus(n: Int) = PassiveCardDef(
    id = "plus$n",
    name = "+$n",
    description = "+$n bonus points",
    sigil = "+$n",
    bonusPoints = n,
    scoring = PassiveScoring.FLAT,
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
        FREEZE, DRAW_THREE, STRIKE, STEAL, HEX, SWAP, DOUBLE_OR_NOTHING, SLOTS,
    ).associateBy { it.id }

    val passives: Map<String, PassiveCardDef> = listOf(
        SECOND_LIFE, ARMOR, DOUBLE_POINTS, BOUNTY,
        PLUS_TWO, PLUS_FOUR, PLUS_SIX, PLUS_EIGHT, PLUS_TEN,
    ).associateBy { it.id }

    fun action(id: String?): ActionCardDef? = id?.let { actions[it] }

    fun passive(id: String?): PassiveCardDef? = id?.let { passives[it] }
}
