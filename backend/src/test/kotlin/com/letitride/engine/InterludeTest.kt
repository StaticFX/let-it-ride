package com.letitride.engine

import com.letitride.server.botShop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shop between rounds.
 *
 * The money is the score, which is the whole of what makes it a decision — so
 * most of what is worth checking is that a purchase comes off the scoreboard
 * rather than out of the round, that a shelf is private on the wire as well as
 * in the rules, and that a settled game does not open one on the way out.
 */
class InterludeTest {

    private fun rollingConfig(rounds: Int = 6) = config(
        deck = DeckPresets.ROLLING_RULES.deck,
        winCondition = WinCondition.ROUNDS,
        totalRounds = rounds,
    ).copy(mode = GameMode.ROLLING_RULES, deckPresetId = DeckPresets.ROLLING_RULES.id)

    /** A table sitting on the scoreboard with everybody funded, ready to shop. */
    private fun atRoundEnd(
        players: List<String> = listOf("a", "b"),
        score: Int = 500,
        rng: Rng = testRng(),
    ): GameState {
        var state = startedAndDealt(rollingConfig(), players, rest = List(30) { num(it % 11 + 1) })
        for (id in players) state = t(state, GameAction.Stay(id), rng)
        return state
            .copy(players = state.players.map { it.copy(score = score) })
            .let { t(it, GameAction.NextRound, rng) }
    }

    // ─── Opening and shutting ───

    @Test
    fun `the round after this one is on the other side of a shop`() {
        val state = atRoundEnd()
        assertEquals(GamePhase.ROUND_END, state.phase, "the shop is not a phase of its own")
        val shop = assertNotNull(state.interlude)
        assertEquals(listOf("a", "b"), shop.stock.keys.toList().sorted())
    }

    @Test
    fun `every seat gets a shelf of its own`() {
        val shop = assertNotNull(atRoundEnd().interlude)
        assertEquals(SHOP_OFFERS, shop.stock["a"]!!.size)
        assertEquals(SHOP_OFFERS, shop.stock["b"]!!.size)
        // Two shelves may hold the same card — nothing is being taken off
        // anybody — but one shelf never holds the same card twice.
        for (shelf in shop.stock.values) {
            val defs = shelf.map { it.card.defId }
            assertEquals(defs.size, defs.distinct().size, "a shelf offered the same card twice")
        }
    }

    @Test
    fun `the same seed rolls the same shelves`() {
        val first = atRoundEnd(rng = testRng(88)).interlude!!.stock.mapValues { it.value.map { o -> o.card.defId } }
        val again = atRoundEnd(rng = testRng(88)).interlude!!.stock.mapValues { it.value.map { o -> o.card.defId } }
        assertEquals(first, again, "a room has to stay replayable from its seed")
    }

    @Test
    fun `the window shuts when everybody says they are finished`() {
        var state = atRoundEnd()
        assertEquals(listOf("a", "b"), state.waitingOnShop)

        state = t(state, GameAction.FinishShopping("a"))
        assertEquals(listOf("b"), state.waitingOnShop)

        state = t(state, GameAction.FinishShopping("b"))
        assertTrue(state.waitingOnShop.isEmpty())
        // Shutting it and dealing are two steps, so the auction has somewhere to
        // be watched between them.
        state = t(state, GameAction.CloseInterlude)
        assertTrue(state.interlude!!.closed)
        assertEquals(GamePhase.ROUND_END, state.phase)

        state = t(state, GameAction.OpenRound)
        assertNull(state.interlude)
        assertEquals(GamePhase.PLAYING, state.phase)
    }

    @Test
    fun `a seat that has gone does not hold the window`() {
        val state = atRoundEnd()
            .let { s -> s.copy(players = s.players.map { if (it.id == "b") it.copy(connected = false) else it }) }
        assertEquals(listOf("a"), state.waitingOnShop, "a tab that closed cannot press a button")
    }

    @Test
    fun `a settled game opens no shop`() {
        // Last round, so the game is over rather than paused.
        var state = startedAndDealt(rollingConfig(rounds = 1), listOf("a", "b"), rest = List(20) { num(it % 9 + 1) })
        state = t(state, GameAction.Stay("a"))
        state = t(state, GameAction.Stay("b"))
        assertNotNull(state.gameWinnerId)

        state = t(state, GameAction.NextRound)
        assertEquals(GamePhase.GAME_END, state.phase)
        assertNull(state.interlude, "the results screen is the end of the evening")
    }

    @Test
    fun `the host cannot cut short a window other people are spending in`() {
        val state = atRoundEnd()
        assertEquals(state, t(state, GameAction.NextRound), "the button does nothing while the shop is open")
    }

    // ─── Buying ───

    @Test
    fun `a purchase comes off the scoreboard, not out of the round`() {
        var state = atRoundEnd()
        val offer = state.interlude!!.stock["a"]!!.first()

        state = t(state, GameAction.Buy("a", offer.id))
        assertEquals(500 - offer.price, state.player("a")!!.score)
        // Not `roundAdjustments`: between rounds there is no round to score, and
        // a purchase held there would land at the end of the *next* one.
        assertTrue(state.roundAdjustments.isEmpty())
        assertEquals(listOf(offer.card.id), state.player("a")!!.gamblers.map { it.id })
    }

    @Test
    fun `and the card the shop minted was never in the deck`() {
        val before = atRoundEnd()
        val offer = before.interlude!!.stock["a"]!!.first()
        val after = t(before, GameAction.Buy("a", offer.id))

        assertEquals(before.allCardIds().sorted(), after.allCardIds().sorted(), "the deck is not the dealer's stock")
        assertTrue(offer.card.isEphemeral)
    }

    @Test
    fun `you cannot buy off somebody else's shelf`() {
        val state = atRoundEnd()
        val theirs = state.interlude!!.stock["b"]!!.first()
        assertEquals(state, t(state, GameAction.Buy("a", theirs.id)), "private stock is private")
    }

    @Test
    fun `you cannot buy the same card twice`() {
        var state = atRoundEnd()
        val offer = state.interlude!!.stock["a"]!!.first()
        state = t(state, GameAction.Buy("a", offer.id))
        assertEquals(state, t(state, GameAction.Buy("a", offer.id)))
    }

    @Test
    fun `you cannot buy what you cannot afford`() {
        val state = atRoundEnd(score = 1)
        val offer = state.interlude!!.stock["a"]!!.first()
        assertEquals(state, t(state, GameAction.Buy("a", offer.id)))
    }

    @Test
    fun `a full tray will not take a sixth`() {
        val full = (1..GAMBLER_HAND_LIMIT).map { gambler("shuffle", "g-held-$it") }
        val state = atRoundEnd().holding("a", *full.toTypedArray())
        val offer = state.interlude!!.stock["a"]!!.first()
        assertEquals(state, t(state, GameAction.Buy("a", offer.id)), "no room is a reason as good as no money")
    }

    @Test
    fun `and nothing can be bought once the window has shut`() {
        var state = atRoundEnd()
        val offer = state.interlude!!.stock["a"]!!.first()
        state = t(state, GameAction.CloseInterlude)
        assertEquals(state, t(state, GameAction.Buy("a", offer.id)))
    }

    @Test
    fun `what you bought is still yours next round`() {
        var state = atRoundEnd()
        val offer = state.interlude!!.stock["a"]!!.first()
        state = t(state, GameAction.Buy("a", offer.id))
        state = throughInterlude(state)

        assertEquals(GamePhase.PLAYING, state.phase)
        assertEquals(listOf(offer.card.id), state.player("a")!!.gamblers.map { it.id })
    }

    // ─── The bot ───

    @Test
    fun `a jackpot is worth more to a bot than a common`() {
        val common = GamblerCatalog.byRarity(Rarity.COMMON).first()
        val jackpot = GamblerCatalog.byRarity(Rarity.JACKPOT).firstOrNull()
        assertTrue(com.letitride.server.gamblerWorth(common) > 0)
        if (jackpot != null) {
            assertTrue(com.letitride.server.gamblerWorth(jackpot) > com.letitride.server.gamblerWorth(common))
        }
    }

    @Test
    fun `a bot buys the biggest bargain it can pay for`() {
        val state = atRoundEnd(players = listOf("a", "bot"), score = 500)
        val shelf = state.interlude!!.stock["bot"]!!
        val picked = assertNotNull(botShop(state, "bot"))
        val chosen = shelf.first { it.id == picked }
        // Bargain, not cheapness: what it is worth less what it costs.
        val bargain = { o: Offer ->
            com.letitride.server.gamblerWorth(Catalog.gambler(o.card.defId)!!) - o.price
        }
        assertTrue(shelf.all { bargain(it) <= bargain(chosen) })
    }

    @Test
    fun `a bot with no room buys nothing`() {
        val full = (1..GAMBLER_HAND_LIMIT).map { gambler("shuffle", "g-held-$it") }
        val state = atRoundEnd(players = listOf("a", "bot")).holding("bot", *full.toTypedArray())
        assertNull(botShop(state, "bot"))
    }

    @Test
    fun `a bot with nothing to spend buys nothing`() {
        val state = atRoundEnd(players = listOf("a", "bot"), score = 0)
        assertNull(botShop(state, "bot"))
    }

    @Test
    fun `a bot within a round of winning does not shop at all`() {
        val state = atRoundEnd(players = listOf("a", "bot"), score = 500)
            .let { s ->
                s.copy(
                    config = s.config.copy(winCondition = WinCondition.FIRST_TO_SCORE, targetScore = 520),
                )
            }
        assertNull(botShop(state, "bot"), "the shop is a way to make points, not a way to spend a win")
    }

    @Test
    fun `a bot keeps most of what it came in with`() {
        var state = atRoundEnd(players = listOf("a", "bot"), score = 500)
        var guard = 0
        while (guard++ < 12) {
            val offerId = botShop(state, "bot") ?: break
            state = t(state, GameAction.Buy("bot", offerId))
        }
        assertTrue(state.player("bot")!!.score >= 500 * (1 - com.letitride.server.BOT_SHOP_SHARE) - 1)
        assertFalse(state.player("bot")!!.score < 0)
    }
}
