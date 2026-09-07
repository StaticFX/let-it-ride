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
import kotlin.test.assertTrue

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
    fun `a bust off the deck is given the whole slam and not just the scatter`() {
        // The card is carried up over the seat and held there before it comes
        // down, which is most of the animation and none of the old window.
        val events = listOf(
            GameEvent.Draw("a", card),
            GameEvent.Bust("a", "duplicate", card, card),
            GameEvent.RoundScored(emptyMap(), "b"),
        )
        assertEquals(OUTRO_AFTER_DRAWN_BUST_MS, outroPreambleFor(events))
        assertTrue(OUTRO_AFTER_DRAWN_BUST_MS > OUTRO_AFTER_BUST_MS)
    }

    @Test
    fun `a card that busts somebody else with your draw is not their slam`() {
        // "Redirect" hands a drawn card straight on. The card was drawn, but not
        // by the seat it landed on, and it has already been watched crossing the
        // table — so that seat gets the plain bust it would have got anyway.
        val events = listOf(
            GameEvent.Draw("a", card),
            GameEvent.Bust("b", "duplicate", card, card),
        )
        assertEquals(OUTRO_AFTER_BUST_MS, outroPreambleFor(events))
    }

    @Test
    fun `a bust nobody drew is still only a bust`() {
        val events = listOf(GameEvent.Bust("a", "duplicate", card, card), GameEvent.RoundScored(emptyMap(), "b"))
        assertEquals(OUTRO_AFTER_BUST_MS, outroPreambleFor(events))
    }

    @Test
    fun `a second life spent in the last moment of a round is watched out`() {
        // A banked seat can burn one in the same transition that ends the round
        // under somebody else, and the save is the longer of the two things the
        // table is being shown.
        val events = listOf(
            GameEvent.SecondChance("a", card, card, card),
            GameEvent.Bust("b", "duplicate", card, card),
            GameEvent.RoundScored(emptyMap(), "b"),
        )
        assertEquals(OUTRO_AFTER_SECOND_LIFE_MS, outroPreambleFor(events))
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

    @Test
    fun `a coin called wrong is given the whole throw, not just the bust`() {
        // The bust arrives in the same batch as the coin, and used to be the one
        // that was read — so the closing card came down while the coin was still
        // in the air.
        val events = listOf(
            GameEvent.CoinFlip("a", "heads", "tails"),
            GameEvent.Bust("a", "coin flip"),
            GameEvent.RoundScored(emptyMap(), "b"),
        )
        assertEquals(OUTRO_AFTER_COIN_MS, outroPreambleFor(events))
    }

    @Test
    fun `a bottle is given long enough to stop on somebody`() {
        val events = listOf(GameEvent.BottleSpin("b"), GameEvent.Bust("b", "assassination"))
        assertEquals(OUTRO_AFTER_SPIN_MS, outroPreambleFor(events))
    }

    @Test
    fun `points crossing the table are given time to get there`() {
        val events = listOf(GameEvent.PointsTransferred("a", "b", 10), GameEvent.Stay("a"))
        assertEquals(OUTRO_AFTER_TRANSFER_MS, outroPreambleFor(events))
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

    // ─── Paying the table ───

    @Test
    fun `the table is given time to be paid a seat at a time`() {
        // Every seat's points land on the scoreboard before the closing card
        // comes over, so the window has to grow with the table.
        val two = payoutWindowFor(2)
        val five = payoutWindowFor(5)
        assertTrue(two > 0)
        assertTrue(five > two, "five seats take longer to pay than two")
    }

    @Test
    fun `a table with nobody at it is paid nothing`() {
        assertEquals(0L, payoutWindowFor(0))
    }
}
