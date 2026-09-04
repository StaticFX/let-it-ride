package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first card that points at cards instead of a seat. Most of what is being
 * checked here is the pick itself — that the table stops for two of them, that
 * an illegal pair is replaced rather than refused, and that a card lands in the
 * pile it belongs in when it changes owner.
 */
class SwapCardsTest {

    /** Deals the swap card to "a" with everyone's hands set to something known. */
    private fun withSwap(
        players: List<String> = listOf("a", "b"),
        hands: Map<String, List<Card>> = mapOf("a" to listOf(num(3)), "b" to listOf(num(9))),
        passives: Map<String, List<Card>> = emptyMap(),
    ): GameState {
        val dealt = startedAndDealt(players = players, rest = listOf(action(SWAP_CARDS.id)))
        val seeded = dealt.copy(
            players = dealt.players.map { p ->
                val hand = hands[p.id] ?: p.hand
                p.copy(
                    hand = hand,
                    handValue = hand.sumOf { it.value },
                    passives = passives[p.id] ?: emptyList(),
                )
            },
        )
        return t(seeded, GameAction.Hit("a"))
    }

    private fun play(state: GameState, vararg cards: String) =
        t(state, GameAction.PlayAction("a", "a", SWAP_CARDS.id, cards = cards.toList()))

    @Test
    fun `the table stops for two cards rather than a seat`() {
        val state = withSwap()
        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PickKind.CARD, pending.kind)
        assertEquals(2, pending.picks)
        assertEquals(setOf("n-3-3", "n-9-9"), pending.validCards.toSet())
    }

    @Test
    fun `every card on the table is on offer, modifiers included`() {
        val state = withSwap(passives = mapOf("b" to listOf(passive(PLUS_FOUR.id))))
        assertTrue("p-${PLUS_FOUR.id}" in state.pendingAction!!.validCards)
    }

    @Test
    fun `two cards change owners`() {
        var state = withSwap()
        state = play(state, "n-3-3", "n-9-9")

        assertNull(state.pendingAction)
        assertEquals(listOf(9), state.hand("a").map { it.value })
        assertEquals(listOf(3), state.hand("b").map { it.value })
        assertEquals(9, state.player("a")!!.handValue)
        assertEquals(3, state.player("b")!!.handValue)
    }

    @Test
    fun `the swap is announced with both cards so the table can fly them past each other`() {
        val state = withSwap()
        val result = tr(state, GameAction.PlayAction("a", "a", SWAP_CARDS.id, cards = listOf("n-3-3", "n-9-9")))

        val swapped = result.events.filterIsInstance<GameEvent.CardsSwapped>().single()
        assertEquals("a", swapped.firstPlayerId)
        assertEquals("n-3-3", swapped.firstCard.id)
        assertEquals("b", swapped.secondPlayerId)
        assertEquals("n-9-9", swapped.secondCard.id)
    }

    @Test
    fun `handing somebody a card they already hold busts them`() {
        var state = withSwap(
            hands = mapOf(
                "a" to listOf(num(3, id = "a-3")),
                "b" to listOf(num(3, id = "b-3"), num(9, id = "b-9")),
            ),
        )
        // b gives up their 9 and takes a's 3 — on top of the 3 they already have.
        state = play(state, "b-9", "a-3")

        assertEquals(PlayerStatus.BUST, state.status("b"))
        assertEquals(PlayerStatus.ACTIVE, state.status("a"))
    }

    @Test
    fun `a modifier that changes owner lands in the row, not the hand`() {
        var state = withSwap(
            hands = mapOf("a" to listOf(num(3)), "b" to listOf(num(9))),
            passives = mapOf("a" to listOf(passive(PLUS_FOUR.id))),
        )
        state = play(state, "p-${PLUS_FOUR.id}", "n-9-9")

        val a = state.player("a")!!
        val b = state.player("b")!!
        assertEquals(listOf("n-3-3", "n-9-9"), a.hand.map { it.id })
        assertTrue(a.passives.isEmpty())
        assertTrue(b.hand.isEmpty(), "b gave up the only card in their hand")
        assertEquals(listOf("p-${PLUS_FOUR.id}"), b.passives.map { it.id })
        // A modifier in the row is worth nothing towards the hand's total.
        assertEquals(12, a.handValue)
        assertEquals(0, b.handValue)
    }

    @Test
    fun `a modifier moved across does not count towards the flip`() {
        var state = withSwap(
            players = listOf("a", "b"),
            hands = mapOf("a" to (1..6).map { num(it) }, "b" to listOf(num(9))),
            passives = mapOf("b" to listOf(passive(PLUS_FOUR.id))),
        )
        state = play(state, "n-9-9", "p-${PLUS_FOUR.id}")

        // a's hand is still six cards; the modifier sits beside it.
        assertEquals(6, state.hand("a").size)
        assertEquals(GamePhase.PLAYING, state.phase)
        assertNull(state.flip7PlayerId)
    }

    @Test
    fun `two picks off the same seat are replaced rather than refused`() {
        var state = withSwap(
            hands = mapOf("a" to listOf(num(3, id = "a-3"), num(4, id = "a-4")), "b" to listOf(num(9))),
        )
        // Both from a — the second is dropped and a legal one filled in, so the
        // card still resolves instead of stranding the table on a bad pick.
        state = play(state, "a-3", "a-4")

        assertNull(state.pendingAction)
        assertTrue("n-9-9" in state.hand("a").map { it.id }, "a took b's only card")
        assertEquals(1, state.hand("b").size)
    }

    @Test
    fun `a pick nobody is holding falls back to a legal one`() {
        var state = withSwap()
        state = play(state, "not-a-card", "also-not")

        assertNull(state.pendingAction)
        assertEquals(listOf(9), state.hand("a").map { it.value })
        assertEquals(listOf(3), state.hand("b").map { it.value })
    }

    @Test
    fun `it fizzles when only one player is holding anything`() {
        val dealt = startedAndDealt(rest = listOf(action(SWAP_CARDS.id), num(11)))
        val stripped = dealt.copy(
            players = dealt.players.map { if (it.id == "b") it.copy(hand = emptyList(), handValue = 0) else it },
        )
        val result = tr(stripped, GameAction.Hit("a"))

        assertNull(result.state.pendingAction)
        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == SWAP_CARDS.id })
        assertNotNull(result.state.forcedDraws, "the drawer is owed a replacement")
    }

    @Test
    fun `a clock that runs out still trades two cards`() {
        var state = withSwap()
        assertNotNull(state.pendingAction)

        state = t(state, GameAction.Timeout("a"))

        assertNull(state.pendingAction)
        assertFalse(state.hand("a").any { it.id == "n-3-3" }, "the swap went through on its own")
    }
}
