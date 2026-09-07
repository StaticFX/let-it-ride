package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.GamePhase
import com.letitride.engine.WinCondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who gets to start the evening over.
 *
 * The same answer as every other message that decides something for the whole
 * table — the host — and the same shape as `NEXT_ROUND` right above it, because
 * a guest who could restart the game could take the results screen away from
 * everybody else while they were still reading it.
 */
class PlayAgainRoomTest {

    private fun seat(playerId: String) = Connection(playerId, Channel(Channel.UNLIMITED))

    private fun room(): Room =
        RoomRegistry(appJson, CoroutineScope(Job())).create(seed = 20260907L, dev = true)

    /** Plays a one-round game out to its results screen. */
    private suspend fun finishedRoom(): Room {
        val room = room()
        room.attach("a", "ana", seat("a"))
        room.attach("b", "bo", seat("b"))

        room.handle(
            "a",
            ClientMessage.SetConfig(
                room.state.config.copy(winCondition = WinCondition.ROUNDS, totalRounds = 1),
            ),
        )
        room.handle("a", ClientMessage.StartGame)
        // Everybody out, the round scored, and none of the waiting the table
        // would normally do about it.
        room.handle("a", ClientMessage.Dev(DevSetup(endRound = true, skipWait = true)))
        room.handle("a", ClientMessage.NextRound)
        assertEquals(GamePhase.GAME_END, room.state.phase, "the game is over before the test starts")
        return room
    }

    @Test
    fun `a guest asking to play again is ignored`() = runBlocking {
        val room = finishedRoom()

        room.handle("b", ClientMessage.PlayAgain)

        assertEquals(GamePhase.GAME_END, room.state.phase, "the results are still up")
        room.close()
    }

    @Test
    fun `the host takes the whole table back to the lobby`() = runBlocking {
        val room = finishedRoom()

        room.handle("a", ClientMessage.PlayAgain)

        assertEquals(GamePhase.LOBBY, room.state.phase)
        assertEquals(listOf("a", "b"), room.state.players.map { it.id }, "nobody was sent home")
        assertEquals(1, room.state.config.totalRounds, "and the settings survived with them")
        room.close()
    }
}
