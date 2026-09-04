package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Extreme" lifts two floors at once: a card that takes something away may be
 * aimed at a seat that is already out, and a round may leave a player worse off
 * than it found them.
 */
class ExtremeTest {

    private val extreme = listOf(LobbyRules.EXTREME.id)

    private fun withStatus(state: GameState, id: String, status: PlayerStatus): GameState = state.copy(
        players = state.players.map { if (it.id == id) it.copy(status = status) else it },
    )

    // ─── Reaching a seat that is already out ───

    @Test
    fun `a strike reaches a hand that has already been banked`() {
        val dealt = startedAndDealt(
            config = config(rules = extreme),
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(9)),
            rest = listOf(action(STRIKE.id)),
        )
        var state = withStatus(dealt, "b", PlayerStatus.STAYED)
        state = t(state, GameAction.Hit("a"))

        assertTrue("b" in state.pendingAction!!.validTargets, "a banked hand is still worth points")

        state = t(state, GameAction.PlayAction("a", "b", STRIKE.id))
        assertTrue(state.hand("b").isEmpty(), "the banked card was struck off")
        assertEquals(PlayerStatus.STAYED, state.status("b"), "striking does not put them back in the round")
    }

    @Test
    fun `without the rule the same strike cannot see them at all`() {
        val dealt = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(9), num(5)),
            rest = listOf(action(STRIKE.id)),
        )
        var state = withStatus(dealt, "b", PlayerStatus.STAYED)
        state = t(state, GameAction.Hit("a"))

        assertFalse("b" in state.pendingAction!!.validTargets)
    }

    @Test
    fun `unlucky 7 can be hung on a hand that is already banked`() {
        val dealt = startedAndDealt(
            config = config(rules = extreme),
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(9)),
            rest = listOf(action(UNLUCKY_SEVEN.id)),
        )
        var state = withStatus(dealt, "b", PlayerStatus.STAYED)
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", UNLUCKY_SEVEN.id))

        assertTrue(MUST_FLIP.id in state.player("b")!!.marks)
        assertEquals(0, Engine.roundScore(state.player("b")!!, flip7PlayerId = null))
    }

    @Test
    fun `cards held by a seat that is out can be swapped for`() {
        val dealt = startedAndDealt(
            config = config(rules = extreme),
            players = listOf("a", "b"),
            openingCards = listOf(num(1, id = "a-1"), num(9, id = "b-9")),
            rest = listOf(action(SWAP_CARDS.id)),
        )
        var state = withStatus(dealt, "b", PlayerStatus.STAYED)
        state = t(state, GameAction.Hit("a"))

        assertEquals(setOf("a-1", "b-9"), state.pendingAction!!.validCards.toSet())

        state = t(state, GameAction.PlayAction("a", "a", SWAP_CARDS.id, cards = listOf("a-1", "b-9")))
        assertEquals(listOf("b-9"), state.hand("a").map { it.id })
        assertEquals(listOf("a-1"), state.hand("b").map { it.id })
    }

    @Test
    fun `freezing somebody who is already out still does nothing`() {
        // The rule widens who may be aimed at; it does not make a card that
        // stops a player mean anything against one who has already stopped.
        val dealt = startedAndDealt(
            config = config(rules = extreme),
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(9)),
            rest = listOf(action(FREEZE.id)),
        )
        var state = withStatus(dealt, "b", PlayerStatus.BUST)
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))

        assertEquals(PlayerStatus.BUST, state.status("b"), "a bust must not be undone by a freeze")
    }

    // ─── Going below zero ───

    @Test
    fun `a round can leave a player worse off than it found them`() {
        val player = Player(id = "a", name = "a", hand = listOf(num(4)), score = 10)
        val state = Engine.newGame(config(rules = extreme, deck = DeckPresets.PURE.deck))
            .copy(
                phase = GamePhase.PLAYING,
                players = listOf(player, Player(id = "b", name = "b", hand = listOf(num(5)), score = 10)),
                roundAdjustments = mapOf("a" to -20),
            )

        var after = t(state, GameAction.Stay("a"))
        after = t(after, GameAction.Stay("b"))

        assertEquals(GamePhase.ROUND_END, after.phase)
        assertEquals(-16, after.roundDeltas["a"], "4 made, 20 taken")
        assertEquals(-6, after.player("a")!!.score)
    }

    @Test
    fun `without the rule the same round stops at nothing`() {
        val player = Player(id = "a", name = "a", hand = listOf(num(4)), score = 10)
        val state = Engine.newGame(config(deck = DeckPresets.PURE.deck))
            .copy(
                phase = GamePhase.PLAYING,
                players = listOf(player, Player(id = "b", name = "b", hand = listOf(num(5)), score = 10)),
                roundAdjustments = mapOf("a" to -20),
            )

        var after = t(state, GameAction.Stay("a"))
        after = t(after, GameAction.Stay("b"))

        assertEquals(0, after.roundDeltas["a"])
        assertEquals(10, after.player("a")!!.score)
    }

    @Test
    fun `the game is still won by whoever is highest, negatives and all`() {
        val state = Engine.newGame(
            config(rules = extreme, deck = DeckPresets.PURE.deck, winCondition = WinCondition.ROUNDS, totalRounds = 1),
        ).copy(
            phase = GamePhase.PLAYING,
            round = 1,
            players = listOf(
                Player(id = "a", name = "a", hand = listOf(num(1)), score = -30),
                Player(id = "b", name = "b", hand = listOf(num(2)), score = -5),
            ),
        )

        var after = t(state, GameAction.Stay("a"))
        after = t(after, GameAction.Stay("b"))

        assertEquals("b", after.gameWinnerId, "least bad is still best")
    }

    @Test
    fun `anti flip can put its victim in the red once extreme is on`() {
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.ANTI_FLIP.id, LobbyRules.EXTREME.id)),
            players = listOf("a", "b", "c"),
            rest = listOf(num(7, id = "seventh")),
        )
        var state = dealt.copy(
            players = dealt.players.map {
                when (it.id) {
                    "a" -> {
                        val hand = (1..6).map { v -> num(v) }
                        it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
                    }
                    "b" -> it.copy(hand = listOf(num(4)), handValue = 4)
                    else -> it
                }
            },
        )

        state = t(state, GameAction.Hit("a"))
        assertNotNull(state.pendingAction)
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_SPEND))
        state = t(state, GameAction.PlayAction("a", "b", ANTI_FLIP_ID))

        assertEquals(-11, state.roundDeltas["b"], "4 made, 15 taken")
    }
}
