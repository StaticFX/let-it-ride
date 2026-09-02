package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckTest {

    @Test
    fun `flip 7 preset matches the published 94-card deck`() {
        val deck = DeckPresets.FLIP7.deck
        // 0 once, then N copies of every value 1..12 → 79 number cards.
        assertEquals(79, deck.numberCards.sumOf { it.count })
        assertEquals(6, deck.actionCards.size)
        assertEquals(9, deck.passiveCards.size)
        assertEquals(94, DeckPresets.FLIP7.cardCount)
    }

    @Test
    fun `flip 7 number cards appear as many times as their value`() {
        val entries = DeckPresets.FLIP7.deck.numberCards.associate { it.value to it.count }
        assertEquals(1, entries[0])
        for (value in 1..12) assertEquals(value, entries[value], "value $value")
    }

    @Test
    fun `flip 7 carries one of each modifier and three second chances`() {
        val passives = DeckPresets.FLIP7.deck.passiveCards.groupingBy { it }.eachCount()
        assertEquals(3, passives[SECOND_LIFE.id])
        assertEquals(1, passives[DOUBLE_POINTS.id])
        for (id in listOf(PLUS_TWO.id, PLUS_FOUR.id, PLUS_SIX.id, PLUS_EIGHT.id, PLUS_TEN.id)) {
            assertEquals(1, passives[id], id)
        }
    }

    @Test
    fun `preset card counts are derived from the deck itself`() {
        for (preset in DeckPresets.all) {
            assertEquals(
                Deck.build(preset.deck).size,
                preset.cardCount,
                "${preset.id} advertises a count it does not build",
            )
        }
    }

    @Test
    fun `classic 52 builds four suits of thirteen ranks`() {
        val cards = Deck.build(DeckPresets.CLASSIC52.deck)
        assertEquals(52, cards.size)
        assertEquals(13, cards.map { it.label }.distinct().size)
        for (suit in listOf("hearts", "diamonds", "clubs", "spades")) {
            assertEquals(13, cards.count { it.suit == suit }, suit)
        }
    }

    @Test
    fun `built cards all have unique ids`() {
        for (preset in DeckPresets.all) {
            val cards = Deck.build(preset.deck)
            assertEquals(cards.size, cards.map { it.id }.distinct().size, preset.id)
        }
    }

    @Test
    fun `unknown card ids are skipped rather than building broken cards`() {
        val cards = Deck.build(DeckConfig(actionCards = listOf("nope"), passiveCards = listOf("alsoNope")))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `shuffling with the same seed is reproducible`() {
        val cards = Deck.build(DeckPresets.LET_IT_RIDE.deck)
        val a = Rng(7).shuffled(cards).map { it.id }
        val b = Rng(7).shuffled(cards).map { it.id }
        assertEquals(a, b)
    }
}
