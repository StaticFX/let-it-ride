package com.letitride.account

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one file the game writes to.
 *
 * Everything here runs against a real SQLite database in a temporary
 * directory rather than a stand-in, because half of what is being asserted is
 * the SQL itself — an upsert that adds, an upsert that only raises, and a
 * leaderboard that leaves the bots out.
 */
class AccountStoreTest {

    private val directory = Files.createTempDirectory("let-it-ride-store")
    private val store = AccountStore(directory.resolve("stats.db").absolutePathString())

    @AfterTest
    fun tearDown() {
        store.close()
        directory.toFile().deleteRecursively()
    }

    private fun devin() = store.upsert("https://id.example.com", "sub-1", "devin", "devin", null, null)

    private fun someone(subject: String, name: String) =
        store.upsert("https://id.example.com", subject, name, name, null, null)

    private fun game(
        account: Account,
        id: String,
        score: Int,
        won: Boolean,
        humans: Int = 2,
        place: Int = if (won) 1 else 2,
    ) = store.recordGame(
        GameRecord(
            gameId = id,
            accountId = account.id,
            roomCode = "WXYZ",
            mode = "classic",
            deck = "letitride",
            seats = 4,
            humans = humans,
            score = score,
            place = place,
            won = won,
            rounds = 5,
            finishedAt = 1_700_000_000_000,
        ),
    )

    // ─── Who somebody is ───

    @Test
    fun `the same person at the same provider is the same account twice`() {
        val first = devin()
        val second = devin()
        assertEquals(first.id, second.id)
    }

    @Test
    fun `the same subject at a different provider is a different person`() {
        val here = store.upsert("https://id.example.com", "sub-1", "devin", null, null, null)
        val there = store.upsert("https://other.example.com", "sub-1", "devin", null, null, null)
        assertNotEquals(here.id, there.id, "an id is a fact about a provider as well as a subject")
    }

    @Test
    fun `an account id repeats nothing the provider considers its own`() {
        val account = store.upsert("https://id.example.com", "sub-1", "devin", null, null, null)
        assertTrue("sub-1" !in account.id)
        assertEquals(16, account.id.length)
    }

    @Test
    fun `a name changed at the provider is the name the table sees`() {
        val id = devin().id
        store.upsert("https://id.example.com", "sub-1", "devin the second", "devin", null, null)
        assertEquals("devin the second", store.byId(id)?.name)
    }

    @Test
    fun `an account nobody has heard of has no stats rather than empty ones`() {
        assertNull(store.statsFor("nobody"))
    }

    // ─── Counting ───

    @Test
    fun `a tally adds up across rounds`() {
        val account = devin()
        store.bump(account.id, Counter.BUSTS, 1)
        store.bump(account.id, Counter.BUSTS, 2)
        assertEquals(3, store.statsFor(account.id)?.busts)
    }

    @Test
    fun `a high-water mark is raised and never lowered`() {
        val account = devin()
        store.raise(account.id, Counter.BEST_ROUND, 30)
        store.raise(account.id, Counter.BEST_ROUND, 12)
        assertEquals(30, store.statsFor(account.id)?.bestRound, "a worse round does not replace a better one")
    }

    @Test
    fun `the cards somebody keeps drawing come back in order`() {
        val account = devin()
        store.countCards(
            account.id,
            Bucket.DRAWN,
            mapOf(CardKey("7", "number") to 4, CardKey("3", "number") to 9),
        )
        store.countCards(account.id, Bucket.DRAWN, mapOf(CardKey("7", "number") to 4))

        val drawn = store.statsFor(account.id)!!.mostDrawn
        assertEquals(listOf("3" to 9, "7" to 8), drawn.map { it.card to it.count })
    }

    @Test
    fun `the three piles of cards are counted apart`() {
        val account = devin()
        store.countCards(account.id, Bucket.DRAWN, mapOf(CardKey("7", "number") to 5))
        store.countCards(account.id, Bucket.BUSTED_TO, mapOf(CardKey("7", "number") to 1))
        store.countCards(account.id, Bucket.PLAYED, mapOf(CardKey("freeze", "action") to 2))

        val stats = store.statsFor(account.id)!!
        assertEquals(5, stats.mostDrawn.single().count)
        assertEquals(1, stats.mostBustedTo.single().count)
        assertEquals(listOf("freeze"), stats.mostPlayed.map { it.card })
    }

    // ─── Games ───

    @Test
    fun `a win rate is wins over games`() {
        val account = devin()
        game(account, "g1", score = 200, won = true)
        game(account, "g2", score = 100, won = false)
        game(account, "g3", score = 60, won = false)
        game(account, "g4", score = 40, won = false)

        val stats = store.statsFor(account.id)!!
        assertEquals(4, stats.games)
        assertEquals(1, stats.wins)
        assertEquals(0.25, stats.winRate)
        assertEquals(100.0, stats.averageScore)
        assertEquals(200, stats.bestScore)
    }

    @Test
    fun `the same game is only ever written down once`() {
        val account = devin()
        game(account, "g1", score = 200, won = true)
        game(account, "g1", score = 200, won = true)
        assertEquals(1, store.statsFor(account.id)?.games, "a re-broadcast game-over is not a second win")
    }

    @Test
    fun `beating the house is a game you played and not a game you are ranked on`() {
        val account = devin()
        game(account, "g1", score = 200, won = true, humans = 1)

        val stats = store.statsFor(account.id)!!
        assertEquals(1, stats.games, "it still happened, and it is still yours")
        assertEquals(0, stats.rankedGames, "…but three bots are not an opponent")
        assertTrue(store.leaderboard().isEmpty())
    }

    @Test
    fun `the leaderboard is ordered by wins`() {
        val winner = someone("sub-a", "winner")
        val runnerUp = someone("sub-b", "runner up")
        game(winner, "g1", score = 200, won = true)
        game(winner, "g2", score = 190, won = true)
        game(runnerUp, "g1", score = 150, won = false)
        game(runnerUp, "g2", score = 140, won = false)
        game(runnerUp, "g3", score = 210, won = true)

        val rows = store.leaderboard()
        assertEquals(listOf("winner", "runner up"), rows.map { it.account.name })
        assertEquals(2, rows.first().wins)
        assertEquals(1.0, rows.first().winRate)
        assertEquals(1 / 3.0, rows.last().winRate)
    }

    @Test
    fun `a bust rate is only ever over rounds that were played`() {
        val account = devin()
        assertEquals(0.0, store.statsFor(account.id)?.bustRate, "nothing divided by nothing is nothing, not a crash")

        store.bump(account.id, Counter.ROUNDS, 10)
        store.bump(account.id, Counter.BUSTS, 4)
        assertEquals(0.4, store.statsFor(account.id)?.bustRate)
    }

    @Test
    fun `a store re-opened on the same file still knows everybody`() {
        val account = devin()
        game(account, "g1", score = 200, won = true)
        store.close()

        val reopened = AccountStore(directory.resolve("stats.db").absolutePathString())
        try {
            assertEquals(1, reopened.statsFor(account.id)?.wins)
        } finally {
            reopened.close()
        }
    }
}
