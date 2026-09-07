package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ids for cards that were never dealt — see [Ctx.mint].
 *
 * The counter used to live on the transition, so it started again at nought
 * every time and two cards minted a turn apart shared an id. Everything that
 * keys on a card id then has two cards it cannot tell apart, which is what
 * stopped a second mutate from ever opening its shop.
 */
class MintTest {

    @Test
    fun `two cards minted in two transitions do not share an id`() {
        val deck = DeckConfig(numberCards = DeckPresets.PURE.deck.numberCards, actionCards = listOf(JUST_ONE_MORE.id))
        var state = startedAndDealt(
            config = config(deck = deck),
            openingCards = listOf(num(4), num(6)),
            rest = listOf(action(JUST_ONE_MORE.id, id = "j1"), action(JUST_ONE_MORE.id, id = "j2")),
        )

        state = t(state, GameAction.Hit("a"))
        val mine = state.player("a")!!.passives.single().id
        state = t(state, GameAction.Hit("b"))
        val theirs = state.player("b")!!.passives.single().id

        assertNotEquals(mine, theirs)
        assertTrue(mine.startsWith("tmp-"), "and both are still minted")
        assertTrue(theirs.startsWith("tmp-"))
    }

    @Test
    fun `two prompts raised in two transitions do not share a card id`() {
        val state = startedAndDealt(openingCards = listOf(num(4), num(6)))
        val first = Ctx(state, testRng()).apply { raisePrompt(MUTATE_ID, "a", PHASE_BUY, listOf("a")) }
        val second = Ctx(first.state, testRng()).apply { raisePrompt(MUTATE_ID, "b", PHASE_BUY, listOf("b")) }

        assertNotEquals(
            first.state.pendingAction!!.card.id,
            second.state.pendingAction!!.card.id,
            "the client keys its answer on this id — two prompts sharing one is a prompt that cannot be answered",
        )
    }

    @Test
    fun `every card on the table has an id of its own`() {
        // Two coin flips called right in one round: both mint a x2, and the two
        // of them end up side by side in the same modifier row.
        val deck = DeckConfig(numberCards = DeckPresets.PURE.deck.numberCards, actionCards = times(COIN_FLIP_ID, 2))
        var state = startedAndDealt(
            config = config(deck = deck),
            openingCards = listOf(num(4), num(6)),
            rest = listOf(action(COIN_FLIP_ID, id = "c1"), action(COIN_FLIP_ID, id = "c2")),
        )

        for (round in 1..2) {
            state = t(state, GameAction.Hit("a"))
            val call = state.pendingAction?.options?.first() ?: break
            // Whichever way it lands: a bust ends the run, a win mints a x2.
            state = settle(t(state, GameAction.PlayAction("a", "a", COIN_FLIP_ID, call)))
            if (state.status("a") != PlayerStatus.ACTIVE) break
            state = state.copy(turnIndex = 0)
        }

        val ids = state.players.flatMap { it.hand + it.passives }.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "two cards on the table are wearing the same id")
    }
}

private fun times(id: String, n: Int) = List(n) { id }
