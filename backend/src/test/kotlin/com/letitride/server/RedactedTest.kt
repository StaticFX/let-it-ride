package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.DeckConfig
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.NumberCardEntry
import com.letitride.engine.Player
import com.letitride.engine.PlayerStatus
import com.letitride.engine.REDACTED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "redacted" card, at the only place it can be got wrong: the wire.
 *
 * Everything else about it is an ordinary modifier — it is dealt, it lies in the
 * row in front of a seat, it can be stolen or swapped away. What is not ordinary
 * is that the hand behind it goes out to its owner and to nobody else, which
 * means the same state is two different payloads, and a mistake in either one
 * has no symptom at all.
 */
class RedactedTest {
    private fun number(id: String, value: Int) =
        Card(id = id, kind = CardKind.NUMBER, label = value.toString(), value = value)

    private fun table(
        status: PlayerStatus = PlayerStatus.ACTIVE,
        hand: List<Card> = listOf(number("n-7", 7), number("n-4", 4)),
    ) = GameState(
        phase = GamePhase.PLAYING,
        config = GameConfig(deck = DeckConfig(numberCards = listOf(NumberCardEntry(value = 3, count = 20)))),
        players = listOf(
            Player(
                id = "a",
                name = "ana",
                hand = hand,
                handValue = hand.sumOf { it.value },
                passives = listOf(
                    Card(id = "p-1", kind = CardKind.PASSIVE, label = "redacted", value = 0, defId = REDACTED.id),
                ),
                status = status,
            ),
            Player(id = "b", name = "bo", hand = listOf(number("n-2", 2)), handValue = 2),
        ),
    )

    private fun GameState.viewFor(viewerId: String?) =
        toView(viewerId = viewerId, roomCode = "ROOM", hostId = "a", turnDeadline = null)

    // ─── The projection ───

    @Test
    fun `its holder is sent their own hand exactly as it is`() {
        val hand = table().viewFor("a").players.first { it.id == "a" }.hand
        assertEquals(listOf("7", "4"), hand.map { it.label })
        assertFalse(hand.any { it.hidden })
    }

    @Test
    fun `everybody else is sent the same cards with no faces on them`() {
        val seat = table().viewFor("b").players.first { it.id == "a" }
        assertEquals(2, seat.hand.size, "how many cards somebody is holding was never a secret")
        assertEquals(listOf("n-7", "n-4"), seat.hand.map { it.id }, "the ids stay, or nothing can be animated")
        assertTrue(seat.hand.all { it.hidden })
        assertTrue(seat.hand.all { it.label.isEmpty() && it.value == 0 })
    }

    @Test
    fun `and no face of it reaches their copy of the payload at all`() {
        val payload = appJson.encodeToString(GameStateView.serializer(), table().viewFor("b"))
        // The only claim worth making: not "absent from a list somewhere",
        // absent from the whole push.
        assertFalse("\"7\"" in payload, "a hidden card's label reached another seat")
        assertFalse("\"4\"" in payload)
    }

    @Test
    fun `the total is withheld rather than sent as nothing`() {
        val theirs = table().viewFor("b")
        assertNull(theirs.handWorth["a"], "no total at all, so the seat can say it does not know")
        assertEquals(listOf("a"), theirs.hiddenHandIds)
        assertEquals(0, theirs.players.first { it.id == "a" }.handValue)

        val mine = table().viewFor("a")
        assertEquals(11, mine.handWorth["a"])
        assertTrue(mine.hiddenHandIds.isEmpty(), "you are never hidden from yourself")
    }

    @Test
    fun `the card doing the hiding stays face up`() {
        val seat = table().viewFor("b").players.first { it.id == "a" }
        assertEquals(listOf(REDACTED.id), seat.passives.map { it.defId })
        assertFalse(seat.passives.any { it.hidden }, "a hand hidden for no visible reason is a bug, not a card")
    }

    @Test
    fun `a hand goes face up the moment its owner's round is over`() {
        for (status in listOf(PlayerStatus.STAYED, PlayerStatus.BUST)) {
            val seat = table(status = status).viewFor("b").players.first { it.id == "a" }
            assertEquals(listOf("7", "4"), seat.hand.map { it.label }, "$status")
            assertEquals(11, table(status = status).viewFor("b").handWorth["a"], "$status")
        }
    }

    @Test
    fun `nobody else's hand is touched`() {
        val seat = table().viewFor("a").players.first { it.id == "b" }
        assertEquals(listOf("2"), seat.hand.map { it.label })
    }

    @Test
    fun `a view with no viewer hides every hidden hand there is`() {
        val view = table().viewFor(null)
        assertEquals(listOf("a"), view.hiddenHandIds)
        assertTrue(view.players.first { it.id == "a" }.hand.all { it.hidden })
    }

    // ─── The events ───
    //
    // The state says what is on the table now; the events say what just
    // happened, and a card can be named by one without the other. Both have to
    // agree or the hand is on the wire anyway — see `Room.redactFor`.

    private fun cut(events: List<GameEvent>, viewerId: String, state: GameState = table()) =
        redactFor(events, viewerId, state)

    @Test
    fun `a card drawn into a hidden hand flies face down for everybody else`() {
        val drawn = number("n-9", 9)
        assertEquals(drawn, (cut(listOf(GameEvent.Draw("a", drawn)), "a").single() as GameEvent.Draw).card)

        val theirs = cut(listOf(GameEvent.Draw("a", drawn)), "b").single() as GameEvent.Draw
        assertEquals("n-9", theirs.card.id, "the table still watches the card cross to the seat")
        assertTrue(theirs.card.hidden)
        assertEquals("", theirs.card.label)
    }

    @Test
    fun `an action or a modifier drawn by the same seat is not`() {
        val modifier = Card(id = "p-2", kind = CardKind.PASSIVE, label = "+4", value = 0, defId = "plus4")
        val theirs = cut(listOf(GameEvent.Draw("a", modifier)), "b").single() as GameEvent.Draw
        assertEquals("+4", theirs.card.label, "the row in front of a seat was never covered by this card")
    }

    @Test
    fun `a card leaving a hidden hand for the discard pile keeps its secret`() {
        val struck = number("n-7", 7)
        val theirs = cut(listOf(GameEvent.Discard("a", struck)), "b").single() as GameEvent.Discard
        assertTrue(theirs.card.hidden, "the pile is a count on the wire — this is the only look anybody gets")
        assertFalse((cut(listOf(GameEvent.Discard("a", struck)), "a").single() as GameEvent.Discard).card.hidden)
    }

    @Test
    fun `a card stolen out of one is public, because it lands somewhere everyone can see`() {
        val taken = number("n-7", 7)
        val theirs = cut(listOf(GameEvent.Steal("a", "b", taken)), "b").single() as GameEvent.Steal
        assertEquals("7", theirs.card.label, "it is lying in b's own hand by now")
    }

    @Test
    fun `and a card stolen into one is not`() {
        val taken = number("n-2", 2)
        val watching = cut(listOf(GameEvent.Steal("b", "a", taken)), "b").single() as GameEvent.Steal
        assertTrue(watching.card.hidden, "b gave it up and no longer gets to see where it went")
        assertFalse((cut(listOf(GameEvent.Steal("b", "a", taken)), "a").single() as GameEvent.Steal).card.hidden)
    }

    @Test
    fun `a swap cuts each card by where it is going, not where it came from`() {
        val mine = number("n-7", 7)
        val theirs = number("n-2", 2)
        val seen = cut(listOf(GameEvent.CardsSwapped("a", mine, "b", theirs)), "b").single()
            as GameEvent.CardsSwapped

        assertEquals("7", seen.firstCard.label, "a's card is going to b, who is about to be holding it")
        assertTrue(seen.secondCard.hidden, "b's card is going somewhere b may not look")
    }

    @Test
    fun `both cards of a save are cut, or naming one would name the other`() {
        val duplicate = number("n-7b", 7)
        val matched = number("n-7", 7)
        val saver = Card(id = "p-9", kind = CardKind.PASSIVE, label = "second life", value = 0, defId = "secondLife")
        val seen = cut(listOf(GameEvent.SecondChance("a", duplicate, matched, saver)), "b").single()
            as GameEvent.SecondChance

        assertTrue(seen.card.hidden)
        assertTrue(seen.matched!!.hidden)
        assertEquals("second life", seen.saver!!.label, "what was spent came off a row everybody can read")
    }

    @Test
    fun `a hand that has gone out is not cut at all`() {
        val drawn = number("n-9", 9)
        val out = table(status = PlayerStatus.STAYED)
        val theirs = cut(listOf(GameEvent.Draw("a", drawn)), "b", out).single() as GameEvent.Draw
        assertEquals("9", theirs.card.label, "the seat turned its cards over when its round ended")
    }

    @Test
    fun `a table with nothing to hide gets the batch it was handed`() {
        val plain = table().let { s ->
            s.copy(players = s.players.map { if (it.id == "a") it.copy(passives = emptyList()) else it })
        }
        val events = listOf(GameEvent.Draw("a", number("n-9", 9)))
        assertTrue(cut(events, "b", plain) === events, "the common table pays nothing for this")
    }
}
