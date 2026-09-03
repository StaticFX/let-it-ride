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
