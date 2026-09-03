package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.Engine
import com.letitride.engine.FREEZE
import com.letitride.engine.GameAction
import com.letitride.engine.Rng
import com.letitride.engine.action
import com.letitride.engine.num
import com.letitride.engine.startedAndDealt
import com.letitride.engine.t
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker is driven entirely by what comes down the wire, so this checks the
 * serialised shape rather than the engine's own types.
 */
class PendingActionViewTest {

    private fun freezePending() = t(
        startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(FREEZE.id)),
        ),
        GameAction.Hit("a"),
    )

    @Test
    fun `a freeze offers every player still in the round`() {
        val view = freezePending().toView("ABCD", "a", null)
        val pending = view.pendingAction
        assertTrue(pending != null, "there should be a card waiting on a target")
        assertEquals(FREEZE.id, pending.cardDefId)
        assertEquals(listOf("a", "b", "c"), pending.validTargets)
    }

    @Test
    fun `the serialised payload actually carries validTargets and cardId`() {
        val view = freezePending().toView("ABCD", "a", null)
        val payload = appJson.encodeToString(
            ServerMessage.serializer(),
            ServerMessage.State(view, emptyList()),
        )

        assertTrue("\"validTargets\":[\"a\",\"b\",\"c\"]" in payload, "wire payload was: $payload")
        assertTrue("\"cardId\":" in payload, "wire payload was: $payload")
    }

    @Test
    fun `a card waiting on a target survives a round trip`() {
        val view = freezePending().toView("ABCD", "a", null)
        val payload = appJson.encodeToString(ServerMessage.serializer(), ServerMessage.State(view, emptyList()))
        val decoded = appJson.decodeFromString(ServerMessage.serializer(), payload) as ServerMessage.State

        assertEquals(listOf("a", "b", "c"), decoded.state.pendingAction?.validTargets)
    }

    @Test
    fun `each copy of a card gets its own id`() {
        // Two freezes back to back for the same player: the ids must differ, or
        // the client cannot tell the second card from the first and treats it as
        // already played.
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(FREEZE.id, id = "freeze-1"), action(FREEZE.id, id = "freeze-2")),
        )
        state = t(state, GameAction.Hit("a"))
        val first = state.toView("ABCD", "a", null).pendingAction?.cardId
        assertEquals("freeze-1", first)

        // a freezes b, the turn comes round to c, who turns up the second one.
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = t(state, GameAction.Hit("c"))
        val second = state.toView("ABCD", "c", null).pendingAction?.cardId

        assertEquals("freeze-2", second)
        assertTrue(first != second, "two copies of a card must not share an id")
    }

    @Test
    fun `a bot can always find a target for a freeze`() {
        val state = freezePending()
        val pending = state.pendingAction!!
        val candidates = pending.validTargets.mapNotNull { state.player(it) }
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it.id != pending.playerId }, "somebody other than the drawer should be pickable")
    }

    @Test
    fun `the engine accepts every target it advertised`() {
        val state = freezePending()
        val pending = state.pendingAction!!
        for (target in pending.validTargets) {
            val after = Engine.transition(
                state,
                GameAction.PlayAction(pending.playerId, target, pending.cardDefId),
                Rng(1),
            ).state
            assertEquals(
                com.letitride.engine.PlayerStatus.STAYED,
                after.player(target)!!.status,
                "advertised target $target was not actually frozen",
            )
        }
    }
}
