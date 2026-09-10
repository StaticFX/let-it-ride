package com.letitride.account

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.GameEvent
import com.letitride.engine.GameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Turning a table into a row in a database.
 *
 * The engine stays pure and knows nothing about any of this — it announces what
 * happened and a room decides whether anybody is keeping score. That is the
 * same line `Rooms.kt` already sits on, and it is why every number here is read
 * out of the event stream rather than computed a second time: if the table saw
 * it, it counts, and there is no second definition of "a bust" to drift.
 *
 * Nothing is tallied for a seat with no account behind it. A guest is not a row
 * with nulls in it; there is simply no work to do.
 */

private val log = LoggerFactory.getLogger("com.letitride.account.Tally")

/** One seat's round, frozen and ready to write. */
data class RoundWrite(
    val accountId: String,
    val rounds: Int,
    val busts: Int,
    val stays: Int,
    val flip7s: Int,
    val cardsDrawn: Int,
    val actionsPlayed: Int,
    val bestRound: Int,
    val drawn: Map<CardKey, Int>,
    val bustedTo: Map<CardKey, Int>,
    val played: Map<CardKey, Int>,
)

/**
 * The one thread that writes.
 *
 * A room hands work over and carries on. Rooms record while holding the mutex
 * that the whole table is waiting on, and a disk write in there would be a
 * table that stutters every time somebody's round is scored — so the call has
 * to be one that cannot block, which is what a channel and a consumer of its
 * own gives.
 *
 * Unbounded, and that is not an oversight. The alternative to a queue that
 * grows is a queue that throws work away, and the work here arrives about twice
 * a minute per table while the thing draining it writes a handful of rows in
 * microseconds. There is no backlog to bound; a bound would only ever fire in a
 * situation where the database had already stopped answering, and losing
 * somebody's evening quietly is a worse answer to that than a queue anybody can
 * see in a heap dump.
 */
class StatsRecorder(private val store: AccountStore, scope: CoroutineScope) {
    private val queue = Channel<Write>(capacity = Channel.UNLIMITED)

    private sealed interface Write {
        data class Round(val write: RoundWrite) : Write
        data class Finished(val record: GameRecord) : Write
    }

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val work = queue.receiveCatching().getOrNull() ?: break
                runCatching {
                    when (work) {
                        is Write.Round -> apply(work.write)
                        is Write.Finished -> store.recordGame(work.record)
                    }
                }.onFailure { log.warn("could not write stats down", it) }
            }
        }
    }

    fun round(write: RoundWrite) {
        queue.trySend(Write.Round(write))
    }

    fun game(record: GameRecord) {
        queue.trySend(Write.Finished(record))
    }

    private fun apply(write: RoundWrite) {
        store.bump(write.accountId, Counter.ROUNDS, write.rounds)
        store.bump(write.accountId, Counter.BUSTS, write.busts)
        store.bump(write.accountId, Counter.STAYS, write.stays)
        store.bump(write.accountId, Counter.FLIP7S, write.flip7s)
        store.bump(write.accountId, Counter.CARDS_DRAWN, write.cardsDrawn)
        store.bump(write.accountId, Counter.ACTIONS_PLAYED, write.actionsPlayed)
        store.raise(write.accountId, Counter.BEST_ROUND, write.bestRound)
        store.countCards(write.accountId, Bucket.DRAWN, write.drawn)
        store.countCards(write.accountId, Bucket.BUSTED_TO, write.bustedTo)
        store.countCards(write.accountId, Bucket.PLAYED, write.played)
    }
}

/** What one seat has done since the last time the round was written down. */
private class SeatTally {
    var rounds = 0
    var busts = 0
    var stays = 0
    var flip7s = 0
    var cardsDrawn = 0
    var actionsPlayed = 0
    var bestRound = 0
    val drawn = mutableMapOf<CardKey, Int>()
    val bustedTo = mutableMapOf<CardKey, Int>()
    val played = mutableMapOf<CardKey, Int>()

    fun isEmpty(): Boolean =
        rounds == 0 && busts == 0 && stays == 0 && flip7s == 0 && cardsDrawn == 0 &&
            actionsPlayed == 0 && bestRound == 0 && drawn.isEmpty() && bustedTo.isEmpty() && played.isEmpty()

    fun freeze(accountId: String) = RoundWrite(
        accountId, rounds, busts, stays, flip7s, cardsDrawn, actionsPlayed, bestRound,
        drawn.toMap(), bustedTo.toMap(), played.toMap(),
    )
}

/**
 * One table's running tallies.
 *
 * Held by the room and fed every batch of events it produces. Two moments
 * matter and they are deliberately different: a *round* is written down the
 * moment it is scored, and the *game* only when it actually finishes.
 *
 * That split is the whole answer to somebody who walks out when they are
 * losing. The cards they drew and the rounds they busted are already written,
 * because those happened; the win and the loss are not, because a game nobody
 * finished has no result. So quitting costs you a game in your total and buys
 * you nothing at all — which is the opposite of what one flush at the end would
 * have made true.
 */
class RoomTally(private val roomCode: String) {
    /** Seat → account, for the seats that have one. Guests are simply absent. */
    private val accounts = mutableMapOf<String, String>()
    private val seats = mutableMapOf<String, SeatTally>()

    /**
     * A fresh id per game, so "play again" at the same table is a second game
     * and not more of the first one. Null until a game is actually dealt.
     */
    private var gameId: String? = null
    private var recorded = false

    fun seat(playerId: String, accountId: String) {
        accounts[playerId] = accountId
    }

    fun unseat(playerId: String) {
        accounts.remove(playerId)
        seats.remove(playerId)
    }

    fun accountFor(playerId: String): String? = accounts[playerId]

    fun hasAccounts(): Boolean = accounts.isNotEmpty()

    /** Starts a game. Called when a table leaves the lobby. */
    fun begin(id: String) {
        gameId = id
        recorded = false
        seats.clear()
    }

    private fun tally(playerId: String): SeatTally? {
        if (playerId !in accounts) return null
        return seats.getOrPut(playerId) { SeatTally() }
    }

    /**
     * Reads a batch of events and adds what it says to whoever it happened to.
     *
     * The batch is the room's own — unredacted — so a gambler card drawn into a
     * hidden hand is counted here even though every other seat was sent that
     * event with the card taken out of it. That is the right side of the line:
     * this is your own history and nobody else can read it.
     */
    fun absorb(events: List<GameEvent>) {
        if (accounts.isEmpty() || gameId == null) return
        for (event in events) {
            when (event) {
                is GameEvent.Draw -> tally(event.playerId)?.let {
                    it.cardsDrawn += 1
                    keyOf(event.card)?.let { key -> it.drawn.merge(key, 1, Int::plus) }
                }

                is GameEvent.GamblerDrawn -> tally(event.playerId)?.let {
                    it.cardsDrawn += 1
                    event.card?.let { card -> keyOf(card)?.let { key -> it.drawn.merge(key, 1, Int::plus) } }
                }

                is GameEvent.Bust -> tally(event.playerId)?.let {
                    it.busts += 1
                    // Only a bust that had a card in it names one. An
                    // assassination is a bust nothing was drawn for, and
                    // counting the last card in the hand for it would quietly
                    // blame a card that did nothing.
                    event.card?.let { card -> keyOf(card)?.let { key -> it.bustedTo.merge(key, 1, Int::plus) } }
                }

                is GameEvent.Stay -> tally(event.playerId)?.let { it.stays += 1 }

                is GameEvent.Flip7 -> tally(event.playerId)?.let { it.flip7s += 1 }

                is GameEvent.ActionPlayed -> tally(event.fromPlayerId)?.let {
                    it.actionsPlayed += 1
                    it.played.merge(CardKey(event.cardDefId, "action"), 1, Int::plus)
                }

                is GameEvent.GamblerPlayed -> tally(event.playerId)?.let {
                    it.actionsPlayed += 1
                    keyOf(event.card)?.let { key -> it.played.merge(key, 1, Int::plus) }
                }

                else -> Unit
            }
        }
    }

    /**
     * Everything the round owes the database, and clears what it hands over.
     *
     * Called on [GameEvent.RoundScored], which carries a delta for every seat —
     * so this is also the one place that knows how many rounds somebody has
     * actually sat through, and what the best of them paid.
     */
    fun closeRound(event: GameEvent.RoundScored): List<RoundWrite> {
        if (accounts.isEmpty() || gameId == null) return emptyList()
        for ((playerId, delta) in event.deltas) {
            val seat = tally(playerId) ?: continue
            seat.rounds += 1
            seat.bestRound = maxOf(seat.bestRound, delta)
        }
        val writes = seats.mapNotNull { (playerId, seat) ->
            val accountId = accounts[playerId] ?: return@mapNotNull null
            if (seat.isEmpty()) null else seat.freeze(accountId)
        }
        seats.clear()
        return writes
    }

    /**
     * The game's result, once per game.
     *
     * Guarded rather than trusted to be called once: `GAME_END` is a phase a
     * room can be re-broadcast in, and a placement written twice would be a
     * doubled win. The `ON CONFLICT DO NOTHING` in the store is the second
     * guard, on the same principle as everything else here having two.
     */
    fun finish(state: GameState, now: Long = System.currentTimeMillis()): List<GameRecord> {
        val id = gameId ?: return emptyList()
        if (recorded || accounts.isEmpty()) return emptyList()
        recorded = true

        val humans = state.players.count { !it.isBot }
        // Placing is by score, and a tie shares the better place rather than
        // inventing an order between two people who finished level.
        val ranked = state.players.sortedByDescending { it.score }
        val places = mutableMapOf<String, Int>()
        var place = 0
        var lastScore: Int? = null
        ranked.forEachIndexed { index, player ->
            if (player.score != lastScore) {
                place = index + 1
                lastScore = player.score
            }
            places[player.id] = place
        }

        return state.players.mapNotNull { player ->
            val accountId = accounts[player.id] ?: return@mapNotNull null
            GameRecord(
                gameId = id,
                accountId = accountId,
                roomCode = roomCode,
                mode = state.config.mode.name.lowercase(),
                deck = state.config.deckPresetId,
                seats = state.players.size,
                humans = humans,
                score = player.score,
                place = places[player.id] ?: state.players.size,
                // The engine's own answer, not "whoever has the most points" —
                // a knockout flip takes the game whatever the scoreboard says.
                won = state.gameWinnerId == player.id,
                rounds = state.round,
                finishedAt = now,
            )
        }
    }
}

/**
 * How a card is filed.
 *
 * Number cards go under what is printed on them, which is the key the game
 * already busts on, so "the card you keep busting to" means the same thing here
 * as it does at the table. Everything else goes under its definition id,
 * because a special card's printed name is a face the catalog owns and could be
 * reworded without the card changing at all.
 *
 * Null for a card that names nothing — a face-down one, or a special with no
 * definition behind it. Those are not counted rather than counted as "".
 */
fun keyOf(card: Card): CardKey? {
    if (card.hidden) return null
    return when (card.kind) {
        CardKind.NUMBER -> card.label.takeIf { it.isNotBlank() }?.let { CardKey(it, "number") }
        CardKind.ACTION -> card.defId?.let { CardKey(it, "action") }
        CardKind.PASSIVE -> card.defId?.let { CardKey(it, "passive") }
        CardKind.GAMBLER -> card.defId?.let { CardKey(it, "gambler") }
    }
}
