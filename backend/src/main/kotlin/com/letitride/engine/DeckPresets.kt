package com.letitride.engine

import kotlinx.serialization.Serializable

@Serializable
data class DeckPreset(
    val id: String,
    val name: String,
    val description: String,
    val deck: DeckConfig,
) {
    val cardCount: Int get() = Deck.size(deck)
}

private fun times(id: String, n: Int) = List(n) { id }

/** Flip 7's number cards: 0 appears once, every other value N appears N times. */
private fun flip7Numbers(max: Int): List<NumberCardEntry> =
    listOf(NumberCardEntry(value = 0, count = 1, label = "0")) +
        (1..max).map { NumberCardEntry(value = it, count = it, label = it.toString()) }

private val SUITS = listOf("hearts", "diamonds", "clubs", "spades")

private val CLASSIC_RANKS = listOf(
    "A" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7,
    "8" to 8, "9" to 9, "10" to 10, "J" to 11, "Q" to 12, "K" to 13,
)

object DeckPresets {
    /** 0–12 numbers, three of each action, one of each modifier — 94 cards. */
    val FLIP7 = DeckPreset(
        id = "flip7",
        name = "Flip 7",
        description = "the official deck — 0-12, freeze, flip three, second chance & modifiers",
        deck = DeckConfig(
            numberCards = flip7Numbers(12),
            actionCards = times(FREEZE.id, 3) + times(DRAW_THREE.id, 3),
            passiveCards = times(SECOND_LIFE.id, 3) +
                listOf(PLUS_TWO.id, PLUS_FOUR.id, PLUS_SIX.id, PLUS_EIGHT.id, PLUS_TEN.id, DOUBLE_POINTS.id),
        ),
    )

    /** The house variant: numbers run to 13 and the modifier mix is heavier. */
    val LET_IT_RIDE = DeckPreset(
        id = "letitride",
        name = "Let It Ride",
        description = "0-13 number cards, freeze & draw 3, passives",
        deck = DeckConfig(
            numberCards = flip7Numbers(13),
            actionCards = times(DRAW_THREE.id, 3) + times(FREEZE.id, 3),
            passiveCards = listOf(SECOND_LIFE.id, DOUBLE_POINTS.id) +
                times(PLUS_TEN.id, 2) + times(PLUS_FOUR.id, 5),
        ),
    )

    val PURE = DeckPreset(
        id = "pure",
        name = "Pure",
        description = "just the numbers — no actions, no passives",
        deck = DeckConfig(numberCards = flip7Numbers(13)),
    )

    val CLASSIC52 = DeckPreset(
        id = "classic52",
        name = "Classic 52",
        description = "standard playing cards, A through K",
        deck = DeckConfig(
            numberCards = CLASSIC_RANKS.map { (rank, value) ->
                NumberCardEntry(value = value, count = SUITS.size, label = rank, suits = SUITS)
            },
        ),
    )

    val CHAOS = DeckPreset(
        id = "chaos",
        name = "Chaos",
        description = "every action & passive card, total mayhem",
        deck = DeckConfig(
            numberCards = flip7Numbers(13),
            actionCards = times(STRIKE.id, 2) + times(STEAL.id, 2) + times(HEX.id, 2) + times(SWAP.id, 2) +
                times(DRAW_THREE.id, 3) + times(FREEZE.id, 3) +
                times(DOUBLE_OR_NOTHING.id, 2) + times(SLOTS.id, 3),
            passiveCards = times(ARMOR.id, 2) + times(SECOND_LIFE.id, 2) +
                listOf(DOUBLE_POINTS.id, BOUNTY.id) +
                times(PLUS_TEN.id, 3) + times(PLUS_FOUR.id, 5),
        ),
    )

    val GAMBLER = DeckPreset(
        id = "gambler",
        name = "Gambler",
        description = "double or nothing, slots & high-risk plays",
        deck = DeckConfig(
            numberCards = flip7Numbers(13),
            actionCards = times(DOUBLE_OR_NOTHING.id, 3) + times(SLOTS.id, 3) + times(DRAW_THREE.id, 2),
            passiveCards = listOf(SECOND_LIFE.id) + times(DOUBLE_POINTS.id, 2) + times(PLUS_TEN.id, 2),
        ),
    )

    val FRIENDLY = DeckPreset(
        id = "friendly",
        name = "Friendly",
        description = "numbers + passive bonuses, no attacks",
        deck = DeckConfig(
            numberCards = flip7Numbers(13),
            passiveCards = times(SECOND_LIFE.id, 2) + listOf(DOUBLE_POINTS.id) + times(BOUNTY.id, 2) +
                times(PLUS_TEN.id, 3) + times(PLUS_FOUR.id, 5),
        ),
    )

    val all: List<DeckPreset> = listOf(FLIP7, LET_IT_RIDE, PURE, CLASSIC52, CHAOS, GAMBLER, FRIENDLY)

    private val byId = all.associateBy { it.id }

    val default: DeckPreset = LET_IT_RIDE

    fun byId(id: String?): DeckPreset? = id?.let { byId[it] }
}

/** Flip 7's own default: first player to 200 points takes the game. */
fun defaultGameConfig(): GameConfig = GameConfig(
    deckPresetId = DeckPresets.default.id,
    deck = DeckPresets.default.deck,
    winCondition = WinCondition.FIRST_TO_SCORE,
    targetScore = 200,
)
