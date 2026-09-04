package com.letitride.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the engine did during one transition, in order. The client
 * animates from these instead of diffing state, so it can show the card that
 * was actually drawn without the server ever revealing the rest of the deck.
 */
@Serializable
sealed class GameEvent {
    @Serializable
    @SerialName("draw")
    data class Draw(val playerId: String, val card: Card) : GameEvent()

    @Serializable
    @SerialName("passive")
    data class PassiveGained(val playerId: String, val card: Card) : GameEvent()

    /**
     * [card] is what tipped them over and [matched] is the card already in hand
     * it collided with, so the table can point at the pair rather than just
     * announcing a bust.
     */
    @Serializable
    @SerialName("bust")
    data class Bust(
        val playerId: String,
        val reason: String,
        val card: Card? = null,
        val matched: Card? = null,
    ) : GameEvent()

    @Serializable
    @SerialName("stay")
    data class Stay(val playerId: String) : GameEvent()

    @Serializable
    @SerialName("skip")
    data class Skip(val playerId: String) : GameEvent()

    @Serializable
    @SerialName("discard")
    data class Discard(val playerId: String, val card: Card) : GameEvent()

    @Serializable
    @SerialName("steal")
    data class Steal(val fromPlayerId: String, val toPlayerId: String, val card: Card) : GameEvent()

    @Serializable
    @SerialName("swap")
    data class Swap(val fromPlayerId: String, val toPlayerId: String) : GameEvent()

    /**
     * Two cards changed hands. [firstCard] went from [firstPlayerId] to
     * [secondPlayerId] and [secondCard] came the other way, so the table can
     * fly them past each other rather than announcing the result.
     */
    @Serializable
    @SerialName("cardsSwapped")
    data class CardsSwapped(
        val firstPlayerId: String,
        val firstCard: Card,
        val secondPlayerId: String,
        val secondCard: Card,
    ) : GameEvent()

    @Serializable
    @SerialName("freeze")
    data class Freeze(val playerId: String) : GameEvent()

    /**
     * [points] moved from [fromPlayerId] to [toPlayerId] in the middle of a
     * round — a toll, rather than anything the hands did. Both halves are
     * already in the round's adjustments by the time this goes out; this is the
     * announcement, so the table can watch the points cross it.
     */
    @Serializable
    @SerialName("pointsTransferred")
    data class PointsTransferred(
        val fromPlayerId: String,
        val toPlayerId: String,
        val points: Int,
    ) : GameEvent()

    @Serializable
    @SerialName("actionPlayed")
    data class ActionPlayed(
        val cardDefId: String,
        val fromPlayerId: String,
        val targetPlayerId: String,
    ) : GameEvent()

    /** Second chance consumed: the duplicate was discarded instead of busting. */
    @Serializable
    @SerialName("secondChance")
    data class SecondChance(val playerId: String, val card: Card, val matched: Card? = null) : GameEvent()

    /** An action card was drawn that nobody at the table could be hit with. */
    @Serializable
    @SerialName("fizzled")
    data class Fizzled(val cardDefId: String, val playerId: String) : GameEvent()

    /** A surplus second chance was handed to a player who did not have one. */
    @Serializable
    @SerialName("secondChancePassed")
    data class SecondChancePassed(val fromPlayerId: String, val toPlayerId: String) : GameEvent()

    @Serializable
    @SerialName("flip7")
    data class Flip7(val playerId: String) : GameEvent()

    /**
     * The coin was called and thrown. [call] is what the player said, [result]
     * is the face it landed on — both travel together so the coin can land on
     * the announced face instead of the client guessing from the outcome.
     * They match exactly when the player won.
     */
    @Serializable
    @SerialName("coinFlip")
    data class CoinFlip(val playerId: String, val call: String, val result: String) : GameEvent()

    /**
     * Assassination's bottle stopped on [victimId]. The server spins it — four
     * clients rolling their own would each show a different bottle — and the
     * bust event that follows is the same one every other bust sends.
     */
    @Serializable
    @SerialName("bottleSpin")
    data class BottleSpin(val victimId: String) : GameEvent()

    /**
     * Every hand in [playerIds] moved one seat. The list is in seat order and
     * only holds the seats that took part; [direction] is "left" or "right",
     * and for "right" each player's hand went to the next id in the list
     * (wrapping), for "left" to the previous one.
     */
    @Serializable
    @SerialName("tableSpun")
    data class TableSpun(val direction: String, val playerIds: List<String>) : GameEvent()

    /**
     * [card] is the card the spin is about to produce. It is announced up front
     * so the reels can land on it and the machine can be gone before the card
     * is actually dealt — otherwise the card arrives mid-animation.
     */
    @Serializable
    @SerialName("slots")
    data class Slots(val playerId: String, val card: Card? = null) : GameEvent()

    @Serializable
    @SerialName("timeout")
    data class Timeout(val playerId: String) : GameEvent()

    @Serializable
    @SerialName("deckReshuffled")
    data class DeckReshuffled(val cards: Int) : GameEvent()

    /**
     * The "bounty" house rule paid out: [bustedPlayerId] went into the round in
     * front and busted, so every id in [collectorIds] collects [points]. Sent
     * ahead of [RoundScored], whose deltas already include the payout, so the
     * table can make a moment of it before the scoreboard appears.
     */
    @Serializable
    @SerialName("bounty")
    data class BountyPaid(
        val bustedPlayerId: String,
        val collectorIds: List<String>,
        val points: Int,
    ) : GameEvent()

    /**
     * "Anti flip": [playerId] gave up their flip bonus to take [points] off
     * [targetPlayerId] instead. Both sides of it land in the round's deltas, so
     * this is only the announcement.
     */
    @Serializable
    @SerialName("antiFlip")
    data class AntiFlip(
        val playerId: String,
        val targetPlayerId: String,
        val points: Int,
    ) : GameEvent()

    /**
     * "Comeback": both throws at once, because neither could see the other's
     * until now. [challengerWon] settles it — a draw is neither.
     */
    @Serializable
    @SerialName("throws")
    data class Throws(
        val challengerId: String,
        val challengerThrow: String,
        val leaderId: String,
        val leaderThrow: String,
        val challengerWon: Boolean,
    ) : GameEvent()

    /** Two players' banked scores changed places. */
    @Serializable
    @SerialName("scoresSwapped")
    data class ScoresSwapped(
        val firstPlayerId: String,
        val firstScore: Int,
        val secondPlayerId: String,
        val secondScore: Int,
    ) : GameEvent()

    /**
     * "All in": every bet turned face up at once, and [halvedIds] bet the
     * highest or the lowest of them.
     */
    @Serializable
    @SerialName("allIn")
    data class AllIn(val bets: Map<String, Card>, val halvedIds: List<String>) : GameEvent()

    /** [playerId] bought [card] and the round is [price] the poorer for it. */
    @Serializable
    @SerialName("bought")
    data class Bought(val playerId: String, val card: Card, val price: Int) : GameEvent()

    @Serializable
    @SerialName("roundScored")
    data class RoundScored(val deltas: Map<String, Int>, val winnerId: String?) : GameEvent()
}
