package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TurnTest {

    @Test
    fun `hitting takes a card and passes the turn`() {
        val state = startedAndDealt(rest = listOf(num(5)))
        val after = t(state, GameAction.Hit("a"))
        assertEquals(2, after.hand("a").size)
        assertEquals(1, after.turnIndex)
    }

    @Test
    fun `only the player on turn may act`() {
        val state = startedAndDealt(rest = listOf(num(5)))
        assertEquals(state, t(state, GameAction.Hit("b")))
        assertEquals(state, t(state, GameAction.Stay("b")))
    }

    @Test
    fun `nobody may act while the opening deal is still running`() {
        val state = started(deck = listOf(num(1), num(2), num(3)))
        assertEquals(state, t(state, GameAction.Hit("a")))
    }

    @Test
    fun `going out with nothing on the table is refused`() {
        val state = started().copy(dealQueue = emptyList(), turnIndex = 0)
        assertEquals(state, t(state, GameAction.Stay("a")))
    }

    @Test
    fun `no forced draw lets a player go out immediately`() {
        val state = started(config(rules = listOf(LobbyRules.NO_FORCED_FIRST.id)))
            .copy(dealQueue = emptyList(), turnIndex = 0)
        assertEquals(PlayerStatus.STAYED, t(state, GameAction.Stay("a")).status("a"))
    }

    @Test
    fun `everyone out ends the round`() {
        var state = startedAndDealt()
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(GamePhase.ROUND_END, state.phase)
    }

    @Test
    fun `hex skips the target and the turn still moves on`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(HEX.id), num(9), num(10)),
        )
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", HEX.id))
        // b was hexed, so c gets the turn instead.
        assertEquals("c", state.currentPlayer?.id)
    }

    @Test
    fun `a table where everyone is skipping still hands someone the turn`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"))
        val state = dealt.copy(
            players = dealt.players.map { if (it.id == "a") it else it.copy(skipNextTurn = true) },
        )

        // Both remaining players are skipping. Their flags clear, but the turn
        // must land on one of them rather than stalling on the player who left.
        val after = t(state, GameAction.Stay("a"))
        assertEquals(GamePhase.PLAYING, after.phase)
        assertEquals(PlayerStatus.ACTIVE, after.players[after.turnIndex].status)
        assertTrue(after.players.none { it.skipNextTurn })
    }

    @Test
    fun `a skipped player is not skipped twice`() {
        var state = startedAndDealt(players = listOf("a", "b", "c"), rest = List(6) { num(it + 4) })
        state = state.copy(players = state.players.map { if (it.id == "b") it.copy(skipNextTurn = true) else it })

        state = t(state, GameAction.Stay("a"))
        assertEquals("c", state.currentPlayer?.id)
        assertEquals(false, state.player("b")!!.skipNextTurn)

        state = t(state, GameAction.Hit("c"))
        assertEquals("b", state.currentPlayer?.id)
    }

    @Test
    fun `running the table dry makes the player go out instead of stalling`() {
        val state = startedAndDealt(openingCards = listOf(num(1), num(2)))
            .copy(deck = emptyList(), discard = emptyList())
        val after = t(state, GameAction.Hit("a"))
        assertEquals(PlayerStatus.STAYED, after.status("a"))
        assertNotEquals(state.turnIndex, after.turnIndex)
    }

    @Test
    fun `an empty deck is refilled from the discard pile`() {
        val state = startedAndDealt(openingCards = listOf(num(1), num(2)))
            .copy(deck = emptyList(), discard = listOf(num(9), num(10)))
        val result = tr(state, GameAction.Hit("a"))
        assertTrue(result.events.any { it is GameEvent.DeckReshuffled })
        assertEquals(2, result.state.hand("a").size)
        assertTrue(result.state.discard.isEmpty())
    }

    @Test
    fun `the turn clock sends a player out`() {
        val state = startedAndDealt()
        val result = tr(state, GameAction.Timeout("a"))
        assertEquals(PlayerStatus.STAYED, result.state.status("a"))
        assertTrue(result.events.any { it is GameEvent.Timeout })
    }

    @Test
    fun `a timed-out action card lands on somebody else`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(FREEZE.id), num(9)),
        )
        state = t(state, GameAction.Hit("a"))
        assertEquals("a", state.pendingAction?.playerId)

        val after = t(state, GameAction.Timeout("a"))
        assertEquals(null, after.pendingAction)
        val frozen = after.players.filter { it.id != "a" && it.status == PlayerStatus.STAYED }
        assertEquals(1, frozen.size, "exactly one other player should have been frozen")
    }

    @Test
    fun `double draw takes two cards in one turn`() {
        val state = startedAndDealt(
            config(rules = listOf(LobbyRules.DOUBLE_DRAW.id)),
            rest = listOf(num(5), num(6), num(7)),
        )
        val after = t(state, GameAction.Hit("a"))
        assertEquals(3, after.hand("a").size)
    }
}
