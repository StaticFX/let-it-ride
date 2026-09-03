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

    @Serializable
    @SerialName("freeze")
    data class Freeze(val playerId: String) : GameEvent()

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

    @Serializable
    @SerialName("doubleOrNothing")
    data class DoubleOrNothing(val playerId: String, val won: Boolean) : GameEvent()

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

    @Serializable
    @SerialName("roundScored")
    data class RoundScored(val deltas: Map<String, Int>, val winnerId: String?) : GameEvent()
}
