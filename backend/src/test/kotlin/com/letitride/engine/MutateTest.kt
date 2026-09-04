package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Buying a card out of your own score. The only card that reaches past what has
 * been dealt into what the deck merely *holds*, so most of what matters is what
 * it is allowed to offer and what it charges.
 */
class MutateTest {

    /** A deck with a couple of numbers and a couple of modifiers to shop from. */
    private val shopDeck = DeckConfig(
        numberCards = listOf(
            NumberCardEntry(value = 1, count = 4, label = "1"),
            NumberCardEntry(value = 12, count = 4, label = "12"),
        ),
        actionCards = listOf(MUTATE_ID),
        passiveCards = listOf(PLUS_TWO.id, DOUBLE_POINTS.id),
    )

    private fun shopping(score: Int, players: List<String> = listOf("a", "b")): GameState {
        val dealt = startedAndDealt(
            config = config(deck = shopDeck),
            players = players,
            rest = listOf(action(MUTATE_ID)),
        )
        val funded = dealt.copy(players = dealt.players.map { it.copy(score = score) })
        return t(funded, GameAction.Hit("a"))
    }

    private fun buy(state: GameState, offerId: String) =
        t(state, GameAction.PlayAction("a", "a", MUTATE_ID, cards = listOf(offerId)))

    @Test
    fun `it puts the deck up for sale, priced`() {
        val state = shopping(score = 100)

        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PickKind.CATALOG, pending.kind)
        assertEquals(PHASE_BUY, pending.phase)

        val byId = pending.offers.associateBy { it.id }
        assertEquals(setOf("num:1", "num:12", "passive:plus2", "passive:doublePoints"), byId.keys)
        assertEquals(6, byId.getValue("num:1").price, "a 1 is worth 1, plus five for choosing it")
        assertEquals(17, byId.getValue("num:12").price)
        assertEquals(PLUS_TWO.price, byId.getValue("passive:plus2").price)
    }

    @Test
    fun `only what this deck holds is on sale`() {
        val state = shopping(score = 500)
        val ids = state.pendingAction!!.offers.map { it.id }

        assertFalse("passive:secondLife" in ids, "this deck has never held a second life")
        assertFalse(ids.any { it.startsWith("num:7") }, "nor a 7")
    }

    @Test
    fun `only what the buyer can afford is on sale`() {
        val state = shopping(score = 10)
        val ids = state.pendingAction!!.offers.map { it.id }

        assertEquals(listOf("num:1", "passive:plus2"), ids, "the 12 and the ×2 are out of reach")
    }

    @Test
    fun `a player with nothing to spend cannot shop at all`() {
        val dealt = startedAndDealt(
            config = config(deck = shopDeck),
            players = listOf("a", "b"),
            rest = listOf(action(MUTATE_ID), num(9)),
        )
        val result = tr(dealt.copy(players = dealt.players.map { it.copy(score = 0) }), GameAction.Hit("a"))

        assertNull(result.state.pendingAction)
        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == MUTATE_ID })
        assertNotNull(result.state.forcedDraws, "the drawer is owed a replacement")
    }

    @Test
    fun `buying a number card puts it in the hand and takes the price off the round`() {
        var state = shopping(score = 100)
        val before = state.hand("a").size

        val result = tr(state, GameAction.PlayAction("a", "a", MUTATE_ID, cards = listOf("num:12")))
        state = result.state

        assertNull(state.pendingAction)
        assertEquals(before + 1, state.hand("a").size)
        assertEquals("12", state.hand("a").last().label)
        assertEquals(-17, state.roundAdjustments["a"])
        assertEquals(100, state.player("a")!!.score, "the scoreboard does not move until the round is scored")

        val bought = result.events.filterIsInstance<GameEvent.Bought>().single()
        assertEquals(17, bought.price)
    }

    @Test
    fun `buying a modifier puts it in the row in front`() {
        var state = shopping(score = 100)
        state = buy(state, "passive:doublePoints")

        val a = state.player("a")!!
        assertEquals(listOf(DOUBLE_POINTS.id), a.passives.map { it.defId })
        assertEquals(-DOUBLE_POINTS.price, state.roundAdjustments["a"])
    }

    @Test
    fun `a bought card is minted, not taken out of the deck`() {
        var state = shopping(score = 100)
        val before = state.allCardIds().toSet()
        val deckBefore = state.deck.size

        state = buy(state, "num:12")

        assertEquals(before, state.allCardIds().toSet(), "the deck lost a card to a purchase")
        assertEquals(deckBefore, state.deck.size)
        assertTrue(state.hand("a").last().isEphemeral)
    }

    @Test
    fun `the price comes out of the round, and the round can be spent down to nothing`() {
        var state = shopping(score = 100)
        state = buy(state, "num:12")
        // The purchase resolves the card, so the turn has already moved on.
        state = t(state, GameAction.Stay("b"))
        state = t(state, GameAction.Stay("a"))

        // The hand is the opening card plus the bought 12; the 17 comes off it,
        // and without "extreme" the round stops at zero rather than going under.
        assertEquals(0, state.roundDeltas["a"])
        assertEquals(100, state.player("a")!!.score)
    }

    @Test
    fun `under extreme a purchase can cost more than the round made`() {
        val dealt = startedAndDealt(
            config = config(deck = shopDeck, rules = listOf(LobbyRules.EXTREME.id)),
            players = listOf("a", "b"),
            rest = listOf(action(MUTATE_ID)),
        )
        var state = t(dealt.copy(players = dealt.players.map { it.copy(score = 100) }), GameAction.Hit("a"))
        state = buy(state, "num:12")
        state = t(state, GameAction.Stay("b"))
        state = t(state, GameAction.Stay("a"))

        val hand = 1 + 12
        assertEquals(hand - 17, state.roundDeltas["a"])
        assertEquals(100 + hand - 17, state.player("a")!!.score)
    }

    @Test
    fun `a purchase never puts the buyer under, whatever they ask for`() {
        // Everything on the list has to be payable out of what is left, so two
        // purchases in a round cannot add up to more than there was.
        var state = shopping(score = 20)
        state = buy(state, "num:12")
        assertEquals(-17, state.roundAdjustments["a"])

        // Three left to spend, and nothing is that cheap.
        val stillShopping = t(state.copy(deck = listOf(action(MUTATE_ID, id = "second"))), GameAction.Hit("a"))
        assertNull(stillShopping.pendingAction, "there was nothing left they could afford")
    }

    @Test
    fun `a pick that is not on the list falls back to one that is`() {
        var state = shopping(score = 10)
        state = buy(state, "passive:secondLife")

        assertNull(state.pendingAction)
        assertTrue(state.roundAdjustments["a"]!! < 0, "something was bought rather than nothing")
    }

    @Test
    fun `a clock that runs out still spends the money`() {
        var state = shopping(score = 100)
        assertNotNull(state.pendingAction)

        state = t(state, GameAction.Timeout("a"))

        assertNull(state.pendingAction)
        assertTrue(state.roundAdjustments["a"]!! < 0)
    }
}
