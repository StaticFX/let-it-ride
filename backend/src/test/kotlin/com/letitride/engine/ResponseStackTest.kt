package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cards answering cards.
 *
 * The window is the interesting part rather than the counters themselves: it has
 * to open only when somebody could actually answer, ask everybody once it does,
 * shut when they have all spoken, and unwind innermost-first — and it must never
 * be possible to leave a card on it, because the table does not move while one
 * is open.
 */
class ResponseStackTest {

    private fun rollingConfig() = config(deck = DeckPresets.PURE.deck).copy(mode = GameMode.ROLLING_RULES)

    /** A table of three, mid-round, with nobody holding anything. */
    private fun table(players: List<String> = listOf("a", "b", "c")): GameState =
        startedAndDealt(rollingConfig(), players, rest = List(30) { num(it % 11 + 1) })

    private fun GameState.play(playerId: String, defId: String): GameState {
        val card = player(playerId)!!.gamblers.first { it.defId == defId }
        return t(this, GameAction.PlayGambler(playerId, card.id))
    }

    // ─── Whether the table stops at all ───

    @Test
    fun `a card nobody can answer never stops the table`() {
        val state = table().holding("a", gambler("shuffle"))
        val after = state.play("a", "shuffle")

        assertTrue(after.responseStack.isEmpty(), "there was nobody to ask")
        assertNull(after.openResponse)
    }

    @Test
    fun `a card somebody could answer stops it, and asks the whole table`() {
        val state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
        val after = state.play("a", "shuffle")

        val window = assertNotNull(after.openResponse)
        assertEquals("shuffle", window.cardDefId)
        // Everybody but the player of the card — not only the one holding the
        // answer. Asking just the holders would say who they were.
        assertEquals(listOf("b", "c"), window.awaiting)
    }

    @Test
    fun `and c being asked says nothing about what c is holding`() {
        val withCounter = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")

        // The same window, with the counter in a different hand. Whoever is
        // asked is identical either way, which is the whole of the claim.
        val elsewhere = table()
            .holding("a", gambler("shuffle"))
            .holding("c", gambler("nahhh"))
            .play("a", "shuffle")

        assertEquals(
            withCounter.openResponse?.awaiting,
            elsewhere.openResponse?.awaiting,
            "who is asked must not depend on who is holding the answer",
        )
    }

    @Test
    fun `everybody declining lets the card through`() {
        var state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")

        state = t(state, GameAction.PassResponse("b"))
        assertNotNull(state.openResponse, "c has not spoken yet")

        state = t(state, GameAction.PassResponse("c"))
        assertTrue(state.responseStack.isEmpty())
        // ...and the card is spent, having done what it does.
        assertTrue(state.discard.any { it.defId == "shuffle" })
    }

    @Test
    fun `the table will not move while a card is in flight`() {
        val state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")

        assertTrue(state.isInterrupted)
        assertEquals(state, t(state, GameAction.Hit("a")))
        assertEquals(state, t(state, GameAction.Stay("a")))
    }

    @Test
    fun `somebody who was not asked cannot answer, and nobody answers twice`() {
        val state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")

        // "a" played it; they are not asked.
        assertEquals(state, t(state, GameAction.PassResponse("a")))

        val passed = t(state, GameAction.PassResponse("b"))
        assertEquals(passed, t(passed, GameAction.PassResponse("b")))
    }

    @Test
    fun `the clock shuts the window on everybody at once`() {
        val state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")

        // One clock covers the window: a player who walked away must not hold
        // the table for the rest of it.
        val out = t(state, GameAction.Timeout("b"))
        assertTrue(out.responseStack.isEmpty())
        assertNull(out.openResponse)
    }

    // ─── The counters ───

    @Test
    fun `nullify only answers a card that is aimed at you`() {
        val state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nullify"))

        // Every gambler card written so far resolves on whoever played it, so
        // there is nothing here pointed at b and the window never opens.
        val after = state.play("a", "shuffle")
        assertTrue(after.responseStack.isEmpty(), "a card that is not aimed at you is none of your business")
    }

    /**
     * What nullify is actually for, today: a *counter* is aimed at the player
     * whose card it stops, so answering somebody's answer is exactly the case
     * the card describes.
     */
    @Test
    fun `nullify stops a counter thrown at you, and is spent doing it`() {
        var state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .holding("a", gambler("nullify"))
            .play("a", "shuffle")

        val shuffleId = state.responseStack.first().card.id
        state = state.play("b", "nahhh")

        // b's nahhh is aimed at a, so a is offered the nullify.
        val window = assertNotNull(state.openResponse)
        assertEquals("nahhh", window.cardDefId)
        assertTrue("a" in window.awaiting)
        assertEquals(listOf<String>(), Engine.respondableGamblers(state, "c"), "c holds nothing that speaks to it")

        state = state.play("a", "nullify")
        assertTrue(state.responseStack.isEmpty())

        // The nahhh was stopped, so the shuffle underneath went through...
        assertTrue(state.discard.any { it.id == shuffleId })
        // ...and the nahhh is spent rather than handed back, which is the whole
        // difference between the two cards.
        assertTrue(state.discard.any { it.defId == "nahhh" })
        assertFalse(state.player("b")!!.gamblers.any { it.defId == "nahhh" })
    }

    @Test
    fun `nahhh stops anything, and the card goes home`() {
        var state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")

        val shuffleId = state.responseStack.first().card.id
        state = state.play("b", "nahhh")
        // b answering shuts the window; nobody can answer the nahhh itself.
        assertTrue(state.responseStack.isEmpty())

        // The card is back in the hand that played it, not in the discard pile.
        assertTrue(state.player("a")!!.gamblers.any { it.id == shuffleId }, "a nahhh buys a round, it does not win one")
        assertFalse(state.discard.any { it.id == shuffleId })
        // ...and the nahhh itself is gone.
        assertTrue(state.discard.any { it.defId == "nahhh" })
    }

    @Test
    fun `a card handed home may put its owner over the cap`() {
        val full = (1..GAMBLER_HAND_LIMIT).map { gambler("shuffle", "g-held-$it") }
        var state = table()
            .holding("a", *full.toTypedArray())
            .holding("b", gambler("nahhh"))
        state = state.play("a", "shuffle")
        assertEquals(GAMBLER_HAND_LIMIT - 1, state.player("a")!!.gamblers.size)

        state = state.play("b", "nahhh")
        // Back over the line it came from. A card coming home is not a card you
        // gained — you had room for it when you played it.
        assertEquals(GAMBLER_HAND_LIMIT, state.player("a")!!.gamblers.size)
    }

    @Test
    fun `a counter is aimed at whoever played the card it stops`() {
        // Which is what gives nullify something to do while every gambler card
        // still resolves on its own player, and is why "deflect" is not here
        // yet: it would have nothing to turn round.
        val state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .play("a", "shuffle")
            .play("b", "nahhh")

        assertTrue(state.responseStack.isEmpty(), "nobody could answer the nahhh")
    }

    // ─── The stack, being a stack ───

    @Test
    fun `a counter can itself be countered`() {
        var state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .holding("c", gambler("nahhh", "g-nahhh-c"))
            .play("a", "shuffle")

        // b answers the shuffle...
        state = state.play("b", "nahhh")
        // ...and c is asked about the nahhh, because c is holding one too.
        val window = assertNotNull(state.openResponse, "the counter is a card like any other")
        assertEquals("nahhh", window.cardDefId)
        assertEquals(2, state.responseStack.size, "the pile got taller")

        // c stops the nahhh, so the shuffle goes through after all.
        state = state.play("c", "nahhh")
        assertTrue(state.responseStack.isEmpty())
        assertTrue(state.discard.any { it.defId == "shuffle" }, "the card at the bottom was never cancelled")
        // b's nahhh went home, because c's nahhh hands cards back.
        assertTrue(state.player("b")!!.gamblers.any { it.defId == "nahhh" })
    }

    @Test
    fun `copycat plays the card it is answering, for itself`() {
        var state = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("copycat"))
            .play("a", "shuffle")

        state = state.play("b", "copycat")
        assertTrue(state.responseStack.isEmpty())

        // Both happened: the copy, and then the original underneath it.
        // The copy was minted, so it never touches the deck's accounting.
        assertTrue(state.discard.any { it.defId == "copycat" })
        assertTrue(state.discard.any { it.defId == "shuffle" })
        assertFalse(state.discard.any { it.isEphemeral }, "a minted copy never reaches the pile")
    }

    // ─── Answering an ordinary action card ───
    //
    // "Nullify any actions against you, for example redirect" and "when somebody
    // wants to use a card on you" — a freeze pointed at your seat is exactly
    // what both of them describe, and for a while neither could touch one: the
    // stack held gambler cards and nothing else, so the two cards in the set
    // that say *card* could only ever answer a third of the cards in the game.

    /** A freeze drawn by "a", stopped on the pick, in a rolling-rules game. */
    private fun withFreeze(vararg holding: Pair<String, String>): GameState {
        val players = listOf("a", "b", "c")
        var dealt = startedAndDealt(
            rollingConfig(),
            players,
            openingCards = players.indices.map { num(it + 1) },
            rest = listOf(action(FREEZE.id)) + List(20) { num(it % 9 + 12, id = "n-rest-$it") },
        )
        for ((who, what) in holding) dealt = dealt.holding(who, gambler(what))
        return t(dealt, GameAction.Hit("a"))
    }

    @Test
    fun `a freeze aimed at somebody holding a nullify stops the table`() {
        var state = withFreeze("b" to "nullify")
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))

        val window = assertNotNull(state.openResponse, "a card was used on b, and b can answer it")
        assertEquals(FREEZE.id, window.cardDefId)
        assertEquals(StackKind.ACTION, window.kind)
        assertEquals(PlayerStatus.ACTIVE, state.status("b"), "and it has not gone off yet")
    }

    @Test
    fun `and nullifying it means it never happens at all`() {
        var state = withFreeze("b" to "nullify")
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = state.play("b", "nullify")

        assertTrue(state.responseStack.isEmpty())
        assertEquals(PlayerStatus.ACTIVE, state.status("b"), "b was never frozen")
        assertTrue(state.discard.any { it.defId == FREEZE.id }, "the freeze was spent all the same")
        // The turn still ends: the card was drawn on a's turn, and whatever
        // became of it that turn is over.
        assertEquals("b", state.currentPlayer?.id)
    }

    @Test
    fun `a deflected freeze goes off on whoever threw it`() {
        var state = withFreeze("b" to "deflect")
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))
        state = state.play("b", "deflect")

        assertEquals(PlayerStatus.STAYED, state.status("a"), "a froze themselves")
        assertEquals(PlayerStatus.ACTIVE, state.status("b"))
    }

    @Test
    fun `a card played on yourself is nobody's business`() {
        // "Plus 4" resolves on its drawer, so there is nothing aimed at anybody
        // and no window — a nullify in another hand never comes into it.
        var state = withFreeze("b" to "nullify")
        state = t(state, GameAction.PlayAction("a", "a", FREEZE.id))

        assertNull(state.openResponse, "a card aimed at its own player is not a card used on you")
        assertEquals(PlayerStatus.STAYED, state.status("a"))
    }

    @Test
    fun `nahhh does not answer an action card, whatever it is pointed at`() {
        // The one place the set draws the line: "when another player wants to
        // play a gamblers card". A freeze is drawn and aimed, not chosen.
        var state = withFreeze("b" to "nahhh")
        state = t(state, GameAction.PlayAction("a", "b", FREEZE.id))

        assertNull(state.openResponse)
        assertEquals(PlayerStatus.STAYED, state.status("b"), "the freeze went off, unanswered")
    }

    @Test
    fun `an action card on the stack is still a card the table owns`() {
        val start = withFreeze("b" to "nullify")
        val before = start.allCardIds().sorted()

        var state = t(start, GameAction.PlayAction("a", "b", FREEZE.id))
        assertEquals(before, state.allCardIds().sorted(), "a freeze in flight went missing")

        state = state.play("b", "nullify")
        assertEquals(before, state.allCardIds().sorted())
    }

    @Test
    fun `nothing is created or destroyed by any of it`() {
        val start = table()
            .holding("a", gambler("shuffle"))
            .holding("b", gambler("nahhh"))
            .holding("c", gambler("copycat"))
        val before = start.allCardIds().sorted()

        var state = start.play("a", "shuffle")
        // A card in flight is nobody's, and still has to be counted.
        assertEquals(before, state.allCardIds().sorted(), "a card on the stack went missing")

        state = state.play("b", "nahhh")
        assertEquals(before, state.allCardIds().sorted())

        state = state.play("c", "copycat")
        assertEquals(before, state.allCardIds().sorted())
        assertTrue(state.responseStack.isEmpty())
    }
}
