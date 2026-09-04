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
    fun `swap takes the modifier row with the hand`() {
        var state = withPending(SWAP.id, openingCards = listOf(num(1), num(12)))
        state = state.copy(
            players = state.players.map {
                when (it.id) {
                    "a" -> it.copy(passives = listOf(passive(DOUBLE_POINTS.id, id = "the-double")))
                    else -> it.copy(passives = listOf(passive(DISCORDIA.id, id = "the-discordia")))
                }
            },
        )

        state = t(state, GameAction.PlayAction("a", "b", SWAP.id))

        assertEquals(listOf("the-discordia"), state.player("a")!!.passives.map { it.id })
        assertEquals(listOf("the-double"), state.player("b")!!.passives.map { it.id })
    }

    @Test
    fun `a swap that pushes a hand over the threshold busts on arrival`() {
        var state = withPending(SWAP.id, openingCards = listOf(num(1), num(12)))
            .let { s -> s.copy(config = s.config.copy(ruleIds = listOf(LobbyRules.BLACKJACKING.id))) }
        state = state.copy(
            players = state.players.map {
                if (it.id == "b") it.copy(hand = listOf(num(11), num(12)), handValue = 23) else it
            },
        )

        state = t(state, GameAction.PlayAction("a", "b", SWAP.id))

        assertEquals(PlayerStatus.BUST, state.status("a"), "the hand that arrived was already over")
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
        assertEquals(
            ForcedDraws("b", 1, source = SLOTS_SOURCE), state.forcedDraws,
            "slots' own draw runs first, tagged so the room can pace the reels",
        )
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

    // ─── Cards that ask a question ───

    @Test
    fun `a card that asks a question pauses even though it resolves on its drawer`() {
        val state = withPending(COIN_FLIP.id)
        assertEquals(COIN_FLIP.id, state.pendingAction?.cardDefId)
        assertEquals(listOf(COIN_HEADS, COIN_TAILS), state.pendingAction?.options)
        assertEquals(listOf("a"), state.pendingAction?.validTargets, "only the drawer is involved")
    }

    @Test
    fun `a card that asks nothing carries no options`() {
        assertEquals(emptyList(), withPending(FREEZE.id).pendingAction?.options)
    }

    @Test
    fun `an answer the card never offered falls back to its first option`() {
        val state = withPending(COIN_FLIP.id)
        val result = tr(state, GameAction.PlayAction("a", "a", COIN_FLIP.id, "sideways"))
        assertEquals(COIN_HEADS, result.events.filterIsInstance<GameEvent.CoinFlip>().single().call)
    }

    @Test
    fun `a client that sends no answer at all still resolves the card`() {
        val state = withPending(COIN_FLIP.id)
        val result = tr(state, GameAction.PlayAction("a", "a", COIN_FLIP.id))
        assertNull(result.state.pendingAction)
        assertEquals(COIN_HEADS, result.events.filterIsInstance<GameEvent.CoinFlip>().single().call)
    }

    @Test
    fun `the clock answers the question rather than sitting on it`() {
        val state = withPending(COIN_FLIP.id)
        val result = tr(state, GameAction.Timeout("a"))
        assertNull(result.state.pendingAction, "the table must not stall on an unflipped coin")
        val flip = result.events.filterIsInstance<GameEvent.CoinFlip>().single()
        assertTrue(flip.call in listOf(COIN_HEADS, COIN_TAILS))
    }

    @Test
    fun `spin the table moves every hand one seat the way it was called`() {
        val state = withPending(
            SPIN_TABLE.id,
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
        )

        val right = t(state, GameAction.PlayAction("a", "a", SPIN_TABLE.id, SPIN_RIGHT))
        assertEquals(listOf(3, 1, 2), listOf("a", "b", "c").map { right.player(it)!!.handValue })

        val left = t(state, GameAction.PlayAction("a", "a", SPIN_TABLE.id, SPIN_LEFT))
        assertEquals(listOf(2, 3, 1), listOf("a", "b", "c").map { left.player(it)!!.handValue })
    }

    @Test
    fun `a spin says which seats moved and which way`() {
        val state = withPending(
            SPIN_TABLE.id,
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
        )
        val spun = tr(state, GameAction.PlayAction("a", "a", SPIN_TABLE.id, SPIN_RIGHT))
            .events.filterIsInstance<GameEvent.TableSpun>().single()
        assertEquals(SPIN_RIGHT, spun.direction)
        assertEquals(listOf("a", "b", "c"), spun.playerIds)
    }

    @Test
    fun `a seat that is already out is spun along with everybody else`() {
        var state = withPending(
            SPIN_TABLE.id,
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
        )
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it },
        )
        state = t(state, GameAction.PlayAction("a", "a", SPIN_TABLE.id, SPIN_RIGHT))

        assertEquals(listOf(3, 1, 2), listOf("a", "b", "c").map { state.player(it)!!.handValue })
        assertEquals(PlayerStatus.STAYED, state.status("b"), "the hand moved; the seat's round did not")
    }

    @Test
    fun `a busted hand pushed onto a seat that had banked busts it too`() {
        var state = withPending(
            SPIN_TABLE.id,
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(2)),
        )
        // b busted on a pair of fives and is still holding both of them; a has
        // already banked a clean hand. The spin hands b's wreckage to a.
        state = state.copy(
            players = state.players.map {
                when (it.id) {
                    "b" -> it.copy(
                        hand = listOf(num(5, id = "five"), num(5, id = "five-again")),
                        handValue = 10,
                        status = PlayerStatus.BUST,
                        bustReason = "duplicate",
                    )

                    else -> it.copy(status = PlayerStatus.STAYED)
                }
            },
        )

        state = t(state, GameAction.PlayAction("a", "a", SPIN_TABLE.id, SPIN_RIGHT))

        assertEquals(PlayerStatus.BUST, state.status("a"), "a banked hand is not safe from a spin")
    }

    @Test
    fun `a spin re-checks the hand that lands in front of you`() {
        var state = withPending(
            SPIN_TABLE.id,
            players = listOf("a", "b"),
            openingCards = listOf(num(1), num(2)),
        ).let { s -> s.copy(config = s.config.copy(ruleIds = listOf(LobbyRules.BLACKJACKING.id))) }
        state = state.copy(
            players = state.players.map {
                if (it.id == "b") it.copy(hand = listOf(num(9), num(13)), handValue = 22) else it
            },
        )
        // b was over 21 before the spin but never drew there; a receives it and
        // busts on the threshold the moment the hand lands.
        state = t(state, GameAction.PlayAction("a", "a", SPIN_TABLE.id, SPIN_RIGHT))
        assertEquals(PlayerStatus.BUST, state.status("a"))
    }

    // ─── Assassination ───

    @Test
    fun `assassination busts one player picked by the server`() {
        val victims = (1L..30L).map { seed ->
            val state = t(
                startedAndDealt(
                    players = listOf("a", "b", "c"),
                    openingCards = listOf(num(1), num(2), num(3)),
                    rest = listOf(action(ASSASSINATION.id)),
                ),
                GameAction.Hit("a"),
                Rng(seed),
            )
            val busted = state.players.filter { it.status == PlayerStatus.BUST }
            assertEquals(1, busted.size, "exactly one player goes down per bottle")
            busted.single().id
        }
        assertTrue(victims.distinct().size > 1, "the bottle must not always stop on the same seat")
        assertTrue("a" in victims, "the player who drew it is in the running too")
    }

    @Test
    fun `the bottle event names the victim the server picked`() {
        val result = tr(
            startedAndDealt(
                players = listOf("a", "b", "c"),
                openingCards = listOf(num(1), num(2), num(3)),
                rest = listOf(action(ASSASSINATION.id)),
            ),
            GameAction.Hit("a"),
        )
        val spin = result.events.filterIsInstance<GameEvent.BottleSpin>().single()
        assertEquals(PlayerStatus.BUST, result.state.status(spin.victimId))
    }

    @Test
    fun `double it spins the bottle twice and takes two players down`() {
        val state = t(
            startedAndDealt(
                config(rules = listOf(LobbyRules.DOUBLE_IT.id)),
                players = listOf("a", "b", "c"),
                openingCards = listOf(num(1), num(2), num(3)),
                rest = listOf(action(ASSASSINATION.id)),
            ),
            GameAction.Hit("a"),
        )
        assertEquals(2, state.players.count { it.status == PlayerStatus.BUST })
    }

    // ─── Don't care + ratio ───

    @Test
    fun `dont care can be pointed at a player who is already out`() {
        var state = withPending(
            DONT_CARE.id,
            players = listOf("a", "b", "c"),
            openingCards = listOf(num(1), num(2), num(3)),
        )
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it },
        )
        assertEquals(listOf("a", "b", "c"), state.pendingAction?.validTargets)

        state = t(state, GameAction.PlayAction("a", "b", DONT_CARE.id))
        assertEquals(PlayerStatus.BUST, state.status("b"), "going out is no protection")
    }

    @Test
    fun `a player busted after going out scores nothing`() {
        var state = withPending(
            DONT_CARE.id,
            players = listOf("a", "b"),
            openingCards = listOf(num(9), num(12)),
        )
        // b banked their 12 before a turned the card up.
        state = state.copy(
            players = state.players.map { if (it.id == "b") it.copy(status = PlayerStatus.STAYED) else it },
        )
        state = t(state, GameAction.PlayAction("a", "b", DONT_CARE.id))
        state = t(state, GameAction.Stay("a"))
        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(0, state.roundDeltas["b"])
        assertEquals(9, state.roundDeltas["a"])
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
