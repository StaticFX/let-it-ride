package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.Catalog
import com.letitride.engine.DeckConfig
import com.letitride.engine.GameState
import com.letitride.engine.Player
import com.letitride.engine.PlayerStatus
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * The local testing mode: write a state onto the table, and say which cards come
 * off the deck next.
 *
 * The engine is the only thing that decides anything during a game, which is
 * exactly what makes a particular situation hard to reach by playing — a bust on
 * the next card, a hand one short of the flip, a table on match point. Everything
 * here writes that situation down instead, so the round starts where the thing
 * being looked at actually happens.
 *
 * Gated behind [TEST_HOOKS_ENV] alongside the seed and the pacing, and for the
 * same reason: a server that let a client deal itself a card would not be a game.
 */

/**
 * One seat's state, as a patch. Every field is optional and only the ones that
 * arrive are written, so a panel that only moves a score does not have to send
 * back a hand it never touched.
 *
 * The seat is named by [playerId], or by [seat] for a client working from a
 * screenshot rather than from ids; failing both, by where it sits in the list
 * that was sent.
 */
@Serializable
data class DevPlayerPatch(
    val playerId: String? = null,
    val seat: Int? = null,
    val name: String? = null,
    val score: Int? = null,
    val status: PlayerStatus? = null,
    /** The whole hand, by card name — see [DevMode.take]. Replaces what is held. */
    val hand: List<String>? = null,
    /**
     * The modifier row, same naming — and the effect cards are in it too, so a
     * seat can be handed a "bomber" or a "discordia" the same way it is handed
     * a "plus4".
     */
    val passives: List<String>? = null,
    val skipNextTurn: Boolean? = null,
)

/**
 * Everything one dev command can change. Applied in one go so the table is never
 * broadcast half-written — a hand set up for a bust and the card that busts it
 * land on the client together.
 */
@Serializable
data class DevSetup(
    /**
     * The next cards off the deck, in the order they will be drawn. Each is
     * lifted out of the deck where the deck holds one, so the deck stays the
     * deck; a card the table is not playing with is minted instead — see
     * [DevMode.take].
     *
     * Sent before the game starts, it is held and applied to the shuffle.
     */
    val stack: List<String>? = null,
    val players: List<DevPlayerPatch> = emptyList(),
    val round: Int? = null,
    /** Hands the turn to a seat, whoever the engine had it on. */
    val turnPlayerId: String? = null,
    /** Drops whatever the table is stopped on — a prompt, a run of forced draws. */
    val clearPrompt: Boolean = false,
    /** Everybody still in goes out, and the engine scores the round as it stands. */
    val endRound: Boolean = false,
    /** Cuts short the title card, the closing card and any animation being waited on. */
    val skipWait: Boolean = false,
)

object DevMode {
    /** How much of the deck a dev client is shown. Enough to see a stack land. */
    const val PEEK = 12

    /** Ids for cards that were never in the deck. See [take]. */
    private val minted = AtomicLong()

    /**
     * Writes [setup] onto [state]. Pure: the room decides what to do about its
     * own clocks afterwards.
     */
    fun apply(state: GameState, setup: DevSetup): GameState {
        val pile = Pile(state)
        var next = state

        // A prompt is a card being held out mid-play, so dropping one has to put
        // the card somewhere — otherwise the deck quietly loses it.
        if (setup.clearPrompt || setup.endRound) {
            next.pendingAction?.let { pile.putBack(it.card) }
            next = next.copy(pendingAction = null, forcedDraws = null, forcedDrawStack = emptyList())
        }

        for ((index, patch) in setup.players.withIndex()) {
            val target = locate(next, patch, index) ?: continue
            next = next.copy(
                players = next.players.map { if (it.id == target.id) patched(it, patch, pile) else it },
            )
        }

        setup.stack?.let { names -> pile.putOnTop(names.mapNotNull { take(it, pile) }) }

        if (setup.endRound) {
            next = next.copy(
                players = next.players.map {
                    if (it.status == PlayerStatus.ACTIVE) it.copy(status = PlayerStatus.STAYED) else it
                },
                dealQueue = emptyList(),
            )
        }

        setup.round?.let { next = next.copy(round = it.coerceAtLeast(1)) }
        setup.turnPlayerId?.let { id ->
            val seat = next.players.indexOfFirst { it.id == id }
            if (seat >= 0) next = next.copy(turnIndex = seat)
        }

        return next.copy(deck = pile.deck, discard = pile.discard)
    }

    /** Puts [names] on top of the deck, in draw order, without touching anything else. */
    fun stack(state: GameState, names: List<String>): GameState = apply(state, DevSetup(stack = names))

    /** What a dev client is shown of the deck: the next few cards, in order. */
    fun peek(state: GameState): List<Card> = state.deck.take(PEEK)

    // ─── Seats ───

    private fun locate(state: GameState, patch: DevPlayerPatch, index: Int): Player? {
        patch.playerId?.let { id -> return state.player(id) }
        patch.seat?.let { return state.players.getOrNull(it) }
        return state.players.getOrNull(index)
    }

    private fun patched(player: Player, patch: DevPlayerPatch, pile: Pile): Player {
        var next = player

        patch.name?.let { next = next.copy(name = it.take(16)) }
        patch.score?.let { next = next.copy(score = it) }
        patch.skipNextTurn?.let { next = next.copy(skipNextTurn = it) }

        patch.hand?.let { names ->
            next.hand.forEach { pile.putBack(it) }
            val hand = names.mapNotNull { take(it, pile) }
            next = next.copy(hand = hand, handValue = hand.sumOf { it.value })
        }

        patch.passives?.let { names ->
            next.passives.forEach { pile.putBack(it) }
            next = next.copy(passives = names.mapNotNull { take(it, pile) })
        }

        // Last, so a hand written onto a busted seat does not silently revive it
        // and a status written alongside one still wins.
        patch.status?.let {
            next = next.copy(status = it, bustReason = if (it == PlayerStatus.BUST) "dev" else null)
        }

        return next
    }

    // ─── Cards by name ───

    /**
     * The card [name] asks for: an action or passive definition id ("freeze",
     * "plus4"), or what is printed on a number card ("7", "K").
     *
     * Taken out of the deck where the deck holds one, then out of the discard
     * pile — so putting a card somewhere moves it rather than conjuring it, and
     * every count the game keeps still adds up. Only when the table genuinely
     * has no such card is one minted, which is what lets a deck be tested
     * against a card it does not contain. A minted card is ephemeral: it can be
     * drawn, played and scored, and it is dropped at the end of the round
     * instead of joining the discard pile, so the deck never grows.
     */
    private fun take(name: String, pile: Pile): Card? =
        pile.take(name) ?: mint(name, pile.deckConfig)

    private fun mint(name: String, deck: DeckConfig): Card? {
        val id = "tmp-dev-${minted.incrementAndGet()}"

        Catalog.action(name)?.let {
            return Card(id = id, kind = CardKind.ACTION, label = it.name, value = 0, defId = it.id)
        }
        Catalog.passive(name)?.let {
            return Card(id = id, kind = CardKind.PASSIVE, label = it.name, value = 0, defId = it.id)
        }

        // A number the deck prints keeps the deck's own face — the value behind
        // a "K" is the table's business, not the caller's.
        val entry = deck.numberCards.firstOrNull { (it.label ?: it.value.toString()) == name }
        if (entry != null) {
            return Card(
                id = id,
                kind = CardKind.NUMBER,
                label = name,
                value = entry.value,
                suit = entry.suits?.firstOrNull(),
            )
        }

        val value = name.toIntOrNull() ?: return null
        return Card(id = id, kind = CardKind.NUMBER, label = name, value = value)
    }

    /**
     * The deck and the discard pile, open for the length of one command.
     *
     * Cards move between the two constantly while a setup is applied — a hand
     * being replaced sends its cards back, the replacements come out of the deck
     * — and doing that on an immutable state a card at a time reads as nothing
     * but copies.
     */
    private class Pile(state: GameState) {
        val deck = state.deck.toMutableList()
        val discard = state.discard.toMutableList()
        val deckConfig: DeckConfig = state.config.deck

        fun take(name: String): Card? = lift(deck, name) ?: lift(discard, name)

        fun putBack(card: Card) {
            if (!card.isEphemeral) discard += card
        }

        fun putOnTop(cards: List<Card>) {
            deck.addAll(0, cards)
        }

        private fun lift(pile: MutableList<Card>, name: String): Card? {
            val index = pile.indexOfFirst { it.defId == name || it.label == name }
            return if (index >= 0) pile.removeAt(index) else null
        }
    }
}
