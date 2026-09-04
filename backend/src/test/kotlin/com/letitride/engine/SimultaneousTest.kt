package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two cards that ask more than one player at once, and the collection they
 * are built on: nothing resolves until everybody has answered, and nothing
 * anybody said leaves the server until it does.
 */
class SimultaneousTest {

    private fun scored(state: GameState, scores: Map<String, Int>): GameState = state.copy(
        players = state.players.map { it.copy(score = scores[it.id] ?: it.score) },
    )

    private fun handed(state: GameState, hands: Map<String, List<Card>>): GameState = state.copy(
        players = state.players.map { p ->
            val hand = hands[p.id] ?: return@map p
            p.copy(hand = hand, handValue = hand.sumOf { it.value })
        },
    )

    // ─── Comeback ───

    /** "a" is bottom, "c" is top, so the card is a's to use. */
    private fun trailing(): GameState {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(action(COMEBACK_ID)))
        return scored(dealt, mapOf("a" to 5, "b" to 40, "c" to 90))
    }

    @Test
    fun `it asks the leader as well, and neither answer resolves it alone`() {
        var state = t(trailing(), GameAction.Hit("a"))

        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PHASE_THROW, pending.phase)
        assertEquals(setOf("a", "c"), pending.respondents.toSet(), "the leader has to throw too")

        state = t(state, GameAction.PlayAction("a", "a", COMEBACK_ID, choice = THROW_PAPER))
        assertNotNull(state.pendingAction, "one throw is not a game")
        assertEquals(listOf("c"), state.pendingAction!!.waitingOn)
        assertEquals(90, state.player("c")!!.score, "nothing has happened yet")
    }

    @Test
    fun `winning the throw trades the two scores`() {
        var state = t(trailing(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", COMEBACK_ID, choice = THROW_PAPER))
        val result = tr(state, GameAction.PlayAction("c", "a", COMEBACK_ID, choice = THROW_ROCK))
        state = result.state

        assertNull(state.pendingAction)
        assertEquals(90, state.player("a")!!.score)
        assertEquals(5, state.player("c")!!.score)
        val throws = result.events.filterIsInstance<GameEvent.Throws>().single()
        assertTrue(throws.challengerWon)
        assertEquals(THROW_PAPER, throws.challengerThrow)
        assertEquals(THROW_ROCK, throws.leaderThrow)
    }

    @Test
    fun `losing it changes nothing`() {
        var state = t(trailing(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", COMEBACK_ID, choice = THROW_ROCK))
        state = t(state, GameAction.PlayAction("c", "a", COMEBACK_ID, choice = THROW_PAPER))

        assertEquals(5, state.player("a")!!.score)
        assertEquals(90, state.player("c")!!.score)
    }

    @Test
    fun `a draw is a draw`() {
        var state = t(trailing(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", COMEBACK_ID, choice = THROW_ROCK))
        val result = tr(state, GameAction.PlayAction("c", "a", COMEBACK_ID, choice = THROW_ROCK))

        assertEquals(5, result.state.player("a")!!.score)
        assertFalse(result.events.filterIsInstance<GameEvent.Throws>().single().challengerWon)
        assertNull(result.state.pendingAction, "a draw settles it rather than going round again")
    }

    @Test
    fun `anybody but the trailing player wastes it`() {
        val dealt = startedAndDealt(
            players = listOf("a", "b", "c"),
            rest = listOf(action(COMEBACK_ID), num(11)),
        )
        // "a" is top now, so the card is not theirs to use.
        val result = tr(scored(dealt, mapOf("a" to 90, "b" to 40, "c" to 5)), GameAction.Hit("a"))

        assertNull(result.state.pendingAction)
        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == COMEBACK_ID })
        assertNotNull(result.state.forcedDraws, "the drawer is owed a replacement")
    }

    @Test
    fun `a tie for last means nobody is in last`() {
        val dealt = startedAndDealt(
            players = listOf("a", "b", "c"),
            rest = listOf(action(COMEBACK_ID), num(11)),
        )
        val result = tr(scored(dealt, mapOf("a" to 5, "b" to 5, "c" to 90)), GameAction.Hit("a"))

        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == COMEBACK_ID })
    }

    @Test
    fun `a clock that runs out throws for whoever is missing`() {
        var state = t(trailing(), GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "a", COMEBACK_ID, choice = THROW_PAPER))
        assertNotNull(state.pendingAction)

        state = t(state, GameAction.Timeout("a"))

        assertNull(state.pendingAction, "one player walking away must not hold the table")
    }

    // ─── All in ───

    /** Four players, each holding one distinct card, and "a" about to draw it. */
    private fun betting(): GameState {
        val dealt = startedAndDealt(
            players = listOf("a", "b", "c", "d"),
            rest = listOf(action(ALL_IN_ID)),
        )
        return handed(
            dealt,
            mapOf(
                "a" to listOf(num(2, id = "a-2")),
                "b" to listOf(num(9, id = "b-9")),
                "c" to listOf(num(5, id = "c-5")),
                "d" to listOf(num(7, id = "d-7")),
            ),
        )
    }

    private fun bet(state: GameState, playerId: String, cardId: String) =
        t(state, GameAction.PlayAction(playerId, playerId, ALL_IN_ID, cards = listOf(cardId)))

    @Test
    fun `it asks everybody holding a hand`() {
        val state = t(betting(), GameAction.Hit("a"))

        val pending = state.pendingAction
        assertNotNull(pending)
        assertEquals(PHASE_BET, pending.phase)
        assertEquals(PickKind.CARD, pending.kind)
        assertEquals(setOf("a", "b", "c", "d"), pending.respondents.toSet())
        assertEquals(1, pending.picks, "one card each")
    }

    @Test
    fun `nothing resolves until the last bet is in`() {
        var state = t(betting(), GameAction.Hit("a"))
        state = bet(state, "a", "a-2")
        state = bet(state, "b", "b-9")
        state = bet(state, "c", "c-5")

        assertNotNull(state.pendingAction, "one player has not bet yet")
        assertTrue(state.players.none { HALVED.id in it.marks })
    }

    @Test
    fun `the highest and the lowest bet both score half`() {
        var state = t(betting(), GameAction.Hit("a"))
        state = bet(state, "a", "a-2")
        state = bet(state, "b", "b-9")
        state = bet(state, "c", "c-5")
        val result = tr(state, GameAction.PlayAction("d", "d", ALL_IN_ID, cards = listOf("d-7")))
        state = result.state

        assertNull(state.pendingAction)
        assertTrue(HALVED.id in state.player("a")!!.marks, "2 was the lowest")
        assertTrue(HALVED.id in state.player("b")!!.marks, "9 was the highest")
        assertFalse(HALVED.id in state.player("c")!!.marks)
        assertFalse(HALVED.id in state.player("d")!!.marks)

        val revealed = result.events.filterIsInstance<GameEvent.AllIn>().single()
        assertEquals(4, revealed.bets.size, "every bet turns over at once")
        assertEquals(setOf("a", "b"), revealed.halvedIds.toSet())
    }

    @Test
    fun `a halved hand is worth half of everything it made`() {
        val hand = listOf(num(10), num(9), num(3))
        val player = Player(id = "a", name = "a", hand = hand, marks = setOf(HALVED.id))
        assertEquals(11, Engine.roundScore(player, flip7PlayerId = null), "22 halved, rounded down")
        assertEquals(22, Engine.roundScore(player.copy(marks = emptySet()), flip7PlayerId = null))
    }

    @Test
    fun `everybody tied at an end pays`() {
        var state = t(betting(), GameAction.Hit("a"))
        state = handed(state, mapOf("d" to listOf(num(9, id = "d-9"))))
        state = bet(state, "a", "a-2")
        state = bet(state, "b", "b-9")
        state = bet(state, "c", "c-5")
        state = bet(state, "d", "d-9")

        assertTrue(HALVED.id in state.player("b")!!.marks)
        assertTrue(HALVED.id in state.player("d")!!.marks, "tied for highest, and both pay")
        assertFalse(HALVED.id in state.player("c")!!.marks)
    }

    @Test
    fun `a bet that is not the player's own falls back to one that is`() {
        var state = t(betting(), GameAction.Hit("a"))
        // "a" tries to bet somebody else's card; the reckoning uses their own.
        state = bet(state, "a", "b-9")
        state = bet(state, "b", "b-9")
        state = bet(state, "c", "c-5")
        state = bet(state, "d", "d-7")

        assertTrue(HALVED.id in state.player("a")!!.marks, "a's own 2 was still the lowest")
        assertTrue(HALVED.id in state.player("b")!!.marks)
    }

    @Test
    fun `two bettors is not a bet`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(action(ALL_IN_ID), num(11)))
        val result = tr(dealt, GameAction.Hit("a"))

        assertNull(result.state.pendingAction, "the same player would be both ends of it")
        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == ALL_IN_ID })
    }

    // ─── The collection itself ───

    @Test
    fun `nobody may answer twice, or answer for somebody else`() {
        var state = t(betting(), GameAction.Hit("a"))
        state = bet(state, "a", "a-2")

        assertEquals(state, bet(state, "a", "a-2"), "a second answer from the same player is ignored")

        // "a" cannot get "b" out of the way by answering as them.
        val forged = t(state, GameAction.PlayAction("b", "b", ALL_IN_ID, cards = listOf("b-9")))
        assertTrue("b" in forged.pendingAction?.answers.orEmpty().keys, "the engine cannot tell who sent it")
        // ...which is the room's job, and it checks the socket the frame came in on.
    }

    @Test
    fun `an answer is held on the player who gave it, and only there`() {
        var state = t(betting(), GameAction.Hit("a"))
        state = bet(state, "a", "a-2")

        val answers = state.pendingAction!!.answers
        assertEquals(setOf("a"), answers.keys)
        assertEquals(listOf("a-2"), answers.getValue("a").cards)
        // What leaves the server is checked where the wire shape is — see
        // PendingActionViewTest, "a simultaneous prompt sends who has answered".
    }
}
