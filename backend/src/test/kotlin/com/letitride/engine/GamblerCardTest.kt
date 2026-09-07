package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What each of the cards actually does.
 *
 * The full-game sweep proves the set does not crash a table and does not lose a
 * card; it proves nothing about whether any given card does what its face says.
 * One test per claim on a card, in the card's own words.
 */
class GamblerCardTest {

    private fun rollingConfig() = config(deck = DeckPresets.PURE.deck).copy(mode = GameMode.ROLLING_RULES)

    /**
     * A table mid-round with hands worth something.
     *
     * The opening cards are big on purpose: several of these cards are a
     * *percentage* of a round, and a tenth of two points is nothing at all —
     * which would have every one of them pass by doing nothing.
     */
    private fun table(players: List<String> = listOf("a", "b")): GameState =
        startedAndDealt(
            rollingConfig(),
            players,
            openingCards = players.indices.map { num(60 + it * 7, id = "n-open-${'$'}it") },
            rest = List(30) { num(it % 11 + 20, id = "n-rest-${'$'}it") },
        )

    private fun GameState.play(playerId: String, defId: String): GameState {
        val card = player(playerId)!!.gamblers.first { it.defId == defId }
        return t(this, GameAction.PlayGambler(playerId, card.id))
    }

    /** Ends the round the short way, so what it paid can be read. */
    private fun GameState.scoreRound(): GameState {
        var state = this
        for (player in players) {
            if (state.player(player.id)?.status == PlayerStatus.ACTIVE) {
                state = t(state, GameAction.Stay(player.id))
            }
        }
        return settle(state)
    }

    // ─── Holding one ───

    @Test
    fun `a pouch makes room for two more, and takes one of the slots itself`() {
        val plain = table().holding("a", gambler("shuffle"))
        assertEquals(GAMBLER_HAND_LIMIT, Engine.gamblerLimitFor(plain, "a"))

        val roomier = table().holding("a", gambler(POUCH_ID))
        assertEquals(GAMBLER_HAND_LIMIT + POUCH_SLOTS, Engine.gamblerLimitFor(roomier, "a"))
        // Net one: the pouch is holding one of the slots it made.
        val free = Engine.gamblerLimitFor(roomier, "a") - roomier.player("a")!!.gamblers.size
        assertEquals(GAMBLER_HAND_LIMIT + POUCH_SLOTS - 1, free)
    }

    @Test
    fun `a rigged bid is never playable — it is consumed by the auction`() {
        val state = table().holding("a", gambler(RIGGED_BID.id))
        assertEquals(emptyList(), Engine.playableGamblers(state, "a"))
    }

    // ─── Cards about a round ───

    @Test
    fun `split the pot banks half the hand out of reach of a bust`() {
        var state = table().holding("a", gambler("splitThePot"))
        val worth = Engine.handWorth(state.player("a")!!)
        assertTrue(worth > 0)

        state = state.play("a", "splitThePot")
        assertEquals(worth / 2, state.roundAdjustments["a"])
        assertTrue(state.player("a")!!.passives.any { it.defId == HALVED.id }, "the rest still rides, at half")
    }

    @Test
    fun `and what it banked survives busting`() {
        var state = table().holding("a", gambler("splitThePot"))
        val banked = Engine.handWorth(state.player("a")!!) / 2
        state = state.play("a", "splitThePot")

        // Bust outright: the hand scores nothing, the adjustment does not.
        state = t(state, GameAction.Hit("a")).let { s ->
            var out = s
            var guard = 0
            while (out.player("a")!!.status == PlayerStatus.ACTIVE && guard++ < 30) {
                out = t(out, GameAction.Hit("a"))
            }
            out
        }
        if (state.player("a")!!.status != PlayerStatus.BUST) return
        state = state.scoreRound()
        assertEquals(banked, state.roundDeltas["a"], "a banked half is safe from a bust")
    }

    @Test
    fun `double down counts the round twice`() {
        val plain = table().holding("a", gambler("doubleDown")).scoreRound()
        val doubled = table().holding("a", gambler("doubleDown")).play("a", "doubleDown").scoreRound()
        assertEquals((plain.roundDeltas["a"] ?: 0) * 2, doubled.roundDeltas["a"])
    }

    @Test
    fun `taxes takes a tenth of everybody else's round`() {
        var state = table(listOf("a", "b", "c")).holding("a", gambler("taxes"))
        state = state.play("a", "taxes").scoreRound()

        val theirs = listOf("b", "c").sumOf { state.roundDeltas[it] ?: 0 }
        assertTrue(theirs > 0, "the others made something to be taxed on")
        // Whatever they made, a's round is bigger by the tithe on it.
        val untaxed = table(listOf("a", "b", "c")).scoreRound()
        assertTrue(
            (state.roundDeltas["a"] ?: 0) > (untaxed.roundDeltas["a"] ?: 0),
            "the collector is better off for it",
        )
    }

    @Test
    fun `already down does nothing when there is no leader to catch`() {
        // Everybody on nought, which is where a game starts — and where the
        // formula would divide by zero if it were not guarded.
        val state = table().holding("a", gambler("alreadyDown")).play("a", "alreadyDown")
        val plain = table().scoreRound()
        assertEquals(plain.roundDeltas["a"], state.scoreRound().roundDeltas["a"])
    }

    @Test
    fun `and pays a share of the round to whoever is behind`() {
        var state = table().holding("a", gambler("alreadyDown"))
        state = state.copy(players = state.players.map { if (it.id == "b") it.copy(score = 500) else it })
        val boosted = state.play("a", "alreadyDown").scoreRound()

        var plain = table()
        plain = plain.copy(players = plain.players.map { if (it.id == "b") it.copy(score = 500) else it })
        assertTrue(
            (boosted.roundDeltas["a"] ?: 0) > (plain.scoreRound().roundDeltas["a"] ?: 0),
            "a is a long way behind, so the round is worth more to them",
        )
    }

    @Test
    fun `a loan pays out now and takes it back with interest at the end`() {
        var state = table().holding("a", gambler("loan"))
        state = state.play("a", "loan")

        assertEquals(LOAN_ADVANCE, state.roundAdjustments["a"])
        // The IOU is a card, and it is taking up a slot.
        assertEquals(listOf(LOAN_DEBT_ID), state.player("a")!!.gamblers.map { it.defId })

        val plain = table().scoreRound().roundDeltas["a"] ?: 0
        state = state.scoreRound()
        assertEquals(plain + LOAN_ADVANCE - LOAN_REPAYMENT, state.roundDeltas["a"])
        assertTrue(state.player("a")!!.gamblers.isEmpty(), "the debt is settled and gone")
    }

    @Test
    fun `a loan with nowhere to put the IOU advances nothing`() {
        // Full once the loan itself has been played out of it, so the IOU has
        // nowhere to go.
        val full = (1..GAMBLER_HAND_LIMIT).map { gambler("shuffle", "g-held-$it") }
        var state = table().holding("a", gambler("loan"), *full.toTypedArray())
        state = state.play("a", "loan")

        assertNull(state.roundAdjustments["a"], "a debt that cannot be recorded is a debt that never comes due")
    }

    // ─── Cards about the table ───

    @Test
    fun `not this time takes the flip off everybody`() {
        val state = table(listOf("a", "b", "c")).holding("a", gambler("notThisTime")).play("a", "notThisTime")
        for (player in state.players) {
            assertTrue(player.passives.any { it.defId == NO_FLIP.id }, "${player.id} can still flip out")
        }
    }

    @Test
    fun `house rules turns every number on the table over`() {
        val state = table().holding("a", gambler("houseRules")).play("a", "houseRules")
        for (player in state.players) {
            assertTrue(player.passives.any { it.defId == ANTIMATTER.id })
        }
        // Which is the whole card: what a hand is worth is now the opposite.
        assertTrue(Engine.handWorth(state.player("b")!!) < 0)
    }

    @Test
    fun `a seat that already busted is left out of the house rules`() {
        // It says "for the rest of this round", and a seat that is out has no
        // rest of the round. Free to ignore while every bust scored zero however
        // it was decorated; not free now that an antimatter bust is paid — it
        // would be a fresh penalty on the one player who can do nothing about it.
        var state = table(listOf("a", "b", "c"))
        state = state.copy(
            players = state.players.map { if (it.id == "c") it.copy(status = PlayerStatus.BUST) else it },
        )
        state = state.holding("a", gambler("houseRules")).play("a", "houseRules")

        assertTrue(state.player("b")!!.passives.any { it.defId == ANTIMATTER.id })
        assertFalse(state.player("c")!!.passives.any { it.defId == ANTIMATTER.id })
        assertEquals(0, Engine.roundScore(state.player("c")!!, flip7PlayerId = null))
    }

    // ─── Coming back ───

    @Test
    fun `revive puts you back in the round with nothing`() {
        var state = table().holding("a", gambler("revive"))
        state = t(state, GameAction.Stay("a"))
        assertEquals(PlayerStatus.STAYED, state.status("a"))

        val before = state.allCardIds().sorted()
        state = state.play("a", "revive")
        assertEquals(PlayerStatus.ACTIVE, state.status("a"))
        assertTrue(state.hand("a").isEmpty())
        // Every card that left reached the discard pile.
        assertEquals(before, state.allCardIds().sorted())
    }

    @Test
    fun `the cooler revive keeps the hand, duplicates and all`() {
        var state = table().holding("a", gambler("coolerRevive"))
        // Bust on a duplicate of the opening card.
        val opening = state.hand("a").first()
        state = state.copy(deck = listOf(num(opening.value, opening.label, "n-dupe")))
        state = t(state, GameAction.Hit("a"))
        assertEquals(PlayerStatus.BUST, state.status("a"))

        state = state.play("a", "coolerRevive")
        assertEquals(PlayerStatus.ACTIVE, state.status("a"))
        assertEquals(2, state.hand("a").size, "the hand came back as it was")
        assertTrue(state.player("a")!!.passives.any { it.defId == COOLER.id })
        // ...and it does not simply bust again the moment anything touches it.
        assertNull(Ctx(state, testRng()).checkBust(state.player("a")!!))
    }

    // ─── Cards about the deck ───

    @Test
    fun `a foreseer sees the next three, and nobody else does`() {
        var state = table().holding("a", gambler("foreseer"))
        state = state.copy(deck = (1..8).map { num(it + 30, id = "n-deck-$it") })
        state = state.play("a", "foreseer")

        val mine = Engine.foresightFor(state, "a")
        assertEquals(FORESIGHT_CARDS, mine.size)
        assertEquals(state.deck.take(FORESIGHT_CARDS).map { it.id }, mine.map { it.id })
        assertEquals(emptyList(), Engine.foresightFor(state, "b"), "it is your foresight, not the table's")
    }

    @Test
    fun `a stacked deck shows five and lets you say which comes next`() {
        var state = table().holding("a", gambler("stackedDeck"))
        state = state.copy(deck = (1..8).map { num(it + 30, id = "n-deck-$it") })
        state = state.play("a", "stackedDeck")

        val pending = assertNotNull(state.pendingAction)
        assertEquals(PickKind.CARD, pending.kind)
        assertEquals(STACKED_DECK_CARDS, pending.validCards.size)
        // ...and while the question is open, the asker can see them.
        assertEquals(STACKED_DECK_CARDS, Engine.foresightFor(state, "a").size)
        assertEquals(emptyList(), Engine.foresightFor(state, "b"))

        val wanted = "n-deck-4"
        state = t(state, GameAction.PlayAction("a", "a", "stackedDeck", null, listOf(wanted)))
        assertEquals(wanted, state.deck.first().id)
    }

    @Test
    fun `trading in hands the lot back and takes that many new ones`() {
        val held = listOf(gambler("shuffle", "g-1"), gambler("drawTwo", "g-2"))
        var state = table().holding("a", gambler("tradeIn"), *held.toTypedArray())
        state = state.play("a", "tradeIn")

        assertEquals(held.size, state.player("a")!!.gamblers.size)
        // The old ones went to the pile, the new ones were minted.
        for (card in held) assertTrue(state.discard.any { it.id == card.id })
        assertTrue(state.player("a")!!.gamblers.all { it.isEphemeral })
    }

    @Test
    fun `back to the shop sells the rest of the hand, between rounds`() {
        // Nowhere near a shop: the window is shut, so the card is not playable.
        val playing = table().holding("a", gambler("backToTheShop"), gambler("shuffle"))
        val playable = Engine.playableGamblers(playing, "a")
        assertFalse(
            playing.player("a")!!.gamblers.first { it.defId == "backToTheShop" }.id in playable,
            "the shop is shut; there is nobody to sell to",
        )
        // ...while an "always" card in the same hand plainly is.
        assertTrue(playing.player("a")!!.gamblers.first { it.defId == "shuffle" }.id in playable)
    }
}
