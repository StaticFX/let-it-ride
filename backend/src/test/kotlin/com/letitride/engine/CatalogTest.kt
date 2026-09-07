package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {
    /**
     * Nothing in the game carries which catalog its definition came from. A card
     * on the table is a `defId` and a kind, the testing panel stacks a card by
     * bare name, and `Catalog.action`/`passive`/`gambler` are each asked in turn
     * by whatever is nearest. Two cards sharing an id would therefore be the
     * same card in some places and different cards in others, which is the kind
     * of bug that only shows up as a card behaving oddly on one table.
     */
    @Test
    fun `no two cards anywhere share an id`() {
        val ids = Catalog.actions.keys.toList() + Catalog.passives.keys + Catalog.gamblers.keys
        val duplicated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicated.isEmpty(), "these ids are defined in more than one catalog: $duplicated")
    }

    @Test
    fun `every gambler card is registered under its own id`() {
        for ((id, def) in Catalog.gamblers) assertEquals(id, def.id)
    }

    /**
     * A card that can never be played is a card that sits in a hand taking up a
     * slot for ever. The passive window is the one exception, and it is an
     * exception on purpose: those work by being held.
     */
    @Test
    fun `a gambler card either has an effect or works by being held`() {
        for (def in GamblerCatalog.all) {
            if (def.window == PlayWindow.PASSIVE) continue
            val ctx = Ctx(started(config(), listOf("a", "b")), Rng(1))
            val player = ctx.player("a")!!
            // Not a test of what the card does — only that somebody wrote one.
            def.onPlay(ctx, Play(from = player, target = player))
        }
    }

    /** Every price the shop and the dealer will quote has to be a real number. */
    @Test
    fun `every gambler card is priced`() {
        for (def in GamblerCatalog.all) {
            assertTrue(def.price > 0, "${def.id} has no price, so the shop cannot sell it")
            assertTrue(def.cost >= 0, "${def.id} pays its player to play it")
        }
    }
}
