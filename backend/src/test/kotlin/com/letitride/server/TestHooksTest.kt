package com.letitride.server

import com.letitride.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seed hook lets the end-to-end suite replay a deal card for card. It must
 * stay off unless the environment asks for it, or a public server would let
 * anyone deal themselves a deck they already know.
 */
class TestHooksTest {

    @Test
    fun `the hook is off by default`() {
        assertTrue(!testHooksEnabled { null })
        assertTrue(!testHooksEnabled { "" })
        assertTrue(!testHooksEnabled { "0" })
        assertTrue(!testHooksEnabled { "off" })
    }

    @Test
    fun `the hook reads the documented variable`() {
        val calls = mutableListOf<String>()
        testHooksEnabled { calls += it; null }
        assertEquals(listOf(TEST_HOOKS_ENV), calls)
    }

    @Test
    fun `the usual affirmatives turn it on`() {
        for (value in listOf("1", "true", "TRUE", "yes")) {
            assertTrue(testHooksEnabled { value }, "'$value' should have enabled the hook")
        }
    }

    // ─── Pacing ───

    private fun env(hooks: String?, pace: String?): (String) -> String? = { name ->
        when (name) {
            TEST_HOOKS_ENV -> hooks
            PACE_ENV -> pace
            else -> null
        }
    }

    @Test
    fun `a server without the test hooks runs at full pace, whatever it is asked`() {
        // The gate matters more than the value: a public server must not be
        // able to have the pacing pulled out from under its players.
        assertEquals(1.0, pacingFactor(env(hooks = null, pace = "0.1")))
        assertEquals(1.0, pacingFactor(env(hooks = "0", pace = "0.1")))
    }

    @Test
    fun `with the hooks on it takes the pace it is given`() {
        assertEquals(0.25, pacingFactor(env(hooks = "1", pace = "0.25")))
        assertEquals(1.0, pacingFactor(env(hooks = "1", pace = null)), "the default is what a player sees")
    }

    @Test
    fun `it only ever speeds a table up, and never to a standstill`() {
        assertEquals(1.0, pacingFactor(env(hooks = "1", pace = "4")), "slowing a table down is nobody's business")
        assertEquals(0.05, pacingFactor(env(hooks = "1", pace = "0")), "a pace of zero would race the client")
        assertEquals(0.05, pacingFactor(env(hooks = "1", pace = "-3")))
        assertEquals(1.0, pacingFactor(env(hooks = "1", pace = "banana")))
        assertEquals(1.0, pacingFactor(env(hooks = "1", pace = "NaN")))
    }

    @Test
    fun `a create-room body without a seed still parses`() {
        val request = appJson.decodeFromString(CreateRoomRequest.serializer(), """{"name":"devin"}""")
        assertEquals("devin", request.name)
        assertNull(request.seed)
    }

    @Test
    fun `a seed survives the wire`() {
        val request = appJson.decodeFromString(
            CreateRoomRequest.serializer(),
            """{"name":"devin","seed":-9007199254740993}""",
        )
        assertEquals(-9007199254740993L, request.seed)
    }

    @Test
    fun `the same seed deals the same game`() {
        val registry = RoomRegistry(appJson, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()))
        val a = registry.create(seed = 4242)
        val b = registry.create(seed = 4242)
        val c = registry.create(seed = 4243)

        // The shuffle happens at StartGame, so compare what the rng will produce
        // for each room by dealing the same opening from each.
        fun opening(room: Room): List<String> {
            val deck = com.letitride.engine.Deck.build(room.state.config.deck)
            return com.letitride.engine.Rng(room.seed).shuffled(deck).take(10).map { it.id }
        }

        assertEquals(opening(a), opening(b), "the same seed must deal the same cards")
        assertTrue(opening(a) != opening(c), "different seeds must deal differently")

        a.close(); b.close(); c.close()
    }
}
