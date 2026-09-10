package com.letitride.account

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The default is no accounts at all, and it has to stay the default.
 *
 * This whole feature is an addition to a game that shipped without it, and the
 * shipped image still has neither a database nor an identity provider — so the
 * thing most worth a test is not that signing in works, it is that a server
 * asked for none of it builds none of it.
 */
class AccountsOffTest {

    private fun env(vararg values: Pair<String, String>): (String) -> String? {
        val map = values.toMap()
        return { map[it] }
    }

    // ─── Nothing configured ───

    @Test
    fun `an empty environment has nowhere to write and nobody to write about`() {
        assertNull(openAccountStore(env()))
        assertNull(oidcConfigFrom(env()))
    }

    @Test
    fun `a blank path is the same as no path`() {
        assertNull(openAccountStore(env(DB_PATH_ENV to "   ")))
    }

    @Test
    fun `a database that cannot be opened costs the server nothing`() {
        // A directory is not a database file, and never will be one.
        val directory = Files.createTempDirectory("let-it-ride-not-a-db")
        try {
            assertNull(
                openAccountStore(env(DB_PATH_ENV to directory.absolutePathString())),
                "a server with a broken database still deals cards",
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an accounts with neither half answers no to the only question anybody asks`() {
        val accounts = Accounts(null, null, null)
        assertFalse(accounts.enabled)
        assertNull(accounts.recorder, "a room handed no recorder does not tally")
    }

    // ─── Half configured ───

    @Test
    fun `an issuer with no client secret is not an identity provider`() {
        val config = oidcConfigFrom(
            env(
                OIDC_ISSUER_ENV to "https://id.example.com",
                OIDC_CLIENT_ID_ENV to "let-it-ride",
            ),
        )
        assertNull(config, "half a client is not a client, and a half-open door is worse than a shut one")
    }

    @Test
    fun `a client with no issuer is not one either`() {
        assertNull(oidcConfigFrom(env(OIDC_CLIENT_ID_ENV to "let-it-ride", OIDC_CLIENT_SECRET_ENV to "shh")))
    }

    // ─── Configured ───

    @Test
    fun `the issuer's trailing slash is not part of it`() {
        val config = oidcConfigFrom(
            env(
                OIDC_ISSUER_ENV to "https://id.example.com/",
                OIDC_CLIENT_ID_ENV to "let-it-ride",
                OIDC_CLIENT_SECRET_ENV to "shh",
            ),
        )
        assertEquals("https://id.example.com", config?.issuer)
    }

    @Test
    fun `the scopes and the button's name have sensible defaults and can be said instead`() {
        val plain = oidcConfigFrom(
            env(
                OIDC_ISSUER_ENV to "https://id.example.com",
                OIDC_CLIENT_ID_ENV to "let-it-ride",
                OIDC_CLIENT_SECRET_ENV to "shh",
            ),
        )
        assertEquals("openid profile email", plain?.scopes)
        assertNull(plain?.redirectUri, "worked out from the request unless somebody says otherwise")

        val stated = oidcConfigFrom(
            env(
                OIDC_ISSUER_ENV to "https://id.example.com",
                OIDC_CLIENT_ID_ENV to "let-it-ride",
                OIDC_CLIENT_SECRET_ENV to "shh",
                OIDC_SCOPES_ENV to "openid profile",
                OIDC_PROVIDER_NAME_ENV to "PocketID",
                OIDC_REDIRECT_ENV to "https://cards.example.com/api/auth/callback",
            ),
        )
        assertEquals("openid profile", stated?.scopes)
        assertEquals("PocketID", stated?.providerName)
        assertEquals("https://cards.example.com/api/auth/callback", stated?.redirectUri)
    }

    // ─── The session key ───

    @Test
    fun `a stated secret is stretched to a key and is the same key every time`() {
        val warnings = mutableListOf<String>()
        val first = sessionKey(env(SESSION_SECRET_ENV to "correct horse battery staple"), warnings::add)
        val second = sessionKey(env(SESSION_SECRET_ENV to "correct horse battery staple"), warnings::add)

        assertEquals(32, first.size)
        assertTrue(first.contentEquals(second), "the same secret has to survive a restart")
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `no secret is a working server that says what it has cost you`() {
        val warnings = mutableListOf<String>()
        val key = sessionKey(env(), warnings::add)

        assertTrue(key.isNotEmpty())
        assertEquals(1, warnings.size)
        assertTrue(SESSION_SECRET_ENV in warnings.single())
    }

    // ─── A flight ───

    @Test
    fun `a sign-in in the air is good once and only once`() {
        val flights = Flights()
        val flight = flights.start("https://cards.example.com/api/auth/callback", "/")

        assertNotNull(flights.claim(flight.state))
        assertNull(flights.claim(flight.state), "a state that has been spent is a state that has been spent")
    }

    @Test
    fun `a flight older than its window is not honoured`() {
        val flights = Flights(ttlMs = 0)
        val flight = flights.start("https://cards.example.com/api/auth/callback", "/")
        Thread.sleep(2)
        assertNull(flights.claim(flight.state))
    }

    @Test
    fun `a state is not guessable`() {
        val flights = Flights()
        val states = (1..64).map { flights.start("https://x/y", "/").state }.toSet()
        assertEquals(64, states.size)
        assertTrue(states.all { it.length >= 40 })
    }
}
