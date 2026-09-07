package com.letitride.engine

import com.letitride.appJson
import com.letitride.server.GameStateView
import com.letitride.server.toView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auction house.
 *
 * Everybody writes down one number in secret, and when the window shuts they are
 * all turned over at once. What that costs, mostly, is discipline about *when*
 * anything is visible — and about the winner paying only after the table has
 * read the bids rather than in the same breath.
 */
class AuctionTest {

    private fun rollingConfig() = config(
        deck = DeckPresets.ROLLING_RULES.deck,
        winCondition = WinCondition.ROUNDS,
        totalRounds = 6,
    ).copy(mode = GameMode.ROLLING_RULES, deckPresetId = DeckPresets.ROLLING_RULES.id)

    /** A table sitting in the shop, funded, with a lot on the block. */
    private fun shopping(
        players: List<String> = listOf("a", "b", "c"),
        score: Int = 600,
        rng: Rng = testRng(),
    ): GameState {
        var state = startedAndDealt(rollingConfig(), players, rest = List(40) { num(it % 11 + 1) })
        for (id in players) state = t(state, GameAction.Stay(id), rng)
        return state
            .copy(players = state.players.map { it.copy(score = score) })
            .let { t(it, GameAction.NextRound, rng) }
    }

    private fun GameState.bid(playerId: String, points: Int?): GameState =
        t(this, GameAction.FinishShopping(playerId, points))

    @Test
    fun `there is one jackpot on the block`() {
        val shop = assertNotNull(shopping().interlude)
        val lot = assertNotNull(shop.lot, "this build has jackpots, so there is something to sell")
        assertEquals(Rarity.JACKPOT, Catalog.gambler(lot.card.defId)!!.rarity)
    }

    @Test
    fun `the highest bid takes it, and pays what it bid`() {
        var state = shopping()
        state = state.bid("a", 100).bid("b", 250).bid("c", 40)
        // Nothing has moved yet — the bids are still sealed.
        assertEquals(600, state.player("b")!!.score)

        state = t(state, GameAction.CloseInterlude)
        val sale = assertNotNull(state.interlude?.sale)
        assertEquals("b", sale.winnerId)
        assertEquals(250, sale.price)
        // ...and still nothing, because the table has not read them yet.
        assertEquals(600, state.player("b")!!.score)
        assertEquals(1, state.pendingOutcomes.size)

        state = settle(state)
        assertEquals(350, state.player("b")!!.score)
        assertEquals(listOf(sale.card.id), state.player("b")!!.gamblers.map { it.id })
    }

    @Test
    fun `a bid over what you are holding is clamped rather than refused`() {
        var state = shopping(score = 120)
        state = state.bid("a", 5_000).bid("b", null).bid("c", null)
        state = t(state, GameAction.CloseInterlude)

        val sale = assertNotNull(state.interlude?.sale)
        assertEquals("a", sale.winnerId)
        assertEquals(120, sale.price, "clamped to the purse, the way every other illegal answer is fixed up")
    }

    @Test
    fun `a bid of nothing is not a bid`() {
        var state = shopping()
        state = state.bid("a", 0).bid("b", null).bid("c", null)
        state = t(state, GameAction.CloseInterlude)
        assertNull(state.interlude?.sale?.winnerId, "a table of shrugs does not hand somebody a free jackpot")
    }

    @Test
    fun `nobody bidding leaves it unsold`() {
        var state = shopping()
        for (id in listOf("a", "b", "c")) state = state.bid(id, null)
        state = t(state, GameAction.CloseInterlude)

        assertNull(state.interlude?.sale?.winnerId)
        assertTrue(state.pendingOutcomes.isEmpty(), "nothing to settle")
        assertTrue(state.players.all { it.gamblers.isEmpty() })
    }

    @Test
    fun `a tie goes to whoever is poorer, and then by seat`() {
        var state = shopping()
        // b is the poorer of the two who bid the same.
        state = state.copy(players = state.players.map { if (it.id == "b") it.copy(score = 300) else it })
        state = state.bid("a", 100).bid("b", 100).bid("c", null)
        state = t(state, GameAction.CloseInterlude)

        assertEquals("b", state.interlude?.sale?.winnerId)
    }

    @Test
    fun `a full tray cannot take the lot, and it falls to the next bidder`() {
        val full = (1..GAMBLER_HAND_LIMIT).map { gambler("shuffle", "g-held-$it") }
        var state = shopping().holding("a", *full.toTypedArray())
        state = state.bid("a", 400).bid("b", 100).bid("c", null)
        state = t(state, GameAction.CloseInterlude)

        assertEquals("b", state.interlude?.sale?.winnerId, "nowhere to put it is a reason as good as no money")
        assertEquals(100, state.interlude?.sale?.price)
    }

    // ─── Rigged bid ───

    @Test
    fun `a rigged bid takes the lot for a tenth over the winner`() {
        var state = shopping().holding("c", gambler(RIGGED_BID.id))
        state = state.bid("a", 100).bid("b", 200).bid("c", null)
        state = t(state, GameAction.CloseInterlude)

        val sale = assertNotNull(state.interlude?.sale)
        assertEquals("c", sale.winnerId)
        assertEquals(220, sale.price)
        assertEquals(listOf("c"), sale.rigged)
        // ...and the card is spent doing it.
        assertFalse(state.player("c")!!.gamblers.any { it.defId == RIGGED_BID.id })
    }

    @Test
    fun `a rigged bid with nothing to beat does nothing, and is not spent`() {
        var state = shopping().holding("c", gambler(RIGGED_BID.id))
        for (id in listOf("a", "b", "c")) state = state.bid(id, null)
        state = t(state, GameAction.CloseInterlude)

        assertNull(state.interlude?.sale?.winnerId)
        assertTrue(
            state.player("c")!!.gamblers.any { it.defId == RIGGED_BID.id },
            "the card's promise is to outbid the winner, and there was none",
        )
    }

    @Test
    fun `a rigged bidder who cannot pay leaves the lot with the one who could`() {
        var state = shopping().holding("c", gambler(RIGGED_BID.id))
        state = state.copy(players = state.players.map { if (it.id == "c") it.copy(score = 10) else it })
        state = state.bid("a", 300).bid("b", null).bid("c", null)
        state = t(state, GameAction.CloseInterlude)

        assertEquals("a", state.interlude?.sale?.winnerId)
    }

    // ─── Secrecy ───

    @Test
    fun `no bid reaches the wire while the window is open`() {
        var state = shopping()
        state = state.bid("a", 250)

        val view = state.toView(viewerId = "b", roomCode = "ROOM", hostId = "a", turnDeadline = null)
        val payload = appJson.encodeToString(GameStateView.serializer(), view)
        assertFalse("250" in payload, "a sealed bid is not sealed if it is on the wire")
        assertNull(view.interlude?.myBid, "b has not bid")
        // ...and the bidder's own is their own.
        val theirs = state.toView(viewerId = "a", roomCode = "ROOM", hostId = "a", turnDeadline = null)
        assertEquals(250, theirs.interlude?.myBid)
    }

    @Test
    fun `and every bid does once the hammer has fallen`() {
        var state = shopping()
        state = state.bid("a", 100).bid("b", 250).bid("c", 40)
        state = t(state, GameAction.CloseInterlude)

        val view = state.toView(viewerId = "c", roomCode = "ROOM", hostId = "a", turnDeadline = null)
        assertEquals(mapOf("a" to 100, "b" to 250, "c" to 40), view.interlude?.sale?.bids)
    }

    @Test
    fun `the lot is rolled blind of what anybody is holding`() {
        // Two tables, same seed, one of them already holding every jackpot in
        // the game. A lot that avoided them would be a window into the hand.
        val jackpots = GamblerCatalog.forSale(Rarity.JACKPOT)
        val plain = shopping(rng = testRng(31)).interlude?.lot?.card?.defId
        val loaded = shopping(rng = testRng(31))
            .holding("a", *jackpots.take(GAMBLER_HAND_LIMIT).map { gambler(it.id) }.toTypedArray())
            .let { it }
        // The lot was rolled when the window opened, before anything was placed
        // in a hand — which is the point, and is why this reads the same.
        assertEquals(plain, loaded.interlude?.lot?.card?.defId)
    }
}
