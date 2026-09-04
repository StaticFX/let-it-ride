package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first prompt raised outside a card being played: the card arms its
 * drawer, and the table stops much later, when they bust, to ask who is going
 * with them.
 */
class SuicideBomberTest {

    /** Hands [id] a bomb, the way the card that arms them would. */
    private fun armed(state: GameState, id: String): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) it.copy(passives = it.passives + passive(BOMBER.id, id = "tmp-bomber-$id")) else it
        },
    )

    private fun armedStill(state: GameState, id: String): Boolean =
        state.player(id)!!.passives.any { it.defId == BOMBER.id }

    private fun handOf(state: GameState, id: String, cards: List<Card>): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) it.copy(hand = cards, handValue = cards.sumOf { c -> c.value }) else it
        },
    )

    @Test
    fun `the card arms its drawer and nothing else`() {
        val dealt = startedAndDealt(rest = listOf(action(SUICIDE_BOMBER.id)))
        val result = tr(dealt, GameAction.Hit("a"))

        assertTrue(armedStill(result.state, "a"))
        assertNull(result.state.pendingAction, "arming asks nothing")
        assertEquals(PlayerStatus.ACTIVE, result.state.status("a"))
        assertEquals(PlayerStatus.ACTIVE, result.state.status("b"))
    }

    @Test
    fun `busting stops the table and asks who is coming along`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(4, id = "dup")))
        var state = armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a")

        state = t(state, GameAction.Hit("a"))

        assertEquals(PlayerStatus.BUST, state.status("a"))
        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(SUICIDE_BOMBER.id, pending.cardDefId)
        assertEquals("a", pending.playerId, "the bomber picks, even though they are out")
        assertEquals(PHASE_BUST, pending.phase)
        assertEquals(setOf("b", "c"), pending.validTargets.toSet())
    }

    @Test
    fun `the seat that is picked busts too`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(4, id = "dup")))
        var state = armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a")
        state = t(state, GameAction.Hit("a"))

        state = t(state, GameAction.PlayAction("a", "c", SUICIDE_BOMBER.id))

        assertEquals(PlayerStatus.BUST, state.status("c"))
        assertEquals(BUST_BOMBER, state.player("c")!!.bustReason)
        assertEquals(PlayerStatus.ACTIVE, state.status("b"), "only the one seat goes")
        assertNull(state.pendingAction)
    }

    @Test
    fun `the bomb is spent when it fires`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(4, id = "dup")))
        var state = armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a")
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", SUICIDE_BOMBER.id))

        assertFalse(armedStill(state, "a"))
    }

    @Test
    fun `a bomber with nobody left to take goes quietly`() {
        val dealt = startedAndDealt(rest = listOf(num(4, id = "dup")))
        var state = armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a")
        // b is already out, so there is no seat to point at.
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it },
        )

        state = t(state, GameAction.Hit("a"))

        assertEquals(PlayerStatus.BUST, state.status("a"))
        assertNull(state.pendingAction, "there was nobody to ask about")
        assertEquals(GamePhase.ROUND_END, state.phase, "the round closes as it would have anyway")
    }

    @Test
    fun `a bomber taken out by a bomb gets a pick of their own`() {
        // a and b are both carrying one. a busts and takes b — and b, now out
        // themselves, is asked the same question in turn rather than the chain
        // resolving itself behind their back.
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(4, id = "dup")))
        var state = armed(armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a"), "b")

        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", SUICIDE_BOMBER.id))

        assertEquals(PlayerStatus.BUST, state.status("a"))
        assertEquals(PlayerStatus.BUST, state.status("b"))
        assertEquals(PlayerStatus.ACTIVE, state.status("c"), "c is being pointed at, not yet taken")

        val second = state.pendingAction
        assertNotNull(second)
        assertEquals("b", second.playerId, "the second bomber picks for themselves")
        assertEquals(listOf("c"), second.validTargets)

        state = t(state, GameAction.PlayAction("b", "c", SUICIDE_BOMBER.id))
        assertEquals(PlayerStatus.BUST, state.status("c"))
        assertNull(state.pendingAction, "the chain ran out rather than looping")
        assertTrue(state.players.none { p -> p.passives.any { it.defId == BOMBER.id } })
    }

    @Test
    fun `a bomb with no table left to stop picks for itself`() {
        // "Double it!" spins the bottle twice inside one play, so the second
        // victim busts while the first one's prompt is already open. Their bomb
        // cannot stop the table again, so it takes somebody without asking —
        // and the whole armed table goes down rather than the bomb being lost.
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.DOUBLE_IT.id)),
            players = listOf("a", "b", "c", "d"),
            rest = listOf(action(ASSASSINATION.id)),
        )
        var state = dealt
        for (id in listOf("a", "b", "c", "d")) state = armed(state, id)

        state = t(state, GameAction.Hit("a"))

        assertTrue(state.players.all { it.status == PlayerStatus.BUST }, "a bomb went missing")
        assertTrue(
            state.players.none { p -> p.passives.any { it.defId == BOMBER.id } },
            "every bomb should have been spent",
        )
    }

    @Test
    fun `a clock that runs out still takes somebody`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(4, id = "dup")))
        var state = armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a")
        state = t(state, GameAction.Hit("a"))
        assertNotNull(state.pendingAction)

        state = t(state, GameAction.Timeout("a"))

        assertNull(state.pendingAction)
        assertTrue(
            state.status("b") == PlayerStatus.BUST || state.status("c") == PlayerStatus.BUST,
            "the clock ran out and the bomb went nowhere",
        )
    }

    @Test
    fun `drawing a second bomb while armed fizzles`() {
        val dealt = startedAndDealt(rest = listOf(action(SUICIDE_BOMBER.id), num(9)))
        val result = tr(armed(dealt, "a"), GameAction.Hit("a"))

        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == SUICIDE_BOMBER.id })
        assertNotNull(result.state.forcedDraws, "the drawer is owed a replacement")
    }

    @Test
    fun `the bomb does not put a card back into the deck`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(num(4, id = "dup")))
        var state = armed(handOf(dealt, "a", listOf(num(4, id = "held"))), "a")
        val before = state.allCardIds().toSet()

        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", SUICIDE_BOMBER.id))

        assertEquals(before, state.allCardIds().toSet(), "the prompt minted a card that stayed in the game")
    }
}
