package com.letitride.engine

import com.letitride.server.botShop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plays complete games with every preset and house rule, asserting after every
 * single transition that no card was created or destroyed. Card conservation is
 * the invariant that used to break: cards knocked out of a hand simply vanished,
 * so the deck quietly shrank until a long game ran out of cards.
 */
class FullGameTest {

    private fun playToTheEnd(
        preset: DeckPreset,
        rules: List<String>,
        playerCount: Int,
        seed: Long,
        maxSteps: Int = 20_000,
        mode: GameMode = GameMode.CLASSIC,
        winCondition: WinCondition = WinCondition.FIRST_TO_SCORE,
    ): GameState {
        val rng = Rng(seed)
        val players = (0 until playerCount).map { "p$it" }
        val config = GameConfig(
            deckPresetId = preset.id,
            deck = preset.deck,
            mode = mode,
            ruleIds = rules,
            winCondition = winCondition,
            targetScore = 200,
            totalRounds = 12,
        )

        var state = t(lobby(config, players), GameAction.StartGame, rng)
        val allCards = state.allCardIds().sorted()
        assertEquals(preset.cardCount, allCards.size)

        var steps = 0
        while (state.phase != GamePhase.GAME_END) {
            assertTrue(steps++ < maxSteps, "${preset.id}/$seed did not finish in $maxSteps steps")
            val before = state
            state = step(state, rng)
            assertTrue(
                state !== before,
                "${preset.id}/$seed stalled at step $steps (phase=${before.phase}, turn=${before.currentPlayer?.id})",
            )
            assertEquals(
                allCards,
                state.allCardIds().sorted(),
                "${preset.id}/$seed lost or invented a card at step $steps",
            )
            assertHandsAreLegal(state)
        }

        assertNotNull(state.gameWinnerId)
        return state
    }

    /** Mirrors what the room's pacer does, with a simple deterministic policy. */
    private fun step(state: GameState, rng: Rng): GameState {
        // An open window comes before everything, exactly as it does in
        // `promptOf`. A card in flight interrupts the table, and an interrupted
        // table refuses to deal — so a driver that worked through its deal queue
        // first would sit on a `DealTo` the engine will not take, for ever. That
        // is reachable on the opening deal itself: an action card turned up mid
        // deal, aimed at somebody, and somebody else holding a nullify.
        state.openResponse?.let { window ->
            // Both ways, off the same seeded stream: a driver that always
            // countered would never once shut a window by everybody declining,
            // which is how most of them actually shut.
            val actor = window.awaiting.first()
            val counter = Engine.respondableGamblers(state, actor).firstOrNull()
            return if (counter != null && rng.nextBoolean()) {
                t(state, GameAction.PlayGambler(actor, counter), rng)
            } else {
                t(state, GameAction.PassResponse(actor), rng)
            }
        }

        state.pendingAction?.let { pending ->
            // Whoever the prompt is still waiting on — one player for nearly
            // every card, the whole table for the ones that ask at once. A
            // driver that only ever answered as the drawer would leave those
            // open and the table would simply stop.
            val actor = pending.waitingOn.firstOrNull() ?: pending.playerId
            val target = pending.validTargets.firstOrNull { it != actor }
                ?: pending.validTargets.firstOrNull()
                ?: actor
            // Cards that ask a question get a random answer, so a run covers
            // both sides of every coin and both directions of every spin.
            val choice = rng.pick(pending.options)
            // ...and a card that asks for cards is given ones the player is
            // actually holding, which is what a client would offer them.
            val own = state.player(actor)?.let { p -> (p.hand + p.passives).map { it.id } }.orEmpty()
            val cards = pending.validCards.filter { it in own }.take(pending.picks).ifEmpty {
                pending.validCards.take(pending.picks)
            }
            return t(state, GameAction.PlayAction(actor, target, pending.cardDefId, choice, cards), rng)
        }
        // A card that has landed goes off before anything else moves — the room
        // does this once the table has finished watching it.
        if (state.pendingOutcomes.isNotEmpty()) return t(state, GameAction.ResolveOutcome, rng)
        if (state.forcedDraws != null) return t(state, GameAction.ForcedDraw, rng)
        if (state.dealQueue.isNotEmpty()) return t(state, GameAction.DealTo(state.dealQueue.first()), rng)
        // The shop, which in rolling rules is where "next round" goes first.
        // Somebody has to finish shopping or the window never shuts, and
        // somebody has to *buy* now and then or the sweep would only ever prove
        // that a shop can be walked past.
        state.interlude?.let { shop ->
            if (shop.closed) return t(state, GameAction.OpenRound, rng)
            val shopper = state.waitingOnShop.firstOrNull()
                ?: return t(state, GameAction.CloseInterlude, rng)
            // The shipped bot's own policy, rather than a policy invented here.
            //
            // It checks room and money — the engine refuses a purchase into a
            // full tray silently, the way it refuses a mistimed hit, so a driver
            // that asked anyway would sit there asking. And it keeps something
            // back: a first attempt that spent every point it could see never
            // reached the target score in twenty thousand steps, because in a
            // game where the money *is* the score a table that buys everything
            // never wins. That is a true thing about the mode, and `botBudget`
            // is where the answer to it lives.
            val offerId = botShop(state, shopper)
            return if (offerId != null) {
                t(state, GameAction.Buy(shopper, offerId), rng)
            } else {
                t(state, GameAction.FinishShopping(shopper), rng)
            }
        }

        if (state.phase == GamePhase.ROUND_END) return t(state, GameAction.NextRound, rng)

        // Rolling rules: somebody spends a gambler card before anybody takes a
        // turn. Without this the mode's preset would play through these sweeps
        // with every tray filling up and nothing ever coming out of one — the
        // test would pass, and would have proved only that gambler cards can be
        // held. Whoever can go first, so the whole table gets used.
        for (player in state.players) {
            val playable = Engine.playableGamblers(state, player.id).firstOrNull() ?: continue
            return t(state, GameAction.PlayGambler(player.id, playable), rng)
        }

        val current = state.currentPlayer ?: return t(state, GameAction.NextRound, rng)
        // A table that will not let this seat stop is not offered a stay: the
        // engine refuses it, and a driver that kept asking would sit here for
        // ever. The bots take the same answer from the same place.
        return if (current.hand.size < 3 || !Engine.canStay(state, current)) {
            t(state, GameAction.Hit(current.id), rng)
        } else {
            t(state, GameAction.Stay(current.id), rng)
        }
    }

    private fun assertHandsAreLegal(state: GameState) {
        for (player in state.players) {
            if (player.status == PlayerStatus.BUST) continue
            // "The cooler revive" hands a hand back with its duplicates still in
            // it and makes them harmless for the rest of the round, which is the
            // whole card. This invariant predates it and has to say so.
            if (player.passives.any { it.defId == COOLER.id }) continue
            val labels = player.hand.map { it.label }
            assertEquals(
                labels.size, labels.distinct().size,
                "${player.id} is holding duplicates without having busted: $labels",
            )
            assertEquals(player.hand.sumOf { it.value }, player.handValue, "${player.id} hand total drifted")
            assertTrue(player.hand.all { it.kind == CardKind.NUMBER }, "only number cards belong in a hand")
        }
    }

    @Test
    fun `every deck preset plays a full game without losing a card`() {
        for (preset in DeckPresets.all) {
            playToTheEnd(preset, rules = emptyList(), playerCount = 4, seed = 42)
        }
    }

    @Test
    fun `games stay consistent across many shuffles`() {
        for (seed in 1L..25L) {
            playToTheEnd(DeckPresets.CHAOS, rules = emptyList(), playerCount = 5, seed = seed)
        }
    }

    @Test
    fun `every house rule survives a full game`() {
        for (rule in LobbyRules.all) {
            playToTheEnd(DeckPresets.CHAOS, rules = listOf(rule.id), playerCount = 3, seed = 7)
        }
    }

    @Test
    fun `all house rules at once still terminate`() {
        playToTheEnd(DeckPresets.CHAOS, rules = LobbyRules.all.map { it.id }, playerCount = 4, seed = 11)
    }

    /**
     * Rolling rules with the mode actually on, which is a different game from
     * the same deck played classic: "extreme" is forced, so a round can take a
     * score below nothing, and the driver spends gambler cards the moment it can.
     *
     * Several seeds because the interesting states here are the ones only a
     * particular order of draws reaches — a redirect landing on somebody's
     * duplicate, a hand filling to its cap, a second opinion throwing back the
     * last card in the deck.
     *
     * Played to a number of rounds rather than to a score, and that is about the
     * driver rather than the mode. This one plays every gambler card the moment
     * it can, which includes playing "fuck it" every single time anybody goes
     * out — so every score snaps back to nothing and the race to two hundred is
     * unwinnable by construction. Five hundred and eighty-six rounds of it went
     * by before this was written down. A rounds-limited game exercises exactly
     * the same machinery and is guaranteed to end.
     */
    @Test
    fun `rolling rules plays out under every shuffle`() {
        for (seed in 1L..12L) {
            val state = playToTheEnd(
                DeckPresets.ROLLING_RULES,
                rules = emptyList(),
                playerCount = 4,
                seed = seed,
                mode = GameMode.ROLLING_RULES,
                winCondition = WinCondition.ROUNDS,
            )
            assertTrue(
                state.players.all { it.gamblers.size <= Engine.gamblerLimitFor(state, it.id) },
                "somebody ended the game holding more than they may",
            )
        }
    }

    /** ...and with every house rule stacked on top of it. */
    @Test
    fun `rolling rules survives all the house rules at once`() {
        playToTheEnd(
            DeckPresets.ROLLING_RULES,
            rules = LobbyRules.all.map { it.id },
            playerCount = 4,
            seed = 17,
            mode = GameMode.ROLLING_RULES,
            winCondition = WinCondition.ROUNDS,
        )
    }

    @Test
    fun `two players is enough`() {
        playToTheEnd(DeckPresets.FLIP7, rules = emptyList(), playerCount = 2, seed = 3)
    }

    @Test
    fun `a long game recycles the discard pile instead of running dry`() {
        val rng = Rng(99)
        val config = GameConfig(
            deckPresetId = DeckPresets.FLIP7.id,
            deck = DeckPresets.FLIP7.deck,
            winCondition = WinCondition.FIRST_TO_SCORE,
            targetScore = 800,
        )
        var state = t(lobby(config, listOf("a", "b", "c", "d", "e")), GameAction.StartGame, rng)
        var reshuffles = 0
        var steps = 0
        while (state.phase != GamePhase.GAME_END && steps++ < 50_000) {
            val result = tr(state, stepAction(state), rng)
            reshuffles += result.events.count { it is GameEvent.DeckReshuffled }
            if (result.state === state) break
            state = result.state
        }
        assertEquals(GamePhase.GAME_END, state.phase)
        assertTrue(reshuffles > 0, "a game to 800 must have recycled the pile at least once")
    }

    private fun stepAction(state: GameState): GameAction {
        state.pendingAction?.let { pending ->
            val actor = pending.waitingOn.firstOrNull() ?: pending.playerId
            val target = pending.validTargets.firstOrNull { it != actor }
                ?: pending.validTargets.firstOrNull()
                ?: actor
            val own = state.player(actor)?.let { p -> (p.hand + p.passives).map { it.id } }.orEmpty()
            val cards = pending.validCards.filter { it in own }.take(pending.picks).ifEmpty {
                pending.validCards.take(pending.picks)
            }
            return GameAction.PlayAction(actor, target, pending.cardDefId, pending.options.firstOrNull(), cards)
        }
        if (state.forcedDraws != null) return GameAction.ForcedDraw
        if (state.dealQueue.isNotEmpty()) return GameAction.DealTo(state.dealQueue.first())
        if (state.phase == GamePhase.ROUND_END) return GameAction.NextRound
        val current = state.currentPlayer ?: return GameAction.NextRound
        return if (current.hand.size < 4) GameAction.Hit(current.id) else GameAction.Stay(current.id)
    }
}
