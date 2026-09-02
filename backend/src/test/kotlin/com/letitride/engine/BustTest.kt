package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BustTest {

    @Test
    fun `a duplicate number busts you out of the round`() {
        val state = startedAndDealt(openingCards = listOf(num(5), num(2)), rest = listOf(num(5, id = "dup")))
        val after = t(state, GameAction.Hit("a"))
        assertEquals(PlayerStatus.BUST, after.status("a"))
        assertEquals(Ctx.BUST_DUPLICATE, after.player("a")!!.bustReason)
    }

    @Test
    fun `a busted hand scores nothing`() {
        var state = startedAndDealt(openingCards = listOf(num(5), num(2)), rest = listOf(num(5, id = "dup")))
        state = t(state, GameAction.Hit("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(0, state.roundDeltas["a"])
        assertEquals(0, state.player("a")!!.score)
    }

    @Test
    fun `second chance eats the duplicate and both cards reach the discard pile`() {
        val dealt = startedAndDealt(openingCards = listOf(num(5), num(2)), rest = listOf(num(5, id = "dup")))
        val state = dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") it.copy(passives = listOf(passive(SECOND_LIFE.id))) else it
            },
        )

        val result = tr(state, GameAction.Hit("a"))
        val after = result.state
        assertEquals(PlayerStatus.ACTIVE, after.status("a"))
        assertEquals(listOf("5"), after.hand("a").map { it.label })
        assertTrue(after.player("a")!!.passives.isEmpty())
        assertTrue(result.events.any { it is GameEvent.SecondChance })

        val discarded = after.discard.map { it.id }
        assertTrue("dup" in discarded, "the duplicate must go to the discard pile")
        assertTrue("p-${SECOND_LIFE.id}" in discarded, "the spent second chance must go to the discard pile")
    }

    @Test
    fun `second chance does not cover a threshold bust`() {
        val dealt = startedAndDealt(
            config(rules = listOf(LobbyRules.BLACKJACKING.id)),
            openingCards = listOf(num(13), num(2)),
            rest = listOf(num(11)),
        )
        val state = dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") it.copy(passives = listOf(passive(SECOND_LIFE.id))) else it
            },
        )

        val after = t(state, GameAction.Hit("a"))
        assertEquals(PlayerStatus.BUST, after.status("a"))
        assertEquals(Ctx.BUST_THRESHOLD, after.player("a")!!.bustReason)
        assertEquals(1, after.player("a")!!.passives.size, "second chance is only for duplicates")
    }

    @Test
    fun `blackjacking busts a hand over 21`() {
        val state = startedAndDealt(
            config(rules = listOf(LobbyRules.BLACKJACKING.id)),
            openingCards = listOf(num(13), num(2)),
            rest = listOf(num(9)),
        )
        assertEquals(PlayerStatus.BUST, t(state, GameAction.Hit("a")).status("a"))
    }

    @Test
    fun `without blackjacking a big hand is fine`() {
        val state = startedAndDealt(openingCards = listOf(num(13), num(2)), rest = listOf(num(12)))
        assertEquals(PlayerStatus.ACTIVE, t(state, GameAction.Hit("a")).status("a"))
    }

    @Test
    fun `a surplus second chance is handed to a player who has none`() {
        val dealt = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(passive(SECOND_LIFE.id, id = "sl-2")),
        )
        val state = dealt.copy(
            players = dealt.players.map {
                if (it.id == "a") it.copy(passives = listOf(passive(SECOND_LIFE.id, id = "sl-1"))) else it
            },
        )

        val result = tr(state, GameAction.Hit("a"))
        assertEquals(1, result.state.player("a")!!.passives.size, "you never hold two")
        val receiver = result.state.players.first { it.id != "a" && it.passives.isNotEmpty() }
        assertEquals("sl-2", receiver.passives.single().id)
        assertTrue(result.events.any { it is GameEvent.SecondChancePassed })
    }

    @Test
    fun `a surplus second chance with nowhere to go is discarded`() {
        val dealt = startedAndDealt(
            openingCards = listOf(num(1), num(2)),
            rest = listOf(passive(SECOND_LIFE.id, id = "sl-2")),
        )
        val state = dealt.copy(
            players = dealt.players.map { it.copy(passives = listOf(passive(SECOND_LIFE.id, id = "sl-${it.id}"))) },
        )

        val after = t(state, GameAction.Hit("a"))
        assertEquals(1, after.player("a")!!.passives.size)
        assertEquals(1, after.player("b")!!.passives.size)
        assertTrue(after.discard.any { it.id == "sl-2" })
    }

    @Test
    fun `stealing a card you already hold busts the thief`() {
        val dealt = startedAndDealt(
            openingCards = listOf(num(7), num(7, id = "b-seven")),
            rest = listOf(action(STEAL.id)),
        )
        var state = t(dealt, GameAction.Hit("a"))
        assertEquals(STEAL.id, state.pendingAction?.cardDefId)

        state = t(state, GameAction.PlayAction("a", "b", STEAL.id))
        assertEquals(PlayerStatus.BUST, state.status("a"))
        assertFalse(state.player("b")!!.hand.any { it.id == "b-seven" })
    }
}
