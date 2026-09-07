package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.CUSTOM_DECK_ID
import com.letitride.engine.DeckConfig
import com.letitride.engine.DeckPresets
import com.letitride.engine.GameConfig
import com.letitride.engine.GameMode
import com.letitride.engine.NumberCardEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a room does to a config before it plays it.
 *
 * `sanitize` is the only thing standing between a crafted `SET_CONFIG` and the
 * engine, so every rule it keeps is one nothing downstream has to check again.
 */
class RoomConfigTest {

    private fun seat(playerId: String) = Connection(playerId, Channel(Channel.UNLIMITED))

    private fun room(): Room =
        RoomRegistry(appJson, CoroutineScope(Job())).create(seed = 20260906L, dev = false)

    /** A deck that is legal on its own, holding two gambler cards. */
    private fun deckWithGamblers() = DeckConfig(
        numberCards = (1..12).map { NumberCardEntry(value = it, count = 2) },
        gamblerCards = listOf("nullify", "shuffle"),
    )

    @Test
    fun `a classic table is not dealt gambler cards, whatever deck it was handed`() = runBlocking {
        val room = room()
        room.attach("a", "ana", seat("a"))

        room.handle(
            "a",
            ClientMessage.SetConfig(
                GameConfig(deckPresetId = CUSTOM_DECK_ID, deck = deckWithGamblers(), mode = GameMode.CLASSIC),
            ),
        )

        // Kept as a deck, minus the cards it has nowhere to put. A hidden hand
        // is a thing the rolling-rules screen draws and the classic one does
        // not, so a gambler card here would leave the deck and be seen by
        // nobody ever again.
        assertEquals(emptyList(), room.state.config.deck.gamblerCards)
        assertTrue(room.state.config.deck.numberCards.isNotEmpty(), "the rest of the deck survived")
        room.close()
    }

    @Test
    fun `and a rolling rules table keeps every one of them`() = runBlocking {
        val room = room()
        room.attach("a", "ana", seat("a"))

        room.handle(
            "a",
            ClientMessage.SetConfig(
                GameConfig(
                    deckPresetId = CUSTOM_DECK_ID,
                    deck = deckWithGamblers(),
                    mode = GameMode.ROLLING_RULES,
                ),
            ),
        )

        assertEquals(listOf("nullify", "shuffle"), room.state.config.deck.gamblerCards)
        room.close()
    }

    @Test
    fun `the rolling rules preset in classic is stripped too, not only a built deck`() = runBlocking {
        // A preset's cards are copied over whatever arrived, so this is a second
        // path into the same hole and it has to be shut at the same place.
        val room = room()
        room.attach("a", "ana", seat("a"))

        room.handle(
            "a",
            ClientMessage.SetConfig(
                GameConfig(
                    deckPresetId = DeckPresets.ROLLING_RULES.id,
                    deck = DeckPresets.ROLLING_RULES.deck,
                    mode = GameMode.CLASSIC,
                ),
            ),
        )

        assertTrue(
            DeckPresets.ROLLING_RULES.deck.gamblerCards.isNotEmpty(),
            "the preset is the one that carries them; this test says nothing otherwise",
        )
        assertEquals(emptyList(), room.state.config.deck.gamblerCards)
        room.close()
    }
}
