package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyTest {

    @Test
    fun `players can be added and removed while in the lobby`() {
        var state = t(Engine.newGame(config()), GameAction.AddPlayer("a", "Alice"))
        assertEquals(1, state.players.size)
        state = t(state, GameAction.RemovePlayer("a"))
        assertEquals(0, state.players.size)
    }

    @Test
    fun `the same player id cannot take two seats`() {
        var state = t(Engine.newGame(config()), GameAction.AddPlayer("a", "Alice"))
        state = t(state, GameAction.AddPlayer("a", "Alice again"))
        assertEquals(1, state.players.size)
    }

    @Test
    fun `the table is capped`() {
        var state = Engine.newGame(config())
        repeat(MAX_PLAYERS + 2) { state = t(state, GameAction.AddPlayer("p$it", "p$it")) }
        assertEquals(MAX_PLAYERS, state.players.size)
    }

    @Test
    fun `starting needs at least two players`() {
        val state = t(lobby(players = listOf("a")), GameAction.StartGame)
        assertEquals(GamePhase.LOBBY, state.phase)
    }

    @Test
    fun `starting deals nobody in yet and queues the opening flip`() {
        val state = started()
        assertEquals(GamePhase.PLAYING, state.phase)
        assertEquals(1, state.round)
        assertTrue(state.players.all { it.hand.isEmpty() && it.passives.isEmpty() })
        assertEquals(listOf("a", "b"), state.dealQueue)
    }

    @Test
    fun `the opening deal gives everyone exactly one card`() {
        val state = startedAndDealt()
        assertEquals(1, state.hand("a").size)
        assertEquals(1, state.hand("b").size)
        assertTrue(state.dealQueue.isEmpty())
    }

    @Test
    fun `the round starter acts first once dealing finishes`() {
        val state = startedAndDealt(players = listOf("a", "b", "c"))
        assertEquals("a", state.currentPlayer?.id)
    }

    @Test
    fun `an opening modifier does not trigger a second deal to the same player`() {
        val state = startedAndDealt(
            openingCards = listOf(passive(PLUS_TEN.id), num(3)),
            rest = listOf(num(4), num(5)),
        )
        assertEquals(1, state.player("a")!!.passives.size)
        assertTrue(state.hand("a").isEmpty())
        assertEquals(1, state.hand("b").size)
    }

    @Test
    fun `config changes are only accepted before the game starts`() {
        val updated = config(totalRounds = 9)
        val lobbyState = t(lobby(), GameAction.SetConfig(updated))
        assertEquals(9, lobbyState.config.totalRounds)

        val playing = t(started(), GameAction.SetConfig(config(totalRounds = 2)))
        assertEquals(3, playing.config.totalRounds)
    }

    @Test
    fun `leaving mid-game folds the player instead of vacating their seat`() {
        val state = startedAndDealt(players = listOf("a", "b", "c"))
        val after = t(state, GameAction.RemovePlayer("b"))
        assertEquals(3, after.players.size)
        assertEquals(PlayerStatus.STAYED, after.status("b"))
        assertEquals(false, after.player("b")!!.connected)
    }

    @Test
    fun `a leaver who owed a target pick releases the table`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(action(FREEZE.id), num(9)),
        )
        state = t(state, GameAction.Hit("a"))
        assertEquals("a", state.pendingAction?.playerId)

        val after = t(state, GameAction.RemovePlayer("a"))
        assertNull(after.pendingAction)
        assertTrue(after.discard.any { it.defId == FREEZE.id }, "the abandoned card should be discarded")
    }
}
