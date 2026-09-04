package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The house rule that turns flipping out into a decision. It is the first thing
 * that stops the table *after* the round is over — the scoring simply waits,
 * because nothing moves while a prompt is open.
 */
class AntiFlipTest {

    private val rules = listOf(LobbyRules.ANTI_FLIP.id)

    /** Puts [id] one card short of the flip, then hands them the seventh. */
    private fun aboutToFlip(players: List<String> = listOf("a", "b", "c")): GameState {
        val dealt = startedAndDealt(
            config = config(rules = rules),
            players = players,
            rest = listOf(num(7, id = "seventh")),
        )
        return dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") {
                    val hand = (1..6).map { v -> num(v) }
                    it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
                } else {
                    it
                }
            },
        )
    }

    @Test
    fun `flipping out stops the table for a decision instead of scoring`() {
        val result = tr(aboutToFlip(), GameAction.Hit("a"))

        assertTrue(result.events.any { it is GameEvent.Flip7 })
        assertEquals(GamePhase.PLAYING, result.state.phase, "the round must not score until it is answered")

        val pending = result.state.pendingAction
        assertNotNull(pending)
        assertEquals(PHASE_FLIP_CHOICE, pending.phase)
        assertEquals("a", pending.playerId)
        assertEquals(listOf(ANTI_FLIP_KEEP, ANTI_FLIP_SPEND), pending.options)
        assertEquals(listOf("a"), pending.validTargets, "there is no seat in this half of it")
    }

    @Test
    fun `banking it scores the round exactly as it always did`() {
        var state = t(aboutToFlip(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_KEEP))

        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(28 + FLIP7_BONUS, state.roundDeltas["a"])
        assertTrue(state.roundAdjustments.isEmpty(), "banking it moves no points about")
    }

    @Test
    fun `spending it asks who pays`() {
        var state = t(aboutToFlip(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_SPEND))

        assertEquals(GamePhase.PLAYING, state.phase, "still not scored")
        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PHASE_FLIP_TARGET, pending.phase)
        assertEquals(setOf("b", "c"), pending.validTargets.toSet(), "anyone but the flipper")
        assertTrue(pending.options.isEmpty(), "this half only wants a seat")
    }

    @Test
    fun `the bonus is given up and taken off the seat that was picked`() {
        var state = t(aboutToFlip(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_SPEND))
        val result = tr(state, GameAction.PlayAction("a", "b", ANTI_FLIP_ID))
        state = result.state

        assertEquals(GamePhase.ROUND_END, state.phase)
        // The flipper keeps the hand and gives up the bonus: 28, not 43.
        assertEquals(28, state.roundDeltas["a"])
        assertTrue(result.events.any { it is GameEvent.AntiFlip && it.targetPlayerId == "b" })

        val announced = result.events.filterIsInstance<GameEvent.AntiFlip>().single()
        assertEquals(FLIP7_BONUS, announced.points)
    }

    @Test
    fun `a hand worth less than the bonus stops at nothing, not below it`() {
        // b is holding a 4 and is about to be docked 15. Without "extreme" the
        // round can cost them everything they made and no more.
        var state = aboutToFlip()
        state = state.copy(
            players = state.players.map {
                if (it.id == "b") it.copy(hand = listOf(num(4)), handValue = 4) else it
            },
        )
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_SPEND))
        state = t(state, GameAction.PlayAction("a", "b", ANTI_FLIP_ID))

        assertEquals(0, state.roundDeltas["b"])
        assertEquals(-FLIP7_BONUS, state.roundAdjustments["b"], "the deduction itself is recorded in full")
        assertEquals(0, state.player("b")!!.score)
    }

    @Test
    fun `the deduction reaches a seat that busted`() {
        var state = aboutToFlip()
        state = state.copy(
            players = state.players.map {
                if (it.id == "c") it.copy(status = PlayerStatus.BUST, bustReason = "duplicate") else it
            },
        )
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_SPEND))

        assertTrue("c" in state.pendingAction!!.validTargets, "the points come off the scoreboard, not the hand")
    }

    @Test
    fun `a clock that runs out answers both halves on its own`() {
        var state = t(aboutToFlip(), GameAction.Hit("a"))
        var guard = 0
        while (state.pendingAction != null && guard++ < 4) {
            state = t(state, GameAction.Timeout(state.pendingAction!!.playerId))
        }

        assertNull(state.pendingAction, "the table was left stopped")
        assertEquals(GamePhase.ROUND_END, state.phase)
    }

    @Test
    fun `the rule does nothing when it is switched off`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(7, id = "seventh")))
        val state = dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") {
                    val hand = (1..6).map { v -> num(v) }
                    it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
                } else {
                    it
                }
            },
        )

        val after = t(state, GameAction.Hit("a"))

        assertNull(after.pendingAction)
        assertEquals(GamePhase.ROUND_END, after.phase)
        assertEquals(28 + FLIP7_BONUS, after.roundDeltas["a"])
    }

    @Test
    fun `adjustments are wiped with the rest of the round`() {
        var state = t(aboutToFlip(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ANTI_FLIP_ID, choice = ANTI_FLIP_SPEND))
        state = t(state, GameAction.PlayAction("a", "b", ANTI_FLIP_ID))
        assertTrue(state.roundAdjustments.isNotEmpty())

        state = t(state, GameAction.NextRound)

        assertTrue(state.roundAdjustments.isEmpty())
    }

    @Test
    fun `it is not a card and no deck may contain it`() {
        assertFalse(ANTI_FLIP.deckable)
        assertTrue(Catalog.deckableActions.none { it.id == ANTI_FLIP_ID })
        for (preset in DeckPresets.all) {
            assertFalse(ANTI_FLIP_ID in preset.deck.actionCards, "${preset.id} deals a card that is not one")
        }
    }
}
