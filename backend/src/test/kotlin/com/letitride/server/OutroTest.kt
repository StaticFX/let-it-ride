package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.GameEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class OutroTest {

    private val card = Card(id = "n-1", kind = CardKind.NUMBER, label = "7", value = 7)

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
}
