package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringTest {

    private fun playerWith(hand: List<Card>, passives: List<Card> = emptyList()) = Player(
        id = "a", name = "a", hand = hand, handValue = hand.sumOf { it.value }, passives = passives,
    )

    @Test
    fun `a plain hand scores its number cards`() {
        assertEquals(15, Engine.roundScore(playerWith(listOf(num(10), num(5))), null))
    }

    @Test
    fun `x2 doubles the number cards before flat modifiers are added`() {
        val player = playerWith(listOf(num(10), num(5)), listOf(passive(DOUBLE_POINTS.id), passive(PLUS_TEN.id)))
        // (10 + 5) * 2 + 10, not (10 + 5 + 10) * 2
        assertEquals(40, Engine.roundScore(player, null))
    }

    @Test
    fun `flat modifiers stack`() {
        val player = playerWith(
            listOf(num(3)),
            listOf(passive(PLUS_TWO.id), passive(PLUS_FOUR.id), passive(PLUS_SIX.id), passive(PLUS_EIGHT.id)),
        )
        assertEquals(3 + 2 + 4 + 6 + 8, Engine.roundScore(player, null))
    }

    @Test
    fun `flip 7 pays a flat fifteen on top and is not doubled`() {
        val player = playerWith(listOf(num(10)), listOf(passive(DOUBLE_POINTS.id)))
        assertEquals(20 + FLIP7_BONUS, Engine.roundScore(player, flip7PlayerId = "a"))
    }

    @Test
    fun `protection cards score nothing`() {
        val player = playerWith(listOf(num(4)), listOf(passive(SECOND_LIFE.id), passive(ARMOR.id)))
        assertEquals(4, Engine.roundScore(player, null))
    }

    @Test
    fun `busting wipes the round out`() {
        val player = playerWith(listOf(num(12)), listOf(passive(PLUS_TEN.id))).copy(status = PlayerStatus.BUST)
        assertEquals(0, Engine.roundScore(player, null))
    }

    @Test
    fun `seven unique cards ends the round for everyone and pays the bonus`() {
        val seventh = num(7, id = "seventh")
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(seventh))
        val state = dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") {
                    val hand = (1..6).map { v -> num(v) }
                    it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
                } else {
                    it
                }
            },
        )

        val result = tr(state, GameAction.Hit("a"))
        assertTrue(result.events.any { it is GameEvent.Flip7 })
        assertEquals(GamePhase.ROUND_END, result.state.phase)
        assertEquals("a", result.state.flip7PlayerId)
        assertTrue(result.state.players.none { it.status == PlayerStatus.ACTIVE })
        assertEquals(28 + FLIP7_BONUS, result.state.roundDeltas["a"])
    }

    @Test
    fun `seven unique cards during a forced draw also ends the round`() {
        val dealt = startedAndDealt(rest = listOf(num(7, id = "seventh"), num(8), num(9)))
        val state = dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") {
                    val hand = (1..6).map { v -> num(v) }
                    it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
                } else {
                    it
                }
            },
            forcedDraws = ForcedDraws("a", 3),
        )

        val result = tr(state, GameAction.ForcedDraw)
        assertTrue(result.events.any { it is GameEvent.Flip7 })
        assertEquals(GamePhase.ROUND_END, result.state.phase)
        assertNull(result.state.forcedDraws, "the rest of the forced draws are cancelled")
    }

    @Test
    fun `scores are banked the moment the round closes`() {
        var state = startedAndDealt(openingCards = listOf(num(9), num(4)))
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(9, state.player("a")!!.score)
        assertEquals(4, state.player("b")!!.score)
        assertEquals("a", state.roundWinnerId)
    }

    @Test
    fun `an equal round score is broken by the shorter hand`() {
        var state = startedAndDealt(
            openingCards = listOf(num(3), num(6)),
            rest = listOf(num(3, label = "other3", id = "a-3b")),
        )
        state = t(state, GameAction.Hit("a"))
        // Both are on 6 now, but b got there with one card.
        state = t(state, GameAction.Stay("b"))
        state = t(state, GameAction.Stay("a"))
        assertEquals(6, state.roundDeltas["a"])
        assertEquals(6, state.roundDeltas["b"])
        assertEquals("b", state.roundWinnerId)
    }

    @Test
    fun `the next round rotates the starter and returns every card to the pile`() {
        var state = startedAndDealt(openingCards = listOf(num(9), num(4)))
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        val before = state.allCardIds().sorted()

        state = t(state, GameAction.NextRound)
        assertEquals(GamePhase.PLAYING, state.phase)
        assertEquals(2, state.round)
        assertEquals(1, state.roundStartPlayer)
        assertEquals(listOf("b", "a"), state.dealQueue)
        assertTrue(state.players.all { it.hand.isEmpty() && it.passives.isEmpty() })
        assertEquals(before, state.allCardIds().sorted())
    }

    @Test
    fun `the game ends once the round limit is reached`() {
        var state = startedAndDealt(config(totalRounds = 1), openingCards = listOf(num(9), num(4)))
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals("a", state.gameWinnerId)
        state = t(state, GameAction.NextRound)
        assertEquals(GamePhase.GAME_END, state.phase)
    }

    @Test
    fun `first to score awards the highest scorer, not the first seat`() {
        var state = startedAndDealt(
            config(winCondition = WinCondition.FIRST_TO_SCORE, targetScore = 10),
            openingCards = listOf(num(11), num(13)),
        )
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        // Both crossed the line in the same round; b scored more.
        assertEquals("b", state.gameWinnerId)
    }

    @Test
    fun `a minted modifier scores but never joins the deck`() {
        var state = startedAndDealt(openingCards = listOf(num(9), num(4)))
        val before = state.allCardIds().sorted()

        // What double-or-nothing hands out when the coin lands right.
        val minted = Card("tmp-${DOUBLE_POINTS.id}-0", CardKind.PASSIVE, "double points", 0, DOUBLE_POINTS.id)
        state = state.copy(
            players = state.players.map { if (it.id == "a") it.copy(passives = listOf(minted)) else it },
        )

        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(18, state.roundDeltas["a"], "the ×2 counts while it is on the table")

        state = t(state, GameAction.NextRound)
        assertTrue(state.discard.none { it.isEphemeral })
        assertEquals(before, state.allCardIds().sorted())
    }

    @Test
    fun `double or nothing either doubles you or busts you, and nothing else`() {
        val outcomes = (1L..40L).map { seed ->
            val dealt = startedAndDealt(rest = listOf(action(DOUBLE_OR_NOTHING.id)))
            t(dealt, GameAction.Hit("a"), Rng(seed))
        }
        assertTrue(outcomes.any { it.status("a") == PlayerStatus.BUST }, "should sometimes bust")
        assertTrue(
            outcomes.any { it.player("a")!!.passives.any { p -> p.defId == DOUBLE_POINTS.id } },
            "should sometimes pay out",
        )
        for (state in outcomes) {
            val busted = state.status("a") == PlayerStatus.BUST
            val doubled = state.player("a")!!.passives.any { it.defId == DOUBLE_POINTS.id }
            assertTrue(busted != doubled, "exactly one of the two outcomes must happen")
        }
    }
}
