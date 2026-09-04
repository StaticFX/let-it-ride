package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.COIN_FLIP
import com.letitride.engine.Engine
import com.letitride.engine.FREEZE
import com.letitride.engine.GameAction
import com.letitride.engine.ALL_IN_ID
import com.letitride.engine.PickKind
import com.letitride.engine.Rng
import com.letitride.engine.SWAP_CARDS
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
    fun `a card that asks a question carries its options on the wire`() {
        val view = t(
            startedAndDealt(
                players = listOf("a", "b", "c"),
                openingCards = listOf(num(1), num(2), num(3)),
                rest = listOf(action(COIN_FLIP.id)),
            ),
            GameAction.Hit("a"),
        ).toView("ABCD", "a", null)

        val pending = view.pendingAction
        assertTrue(pending != null, "a coin flip has to stop the table for a call")
        assertEquals(listOf("heads", "tails"), pending.options)
        assertEquals(listOf("a"), pending.validTargets, "nobody but the drawer is involved")

        val payload = appJson.encodeToString(ServerMessage.serializer(), ServerMessage.State(view, emptyList()))
        assertTrue("\"options\":[\"heads\",\"tails\"]" in payload, "wire payload was: $payload")

        val decoded = appJson.decodeFromString(ServerMessage.serializer(), payload) as ServerMessage.State
        assertEquals(listOf("heads", "tails"), decoded.state.pendingAction?.options)
    }

    @Test
    fun `a card that only wants a target sends an empty option list`() {
        val view = freezePending().toView("ABCD", "a", null)
        assertEquals(emptyList(), view.pendingAction?.options)
        val payload = appJson.encodeToString(ServerMessage.serializer(), ServerMessage.State(view, emptyList()))
        assertTrue("\"options\":[]" in payload, "wire payload was: $payload")
    }

    @Test
    fun `a play carrying a choice decodes, and so does one without`() {
        val withChoice = appJson.decodeFromString(
            ClientMessage.serializer(),
            """{"type":"PLAY_ACTION","targetPlayerId":"a","cardDefId":"coinFlip","choice":"tails"}""",
        ) as ClientMessage.PlayAction
        assertEquals("tails", withChoice.choice)

        // The field is optional on purpose: an older client still plays cards.
        val without = appJson.decodeFromString(
            ClientMessage.serializer(),
            """{"type":"PLAY_ACTION","targetPlayerId":"a","cardDefId":"freeze"}""",
        ) as ClientMessage.PlayAction
        assertEquals(null, without.choice)
    }

    @Test
    fun `a card that points at cards says so, and names them`() {
        val dealt = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(2)),
            rest = listOf(action(SWAP_CARDS.id)),
        )
        val view = t(dealt, GameAction.Hit("a")).toView("ABCD", "a", null)

        val pending = view.pendingAction
        assertTrue(pending != null, "the table has to stop for the cards to be picked")
        assertEquals(PickKind.CARD, pending.kind)
        assertEquals(2, pending.picks)
        assertEquals(setOf("n-1-1", "n-2-2"), pending.validCards.toSet())

        val payload = appJson.encodeToString(ServerMessage.serializer(), ServerMessage.State(view, emptyList()))
        assertTrue("\"kind\":\"card\"" in payload, "wire payload was: $payload")
        val decoded = appJson.decodeFromString(ServerMessage.serializer(), payload) as ServerMessage.State
        assertEquals(PickKind.CARD, decoded.state.pendingAction?.kind)
        assertEquals(2, decoded.state.pendingAction?.picks)
    }

    @Test
    fun `a card-picking play decodes, with and without the cards`() {
        val withCards = appJson.decodeFromString(
            ClientMessage.serializer(),
            """{"type":"PLAY_ACTION","targetPlayerId":"a","cardDefId":"swapCards","cards":["x","y"]}""",
        ) as ClientMessage.PlayAction
        assertEquals(listOf("x", "y"), withCards.cards)

        val without = appJson.decodeFromString(
            ClientMessage.serializer(),
            """{"type":"PLAY_ACTION","targetPlayerId":"a","cardDefId":"freeze"}""",
        ) as ClientMessage.PlayAction
        assertEquals(emptyList(), without.cards)
    }

    @Test
    fun `a simultaneous prompt sends who has answered, never what they said`() {
        val dealt = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(2, id = "a-2"), num(9, id = "b-9"), num(5, id = "c-5")),
            rest = listOf(action(ALL_IN_ID)),
        )
        var state = t(dealt, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", ALL_IN_ID, cards = listOf("a-2")))

        val view = state.toView("ABCD", "a", null)
        val pending = view.pendingAction
        assertTrue(pending != null, "the table is waiting on the rest of the bets")
        assertEquals(listOf("a", "b", "c"), pending.responders)
        assertEquals(listOf("a"), pending.answered)

        val payload = appJson.encodeToString(ServerMessage.serializer(), ServerMessage.State(view, emptyList()))
        assertTrue("\"answered\":[\"a\"]" in payload, "wire payload was: $payload")
        // The bets themselves stay on the server until every one is in, which
        // is what makes the prompt secret without any per-viewer filtering.
        assertTrue("\"answers\"" !in payload, "an answer reached the wire: $payload")
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
