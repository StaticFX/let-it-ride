package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The effect cards — the per-round effects a player carries — and the cards
 * that hand them out. They were marks once, which is to say they were not cards
 * and nothing could take one off you; everything in this game is a card now, so
 * they lie in the modifier row and can be traded away like anything else.
 */
class EffectCardTest {

    /** Puts [id] one card short of the flip with a known, gapless hand. */
    private fun sixInHand(state: GameState, id: String): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) {
                val hand = (1..6).map { v -> num(v) }
                it.copy(hand = hand, handValue = hand.sumOf { c -> c.value })
            } else {
                it
            }
        },
    )

    private fun holding(state: GameState, id: String, defId: String): GameState = state.copy(
        players = state.players.map {
            if (it.id == id) it.copy(passives = it.passives + passive(defId, id = "tmp-$defId-$id")) else it
        },
    )

    private fun holds(state: GameState, id: String, defId: String): Boolean =
        state.player(id)!!.passives.any { it.defId == defId }

    // ─── just one more ───

    @Test
    fun `just one more resolves on its drawer without asking for a target`() {
        val dealt = startedAndDealt(rest = listOf(action(JUST_ONE_MORE.id)))
        val result = tr(dealt, GameAction.Hit("a"))

        assertNull(result.state.pendingAction, "a self-targeting card never parks the table")
        assertTrue(holds(result.state, "a", NO_FLIP.id))
        assertTrue(result.events.any { it is GameEvent.PassiveGained && it.playerId == "a" })
    }

    @Test
    fun `the card it hands over is minted rather than taken out of the deck`() {
        val dealt = startedAndDealt(rest = listOf(action(JUST_ONE_MORE.id)))
        val before = dealt.allCardIds().sorted()

        val state = t(dealt, GameAction.Hit("a"))

        assertEquals(before, state.allCardIds().sorted(), "the deck is the same deck")
        assertTrue(state.player("a")!!.passives.single { it.defId == NO_FLIP.id }.isEphemeral)
    }

    @Test
    fun `a player holding it draws straight past the flip target`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(num(7, id = "seventh")))
        val state = holding(sixInHand(dealt, "a"), "a", NO_FLIP.id)

        val result = tr(state, GameAction.Hit("a"))

        assertFalse(result.events.any { it is GameEvent.Flip7 })
        assertNull(result.state.flip7PlayerId)
        assertEquals(GamePhase.PLAYING, result.state.phase)
        assertEquals(7, result.state.hand("a").size, "the hand keeps growing past the target")
        assertEquals(PlayerStatus.ACTIVE, result.state.status("a"))
    }

    @Test
    fun `a player past the target still busts on a duplicate`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(num(7, id = "seventh"), num(3, id = "dup")))
        var state = holding(sixInHand(dealt, "a"), "a", NO_FLIP.id)

        state = t(state, GameAction.Hit("a"))
        assertEquals(PlayerStatus.ACTIVE, state.status("a"))
        // Round the table and back: the eighth card collides with the 3.
        state = t(state.copy(turnIndex = 0), GameAction.Hit("a"))

        assertEquals(PlayerStatus.BUST, state.status("a"))
    }

    @Test
    fun `it only stops the flip for the player holding it`() {
        val dealt = startedAndDealt(players = listOf("a", "b"), rest = listOf(num(7, id = "seventh")))
        val state = holding(sixInHand(dealt, "b"), "a", NO_FLIP.id)

        // b is not holding one, so b flipping still ends the round for everyone.
        val result = tr(state.copy(turnIndex = 1), GameAction.Hit("b"))

        assertEquals("b", result.state.flip7PlayerId)
        assertEquals(GamePhase.ROUND_END, result.state.phase)
    }

    @Test
    fun `drawing just one more twice fizzles instead of being spent for nothing`() {
        val dealt = startedAndDealt(rest = listOf(action(JUST_ONE_MORE.id, id = "second"), num(9)))
        val state = holding(dealt, "a", NO_FLIP.id)

        val result = tr(state, GameAction.Hit("a"))

        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == JUST_ONE_MORE.id })
        assertNotNull(result.state.forcedDraws, "the drawer is owed a replacement card")
        assertTrue(result.state.discard.any { it.id == "second" })
    }

    // ─── unlucky 7 ───

    @Test
    fun `unlucky 7 lands on the seat it is pointed at`() {
        val dealt = startedAndDealt(rest = listOf(action(UNLUCKY_SEVEN.id)))
        var state = t(dealt, GameAction.Hit("a"))
        assertEquals(UNLUCKY_SEVEN.id, state.pendingAction?.cardDefId)

        state = t(state, GameAction.PlayAction("a", "b", UNLUCKY_SEVEN.id))

        assertTrue(holds(state, "b", MUST_FLIP.id))
        assertFalse(holds(state, "a", MUST_FLIP.id))
    }

    @Test
    fun `a hand carrying it that goes out scores nothing`() {
        val player = Player(
            id = "a",
            name = "a",
            hand = listOf(num(10), num(5)),
            passives = listOf(passive(MUST_FLIP.id, id = "tmp-mustFlip-a")),
        )
        assertEquals(0, Engine.roundScore(player, flip7PlayerId = null))
        assertEquals(15, Engine.roundScore(player.copy(passives = emptyList()), flip7PlayerId = null))
    }

    @Test
    fun `a hand carrying it that flips out is paid in full`() {
        val hand = (1..7).map { num(it) }
        val player = Player(
            id = "a",
            name = "a",
            hand = hand,
            passives = listOf(passive(MUST_FLIP.id, id = "tmp-mustFlip-a")),
        )
        assertEquals(28 + FLIP7_BONUS, Engine.roundScore(player, flip7PlayerId = "a"))
    }

    @Test
    fun `unlucky 7 is not offered a seat that is already holding one`() {
        val dealt = startedAndDealt(players = listOf("a", "b", "c"), rest = listOf(action(UNLUCKY_SEVEN.id)))
        val state = t(holding(dealt, "b", MUST_FLIP.id), GameAction.Hit("a"))

        val targets = state.pendingAction?.validTargets
        assertNotNull(targets)
        assertFalse("b" in targets, "b already has one — pointing at them does nothing")
        assertTrue("a" in targets && "c" in targets)
    }

    @Test
    fun `unlucky 7 fizzles when the whole table is already holding one`() {
        var dealt = startedAndDealt(rest = listOf(action(UNLUCKY_SEVEN.id), num(9)))
        dealt = holding(holding(dealt, "a", MUST_FLIP.id), "b", MUST_FLIP.id)

        val result = tr(dealt, GameAction.Hit("a"))

        assertNull(result.state.pendingAction)
        assertTrue(result.events.any { it is GameEvent.Fizzled && it.cardDefId == UNLUCKY_SEVEN.id })
    }

    @Test
    fun `an unlucky 7 can be traded away before the round is scored`() {
        var state = startedAndDealt(
            players = listOf("a", "b"),
            openingCards = listOf(num(4), num(6)),
            rest = listOf(action(SWAP_CARDS.id)),
        )
        state = holding(state, "a", MUST_FLIP.id)
        val unlucky = state.player("a")!!.passives.single { it.defId == MUST_FLIP.id }

        state = t(state, GameAction.Hit("a"))
        state = t(
            state,
            GameAction.PlayAction("a", "a", SWAP_CARDS.id, cards = listOf(unlucky.id, state.hand("b").first().id)),
        )

        assertFalse(holds(state, "a", MUST_FLIP.id), "it was pushed onto b")
        assertTrue(holds(state, "b", MUST_FLIP.id))
    }

    // ─── lifetime ───

    @Test
    fun `effect cards are gone when the next round is dealt`() {
        var state = startedAndDealt(openingCards = listOf(num(4), num(6)))
        state = holding(state, "a", MUST_FLIP.id)
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(GamePhase.ROUND_END, state.phase)
        assertEquals(0, state.roundDeltas["a"], "it held for the round it was given in")

        state = t(state, GameAction.NextRound)

        assertTrue(state.players.all { it.passives.isEmpty() })
        assertTrue(state.discard.none { it.defId == MUST_FLIP.id }, "and it never joins the deck")
    }

    @Test
    fun `no deck may contain one`() {
        val built = sanitizeDeck(
            DeckConfig(
                numberCards = DeckPresets.PURE.deck.numberCards,
                passiveCards = listOf(MUST_FLIP.id, BOMBER.id, PLUS_FOUR.id),
            ),
        )
        assertEquals(listOf(PLUS_FOUR.id), built?.passiveCards)
    }

    @Test
    fun `double it does not hand the same effect over twice`() {
        val dealt = startedAndDealt(
            config = config(rules = listOf(LobbyRules.DOUBLE_IT.id)),
            rest = listOf(action(JUST_ONE_MORE.id)),
        )
        val result = tr(dealt, GameAction.Hit("a"))

        assertEquals(1, result.state.player("a")!!.passives.count { it.defId == NO_FLIP.id })
        assertEquals(
            1,
            result.events.count { it is GameEvent.PassiveGained },
            "the second application announces nothing",
        )
    }
}
