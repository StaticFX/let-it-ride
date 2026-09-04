package com.letitride.server

import com.letitride.appJson
import com.letitride.engine.CardKind
import com.letitride.engine.DeckPresets
import com.letitride.engine.GamePhase
import com.letitride.engine.GameState
import com.letitride.engine.PendingAction
import com.letitride.engine.PlayerStatus
import com.letitride.engine.action
import com.letitride.engine.allCardIds
import com.letitride.engine.config
import com.letitride.engine.started
import com.letitride.engine.startedAndDealt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The testing mode writes a situation down instead of playing towards it. Two
 * things have to hold for that to be worth anything: the table it produces is
 * one the engine could have reached on its own — no card invented, none lost —
 * and none of it exists on a server that was not asked for it.
 */
class DevModeTest {

    private fun table(): GameState = started(config(deck = DeckPresets.PURE.deck), listOf("a", "b"))

    // ─── Which cards come next ───

    @Test
    fun `a stack deals in the order it was written`() {
        val after = DevMode.apply(table(), DevSetup(stack = listOf("7", "3", "12")))
        assertEquals(listOf("7", "3", "12"), after.deck.take(3).map { it.label })
    }

    @Test
    fun `a stacked card is moved rather than conjured`() {
        val before = table()
        val after = DevMode.apply(before, DevSetup(stack = listOf("7", "7", "13")))

        assertEquals(before.deck.size, after.deck.size, "the deck is still the deck")
        assertEquals(
            before.allCardIds().sorted(),
            after.allCardIds().sorted(),
            "every card that existed still exists, and no new one does",
        )
        assertEquals(listOf("7", "7", "13"), after.deck.take(3).map { it.label })
    }

    @Test
    fun `a card the table is not playing with is minted, and stays out of the deck`() {
        // Pure is numbers only, so there is no freeze anywhere to lift.
        val after = DevMode.apply(table(), DevSetup(stack = listOf("freeze")))
        val top = after.deck.first()

        assertEquals(CardKind.ACTION, top.kind)
        assertEquals("freeze", top.defId)
        assertTrue(top.isEphemeral, "a card that was never in the deck must not be able to join it")
    }

    @Test
    fun `an unknown name is skipped rather than dealt`() {
        val after = DevMode.apply(table(), DevSetup(stack = listOf("banana", "7")))
        assertEquals("7", after.deck.first().label)
    }

    @Test
    fun `the peek is the top of the deck, in draw order`() {
        val state = DevMode.apply(table(), DevSetup(stack = listOf("7", "3")))
        assertEquals(state.deck.take(DevMode.PEEK), DevMode.peek(state))
        assertEquals(listOf("7", "3"), DevMode.peek(state).take(2).map { it.label })
    }

    // ─── Writing a table down ───

    @Test
    fun `a hand written onto a seat replaces what was held, and the old cards go back`() {
        val before = startedAndDealt(config(deck = DeckPresets.PURE.deck), listOf("a", "b"))
        val held = before.player("a")!!.hand.single()

        val after = DevMode.apply(
            before,
            DevSetup(players = listOf(DevPlayerPatch(playerId = "a", hand = listOf("3", "4")))),
        )

        assertEquals(listOf("3", "4"), after.player("a")!!.hand.map { it.label })
        assertEquals(7, after.player("a")!!.handValue, "the hand's value is recounted, never carried over")
        assertTrue(after.discard.any { it.id == held.id }, "what the seat was holding is back on the pile")
        assertEquals(
            before.allCardIds().sorted(),
            after.allCardIds().sorted(),
            "moving cards around must not create or destroy any",
        )
    }

    @Test
    fun `a seat can be named by where it sits, or by nothing at all`() {
        val bySeat = DevMode.apply(table(), DevSetup(players = listOf(DevPlayerPatch(seat = 1, score = 40))))
        assertEquals(40, bySeat.player("b")!!.score)

        // No name and no seat: the patches line up with the table in order.
        val byOrder = DevMode.apply(
            table(),
            DevSetup(players = listOf(DevPlayerPatch(score = 11), DevPlayerPatch(score = 22))),
        )
        assertEquals(11, byOrder.player("a")!!.score)
        assertEquals(22, byOrder.player("b")!!.score)
    }

    @Test
    fun `an effect card can be written onto a seat like any other card`() {
        val after = DevMode.apply(
            table(),
            DevSetup(players = listOf(DevPlayerPatch(playerId = "a", passives = listOf("bomber", "nonsense")))),
        )
        assertEquals(listOf("bomber"), after.player("a")!!.passives.map { it.defId })
    }

    @Test
    fun `clearing the prompt puts the card that was being held back on the pile`() {
        val freeze = action("freeze")
        val stopped = table().copy(
            pendingAction = PendingAction(cardDefId = "freeze", playerId = "a", card = freeze),
        )

        val after = DevMode.apply(stopped, DevSetup(clearPrompt = true))

        assertNull(after.pendingAction)
        assertTrue(after.discard.any { it.id == freeze.id }, "the card it was holding is not simply lost")
    }

    @Test
    fun `ending the round takes everybody still in out of it`() {
        val after = DevMode.apply(startedAndDealt(), DevSetup(endRound = true))
        assertTrue(after.players.none { it.status == PlayerStatus.ACTIVE })
        assertTrue(after.dealQueue.isEmpty())
    }

    @Test
    fun `the round and the turn can be moved`() {
        val after = DevMode.apply(table(), DevSetup(round = 4, turnPlayerId = "b"))
        assertEquals(4, after.round)
        assertEquals("b", after.currentPlayer?.id)
    }

    // ─── The gate ───

    private fun seat(playerId: String) = Connection(playerId, Channel(Channel.UNLIMITED))

    private fun room(dev: Boolean): Room =
        RoomRegistry(appJson, CoroutineScope(Job())).create(seed = 20260904L, dev = dev)

    @Test
    fun `a room in testing mode takes a dev command`() = runBlocking {
        val room = room(dev = true)
        room.attach("a", "ana", seat("a"))
        room.attach("b", "bo", seat("b"))

        room.handle("a", ClientMessage.Dev(DevSetup(players = listOf(DevPlayerPatch(playerId = "a", score = 42)))))

        assertEquals(42, room.state.player("a")!!.score)
        room.close()
    }

    @Test
    fun `every other room ignores one`() = runBlocking {
        val room = room(dev = false)
        room.attach("a", "ana", seat("a"))
        room.attach("b", "bo", seat("b"))

        room.handle("a", ClientMessage.Dev(DevSetup(players = listOf(DevPlayerPatch(playerId = "a", score = 42)))))

        assertEquals(0, room.state.player("a")!!.score, "a real table must not be writable by a client")
        room.close()
    }

    @Test
    fun `a real table is never told what is coming`() = runBlocking {
        val room = room(dev = false)
        val outbound = Channel<String>(Channel.UNLIMITED)
        room.attach("a", "ana", Connection("a", outbound))
        room.attach("b", "bo", seat("b"))
        room.handle("a", ClientMessage.StartGame)

        val states = generateSequence { outbound.tryReceive().getOrNull() }
            .map { appJson.decodeFromString(ServerMessage.serializer(), it) }
            .filterIsInstance<ServerMessage.State>()
            .toList()

        assertTrue(states.isNotEmpty(), "the room said nothing at all")
        assertTrue(states.all { it.state.devDeck == null }, "the deck is the one thing a player cannot be shown")
        room.close()
    }

    @Test
    fun `a dev command before the deal is held for the shuffle`() = runBlocking {
        val room = room(dev = true)
        room.attach("a", "ana", seat("a"))
        room.attach("b", "bo", seat("b"))

        room.handle("a", ClientMessage.Dev(DevSetup(stack = listOf("freeze", "7"))))
        assertEquals(GamePhase.LOBBY, room.state.phase, "there is no deck to stack yet")

        room.handle("a", ClientMessage.StartGame)
        assertEquals(listOf("freeze", "7"), room.state.deck.take(2).map { it.defId ?: it.label })
        room.close()
    }
}
