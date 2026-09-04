package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deck somebody built rather than picked. Everything here is about what a
 * table will accept: the client is not the authority on what is playable, and
 * a deck that could hang a round must never get as far as being dealt.
 */
class CustomDeckTest {

    private fun numbers(count: Int, values: IntRange = 1..13) =
        values.map { NumberCardEntry(value = it, count = count, label = it.toString()) }

    @Test
    fun `an ordinary hand-built deck comes back as it went in`() {
        val deck = DeckConfig(
            numberCards = numbers(count = 2),
            actionCards = listOf(FREEZE.id, FREEZE.id),
            passiveCards = listOf(PLUS_FOUR.id),
        )
        assertEquals(deck, sanitizeDeck(deck))
    }

    @Test
    fun `a deck with too few numbers is refused`() {
        // Four cards is not a game, and the floor is what stops a round hanging
        // on a deck that is nearly all action cards.
        val deck = DeckConfig(numberCards = numbers(count = 1, values = 1..4))
        assertNull(sanitizeDeck(deck))
    }

    @Test
    fun `a deck that is mostly action cards is refused`() {
        // Every fizzle deals its drawer another card, so a deck without enough
        // numbers in it can keep a turn going for as long as the pile lasts.
        val deck = DeckConfig(
            numberCards = numbers(count = 1, values = 1..13),
            actionCards = List(30) { FREEZE.id },
        )
        assertNull(sanitizeDeck(deck), "13 numbers against 30 actions is not a deck")
    }

    @Test
    fun `a count somebody typed too high is clamped rather than refused`() {
        val deck = DeckConfig(numberCards = listOf(NumberCardEntry(value = 5, count = 900, label = "5")))
        val cleaned = sanitizeDeck(deck)
        assertNotNull(cleaned)
        assertEquals(DeckLimits.MAX_COPIES, cleaned.numberCards.single().count)
    }

    @Test
    fun `cards this build has never heard of are dropped`() {
        val deck = DeckConfig(
            numberCards = numbers(count = 2),
            actionCards = listOf(FREEZE.id, "teleport", "summonADog"),
            passiveCards = listOf(PLUS_FOUR.id, "invincibility"),
        )
        val cleaned = sanitizeDeck(deck)
        assertNotNull(cleaned)
        assertEquals(listOf(FREEZE.id), cleaned.actionCards)
        assertEquals(listOf(PLUS_FOUR.id), cleaned.passiveCards)
    }

    @Test
    fun `a definition that is not a card cannot be dealt into one`() {
        val deck = DeckConfig(numberCards = numbers(count = 2), actionCards = listOf(ANTI_FLIP_ID))
        val cleaned = sanitizeDeck(deck)
        assertNotNull(cleaned)
        assertTrue(cleaned.actionCards.isEmpty(), "anti flip is a house rule, not something you deal")
    }

    @Test
    fun `the same number cannot be listed twice`() {
        val deck = DeckConfig(
            numberCards = numbers(count = 2) + NumberCardEntry(value = 5, count = 20, label = "5"),
        )
        val cleaned = sanitizeDeck(deck)
        assertNotNull(cleaned)
        assertEquals(13, cleaned.numberCards.size)
        assertEquals(2, cleaned.numberCards.first { it.label == "5" }.count, "the first listing wins")
    }

    @Test
    fun `a deck nobody could shuffle is refused`() {
        val deck = DeckConfig(numberCards = (1..60).map { NumberCardEntry(it, DeckLimits.MAX_COPIES, "$it") })
        assertNull(sanitizeDeck(deck), "1200 cards is not a deck either")
    }

    @Test
    fun `an empty deck is refused rather than played`() {
        assertNull(sanitizeDeck(DeckConfig()))
    }

    @Test
    fun `a built deck actually plays`() {
        val deck = sanitizeDeck(
            DeckConfig(
                numberCards = numbers(count = 3),
                actionCards = listOf(FREEZE.id, DRAW_THREE.id),
                passiveCards = listOf(PLUS_FOUR.id, SECOND_LIFE.id),
            ),
        )
        assertNotNull(deck)

        val started = t(lobby(config(deck = deck), listOf("a", "b", "c")), GameAction.StartGame)
        assertEquals(GamePhase.PLAYING, started.phase)
        assertEquals(Deck.size(deck), started.deck.size, "the whole deck was built")

        val dealt = finishDeal(started)
        // The deal genuinely stops for an action card that came up as somebody's
        // opening card, so "everyone has been dealt in" is not the thing to
        // check — that cards came off the deck and the round is live is.
        assertTrue(dealt.deck.size < started.deck.size, "nothing was dealt at all")
        assertEquals(GamePhase.PLAYING, dealt.phase)
    }
}
