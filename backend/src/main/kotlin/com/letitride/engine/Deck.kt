package com.letitride.engine

import kotlin.random.Random

/** Seeded so a room's shuffles are reproducible from its seed alone. */
class Rng(seed: Long) {
    private val random = Random(seed)

    fun nextInt(bound: Int): Int = random.nextInt(bound)

    fun nextBoolean(): Boolean = random.nextBoolean()

    fun nextLong(): Long = random.nextLong()

    fun <T> shuffled(items: List<T>): List<T> = items.shuffled(random)

    fun <T> pick(items: List<T>): T? = if (items.isEmpty()) null else items[random.nextInt(items.size)]
}

object Deck {
    fun build(config: DeckConfig): List<Card> {
        val cards = mutableListOf<Card>()
        var uid = 0

        for (entry in config.numberCards) {
            val label = entry.label ?: entry.value.toString()
            repeat(entry.count) { i ->
                cards += Card(
                    id = "n-${uid++}",
                    kind = CardKind.NUMBER,
                    label = label,
                    value = entry.value,
                    suit = entry.suits?.let { it[i % it.size] },
                )
            }
        }

        for (defId in config.actionCards) {
            val def = Catalog.action(defId) ?: continue
            cards += Card(id = "a-${uid++}", kind = CardKind.ACTION, label = def.name, value = 0, defId = def.id)
        }

        for (defId in config.passiveCards) {
            val def = Catalog.passive(defId) ?: continue
            cards += Card(id = "p-${uid++}", kind = CardKind.PASSIVE, label = def.name, value = 0, defId = def.id)
        }

        return cards
    }

    fun size(config: DeckConfig): Int =
        config.numberCards.sumOf { it.count } +
            config.actionCards.count { Catalog.action(it) != null } +
            config.passiveCards.count { Catalog.passive(it) != null }
}
