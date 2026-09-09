package com.letitride.server

import com.letitride.engine.ANTIMATTER
import com.letitride.engine.ALL_IN_ID
import com.letitride.engine.CIRCLEJERK_ID
import com.letitride.engine.DISCORDIA
import com.letitride.engine.PHASE_GIVE
import com.letitride.engine.PHASE_HANDOVER
import com.letitride.engine.REVERSE_CIRCLEJERK_ID
import com.letitride.engine.DOUBLE_POINTS
import com.letitride.engine.GameState
import com.letitride.engine.PLUS_TEN
import com.letitride.engine.PendingAction
import com.letitride.engine.PickKind
import com.letitride.engine.Player
import com.letitride.engine.SWAP_CARDS
import com.letitride.engine.action
import com.letitride.engine.config
import com.letitride.engine.num
import com.letitride.engine.passive
import com.letitride.engine.startedAndDealt
import com.letitride.engine.testRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a bot does with a card that points at cards.
 *
 * Nothing here decides a rule — the engine takes the pick and keeps it legal
 * whatever arrives. This is only about which of the legal picks a bot makes,
 * and the answer used to be "whichever one the shuffle put on top", which meant
 * a bot holding a card nobody wants held it until the round ended.
 */
class BotTest {

    private fun table(
        hands: Map<String, List<com.letitride.engine.Card>>,
        passives: Map<String, List<com.letitride.engine.Card>> = emptyMap(),
        players: List<String> = listOf("bot", "them"),
    ): GameState {
        val dealt = startedAndDealt(config = config(), players = players)
        return dealt.copy(
            players = dealt.players.map { p ->
                val hand = hands[p.id] ?: emptyList()
                p.copy(
                    hand = hand,
                    handValue = hand.sumOf { it.value },
                    passives = passives[p.id] ?: emptyList(),
                )
            },
        )
    }

    private fun swapPrompt(state: GameState) = PendingAction(
        cardDefId = SWAP_CARDS.id,
        playerId = "bot",
        card = action(SWAP_CARDS.id),
        validTargets = listOf("bot"),
        kind = PickKind.CARD,
        validCards = state.players.flatMap { p -> (p.hand + p.passives).map { it.id } },
        picks = 2,
    )

    @Test
    fun `a bot leads with the card it would rather not be holding`() {
        val state = table(
            hands = mapOf("bot" to listOf(num(9, id = "bot-9")), "them" to listOf(num(4, id = "them-4"))),
            passives = mapOf("bot" to listOf(passive(DISCORDIA.id, id = "the-discordia"))),
        )

        val picks = botCardPicks(state, "bot", swapPrompt(state), testRng())

        assertEquals("the-discordia", picks.first(), "it has something worth being rid of")
    }

    @Test
    fun `an antimatter goes the same way`() {
        val state = table(
            hands = mapOf("bot" to listOf(num(9, id = "bot-9")), "them" to listOf(num(4, id = "them-4"))),
            passives = mapOf("bot" to listOf(passive(ANTIMATTER.id, id = "the-antimatter"))),
        )

        val picks = botCardPicks(state, "bot", swapPrompt(state), testRng())

        assertEquals("the-antimatter", picks.first())
    }

    @Test
    fun `and takes the best thing on the other side of the table`() {
        val state = table(
            hands = mapOf("bot" to listOf(num(2, id = "bot-2")), "them" to listOf(num(11, id = "them-11"))),
            passives = mapOf("them" to listOf(passive(PLUS_TEN.id, id = "their-plus"))),
        )

        val picks = botCardPicks(state, "bot", swapPrompt(state), testRng())

        assertEquals("bot-2", picks.first(), "the least it is holding")
        assertEquals("them-11", picks[1], "the most they are")
    }

    @Test
    fun `it will not take a card that would bust it on arrival`() {
        val state = table(
            hands = mapOf(
                "bot" to listOf(num(3, id = "bot-3"), num(13, id = "bot-13")),
                "them" to listOf(num(13, id = "them-13"), num(7, id = "them-7")),
            ),
        )

        val picks = botCardPicks(state, "bot", swapPrompt(state), testRng())

        assertEquals("them-7", picks[1], "their 13 would land on the 13 it is already holding")
    }

    @Test
    fun `a bet comes from the middle of the hand, not the ends`() {
        // "All in" halves the round for whoever bet the highest and the lowest,
        // so the one thing a bot should not do is bet either.
        val hand = listOf(num(2, id = "bot-2"), num(7, id = "bot-7"), num(12, id = "bot-12"))
        val state = table(hands = mapOf("bot" to hand, "them" to listOf(num(5, id = "them-5"))))
        val pending = PendingAction(
            cardDefId = ALL_IN_ID,
            playerId = "bot",
            card = action(ALL_IN_ID),
            validTargets = listOf("bot"),
            kind = PickKind.CARD,
            validCards = hand.map { it.id } + "them-5",
            responders = listOf("bot", "them"),
        )

        val picks = botCardPicks(state, "bot", pending, testRng())

        assertEquals(listOf("bot-7"), picks)
    }

    @Test
    fun `a card being given away is the worst one it is holding, not the middle`() {
        // Both circlejerks ask for cards that *leave*, which is the opposite of
        // a bet: the bot's own hand is the only thing on offer, and the way to
        // play it is to be rid of the thing it least wants.
        val hand = listOf(num(2, id = "bot-2"), num(7, id = "bot-7"), num(12, id = "bot-12"))
        val state = table(
            hands = mapOf("bot" to hand, "them" to listOf(num(5, id = "them-5"))),
            passives = mapOf("bot" to listOf(passive(DISCORDIA.id, id = "the-discordia"))),
        )
        val pending = PendingAction(
            cardDefId = CIRCLEJERK_ID,
            playerId = "bot",
            card = action(CIRCLEJERK_ID),
            validTargets = listOf("bot"),
            kind = PickKind.CARD,
            validCards = hand.map { it.id } + "the-discordia",
            picks = 2,
            oneCardPerSeat = false,
            phase = PHASE_GIVE,
        )

        val picks = botCardPicks(state, "bot", pending, testRng())

        assertEquals("the-discordia", picks[0], "somebody else's problem now")
        assertEquals("bot-2", picks[1], "and then the least it is worth losing")
    }

    @Test
    fun `and so is one it is being asked to hand over`() {
        val hand = listOf(num(3, id = "bot-3"), num(11, id = "bot-11"))
        val state = table(hands = mapOf("bot" to hand, "them" to listOf(num(5, id = "them-5"))))
        val pending = PendingAction(
            cardDefId = REVERSE_CIRCLEJERK_ID,
            playerId = "them",
            card = action(REVERSE_CIRCLEJERK_ID),
            validTargets = listOf("them"),
            kind = PickKind.CARD,
            validCards = hand.map { it.id },
            responders = listOf("bot"),
            phase = PHASE_HANDOVER,
        )

        val picks = botCardPicks(state, "bot", pending, testRng())

        assertEquals("bot-3", picks.first(), "you asked, so you get the cheap one")
    }

    // ─── What a seat is worth attacking ───

    @Test
    fun `a seat carrying a discordia is worth something to aim at`() {
        val holder = Player(id = "a", name = "a", passives = listOf(passive(DISCORDIA.id)))
        val plain = Player(id = "b", name = "b", passives = listOf(passive(DOUBLE_POINTS.id)))

        assertTrue(tollFrom(holder) > 0, "it pays whoever plays a card on it")
        assertEquals(0, tollFrom(plain))
    }

    @Test
    fun `a card nobody wants is worth less than nothing to a bot`() {
        assertTrue(cardWorth(passive(DISCORDIA.id)) < 0)
        assertTrue(cardWorth(passive(ANTIMATTER.id)) < 0)
        assertTrue(cardWorth(passive(PLUS_TEN.id)) > 0)
        assertEquals(13, cardWorth(num(13)))
    }
}
