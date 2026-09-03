package com.letitride.server

import com.letitride.appJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The room hands the client a gate and stops. Nothing else at the table moves
 * until that client says the animation is over — that is the whole contract,
 * and these are the ways it can go wrong: the wrong client releasing it, a
 * stale id releasing it, a move slipping through while it is up, and a client
 * that never answers owning the room forever.
 */
class AnimationGateTest {

    /** A connected player, with everything the room ever sent it. */
    private class Seat(val playerId: String) {
        val outbound = Channel<String>(Channel.UNLIMITED)
        val connection = Connection(playerId, outbound)
        private val states = mutableListOf<GameStateView>()

        fun latest(): GameStateView? {
            while (true) {
                val payload = outbound.tryReceive().getOrNull() ?: break
                val message = appJson.decodeFromString(ServerMessage.serializer(), payload)
                if (message is ServerMessage.State) states += message.state
            }
            return states.lastOrNull()
        }

        fun gate(): AnimationGateView? = latest()?.animationGate
    }

    private fun room(): Room =
        RoomRegistry(appJson, CoroutineScope(Job())).create(seed = 20260903L)

    /** Polls until [predicate] holds, so a test never depends on the tick phase. */
    private suspend fun waitFor(
        timeoutMs: Long = 12_000,
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        throw AssertionError("waited ${timeoutMs}ms for $description and it never happened")
    }

    /** A started two-player room, wound forward to its first open gate. */
    private suspend fun gatedRoom(): Triple<Room, Seat, Seat> {
        val room = room()
        val a = Seat("a")
        val b = Seat("b")
        room.attach("a", "ana", a.connection)
        room.attach("b", "bo", b.connection)
        room.handle("a", ClientMessage.StartGame)
        waitFor(description = "the first animation gate") { a.gate() != null }
        return Triple(room, a, b)
    }

    @Test
    fun `the table holds on the gate until the client it belongs to acks`() = runBlocking {
        val (room, a, _) = gatedRoom()
        try {
            val gate = assertNotNull(a.gate(), "the room should have opened a gate")
            val held = room.state.dealQueue

            // Comfortably past DEAL_STEP_MS: the step delay is only a floor now,
            // and on its own it must not move the table on.
            delay(1500)
            assertEquals(held, room.state.dealQueue, "the deal advanced while the client was animating")

            room.handle(gate.ackPlayerId, ClientMessage.AnimationDone(gate.id))
            waitFor(description = "the deal to advance once acked") { room.state.dealQueue != held }
        } finally {
            room.close()
        }
    }

    @Test
    fun `only the client the gate belongs to can release it`() = runBlocking {
        val (room, a, _) = gatedRoom()
        try {
            val gate = assertNotNull(a.gate())
            val other = if (gate.ackPlayerId == "a") "b" else "a"
            val held = room.state.dealQueue

            room.handle(other, ClientMessage.AnimationDone(gate.id))
            delay(1500)
            assertEquals(held, room.state.dealQueue, "a bystander released somebody else's animation")

            room.handle(gate.ackPlayerId, ClientMessage.AnimationDone(gate.id))
            waitFor(description = "the deal to advance") { room.state.dealQueue != held }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a stale gate id does not release the current gate`() = runBlocking {
        val (room, a, _) = gatedRoom()
        try {
            val gate = assertNotNull(a.gate())
            val held = room.state.dealQueue

            room.handle(gate.ackPlayerId, ClientMessage.AnimationDone(gate.id - 1))
            room.handle(gate.ackPlayerId, ClientMessage.AnimationDone(gate.id + 1))
            delay(1500)
            assertEquals(held, room.state.dealQueue, "an ack for another batch released this one")
        } finally {
            room.close()
        }
    }

    @Test
    fun `a move made while the table is animating is dropped`() = runBlocking {
        val (room, a, _) = gatedRoom()
        try {
            assertNotNull(a.gate())
            val before = room.state

            // Whoever is nominally on move, the answer is the same: nothing.
            room.handle("a", ClientMessage.Hit)
            room.handle("b", ClientMessage.Hit)
            room.handle("a", ClientMessage.Stay)
            room.handle("b", ClientMessage.Stay)

            assertEquals(before.players, room.state.players, "a move landed mid-animation")
            assertEquals(before.dealQueue, room.state.dealQueue)
        } finally {
            room.close()
        }
    }

    @Test
    fun `a client that never acks does not own the table`() = runBlocking {
        val (room, a, _) = gatedRoom()
        try {
            assertNotNull(a.gate())
            val held = room.state.dealQueue

            // Nobody acks. The room has to give up and carry on by itself.
            waitFor(
                timeoutMs = ANIMATION_GATE_MAX_MS + 3000,
                description = "the room to step past a silent client",
            ) { room.state.dealQueue != held }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a tab that closes mid-animation releases the table straight away`() = runBlocking {
        val (room, a, b) = gatedRoom()
        try {
            val gate = assertNotNull(a.gate())
            val held = room.state.dealQueue

            room.detach(gate.ackPlayerId)

            // Well inside the timeout: leaving has to release the gate, not
            // leave the rest of the table sitting out the ceiling.
            waitFor(
                timeoutMs = ANIMATION_GATE_MAX_MS - 1500,
                description = "the table to carry on without the player who left",
            ) { room.state.dealQueue != held }

            assertTrue(b.latest() != null, "the remaining seat should still be getting state")
        } finally {
            room.close()
        }
    }
}
