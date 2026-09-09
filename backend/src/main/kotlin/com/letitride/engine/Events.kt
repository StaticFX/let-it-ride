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
     * Somebody drew a gambler card. Announced instead of [Draw], because this is
     * the one draw the table may not watch: everyone has to see that a card went
     * into a hidden hand — that is what keeps the count honest and the moment
     * readable — and nobody but its owner may see which.
     *
     * [card] is filled in only in the drawer's own copy of the batch; every
     * other client is sent the same event with it nulled out. See
     * `Room.redactFor`, which is the single place that happens.
     *
     * [kept] is false when there was no room for it and it went to the discard
     * pile instead. Refusing the draw outright would leave the card on top of
     * the deck for the next player to find, and the one after that.
     */
    @Serializable
    @SerialName("gamblerDrawn")
    data class GamblerDrawn(
        val playerId: String,
        val card: Card? = null,
        val kept: Boolean = true,
    ) : GameEvent()

    /**
     * A gambler card came out of a hidden hand and went face up. Public, and
     * completely so: playing one is how the table finds out what you were
     * carrying, and half the point of holding it was that they did not know.
     *
     * [firstSeen] is true the first time this card has been played at this
     * table, and is what the client turns into how long it holds the reveal for.
     * A card nobody has seen has to be *read* — name, and what it does — which
     * is four seconds of somebody's attention; the fifth nullify of the evening
     * needs a beat and no more. The room works it out (see `Room.markFirstSight`)
     * because the player of the card owns the animation gate, and they are the
     * one person who certainly already knows what it does.
     */
    @Serializable
    @SerialName("gamblerPlayed")
    data class GamblerPlayed(
        val playerId: String,
        val card: Card,
        val firstSeen: Boolean = false,
    ) : GameEvent()

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

    /**
     * One card crossed the table from [fromPlayerId] to [toPlayerId].
     *
     * Named for the card that first sent one, and it now covers every way a
     * single card changes seats — a steal takes one at random, a circlejerk
     * hands one over, a reverse circlejerk asks for one. Who chose it is the
     * difference between those cards and no difference at all to the table
     * watching it fly, which is what this event is for.
     */
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

    /**
     * Second chance consumed: the duplicate was discarded instead of busting.
     *
     * [card] is the duplicate that would have ended the round and [matched] is
     * what it collided with, the same pair [Bust] names. [saver] is the card
     * that was spent to stop it — it has already left the modifier row by the
     * time this goes out, and the state that travels with it is the state
     * *after*, so a client that wanted to show what saved somebody could not
     * find it anywhere. A card that dies for you is worth watching die; sending
     * it is what lets the table watch.
     */
    @Serializable
    @SerialName("secondChance")
    data class SecondChance(
        val playerId: String,
        val card: Card,
        val matched: Card? = null,
        val saver: Card? = null,
    ) : GameEvent()

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
     * A gambler card was stopped by another one. Public — both cards are face up
     * by the time this happens, which is the price of playing either.
     *
     * [returned] is the difference between the two cards that do it: a nullified
     * card is spent, and one stopped by a "nahhh" goes back to the hand it came
     * out of and can be played again.
     */
    @Serializable
    @SerialName("gamblerCountered")
    data class GamblerCountered(
        val playerId: String,
        val card: Card,
        val returned: Boolean = false,
    ) : GameEvent()

    /** A gambler card came back to the hand that played it — see "nahhh". */
    @Serializable
    @SerialName("gamblerReturned")
    data class GamblerReturned(val playerId: String, val card: Card) : GameEvent()

    /**
     * A gambler card aimed at somebody was turned round on the player who threw
     * it — see "deflect". [toPlayerId] is where it is pointing now.
     */
    @Serializable
    @SerialName("gamblerDeflected")
    data class GamblerDeflected(
        val playerId: String,
        val toPlayerId: String,
        val card: Card,
    ) : GameEvent()

    /**
     * A card was drawn and handed straight on to somebody else — see the
     * "redirect" gambler card. The card is public: the table watched it come off
     * the deck before anybody decided whose it was.
     */
    @Serializable
    @SerialName("redirected")
    data class Redirected(
        val fromPlayerId: String,
        val toPlayerId: String,
        val card: Card,
    ) : GameEvent()

    /**
     * Somebody shuffled the deck on purpose, rather than it running dry and
     * being folded back together. Told apart from [DeckReshuffled] because they
     * mean opposite things to whoever was counting cards: one is the pile you
     * were reading being scrambled, the other is it growing back.
     */
    @Serializable
    @SerialName("deckShuffled")
    data class DeckShuffled(val cards: Int) : GameEvent()

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

    /** Gambler cards went back to the dealer for a share of their price. */
    @Serializable
    @SerialName("soldBack")
    data class SoldBack(val playerId: String, val cards: List<Card>, val points: Int) : GameEvent()

    /** A tithe collected off everybody else's round — see "taxes". */
    @Serializable
    @SerialName("taxed")
    data class Taxed(val playerId: String, val points: Int) : GameEvent()

    /** A loan came due — see "loan". */
    @Serializable
    @SerialName("loanRepaid")
    data class LoanRepaid(val playerId: String, val points: Int) : GameEvent()

    /** Somebody who was out of the round is back in it — see "revive". */
    @Serializable
    @SerialName("revived")
    data class Revived(val playerId: String) : GameEvent()

    /** The shop opened between rounds, and what is on the block — rolling rules. */
    @Serializable
    @SerialName("shopOpened")
    data class ShopOpened(val lot: Card? = null) : GameEvent()

    /**
     * Every sealed bid turned over at once, and what the lot went for.
     *
     * The same shape [AllIn] has and for the same reason: the moment *is* the
     * card. [winnerId] is null when nobody wanted it, and [rigged] names anybody
     * whose "rigged bid" fired after the close, in the order they fired.
     */
    @Serializable
    @SerialName("auction")
    data class AuctionClosed(
        val lot: Card,
        val bids: Map<String, Int> = emptyMap(),
        val winnerId: String? = null,
        val price: Int = 0,
        val rigged: List<String> = emptyList(),
    ) : GameEvent()

    /**
     * [playerId] bought [card] and is [price] the poorer for it.
     *
     * [hidden] is set for a card that goes into a hand nobody may see. The buyer
     * and the price stay public — a score that moves has to be accountable —
     * and the face is cut out for everybody but the buyer by the same
     * projection that hides the rest of the tray. An older server never sets it,
     * which is the in-round shop, whose purchases were always face up.
     */
    @Serializable
    @SerialName("bought")
    data class Bought(
        val playerId: String,
        val card: Card,
        val price: Int,
        val hidden: Boolean = false,
    ) : GameEvent()

    @Serializable
    @SerialName("roundScored")
    data class RoundScored(val deltas: Map<String, Int>, val winnerId: String?) : GameEvent()
}
