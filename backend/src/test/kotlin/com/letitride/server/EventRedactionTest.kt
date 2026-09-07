package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.GameEvent
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The guard on the one thing this game keeps secret.
 *
 * A gambler hand is hidden by [Room.redactFor] cutting the face out of the
 * event that announces it. That works, and it will keep working right up until
 * somebody adds a second event carrying a card somebody else should not see —
 * at which point nothing fails, nothing warns, and the cards are simply on the
 * wire. A leak has no symptom.
 *
 * So the rule is written down instead of remembered: every event that carries a
 * [Card] is either named below as public, or is handled by the redactor. Adding
 * one and doing neither fails here, in a test whose message says what to do.
 */
class EventRedactionTest {
    /**
     * Events that carry a card everybody is entitled to see. Which is nearly all
     * of them, on purpose: the table watches what happens and replays it as
     * animation, and an event nobody may see is an event nobody can animate.
     */
    private val public = setOf(
        "Draw", "PassiveGained", "Bust", "Discard", "Steal", "CardsSwapped",
        "SecondChance", "Slots", "Bought",
        // Playing a gambler card is exactly how the table finds out what you
        // were carrying. Half the point of holding it was that they did not.
        "GamblerPlayed",
        // The table watched this one come off the deck before anybody decided
        // whose it was — that is what makes handing it on a decision.
        "Redirected",
        // All three of these are about a card that is already face up: it was
        // turned over when it was played, which is the price of playing one.
        // A card going *home* is going back somewhere nobody can see, but the
        // table has already read it and pretending otherwise would only make
        // the count on the seat inexplicable.
        "GamblerCountered", "GamblerReturned", "GamblerDeflected",
        // Selling is information you paid for by giving the cards up. A hidden
        // sale would be a score jump nobody at the table could account for.
        "SoldBack",
        // The lot, and the bids once the hammer has fallen. An auction nobody
        // can see the lot in is not an auction, and the bids are secret only
        // until they are read — after which they are the whole point.
        "ShopOpened", "AuctionClosed",
    )

    /** Events the redactor cuts down before they go out. Keep in step with `Room.redactFor`. */
    private val redacted = setOf("GamblerDrawn")

    @Test
    fun `every event carrying a card is either public or redacted`() {
        val carriers = GameEvent::class.sealedSubclasses.filter { subclass ->
            subclass.declaredMemberProperties.any { property ->
                val type = property.returnType
                val classifier = type.classifier
                when {
                    classifier == Card::class -> true
                    // A list of cards, which is the same problem in bulk.
                    classifier is kotlin.reflect.KClass<*> && classifier.isSubclassOf(Collection::class) ->
                        type.arguments.any { it.type?.classifier == Card::class }

                    else -> false
                }
            }
        }.mapNotNull { it.simpleName }

        // A reflection test that finds nothing passes without proving anything,
        // which is the one way this guard could quietly stop guarding.
        assertTrue(
            carriers.size >= public.size,
            "found only ${carriers.size} events carrying a card, which is fewer than are listed as " +
                "public — the reflection above has stopped seeing them",
        )

        val unaccounted = carriers.filterNot { it in public || it in redacted }
        assertTrue(
            unaccounted.isEmpty(),
            "these events carry a card and nobody has said who may see it: $unaccounted. " +
                "Either add it to `public` here, or cut the card out of it in `Room.redactFor` " +
                "and add it to `redacted`.",
        )
    }

    /** ...and the other way round, so the lists above cannot rot into fiction. */
    @Test
    fun `nothing is listed that does not exist`() {
        val names = GameEvent::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val stale = (public + redacted) - names
        assertTrue(stale.isEmpty(), "these events are listed here but no longer exist: $stale")
    }
}
