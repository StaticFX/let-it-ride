package com.letitride.account

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in, against whatever OpenID Connect provider the operator points this
 * at — PocketID is what it was written for and is not named anywhere in it.
 *
 * Everything here is driven by the provider's own discovery document rather
 * than by endpoints written down in this file. That is the difference between
 * "works with PocketID" and "works with the thing you actually run", and it
 * costs one request at startup.
 *
 * Nothing in this file runs on a server with no issuer configured. That is the
 * default and the shipped image's behaviour: the login route is not registered,
 * the client is never built, and the front door has no button on it.
 */

const val OIDC_ISSUER_ENV = "LETITRIDE_OIDC_ISSUER"
const val OIDC_CLIENT_ID_ENV = "LETITRIDE_OIDC_CLIENT_ID"
const val OIDC_CLIENT_SECRET_ENV = "LETITRIDE_OIDC_CLIENT_SECRET"
const val OIDC_REDIRECT_ENV = "LETITRIDE_OIDC_REDIRECT_URI"
const val OIDC_SCOPES_ENV = "LETITRIDE_OIDC_SCOPES"

/** What the button says. The provider's name is the operator's to give. */
const val OIDC_PROVIDER_NAME_ENV = "LETITRIDE_OIDC_NAME"

private val log = LoggerFactory.getLogger("com.letitride.account.Oidc")

data class OidcConfig(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    /**
     * The address the provider sends people back to, when the operator has
     * pinned one.
     *
     * Null means "work it out from the request", which is right on a LAN and
     * right behind a proxy that sets `X-Forwarded-*`. It is here at all because
     * a mismatch between this and what is registered at the provider is the one
     * failure in this whole feature that produces no useful message at either
     * end, and an operator who hits it needs a way to simply state the answer.
     */
    val redirectUri: String?,
    val scopes: String,
    val providerName: String,
)

fun oidcConfigFrom(env: (String) -> String? = System::getenv): OidcConfig? {
    val issuer = env(OIDC_ISSUER_ENV)?.trim()?.trimEnd('/').orEmpty()
    val clientId = env(OIDC_CLIENT_ID_ENV)?.trim().orEmpty()
    val clientSecret = env(OIDC_CLIENT_SECRET_ENV)?.trim().orEmpty()
    if (issuer.isBlank() || clientId.isBlank()) return null
    if (clientSecret.isBlank()) {
        log.warn("$OIDC_ISSUER_ENV is set but $OIDC_CLIENT_SECRET_ENV is not — signing in is off")
        return null
    }
    return OidcConfig(
        issuer = issuer,
        clientId = clientId,
        clientSecret = clientSecret,
        redirectUri = env(OIDC_REDIRECT_ENV)?.trim()?.takeIf { it.isNotBlank() },
        scopes = env(OIDC_SCOPES_ENV)?.trim()?.takeIf { it.isNotBlank() } ?: "openid profile email",
        providerName = env(OIDC_PROVIDER_NAME_ENV)?.trim()?.takeIf { it.isNotBlank() } ?: "your account",
    )
}

/** The half of the discovery document this needs. */
data class Discovery(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val userinfoEndpoint: String?,
    val authMethods: List<String>,
)

/** Who signed in, as the provider describes them. */
data class OidcIdentity(
    val issuer: String,
    val subject: String,
    val name: String,
    val username: String?,
    val email: String?,
    val picture: String?,
)

class OidcException(message: String) : Exception(message)

class OidcClient(
    val config: OidcConfig,
    private val http: HttpClient = defaultHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val discoveryLock = Mutex()
    private var cached: Discovery? = null

    /**
     * The provider's endpoints, fetched once and then remembered.
     *
     * Not fetched at startup. A game server that refused to boot because an
     * identity provider was slow to come up would be a game server that a
     * homelab restart can take down for reasons that have nothing to do with
     * the game — so the first person to press "sign in" pays for it, and a
     * failure is a failure of that click and of nothing else.
     */
    suspend fun discovery(): Discovery = discoveryLock.withLock {
        cached ?: fetchDiscovery().also { cached = it }
    }

    private suspend fun fetchDiscovery(): Discovery {
        val url = "${config.issuer}/.well-known/openid-configuration"
        val response = runCatching { http.get(url) }
            .getOrElse { throw OidcException("could not reach $url: ${it.describe()}") }
        if (!response.status.isSuccess()) {
            throw OidcException("$url answered ${response.status}")
        }
        val document = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: throw OidcException("$url did not answer with a discovery document")

        val issuer = document.string("issuer") ?: throw OidcException("the discovery document names no issuer")
        // The spec requires these to match, and a mismatch is how a
        // misconfigured proxy shows up — worth failing loudly on rather than
        // quietly signing people in against an issuer nobody meant.
        if (issuer.trimEnd('/') != config.issuer.trimEnd('/')) {
            throw OidcException("$OIDC_ISSUER_ENV is ${config.issuer} but the provider calls itself $issuer")
        }
        return Discovery(
            issuer = issuer,
            authorizationEndpoint = document.string("authorization_endpoint")
                ?: throw OidcException("the discovery document names no authorization endpoint"),
            tokenEndpoint = document.string("token_endpoint")
                ?: throw OidcException("the discovery document names no token endpoint"),
            userinfoEndpoint = document.string("userinfo_endpoint"),
            authMethods = (document["token_endpoint_auth_methods_supported"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty(),
        )
    }

    /** Where to send somebody to sign in. */
    suspend fun authorizeUrl(flight: Flight, redirectUri: String): String {
        val discovery = discovery()
        return URLBuilder(discovery.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", config.scopes)
            parameters.append("state", flight.state)
            parameters.append("nonce", flight.nonce)
            parameters.append("code_challenge", challengeFor(flight.verifier))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    /**
     * Swaps the code for who it belongs to.
     *
     * The id token's signature is deliberately not checked, and the reason is
     * worth stating because "we skipped a signature" reads like a shortcut. It
     * is not one here: this token did not arrive through the browser. It came
     * back on a TLS connection this server opened directly to the token
     * endpoint named in the provider's own discovery document, authenticated
     * with the client secret — which is exactly the case OpenID Connect Core
     * §3.1.3.7 allows a client to accept without verifying the JWS. Anything
     * that could forge the token could equally forge the response carrying it,
     * so a signature check would be verifying the same channel twice.
     *
     * What is checked is everything a signature would not have caught anyway:
     * the issuer, the audience, the expiry, and the nonce this server minted
     * for this one sign-in. And the subject is then confirmed a second time
     * against the userinfo endpoint, which is an independent authenticated call
     * to the provider — so no claim reaches the database on the strength of a
     * single answer.
     */
    suspend fun exchange(code: String, flight: Flight, redirectUri: String): OidcIdentity {
        val discovery = discovery()
        val form = Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
            append("code_verifier", flight.verifier)
            append("client_id", config.clientId)
            // client_secret_post unless the provider says it only takes Basic.
            if (!prefersBasic(discovery)) append("client_secret", config.clientSecret)
        }
        val response = runCatching {
            http.submitForm(discovery.tokenEndpoint, form) {
                if (prefersBasic(discovery)) {
                    val credentials = "${urlEncode(config.clientId)}:${urlEncode(config.clientSecret)}"
                    header(HttpHeaders.Authorization, "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}")
                }
            }
        }.getOrElse { throw OidcException("could not reach the token endpoint: ${it.describe()}") }

        if (!response.status.isSuccess()) {
            // The body is the provider's own error and is the only thing that
            // ever says *which* of the six things is misconfigured.
            throw OidcException("the token endpoint answered ${response.status}: ${response.bodyAsText().take(200)}")
        }

        val tokens = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: throw OidcException("the token endpoint did not answer with json")
        val idToken = tokens.string("id_token")
            ?: throw OidcException("the token endpoint returned no id token")
        val accessToken = tokens.string("access_token")

        val claims = readClaims(idToken)
        verify(claims, discovery, flight)

        val subject = claims.string("sub") ?: throw OidcException("the id token names no subject")
        val fromUserinfo = accessToken?.let { userinfo(discovery, it, subject) }
        val merged = fromUserinfo ?: claims

        return OidcIdentity(
            issuer = discovery.issuer,
            subject = subject,
            // Every provider fills in a different one of these; the display
            // name is only ever the first that is actually there.
            name = merged.string("name")
                ?: merged.string("preferred_username")
                ?: merged.string("nickname")
                ?: merged.string("given_name")
                ?: merged.string("email")?.substringBefore('@')
                ?: "player",
            username = merged.string("preferred_username"),
            email = merged.string("email"),
            picture = merged.string("picture"),
        )
    }

    /**
     * The provider's own account of who that token belongs to.
     *
     * Best effort: a provider with no userinfo endpoint, or one having a bad
     * minute, leaves the id token's claims standing rather than failing a
     * sign-in that has otherwise succeeded. What it is never allowed to do is
     * *disagree* — a subject that comes back different is a token that has been
     * swapped somewhere between here and there, and that is fatal.
     */
    private suspend fun userinfo(discovery: Discovery, accessToken: String, subject: String): JsonObject? {
        val endpoint = discovery.userinfoEndpoint ?: return null
        val response = runCatching {
            http.get(endpoint) { header(HttpHeaders.Authorization, "Bearer $accessToken") }
        }.getOrElse {
            log.warn("could not reach the userinfo endpoint; falling back to the id token", it)
            return null
        }
        if (!response.status.isSuccess()) {
            log.warn("the userinfo endpoint answered ${response.status}; falling back to the id token")
            return null
        }
        val claims = runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
            ?: return null
        if (claims.string("sub") != subject) {
            throw OidcException("the userinfo endpoint named a different subject than the id token")
        }
        return claims
    }

    private fun verify(claims: JsonObject, discovery: Discovery, flight: Flight) {
        val issuer = claims.string("iss") ?: throw OidcException("the id token names no issuer")
        if (issuer.trimEnd('/') != discovery.issuer.trimEnd('/')) {
            throw OidcException("the id token was issued by $issuer, not ${discovery.issuer}")
        }
        val audiences = when (val aud = claims["aud"]) {
            is JsonPrimitive -> listOf(aud.content)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
        if (config.clientId !in audiences) {
            throw OidcException("the id token was not issued for this client")
        }
        val expiry = (claims["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw OidcException("the id token has no expiry")
        // Thirty seconds of slack for two clocks that are not quite the same.
        if (expiry + 30 < System.currentTimeMillis() / 1000) {
            throw OidcException("the id token had already expired")
        }
        val nonce = claims.string("nonce")
        if (nonce != flight.nonce) {
            throw OidcException("the id token carries the wrong nonce")
        }
    }

    private fun readClaims(idToken: String): JsonObject {
        val parts = idToken.split('.')
        if (parts.size < 2) throw OidcException("the id token is not a jwt")
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }
            .getOrElse { throw OidcException("the id token's payload could not be read") }
        return runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: throw OidcException("the id token's payload is not json")
    }

    private fun prefersBasic(discovery: Discovery): Boolean =
        discovery.authMethods.isNotEmpty() &&
            "client_secret_post" !in discovery.authMethods &&
            "client_secret_basic" in discovery.authMethods

    fun close() = http.close()
}

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/**
 * One sign-in in progress.
 *
 * The verifier never leaves this server — it is what makes the code that comes
 * back through the browser worthless to anybody who intercepts it — so these
 * are held in memory rather than in a cookie, and a restart mid-sign-in simply
 * costs somebody a second click.
 */
data class Flight(
    val state: String,
    val nonce: String,
    val verifier: String,
    val redirectUri: String,
    val returnTo: String,
    val startedAt: Long,
)

/**
 * The sign-ins currently in the air.
 *
 * Bounded and swept, because this is the one map on the server that anybody on
 * the internet can add to by pressing a button. A flight is good for ten
 * minutes, and the oldest are dropped once there are more than [MAX] of them —
 * so the worst somebody can do by hammering the login route is make their own
 * sign-in fail.
 */
class Flights(private val ttlMs: Long = 10 * 60 * 1000) {
    private val flights = java.util.concurrent.ConcurrentHashMap<String, Flight>()

    fun start(redirectUri: String, returnTo: String): Flight {
        sweep()
        val flight = Flight(
            state = randomToken(),
            nonce = randomToken(),
            verifier = randomToken() + randomToken(),
            redirectUri = redirectUri,
            returnTo = returnTo,
            startedAt = System.currentTimeMillis(),
        )
        flights[flight.state] = flight
        return flight
    }

    /** Reads a flight and spends it; a state is only ever good once. */
    fun claim(state: String): Flight? {
        sweep()
        return flights.remove(state)?.takeIf { System.currentTimeMillis() - it.startedAt <= ttlMs }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        flights.entries.removeIf { now - it.value.startedAt > ttlMs }
        if (flights.size <= MAX) return
        flights.entries.sortedBy { it.value.startedAt }.take(flights.size - MAX).forEach { flights.remove(it.key) }
    }

    private companion object {
        const val MAX = 512
    }
}

private val random = SecureRandom()

fun randomToken(bytes: Int = 32): String {
    val buffer = ByteArray(bytes)
    random.nextBytes(buffer)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
}

private fun challengeFor(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

/**
 * A failure in a sentence somebody can act on.
 *
 * The name of the exception when there is no message, because the two failures
 * an operator will actually hit here — a hostname that does not resolve and a
 * connection that is refused — both arrive with a null message, and "could not
 * reach https://id.example.com/…: null" tells nobody anything.
 */
private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown failure"

/** A string claim, or null when it is absent, null, blank or not a string. */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
