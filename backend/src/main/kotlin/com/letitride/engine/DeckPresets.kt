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

/**
 * The id a deck that is nobody's preset goes by. A config carrying this keeps
 * its own [DeckConfig] instead of having a preset's copied over it.
 */
const val CUSTOM_DECK_ID = "custom"

/**
 * What a deck somebody built has to be before a table will play it.
 *
 * The floor on number cards is not taste, it is termination: an action card
 * nobody can be hit with fizzles and deals its drawer another, and a deck made
 * mostly of those can keep doing that for as long as the discard pile keeps
 * being shuffled back in. Numbers are what ends a turn.
 */
object DeckLimits {
    const val MIN_NUMBER_CARDS = 12
    const val MAX_CARDS = 260
    /** Of any one card — a deck of forty freezes is not a game. */
    const val MAX_COPIES = 20
    const val MAX_SPECIALS = 40
    /** Number cards as a share of the whole, for the reason above. */
    const val MIN_NUMBER_SHARE = 0.4
}

/**
 * Trims a deck to something playable, or returns null when there is nothing
 * playable in it.
 *
 * Trims rather than refuses wherever it can: a count somebody typed too high is
 * clamped and a card this build has never heard of is dropped, because either
 * is more likely to be an old config or a fat finger than an attack. What it
 * will not do is hand back a deck a table could hang on.
 */
fun sanitizeDeck(deck: DeckConfig): DeckConfig? {
    val numbers = deck.numberCards
        .asSequence()
        .filter { it.count > 0 && it.value in 0..99 }
        .map { it.copy(count = it.count.coerceAtMost(DeckLimits.MAX_COPIES)) }
        .distinctBy { it.label ?: it.value.toString() }
        .take(64)
        .toList()

    // A card that is not a card — a house rule's prompt — is never dealt.
    val actions = deck.actionCards
        .filter { Catalog.action(it)?.deckable == true }
        .take(DeckLimits.MAX_SPECIALS)
    // Same rule as the actions above: a card that is not dealt from a deck —
    // an effect minted by whatever causes it — is never dealt from this one.
    val passives = deck.passiveCards
        .filter { Catalog.passive(it)?.deckable == true }
        .take(DeckLimits.MAX_SPECIALS)

    val numberCount = numbers.sumOf { it.count }
    if (numberCount < DeckLimits.MIN_NUMBER_CARDS) return null

    val total = numberCount + actions.size + passives.size
    if (total > DeckLimits.MAX_CARDS) return null
    if (numberCount < total * DeckLimits.MIN_NUMBER_SHARE) return null

    return DeckConfig(numberCards = numbers, actionCards = actions, passiveCards = passives)
}

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
            passiveCards = listOf(SECOND_LIFE.id, DOUBLE_POINTS.id, DISCORDIA.id) +
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
            actionCards = times(STRIKE.id, 2) + times(STEAL.id, 2) + times(HEX.id, 2) +
                times(SWAP.id, 2) + times(SWAP_CARDS.id, 2) +
                times(DRAW_THREE.id, 3) + times(FREEZE.id, 3) +
                times(COIN_FLIP.id, 2) + times(SLOTS.id, 3) +
                times(SPIN_TABLE.id, 2) + listOf(ASSASSINATION.id, DONT_CARE.id) +
                times(UNLUCKY_SEVEN.id, 2) + times(JUST_ONE_MORE.id, 2) +
                times(SUICIDE_BOMBER.id, 2) + times(COMEBACK.id, 2) + times(ALL_IN.id, 2) +
                times(MUTATE.id, 2),
            passiveCards = times(ARMOR.id, 2) + times(SECOND_LIFE.id, 2) +
                listOf(DOUBLE_POINTS.id) + times(DISCORDIA.id, 2) +
                times(PLUS_TEN.id, 3) + times(PLUS_FOUR.id, 5),
        ),
    )

    val GAMBLER = DeckPreset(
        id = "gambler",
        name = "Gambler",
        description = "coin flips, slots & high-risk plays",
        deck = DeckConfig(
            numberCards = flip7Numbers(13),
            actionCards = times(COIN_FLIP.id, 3) + times(SLOTS.id, 3) + times(DRAW_THREE.id, 2) +
                times(JUST_ONE_MORE.id, 2) + times(MUTATE.id, 2),
            passiveCards = listOf(SECOND_LIFE.id) + times(DOUBLE_POINTS.id, 2) + times(PLUS_TEN.id, 2),
        ),
    )

    val FRIENDLY = DeckPreset(
        id = "friendly",
        name = "Friendly",
        description = "numbers + passive bonuses, no attacks",
        deck = DeckConfig(
            numberCards = flip7Numbers(13),
            passiveCards = times(SECOND_LIFE.id, 2) + listOf(DOUBLE_POINTS.id) +
                times(PLUS_SIX.id, 2) + times(PLUS_TEN.id, 3) + times(PLUS_FOUR.id, 5),
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
