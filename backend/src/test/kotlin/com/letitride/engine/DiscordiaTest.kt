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
    fun `a round that only cost points never puts anybody in the red`() {
        // b's hand is worth 2 and the toll is 10; without "extreme" the round
        // bottoms out at nothing rather than taking the difference off the
        // scoreboard.
        var state = holding(withPending(FREEZE.id), "b", DISCORDIA.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = t(state, GameAction.Stay("a"))

        assertEquals(0, state.roundDeltas["b"])
        assertEquals(0, state.player("b")!!.score)
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
