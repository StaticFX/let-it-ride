package com.letitride.account

import com.letitride.server.ApiError
import com.letitride.server.MAX_NAME_LENGTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Accounts, and everything that hangs off having one.
 *
 * Two switches, and the game plays with both of them off — which is what the
 * shipped image does and what every existing test does. [DB_PATH_ENV] says
 * where to keep what people have done; [OIDC_ISSUER_ENV] and its neighbours say
 * who is allowed to claim it. Neither implies the other and both are needed:
 * an identity with nowhere to write is a login that buys you nothing, and a
 * database with no way to prove who you are has no rows to put in it. So when
 * only one is configured this says so in the log and stays off, rather than
 * half-working.
 *
 * A guest is not a lesser player. Nothing on this path gates a seat, a room, a
 * card or a rule — the whole of it is a name you can trust and a page that
 * remembers. Take it all away and the game is the game.
 */

const val SESSION_COOKIE = "letitride_session"
const val SESSION_SECRET_ENV = "LETITRIDE_SESSION_SECRET"

/**
 * Marks the session cookie `Secure`, which means a browser will only ever send
 * it back over https.
 *
 * Off by default, and that is not carelessness: the documented way to run this
 * is a box on a LAN at a plain http address, and a `Secure` cookie there is a
 * sign-in that appears to work and then silently forgets you on the next
 * request. It is turned on for you when the callback address is an https one,
 * which is the case where it is both correct and free.
 */
const val SECURE_COOKIES_ENV = "LETITRIDE_SECURE_COOKIES"

/** Session payload. Just the id — everything else is a row away. */
@Serializable
data class UserSession(val accountId: String, val signedInAt: Long)

@Serializable
data class AuthView(
    /** Whether this server can sign anybody in at all. */
    val enabled: Boolean,
    /** What to put on the button, e.g. "PocketID". */
    val provider: String,
    val account: Account? = null,
)

@Serializable
data class LeaderboardView(val rows: List<LeaderboardRow>)

/**
 * The account half of the server, built once and handed to whoever needs it.
 *
 * [enabled] is the single question anything else should ask. Both halves null
 * is the ordinary case.
 */
class Accounts(
    val store: AccountStore?,
    val oidc: OidcClient?,
    val recorder: StatsRecorder?,
) {
    val enabled: Boolean get() = store != null && oidc != null

    private val flights = Flights()

    /** Whoever is signed in on this call, or null for a guest. */
    fun of(call: ApplicationCall): Account? {
        val store = store ?: return null
        val session = call.sessions.get<UserSession>() ?: return null
        return store.byId(session.accountId)
    }

    fun close() {
        oidc?.close()
        store?.close()
    }

    // ═══════════════════════════════════════════
    // Routes
    // ═══════════════════════════════════════════

    fun routes(route: Route) = with(route) {
        route("/auth") {
            get("/me") {
                val account = withContext(Dispatchers.IO) { of(call) }
                call.respond(AuthView(enabled, oidc?.config?.providerName ?: "", account))
            }

            get("/login") {
                val client = oidc
                if (client == null || store == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("this server has no sign-in"))
                    return@get
                }
                val redirectUri = client.config.redirectUri ?: call.callbackUrl()
                val flight = flights.start(redirectUri, call.returnTo())
                val url = runCatching { client.authorizeUrl(flight, redirectUri) }.getOrElse {
                    call.application.log.warn("could not start a sign-in", it)
                    call.respondRedirect(flight.returnTo.withError(it.readable()))
                    return@get
                }
                call.respondRedirect(url)
            }

            /**
             * Where the provider sends people back to.
             *
             * Every failure here ends as a redirect to the page they came from
             * with a message in the query rather than as a bare status code:
             * this is a browser navigation and whoever is looking at it is a
             * player, not a client library. The message is the provider's own
             * where there is one, because "the redirect uri does not match" is
             * the only sentence that has ever helped anybody fix this.
             */
            get("/callback") {
                val client = oidc
                val store = store
                if (client == null || store == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("this server has no sign-in"))
                    return@get
                }

                val state = call.request.queryParameters["state"].orEmpty()
                val flight = flights.claim(state)
                if (flight == null) {
                    // No flight means the state was forged, spent, or older than
                    // ten minutes — a stale browser tab is by far the most
                    // likely, so it is worded for that.
                    call.respondRedirect("/".withError("that sign-in took too long — try again"))
                    return@get
                }

                call.request.queryParameters["error"]?.let { error ->
                    val description = call.request.queryParameters["error_description"] ?: error
                    call.respondRedirect(flight.returnTo.withError(description))
                    return@get
                }

                val code = call.request.queryParameters["code"].orEmpty()
                if (code.isBlank()) {
                    call.respondRedirect(flight.returnTo.withError("the provider sent no code back"))
                    return@get
                }

                val identity = runCatching { client.exchange(code, flight, flight.redirectUri) }.getOrElse {
                    call.application.log.warn("a sign-in failed", it)
                    call.respondRedirect(flight.returnTo.withError(it.readable()))
                    return@get
                }

                val account = withContext(Dispatchers.IO) {
                    store.upsert(
                        issuer = identity.issuer,
                        subject = identity.subject,
                        name = identity.name.take(MAX_NAME_LENGTH),
                        username = identity.username,
                        email = identity.email,
                        picture = identity.picture,
                    )
                }
                call.sessions.set(UserSession(account.id, System.currentTimeMillis()))
                call.respondRedirect(flight.returnTo)
            }

            post("/logout") {
                // Guarded rather than always cleared: on a server with no
                // accounts the session plugin was never installed, and asking
                // it to forget something throws. Signing out of nothing is a
                // thing a client is allowed to ask for — it is what a stale tab
                // does on the way to the front door.
                if (enabled) call.sessions.clear<UserSession>()
                call.respond(AuthView(enabled, oidc?.config?.providerName ?: ""))
            }
        }

        route("/stats") {
            get("/me") {
                val store = store
                val account = if (store == null) null else withContext(Dispatchers.IO) { of(call) }
                if (store == null || account == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("nobody is signed in"))
                    return@get
                }
                val stats = withContext(Dispatchers.IO) { store.statsFor(account.id) }
                if (stats == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("no games yet"))
                    return@get
                }
                call.respond(stats)
            }

            get("/player/{id}") {
                val store = store
                if (store == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("this server keeps no stats"))
                    return@get
                }
                val id = call.parameters["id"].orEmpty()
                val stats = withContext(Dispatchers.IO) { store.statsFor(id) }
                if (stats == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("no such player"))
                    return@get
                }
                call.respond(stats)
            }

            get("/leaderboard") {
                val store = store
                if (store == null) {
                    call.respond(LeaderboardView(emptyList()))
                    return@get
                }
                call.respond(LeaderboardView(withContext(Dispatchers.IO) { store.leaderboard() }))
            }
        }
    }
}

/**
 * Builds the account half of the server from the environment, and installs the
 * session cookie when there is anything to put in it.
 *
 * Returns an [Accounts] either way. A disabled one answers every question with
 * "no", which is what lets the rest of the server ask without checking first.
 */
fun Application.installAccounts(
    scope: CoroutineScope,
    env: (String) -> String? = System::getenv,
): Accounts {
    val store = openAccountStore(env)
    val config = oidcConfigFrom(env)

    if (store != null && config == null) {
        log.warn("$DB_PATH_ENV is set but no identity provider is — nobody can sign in, so nothing will be recorded")
    }
    if (store == null && config != null) {
        log.warn("$OIDC_ISSUER_ENV is set but $DB_PATH_ENV is not — an account with nowhere to write is no account, so sign-in is off")
    }
    if (store == null || config == null) {
        store?.close()
        return Accounts(null, null, null)
    }

    val secure = env(SECURE_COOKIES_ENV)?.lowercase() in setOf("1", "true", "yes") ||
        config.redirectUri?.startsWith("https://") == true
    // Read out here rather than inside the block below: `log` is the
    // application's and the session builder is a receiver of its own.
    val key = sessionKey(env, log::warn)

    install(Sessions) {
        cookie<UserSession>(SESSION_COOKIE) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = SESSION_DAYS * 24L * 60 * 60
            cookie.secure = secure
            // Lax rather than Strict: the sign-in *is* a navigation back from
            // somebody else's site, and Strict would drop the cookie on exactly
            // the request that just set it.
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(key))
        }
    }

    log.info("accounts are on: sign-in against ${config.issuer}, kept in ${store.file}")
    return Accounts(store, OidcClient(config), StatsRecorder(store, scope))
}

private const val SESSION_DAYS = 30

/**
 * The key the session cookie is signed with.
 *
 * Generated when the operator has not set one, which keeps the zero-config
 * path working — at the price of every sign-in ending when the container
 * restarts. That is worth a line in the log rather than a hard failure: the
 * cost is one click, and refusing to boot over it would make the feature
 * mandatory for anybody who turned it on once.
 */
internal fun sessionKey(env: (String) -> String?, warn: (String) -> Unit): ByteArray {
    val secret = env(SESSION_SECRET_ENV)?.takeIf { it.isNotBlank() }
    if (secret == null) {
        warn("$SESSION_SECRET_ENV is not set — a random one is in use, so signing in will not survive a restart")
        return randomToken().toByteArray()
    }
    // Stretched to a fixed length so any passphrase is a usable key.
    return MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
}

/**
 * The address the provider should send people back to.
 *
 * Read off the request, which behind a reverse proxy means read off
 * `X-Forwarded-*` — see the `XForwardedHeaders` plugin, which is installed for
 * this one purpose. [OIDC_REDIRECT_ENV] overrides it, and an operator whose
 * proxy does not set those headers will need it.
 */
private fun ApplicationCall.callbackUrl(): String {
    val origin = request.origin
    val scheme = origin.scheme
    val host = request.host()
    val port = origin.serverPort
    val authority = if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
        host
    } else {
        "$host:$port"
    }
    return "$scheme://$authority/api/auth/callback"
}

/**
 * Where to put somebody down afterwards.
 *
 * Only ever a path on this server. An open redirect is the classic way a login
 * route becomes somebody else's phishing page, and the guard is the boring one:
 * it must start with a single slash. `//evil.example` is a URL, not a path.
 */
private fun ApplicationCall.returnTo(): String {
    val asked = request.queryParameters["return"].orEmpty()
    if (!asked.startsWith("/") || asked.startsWith("//")) return "/"
    return asked.take(512)
}

private fun String.withError(message: String): String {
    val separator = if (contains('?')) "&" else "?"
    return "$this${separator}authError=${URLEncoder.encode(message.take(200), "UTF-8")}"
}

private fun Throwable.readable(): String =
    (this as? OidcException)?.message ?: message ?: "the sign-in did not work"
