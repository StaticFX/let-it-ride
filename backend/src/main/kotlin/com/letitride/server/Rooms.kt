package com.letitride.server

import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckPresets
import com.letitride.engine.Engine
import com.letitride.engine.FLIP7_TARGET
import com.letitride.engine.GameAction
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.LobbyRules
import com.letitride.engine.MAX_PLAYERS
import com.letitride.engine.Player
import com.letitride.engine.PlayerStatus
import com.letitride.engine.Rng
import com.letitride.engine.SECOND_LIFE
import com.letitride.engine.defaultGameConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TICK_MS = 150L
private const val DEAL_STEP_MS = 750L
private const val FORCED_DRAW_STEP_MS = 800L
private const val BOT_THINK_MS = 900L
private const val BOT_PICK_MS = 950L
private const val EMPTY_ROOM_TTL_MS = 10 * 60 * 1000L

private val BOT_NAMES = listOf("Ace", "Bluff", "Chips", "Dice", "Echo", "Faro")

class Connection(val playerId: String, val outbound: Channel<String>)

/**
 * One in-memory game. The room owns the authoritative [GameState]; clients only
 * ever send intents. Everything that needs pacing — the opening deal, forced
 * draws, bot moves and the turn clock — is driven by [tick] rather than by the
 * client, so a slow or hostile client cannot stall or rush the table.
 */
class Room(
    val code: String,
    seed: Long,
    private val json: Json,
    parentScope: CoroutineScope,
) {
    private val rng = Rng(seed)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]))

    private val connections = ConcurrentHashMap<String, Connection>()

    var state: GameState = Engine.newGame(defaultGameConfig())
        private set

    var hostId: String? = null
        private set

    private var turnDeadline: Long? = null
    private var promptKey: String? = null
    private var nextStepAt: Long = 0
    private var botCounter = 0

    @Volatile
    var emptySince: Long? = System.currentTimeMillis()
        private set

    init {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching { tick() }
            }
        }
    }

    // ═══════════════════════════════════════════
    // Membership
    // ═══════════════════════════════════════════

    suspend fun canJoin(): Boolean = mutex.withLock {
        state.phase == GamePhase.LOBBY && state.players.size < MAX_PLAYERS
    }

    /** Registers a socket. Returns false when the room cannot take the player. */
    suspend fun attach(playerId: String, name: String, connection: Connection): Boolean {
        val events: List<GameEvent>
        mutex.withLock {
            val existing = state.player(playerId)
            if (existing == null) {
                if (state.phase != GamePhase.LOBBY || state.players.size >= MAX_PLAYERS) return false
                events = applyLocked(GameAction.AddPlayer(playerId, name))
            } else {
                events = applyLocked(GameAction.SetConnected(playerId, true))
            }
            connections[playerId] = connection
            emptySince = null
            if (hostId == null || state.player(hostId!!) == null) hostId = playerId
        }
        send(connection, ServerMessage.Welcome(playerId, code, hostId == playerId))
        broadcast(events)
        return true
    }

    suspend fun detach(playerId: String) {
        val events: List<GameEvent>
        mutex.withLock {
            connections.remove(playerId)
            // In the lobby this drops the seat; mid-game the engine folds the
            // player instead so seat indices and scores survive.
            events = applyLocked(GameAction.RemovePlayer(playerId))
            if (hostId == playerId) {
                hostId = state.players.firstOrNull { connections.containsKey(it.id) && !it.isBot }?.id
            }
            if (connections.isEmpty()) emptySince = System.currentTimeMillis()
        }
        broadcast(events)
    }

    fun isEmpty(): Boolean = connections.isEmpty()

    fun isStale(now: Long): Boolean = emptySince?.let { now - it > EMPTY_ROOM_TTL_MS } ?: false

    fun close() {
        scope.cancel()
        connections.values.forEach { it.outbound.close() }
        connections.clear()
    }

    // ═══════════════════════════════════════════
    // Client intents
    // ═══════════════════════════════════════════

    suspend fun handle(playerId: String, message: ClientMessage) {
        val events: List<GameEvent> = mutex.withLock {
            when (message) {
                ClientMessage.Ping -> {
                    connections[playerId]?.let { sendRaw(it, ServerMessage.Pong) }
                    return
                }

                ClientMessage.Hit -> applyLocked(GameAction.Hit(playerId))
                ClientMessage.Stay -> applyLocked(GameAction.Stay(playerId))

                is ClientMessage.PlayAction -> {
                    val pending = state.pendingAction
                    if (pending == null || pending.playerId != playerId) return
                    applyLocked(
                        GameAction.PlayAction(playerId, message.targetPlayerId, message.cardDefId),
                    )
                }

                is ClientMessage.SetConfig -> {
                    if (playerId != hostId) return
                    applyLocked(GameAction.SetConfig(sanitize(message.config)))
                }

                ClientMessage.StartGame -> {
                    if (playerId != hostId) return
                    applyLocked(GameAction.StartGame)
                }

                ClientMessage.NextRound -> {
                    if (playerId != hostId) return
                    applyLocked(GameAction.NextRound)
                }

                is ClientMessage.Kick -> {
                    if (playerId != hostId || message.playerId == hostId) return
                    val target = connections[message.playerId]
                    if (target != null) sendRaw(target, ServerMessage.Kicked)
                    connections.remove(message.playerId)
                    target?.outbound?.close()
                    applyLocked(GameAction.RemovePlayer(message.playerId))
                }

                ClientMessage.AddBot -> {
                    if (playerId != hostId || state.phase != GamePhase.LOBBY) return
                    val name = BOT_NAMES.getOrNull(botCounter) ?: "Bot ${botCounter + 1}"
                    botCounter++
                    applyLocked(GameAction.AddPlayer("bot-${code}-$botCounter", name, isBot = true))
                }
            }
        }
        broadcast(events)
    }

    /** Clamps host-supplied config so a crafted message cannot break a game. */
    private fun sanitize(config: GameConfig): GameConfig {
        val preset = DeckPresets.byId(config.deckPresetId) ?: DeckPresets.default
        return config.copy(
            deckPresetId = preset.id,
            deck = preset.deck,
            totalRounds = config.totalRounds.coerceIn(1, 20),
            targetScore = config.targetScore.coerceIn(50, 1000),
            turnTimeSeconds = config.turnTimeSeconds.coerceIn(10, 300),
            ruleIds = config.ruleIds.filter { id -> LobbyRules.all.any { it.id == id } }.distinct(),
        )
    }

    // ═══════════════════════════════════════════
    // Pacing
    // ═══════════════════════════════════════════

    private suspend fun tick() {
        val events: List<GameEvent> = mutex.withLock {
            val now = System.currentTimeMillis()
            val snapshot = state

            if (snapshot.phase != GamePhase.PLAYING) {
                promptKey = null
                turnDeadline = null
                return@withLock emptyList()
            }

            val prompt = promptOf(snapshot)
            if (prompt != promptKey) {
                promptKey = prompt
                nextStepAt = now + stepDelayFor(prompt)
                turnDeadline = deadlineFor(prompt, snapshot, now)
            }

            // The clock is checked before the pacing gate — a human who never
            // answers has to be timed out no matter what else is scheduled.
            val deadline = turnDeadline
            if (deadline != null && now >= deadline) {
                turnDeadline = null
                return@withLock timeoutNow(snapshot)
            }

            if (now < nextStepAt) return@withLock emptyList()

            val stepped = when {
                prompt.startsWith("deal:") -> applyLocked(GameAction.DealTo(prompt.removePrefix("deal:")))

                prompt.startsWith("forced:") -> applyLocked(GameAction.ForcedDraw)

                prompt.startsWith("pick:") -> {
                    val actor = prompt.removePrefix("pick:")
                    if (snapshot.player(actor)?.isBot == true) botPick(snapshot, actor) else null
                }

                prompt.startsWith("turn:") -> {
                    val actor = prompt.removePrefix("turn:")
                    if (snapshot.player(actor)?.isBot == true) botMove(snapshot, actor) else null
                }

                else -> null
            }

            // A human's turn simply waits; only paced work reschedules.
            if (stepped == null) return@withLock emptyList()
            nextStepAt = now + stepDelayFor(promptOf(state))
            stepped
        }
        broadcast(events)
    }

    private fun timeoutNow(snapshot: GameState): List<GameEvent> {
        val actor = snapshot.pendingAction?.playerId ?: snapshot.currentPlayer?.id ?: return emptyList()
        return applyLocked(GameAction.Timeout(actor))
    }

    /** A stable description of who the table is waiting on and why. */
    private fun promptOf(snapshot: GameState): String {
        val pending = snapshot.pendingAction
        if (pending != null) return "pick:${pending.playerId}"
        val forced = snapshot.forcedDraws
        if (forced != null) return "forced:${forced.playerId}:${forced.remaining}"
        val dealing = snapshot.dealQueue.firstOrNull()
        if (dealing != null) return "deal:$dealing"
        return "turn:${snapshot.currentPlayer?.id ?: "none"}"
    }

    private fun stepDelayFor(prompt: String): Long = when {
        prompt.startsWith("deal:") -> DEAL_STEP_MS
        prompt.startsWith("forced:") -> FORCED_DRAW_STEP_MS
        prompt.startsWith("pick:") -> BOT_PICK_MS
        else -> BOT_THINK_MS
    }

    /** Only humans are on the clock; bots always act well inside it. */
    private fun deadlineFor(prompt: String, snapshot: GameState, now: Long): Long? {
        val actor = when {
            prompt.startsWith("pick:") -> prompt.removePrefix("pick:")
            prompt.startsWith("turn:") -> prompt.removePrefix("turn:")
            else -> return null
        }
        val player = snapshot.player(actor) ?: return null
        if (player.isBot) return null
        val seconds = snapshot.config.turnTimeSeconds
        if (seconds <= 0) return null
        return now + seconds * 1000L
    }

    // ═══════════════════════════════════════════
    // Bots
    // ═══════════════════════════════════════════

    private fun botMove(snapshot: GameState, botId: String): List<GameEvent> {
        val bot = snapshot.player(botId) ?: return emptyList()
        return if (shouldHit(snapshot, bot)) {
            applyLocked(GameAction.Hit(botId))
        } else {
            applyLocked(GameAction.Stay(botId))
        }
    }

    /**
     * Bots know the deck composition (the server does), so they play the actual
     * duplicate odds rather than a card count. A second chance in hand makes
     * them noticeably braver.
     */
    private fun shouldHit(snapshot: GameState, bot: Player): Boolean {
        if (bot.hand.isEmpty()) return true
        val unseen = snapshot.deck + snapshot.discard
        if (unseen.none { it.kind == CardKind.NUMBER }) return false

        val held = bot.hand.map { it.label }.toSet()
        val duplicates = unseen.count { it.kind == CardKind.NUMBER && it.label in held }
        var risk = duplicates.toDouble() / unseen.size
        if (bot.passives.any { it.defId == SECOND_LIFE.id }) risk *= 0.3

        // One card short of a Flip 7 the bonus is worth almost any risk.
        val threshold = when {
            bot.hand.size >= FLIP7_TARGET - 1 -> 0.60
            bot.hand.size <= 2 -> 0.45
            bot.handValue < 20 -> 0.30
            else -> 0.20
        }
        return risk < threshold
    }

    private fun botPick(snapshot: GameState, botId: String): List<GameEvent> {
        val pending = snapshot.pendingAction ?: return emptyList()
        val others = snapshot.players.filter { it.status == PlayerStatus.ACTIVE && it.id != botId }
        // Whatever the card does, the player with the most on the table is the
        // one worth pointing it at.
        val target = others.maxByOrNull { it.handValue }?.id ?: botId
        Catalog.action(pending.cardDefId) ?: return emptyList()
        return applyLocked(GameAction.PlayAction(botId, target, pending.cardDefId))
    }

    // ═══════════════════════════════════════════
    // Plumbing
    // ═══════════════════════════════════════════

    /** Must be called with [mutex] held. */
    private fun applyLocked(action: GameAction): List<GameEvent> {
        val result = Engine.transition(state, action, rng)
        state = result.state
        return result.events
    }

    private fun view(): GameStateView = state.toView(code, hostId, turnDeadline)

    private suspend fun broadcast(events: List<GameEvent>) {
        val message = ServerMessage.State(view(), events)
        val payload = json.encodeToString(ServerMessage.serializer(), message)
        for (connection in connections.values) {
            connection.outbound.trySend(payload)
        }
    }

    suspend fun sendStateTo(playerId: String) {
        val connection = connections[playerId] ?: return
        val message = mutex.withLock { ServerMessage.State(view(), emptyList()) }
        connection.outbound.trySend(json.encodeToString(ServerMessage.serializer(), message))
    }

    private suspend fun send(connection: Connection, message: ServerMessage) {
        connection.outbound.trySend(json.encodeToString(ServerMessage.serializer(), message))
    }

    private fun sendRaw(connection: Connection, message: ServerMessage) {
        connection.outbound.trySend(json.encodeToString(ServerMessage.serializer(), message))
    }
}

// ═══════════════════════════════════════════
// Registry
// ═══════════════════════════════════════════

private const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

class RoomRegistry(private val json: Json, private val scope: CoroutineScope) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val random = Random.Default

    init {
        scope.launch {
            while (isActive) {
                delay(60_000)
                val now = System.currentTimeMillis()
                rooms.entries.removeIf { (_, room) ->
                    val stale = room.isEmpty() && room.isStale(now)
                    if (stale) room.close()
                    stale
                }
            }
        }
    }

    fun get(code: String): Room? = rooms[code.uppercase()]

    fun create(): Room {
        var code = generateCode()
        while (rooms.containsKey(code)) code = generateCode()
        val room = Room(code, random.nextLong(), json, scope)
        rooms[code] = room
        return room
    }

    fun size(): Int = rooms.size

    private fun generateCode(): String =
        (1..4).map { ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)] }.joinToString("")
}

fun newPlayerId(): String = java.util.UUID.randomUUID().toString().take(12)
