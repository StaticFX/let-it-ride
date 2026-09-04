package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringTest {

    private fun playerWith(hand: List<Card>, passives: List<Card> = emptyList()) = Player(
        id = "a", name = "a", hand = hand, handValue = hand.sumOf { it.value }, passives = passives,
    )

    /** Puts a known hand in front of one player, mid-round. */
    private fun GameState.holding(playerId: String, cards: List<Card>): GameState = copy(
        players = players.map {
            if (it.id == playerId) it.copy(hand = cards, handValue = cards.sumOf { c -> c.value }) else it
        },
    )

    private fun GameState.banked(scores: Map<String, Int>): GameState =
        copy(players = players.map { it.copy(score = scores[it.id] ?: it.score) })

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

    // ─── Bounty ───

    /** Sets `a` up as the outright leader and hands them the duplicate that busts them. */
    private fun leaderBustsRound(
        rules: List<String> = listOf(LobbyRules.BOUNTY.id),
        scores: Map<String, Int> = mapOf("a" to 50, "b" to 20, "c" to 10),
    ): GameState = startedAndDealt(
        config(rules = rules),
        players = listOf("a", "b", "c"),
        openingCards = listOf(num(5), num(2), num(3)),
        rest = listOf(num(5, id = "dup")),
    ).banked(scores)

    @Test
    fun `the bounty pays every other player when the leader busts`() {
        var state = t(leaderBustsRound(), GameAction.Hit("a"))
        assertEquals(PlayerStatus.BUST, state.status("a"), "a drew the duplicate")
        state = t(state, GameAction.Stay("b"))
        val result = tr(state, GameAction.Stay("c"))

        assertEquals(GamePhase.ROUND_END, result.state.phase)
        assertEquals(0, result.state.roundDeltas["a"], "the head pays nothing to itself")
        assertEquals(2 + 10, result.state.roundDeltas["b"])
        assertEquals(3 + 10, result.state.roundDeltas["c"])
        assertEquals(50, result.state.player("a")!!.score)

        val paid = result.events.filterIsInstance<GameEvent.BountyPaid>().single()
        assertEquals("a", paid.bustedPlayerId)
        assertEquals(listOf("b", "c"), paid.collectorIds)
        assertEquals(10, paid.points)
    }

    @Test
    fun `without the rule a busting leader pays nobody`() {
        var state = t(leaderBustsRound(rules = emptyList()), GameAction.Hit("a"))
        state = t(state, GameAction.Stay("b"))
        val result = tr(state, GameAction.Stay("c"))
        assertEquals(2, result.state.roundDeltas["b"])
        assertTrue(result.events.none { it is GameEvent.BountyPaid })
    }

    @Test
    fun `a shared lead carries no bounty, so round one never pays out`() {
        var state = t(leaderBustsRound(scores = mapOf("a" to 50, "b" to 50, "c" to 10)), GameAction.Hit("a"))
        state = t(state, GameAction.Stay("b"))
        state = t(state, GameAction.Stay("c"))
        assertEquals(2, state.roundDeltas["b"], "nobody is *the* player in front")

        // The same reasoning covers an opening round, where everyone is on zero.
        var opening = t(leaderBustsRound(scores = emptyMap()), GameAction.Hit("a"))
        opening = t(opening, GameAction.Stay("b"))
        opening = t(opening, GameAction.Stay("c"))
        assertEquals(2, opening.roundDeltas["b"])
    }

    @Test
    fun `only the leader is worth a bounty`() {
        // b busts this time; a is still in front and still standing.
        var state = t(leaderBustsRound(), GameAction.Stay("a"))
        state = t(state, GameAction.Hit("b"))
        assertEquals(PlayerStatus.ACTIVE, state.status("b"), "the 5 is no duplicate of b's 2")
        state = t(state, GameAction.Stay("c"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(5, state.roundDeltas["a"])
        assertEquals(3, state.roundDeltas["c"])
    }

    @Test
    fun `the bounty never reorders the round winner`() {
        fun play(rules: List<String>): GameState {
            var state = startedAndDealt(
                config(rules = rules),
                players = listOf("a", "b", "c"),
                openingCards = listOf(num(5), num(3), num(6)),
                rest = listOf(num(5, id = "dup"), num(3, label = "three again", id = "b-3b")),
            ).banked(mapOf("a" to 50))
            state = t(state, GameAction.Hit("a"))
            state = t(state, GameAction.Hit("b"))
            state = t(state, GameAction.Stay("c"))
            return t(state, GameAction.Stay("b"))
        }

        val plain = play(emptyList())
        val bountied = play(listOf(LobbyRules.BOUNTY.id))
        // b and c both bank 6; c got there with one card, so c takes the round.
        assertEquals("c", plain.roundWinnerId)
        assertEquals(plain.roundWinnerId, bountied.roundWinnerId)
        assertEquals(6, plain.roundDeltas["b"])
        assertEquals(16, bountied.roundDeltas["b"])
        assertEquals(16, bountied.roundDeltas["c"])
    }

    // ─── Flip 9 ───

    @Test
    fun `flip 9 lets a seventh card go by`() {
        val state = startedAndDealt(
            config(rules = listOf(LobbyRules.FLIP_9.id)),
            players = listOf("a", "b", "c"),
            rest = listOf(num(7, id = "seventh")),
        ).holding("a", (1..6).map { num(it) })

        val after = t(state, GameAction.Hit("a"))
        assertEquals(GamePhase.PLAYING, after.phase)
        assertNull(after.flip7PlayerId)
        assertEquals(7, after.hand("a").size)
        assertEquals(PlayerStatus.ACTIVE, after.status("a"))
    }

    @Test
    fun `nine different cards takes the game outright`() {
        val state = startedAndDealt(
            config(rules = listOf(LobbyRules.FLIP_9.id)),
            rest = listOf(num(9, id = "ninth")),
        ).holding("a", (1..8).map { num(it) }).banked(mapOf("b" to 500))

        val result = tr(state, GameAction.Hit("a"))
        assertTrue(result.events.any { it is GameEvent.Flip7 })
        assertEquals(GamePhase.ROUND_END, result.state.phase)
        assertEquals("a", result.state.flip7PlayerId)
        assertEquals(45 + FLIP7_BONUS, result.state.roundDeltas["a"])
        // b is 500 points clear and still loses: the flip is a knockout.
        assertEquals("a", result.state.gameWinnerId)
        assertEquals(GamePhase.GAME_END, t(result.state, GameAction.NextRound).phase)
    }

    @Test
    fun `a plain flip 7 still only ends the round`() {
        val state = startedAndDealt(rest = listOf(num(7, id = "seventh")))
            .holding("a", (1..6).map { num(it) })

        val after = t(state, GameAction.Hit("a"))
        assertEquals(GamePhase.ROUND_END, after.phase)
        assertNull(after.gameWinnerId, "round 1 of 3 decides nothing")
        assertEquals(GamePhase.PLAYING, t(after, GameAction.NextRound).phase)
    }

    @Test
    fun `the flip target the client is told about follows the rules`() {
        assertEquals(7, RuleSet.of(config()).flipTarget)
        assertEquals(9, RuleSet.of(config(rules = listOf(LobbyRules.FLIP_9.id))).flipTarget)
    }

    @Test
    fun `every deck preset can actually reach nine different cards`() {
        for (preset in DeckPresets.all) {
            val labels = preset.deck.numberCards.mapNotNull { it.label ?: it.value.toString() }.distinct()
            assertTrue(labels.size >= 9, "${preset.id} only has ${labels.size} distinct number cards")
        }
    }

    // ─── Coin flip ───

    /** Draws a coin flip for `a`, calls [call], and hands back the whole transition. */
    private fun callCoin(call: String, seed: Long): TransitionResult {
        val pending = t(
            startedAndDealt(rest = listOf(action(COIN_FLIP.id))),
            GameAction.Hit("a"),
            Rng(seed),
        )
        return tr(pending, GameAction.PlayAction("a", "a", COIN_FLIP.id, call), Rng(seed))
    }

    @Test
    fun `a coin flip either doubles you or busts you, and nothing else`() {
        val outcomes = (1L..40L).map { callCoin(COIN_HEADS, it) }
        assertTrue(outcomes.any { it.state.status("a") == PlayerStatus.BUST }, "should sometimes bust")
        assertTrue(
            outcomes.any { it.state.player("a")!!.passives.any { p -> p.defId == DOUBLE_POINTS.id } },
            "should sometimes pay out",
        )
        for (result in outcomes) {
            val busted = result.state.status("a") == PlayerStatus.BUST
            val doubled = result.state.player("a")!!.passives.any { it.defId == DOUBLE_POINTS.id }
            assertTrue(busted != doubled, "exactly one of the two outcomes must happen")

            val flip = result.events.filterIsInstance<GameEvent.CoinFlip>().single()
            assertEquals(COIN_HEADS, flip.call, "the event carries the call the player made")
            assertEquals(doubled, flip.call == flip.result, "landing on the called face is the win")
        }
    }

    @Test
    fun `the call is what decides it, not the coin`() {
        // Same seed, same face: the two calls have to come out opposite ways.
        for (seed in 1L..20L) {
            val heads = callCoin(COIN_HEADS, seed).state.status("a") == PlayerStatus.BUST
            val tails = callCoin(COIN_TAILS, seed).state.status("a") == PlayerStatus.BUST
            assertTrue(heads != tails, "seed $seed busted on both calls")
        }
    }
}
