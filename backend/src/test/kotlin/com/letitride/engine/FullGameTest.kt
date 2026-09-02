package com.letitride.engine

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
    ): GameState {
        val rng = Rng(seed)
        val players = (0 until playerCount).map { "p$it" }
        val config = GameConfig(
            deckPresetId = preset.id,
            deck = preset.deck,
            ruleIds = rules,
            winCondition = WinCondition.FIRST_TO_SCORE,
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
        state.pendingAction?.let { pending ->
            val target = state.players
                .firstOrNull { it.status == PlayerStatus.ACTIVE && it.id != pending.playerId }
                ?.id ?: pending.playerId
            return t(state, GameAction.PlayAction(pending.playerId, target, pending.cardDefId), rng)
        }
        if (state.forcedDraws != null) return t(state, GameAction.ForcedDraw, rng)
        if (state.dealQueue.isNotEmpty()) return t(state, GameAction.DealTo(state.dealQueue.first()), rng)
        if (state.phase == GamePhase.ROUND_END) return t(state, GameAction.NextRound, rng)

        val current = state.currentPlayer ?: return t(state, GameAction.NextRound, rng)
        return if (current.hand.size < 3) {
            t(state, GameAction.Hit(current.id), rng)
        } else {
            t(state, GameAction.Stay(current.id), rng)
        }
    }

    private fun assertHandsAreLegal(state: GameState) {
        for (player in state.players) {
            if (player.status == PlayerStatus.BUST) continue
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
            val target = state.players
                .firstOrNull { it.status == PlayerStatus.ACTIVE && it.id != pending.playerId }
                ?.id ?: pending.playerId
            return GameAction.PlayAction(pending.playerId, target, pending.cardDefId)
        }
        if (state.forcedDraws != null) return GameAction.ForcedDraw
        if (state.dealQueue.isNotEmpty()) return GameAction.DealTo(state.dealQueue.first())
        if (state.phase == GamePhase.ROUND_END) return GameAction.NextRound
        val current = state.currentPlayer ?: return GameAction.NextRound
        return if (current.hand.size < 4) GameAction.Hit(current.id) else GameAction.Stay(current.id)
    }
}
