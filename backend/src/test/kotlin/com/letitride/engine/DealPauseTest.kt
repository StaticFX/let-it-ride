package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins down what the opening deal does when it turns up an action card. */
class DealPauseTest {

    @Test
    fun `an action card dealt in the opening stops the deal until it is played`() {
        var state = started(
            players = listOf("a", "b", "c"),
            deck = listOf(action(FREEZE.id), num(2), num(3), num(9)),
        )
        assertEquals(listOf("a", "b", "c"), state.dealQueue)

        state = t(state, GameAction.DealTo("a"))
        assertEquals(FREEZE.id, state.pendingAction?.cardDefId, "the deal should be waiting on a target")
        assertEquals(listOf("b", "c"), state.dealQueue)

        // Nothing else may be dealt while the card is unresolved.
        val blocked = t(state, GameAction.DealTo("b"))
        assertEquals(state, blocked, "b must not be dealt to while a is still picking")
    }

    @Test
    fun `a steal with nothing to steal is replaced instead of wasted`() {
        val state = started(deck = listOf(action(STEAL.id), num(2), num(9)))
        val result = tr(state, GameAction.DealTo("a"))

        // Nobody holds a card yet, so the steal cannot be pointed at anyone.
        assertNull(result.state.pendingAction, "no pick should be asked for")
        assertTrue(result.events.any { it is GameEvent.Fizzled })
        assertTrue(result.state.discard.any { it.defId == STEAL.id })

        // The drawer gets a replacement card rather than an empty opening.
        assertEquals(ForcedDraws("a", 1), result.state.forcedDraws)
        val after = t(result.state, GameAction.ForcedDraw)
        assertEquals(1, after.hand("a").size)
    }

    @Test
    fun `a strike with nothing to hit is replaced instead of wasted`() {
        val result = tr(started(deck = listOf(action(STRIKE.id), num(2), num(9))), GameAction.DealTo("a"))
        assertNull(result.state.pendingAction)
        assertTrue(result.events.any { it is GameEvent.Fizzled })
    }

    @Test
    fun `steal only offers players who actually hold cards`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(STEAL.id)),
        )
        // c has gone out, b is still in with a card.
        state = state.copy(
            players = state.players.map { if (it.id == "c") it.copy(status = PlayerStatus.STAYED) else it },
        )
        state = t(state, GameAction.Hit("a"))
        assertEquals(listOf("b"), state.pendingAction?.validTargets)
    }

    @Test
    fun `freeze may be pointed at anyone still in the round, including yourself`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(FREEZE.id)),
        )
        state = t(state, GameAction.Hit("a"))
        assertEquals(listOf("a", "b", "c"), state.pendingAction?.validTargets)
    }

    @Test
    fun `an illegal pick is snapped to a legal one`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(STEAL.id)),
        )
        state = state.copy(
            players = state.players.map { if (it.id == "c") it.copy(hand = emptyList(), handValue = 0) else it },
        )
        state = t(state, GameAction.Hit("a"))
        assertEquals(listOf("b"), state.pendingAction?.validTargets)

        // Asking for c — who has nothing — must not silently do nothing.
        val after = t(state, GameAction.PlayAction("a", "c", STEAL.id))
        assertEquals(2, after.hand("a").size, "the steal still landed, on the only legal target")
        assertEquals(0, after.hand("b").size)
    }
}
