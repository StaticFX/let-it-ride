package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Marks — the per-player, per-round effects that are not cards — and the two
 * cards that hand them out.
 */
class MarkTest {

    /** Puts [id] one card short of the flip with a known, gapless hand. */
    private fun sixInHand(state: GameState, id: String): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) {
                val hand = (1..6).map { v -> num(v) }
                it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
            } else {
                it
            }
        },
    )

    private fun withMark(state: GameState, id: String, markId: String): GameState = state.copy(
        players = state.players.map { if (it.id == id) it.copy(marks = it.marks + markId) else it },
    )

    // ─── just one more ───

    @Test
    fun `just one more resolves on its drawer without asking for a target`() {
        val dealt = startedAndDealt(rest = listOf(action(JUST_ONE_MORE.id)))
        val result = tr(dealt, GameAction.Hit("a"))

        assertNull(result.state.pendingAction, "a self-targeting card never parks the table")
        assertTrue(NO_FLIP.id in result.state.player("a")!!.marks)
        assertTrue(result.events.any { it is GameEvent.Marked && it.playerId == "a" })
    }

    @Test
    fun `a marked player draws straight past the flip target`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(num(7, id = "seventh")))
        val state = withMark(sixInHand(dealt, "a"), "a", NO_FLIP.id)

        val result = tr(state, GameAction.Hit("a"))

        assertFalse(result.events.any { it is GameEvent.Flip7 })
        assertNull(result.state.flip7PlayerId)
        assertEquals(GamePhase.PLAYING, result.state.phase)
        assertEquals(7, result.state.hand("a").size, "the hand keeps growing past the target")
        assertEquals(PlayerStatus.ACTIVE, result.state.status("a"))
    }

    @Test
    fun `a marked player past the target still busts on a duplicate`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(num(7, id = "seventh"), num(3, id = "dup")))
        var state = withMark(sixInHand(dealt, "a"), "a", NO_FLIP.id)

        state = t(state, GameAction.Hit("a"))
        assertEquals(PlayerStatus.ACTIVE, state.status("a"))
        // Round the table and back: the eighth card collides with the 3.
        state = t(state.copy(turnIndex = 0), GameAction.Hit("a"))

        assertEquals(PlayerStatus.BUST, state.status("a"))
    }

    @Test
    fun `the mark only stops the flip for the player who carries it`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(num(7, id = "seventh")))
        val state = withMark(sixInHand(dealt, "b"), "a", NO_FLIP.id)

        // b is not marked, so b flipping still ends the round for everyone.
        val result = tr(state.copy(turnIndex = 1), GameAction.Hit("b"))

        assertEquals("b", result.state.flip7PlayerId)
        assertEquals(GamePhase.ROUND_END, result.state.phase)
    }

    @Test
    fun `drawing just one more twice fizzles instead of being spent for nothing`() {
        val dealt = startedAndDealt(rest = listOf(action(JUST_ONE_MORE.id, id = "second"), num(9)))
        val state = withMark(dealt, "a", NO_FLIP.id)

        val result = tr(state, GameAction.Hit("a"))

        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == JUST_ONE_MORE.id })
        assertNotNull(result.state.forcedDraws, "the drawer is owed a replacement card")
        assertTrue(result.state.discard.any { it.id == "second" })
    }

    // ─── unlucky 7 ───

    @Test
    fun `unlucky 7 marks the seat it is pointed at`() {
        val dealt = startedAndDealt(rest = listOf(action(UNLUCKY_SEVEN.id)))
        var state = t(dealt, GameAction.Hit("a"))
        assertEquals(UNLUCKY_SEVEN.id, state.pendingAction?.cardDefId)

        state = t(state, GameAction.PlayAction("a", "b", UNLUCKY_SEVEN.id))

        assertTrue(MUST_FLIP.id in state.player("b")!!.marks)
        assertFalse(MUST_FLIP.id in state.player("a")!!.marks)
    }

    @Test
    fun `a marked hand that goes out scores nothing`() {
        val player = Player(id = "a", name = "a", hand = listOf(num(10), num(5)), marks = setOf(MUST_FLIP.id))
        assertEquals(0, Engine.roundScore(player, flip7PlayerId = null))
        assertEquals(15, Engine.roundScore(player.copy(marks = emptySet()), flip7PlayerId = null))
    }

    @Test
    fun `a marked hand that flips out is paid in full`() {
        val hand = (1..7).map { num(it) }
        val player = Player(id = "a", name = "a", hand = hand, marks = setOf(MUST_FLIP.id))
        assertEquals(28 + FLIP7_BONUS, Engine.roundScore(player, flip7PlayerId = "a"))
    }

    @Test
    fun `unlucky 7 is not offered a seat that already carries it`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(action(UNLUCKY_SEVEN.id)))
        val state = t(withMark(dealt, "b", MUST_FLIP.id), GameAction.Hit("a"))

        val targets = state.pendingAction?.validTargets
        assertNotNull(targets)
        assertFalse("b" in targets, "b is already under it — pointing at them does nothing")
        assertTrue("a" in targets && "c" in targets)
    }

    @Test
    fun `unlucky 7 fizzles when the whole table already carries it`() {
        var dealt = startedAndDealt(rest = listOf(action(UNLUCKY_SEVEN.id), num(9)))
        dealt = withMark(withMark(dealt, "a", MUST_FLIP.id), "b", MUST_FLIP.id)

        val result = tr(dealt, GameAction.Hit("a"))

        assertNull(result.state.pendingAction)
        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == UNLUCKY_SEVEN.id })
    }

    // ─── lifetime ───

    @Test
    fun `marks are wiped when the next round is dealt`() {
        var state = startedAndDealt(openingCards = listOf(num(4), num(6)))
        state = withMark(state, "a", MUST_FLIP.id)
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(0, state.roundDeltas["a"], "the mark held for the round it was given in")

        state = t(state, GameAction.NextRound)

        assertTrue(state.players.all { it.marks.isEmpty() })
    }

    @Test
    fun `double it does not double a mark into an error`() {
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.DOUBLE_IT.id)),
            rest = listOf(action(JUST_ONE_MORE.id)),
        )
        val result = tr(dealt, GameAction.Hit("a"))

        assertEquals(setOf(NO_FLIP.id), result.state.player("a")!!.marks)
        assertEquals(1, result.events.count { it is GameEvent.Marked }, "the second application announces nothing")
    }
}
