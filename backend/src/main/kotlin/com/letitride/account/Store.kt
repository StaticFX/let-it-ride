package com.letitride.account

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a player's history lives.
 *
 * One SQLite file and nothing else. Rooms are still held in memory and still
 * vanish with the container — what is written down here is only what somebody
 * signed in wants to still be true tomorrow, which is the games they finished
 * and the cards that went past them. A guest touches none of this: there is no
 * row to write without an account id, so `null` in and nothing happens.
 *
 * The file is opened once and guarded by a lock rather than pooled. Writes
 * happen twice a round at the very most and reads only when somebody opens the
 * stats screen, so a pool would be ceremony around a table nobody is queueing
 * at — and one connection is also the simplest thing that cannot deadlock
 * against itself.
 */
const val DB_PATH_ENV = "LETITRIDE_DB"

private val log = LoggerFactory.getLogger("com.letitride.account.Store")

/** An account as everything outside this file sees it. */
@Serializable
data class Account(
    /**
     * A short digest of issuer + subject, not the subject itself.
     *
     * This id ends up in URLs and on a leaderboard everybody can read, and the
     * subject is the provider's own handle for a person. Hashing costs nothing
     * and means the public half of this feature never repeats something the
     * identity provider considers internal.
     */
    val id: String,
    val name: String,
    val username: String? = null,
    val picture: String? = null,
)

/** One finished game, from one seat's point of view. */
data class GameRecord(
    val gameId: String,
    val accountId: String,
    val roomCode: String,
    val mode: String,
    val deck: String?,
    val seats: Int,
    /**
     * How many of those seats had a person in them.
     *
     * Recorded because the leaderboard only counts games with somebody else in
     * them — see [leaderboard]. Beating three bots is a game and is worth
     * having in your own history; it is not a result to rank strangers by.
     */
    val humans: Int,
    val score: Int,
    val place: Int,
    val won: Boolean,
    val rounds: Int,
    val finishedAt: Long,
)

/** A card and how often it turned up, for "most drawn" and its cousins. */
@Serializable
data class CardTally(val card: String, val kind: String, val count: Int)

@Serializable
data class PlayerStats(
    val account: Account,
    val games: Int,
    val wins: Int,
    /** Games with at least one other person at the table, and the wins in them. */
    val rankedGames: Int,
    val rankedWins: Int,
    val winRate: Double,
    val averageScore: Double,
    val bestScore: Int,
    val rounds: Int,
    val busts: Int,
    val stays: Int,
    val flip7s: Int,
    val bustRate: Double,
    val cardsDrawn: Int,
    val bestRound: Int,
    val mostDrawn: List<CardTally>,
    val mostBustedTo: List<CardTally>,
    val mostPlayed: List<CardTally>,
)

@Serializable
data class LeaderboardRow(
    val account: Account,
    val games: Int,
    val wins: Int,
    val winRate: Double,
    val averageScore: Double,
    val bestScore: Int,
)

/**
 * Counter names. Strings rather than columns on purpose: a new number worth
 * keeping is a new key and not a migration, which is the same reason a card
 * that does something new is a card and not a flag on `Player`.
 *
 * [BEST_ROUND] is the odd one out — it is a high-water mark rather than a
 * tally, and is written with [AccountStore.raise] instead of [AccountStore.bump].
 */
object Counter {
    const val ROUNDS = "rounds"
    const val BUSTS = "busts"
    const val STAYS = "stays"
    const val FLIP7S = "flip7s"
    const val CARDS_DRAWN = "cardsDrawn"
    const val ACTIONS_PLAYED = "actionsPlayed"
    const val BEST_ROUND = "bestRound"
}

/** Which pile of card counts a tally belongs in. */
object Bucket {
    const val DRAWN = "drawn"
    const val BUSTED_TO = "bustedTo"
    const val PLAYED = "played"
}

/**
 * Opens the store named by [DB_PATH_ENV], or returns null when there is none.
 *
 * A server with nowhere to write is not a broken server — it is the server this
 * repo shipped until now, and it still plays a complete game. So a missing path
 * is silent and an unusable one is a warning, and in both cases everything above
 * this simply runs without accounts.
 */
fun openAccountStore(env: (String) -> String? = System::getenv): AccountStore? {
    val path = env(DB_PATH_ENV)?.trim().orEmpty()
    if (path.isBlank()) return null
    return runCatching { AccountStore(path) }
        .onFailure { log.warn("could not open $path — accounts and stats are off for this run", it) }
        .getOrNull()
}

class AccountStore(path: String) {
    /**
     * Where this ended up, for whoever announces it.
     *
     * Nothing is logged from in here on purpose. Whether accounts are *on* is a
     * decision made a level up, and a store opened next to a missing identity
     * provider is about to be closed again — announcing it at the moment the
     * file opens made the log claim something that was untrue two lines later.
     */
    val file: String = Path.of(path).toAbsolutePath().toString()

    private val lock = Any()
    private val db: Connection

    init {
        Path.of(file).parent?.let { Files.createDirectories(it) }
        db = DriverManager.getConnection("jdbc:sqlite:$file")
        db.autoCommit = true
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA synchronous=NORMAL")
        exec("PRAGMA busy_timeout=5000")
        exec("PRAGMA foreign_keys=ON")
        migrate()
    }

    fun close() = synchronized(lock) { db.close() }

    private fun exec(sql: String) = synchronized(lock) { db.createStatement().use { it.execute(sql) } }

    private fun migrate() = synchronized(lock) {
        db.createStatement().use { statement ->
            SCHEMA.forEach(statement::executeUpdate)
        }
    }

    // ═══════════════════════════════════════════
    // Accounts
    // ═══════════════════════════════════════════

    /**
     * Writes down whoever just signed in, and hands back what the table will
     * call them.
     *
     * The name is refreshed every time rather than kept as it was on the first
     * visit: somebody who renames themselves at the identity provider has
     * renamed themselves, and a leaderboard still showing the old one would be
     * the only place in the game that disagreed.
     */
    fun upsert(
        issuer: String,
        subject: String,
        name: String,
        username: String?,
        email: String?,
        picture: String?,
        now: Long = System.currentTimeMillis(),
    ): Account = synchronized(lock) {
        val id = accountIdFor(issuer, subject)
        db.prepareStatement(
            """
            INSERT INTO accounts (id, issuer, subject, name, username, email, picture, created_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                username = excluded.username,
                email = excluded.email,
                picture = excluded.picture,
                last_seen_at = excluded.last_seen_at
            """.trimIndent(),
        ).use {
            it.setString(1, id)
            it.setString(2, issuer)
            it.setString(3, subject)
            it.setString(4, name)
            it.setString(5, username)
            it.setString(6, email)
            it.setString(7, picture)
            it.setLong(8, now)
            it.setLong(9, now)
            it.executeUpdate()
        }
        Account(id, name, username, picture)
    }

    fun byId(id: String): Account? = synchronized(lock) {
        db.prepareStatement("SELECT id, name, username, picture FROM accounts WHERE id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.toAccount() else null }
        }
    }

    // ═══════════════════════════════════════════
    // Writing a game down
    // ═══════════════════════════════════════════

    /** Adds [by] to a running tally. */
    fun bump(accountId: String, key: String, by: Int) {
        if (by == 0) return
        synchronized(lock) {
            db.prepareStatement(
                """
                INSERT INTO counters (account_id, key, count) VALUES (?, ?, ?)
                ON CONFLICT(account_id, key) DO UPDATE SET count = count + excluded.count
                """.trimIndent(),
            ).use {
                it.setString(1, accountId)
                it.setString(2, key)
                it.setInt(3, by)
                it.executeUpdate()
            }
        }
    }

    /** Raises a high-water mark, and leaves it alone when [value] is not one. */
    fun raise(accountId: String, key: String, value: Int) {
        if (value <= 0) return
        synchronized(lock) {
            db.prepareStatement(
                """
                INSERT INTO counters (account_id, key, count) VALUES (?, ?, ?)
                ON CONFLICT(account_id, key) DO UPDATE SET count = MAX(count, excluded.count)
                """.trimIndent(),
            ).use {
                it.setString(1, accountId)
                it.setString(2, key)
                it.setInt(3, value)
                it.executeUpdate()
            }
        }
    }

    fun countCards(accountId: String, bucket: String, tallies: Map<CardKey, Int>) {
        if (tallies.isEmpty()) return
        synchronized(lock) {
            db.prepareStatement(
                """
                INSERT INTO card_counts (account_id, bucket, card, card_kind, count) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id, bucket, card) DO UPDATE SET count = count + excluded.count
                """.trimIndent(),
            ).use { statement ->
                for ((key, count) in tallies) {
                    statement.setString(1, accountId)
                    statement.setString(2, bucket)
                    statement.setString(3, key.card)
                    statement.setString(4, key.kind)
                    statement.setInt(5, count)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    fun recordGame(record: GameRecord) = synchronized(lock) {
        db.prepareStatement(
            """
            INSERT INTO games (game_id, account_id, room_code, mode, deck, seats, humans, score, place, won, rounds, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(game_id, account_id) DO NOTHING
            """.trimIndent(),
        ).use {
            it.setString(1, record.gameId)
            it.setString(2, record.accountId)
            it.setString(3, record.roomCode)
            it.setString(4, record.mode)
            it.setString(5, record.deck)
            it.setInt(6, record.seats)
            it.setInt(7, record.humans)
            it.setInt(8, record.score)
            it.setInt(9, record.place)
            it.setInt(10, if (record.won) 1 else 0)
            it.setInt(11, record.rounds)
            it.setLong(12, record.finishedAt)
            it.executeUpdate()
        }
    }

    // ═══════════════════════════════════════════
    // Reading it back
    // ═══════════════════════════════════════════

    fun statsFor(accountId: String, topCards: Int = 5): PlayerStats? {
        val account = byId(accountId) ?: return null
        return synchronized(lock) {
            var games = 0
            var wins = 0
            var ranked = 0
            var rankedWins = 0
            var totalScore = 0L
            var bestScore = 0
            db.prepareStatement(
                """
                SELECT COUNT(*)                                  AS games,
                       COALESCE(SUM(won), 0)                     AS wins,
                       COALESCE(SUM(humans >= 2), 0)             AS ranked,
                       COALESCE(SUM(won * (humans >= 2)), 0)     AS ranked_wins,
                       COALESCE(SUM(score), 0)                   AS total_score,
                       COALESCE(MAX(score), 0)                   AS best_score
                FROM games WHERE account_id = ?
                """.trimIndent(),
            ).use {
                it.setString(1, accountId)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        games = rows.getInt("games")
                        wins = rows.getInt("wins")
                        ranked = rows.getInt("ranked")
                        rankedWins = rows.getInt("ranked_wins")
                        totalScore = rows.getLong("total_score")
                        bestScore = rows.getInt("best_score")
                    }
                }
            }

            val counters = countersFor(accountId)
            val rounds = counters[Counter.ROUNDS] ?: 0
            val busts = counters[Counter.BUSTS] ?: 0

            PlayerStats(
                account = account,
                games = games,
                wins = wins,
                rankedGames = ranked,
                rankedWins = rankedWins,
                winRate = if (games == 0) 0.0 else wins.toDouble() / games,
                averageScore = if (games == 0) 0.0 else totalScore.toDouble() / games,
                bestScore = bestScore,
                rounds = rounds,
                busts = busts,
                stays = counters[Counter.STAYS] ?: 0,
                flip7s = counters[Counter.FLIP7S] ?: 0,
                bustRate = if (rounds == 0) 0.0 else busts.toDouble() / rounds,
                cardsDrawn = counters[Counter.CARDS_DRAWN] ?: 0,
                bestRound = counters[Counter.BEST_ROUND] ?: 0,
                mostDrawn = cards(accountId, Bucket.DRAWN, topCards),
                mostBustedTo = cards(accountId, Bucket.BUSTED_TO, topCards),
                mostPlayed = cards(accountId, Bucket.PLAYED, topCards),
            )
        }
    }

    /**
     * The table everybody can read.
     *
     * Only games with two people in them count. A table of bots is a real game
     * and stays in your own history, but three of them are not an opponent, and
     * a ranking anybody can pad by playing the house alone is a ranking nobody
     * reads twice.
     *
     * Ordered by wins before win rate deliberately: a rate over two games is
     * noise dressed as a number, and sorting on it would put whoever played
     * once and got lucky above everyone who turned up all year.
     */
    fun leaderboard(limit: Int = 50): List<LeaderboardRow> = synchronized(lock) {
        db.prepareStatement(
            """
            SELECT a.id, a.name, a.username, a.picture,
                   COUNT(*)                 AS games,
                   COALESCE(SUM(g.won), 0)  AS wins,
                   AVG(g.score)             AS avg_score,
                   MAX(g.score)             AS best_score
            FROM games g JOIN accounts a ON a.id = g.account_id
            WHERE g.humans >= 2
            GROUP BY a.id
            ORDER BY wins DESC, games DESC, avg_score DESC
            LIMIT ?
            """.trimIndent(),
        ).use {
            it.setInt(1, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val games = rows.getInt("games")
                        val wins = rows.getInt("wins")
                        add(
                            LeaderboardRow(
                                account = rows.toAccount(),
                                games = games,
                                wins = wins,
                                winRate = if (games == 0) 0.0 else wins.toDouble() / games,
                                averageScore = rows.getDouble("avg_score"),
                                bestScore = rows.getInt("best_score"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun countersFor(accountId: String): Map<String, Int> =
        db.prepareStatement("SELECT key, count FROM counters WHERE account_id = ?").use {
            it.setString(1, accountId)
            it.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString("key"), rows.getInt("count")) }
            }
        }

    private fun cards(accountId: String, bucket: String, limit: Int): List<CardTally> =
        db.prepareStatement(
            """
            SELECT card, card_kind, count FROM card_counts
            WHERE account_id = ? AND bucket = ?
            ORDER BY count DESC, card ASC LIMIT ?
            """.trimIndent(),
        ).use {
            it.setString(1, accountId)
            it.setString(2, bucket)
            it.setInt(3, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(CardTally(rows.getString("card"), rows.getString("card_kind"), rows.getInt("count")))
                    }
                }
            }
        }
}

private fun ResultSet.toAccount() = Account(
    id = getString("id"),
    name = getString("name"),
    username = getString("username"),
    picture = getString("picture"),
)

/**
 * The public id for a person at a provider.
 *
 * Truncated to 16 hex characters, which is 64 bits — long enough that a
 * collision between two people at the same homelab is not a thing that happens,
 * and short enough to read out of a URL.
 */
fun accountIdFor(issuer: String, subject: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$issuer|$subject".toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * How a card is named in a tally: what is printed on a number card, and the
 * definition id of anything else.
 *
 * The same key the game already busts on — two number cards with the same label
 * are the same card — so "most drawn" agrees with what a player would say they
 * kept drawing. A special card is named by its definition because its printed
 * name is a face the catalog owns and could be reworded tomorrow.
 */
data class CardKey(val card: String, val kind: String)

private val SCHEMA = listOf(
    """
    CREATE TABLE IF NOT EXISTS accounts (
        id           TEXT PRIMARY KEY,
        issuer       TEXT NOT NULL,
        subject      TEXT NOT NULL,
        name         TEXT NOT NULL,
        username     TEXT,
        email        TEXT,
        picture      TEXT,
        created_at   INTEGER NOT NULL,
        last_seen_at INTEGER NOT NULL
    )
    """.trimIndent(),
    "CREATE UNIQUE INDEX IF NOT EXISTS accounts_identity ON accounts (issuer, subject)",
    """
    CREATE TABLE IF NOT EXISTS games (
        game_id     TEXT NOT NULL,
        account_id  TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
        room_code   TEXT NOT NULL,
        mode        TEXT NOT NULL,
        deck        TEXT,
        seats       INTEGER NOT NULL,
        humans      INTEGER NOT NULL,
        score       INTEGER NOT NULL,
        place       INTEGER NOT NULL,
        won         INTEGER NOT NULL,
        rounds      INTEGER NOT NULL,
        finished_at INTEGER NOT NULL,
        PRIMARY KEY (game_id, account_id)
    )
    """.trimIndent(),
    "CREATE INDEX IF NOT EXISTS games_account ON games (account_id)",
    """
    CREATE TABLE IF NOT EXISTS counters (
        account_id TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
        key        TEXT NOT NULL,
        count      INTEGER NOT NULL,
        PRIMARY KEY (account_id, key)
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS card_counts (
        account_id TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
        bucket     TEXT NOT NULL,
        card       TEXT NOT NULL,
        card_kind  TEXT NOT NULL,
        count      INTEGER NOT NULL,
        PRIMARY KEY (account_id, bucket, card)
    )
    """.trimIndent(),
)
