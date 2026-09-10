package com.letitride.account

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.GameEvent
import com.letitride.engine.GamePhase
import com.letitride.engine.action
import com.letitride.engine.gambler
import com.letitride.engine.num
import com.letitride.engine.started
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turning a table into a row.
 *
 * The rule underneath all of this is that a guest leaves no trace and a
 * signed-in player leaves exactly what happened to them — so most of these are
 * about the difference between the two, and the rest are about the two moments
 * a tally is allowed to be written down.
 */
class RoomTallyTest {

    private fun table(vararg accounts: Pair<String, String>): RoomTally {
        val tally = RoomTally("WXYZ")
        accounts.forEach { (playerId, accountId) -> tally.seat(playerId, accountId) }
        tally.begin("game-1")
        return tally
    }

    private fun scored(vararg deltas: Pair<String, Int>) =
        GameEvent.RoundScored(deltas.toMap(), deltas.maxByOrNull { it.second }?.first)

    // ─── A guest is not a row with nulls in it ───

    @Test
    fun `a table nobody has signed in at records nothing at all`() {
        val tally = RoomTally("WXYZ")
        tally.begin("game-1")
        tally.absorb(listOf(GameEvent.Draw("a", num(7)), GameEvent.Bust("a", "duplicate", num(7), num(7))))
        assertTrue(tally.closeRound(scored("a" to 0)).isEmpty())
    }

    @Test
    fun `a guest sitting next to somebody signed in is still not recorded`() {
        val tally = table("a" to "account-a")
        tally.absorb(listOf(GameEvent.Draw("a", num(7)), GameEvent.Draw("guest", num(3))))

        val writes = tally.closeRound(scored("a" to 12, "guest" to 9))
        assertEquals(listOf("account-a"), writes.map { it.accountId })
        assertEquals(1, writes.single().cardsDrawn)
    }

    @Test
    fun `nothing is counted before a game has actually been dealt`() {
        val tally = RoomTally("WXYZ")
        tally.seat("a", "account-a")
        tally.absorb(listOf(GameEvent.Draw("a", num(7))))
        assertTrue(tally.closeRound(scored("a" to 0)).isEmpty(), "a lobby is not a game")
    }

    // ─── What a round says ───

    @Test
    fun `a round counts the draws, the busts and the card that did it`() {
        val tally = table("a" to "account-a")
        tally.absorb(
            listOf(
                GameEvent.Draw("a", num(7)),
                GameEvent.Draw("a", num(3)),
                GameEvent.Draw("a", num(7, id = "n-7-second")),
                GameEvent.Bust("a", "duplicate", num(7, id = "n-7-second"), num(7)),
            ),
        )

        val write = tally.closeRound(scored("a" to 0)).single()
        assertEquals(3, write.cardsDrawn)
        assertEquals(1, write.busts)
        assertEquals(2, write.drawn[CardKey("7", "number")])
        assertEquals(1, write.drawn[CardKey("3", "number")])
        assertEquals(mapOf(CardKey("7", "number") to 1), write.bustedTo)
    }

    @Test
    fun `a bust with no card in it blames no card`() {
        val tally = table("a" to "account-a")
        // An assassination is a bust nothing was drawn for.
        tally.absorb(listOf(GameEvent.Bust("a", "assassinated", null, null)))

        val write = tally.closeRound(scored("a" to 0)).single()
        assertEquals(1, write.busts)
        assertTrue(write.bustedTo.isEmpty(), "the last card in the hand did nothing and is not blamed for it")
    }

    @Test
    fun `a card drawn into a hidden hand is still a card you drew`() {
        val tally = table("a" to "account-a")
        tally.absorb(listOf(GameEvent.GamblerDrawn("a", gambler("mulligan"))))

        val write = tally.closeRound(scored("a" to 0)).single()
        assertEquals(1, write.cardsDrawn)
        assertEquals(1, write.drawn[CardKey("mulligan", "gambler")])
    }

    @Test
    fun `a face-down card names nothing and is counted as nothing`() {
        val tally = table("a" to "account-a")
        tally.absorb(listOf(GameEvent.Draw("a", num(7).faceDown())))

        val write = tally.closeRound(scored("a" to 0)).single()
        assertEquals(1, write.cardsDrawn, "the table still saw a card arrive")
        assertTrue(write.drawn.isEmpty(), "…but nobody, including this, may say which")
    }

    @Test
    fun `cards played are filed by what they are and not by who they hit`() {
        val tally = table("a" to "account-a", "b" to "account-b")
        tally.absorb(listOf(GameEvent.ActionPlayed("freeze", "a", "b")))

        val writes = tally.closeRound(scored("a" to 10, "b" to 0)).associateBy { it.accountId }
        assertEquals(mapOf(CardKey("freeze", "action") to 1), writes["account-a"]?.played)
        assertTrue(writes["account-b"]?.played.isNullOrEmpty(), "being frozen is not playing a freeze")
    }

    @Test
    fun `the round counter follows the scoring and the best round is the best of them`() {
        val tally = table("a" to "account-a")
        val first = tally.closeRound(scored("a" to 21)).single()
        assertEquals(1, first.rounds)
        assertEquals(21, first.bestRound)

        val second = tally.closeRound(scored("a" to 8)).single()
        assertEquals(1, second.rounds)
        assertEquals(8, second.bestRound, "the store keeps the high-water mark; a round only reports its own")
    }

    @Test
    fun `a round hands over what it counted and then starts again from nothing`() {
        val tally = table("a" to "account-a")
        tally.absorb(listOf(GameEvent.Draw("a", num(7))))
        assertEquals(1, tally.closeRound(scored("a" to 7)).single().cardsDrawn)

        val second = tally.closeRound(scored("a" to 7)).single()
        assertEquals(0, second.cardsDrawn, "last round's cards are already written down")
    }

    // ─── What a game says ───

    @Test
    fun `the game's result is the engine's answer and not the scoreboard's`() {
        val tally = table("a" to "account-a", "b" to "account-b")
        // "Flip 9" is a knockout: it takes the game whatever the scores say.
        val state = started(players = listOf("a", "b")).copy(
            phase = GamePhase.GAME_END,
            gameWinnerId = "b",
            round = 3,
        ).let { it.copy(players = it.players.map { player -> player.copy(score = if (player.id == "a") 200 else 40) }) }

        val records = tally.finish(state).associateBy { it.accountId }
        assertEquals(false, records["account-a"]?.won)
        assertEquals(true, records["account-b"]?.won)
        assertEquals(1, records["account-a"]?.place, "placing is still by score")
        assertEquals(3, records["account-a"]?.rounds)
    }

    @Test
    fun `a game is only ever finished once`() {
        val tally = table("a" to "account-a")
        val state = started(players = listOf("a", "b")).copy(phase = GamePhase.GAME_END, gameWinnerId = "a")

        assertEquals(1, tally.finish(state).size)
        assertTrue(tally.finish(state).isEmpty(), "a re-broadcast game-over is not a second win")
    }

    @Test
    fun `a level finish shares the place rather than inventing an order`() {
        val tally = table("a" to "account-a", "b" to "account-b", "c" to "account-c")
        val state = started(players = listOf("a", "b", "c")).copy(phase = GamePhase.GAME_END, gameWinnerId = "a").let {
            it.copy(
                players = it.players.map { player ->
                    player.copy(score = if (player.id == "c") 10 else 100)
                },
            )
        }

        val places = tally.finish(state).associate { it.accountId to it.place }
        assertEquals(1, places["account-a"])
        assertEquals(1, places["account-b"])
        assertEquals(3, places["account-c"], "two firsts means no second")
    }

    @Test
    fun `a seat that stood up is not placed in the game it walked out of`() {
        val tally = table("a" to "account-a", "b" to "account-b")
        tally.unseat("b")

        val state = started(players = listOf("a", "b")).copy(phase = GamePhase.GAME_END, gameWinnerId = "a")
        assertEquals(listOf("account-a"), tally.finish(state).map { it.accountId })
    }

    @Test
    fun `a game against the house says so`() {
        val tally = table("a" to "account-a")
        val state = started(players = listOf("a", "b")).copy(phase = GamePhase.GAME_END, gameWinnerId = "a").let {
            it.copy(players = it.players.map { player -> player.copy(isBot = player.id == "b") })
        }
        assertEquals(1, tally.finish(state).single().humans)
    }

    // ─── Filing ───

    @Test
    fun `a number card is filed under what is printed on it and a special under its definition`() {
        assertEquals(CardKey("K", "number"), keyOf(num(13, label = "K")))
        assertEquals(CardKey("freeze", "action"), keyOf(action("freeze")))
        assertEquals(CardKey("mulligan", "gambler"), keyOf(gambler("mulligan")))
        assertEquals(null, keyOf(Card(id = "x", kind = CardKind.ACTION, label = "?", value = 0)))
    }
}
