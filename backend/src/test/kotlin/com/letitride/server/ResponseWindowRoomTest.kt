package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.DeckPresets
import com.letitride.engine.GameMode
import com.letitride.engine.GamePhase
import com.letitride.engine.defaultGameConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The response window as the room runs it.
 *
 * The engine decides whether a window opens; the room decides how long it stays
 * open and who is on the clock for it. The failure this guards against is the
 * worst one the mode has — a window nobody answers holds the whole table, and
 * at a table of bots there is no clock at all to save it.
 */
class ResponseWindowRoomTest {

    /** A connected player that acks whatever it is asked to, so nothing waits on it. */
    private class Seat(val playerId: String) {
        val outbound = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val connection = Connection(playerId, outbound)
        private val states = mutableListOf<GameStateView>()
        private val acks = mutableListOf<Long>()

        /** Drains the socket, remembering every state and every gate it owes an ack for. */
        fun latest(): GameStateView? {
            while (true) {
                val payload = outbound.tryReceive().getOrNull() ?: break
                val message = appJson.decodeFromString(ServerMessage.serializer(), payload)
                if (message is ServerMessage.State) {
                    states += message.state
                    val gate = message.state.animationGate
                    if (gate != null && gate.ackPlayerId == playerId && gate.id !in acks) acks += gate.id
                }
            }
            return states.lastOrNull()
        }

        /**
         * A real client acks when its animation ends; this one has no
         * animations, so it acks on arrival. Without it every gated step would
         * wait out the room's backstop and a test would take minutes.
         */
        suspend fun ack(room: Room) {
            latest()
            val owed = acks.toList()
            acks.clear()
            for (id in owed) room.handle(playerId, ClientMessage.AnimationDone(id))
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 30_000, description: String, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        throw AssertionError("waited ${timeoutMs}ms for $description and it never happened")
    }

    private val rollingConfig = defaultGameConfig().copy(
        mode = GameMode.ROLLING_RULES,
        deckPresetId = DeckPresets.ROLLING_RULES.id,
        deck = DeckPresets.ROLLING_RULES.deck,
        turnTimeSeconds = 300,
    )

    /**
     * Two seats, a shuffle in the first tray and a nahhh in the second.
     *
     * The deal takes one card per player off the top, and a gambler card does
     * not cost a draw — so entry 0 goes to a's tray, entry 1 is dealt to a, and
     * entries 2 and 3 do the same for b.
     */
    private suspend fun tableWithACounter(): Triple<Room, Seat, Seat> {
        val room = RoomRegistry(appJson, CoroutineScope(Job()))
            .create(seed = 4242L, stack = listOf("shuffle", "5", "nahhh", "6"))
        val a = Seat("a")
        val b = Seat("b")
        room.attach("a", "ana", a.connection)
        room.attach("b", "bo", b.connection)
        room.handle("a", ClientMessage.SetConfig(rollingConfig))
        room.handle("a", ClientMessage.StartGame)

        waitFor(description = "the opening deal to finish") {
            a.ack(room)
            b.ack(room)
            val view = a.latest()
            view != null && view.phase == GamePhase.PLAYING && view.dealQueue.isEmpty()
        }
        return Triple(room, a, b)
    }

    @Test
    fun `a card somebody can answer stops the table and puts them on a clock`() = runBlocking {
        val (room, a, b) = tableWithACounter()
        try {
            val mine = assertNotNull(a.latest()?.myGamblers?.firstOrNull { it.defId == "shuffle" })
            room.handle("a", ClientMessage.PlayGambler(mine.id))

            waitFor(description = "the response window to open") {
                a.ack(room); b.ack(room)
                a.latest()?.responseWindow != null
            }

            val view = assertNotNull(a.latest())
            assertEquals(listOf("b"), view.responseWindow?.awaiting)
            assertEquals(1, view.responseStack.size, "the card is in flight, not resolved")
            // b is a person, so somebody is on a clock for it.
            assertNotNull(view.turnDeadline, "a window a human is being asked about runs on a clock")
        } finally {
            room.close()
        }
    }

    @Test
    fun `and letting it stand lets the card through`() = runBlocking {
        val (room, a, b) = tableWithACounter()
        try {
            val mine = assertNotNull(a.latest()?.myGamblers?.firstOrNull { it.defId == "shuffle" })
            room.handle("a", ClientMessage.PlayGambler(mine.id))
            waitFor(description = "the response window to open") {
                a.ack(room); b.ack(room)
                a.latest()?.responseWindow != null
            }

            room.handle("b", ClientMessage.Pass)
            waitFor(description = "the window to shut") {
                a.ack(room); b.ack(room)
                a.latest()?.responseWindow == null
            }

            val view = assertNotNull(a.latest())
            assertTrue(view.responseStack.isEmpty())
            assertNull(view.responseWindow)
        } finally {
            room.close()
        }
    }

    /**
     * The one that matters. A bot that stayed silent would hold the table until
     * the clock ran out — and `deadlineFor` hands out no clock at all when
     * everybody being asked is a bot, so there would be nothing to run out.
     */
    @Test
    fun `a bot lets it stand on its own, without being asked twice`() = runBlocking {
        val room = RoomRegistry(appJson, CoroutineScope(Job()))
            .create(seed = 4242L, stack = listOf("shuffle", "5", "nahhh", "6"))
        val a = Seat("a")
        try {
            room.attach("a", "ana", a.connection)
            room.handle("a", ClientMessage.AddBot)
            room.handle("a", ClientMessage.SetConfig(rollingConfig))
            room.handle("a", ClientMessage.StartGame)
            waitFor(description = "the opening deal to finish") {
                a.ack(room)
                val view = a.latest()
                view != null && view.phase == GamePhase.PLAYING && view.dealQueue.isEmpty()
            }

            val mine = assertNotNull(a.latest()?.myGamblers?.firstOrNull { it.defId == "shuffle" })
            room.handle("a", ClientMessage.PlayGambler(mine.id))

            // No pass is ever sent from this test: the bot has to do it, and the
            // table has to come back on its own.
            waitFor(description = "the bot to let it stand and the table to move on") {
                a.ack(room)
                val view = a.latest()
                view?.responseWindow == null && view?.responseStack?.isEmpty() == true
            }
        } finally {
            room.close()
        }
    }
}
