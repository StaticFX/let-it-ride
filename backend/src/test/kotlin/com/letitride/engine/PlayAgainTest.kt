package com.letitride.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Again!" from the results screen.
 *
 * The table stays together and the game does not: everybody who is still in the
 * room is put back in its lobby with nothing in front of them and nothing on the
 * board, and the settings they played under are still the settings.
 */
class PlayAgainTest {

    /** A game that has been played and won, with the wreckage still on the table. */
    private fun finished(players: List<String> = listOf("a", "b", "c")): GameState {
        val started = t(lobby(config(), players), GameAction.StartGame)
        return started.copy(
            phase = GamePhase.GAME_END,
            round = 3,
            turnIndex = 2,
            roundStartPlayer = 1,
            gameWinnerId = "a",
            roundWinnerId = "a",
            flip7PlayerId = "a",
            roundDeltas = mapOf("a" to 30, "b" to 12),
            roundAdjustments = mapOf("b" to -5),
            roundTolls = mapOf("c" to 10),
            discard = listOf(num(4, id = "spent")),
            minted = 7,
            stackCounter = 4,
            players = started.players.map {
                when (it.id) {
                    "a" -> it.copy(score = 210, hand = listOf(num(9, id = "kept")), handValue = 9)
                    "b" -> it.copy(
                        score = 80,
                        status = PlayerStatus.BUST,
                        bustReason = "duplicate",
                        hand = listOf(num(3, id = "wreck")),
                        handValue = 3,
                        passives = listOf(passive(DISCORDIA.id, id = "the-discordia")),
                        skipNextTurn = true,
                    )

                    else -> it.copy(
                        score = 55,
                        status = PlayerStatus.STAYED,
                        gamblers = listOf(gambler(NULLIFY.id, id = "held-back")),
                    )
                }
            },
        )
    }

    @Test
    fun `a finished game goes back to its own lobby with the same people in it`() {
        val after = t(finished(), GameAction.PlayAgain)

        assertEquals(GamePhase.LOBBY, after.phase)
        assertEquals(listOf("a", "b", "c"), after.players.map { it.id }, "everybody kept their seat")
        assertEquals(finished().config, after.config, "and the settings they agreed on")
    }

    @Test
    fun `nothing anybody won or was carrying survives it`() {
        val after = t(finished(), GameAction.PlayAgain)

        for (player in after.players) {
            assertEquals(0, player.score, "${player.id} brought last game's score into this one")
            assertEquals(PlayerStatus.ACTIVE, player.status)
            assertEquals(emptyList(), player.hand)
            assertEquals(emptyList(), player.passives)
            // The hidden hand outlives a round and must not outlive a game: the
            // next one builds a new deck, and a card carried over would exist
            // twice before anybody had taken a turn. Same reason `startGame`
            // sweeps it.
            assertEquals(emptyList(), player.gamblers)
            assertEquals(0, player.handValue)
            assertNull(player.bustReason)
            assertTrue(!player.skipNextTurn)
        }

        assertNull(after.gameWinnerId)
        assertNull(after.roundWinnerId)
        assertNull(after.flip7PlayerId)
        assertEquals(0, after.round)
        assertEquals(0, after.turnIndex)
        assertEquals(0, after.roundStartPlayer)
        assertEquals(emptyList(), after.deck)
        assertEquals(emptyList(), after.discard)
        assertEquals(emptyList(), after.dealQueue)
        assertEquals(emptyMap(), after.roundDeltas)
        assertEquals(emptyMap(), after.roundAdjustments)
        assertEquals(emptyMap(), after.roundTolls)
    }

    @Test
    fun `the counters that name cards keep climbing across it`() {
        // Nothing minted survives a restart, but an id handed out twice is two
        // cards that believe they are the same card, and the room lives longer
        // than the game does.
        val after = t(finished(), GameAction.PlayAgain)
        assertEquals(7, after.minted)
        assertEquals(4, after.stackCounter)
    }

    @Test
    fun `a seat whose tab has gone is not carried into the next game`() {
        val before = finished().let { state ->
            state.copy(
                players = state.players.map {
                    when (it.id) {
                        "b" -> it.copy(connected = false)
                        "c" -> it.copy(isBot = true, connected = false)
                        else -> it
                    }
                },
            )
        }

        val after = t(before, GameAction.PlayAgain)

        // A seat nobody is behind would be dealt cards, hold the turn clock for
        // its full run every round and count against the room filling up. A bot
        // has never had a tab and stays exactly where it was sitting.
        assertEquals(listOf("a", "c"), after.players.map { it.id })
        assertTrue(after.player("c")!!.isBot)
    }

    @Test
    fun `it is refused anywhere but the results screen`() {
        val lobby = lobby(config(), listOf("a", "b"))
        assertEquals(lobby, t(lobby, GameAction.PlayAgain))

        val playing = t(lobby, GameAction.StartGame)
        assertEquals(playing, t(playing, GameAction.PlayAgain))

        val betweenRounds = playing.copy(phase = GamePhase.ROUND_END)
        assertEquals(betweenRounds, t(betweenRounds, GameAction.PlayAgain))
    }

    @Test
    fun `and the lobby it leaves behind can be started again`() {
        var state = t(finished(), GameAction.PlayAgain)
        state = t(state, GameAction.StartGame)

        assertEquals(GamePhase.PLAYING, state.phase)
        assertEquals(1, state.round)
        assertTrue(state.deck.isNotEmpty(), "a new game deals a new deck")
        assertEquals(listOf("a", "b", "c"), state.dealQueue)
    }
}
