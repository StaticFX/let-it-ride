package com.letitride.server

import com.letitride.engine.Card
import com.letitride.engine.CardKind
import com.letitride.engine.DeckConfig
import com.letitride.engine.GameConfig
import com.letitride.engine.GameState
import com.letitride.engine.NumberCardEntry
import com.letitride.engine.Player
import com.letitride.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hidden hand, at the only point it could ever get out: the wire.
 *
 * Everything else about a gambler card is ordinary — it is dealt, held and
 * discarded like anything else. What is not ordinary is that the state sent to
 * one player is not the state sent to another, which is the first time this
 * game has had to say that, and these are the ways it could quietly stop being
 * true.
 */
class HiddenHandTest {
    private fun table(): GameState = GameState(
        config = GameConfig(deck = DeckConfig(numberCards = listOf(NumberCardEntry(value = 3, count = 20)))),
        players = listOf(
            Player(id = "a", name = "ana", gamblers = listOf(gambler("g-1"), gambler("g-2"))),
            Player(id = "b", name = "bo", gamblers = listOf(gambler("g-3"))),
        ),
    )

    private fun gambler(id: String) =
        Card(id = id, kind = CardKind.GAMBLER, label = "nullify", value = 0, defId = "nullify")

    private fun GameState.viewFor(viewerId: String?) =
        toView(viewerId = viewerId, roomCode = "ROOM", hostId = "a", turnDeadline = null)

    @Test
    fun `a player is sent their own gambler cards face up`() {
        val view = table().viewFor("a")
        assertEquals(listOf("g-1", "g-2"), view.myGamblers.map { it.id })
    }

    @Test
    fun `and nobody else's, at all`() {
        val view = table().viewFor("a")
        // Not "b's cards are absent from a list somewhere" — absent from the
        // whole payload, which is the only claim worth making.
        val payload = appJson.encodeToString(GameStateView.serializer(), view)
        assertFalse("g-3" in payload, "b's gambler card reached a's copy of the state")
    }

    @Test
    fun `how many everyone is holding is public, because the cap is`() {
        val view = table().viewFor("b")
        assertEquals(mapOf("a" to 2, "b" to 1), view.gamblerCounts)
        assertEquals(mapOf("a" to 5, "b" to 5), view.gamblerLimits)
    }

    /**
     * The structural half of the guard. `Player.gamblers` is `@Transient`, so a
     * hand cannot ride out on the player list even if somebody forgets every
     * other rule in this file.
     */
    @Test
    fun `the player list carries no gambler cards whatever the viewer`() {
        for (viewer in listOf("a", "b", null)) {
            val payload = appJson.encodeToString(GameStateView.serializer(), table().viewFor(viewer))
            // `Player.gamblers` is the only field anywhere called that — the
            // view's own is `myGamblers` — so the name appearing at all means
            // the `@Transient` has come off and every hand is on the wire.
            assertFalse(
                "\"gamblers\"" in payload,
                "Player.gamblers was serialised for viewer $viewer: every hidden hand is public",
            )
        }
    }

    @Test
    fun `a view with no viewer shows nobody's hand`() {
        val view = table().viewFor(null)
        assertTrue(view.myGamblers.isEmpty())
        assertTrue(view.playableGamblers.isEmpty())
        // ...but still says how many everybody has, which was never a secret.
        assertEquals(mapOf("a" to 2, "b" to 1), view.gamblerCounts)
    }

    /**
     * A shop card is minted rather than dealt, so a client counting the deck has
     * to be told how many of the cards it can see were never in it.
     */
    @Test
    fun `cards the shop minted are counted apart from the ones the deck dealt`() {
        val bought = Card(id = "tmp-shop-1", kind = CardKind.GAMBLER, label = "nahhh", value = 0, defId = "nahhh")
        val state = table().let { s ->
            s.copy(players = s.players.map { if (it.id == "a") it.copy(gamblers = it.gamblers + bought) else it })
        }
        assertEquals(1, state.viewFor("a").mintedGamblers)
        assertEquals(1, state.viewFor("b").mintedGamblers, "the count is public; the face is not")
    }
}
