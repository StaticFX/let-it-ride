package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionCardTest {

    private fun withPending(
        defId: String,
        players: List<String> = listOf("a", "b"),
        openingCards: List<Card> = players.indices.map { num(it + 1) },
        rest: List<Card> = emptyList(),
    ): GameState {
        val dealt = startedAndDealt(players = players, openingCards = openingCards, rest = listOf(action(defId)) + rest)
        return t(dealt, GameAction.Hit("a"))
    }

    @Test
    fun `drawing an action card pauses for a target`() {
        val state = withPending(FREEZE.id)
        assertEquals(FREEZE.id, state.pendingAction?.cardDefId)
        assertEquals("a", state.pendingAction?.playerId)
        assertEquals(0, state.turnIndex, "the turn does not move until the card is played")
    }

    @Test
    fun `hit and stay are locked out while a target is owed`() {
        val state = withPending(FREEZE.id)
        assertEquals(state, t(state, GameAction.Hit("a")))
        assertEquals(state, t(state, GameAction.Stay("a")))
    }

    @Test
    fun `only the drawer can play the pending card`() {
        val state = withPending(FREEZE.id)
        assertEquals(state, t(state, GameAction.PlayAction("b", "a", FREEZE.id)))
    }

    @Test
    fun `the played card has to be the card that was drawn`() {
        val state = withPending(FREEZE.id)
        assertEquals(state, t(state, GameAction.PlayAction("a", "b", STRIKE.id)))
    }

    @Test
    fun `a resolved action card goes to the discard pile`() {
        var state = withPending(FREEZE.id)
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        assertNull(state.pendingAction)
        assertTrue(state.discard.any { it.defId == FREEZE.id })
    }

    @Test
    fun `freeze sends the target out with their points`() {
        var state = withPending(FREEZE.id, openingCards = listOf(num(1), num(8)))
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        assertEquals(PlayerStatus.STAYED, state.status("b"))
        assertEquals(8, state.player("b")!!.handValue)
    }

    @Test
    fun `freeze cannot be aimed at someone already out`() {
        var state = withPending(FREEZE.id, players = listOf("a", "b", "c"))
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.BUST) else it },
        )
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        // The pick falls back to the player who drew it.
        assertEquals(PlayerStatus.BUST, state.status("b"))
        assertEquals(PlayerStatus.STAYED, state.status("a"))
    }

    @Test
    fun `strike discards the biggest card and it lands in the pile`() {
        var state = withPending(STRIKE.id, openingCards = listOf(num(1), num(2)))
        state = state.copy(
            players = state.players.map {
                if (it.id == "b") it.copy(hand = listOf(num(2), num(11, id = "big")), handValue = 13) else it
            },
        )
        state = t(state, GameAction.PlayAction("a", "b", STRIKE.id))
        assertEquals(listOf("2"), state.hand("b").map { it.label })
        assertEquals(2, state.player("b")!!.handValue)
        assertTrue(state.discard.any { it.id == "big" })
    }

    @Test
    fun `armor eats a strike`() {
        var state = withPending(STRIKE.id, openingCards = listOf(num(1), num(2)))
        state = state.copy(
            players = state.players.map {
                if (it.id == "b") it.copy(passives = listOf(passive(ARMOR.id))) else it
            },
        )
        state = t(state, GameAction.PlayAction("a", "b", STRIKE.id))
        assertEquals(1, state.hand("b").size)
        assertTrue(state.player("b")!!.passives.isEmpty())
    }

    @Test
    fun `swap trades hands and recomputes both totals`() {
        var state = withPending(SWAP.id, openingCards = listOf(num(1), num(12)))
        state = t(state, GameAction.PlayAction("a", "b", SWAP.id))
        assertEquals(12, state.player("a")!!.handValue)
        assertEquals(1, state.player("b")!!.handValue)
    }

    @Test
    fun `draw three queues three forced draws`() {
        var state = withPending(DRAW_THREE.id, rest = List(4) { num(it + 5) })
        state = t(state, GameAction.PlayAction("a", "b", DRAW_THREE.id))
        assertEquals(ForcedDraws("b", 3), state.forcedDraws)

        repeat(3) { state = t(state, GameAction.ForcedDraw) }
        assertNull(state.forcedDraws)
        assertEquals(4, state.hand("b").size)
    }

    @Test
    fun `a draw three inside a draw three resolves before the outer one resumes`() {
        var state = startedAndDealt(
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
            rest = listOf(
                num(4),
                action(DRAW_THREE.id, id = "inner"),
                num(5), num(6), num(7),
                num(8),
            ),
        )
        state = state.copy(pendingAction = PendingAction(DRAW_THREE.id, "a", action(DRAW_THREE.id, id = "outer")))
        state = t(state, GameAction.PlayAction("a", "b", DRAW_THREE.id))
        assertEquals(ForcedDraws("b", 3), state.forcedDraws)

        state = t(state, GameAction.ForcedDraw)
        assertEquals(2, state.forcedDraws?.remaining)

        // b turns up another draw three and must aim it before continuing.
        state = t(state, GameAction.ForcedDraw)
        assertEquals("b", state.pendingAction?.playerId)
        assertEquals(1, state.forcedDraws?.remaining)

        state = t(state, GameAction.PlayAction("b", "c", DRAW_THREE.id))
        assertEquals(ForcedDraws("c", 3), state.forcedDraws)
        assertEquals(listOf(ForcedDraws("b", 1)), state.forcedDrawStack)

        repeat(3) { state = t(state, GameAction.ForcedDraw) }
        assertEquals(ForcedDraws("b", 1), state.forcedDraws)
        assertTrue(state.forcedDrawStack.isEmpty())
    }

    @Test
    fun `busting mid forced draw cancels the rest of them`() {
        var state = startedAndDealt(
            openingCards = listOf(num(1), num(2)),
            rest = listOf(num(2, id = "dup"), num(5), num(6)),
        )
        state = state.copy(forcedDraws = ForcedDraws("b", 3))
        state = t(state, GameAction.ForcedDraw)
        assertEquals(PlayerStatus.BUST, state.status("b"))
        assertNull(state.forcedDraws)
    }

    @Test
    fun `slots stacks onto a running forced draw instead of replacing it`() {
        var state = startedAndDealt(
            openingCards = listOf(num(1), num(2)),
            rest = listOf(action(SLOTS.id), num(5), num(6), num(7)),
        )
        state = state.copy(forcedDraws = ForcedDraws("b", 2))

        val result = tr(state, GameAction.ForcedDraw)
        state = result.state
        assertTrue(result.events.any { it is GameEvent.Slots })
        assertEquals(ForcedDraws("b", 1), state.forcedDraws, "slots' own draw runs first")
        assertEquals(listOf(ForcedDraws("b", 1)), state.forcedDrawStack, "the original draw is preserved")
    }

    @Test
    fun `self-targeting cards resolve without asking for a pick`() {
        val state = withPending(SLOTS.id, rest = listOf(num(9)))
        assertNull(state.pendingAction)
        assertNotNull(state.forcedDraws)
        assertEquals("a", state.forcedDraws?.playerId)
    }

    @Test
    fun `double it makes an action card fire twice`() {
        var state = withPending(
            STRIKE.id,
            openingCards = listOf(num(1), num(2)),
        ).let { s -> s.copy(config = s.config.copy(ruleIds = listOf(LobbyRules.DOUBLE_IT.id))) }

        state = state.copy(
            players = state.players.map {
                if (it.id == "b") {
                    it.copy(hand = listOf(num(2), num(11, id = "big"), num(12, id = "bigger")), handValue = 25)
                } else {
                    it
                }
            },
        )
        state = t(state, GameAction.PlayAction("a", "b", STRIKE.id))
        assertEquals(listOf("2"), state.hand("b").map { it.label }, "both high cards should be gone")
    }

    @Test
    fun `womp womp turns an action card back on the player who drew it`() {
        val state = withPending(FREEZE.id)
            .let { s -> s.copy(config = s.config.copy(ruleIds = listOf(LobbyRules.WOMP_WOMP.id))) }
        // The pending card was created before the rule was applied here, so play
        // it and check the redirect happens at resolution time.
        val after = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        assertEquals(PlayerStatus.STAYED, after.status("a"))
        assertEquals(PlayerStatus.ACTIVE, after.status("b"))
    }

    @Test
    fun `womp womp gives your modifiers away`() {
        val state = startedAndDealt(
            config(rules = listOf(LobbyRules.WOMP_WOMP.id)),
            openingCards = listOf(num(1), num(2)),
            rest = listOf(passive(PLUS_TEN.id)),
        )
        val after = t(state, GameAction.Hit("a"))
        assertTrue(after.player("a")!!.passives.isEmpty())
        assertEquals(1, after.player("b")!!.passives.size)
    }
}
