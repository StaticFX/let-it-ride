package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.DeckPresets
import com.letitride.engine.Engine
import com.letitride.engine.GameEvent
import com.letitride.engine.defaultGameConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutroTest {

    private val card = Card(id = "n-1", kind = CardKind.NUMBER, label = "7", value = 7)

    private fun ended(autoSeconds: Int?, winner: String? = null) = Engine
        .newGame(defaultGameConfig().copy(deck = DeckPresets.PURE.deck, autoNextRoundSeconds = autoSeconds))
        .copy(gameWinnerId = winner)

    @Test
    fun `a round that simply ran out of players goes straight to the card`() {
        assertEquals(0L, outroPreambleFor(listOf(GameEvent.Stay("a"), GameEvent.RoundScored(emptyMap(), "a"))))
    }

    @Test
    fun `a round ended by a bust waits for the bust animation`() {
        val events = listOf(GameEvent.Bust("a", "duplicate", card, card), GameEvent.RoundScored(emptyMap(), "b"))
        assertEquals(OUTRO_AFTER_BUST_MS, outroPreambleFor(events))
    }

    @Test
    fun `a round ended by a flip 7 waits for the fanfare`() {
        val events = listOf(GameEvent.Flip7("a"), GameEvent.RoundScored(emptyMap(), "a"))
        assertEquals(OUTRO_AFTER_FLIP7_MS, outroPreambleFor(events))
    }

    @Test
    fun `a flip 7 that also busted somebody still waits for the longer one`() {
        val events = listOf(GameEvent.Bust("b", "duplicate"), GameEvent.Flip7("a"))
        assertEquals(OUTRO_AFTER_FLIP7_MS, outroPreambleFor(events))
    }

    // ─── Autostart ───

    @Test
    fun `a table left on manual never deals itself`() {
        assertNull(autoNextRoundAt(ended(autoSeconds = null), scoreboardAt = 1_000L))
    }

    @Test
    fun `the countdown runs from the scoreboard, not from the end of the round`() {
        assertEquals(21_000L, autoNextRoundAt(ended(autoSeconds = 20), scoreboardAt = 1_000L))
    }

    @Test
    fun `the round that settled the game does not deal another`() {
        assertNull(autoNextRoundAt(ended(autoSeconds = 20, winner = "a"), scoreboardAt = 1_000L))
    }
}
