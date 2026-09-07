package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hidden hand: how a card gets into one, when it may come out, and what
 * happens to it either way.
 *
 * The conservation checks live in `FullGameTest`, which plays whole games under
 * this preset. These are the rules of the mode itself.
 */
class RollingRulesTest {

    private fun rollingConfig(deck: DeckConfig = DeckPresets.PURE.deck) =
        config(deck = deck).copy(mode = GameMode.ROLLING_RULES)

    private fun table(vararg gamblers: String): GameState {
        val state = startedAndDealt(rollingConfig(), listOf("a", "b"), rest = List(20) { num(it % 9 + 1) })
        return state.holding("a", *gamblers.map { gambler(it) }.toTypedArray())
    }

    // ─── The mode ───

    @Test
    fun `rolling rules always underlays extreme, whatever the host ticked`() {
        val rules = RuleSet.of(rollingConfig())
        assertTrue(rules.allowsNegative, "a rolling rules round has to be able to go below nothing")
        assertTrue(rules.reachesFinished)
    }

    @Test
    fun `the classic game is untouched by any of it`() {
        val rules = RuleSet.of(config())
        assertFalse(rules.allowsNegative)
        assertEquals(emptyList(), forcedRulesFor(GameMode.CLASSIC))
    }

    // ─── Getting one ───

    @Test
    fun `a drawn gambler card goes into the hidden hand and the draw carries on`() {
        val state = started(rollingConfig(), listOf("a", "b"), deck = listOf(gambler("shuffle"), num(5)))
            .let { finishDeal(it.copy(dealQueue = emptyList())) }
        val result = tr(state, GameAction.Hit("a"))

        assertEquals(listOf("shuffle"), result.state.player("a")!!.gamblers.map { it.defId })
        // ...and the number card underneath it, because a gambler card does not
        // cost you your draw.
        assertEquals(listOf(5), result.state.hand("a").map { it.value })
    }

    @Test
    fun `the table is told somebody drew one, and not which`() {
        val state = started(rollingConfig(), listOf("a", "b"), deck = listOf(gambler("shuffle"), num(5)))
            .let { finishDeal(it.copy(dealQueue = emptyList())) }
        val events = tr(state, GameAction.Hit("a")).events

        val drawn = events.filterIsInstance<GameEvent.GamblerDrawn>().single()
        assertEquals("a", drawn.playerId)
        // The engine fills the face in; the room is what cuts it out per viewer.
        assertNotNull(drawn.card)
        // ...and it is *not* announced as an ordinary draw, which would show it.
        assertTrue(events.filterIsInstance<GameEvent.Draw>().none { it.card.kind == CardKind.GAMBLER })
    }

    @Test
    fun `a sixth gambler card has nowhere to go and is discarded`() {
        val full = started(rollingConfig(), listOf("a", "b"), deck = listOf(gambler("shuffle"), num(5)))
            .let { finishDeal(it.copy(dealQueue = emptyList())) }
            .holding("a", *(1..GAMBLER_HAND_LIMIT).map { gambler("shuffle", "g-held-$it") }.toTypedArray())

        val result = tr(full, GameAction.Hit("a"))
        assertEquals(GAMBLER_HAND_LIMIT, result.state.player("a")!!.gamblers.size)
        assertTrue(result.state.discard.any { it.kind == CardKind.GAMBLER }, "it has to end up somewhere")
        assertFalse(result.events.filterIsInstance<GameEvent.GamblerDrawn>().single().kept)
    }

    @Test
    fun `the hidden hand survives the round`() {
        var state = table("shuffle")
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertEquals(GamePhase.ROUND_END, state.phase)
        // "Next round" opens the shop first in this mode; the round is on the
        // other side of it.
        state = throughInterlude(t(state, GameAction.NextRound))

        assertEquals(GamePhase.PLAYING, state.phase)
        assertEquals(listOf("shuffle"), state.player("a")!!.gamblers.map { it.defId })
        // ...where the hand and the modifier row did not.
        assertTrue(state.player("a")!!.hand.isEmpty())
    }

    @Test
    fun `a new game does not`() {
        val state = t(table("shuffle"), GameAction.StartGame)
        // StartGame from PLAYING is a no-op, so wind it back to a lobby first.
        val fresh = t(lobby(rollingConfig(), listOf("a", "b")).holding("a", gambler("shuffle")), GameAction.StartGame)
        assertTrue(fresh.player("a")!!.gamblers.isEmpty(), "a new game builds a new deck; a held card would exist twice")
        assertEquals(GamePhase.PLAYING, state.phase)
    }

    // ─── Playing one ───

    @Test
    fun `an on-turn card is offered on your turn and nowhere else`() {
        val state = table(REDIRECT_ID)
        val mine = state.player("a")!!.gamblers.single().id

        assertEquals(listOf(mine), Engine.playableGamblers(state, "a"))
        // ...and not once the turn has moved on.
        val theirTurn = state.copy(turnIndex = 1)
        assertEquals(emptyList(), Engine.playableGamblers(theirTurn, "a"))
    }

    @Test
    fun `an on-out card is offered only once you are out`() {
        val state = table(FUCK_IT_ID)
        assertEquals(emptyList(), Engine.playableGamblers(state, "a"))

        val out = t(state, GameAction.Stay("a"))
        assertEquals(1, Engine.playableGamblers(out, "a").size)
    }

    @Test
    fun `a card you cannot pay for is not offered`() {
        val broke = table("cheating")
        assertEquals(emptyList(), Engine.playableGamblers(broke, "a"), "cheating costs $CHEATING_COST")

        val rich = broke.copy(players = broke.players.map { if (it.id == "a") it.copy(score = CHEATING_COST) else it })
        assertEquals(1, Engine.playableGamblers(rich, "a").size)
    }

    @Test
    fun `playing one takes it out of the hand and turns it face up`() {
        val state = table("shuffle")
        val cardId = state.player("a")!!.gamblers.single().id
        val result = tr(state, GameAction.PlayGambler("a", cardId))

        assertTrue(result.state.player("a")!!.gamblers.isEmpty())
        val played = result.events.filterIsInstance<GameEvent.GamblerPlayed>().single()
        assertEquals("shuffle", played.card.defId)
        assertTrue(result.state.discard.any { it.id == cardId }, "a spent card goes to the discard pile")
    }

    @Test
    fun `a card nobody offered you cannot be played`() {
        val state = table("shuffle")
        // b has nothing, and asking for a's card by id must not work either.
        val cardId = state.player("a")!!.gamblers.single().id
        assertEquals(state, t(state, GameAction.PlayGambler("b", cardId)))
        assertEquals(state, t(state, GameAction.PlayGambler("a", "g-nonexistent")))
    }

    @Test
    fun `a card played out of turn does not move the turn`() {
        // "shuffle" is playable always, so b can play it on a's turn.
        val state = table().holding("b", gambler("shuffle"))
        val cardId = state.player("b")!!.gamblers.single().id
        assertEquals("a", state.currentPlayer?.id)

        val after = t(state, GameAction.PlayGambler("b", cardId))
        assertEquals("a", after.currentPlayer?.id, "answering a question is not taking a turn")
    }

    // ─── The cards ───

    @Test
    fun `shuffle stirs the deck without folding the discard pile back in`() {
        val deck = (1..12).map { num(it, id = "n-deck-$it") }
        val state = table("shuffle").copy(deck = deck, discard = listOf(num(9, id = "n-binned")))
        val cardId = state.player("a")!!.gamblers.single().id

        val after = t(state, GameAction.PlayGambler("a", cardId))
        assertEquals(deck.size, after.deck.size, "shuffling is not drawing")
        assertEquals(deck.map { it.id }.toSet(), after.deck.map { it.id }.toSet())
        assertTrue(after.discard.any { it.id == "n-binned" }, "the pile is somebody else's business")
    }

    @Test
    fun `draw 2 buys an extra card on the next turn, and only the next one`() {
        // Values well clear of the opening cards: a duplicate would bust the
        // draw half way through and the test would be about something else.
        val state = table("drawTwo").copy(deck = List(6) { num(it + 20, id = "n-deck-$it") })
        val cardId = state.player("a")!!.gamblers.single().id
        val armed = t(state, GameAction.PlayGambler("a", cardId))
        assertTrue(armed.player("a")!!.passives.any { it.defId == DRAW_TWO_ARMED.id })

        val before = armed.hand("a").size
        val drew = t(armed, GameAction.Hit("a"))
        assertEquals(before + 2, drew.hand("a").size)
        assertTrue(drew.player("a")!!.passives.none { it.defId == DRAW_TWO_ARMED.id }, "it is spent")
    }

    @Test
    fun `cheating pays a hundred and takes a gambler card out of the deck`() {
        val loose = gambler("shuffle", "g-in-deck")
        val state = table("cheating")
            .copy(deck = listOf(num(4), loose, num(6)))
            .let { s -> s.copy(players = s.players.map { if (it.id == "a") it.copy(score = 500) else it }) }
        val cardId = state.player("a")!!.gamblers.single().id

        val after = t(state, GameAction.PlayGambler("a", cardId))
        assertEquals(listOf("g-in-deck"), after.player("a")!!.gamblers.map { it.id })
        assertEquals(-CHEATING_COST, after.roundAdjustments["a"])
        assertFalse(after.deck.any { it.id == "g-in-deck" }, "moved, never conjured")
    }

    @Test
    fun `cheating with nothing left to cheat with hands the hundred back`() {
        val state = table("cheating")
            .copy(deck = listOf(num(4), num(6)), discard = emptyList())
            .let { s -> s.copy(players = s.players.map { if (it.id == "a") it.copy(score = 500) else it }) }
        val cardId = state.player("a")!!.gamblers.single().id

        val result = tr(state, GameAction.PlayGambler("a", cardId))
        assertEquals(0, result.state.roundAdjustments["a"] ?: 0)
        assertTrue(result.events.any { it is GameEvent.Fizzled })
    }

    @Test
    fun `fuck it is watched before it is felt`() {
        val out = t(table(FUCK_IT_ID), GameAction.Stay("a"))
            .let { s -> s.copy(players = s.players.map { if (it.id == "a") it.copy(score = 320) else it }) }
        val cardId = out.player("a")!!.gamblers.single().id

        val announced = t(out, GameAction.PlayGambler("a", cardId))
        assertEquals(320, announced.player("a")!!.score, "the score does not move while the table is reading it")
        assertEquals(1, announced.pendingOutcomes.size)

        assertEquals(0, settle(announced).player("a")!!.score)
    }

    // ─── Redirect and second opinion: the cards that are about your next draw ───

    @Test
    fun `redirect turns the next card over and asks whose it is`() {
        val state = table(REDIRECT_ID).copy(deck = listOf(num(9, id = "n-hot")))
        val cardId = state.player("a")!!.gamblers.single().id
        val armed = t(state, GameAction.PlayGambler("a", cardId))

        val result = tr(armed, GameAction.Hit("a"))
        val pending = assertNotNull(result.state.pendingAction)
        assertEquals(PHASE_REDIRECT, pending.phase)
        assertEquals("n-hot", pending.card.id)
        // The table sees the card — that is what makes the choice a choice.
        assertTrue(result.events.filterIsInstance<GameEvent.Draw>().any { it.card.id == "n-hot" })
        assertEquals(listOf("b"), pending.validTargets)
    }

    @Test
    fun `and a card handed on lands in the other hand`() {
        val state = table(REDIRECT_ID).copy(deck = listOf(num(9, id = "n-hot")))
        val cardId = state.player("a")!!.gamblers.single().id
        val armed = t(state, GameAction.PlayGambler("a", cardId))
        val asked = t(armed, GameAction.Hit("a"))

        val given = t(asked, GameAction.PlayAction("a", "b", REDIRECT_ID, PASS_IT_ON))
        assertTrue(given.hand("b").any { it.id == "n-hot" })
        assertTrue(given.hand("a").none { it.id == "n-hot" }, "you gave it away")
    }

    @Test
    fun `or stays where it was drawn`() {
        val state = table(REDIRECT_ID).copy(deck = listOf(num(9, id = "n-hot")))
        val cardId = state.player("a")!!.gamblers.single().id
        val armed = t(state, GameAction.PlayGambler("a", cardId))
        val asked = t(armed, GameAction.Hit("a"))

        val kept = t(asked, GameAction.PlayAction("a", "b", REDIRECT_ID, TAKE_IT))
        assertTrue(kept.hand("a").any { it.id == "n-hot" })
        assertNull(kept.pendingAction)
    }

    @Test
    fun `second opinion throws a card back and deals another`() {
        val state = table(SECOND_OPINION_ID)
            .copy(deck = listOf(num(1, id = "n-dull"), num(12, id = "n-better")))
        val cardId = state.player("a")!!.gamblers.single().id
        val armed = t(state, GameAction.PlayGambler("a", cardId))
        val asked = t(armed, GameAction.Hit("a"))
        assertEquals(PHASE_SECOND_OPINION, asked.pendingAction?.phase)

        val thrown = t(asked, GameAction.PlayAction("a", "a", SECOND_OPINION_ID, THROW_IT_BACK))
        assertTrue(thrown.discard.any { it.id == "n-dull" })
        val settled = t(thrown, GameAction.ForcedDraw)
        assertTrue(settled.hand("a").any { it.id == "n-better" })
    }
}
