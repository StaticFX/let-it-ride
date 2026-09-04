package com.letitride.server

import com.letitride.engine.CUSTOM_DECK_ID
import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckPresets
import com.letitride.engine.sanitizeDeck
import com.letitride.engine.Engine
import com.letitride.engine.GameAction
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.LobbyRules
import com.letitride.engine.MAX_PLAYERS
import com.letitride.engine.PickKind
import com.letitride.engine.Player
import com.letitride.engine.PlayerStatus
import com.letitride.engine.Rng
import com.letitride.engine.RuleSet
import com.letitride.engine.SECOND_LIFE
import com.letitride.engine.SLOTS_SOURCE
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

/**
 * Everything the room waits out, scaled. One multiplier rather than a constant
 * each, so a table cannot be sped up unevenly and end up with a title card
 * still on screen while the first card is being dealt behind it.
 *
 * Always 1.0 on a real server — see [pacingFactor].
 */
private val PACE: Double = pacingFactor()

private fun paced(ms: Long): Long = maxOf(1L, (ms * PACE).toLong())

private val TICK_MS = paced(150L)
private val DEAL_STEP_MS = paced(750L)

/**
 * The client plays a "round N" title card before the table is visible, and the
 * room refuses to do anything at all until it has passed. The deadline is sent
 * to the client rather than agreed by convention, so the animation and the deal
 * cannot drift apart however slow the client is.
 */
private val ROUND_INTRO_MS = paced(2800L)

/** The pause between the title card lifting and the first card being dealt. */
private val POST_INTRO_MS = paced(300L)

/**
 * The closing beats of a round: whatever animation ended it, then a title card,
 * then the scoreboard. The room does not gate on these — the round is already
 * over — it just tells the client how long to hold the table.
 */
private val OUTRO_CARD_MS = paced(1700L)
internal val OUTRO_AFTER_BUST_MS = paced(2200L)
internal val OUTRO_AFTER_FLIP7_MS = paced(3100L)

private val FORCED_DRAW_STEP_MS = paced(800L)

/** How long the slot machine spins before the card it landed on is dealt. */
private val SLOTS_SPIN_MS = paced(2400L)

private val BOT_THINK_MS = paced(900L)
private val BOT_PICK_MS = paced(950L)
private const val EMPTY_ROOM_TTL_MS = 10 * 60 * 1000L

/**
 * How long the room will wait on a client that said it was animating and then
 * went quiet. This is a backstop, not a schedule: a client that acks normally
 * never comes near it. It has to clear the longest animation the client can
 * play by a comfortable margin, or a slow machine gets cut off mid-bust.
 */
internal val ANIMATION_GATE_MAX_MS = paced(5000L)

private val BOT_NAMES = listOf("Ace", "Bluff", "Chips", "Dice", "Echo", "Faro")

class Connection(val playerId: String, val outbound: Channel<String>)

/**
 * A batch of events one client is still animating.
 *
 * The room deliberately does not know what the animation is or how long it
 * runs — those belong to the client, and the two drifting apart is exactly what
 * this replaces. It knows only who to wait for and when to stop waiting.
 */
private data class AnimationGate(
    val id: Long,
    val ackPlayerId: String,
    val openedAt: Long,
    val deadline: Long,
)

/**
 * How long the closing animation needs before the round's title card goes up.
 * A round that simply ran out of players has nothing to wait for.
 */
internal fun outroPreambleFor(events: List<GameEvent>): Long = when {
    events.any { it is GameEvent.Flip7 } -> OUTRO_AFTER_FLIP7_MS
    events.any { it is GameEvent.Bust } -> OUTRO_AFTER_BUST_MS
    else -> 0L
}

/**
 * When a round that has just ended deals the next one on its own, or null when
 * the table waits to be told.
 *
 * [scoreboardAt] is when the closing card gives way to the scoreboard: the
 * countdown is against what the players are actually reading, not against the
 * moment the round ended, or a bust would eat most of it before anyone saw a
 * score. A round that settled the game never autostarts — the results screen is
 * the end of the evening and is not taken away from anybody.
 */
internal fun autoNextRoundAt(state: GameState, scoreboardAt: Long): Long? {
    if (state.gameWinnerId != null) return null
    val seconds = state.config.autoNextRoundSeconds ?: return null
    return scoreboardAt + seconds * 1000L
}

/**
 * One in-memory game. The room owns the authoritative [GameState]; clients only
 * ever send intents. Everything that needs pacing — the opening deal, forced
 * draws, bot moves and the turn clock — is driven by [tick] rather than by the
 * client, so a slow or hostile client cannot stall or rush the table.
 */
class Room(
    val code: String,
    /** Fixes every shuffle this room makes; a room is replayable from it alone. */
    val seed: Long,
    private val json: Json,
    parentScope: CoroutineScope,
    /**
     * Cards to put on top of the deck when the game starts, in order — see
     * [CreateRoomRequest.stack]. Empty for every real room.
     */
    private val stack: List<String> = emptyList(),
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
    private var roundIntroUntil: Long? = null
    private var roundOutroFrom: Long? = null
    private var roundOutroUntil: Long? = null
    private var nextRoundAt: Long? = null
    private var gate: AnimationGate? = null
    private var gateCounter = 0L

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
            // Waiting on an animation in a tab that has gone is waiting for the
            // ceiling; release the table now instead.
            if (gate?.ackPlayerId == playerId) closeGate(System.currentTimeMillis())
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

                is ClientMessage.AnimationDone -> {
                    val open = gate
                    if (open == null || open.id != message.gateId || open.ackPlayerId != playerId) return
                    closeGate(System.currentTimeMillis())
                    // Nothing new happened — but the table has to hear that the
                    // gate lifted, and the clock it gave back.
                    emptyList()
                }

                // Moves made while the table is animating are dropped rather
                // than applied late. The client already hides the buttons; this
                // is what makes a mistimed or stale click harmless.
                ClientMessage.Hit -> {
                    if (gate != null) return
                    applyLocked(GameAction.Hit(playerId))
                }

                ClientMessage.Stay -> {
                    if (gate != null) return
                    applyLocked(GameAction.Stay(playerId))
                }

                is ClientMessage.PlayAction -> {
                    if (gate != null) return
                    val pending = state.pendingAction
                    // Anybody the prompt asked may answer it, and only once.
                    if (pending == null || playerId !in pending.respondents) return
                    if (playerId in pending.answers) return
                    applyLocked(
                        GameAction.PlayAction(
                            playerId,
                            message.targetPlayerId,
                            message.cardDefId,
                            message.choice,
                            message.cards,
                        ),
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
        // A deck somebody built is kept, trimmed to something playable. Anything
        // else has its preset's own cards copied over whatever it arrived with,
        // so a config naming "chaos" is always the chaos everybody agreed on.
        val built = if (config.deckPresetId == CUSTOM_DECK_ID) sanitizeDeck(config.deck) else null
        val preset = if (built != null) null else DeckPresets.byId(config.deckPresetId) ?: DeckPresets.default
        return config.copy(
            deckPresetId = preset?.id ?: CUSTOM_DECK_ID,
            deck = preset?.deck ?: built!!,
            totalRounds = config.totalRounds.coerceIn(1, 20),
            targetScore = config.targetScore.coerceIn(50, 1000),
            turnTimeSeconds = config.turnTimeSeconds.coerceIn(10, 300),
            autoNextRoundSeconds = config.autoNextRoundSeconds?.coerceIn(5, 120),
            ruleIds = config.ruleIds.filter { id -> LobbyRules.all.any { it.id == id } }.distinct(),
        )
    }

    // ═══════════════════════════════════════════
    // Pacing
    // ═══════════════════════════════════════════

    /**
     * Runs whatever the table owes: the next card of the deal, the next forced
     * draw, a bot's move, or a clock that ran out. Returns null when the tick
     * did nothing, so an idle table costs no traffic — waiting on a human is
     * the overwhelmingly common case and it must not stream state at them.
     */
    private suspend fun tick() {
        val events: List<GameEvent>? = mutex.withLock {
            val now = System.currentTimeMillis()
            val snapshot = state

            if (snapshot.phase != GamePhase.PLAYING) {
                val wasTimed = turnDeadline != null || gate != null
                promptKey = null
                turnDeadline = null
                // A round that ended mid-animation hands the pacing over to the
                // outro window; holding a gate past it would strand the room.
                gate = null

                // "Autostart": the scoreboard has been up long enough and the
                // table deals itself. The host pressing the button first still
                // wins — [markRoundBoundaries] drops the deadline when the
                // round actually turns over.
                val deals = nextRoundAt
                if (snapshot.phase == GamePhase.ROUND_END && deals != null && now >= deals) {
                    nextRoundAt = null
                    return@withLock applyLocked(GameAction.NextRound)
                }

                return@withLock if (wasTimed) emptyList() else null
            }

            val prompt = promptOf(snapshot)
            var deadlineMoved = false
            if (prompt != promptKey) {
                promptKey = prompt
                nextStepAt = now + stepDelayFor(prompt, snapshot)
                val next = deadlineFor(prompt, snapshot, now)
                deadlineMoved = next != turnDeadline
                turnDeadline = next
            }

            // The round's title card owns the table: no dealing, no bots, no
            // clock until it has finished.
            val introUntil = roundIntroUntil
            if (introUntil != null) {
                if (now < introUntil) return@withLock null
                roundIntroUntil = null
                // A short beat between the card lifting and the first deal.
                nextStepAt = now + POST_INTRO_MS
                return@withLock emptyList()
            }

            // The clock is checked before the pacing gates — a human who never
            // answers has to be timed out no matter what else is scheduled. An
            // animation nobody can act through is not their thinking time,
            // though, so it does not count against them.
            val deadline = turnDeadline
            if (deadline != null && now >= deadline && gate == null) {
                turnDeadline = null
                return@withLock timeoutNow(snapshot)
            }

            // Nothing moves while a client is still animating the last batch.
            // This is the whole point of the gate: the step delays below are a
            // floor, and the animation finishing is what actually releases it.
            val animating = gate
            if (animating != null) {
                if (now < animating.deadline) return@withLock null
                // The client went quiet. Step anyway rather than let one tab
                // hold the table, and let it catch up from the next state.
                closeGate(now)
                return@withLock emptyList()
            }

            // Only the clock moved; the table still needs to hear about it.
            if (now < nextStepAt) return@withLock if (deadlineMoved) emptyList() else null

            val stepped = when {
                prompt.startsWith("deal:") -> applyLocked(GameAction.DealTo(prompt.removePrefix("deal:")))

                prompt.startsWith("forced:") -> applyLocked(GameAction.ForcedDraw)

                prompt.startsWith("pick:") -> {
                    // One bot per step, so a table of them answers at the same
                    // pace a person would rather than all at once.
                    val pending = snapshot.pendingAction
                    val bot = pending?.waitingOn?.firstOrNull { snapshot.player(it)?.isBot == true }
                    if (bot != null) botPick(snapshot, bot) else null
                }

                prompt.startsWith("turn:") -> {
                    val actor = prompt.removePrefix("turn:")
                    if (snapshot.player(actor)?.isBot == true) botMove(snapshot, actor) else null
                }

                else -> null
            }

            // A human's turn simply waits; only paced work reschedules.
            if (stepped == null) return@withLock if (deadlineMoved) emptyList() else null
            nextStepAt = now + stepDelayFor(promptOf(state), state)
            stepped
        }
        if (events != null) broadcast(events)
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

    private fun stepDelayFor(prompt: String, snapshot: GameState): Long = when {
        prompt.startsWith("deal:") -> DEAL_STEP_MS
        // A slots draw waits for the reels; every other forced draw is a flick.
        prompt.startsWith("forced:") && snapshot.forcedDraws?.source == SLOTS_SOURCE -> SLOTS_SPIN_MS
        prompt.startsWith("forced:") -> FORCED_DRAW_STEP_MS
        prompt.startsWith("pick:") -> BOT_PICK_MS
        else -> BOT_THINK_MS
    }

    /** Only humans are on the clock; bots always act well inside it. */
    private fun deadlineFor(prompt: String, snapshot: GameState, now: Long): Long? {
        val waiting: List<String> = when {
            // One clock covers a prompt however many people it asked, and it
            // runs for as long as any of them is a person. Reading the drawer
            // alone would leave a table of humans waiting on no clock at all
            // whenever a bot happened to draw the card.
            prompt.startsWith("pick:") -> snapshot.pendingAction?.waitingOn.orEmpty()
            prompt.startsWith("turn:") -> listOf(prompt.removePrefix("turn:"))
            else -> return null
        }
        if (waiting.none { snapshot.player(it)?.isBot == false }) return null
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

        // One card short of the flip the bonus is worth almost any risk — and
        // under "flip 9" that last card wins the whole game, so it is the
        // room's rules, not a constant, that says where the line sits.
        val flipTarget = RuleSet.of(snapshot.config).flipTarget
        val threshold = when {
            bot.hand.size >= flipTarget - 1 -> 0.60
            bot.hand.size <= 2 -> 0.45
            bot.handValue < 20 -> 0.30
            else -> 0.20
        }
        return risk < threshold
    }

    private fun botPick(snapshot: GameState, botId: String): List<GameEvent> {
        val pending = snapshot.pendingAction ?: return emptyList()
        Catalog.action(pending.cardDefId) ?: return emptyList()
        // Whatever the card does, the legal target with the most on the table is
        // the one worth pointing it at — and anyone else is preferred to itself.
        val candidates = pending.validTargets.mapNotNull { snapshot.player(it) }
        val target = candidates.filter { it.id != botId }.maxByOrNull { it.handValue }
            ?: candidates.firstOrNull()
            ?: return emptyList()
        // A coin has no smart call, so a bot simply calls one. It has to call
        // something: the card does not resolve without an answer. Asked of the
        // prompt rather than the card — the same card can ask a question the
        // first time it stops the table and nothing the second.
        val choice = rng.pick(pending.options)
        // A card that wants cards gets a shuffle: a bot picking off the top of
        // the list would trade the same two seats' first cards every time, and
        // the engine keeps the pick legal either way. A shop is the same — every
        // offer on it is already one this bot can afford, so any of them will
        // do, and taking the first would have every bot buy the same card.
        val cards = when (pending.kind) {
            PickKind.CARD -> rng.shuffled(pending.validCards)
            PickKind.CATALOG -> listOfNotNull(rng.pick(pending.offers)?.id)
            PickKind.PLAYER -> emptyList()
        }
        return applyLocked(GameAction.PlayAction(botId, target.id, pending.cardDefId, choice, cards))
    }

    // ═══════════════════════════════════════════
    // Plumbing
    // ═══════════════════════════════════════════

    /**
     * Puts the named cards on top of the freshly shuffled deck, in order.
     *
     * They are lifted out of the deck rather than added to it, so the deck is
     * still the same deck — every card conservation check the suite makes holds
     * either way. A name the deck does not hold is simply skipped: a spec that
     * asks for a card the table is not playing with gets the shuffle it would
     * have got anyway, and fails on what it was actually checking.
     */
    private fun stackDeck(state: GameState): GameState {
        if (stack.isEmpty()) return state
        val rest = state.deck.toMutableList()
        val top = mutableListOf<com.letitride.engine.Card>()
        for (name in stack) {
            val index = rest.indexOfFirst { it.defId == name || it.label == name }
            if (index >= 0) top += rest.removeAt(index)
        }
        return state.copy(deck = top + rest)
    }

    /** Must be called with [mutex] held. */
    private fun applyLocked(action: GameAction): List<GameEvent> {
        val before = state
        val result = Engine.transition(state, action, rng)
        state = result.state
        // The deck is built and shuffled by StartGame, so this is the one
        // moment a stacked deck can be arranged.
        if (action is GameAction.StartGame && before.phase != state.phase) state = stackDeck(state)
        markRoundBoundaries(before, result.state, result.events)
        openGate(action, before, result.events)
        return result.events
    }

    /**
     * Holds the table on the batch about to be broadcast. Any batch with
     * something in it gates: the room has no idea which events the client draws
     * something for, and asking it to keep that list in step with the frontend
     * is the coupling this whole mechanism exists to remove. A batch the client
     * has no animation for is acked the moment it lands, which costs a round
     * trip and nothing else.
     *
     * Only a round still in play is gated. A round that just ended already has
     * its closing window, and holding it here would fight that.
     */
    private fun openGate(action: GameAction, before: GameState, events: List<GameEvent>) {
        if (events.isEmpty() || state.phase != GamePhase.PLAYING) return
        val acker = ackPlayerFor(action, before) ?: return
        val now = System.currentTimeMillis()
        gateCounter += 1
        gate = AnimationGate(gateCounter, acker, now, now + ANIMATION_GATE_MAX_MS)
    }

    /**
     * Who times this batch. The player it happened to is the one watching it
     * closely, so they own it — but a bot cannot ack and neither can a seat
     * whose socket has gone, and a table of bots that gated on nobody would be
     * back to guessing. The host's client keeps time in that case; it is
     * watching the same animation from the same events.
     *
     * Null means nobody is connected to wait for, and the step delays alone
     * pace the table.
     */
    private fun ackPlayerFor(action: GameAction, before: GameState): String? {
        val actor = when (action) {
            is GameAction.Hit -> action.playerId
            is GameAction.Stay -> action.playerId
            is GameAction.PlayAction -> action.fromPlayerId
            is GameAction.Timeout -> action.playerId
            is GameAction.DealTo -> action.playerId
            GameAction.ForcedDraw -> before.forcedDraws?.playerId
            else -> null
        }
        val human = actor != null &&
            before.player(actor)?.isBot == false &&
            connections.containsKey(actor)
        return if (human) actor else hostId?.takeIf { connections.containsKey(it) }
    }

    /** Must be called with [mutex] held. */
    private fun closeGate(now: Long) {
        val open = gate ?: return
        gate = null
        // The player could not act while the table was animating, so the time
        // it took is given back rather than counted against their clock.
        turnDeadline = turnDeadline?.plus(now - open.openedAt)
    }

    /**
     * Opens the title-card window when a round starts and the closing window
     * when it ends. Both are absolute timestamps the client renders against.
     */
    private fun markRoundBoundaries(before: GameState, after: GameState, events: List<GameEvent>) {
        val now = System.currentTimeMillis()

        val roundOpening = after.phase == GamePhase.PLAYING &&
            after.dealQueue.isNotEmpty() &&
            after.dealQueue.size == after.players.size
        if (roundOpening) {
            roundIntroUntil = now + ROUND_INTRO_MS
            roundOutroFrom = null
            roundOutroUntil = null
        } else if (after.phase != GamePhase.PLAYING) {
            roundIntroUntil = null
        }

        // Whoever got there first — the clock or the host — the round has turned
        // over and the old deadline is spent.
        if (after.phase != GamePhase.ROUND_END) nextRoundAt = null

        if (before.phase == GamePhase.PLAYING && after.phase == GamePhase.ROUND_END) {
            val preamble = outroPreambleFor(events)
            roundOutroFrom = now + preamble
            roundOutroUntil = now + preamble + OUTRO_CARD_MS
            nextRoundAt = autoNextRoundAt(after, roundOutroUntil!!)
        }
    }

    private fun view(): GameStateView = state.toView(
        code,
        hostId,
        turnDeadline,
        roundIntroUntil,
        roundOutroFrom,
        roundOutroUntil,
        gate?.let { AnimationGateView(it.id, it.ackPlayerId, it.deadline) },
        nextRoundAt,
    )

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

    /** [seed] fixes the room's shuffles; the caller decides whether that is allowed. */
    fun create(seed: Long? = null, stack: List<String> = emptyList()): Room {
        var code = generateCode()
        while (rooms.containsKey(code)) code = generateCode()
        val room = Room(code, seed ?: random.nextLong(), json, scope, stack)
        rooms[code] = room
        return room
    }

    fun size(): Int = rooms.size

    private fun generateCode(): String =
        (1..4).map { ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)] }.joinToString("")
}

fun newPlayerId(): String = java.util.UUID.randomUUID().toString().take(12)
