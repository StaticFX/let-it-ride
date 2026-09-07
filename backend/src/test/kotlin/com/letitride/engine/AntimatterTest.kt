package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Antimatter: every number in front of you counts the wrong way, you may not
 * stop, and busting is not a way out either. The card is all three at once — a
 * hand worth minus something is a hand you would put down immediately, so it
 * does not let you, and a bust that wiped the debt would be that same stop by
 * another name.
 */
class AntimatterTest {

    private fun holding(state: GameState, id: String, defId: String = ANTIMATTER.id): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) it.copy(passives = it.passives + passive(defId, id = "p-$defId-$id")) else it
        },
    )

    private fun playerWith(hand: List<Card>, passives: List<Card>) = Player(
        id = "a", name = "a", hand = hand, handValue = hand.sumOf { it.value }, passives = passives,
    )

    // ─── Scoring ───

    @Test
    fun `every number card counts against its holder`() {
        val player = playerWith(listOf(num(13), num(5)), listOf(passive(ANTIMATTER.id)))
        assertEquals(-18, Engine.roundScore(player, flip7PlayerId = null))
    }

    @Test
    fun `the double doubles the hole rather than digging a second one`() {
        val player = playerWith(
            listOf(num(13), num(5)),
            listOf(passive(ANTIMATTER.id), passive(DOUBLE_POINTS.id)),
        )
        assertEquals(-36, Engine.roundScore(player, flip7PlayerId = null))
    }

    @Test
    fun `a flat bonus fills a little of it back in`() {
        val player = playerWith(
            listOf(num(13), num(5)),
            listOf(passive(ANTIMATTER.id), passive(PLUS_TEN.id)),
        )
        assertEquals(-8, Engine.roundScore(player, flip7PlayerId = null))
    }

    @Test
    fun `two of them do not cancel out`() {
        val player = playerWith(
            listOf(num(13)),
            listOf(passive(ANTIMATTER.id, id = "one"), passive(ANTIMATTER.id, id = "two")),
        )
        assertEquals(-13, Engine.roundScore(player, flip7PlayerId = null))
    }

    // ─── Busting is not a way out ───

    @Test
    fun `busting out of it still costs them the hand`() {
        // A bust that wiped the debt would hand the stop back under another
        // name: draw until the duplicate comes and walk away owing nothing,
        // which is a better round than the one the card was pushing them into.
        val player = playerWith(listOf(num(13)), listOf(passive(ANTIMATTER.id)))
            .copy(status = PlayerStatus.BUST)
        assertEquals(-13, Engine.roundScore(player, flip7PlayerId = null))
    }

    @Test
    fun `everybody else's bust is written off exactly as it always was`() {
        val player = playerWith(listOf(num(13)), emptyList()).copy(status = PlayerStatus.BUST)
        assertEquals(0, Engine.roundScore(player, flip7PlayerId = null))
    }

    @Test
    fun `the card that busted them is one of the cards counted`() {
        // The duplicate lands in the hand before the bust is called, which is
        // what makes being made to draw on get worse rather than being a way out.
        var state = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(9), num(4)),
            rest = listOf(num(9, id = "the-duplicate")),
        )
        state = holding(state, "a")
        state = state.copy(players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it })

        state = t(state, GameAction.Hit("a"))

        assertEquals(PlayerStatus.BUST, state.status("a"))
        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(-18, state.roundDeltas["a"], "both nines, the one that killed them included")
    }

    @Test
    fun `the bust comes off the scoreboard on any table`() {
        // The floor is lifted for its holder whether or not "extreme" is on, and
        // a busted holder is not a special case of that — it is the same round.
        var state = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(6), num(4)),
            rest = listOf(num(6, id = "the-duplicate")),
        )
        state = holding(state, "a")
        state = state.copy(
            players = state.players.map {
                when (it.id) {
                    "a" -> it.copy(score = 30)
                    else -> it.copy(status = PlayerStatus.STAYED)
                }
            },
        )

        state = t(state, GameAction.Hit("a"))

        assertEquals(-12, state.roundDeltas["a"])
        assertEquals(18, state.player("a")!!.score)
    }

    // ─── The floor ───

    @Test
    fun `the round comes off the scoreboard without extreme being on`() {
        // Every other bad round is rounded up to nothing. A card whose whole
        // claim is that a 13 is worth minus thirteen has to mean it.
        var state = startedAndDealt(openingCards = listOf(num(9), num(4)))
        state = holding(state, "a")
        state = state.copy(players = state.players.map { if (it.id == "a") it.copy(score = 50) else it })
        // Somebody froze them; it is the only way out of a card that refuses to
        // stop, and the round closes on the seat that is still holding it.
        state = state.copy(players = state.players.map { it.copy(status = PlayerStatus.STAYED) })
        state = t(state, GameAction.ForcedDraw)

        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(-9, state.roundDeltas["a"])
        assertEquals(41, state.player("a")!!.score)
    }

    @Test
    fun `nobody else's round is lifted off the floor by it`() {
        var state = startedAndDealt(players = listOf("a", "b"), openingCards = listOf(num(9), num(4)))
        state = holding(state, "a")
        state = state.copy(
            players = state.players.map {
                // b spent more on tolls than the round was worth; the floor still
                // catches them, because the card is not theirs.
                if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it.copy(status = PlayerStatus.STAYED)
            },
            roundAdjustments = mapOf("b" to -40),
        )
        state = t(state, GameAction.ForcedDraw)

        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(0, state.roundDeltas["b"])
        assertEquals(-9, state.roundDeltas["a"])
    }

    // ─── Not being allowed to stop ───

    @Test
    fun `its holder cannot go out`() {
        var state = startedAndDealt(openingCards = listOf(num(4), num(6)))
        state = holding(state, "a")

        assertFalse(Engine.canStay(state, state.player("a")!!))
        assertTrue(Engine.canStay(state, state.player("b")!!), "and nobody else is affected")

        val refused = t(state, GameAction.Stay("a"))
        assertEquals(state, refused, "the table simply does not take it")
    }

    @Test
    fun `a freeze still sends them out — being sent is not choosing`() {
        var state = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(4), num(6)),
            rest = listOf(action(FREEZE.id)),
        )
        state = holding(state, "b")
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))

        assertEquals(PlayerStatus.STAYED, state.status("b"), "somebody did them a favour")
    }

    @Test
    fun `an empty hand is still timed out the way it always was`() {
        // The clock only draws for somebody a card is holding down. A player who
        // has simply not drawn anything yet goes out on the clock as before.
        var state = startedAndDealt(openingCards = listOf(num(4), num(6)), rest = listOf(num(7)))
        state = state.copy(
            players = state.players.map { if (it.id == "a") it.copy(hand = emptyList(), handValue = 0) else it },
        )

        state = t(state, GameAction.Timeout("a"))

        assertEquals(PlayerStatus.STAYED, state.status("a"))
    }

    @Test
    fun `the clock draws for them rather than letting them out`() {
        // Waiting quietly must not be a way off a card that says you cannot stop.
        var state = startedAndDealt(openingCards = listOf(num(4), num(6)), rest = listOf(num(7, id = "next")))
        state = holding(state, "a")

        state = t(state, GameAction.Timeout("a"))

        assertEquals(PlayerStatus.ACTIVE, state.status("a"))
        assertTrue(state.hand("a").any { it.id == "next" }, "the clock took the only decision they had left")
    }

    @Test
    fun `giving it away gives the choice back`() {
        var state = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(4), num(6)),
            rest = listOf(action(SWAP_CARDS.id)),
        )
        state = holding(state, "a")
        state = t(state, GameAction.Hit("a"))

        state = t(
            state,
            GameAction.PlayAction(
                "a", "a", SWAP_CARDS.id,
                cards = listOf("p-${ANTIMATTER.id}-a", state.hand("b").first().id),
            ),
        )

        assertFalse(state.player("a")!!.passives.any { it.defId == ANTIMATTER.id })
        assertTrue(state.player("b")!!.passives.any { it.defId == ANTIMATTER.id })
        assertTrue(Engine.canStay(state, state.player("a")!!), "and a is free to stop again")
        assertFalse(Engine.canStay(state, state.player("b")!!))
    }
}
