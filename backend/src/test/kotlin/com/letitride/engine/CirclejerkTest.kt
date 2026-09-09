package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two cards that go round the table rather than across it: one hands a card
 * to each neighbour, the other takes one from each. What is interesting about
 * them is who counts as a neighbour, and which end of the table does the
 * choosing.
 */
class CirclejerkTest {

    /**
     * Deals everyone a known opening card, puts anything in [extra] in front of
     * "a", and has them turn [defId] over.
     *
     * [extra] is what a second turn would have left them holding, without the
     * turn order having to come all the way round again — a number card goes in
     * the hand and anything else in the row, exactly as it would have.
     */
    private fun drawn(
        defId: String,
        players: List<String> = listOf("a", "b", "c", "d"),
        openingCards: List<Card> = players.indices.map { num(it + 1) },
        extra: List<Card> = emptyList(),
    ): GameState {
        val dealt = startedAndDealt(players = players, openingCards = openingCards, rest = listOf(action(defId)))
        val stocked = if (extra.isEmpty()) dealt else stock(dealt, "a", extra)
        return t(stocked, GameAction.Hit("a"))
    }

    private fun stock(state: GameState, playerId: String, cards: List<Card>): GameState = state.copy(
        players = state.players.map { p ->
            if (p.id != playerId) return@map p
            val hand = p.hand + cards.filter { it.kind == CardKind.NUMBER }
            p.copy(
                hand = hand,
                passives = p.passives + cards.filterNot { it.kind == CardKind.NUMBER },
                handValue = hand.sumOf { it.value },
            )
        },
    )

    private fun emptyHanded(state: GameState, playerId: String): GameState = state.copy(
        players = state.players.map {
            if (it.id == playerId) it.copy(hand = emptyList(), passives = emptyList(), handValue = 0) else it
        },
    )

    private fun handOf(state: GameState, id: String) = state.hand(id).map { it.label }

    // ─── Who is next to you ───

    @Test
    fun `your neighbours are the seats either side of you, left first`() {
        val state = startedAndDealt(players = listOf("a", "b", "c", "d"))
        assertEquals(listOf("b", "d"), neighboursOf(state, "a"))
        assertEquals(listOf("c", "a"), neighboursOf(state, "b"))
        assertEquals(listOf("a", "c"), neighboursOf(state, "d"))
    }

    @Test
    fun `at a table of two there is one of them, not the same player twice`() {
        val state = startedAndDealt(players = listOf("a", "b"))
        assertEquals(listOf("b"), neighboursOf(state, "a"))
    }

    @Test
    fun `at a table of three both neighbours are still two different people`() {
        val state = startedAndDealt(players = listOf("a", "b", "c"))
        assertEquals(listOf("b", "c"), neighboursOf(state, "a"))
    }

    // ─── Circlejerk ───

    @Test
    fun `it stops the table and asks its drawer which of their own cards to give`() {
        val state = drawn(CIRCLEJERK_ID, extra = listOf(num(9)))

        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PHASE_GIVE, pending.phase)
        assertEquals(PickKind.CARD, pending.kind)
        assertEquals(listOf("a"), pending.respondents, "nobody else is asked what you give away")
        assertEquals(state.hand("a").map { it.id }, pending.validCards, "only your own cards are on offer")
    }

    @Test
    fun `both picks may come off the one hand, which is the whole card`() {
        val state = drawn(CIRCLEJERK_ID, extra = listOf(num(9)))
        assertEquals(2, state.pendingAction?.picks)
        assertEquals(false, state.pendingAction?.oneCardPerSeat)
    }

    @Test
    fun `the picks go round in seat order — the seat on your left first`() {
        var state = drawn(CIRCLEJERK_ID, extra = listOf(num(9)))
        val mine = state.hand("a").map { it.id }
        assertEquals(2, mine.size)

        state = t(state, GameAction.PlayAction("a", "a", CIRCLEJERK_ID, cards = mine))

        assertTrue("1" in handOf(state, "b"), "the first pick went to the seat on the left")
        assertTrue("9" in handOf(state, "d"), "and the second to the seat on the right")
        assertTrue(state.hand("a").isEmpty(), "both cards left")
    }

    @Test
    fun `a modifier can be given away, and lands in the row rather than the hand`() {
        var state = drawn(CIRCLEJERK_ID, players = listOf("a", "b"), extra = listOf(passive(DISCORDIA.id)))
        assertTrue("p-${DISCORDIA.id}" in state.pendingAction!!.validCards, "the row is yours to give too")

        state = t(state, GameAction.PlayAction("a", "a", CIRCLEJERK_ID, cards = listOf("p-${DISCORDIA.id}")))

        assertTrue(state.player("b")!!.passives.any { it.defId == DISCORDIA.id }, "the curse found a new home")
        assertTrue(state.player("a")!!.passives.isEmpty())
        assertTrue(state.hand("b").none { it.defId == DISCORDIA.id }, "a modifier is not a card in a hand")
    }

    @Test
    fun `a duplicate handed to a neighbour busts them`() {
        var state = drawn(
            CIRCLEJERK_ID,
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(5, id = "n-b-5")),
            extra = listOf(num(5, id = "n-a-5")),
        )

        state = t(state, GameAction.PlayAction("a", "a", CIRCLEJERK_ID, cards = listOf("n-a-5")))

        assertEquals(PlayerStatus.BUST, state.status("b"))
    }

    @Test
    fun `a neighbour who has already banked can still be busted by one`() {
        var state = drawn(
            CIRCLEJERK_ID,
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(5, id = "n-b-5"), num(3)),
            extra = listOf(num(5, id = "n-a-5")),
        )
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it },
        )

        state = t(state, GameAction.PlayAction("a", "a", CIRCLEJERK_ID, cards = listOf("n-a-5", "n-1-1")))

        assertEquals(PlayerStatus.BUST, state.status("b"), "a banked hand is still a hand")
    }

    @Test
    fun `holding one card at a table of four gives one card away rather than fizzling`() {
        val state = drawn(CIRCLEJERK_ID)
        assertEquals(1, state.pendingAction?.picks, "one card, one neighbour, no complaint")

        val after = t(state, GameAction.PlayAction("a", "a", CIRCLEJERK_ID, cards = state.hand("a").map { it.id }))
        assertTrue("1" in handOf(after, "b"))
        assertTrue(after.hand("a").isEmpty())
    }

    @Test
    fun `with nothing in front of you it fizzles and deals you another card`() {
        // The opening deal, where it actually happens: the card is the first
        // thing "a" turns over and there is nothing to give away.
        val result = tr(
            started(players = listOf("a", "b"), deck = listOf(action(CIRCLEJERK_ID), num(4), num(6))),
            GameAction.DealTo("a"),
        )

        assertNull(result.state.pendingAction)
        assertTrue(result.events.filterIsInstance<GameEvent.Fizzled>().any { it.cardDefId == CIRCLEJERK_ID })
        assertEquals("a", result.state.forcedDraws?.playerId, "the drawer is owed a card in its place")
    }

    @Test
    fun `an answer naming cards that are not yours falls back to ones that are`() {
        var state = drawn(CIRCLEJERK_ID, players = listOf("a", "b"))
        val theirs = state.hand("b").single().id

        state = t(state, GameAction.PlayAction("a", "a", CIRCLEJERK_ID, cards = listOf(theirs)))

        assertEquals(listOf("2", "1"), handOf(state, "b"), "b kept their own card and gained a's")
        assertTrue(state.hand("a").isEmpty())
    }

    // ─── Reverse circlejerk ───

    @Test
    fun `it asks both neighbours, and neither answer resolves it alone`() {
        var state = drawn(REVERSE_CIRCLEJERK_ID)

        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PHASE_HANDOVER, pending.phase)
        assertEquals(setOf("b", "d"), pending.respondents.toSet(), "the seats either side, and nobody else")

        val fromB = state.hand("b").single().id
        state = t(state, GameAction.PlayAction("b", "a", REVERSE_CIRCLEJERK_ID, cards = listOf(fromB)))
        assertNotNull(state.pendingAction, "one hand-over is not the card")
        assertEquals(listOf("d"), state.pendingAction!!.waitingOn)
        assertEquals(1, state.hand("b").size, "nothing has moved yet")
    }

    @Test
    fun `each neighbour picks their own card and it lands on the drawer`() {
        var state = drawn(REVERSE_CIRCLEJERK_ID)
        state = t(
            state,
            GameAction.PlayAction("b", "a", REVERSE_CIRCLEJERK_ID, cards = listOf(state.hand("b").single().id)),
        )
        state = t(
            state,
            GameAction.PlayAction("d", "a", REVERSE_CIRCLEJERK_ID, cards = listOf(state.hand("d").single().id)),
        )

        assertNull(state.pendingAction)
        assertEquals(listOf("1", "2", "4"), handOf(state, "a"))
        assertEquals(7, state.player("a")!!.handValue)
        assertTrue(state.hand("b").isEmpty())
        assertTrue(state.hand("d").isEmpty())
    }

    @Test
    fun `a neighbour handing over a duplicate busts the player who asked for it`() {
        var state = drawn(
            REVERSE_CIRCLEJERK_ID,
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(5, id = "n-a-5"), num(5, id = "n-b-5"), num(3)),
        )
        state = t(state, GameAction.PlayAction("b", "a", REVERSE_CIRCLEJERK_ID, cards = listOf("n-b-5")))
        state = t(
            state,
            GameAction.PlayAction("c", "a", REVERSE_CIRCLEJERK_ID, cards = listOf(state.hand("c").single().id)),
        )

        assertEquals(PlayerStatus.BUST, state.status("a"), "you asked for it")
    }

    @Test
    fun `a neighbour who names somebody else's card gives one of their own instead`() {
        var state = drawn(REVERSE_CIRCLEJERK_ID, players = listOf("a", "b", "c"))
        val notTheirs = state.hand("c").single().id

        state = t(state, GameAction.PlayAction("b", "a", REVERSE_CIRCLEJERK_ID, cards = listOf(notTheirs)))
        state = t(state, GameAction.PlayAction("c", "a", REVERSE_CIRCLEJERK_ID, cards = listOf(notTheirs)))

        assertEquals(listOf("1", "2", "3"), handOf(state, "a"))
        assertTrue(state.hand("b").isEmpty())
        assertTrue(state.hand("c").isEmpty())
    }

    @Test
    fun `a neighbour with nothing in front of them is not asked`() {
        val dealt = emptyHanded(
            startedAndDealt(players = listOf("a", "b", "c", "d"), rest = listOf(action(REVERSE_CIRCLEJERK_ID))),
            "d",
        )
        val state = t(dealt, GameAction.Hit("a"))

        assertEquals(listOf("b"), state.pendingAction?.respondents, "there is nothing to ask an empty seat for")
    }

    @Test
    fun `with nobody next to you holding anything it fizzles`() {
        val dealt = emptyHanded(
            startedAndDealt(players = listOf("a", "b"), rest = listOf(action(REVERSE_CIRCLEJERK_ID))),
            "b",
        )
        val result = tr(dealt, GameAction.Hit("a"))

        assertNull(result.state.pendingAction)
        assertTrue(result.events.filterIsInstance<GameEvent.Fizzled>().any { it.cardDefId == REVERSE_CIRCLEJERK_ID })
        assertEquals("a", result.state.forcedDraws?.playerId)
    }

    @Test
    fun `a clock that runs out still hands something over, from everybody at once`() {
        var state = drawn(REVERSE_CIRCLEJERK_ID, players = listOf("a", "b", "c"))
        state = t(state, GameAction.Timeout("a"))

        assertNull(state.pendingAction, "one clock covers the whole prompt")
        assertEquals(3, state.hand("a").size)
        assertTrue(state.hand("b").isEmpty())
        assertTrue(state.hand("c").isEmpty())
    }

    // ─── Both of them ───

    @Test
    fun `neither card creates or destroys one`() {
        for (defId in listOf(CIRCLEJERK_ID, REVERSE_CIRCLEJERK_ID)) {
            var state = drawn(defId, extra = listOf(num(9)))
            val before = state.allCardIds().sorted()
            val pending = state.pendingAction!!
            for (responder in pending.respondents) {
                val own = state.player(responder)!!.let { it.hand + it.passives }.map { c -> c.id }
                state = t(state, GameAction.PlayAction(responder, "a", defId, cards = own.take(pending.picks)))
            }
            assertEquals(before, state.allCardIds().sorted(), defId)
        }
    }
}
