package com.letitride.engine

/**
 * Optional house rules the host toggles before starting. They are plain data
 * rather than callbacks so a config can be serialised as a list of ids and the
 * engine stays the only place that knows what a rule *does*.
 */
data class LobbyRule(
    val id: String,
    val name: String,
    val description: String,
    /** Bust when the hand total goes above this. */
    val bustThreshold: Int? = null,
    /** Cards drawn per voluntary hit. */
    val drawsPerTurn: Int = 1,
    /** Lets a player go out before drawing anything. */
    val allowStayWithEmptyHand: Boolean = false,
    /** How many times an action card's effect fires. */
    val actionRepeat: Int = 1,
    /** Every action card resolves on whoever drew it. */
    val forceSelfTarget: Boolean = false,
    /** Passive cards you draw are handed to a random other player. */
    val passivesToRandomOther: Boolean = false,
)

object LobbyRules {
    val BLACKJACKING = LobbyRule(
        id = "blackjacking",
        name = "blackjacking",
        description = "also bust if your hand goes over 21",
        bustThreshold = 21,
    )

    val DOUBLE_IT = LobbyRule(
        id = "doubleIt",
        name = "double it!",
        description = "every action card resolves twice",
        actionRepeat = 2,
    )

    val WOMP_WOMP = LobbyRule(
        id = "wompWomp",
        name = "womp womp",
        description = "action cards hit you instead — and passives go to someone else",
        forceSelfTarget = true,
        passivesToRandomOther = true,
    )

    val DOUBLE_DRAW = LobbyRule(
        id = "doubleDraw",
        name = "double draw",
        description = "draw 2 cards per turn instead of 1",
        drawsPerTurn = 2,
    )

    val NO_FORCED_FIRST = LobbyRule(
        id = "noForcedFirst",
        name = "no forced draw",
        description = "you may go out before drawing anything",
        allowStayWithEmptyHand = true,
    )

    val all: List<LobbyRule> = listOf(BLACKJACKING, DOUBLE_IT, WOMP_WOMP, DOUBLE_DRAW, NO_FORCED_FIRST)

    private val byId = all.associateBy { it.id }

    fun resolve(ids: List<String>): List<LobbyRule> = ids.mapNotNull { byId[it] }
}

/** The active rule set collapsed into the handful of values the engine reads. */
class RuleSet(val rules: List<LobbyRule>) {
    val bustThreshold: Int? = rules.mapNotNull { it.bustThreshold }.minOrNull()
    val drawsPerTurn: Int = rules.maxOfOrNull { it.drawsPerTurn } ?: 1
    val allowStayWithEmptyHand: Boolean = rules.any { it.allowStayWithEmptyHand }
    val actionRepeat: Int = rules.maxOfOrNull { it.actionRepeat } ?: 1
    val forceSelfTarget: Boolean = rules.any { it.forceSelfTarget }
    val passivesToRandomOther: Boolean = rules.any { it.passivesToRandomOther }

    companion object {
        fun of(config: GameConfig) = RuleSet(LobbyRules.resolve(config.ruleIds))
    }
}
