package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Discordia, and the point transfer underneath it: points changing hands in the
 * middle of a round rather than at the end of one.
 */
class DiscordiaTest {

    private fun withPending(
        defId: String,
        players: List<String> = listOf("a", "b"),
        openingCards: List<Card> = players.indices.map { num(it + 1) },
    ): GameState {
        val dealt = startedAndDealt(players = players, openingCards = openingCards, rest = listOf(action(defId)))
        return t(dealt, GameAction.Hit("a"))
    }

    private fun holding(state: GameState, id: String, defId: String): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) it.copy(passives = it.passives + passive(defId, id = "p-$defId-$id")) else it
        },
    )

    private fun scored(state: GameState, id: String): Int = state.roundAdjustments[id] ?: 0

    // ─── The toll ───

    @Test
    fun `playing a card on its holder takes points off them`() {
        var state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)

        val result = tr(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = result.state

        assertEquals(-DISCORDIA_TOLL, scored(state, "b"))
        assertEquals(DISCORDIA_TOLL, scored(state, "a"))
        val moved = result.events.filterIsInstance<GameEvent.PointsTransferred>().single()
        assertEquals("b", moved.fromPlayerId)
        assertEquals("a", moved.toPlayerId)
        assertEquals(DISCORDIA_TOLL, moved.points)
    }

    @Test
    fun `the card still does what it does`() {
        val state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)
        val after = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        assertEquals(PlayerStatus.STAYED, after.status("b"), "the toll is on top of the card, not instead of it")
    }

    @Test
    fun `a card played on yourself costs nothing`() {
        // "Womp womp" points every card at its drawer, so the holder of a
        // discordia would otherwise be charged for their own freeze.
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.WOMP_WOMP.id)),
            rest = listOf(action(FREEZE.id)),
        )
        val result = tr(holding(dealt, "a", DISCORDIA.id), GameAction.Hit("a"))

        assertTrue(result.events.filterIsInstance<GameEvent.PointsTransferred>().isEmpty())
        assertEquals(0, scored(result.state, "a"))
    }

    @Test
    fun `nothing is owed to somebody playing a card on a seat without one`() {
        val state = withPending(FREEZE.id)
        val result = tr(state, GameAction.PlayAction("a", "b", FREEZE.id))
        assertTrue(result.events.filterIsInstance<GameEvent.PointsTransferred>().isEmpty())
    }

    @Test
    fun `double it charges the toll once, however many times the effect fires`() {
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.DOUBLE_IT.id)),
            players = listOf("a", "b", "c"),
            rest = listOf(action(HEX.id)),
        )
        var state = holding(t(dealt, GameAction.Hit("a")), "b", DISCORDIA.id)

        val result = tr(state, GameAction.PlayAction("a", "b", HEX.id))
        state = result.state

        assertEquals(1, result.events.filterIsInstance<GameEvent.PointsTransferred>().size)
        assertEquals(-DISCORDIA_TOLL, scored(state, "b"))
    }

    @Test
    fun `two discordias cost twice as much`() {
        var state = withPending(FREEZE.id)
        state = holding(holding(state, "b", DISCORDIA.id), "b", DISCORDIA.id)
        state = state.copy(
            players = state.players.map {
                // Two copies, so the ids have to differ.
                if (it.id == "b") it.copy(passives = it.passives.mapIndexed { i, c -> c.copy(id = "d$i") }) else it
            },
        )

        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))

        assertEquals(-2 * DISCORDIA_TOLL, scored(state, "b"))
    }

    // ─── Getting rid of it ───

    @Test
    fun `it can be traded onto somebody else`() {
        var state = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(4), num(6)),
            rest = listOf(action(SWAP_CARDS.id)),
        )
        state = holding(state, "a", DISCORDIA.id)
        state = t(state, GameAction.Hit("a"))

        state = t(
            state,
            GameAction.PlayAction(
                "a", "a", SWAP_CARDS.id,
                cards = listOf("p-${DISCORDIA.id}-a", state.hand("b").first().id),
            ),
        )

        assertFalse(state.player("a")!!.passives.any { it.defId == DISCORDIA.id })
        assertTrue(state.player("b")!!.passives.any { it.defId == DISCORDIA.id })
    }

    @Test
    fun `nobody is ever offered one in the shop`() {
        val ctx = Ctx(
            started(config = config(deck = DeckPresets.CHAOS.deck)).copy(
                players = listOf(Player(id = "a", name = "a", score = 500)),
            ),
            testRng(),
        )
        assertTrue(DeckPresets.CHAOS.deck.passiveCards.contains(DISCORDIA.id), "the deck holds one to be offered")
        assertTrue(ctx.offersFor("a").none { it.id == offerIdForPassive(DISCORDIA.id) })
    }

    // ─── The transfer itself ───

    @Test
    fun `a transfer shows up on both sides of the summary`() {
        var state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = t(state, GameAction.Stay("a"))

        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(DISCORDIA_TOLL, state.roundAdjustments["a"])
        assertEquals(-DISCORDIA_TOLL, state.roundAdjustments["b"])
    }

    @Test
    fun `a toll is worth the same on a bad round as on a good one`() {
        // b's hand is worth 2 and the toll is 10. Every other way of losing
        // points bottoms out at nothing; this one does not, or "takes 10 points
        // off you" would mean ten points or two, whichever the round allowed.
        var state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = t(state, GameAction.Stay("a"))

        assertEquals(-8, state.roundDeltas["b"], "their 2, and 8 more")
        assertEquals(-8, state.player("b")!!.score)
        assertEquals(DISCORDIA_TOLL + 1, state.roundDeltas["a"], "and all ten of them landed")
    }

    @Test
    fun `the floor only comes down as far as what was taken`() {
        // A toll of ten cannot cost eleven. What a player earns still cannot put
        // them in the red — only what was taken from them can, and only that far.
        var state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        // b's hand is worth nothing at all: the toll is the whole of it.
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(hand = emptyList(), handValue = 0) else it },
        )
        state = t(state, GameAction.Stay("a"))

        assertEquals(-DISCORDIA_TOLL, state.roundDeltas["b"])
    }

    @Test
    fun `an anti-flip deduction still stops at nothing`() {
        // The floor is lifted by a toll, not by every way of losing points.
        var state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        // Something else took 40 off them as well; that part is still floored.
        state = state.copy(roundAdjustments = state.roundAdjustments + ("b" to -50))
        state = t(state, GameAction.Stay("a"))

        assertEquals(-DISCORDIA_TOLL, state.roundDeltas["b"], "the toll bites; the rest does not")
    }

    @Test
    fun `under extreme it comes off the scoreboard`() {
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.EXTREME.id)),
            openingCards = listOf(num(1), num(2)),
            rest = listOf(action(FREEZE.id)),
        )
        var state = holding(t(dealt, GameAction.Hit("a")), "b", DISCORDIA.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = t(state, GameAction.Stay("a"))

        assertEquals(2 - DISCORDIA_TOLL, state.roundDeltas["b"])
    }
}
