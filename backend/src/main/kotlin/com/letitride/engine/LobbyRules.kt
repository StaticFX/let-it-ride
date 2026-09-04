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
    /** How many unique number cards trigger the flip bonus. */
    val flipTarget: Int? = null,
    /** Hitting [flipTarget] takes the whole game, not just the round. */
    val flipWinsGame: Boolean = false,
    /** Points every other player collects when the outright leader busts. */
    val bountyPoints: Int = 0,
    /** Flipping out becomes a choice: keep the bonus, or take it off somebody. */
    val antiFlip: Boolean = false,
    /**
     * Cards that take something away may be aimed at a seat that is already
     * out, and a round may cost a player more than they made — so a score can
     * go below zero.
     */
    val extreme: Boolean = false,
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

    val FLIP_9 = LobbyRule(
        id = "flip9",
        name = "flip 9",
        description = "the first to 9 different cards wins the game outright",
        flipTarget = 9,
        flipWinsGame = true,
    )

    val BOUNTY = LobbyRule(
        id = "bounty",
        name = "bounty",
        description = "when the player in the lead busts, everyone else collects 10 points",
        bountyPoints = 10,
    )

    val ANTI_FLIP = LobbyRule(
        id = "antiFlip",
        name = "anti flip",
        description = "flip out and you choose: bank the bonus, or take it off somebody else",
        antiFlip = true,
    )

    val EXTREME = LobbyRule(
        id = "extreme",
        name = "extreme",
        description = "nothing is safe once you are out, and scores can go below zero",
        extreme = true,
    )

    val all: List<LobbyRule> = listOf(
        BLACKJACKING, DOUBLE_IT, WOMP_WOMP, DOUBLE_DRAW, NO_FORCED_FIRST,
        FLIP_9, BOUNTY, ANTI_FLIP, EXTREME,
    )

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
    val flipTarget: Int = rules.mapNotNull { it.flipTarget }.maxOrNull() ?: FLIP7_TARGET
    val flipWinsGame: Boolean = rules.any { it.flipWinsGame }
    val bountyPoints: Int = rules.maxOfOrNull { it.bountyPoints } ?: 0
    val antiFlip: Boolean = rules.any { it.antiFlip }

    /** Whether a card that takes something away may reach a seat already out. */
    val reachesFinished: Boolean = rules.any { it.extreme }

    /** Whether a round may leave a player worse off than they started it. */
    val allowsNegative: Boolean = rules.any { it.extreme }

    companion object {
        fun of(config: GameConfig) = RuleSet(LobbyRules.resolve(config.ruleIds))
    }
}
