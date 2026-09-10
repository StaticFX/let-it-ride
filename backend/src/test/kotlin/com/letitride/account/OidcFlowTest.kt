package com.letitride.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in, driven against a provider that is not there.
 *
 * Everything in [OidcClient] is a conversation with somebody else's server, so
 * without a stand-in for one the only things testable would be the string
 * handling — and the parts worth being sure of are exactly the parts that talk:
 * that the code is exchanged with a verifier and a secret, that a token issued
 * for somebody else is refused, that a stale one is refused, and that a
 * userinfo endpoint disagreeing about who this is stops the whole thing.
 *
 * These are the checks standing in for the signature that is deliberately not
 * verified — see [OidcClient.exchange], which explains why that is the right
 * call for a token that arrived on a back channel this server opened itself.
 */
class OidcFlowTest {

    private val issuer = "https://id.example.com"

    private fun config(clientId: String = "let-it-ride") = OidcConfig(
        issuer = issuer,
        clientId = clientId,
        clientSecret = "shh",
        redirectUri = null,
        scopes = "openid profile email",
        providerName = "PocketID",
    )

    private val discovery = """
        {
          "issuer": "$issuer",
          "authorization_endpoint": "$issuer/authorize",
          "token_endpoint": "$issuer/api/oidc/token",
          "userinfo_endpoint": "$issuer/api/oidc/userinfo",
          "token_endpoint_auth_methods_supported": ["client_secret_post", "client_secret_basic"]
        }
    """.trimIndent()

    /** A JWT with a readable payload and a signature nobody looks at. */
    private fun idToken(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        return "$header.${encoder.encodeToString(claims.toByteArray())}.not-checked"
    }

    private fun claims(
        aud: String = "let-it-ride",
        iss: String = issuer,
        nonce: String,
        exp: Long = System.currentTimeMillis() / 1000 + 300,
        sub: String = "sub-1",
    ) = """{"iss":"$iss","aud":"$aud","sub":"$sub","exp":$exp,"nonce":"$nonce","name":"Devin"}"""

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A provider that answers, and a record of everything it was asked. */
    private class Provider(val requests: MutableList<HttpRequestData> = mutableListOf())

    private fun clientFor(
        provider: Provider,
        token: (HttpRequestData) -> Pair<HttpStatusCode, String>,
        userinfo: (HttpRequestData) -> Pair<HttpStatusCode, String>? = { HttpStatusCode.OK to """{"sub":"sub-1","name":"Devin","preferred_username":"devin","email":"devin@example.com"}""" },
    ): OidcClient {
        val engine = MockEngine { request ->
            provider.requests += request
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(discovery, HttpStatusCode.OK, json)
                "/api/oidc/token" -> token(request).let { (status, body) -> respond(body, status, json) }
                "/api/oidc/userinfo" -> userinfo(request)
                    ?.let { (status, body) -> respond(body, status, json) }
                    ?: respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        return OidcClient(config(), HttpClient(engine))
    }

    private fun flight() = Flights().start("$issuer/callback", "/")

    // ─── Where somebody is sent ───

    @Test
    fun `the authorize url carries a challenge and never the verifier`() = runBlocking {
        val client = clientFor(Provider(), token = { HttpStatusCode.OK to "{}" })
        val flight = flight()

        val url = client.authorizeUrl(flight, "https://cards.example.com/api/auth/callback")

        assertTrue(url.startsWith("$issuer/authorize?"))
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=" in url && "nonce=" in url)
        assertTrue(
            flight.verifier !in url,
            "the verifier is the whole reason an intercepted code is worthless; it must not travel",
        )
    }

    @Test
    fun `a provider that calls itself something else is refused`(): Unit = runBlocking {
        val engine = MockEngine {
            respond("""{"issuer":"https://someone-else.example.com"}""", HttpStatusCode.OK, json)
        }
        val client = OidcClient(config(), HttpClient(engine))
        val failure = assertFailsWith<OidcException> { client.discovery() }
        assertTrue("someone-else" in failure.message.orEmpty())
    }

    // ─── Coming back ───

    @Test
    fun `a code is exchanged with the verifier and the secret, and the person comes back`() = runBlocking {
        val provider = Provider()
        val flight = flight()
        val client = clientFor(provider, token = {
            HttpStatusCode.OK to """{"id_token":"${idToken(claims(nonce = flight.nonce))}","access_token":"at"}"""
        })

        val identity = client.exchange("the-code", flight, flight.redirectUri)

        assertEquals("sub-1", identity.subject)
        assertEquals("Devin", identity.name)
        assertEquals("devin", identity.username)
        assertEquals("devin@example.com", identity.email)
        assertEquals(issuer, identity.issuer)

        val body = String(provider.requests.first { it.url.encodedPath == "/api/oidc/token" }.body.toByteArray())
        assertTrue("grant_type=authorization_code" in body)
        assertTrue("code=the-code" in body)
        assertTrue("code_verifier=" in body)
        assertTrue("client_secret=shh" in body)
    }

    @Test
    fun `a token issued for somebody else is not a sign-in here`(): Unit = runBlocking {
        val flight = flight()
        val client = clientFor(Provider(), token = {
            HttpStatusCode.OK to """{"id_token":"${idToken(claims(aud = "some-other-app", nonce = flight.nonce))}"}"""
        })
        val failure = assertFailsWith<OidcException> { client.exchange("c", flight, flight.redirectUri) }
        assertTrue("not issued for this client" in failure.message.orEmpty())
    }

    @Test
    fun `a token carrying the wrong nonce is a replay and is refused`(): Unit = runBlocking {
        val flight = flight()
        val client = clientFor(Provider(), token = {
            HttpStatusCode.OK to """{"id_token":"${idToken(claims(nonce = "somebody else's nonce"))}"}"""
        })
        assertFailsWith<OidcException> { client.exchange("c", flight, flight.redirectUri) }
        Unit
    }

    @Test
    fun `an expired token is refused`(): Unit = runBlocking {
        val flight = flight()
        val client = clientFor(Provider(), token = {
            HttpStatusCode.OK to
                """{"id_token":"${idToken(claims(nonce = flight.nonce, exp = 1_000_000))}"}"""
        })
        val failure = assertFailsWith<OidcException> { client.exchange("c", flight, flight.redirectUri) }
        assertTrue("expired" in failure.message.orEmpty())
    }

    @Test
    fun `the provider's own error is passed on rather than swallowed`(): Unit = runBlocking {
        val client = clientFor(Provider(), token = {
            HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"redirect uri mismatch"}"""
        })
        val failure = assertFailsWith<OidcException> { client.exchange("c", flight(), "https://x/y") }
        assertTrue(
            "redirect uri mismatch" in failure.message.orEmpty(),
            "this is the one sentence that has ever helped anybody fix this: ${failure.message}",
        )
    }

    // ─── The second opinion ───

    @Test
    fun `a userinfo endpoint naming a different person stops the sign-in`(): Unit = runBlocking {
        val flight = flight()
        val client = clientFor(
            Provider(),
            token = { HttpStatusCode.OK to """{"id_token":"${idToken(claims(nonce = flight.nonce))}","access_token":"at"}""" },
            userinfo = { HttpStatusCode.OK to """{"sub":"somebody-else","name":"Mallory"}""" },
        )
        val failure = assertFailsWith<OidcException> { client.exchange("c", flight, flight.redirectUri) }
        assertTrue("different subject" in failure.message.orEmpty())
    }

    @Test
    fun `a userinfo endpoint having a bad minute leaves the id token standing`() = runBlocking {
        val flight = flight()
        val client = clientFor(
            Provider(),
            token = { HttpStatusCode.OK to """{"id_token":"${idToken(claims(nonce = flight.nonce))}","access_token":"at"}""" },
            userinfo = { HttpStatusCode.ServiceUnavailable to "down" },
        )

        val identity = client.exchange("c", flight, flight.redirectUri)
        assertEquals("sub-1", identity.subject, "a sign-in that has otherwise succeeded is not thrown away")
        assertEquals("Devin", identity.name)
        assertNull(identity.username, "…but nothing is invented that only userinfo could have said")
    }
}
