package com.letitride.server

import com.letitride.account.RoomTally
import com.letitride.account.StatsRecorder
import com.letitride.engine.CUSTOM_DECK_ID
import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckPresets
import com.letitride.engine.sanitizeDeck
import com.letitride.engine.Engine
import com.letitride.engine.GamblerCardDef
import com.letitride.engine.GameAction
import com.letitride.engine.GameConfig
import com.letitride.engine.GameEvent
import com.letitride.engine.GameMode
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.LobbyRules
import com.letitride.engine.PHASE_GIVE
import com.letitride.engine.PHASE_HANDOVER
import com.letitride.engine.forcedRulesFor
import com.letitride.engine.Card
import com.letitride.engine.MAX_PLAYERS
import com.letitride.engine.PassiveScoring
import com.letitride.engine.PendingAction
import com.letitride.engine.PickKind
import com.letitride.engine.Player
import com.letitride.engine.PlayerStatus
import com.letitride.engine.Rarity
import com.letitride.engine.Rng
import com.letitride.engine.RuleSet
import com.letitride.engine.SECOND_LIFE
import com.letitride.engine.SLOTS_SOURCE
import com.letitride.engine.WinCondition
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
 *
 * These are the one place the server has to know roughly how long an animation
 * runs, because the round is over and there is no gate left to wait on. Each is
 * the client's own duration for that animation plus the beat it waits for the
 * played card to land (`SMASH_LAND_MS`), plus a little. Lengthen an animation
 * in `useGame.ts` and the matching number here has to follow, or the closing
 * card lands on top of it — which is exactly what used to happen to the coin.
 */
private val OUTRO_CARD_MS = paced(1700L)

/**
 * The round's payout: every seat is paid what it made, one at a time, on the
 * table it made it on — before the closing card comes over and takes the table
 * away. A round used to end with four scores changing at once behind a card
 * nobody could see through.
 *
 * The window is reserved here and spent by the client, which lands one seat
 * every [OUTRO_PAYOUT_STEP_MS] — keep the two in step, the same way the closing
 * windows above are kept in step with the animations they are waiting for.
 */
private val OUTRO_PAYOUT_LEAD_MS = paced(400L)
private val OUTRO_PAYOUT_STEP_MS = paced(560L)
private val OUTRO_PAYOUT_TAIL_MS = paced(600L)

/** How long the table needs to pay [players] seats, one after another. */
internal fun payoutWindowFor(players: Int): Long =
    if (players <= 0) 0L else OUTRO_PAYOUT_LEAD_MS + players * OUTRO_PAYOUT_STEP_MS + OUTRO_PAYOUT_TAIL_MS
// A bust with no card of its own — a coin called wrong, a bottle, a hand that
// busted on something it was given. The card that inflicted it is the
// announcement, so the bust waits for that card to land before it starts, and
// this covers `SMASH_LAND_MS` as well as the client's own two beats.
internal val OUTRO_AFTER_BUST_MS = paced(2800L)
internal val OUTRO_AFTER_FLIP7_MS = paced(3300L)
internal val OUTRO_AFTER_COIN_MS = paced(3300L)
internal val OUTRO_AFTER_SPIN_MS = paced(3100L)
internal val OUTRO_AFTER_TRANSFER_MS = paced(2400L)

/**
 * ...and the same bust, when the card that did it came off the deck this turn.
 *
 * Twice the window, for an animation that is doing twice the work. A bust
 * inflicted by a card — a coin called wrong, a bottle, an assassination — has
 * already been explained by the card that inflicted it, and the table only has
 * to be told who. A bust the player *drew* has no other announcement, so the
 * card is carried up over the seat and held there before it comes down, which
 * is the only beat in the game between finding out and knowing.
 *
 * The client draws exactly the same distinction, off exactly the same fact —
 * whether the busting card is in a [GameEvent.Draw] in this batch — so the two
 * halves of it stay in step. See `BUST_CARD_MS` in `useGame.ts`.
 *
 * No `SMASH_LAND_MS` term, unlike [OUTRO_AFTER_BUST_MS]: this one is never held
 * back behind a played card. The card is coming off the deck and is dealt on the
 * beat every other draw in the batch is.
 */
internal val OUTRO_AFTER_DRAWN_BUST_MS = paced(4600L)

/**
 * A second life being spent, which is the longest thing the client can play.
 *
 * A save is a whole scene rather than a caption now — the card that would have
 * ended the round is held up in the middle of the screen and torn in half by
 * the one being spent to stop it — and it can land in a round-ending batch:
 * a banked seat can burn a second life in the same transition that busts the
 * last active one (see `resolveBustAfterGain(finishedToo = true)`). Without a
 * window of its own the closing card would come over halfway through it.
 */
internal val OUTRO_AFTER_SECOND_LIFE_MS = paced(6000L)

/**
 * A gambler card ending a round has to be readable on the way out.
 *
 * A card played in the last moment of a round may be one nobody at the table has
 * ever seen, and the closing card comes down on whatever this number allowed.
 * Its client half is `ANIMATION_TTL_MS.gamblerPlayed` at its first-sight length,
 * plus a beat — lengthen one and this has to follow. It was the longest window
 * here until a save became something to watch; see [OUTRO_AFTER_SECOND_LIFE_MS].
 */
internal val OUTRO_AFTER_GAMBLER_MS = paced(4800L)

private val FORCED_DRAW_STEP_MS = paced(800L)

/**
 * The floor under a card that has landed but not yet taken effect — see
 * [com.letitride.engine.PendingOutcome]. Only a floor: what actually holds the
 * table here is the animation gate, and this is what covers a table with nobody
 * connected to open one.
 */
private val OUTCOME_STEP_MS = paced(1600L)

/** How long the slot machine spins before the card it landed on is dealt. */
private val SLOTS_SPIN_MS = paced(2400L)

private val BOT_THINK_MS = paced(900L)
private val BOT_PICK_MS = paced(950L)

/**
 * How long a response window stays open before silence is taken as a no.
 *
 * Its own clock, and much shorter than a turn's: holding a whole table for
 * thirty seconds over a counter most of them cannot play is not a decision, it
 * is a wait. Twelve rather than eight because somebody being asked may be
 * reading two cards they have never seen — the one in flight and the one in
 * their own hand — and only then deciding.
 */
private val RESPONSE_WINDOW_MS = paced(12_000L)

/**
 * The ceiling on the shop between rounds, from the host's setting.
 *
 * Paced like every other beat the room keeps, and a ceiling rather than a
 * schedule: the window shuts the moment every seat says it is finished, which at
 * a table with bots on it is about a second.
 */
private fun shopWindowFor(config: GameConfig): Long = paced(config.shopSeconds * 1000L)

/** A bot's shopping is not an animation. It only has to not be instant. */
private val BOT_SHOP_STEP_MS = paced(280L)

/**
 * How long the table reads the bids before the winner pays.
 *
 * The interlude has no animation gate — there is no table to hold — so unlike
 * everything else in this file this really is the server deciding how long a
 * moment takes. It is the client's `showdown` duration and a beat.
 */
private val AUCTION_REVEAL_MS = paced(2900L)

/** ...and the beat after the money moves, before the next round is dealt. */
private val AUCTION_SETTLE_MS = paced(900L)
private const val EMPTY_ROOM_TTL_MS = 10 * 60 * 1000L

/**
 * How long the room will wait on a client that said it was animating and then
 * went quiet. This is a backstop, not a schedule: a client that acks normally
 * never comes near it. It has to clear the longest animation the client can
 * play by a comfortable margin, or a slow machine gets cut off mid-bust.
 *
 * It was five seconds while every card at the table was one you already knew,
 * and seven once a gambler card nobody has seen had to be *read* — a name and a
 * sentence, by three people at once. It is eight now that the two moments a
 * round actually turns on are played out rather than announced: a second life
 * spent is five seconds of client, and a card played in front of it starts it
 * nearly a second late. The cost of the extra second is only that a hung tab
 * owns a table for that much longer; animating time is handed straight back to
 * whoever is on the clock (see [closeGate]), so nobody's turn is shorter for it.
 */
internal val ANIMATION_GATE_MAX_MS = paced(8000L)

/**
 * One per seat, so a full table of bots never falls through to "Bot 7".
 * Alphabetical because that is how you tell at a glance that nobody is missing.
 */
private val BOT_NAMES =
    listOf("Ace", "Bluff", "Chips", "Dice", "Echo", "Faro", "Gambit", "Hazard", "Ivory", "Joker")

class Connection(val playerId: String, val outbound: Channel<String>)

/**
 * Trims a batch of events for one viewer, against the state they arrived with.
 *
 * The single place an event is cut down for who is reading it, and it should
 * stay that way — a second one would be a second place to forget. Everything
 * else in the game is public by design: the table watches what happens and
 * replays it as animation, and an event nobody may see is an event nobody can
 * animate.
 *
 * Two things are hidden, and they hide differently. A gambler card going into a
 * tray is cut out altogether — nobody but its owner is told which card it even
 * was. A card going into, or coming out of, a hand behind a "redacted" keeps its
 * id and loses its *face*, because the table still has to watch it fly and count
 * it when it lands; `Card.faceDown` is that cut.
 *
 * [state] is the state *after* the transition, which is what turns a busted
 * seat's cards face up in the same push as the card that busted them:
 * `Engine.handIsHidden` stops being true in the same breath as the bust.
 *
 * A free function rather than a method on [Room] because it is pure — a batch, a
 * reader and a table in, a batch out — and because the one thing worth testing
 * about it is exactly that. `EventRedactionTest` walks the sealed hierarchy and
 * fails when an event carrying a card is neither listed as public nor handled
 * here, which is what stops the next hidden thing being added without a line in
 * this function.
 */
internal fun redactFor(events: List<GameEvent>, viewerId: String, state: GameState): List<GameEvent> {
    val hidden = state.players
        .filter { it.id != viewerId && Engine.handIsHidden(it) }
        .map { it.id }
        .toSet()
    if (hidden.isEmpty() && events.none { it is GameEvent.GamblerDrawn }) return events

    // Only a number card lands in a hand. A modifier goes to the row in front of
    // the seat and a spent action card to the discard pile, both of which are
    // public and neither of which this card claims to cover.
    fun into(playerId: String, card: Card) = playerId in hidden && card.kind == CardKind.NUMBER

    return events.map { event ->
        when (event) {
            // Nobody but the drawer is told which card, only that one went into
            // a hand nobody can see.
            is GameEvent.GamblerDrawn ->
                if (event.playerId != viewerId) event.copy(card = null) else event

            // Arriving in a hidden hand: the flight is public, the face is not.
            // The same question four times, read off where each card is *going*
            // rather than where it came from — a card leaving one is lying face
            // up in front of somebody else by the time this goes out, and
            // cutting it there would make that seat's own hand unreadable.
            is GameEvent.Draw ->
                if (into(event.playerId, event.card)) event.copy(card = event.card.faceDown()) else event

            is GameEvent.Steal ->
                if (into(event.toPlayerId, event.card)) event.copy(card = event.card.faceDown()) else event

            is GameEvent.Redirected ->
                if (into(event.toPlayerId, event.card)) event.copy(card = event.card.faceDown()) else event

            // ...and the one bought straight into it, whose price stays public
            // because a score that moves has to be accountable.
            is GameEvent.Bought ->
                if (into(event.playerId, event.card)) event.copy(card = event.card.faceDown()) else event

            // The card the reels are about to land on, which is this seat's next
            // card announced a beat early.
            is GameEvent.Slots -> {
                val card = event.card
                if (card != null && into(event.playerId, card)) event.copy(card = card.faceDown()) else event
            }

            // Two cards, each read by where it is going.
            is GameEvent.CardsSwapped -> event.copy(
                firstCard = if (into(event.secondPlayerId, event.firstCard)) {
                    event.firstCard.faceDown()
                } else {
                    event.firstCard
                },
                secondCard = if (into(event.firstPlayerId, event.secondCard)) {
                    event.secondCard.faceDown()
                } else {
                    event.secondCard
                },
            )

            // Leaving one, and the only two that can while their owner is still
            // in the round. The discard pile is a *count* on the wire, so this
            // event is the only look anybody would get at a card that was in a
            // hidden hand a moment ago — and knowing what has gone narrows what
            // is left, which is the thing the card sells.
            is GameEvent.Discard ->
                if (event.playerId in hidden) event.copy(card = event.card.faceDown()) else event

            // Both halves of a save, or neither: the two cards match by
            // definition, so cutting one and not the other names it anyway. The
            // card spent to stop it stays — it came off the modifier row, which
            // everybody can see.
            is GameEvent.SecondChance ->
                if (event.playerId in hidden) {
                    event.copy(card = event.card.faceDown(), matched = event.matched?.faceDown())
                } else {
                    event
                }

            else -> event
        }
    }
}

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
 *
 * The longest of whatever the batch contains, rather than the first match: a
 * coin called wrong sends a coin flip *and* the bust it caused, and the coin is
 * still turning long after the bust would have been done with.
 */
internal fun outroPreambleFor(events: List<GameEvent>): Long {
    // One event is not enough to size a bust: what the table is about to watch
    // depends on how the card got there, and the only thing that says so is
    // whether the rest of the batch drew it. Read once for the batch rather
    // than per event, since [closingWindowFor] is asked about every one of them.
    val drawn = events.filterIsInstance<GameEvent.Draw>().map { it.playerId to it.card.id }.toSet()
    return events.maxOfOrNull { closingWindowFor(it, drawn) } ?: 0L
}

private fun closingWindowFor(event: GameEvent, drawnThisBatch: Set<Pair<String, String>>): Long = when (event) {
    is GameEvent.GamblerPlayed -> OUTRO_AFTER_GAMBLER_MS
    is GameEvent.SecondChance -> OUTRO_AFTER_SECOND_LIFE_MS
    is GameEvent.Flip7 -> OUTRO_AFTER_FLIP7_MS
    is GameEvent.CoinFlip -> OUTRO_AFTER_COIN_MS
    is GameEvent.BottleSpin -> OUTRO_AFTER_SPIN_MS
    is GameEvent.PointsTransferred -> OUTRO_AFTER_TRANSFER_MS
    // Matched on the seat as well as the card, so a drawn card handed straight
    // on to somebody else — see "redirect" — is not mistaken for one the seat
    // it busted took off the deck itself. The client asks the same question the
    // same way, and shows the long animation to exactly the answers this does.
    is GameEvent.Bust ->
        if (event.card != null && (event.playerId to event.card.id) in drawnThisBatch) OUTRO_AFTER_DRAWN_BUST_MS
        else OUTRO_AFTER_BUST_MS

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
 * What a card lying in front of a player is worth to a bot deciding what to
 * trade. Numbers are worth what they say; a modifier that scores is worth
 * having; and a card nobody wants is worth less than nothing, which is what
 * makes a bot hand one over rather than shuffle its cards at random.
 */
/**
 * What a gambler card is worth to a bot.
 *
 * Rarity first, and rarity only. A bot cannot read a card's text, and the rarity
 * is the game's own statement of how good it is — while valuing one at its
 * *price* would have a bot buy whatever was dearest, which is the shop selling
 * itself.
 */
internal fun gamblerWorth(def: GamblerCardDef): Int = when (def.rarity) {
    Rarity.COMMON -> 40
    Rarity.RARE -> 95
    Rarity.JACKPOT -> 220
}

/** What share of what it came in with a bot will put through the shop. */
internal const val BOT_SHOP_SHARE = 0.4

/**
 * ...and how close to winning it has to be before it stops shopping altogether.
 * The shop is a way to make points, and a bot spending past a win it already
 * had would be spending to lose.
 */
internal const val BOT_SHOP_ENDGAME = 40

/**
 * What a bot will spend this window.
 *
 * Measured against what it came in holding rather than what it has left, or a
 * share of a shrinking purse would let it keep buying for ever.
 */
internal fun botBudget(state: GameState, botId: String): Int {
    val player = state.player(botId) ?: return 0
    val opening = state.interlude?.openingScore?.get(botId) ?: player.score
    if (state.config.winCondition == WinCondition.FIRST_TO_SCORE &&
        player.score >= state.config.targetScore - BOT_SHOP_ENDGAME
    ) {
        return 0
    }
    val spent = (opening - player.score).coerceAtLeast(0)
    return ((opening * BOT_SHOP_SHARE).toInt() - spent).coerceAtLeast(0)
}

/**
 * The next card a bot takes off its shelf, or null when it is finished.
 *
 * The biggest bargain it can pay for out of what is left of its budget and what
 * will still fit in its hand — bargain being what the card is worth to it less
 * what it is being asked for, so a cheap rare beats a dear common and a shelf of
 * overpriced commons is walked away from.
 */
internal fun botShop(state: GameState, botId: String): String? {
    val shop = state.interlude ?: return null
    val player = state.player(botId) ?: return null
    if (player.gamblers.size >= Engine.gamblerLimitFor(state, botId)) return null
    val budget = minOf(botBudget(state, botId), player.score)
    val taken = shop.bought[botId].orEmpty()

    return shop.stock[botId].orEmpty()
        .filterNot { it.id in taken }
        .filter { it.price <= budget }
        .mapNotNull { offer ->
            val def = Catalog.gambler(offer.card.defId) ?: return@mapNotNull null
            offer.id to gamblerWorth(def) - offer.price
        }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

/** What share of its budget a bot will put into the auction rather than the shop. */
internal const val BOT_AUCTION_SHARE = 0.5

/**
 * What a bot bids for the lot, or null for no bid.
 *
 * Under what the card is worth to it, by a margin off the room's own seeded
 * stream. A bot bidding its whole valuation would win every auction it wanted
 * and pay exactly what it gained, and two bots at one table would bid the same
 * number every single time.
 */
internal fun botBid(state: GameState, botId: String, rng: Rng): Int? {
    val lot = state.interlude?.lot ?: return null
    val player = state.player(botId) ?: return null
    if (player.gamblers.size >= Engine.gamblerLimitFor(state, botId)) return null
    val def = Catalog.gambler(lot.card.defId) ?: return null

    val ceiling = minOf(
        (botBudget(state, botId) * BOT_AUCTION_SHARE).toInt(),
        player.score,
        gamblerWorth(def),
    )
    if (ceiling <= 0) return null
    // Somewhere between half the ceiling and the ceiling, so two bots at one
    // table do not bid the same number.
    return (ceiling / 2 + rng.nextInt(ceiling / 2 + 1)).coerceAtLeast(1)
}

internal fun cardWorth(card: Card): Int {
    if (card.kind == CardKind.NUMBER) return card.value
    val def = Catalog.passive(card.defId) ?: return 5
    if (def.isCurse) return -100
    return when (def.scoring) {
        PassiveScoring.DOUBLE_NUMBERS -> 25
        PassiveScoring.FLAT -> def.bonusPoints
        else -> 10
    }
}

/** What aiming a card at this seat pays, before anything the card itself does. */
internal fun tollFrom(player: Player): Int =
    player.passives.mapNotNull { Catalog.passive(it.defId) }.sumOf { it.spite }

/**
 * Which cards a bot points a card-picking prompt at.
 *
 * A circlejerk either way is asking which of its own cards to *give away*, so
 * the bot leads with the worst thing it is holding — which is how a discordia
 * or an antimatter finds a new home — and fills the rest in behind it.
 *
 * A prompt that asks the whole table at once is otherwise asking each of them
 * for one of their own — an "all in" bet — and it is the highest and the lowest
 * bet that pay for it, so the bot bets from the middle of its hand.
 *
 * A prompt that asks one player for two is a trade, and the bot plays it as
 * one: lead with the worst thing it is holding, and take the best thing
 * somebody else has that will not collide with a card it already holds. The
 * engine keeps the pick legal either way; this only decides which legal pick it
 * is.
 */
internal fun botCardPicks(
    snapshot: GameState,
    botId: String,
    pending: PendingAction,
    rng: Rng,
): List<String> {
    val offered = pending.validCards.toSet()
    val bot = snapshot.player(botId) ?: return rng.shuffled(pending.validCards)
    val mine = (bot.hand + bot.passives).filter { it.id in offered }

    if (pending.phase == PHASE_GIVE || pending.phase == PHASE_HANDOVER) {
        // These cards are leaving, so they go worst first — a bet is the only
        // other prompt that asks for your own cards and there the card stays.
        val worst = mine.sortedBy { cardWorth(it) }.map { it.id }
        return worst + rng.shuffled(pending.validCards.filterNot { it in worst })
    }

    if (pending.respondents.size > 1) {
        // Everybody is being asked at once: this is a bet, and the ends of
        // the table are what it costs.
        val ordered = mine.sortedBy { cardWorth(it) }
        val middle = ordered.getOrNull(ordered.size / 2) ?: return rng.shuffled(pending.validCards)
        return listOf(middle.id)
    }

    val heldLabels = bot.hand.map { it.label }.toSet()
    val theirs = snapshot.players
        .filter { it.id != botId }
        .flatMap { it.hand + it.passives }
        .filter { it.id in offered }
    val give = mine.minByOrNull { cardWorth(it) }
    val take = theirs
        // A number card it already has a copy of would bust it on arrival.
        .filterNot { it.kind == CardKind.NUMBER && it.label in heldLabels }
        .maxByOrNull { cardWorth(it) }
        ?: theirs.maxByOrNull { cardWorth(it) }
    val wanted = listOfNotNull(give?.id, take?.id)
    // Shuffled behind the pick it actually wants, so a pick that turns out
    // to be illegal still falls back to something other than the top of the
    // list every time.
    return wanted + rng.shuffled(pending.validCards.filterNot { it in wanted })
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
    stack: List<String> = emptyList(),
    /**
     * Whether this room takes dev commands and shows the deck — see [DevMode].
     * False for every real room, and the only thing that can turn it on is the
     * server's own environment.
     */
    private val dev: Boolean = false,
    /**
     * Where finished games are written down, or null on a server keeping no
     * history — which is every server until somebody configures one. Nothing
     * about how the table plays depends on it.
     */
    private val recorder: StatsRecorder? = null,
) {
    private val rng = Rng(seed)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]))

    private val connections = ConcurrentHashMap<String, Connection>()

    /**
     * Who at this table is signed in, and what they have done since the last
     * round was written down.
     *
     * Always present and almost always empty: a table of guests seats nobody in
     * it, so every call on it is a map lookup that finds nothing. That is
     * cheaper than asking whether stats are on at each of the half-dozen places
     * that would otherwise have to, and it keeps the recording in one place
     * instead of scattered through the room.
     */
    private val tally = RoomTally(code)

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

    /**
     * Epoch millis the shop shuts, or null when no window is open.
     *
     * The room's, not five browsers' — each running its own two minutes would
     * drift, and the one that drifted long would be the one that got to shop.
     */
    private var interludeUntil: Long? = null
    private var gate: AnimationGate? = null
    private var gateCounter = 0L

    /**
     * What goes on top of the deck the moment there is one, and nothing once it
     * has been dealt. A room created with a stack starts with it; a dev client
     * asking for cards before the game has started replaces it, because there is
     * no deck yet to put them on.
     */
    private var pendingStack: List<String> = stack

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

    /**
     * Registers a socket. Returns false when the room cannot take the player.
     *
     * [accountId] is who the *server* worked out this is, from the session
     * cookie on the handshake and never from anything the client said — a seat
     * identity is a claim, and a claim is not something anybody's history
     * should be written against. Null is a guest, which is most people, and
     * changes nothing about the seat.
     */
    suspend fun attach(playerId: String, name: String, connection: Connection, accountId: String? = null): Boolean {
        val events: List<GameEvent>
        mutex.withLock {
            val existing = state.player(playerId)
            if (existing == null) {
                if (state.phase != GamePhase.LOBBY || state.players.size >= MAX_PLAYERS) return false
                if (accountId != null) tally.seat(playerId, accountId)
                events = applyLocked(GameAction.AddPlayer(playerId, name))
            } else {
                // Coming back to a seat re-states the link rather than assuming
                // it survived: a room outlives a socket, but a tab that has
                // signed out and back in is a different account on the same id.
                if (accountId != null) tally.seat(playerId, accountId) else tally.unseat(playerId)
                events = applyLocked(GameAction.SetConnected(playerId, true))
            }
            connections[playerId] = connection
            emptySince = null
            if (hostId == null || state.player(hostId!!) == null) hostId = playerId
        }
        send(connection, ServerMessage.Welcome(playerId, code, hostId == playerId))
        // The connection was registered before this, so the rejoiner's own copy
        // of the state — the only one carrying their hidden hand — comes out of
        // this broadcast like everybody else's. A second, private push would be
        // an extra empty batch arriving while the table may be mid-animation,
        // and the client's escape hatch for an empty batch is to ack the gate.
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
            // ...and the account link follows the seat, not the socket. A
            // player who has genuinely left the table has no result coming;
            // one who has folded mid-game still has a score to be placed on,
            // and losing their link here would quietly wipe the game they were
            // in the middle of.
            if (state.player(playerId) == null) tally.unseat(playerId)
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

                // Dropped while the table is animating, for the same reason a
                // hit is: the client holds the click until the gate lifts and
                // sends it then, so nothing a player actually meant is lost.
                is ClientMessage.PlayGambler -> {
                    if (gate != null) return
                    applyLocked(GameAction.PlayGambler(playerId, message.cardId))
                }

                ClientMessage.Pass -> {
                    if (gate != null) return
                    applyLocked(GameAction.PassResponse(playerId))
                }

                // The shop is not gated — there is no animation between rounds
                // — and both of these are dropped anywhere else by the engine.
                is ClientMessage.Buy -> applyLocked(GameAction.Buy(playerId, message.offerId))

                is ClientMessage.ShopDone -> applyLocked(GameAction.FinishShopping(playerId, message.bid))

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

                ClientMessage.PlayAgain -> {
                    if (playerId != hostId) return
                    val restarted = applyLocked(GameAction.PlayAgain)
                    // The pacer was timing a game that no longer exists. Every
                    // one of these is worked out again by [tick] from the state
                    // it is handed — the same clearing [applyDev] does, and for
                    // the same reason — so dropping them is enough. What is
                    // deliberately left alone is anything that has to keep
                    // climbing for the life of the *room*: `gateCounter`, whose
                    // reuse would let a stale ANIM_DONE release a live gate,
                    // `botCounter`, whose reuse would name two seats the same,
                    // `seenGamblers`, because the same people have still seen
                    // what they saw, and the room's `rng`, which is the one
                    // stream a replay from the seed rests on.
                    promptKey = null
                    turnDeadline = null
                    nextStepAt = 0
                    roundIntroUntil = null
                    roundOutroFrom = null
                    roundOutroUntil = null
                    nextRoundAt = null
                    interludeUntil = null
                    gate = null
                    restarted
                }

                is ClientMessage.Kick -> {
                    if (playerId != hostId || message.playerId == hostId) return
                    val target = connections[message.playerId]
                    if (target != null) sendRaw(target, ServerMessage.Kicked)
                    connections.remove(message.playerId)
                    target?.outbound?.close()
                    val dropped = applyLocked(GameAction.RemovePlayer(message.playerId))
                    if (state.player(message.playerId) == null) tally.unseat(message.playerId)
                    dropped
                }

                ClientMessage.AddBot -> {
                    if (playerId != hostId || state.phase != GamePhase.LOBBY) return
                    val name = BOT_NAMES.getOrNull(botCounter) ?: "Bot ${botCounter + 1}"
                    botCounter++
                    applyLocked(GameAction.AddPlayer("bot-${code}-$botCounter", name, isBot = true))
                }

                // Nothing at all on a real server. Not host-only either: a
                // second browser is how half of this gets tested, and there is
                // no trust to protect on a table that only exists locally.
                is ClientMessage.Dev -> {
                    if (!dev) return
                    applyDev(message.setup)
                }
            }
        }
        broadcast(events)
    }

    /**
     * Writes a dev setup onto the table — see [DevMode]. Must be called with
     * [mutex] held.
     *
     * The state it produces is one the engine never went through, so everything
     * the room was timing against it is dropped: the prompt it thought was open,
     * the clock it started for whoever used to be on turn, the animation it was
     * waiting to be told about. [tick] works all of that out again from the
     * state it is given, so clearing them is enough.
     */
    private fun applyDev(setup: DevSetup): List<GameEvent> {
        setup.stack?.let { names ->
            // Before the deal there is no deck to stack; hold it for [stackDeck].
            if (state.phase == GamePhase.LOBBY) pendingStack = names
        }
        state = DevMode.apply(state, if (state.phase == GamePhase.LOBBY) setup.copy(stack = null) else setup)

        promptKey = null
        turnDeadline = null
        nextStepAt = 0

        if (setup.skipWait) {
            roundIntroUntil = null
            roundOutroFrom = null
            roundOutroUntil = null
            gate = null
        }

        // The round is over as far as the state is concerned — every seat is out
        // — but only the engine can score it. Poking it with a step that does
        // nothing on its own is what gets it to notice and settle.
        if (setup.endRound && state.phase == GamePhase.PLAYING) return applyLocked(GameAction.ForcedDraw)

        return emptyList()
    }

    /** Clamps host-supplied config so a crafted message cannot break a game. */
    private fun sanitize(config: GameConfig): GameConfig {
        // A deck somebody built is kept, trimmed to something playable. Anything
        // else has its preset's own cards copied over whatever it arrived with,
        // so a config naming "chaos" is always the chaos everybody agreed on.
        val built = if (config.deckPresetId == CUSTOM_DECK_ID) sanitizeDeck(config.deck) else null
        val preset = if (built != null) null else DeckPresets.byId(config.deckPresetId) ?: DeckPresets.default
        val chosen = preset?.deck ?: built!!
        // A gambler card outside the mode that gives you somewhere to put it
        // would be dealt into a hidden hand no screen draws — visible to nobody,
        // playable by nobody, and gone from the deck. The deck and the mode are
        // separate settings and either can be changed after the other, so the
        // combination is reachable however carefully the lobby is written; this
        // is the one place both are known at once, so it is the place to say so.
        val deck =
            if (config.mode == GameMode.ROLLING_RULES || chosen.gamblerCards.isEmpty()) chosen
            else chosen.copy(gamblerCards = emptyList())
        return config.copy(
            deckPresetId = preset?.id ?: CUSTOM_DECK_ID,
            deck = deck,
            totalRounds = config.totalRounds.coerceIn(1, 20),
            targetScore = config.targetScore.coerceIn(50, 1000),
            turnTimeSeconds = config.turnTimeSeconds.coerceIn(10, 300),
            // The floor is what stops a crafted config setting nought and
            // skipping everybody's shop; the ceiling is what stops one setting
            // an hour.
            shopSeconds = config.shopSeconds.coerceIn(15, 300),
            autoNextRoundSeconds = config.autoNextRoundSeconds?.coerceIn(5, 120),
            // Rolling rules always underlays "extreme", and it is put there
            // rather than assumed: the lobby then *shows* it on, the rules book
            // describes the game being played, and nothing has to remember the
            // exception. This is also the only thing between a crafted
            // `SET_CONFIG` and the engine, so it has to be the one that decides.
            ruleIds = (config.ruleIds + forcedRulesFor(config.mode))
                .filter { id -> LobbyRules.all.any { it.id == id } }
                .distinct(),
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
                // Dispatched *above* the teardown below, and that ordering is
                // the whole trick: the shop keeps a clock and an animation gate
                // of its own, and the three lines after this drop both.
                if (snapshot.interlude != null) return@withLock tickInterlude(snapshot, now)

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

                prompt.startsWith("outcome:") -> applyLocked(GameAction.ResolveOutcome)

                prompt.startsWith("forced:") -> applyLocked(GameAction.ForcedDraw)

                prompt.startsWith("respond:") -> {
                    // Bots let it stand, one per step. A bot that never counters
                    // is a perfectly good first bot — what it must not do is
                    // stay silent, because a window nobody answers holds the
                    // table until the clock runs out, and at a table of bots
                    // there is no clock at all.
                    val bot = snapshot.openResponse?.awaiting
                        ?.firstOrNull { snapshot.player(it)?.isBot == true }
                    if (bot != null) applyLocked(GameAction.PassResponse(bot)) else null
                }

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

    /**
     * The shop between rounds. Must be called with [mutex] held.
     *
     * Bots go first and all of them at once, because nobody watches a bot shop —
     * the one-per-tick rule elsewhere exists because the table *watches* a bot
     * play a card. Four of them are finished inside a second, so a window is
     * never held open by machinery.
     */
    private fun tickInterlude(snapshot: GameState, now: Long): List<GameEvent>? {
        val shop = snapshot.interlude ?: return null

        // Shut, and whatever it settled being watched. The bids are read, then
        // the money moves, then the round is dealt — three beats, because a
        // score changing while the bids are still turning over hands the table
        // the answer over the top of the question.
        if (shop.closed) {
            if (now < nextStepAt) return null
            if (snapshot.pendingOutcomes.isNotEmpty()) {
                val events = applyLocked(GameAction.ResolveOutcome)
                nextStepAt = now + AUCTION_SETTLE_MS
                return events
            }
            interludeUntil = null
            return applyLocked(GameAction.OpenRound)
        }

        val deadline = interludeUntil
        val everybodyDone = snapshot.waitingOnShop.isEmpty()
        if (everybodyDone || (deadline != null && now >= deadline)) {
            val events = applyLocked(GameAction.CloseInterlude)
            nextStepAt = now + if (snapshot.interlude?.lot != null) AUCTION_REVEAL_MS else 0L
            return events
        }

        if (now < nextStepAt) return null
        val events = mutableListOf<GameEvent>()
        for (botId in snapshot.waitingOnShop.filter { snapshot.player(it)?.isBot == true }) {
            val offerId = botShop(state, botId)
            events += if (offerId != null) {
                applyLocked(GameAction.Buy(botId, offerId))
            } else {
                applyLocked(GameAction.FinishShopping(botId, botBid(state, botId, rng)))
            }
        }
        nextStepAt = now + BOT_SHOP_STEP_MS
        return events.ifEmpty { null }
    }

    private fun timeoutNow(snapshot: GameState): List<GameEvent> {
        val actor = snapshot.openResponse?.awaiting?.firstOrNull()
            ?: snapshot.pendingAction?.playerId
            ?: snapshot.currentPlayer?.id
            ?: return emptyList()
        return applyLocked(GameAction.Timeout(actor))
    }

    /** A stable description of who the table is waiting on and why. */
    private fun promptOf(snapshot: GameState): String {
        // Ahead of everything, including a card that has already landed: a
        // counter can still stop the one that has not, and asking about it after
        // the fact would be asking about something that already happened.
        val window = snapshot.openResponse
        if (window != null) return "respond:${window.id}:${window.awaiting.size}"

        val pending = snapshot.pendingAction
        if (pending != null) return "pick:${pending.playerId}"
        // Ahead of the forced draws: a card that has landed goes off before
        // anything it might have queued behind it.
        val landed = snapshot.pendingOutcomes.firstOrNull()
        if (landed != null) return "outcome:${snapshot.pendingOutcomes.size}:${landed.cardDefId}:${landed.targetId}"
        val forced = snapshot.forcedDraws
        if (forced != null) return "forced:${forced.playerId}:${forced.remaining}"
        val dealing = snapshot.dealQueue.firstOrNull()
        if (dealing != null) return "deal:$dealing"
        return "turn:${snapshot.currentPlayer?.id ?: "none"}"
    }

    private fun stepDelayFor(prompt: String, snapshot: GameState): Long = when {
        prompt.startsWith("deal:") -> DEAL_STEP_MS
        prompt.startsWith("outcome:") -> OUTCOME_STEP_MS
        // A slots draw waits for the reels; every other forced draw is a flick.
        prompt.startsWith("forced:") && snapshot.forcedDraws?.source == SLOTS_SOURCE -> SLOTS_SPIN_MS
        prompt.startsWith("forced:") -> FORCED_DRAW_STEP_MS
        prompt.startsWith("pick:") -> BOT_PICK_MS
        prompt.startsWith("respond:") -> BOT_PICK_MS
        else -> BOT_THINK_MS
    }

    /** Only humans are on the clock; bots always act well inside it. */
    private fun deadlineFor(prompt: String, snapshot: GameState, now: Long): Long? {
        // A response window runs on a clock of its own, and a much shorter one.
        // A turn's thirty seconds is far too long to hold a whole table for a
        // counter most of them cannot play — and eight would be too few, because
        // somebody being asked may be reading two cards they have never seen
        // before deciding. The reveal's own time is handed back by [closeGate],
        // so these twelve seconds start after the card has been read.
        if (prompt.startsWith("respond:")) {
            val waiting = snapshot.openResponse?.awaiting.orEmpty()
            if (waiting.none { snapshot.player(it)?.isBot == false }) return null
            return now + RESPONSE_WINDOW_MS
        }

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
        // Not a judgement — a table that will not let this seat stop would
        // otherwise be offered a stay it refuses, once every think, for ever.
        if (!Engine.canStay(snapshot, bot)) return true
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
        // Whatever the card does, the seat with the most on the table is the one
        // worth pointing it at — plus whatever that seat pays for being pointed
        // at, which is the whole of why anybody attacks a discordia. Anyone else
        // is preferred to itself.
        val candidates = pending.validTargets.mapNotNull { snapshot.player(it) }
        val target = candidates.filter { it.id != botId }.maxByOrNull { it.handValue + tollFrom(it) }
            ?: candidates.firstOrNull()
            ?: return emptyList()
        // A coin has no smart call, so a bot simply calls one. It has to call
        // something: the card does not resolve without an answer. Asked of the
        // prompt rather than the card — the same card can ask a question the
        // first time it stops the table and nothing the second.
        val choice = rng.pick(pending.options)
        // A shop needs no thought — every offer on it is already one this bot
        // can afford, and taking the first would have every bot buy the same
        // card. Cards off the table do need some: see [botCardPicks].
        val cards = when (pending.kind) {
            PickKind.CARD -> botCardPicks(snapshot, botId, pending, rng)
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
        val names = pendingStack
        if (names.isEmpty()) return state
        pendingStack = emptyList()
        val rest = state.deck.toMutableList()
        val top = mutableListOf<com.letitride.engine.Card>()
        for (name in names) {
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
        val events = markFirstSight(result.events)
        markRoundBoundaries(before, result.state, events)
        record(before, events)
        openGate(action, before, events)
        return events
    }

    /**
     * Hands the batch to whoever is keeping score.
     *
     * Here rather than in `broadcast` because these are the room's own events,
     * before `redactFor` has cut them down for anybody: a gambler card drawn
     * into a hidden hand is a card *you* drew, and the version of that event
     * every other seat is sent has had the card taken out of it. Recording off
     * a redacted stream would give a player a history with holes in it exactly
     * where the interesting cards were.
     *
     * Nothing here can fail the transition. [StatsRecorder] takes the work and
     * returns, and a room with no recorder does not even build the tally.
     */
    private fun record(before: GameState, events: List<GameEvent>) {
        val recorder = recorder ?: return
        if (!tally.hasAccounts()) return

        // A game begins when the table leaves the lobby, which is also the one
        // moment "play again" is distinguishable from "more of the same game".
        if (before.phase == GamePhase.LOBBY && state.phase != GamePhase.LOBBY) {
            tally.begin(java.util.UUID.randomUUID().toString())
        }

        tally.absorb(events)

        // A round is written down as it is scored — see [RoomTally], which is
        // where the reasoning about walking out mid-game lives.
        for (event in events) {
            if (event is GameEvent.RoundScored) tally.closeRound(event).forEach(recorder::round)
        }

        if (before.phase != GamePhase.GAME_END && state.phase == GamePhase.GAME_END) {
            tally.finish(state).forEach(recorder::game)
        }
    }

    /**
     * Which gambler cards this table has seen before.
     *
     * Bookkeeping about presentation rather than about the game, so it lives
     * here and not in the engine — nothing about who wins depends on it, and a
     * room that forgot it would only be slower to watch.
     */
    private val seenGamblers = mutableSetOf<String>()

    /**
     * Stamps a gambler card's reveal with whether the table has seen it before.
     *
     * The server states the fact and the client owns the duration, which is the
     * division of labour everywhere else here — durations live in `useGame.ts`
     * and nowhere else. What the room is uniquely able to say is *this table has
     * not seen this card*, and that is the whole difference between a reveal
     * long enough to read and one long enough to recognise.
     *
     * Per table rather than per browser on purpose. The player of a card owns
     * its animation gate, and they are the one person who certainly knows what
     * it does — a client that only lengthened cards *it* had not seen would let
     * them wave it past before anybody else had finished reading.
     */
    private fun markFirstSight(events: List<GameEvent>): List<GameEvent> {
        if (events.none { it is GameEvent.GamblerPlayed }) return events
        return events.map { event ->
            if (event !is GameEvent.GamblerPlayed) return@map event
            val defId = event.card.defId ?: return@map event
            event.copy(firstSeen = seenGamblers.add(defId))
        }
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
            GameAction.ResolveOutcome -> before.pendingOutcomes.firstOrNull()?.playerId
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
            // Whatever ended the round, then the payout, and only then the card.
            val preamble = outroPreambleFor(events) + payoutWindowFor(after.players.size)
            roundOutroFrom = now + preamble
            roundOutroUntil = now + preamble + OUTRO_CARD_MS
            nextRoundAt = autoNextRoundAt(after, roundOutroUntil!!)
        }

        // The shop opening. Its clock starts here rather than when the round
        // ended: it opens *after* the scoreboard, so the two minutes are not
        // spent while people are still reading what the round paid.
        if (before.interlude == null && after.interlude != null) {
            interludeUntil = now + shopWindowFor(after.config)
            nextStepAt = now + BOT_SHOP_STEP_MS
            // The round's closing beats are over. Left set, the outro card would
            // hang over the shop the moment anything re-mounted the table.
            roundOutroFrom = null
            roundOutroUntil = null
            nextRoundAt = null
        }
        if (after.interlude == null) interludeUntil = null
    }

    private fun view(viewerId: String?): GameStateView = state.toView(
        viewerId,
        code,
        hostId,
        turnDeadline,
        roundIntroUntil,
        roundOutroFrom,
        roundOutroUntil,
        gate?.let { AnimationGateView(it.id, it.ackPlayerId, it.deadline) },
        nextRoundAt,
        interludeUntil,
        if (dev) DevMode.peek(state) else null,
        if (dev) state.players.associate { it.id to it.gamblers } else null,
    )

    /**
     * Sends a batch of events and the state they produced to every seat.
     *
     * One encode per connection rather than one for the room. That is a real
     * cost — five seats is five passes over the same few kilobytes — and it is
     * what a hidden hand costs: which faces you may see is a fact about you, and
     * there is no honest way to answer it once for everybody. The alternative,
     * a shared payload with a private patch bolted on afterwards, needs the
     * redaction written anyway and then adds a merge on top of it.
     */
    private suspend fun broadcast(events: List<GameEvent>) {
        for ((playerId, connection) in connections) {
            val message = ServerMessage.State(view(playerId), redactFor(events, playerId, state))
            connection.outbound.trySend(json.encodeToString(ServerMessage.serializer(), message))
        }
    }

    suspend fun sendStateTo(playerId: String) {
        val connection = connections[playerId] ?: return
        val message = mutex.withLock { ServerMessage.State(view(playerId), emptyList()) }
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

class RoomRegistry(
    private val json: Json,
    private val scope: CoroutineScope,
    /** Passed to every room it opens; null on a server keeping no history. */
    private val recorder: StatsRecorder? = null,
) {
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
    fun create(seed: Long? = null, stack: List<String> = emptyList(), dev: Boolean = false): Room {
        var code = generateCode()
        while (rooms.containsKey(code)) code = generateCode()
        val room = Room(code, seed ?: random.nextLong(), json, scope, stack, dev, recorder)
        rooms[code] = room
        return room
    }

    fun size(): Int = rooms.size

    private fun generateCode(): String =
        (1..4).map { ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)] }.joinToString("")
}

fun newPlayerId(): String = java.util.UUID.randomUUID().toString().take(12)
