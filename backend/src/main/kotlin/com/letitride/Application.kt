package com.letitride

import com.letitride.server.ApiError
import com.letitride.server.ClientMessage
import com.letitride.server.Connection
import com.letitride.server.CreateRoomRequest
import com.letitride.server.CreateRoomResponse
import com.letitride.server.RoomInfoResponse
import com.letitride.server.RoomRegistry
import com.letitride.server.ServerMessage
import com.letitride.server.TEST_HOOKS_ENV
import com.letitride.server.buildCatalog
import com.letitride.server.newPlayerId
import com.letitride.server.testHooksEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private const val MAX_NAME_LENGTH = 16

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
    explicitNulls = false
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json(appJson) }
    install(Compression) { gzip() }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 60.seconds
        maxFrameSize = 256 * 1024
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled failure on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal error"))
        }
    }

    val registry = RoomRegistry(appJson, this)
    val hasBundledFrontend = javaClass.classLoader.getResource("web/index.html") != null

    // Off in every shipped image. The end-to-end suite turns it on so a run can
    // pin a room's seed and replay the same deal card for card.
    val testHooks = testHooksEnabled()
    if (testHooks) log.warn("$TEST_HOOKS_ENV is on — clients may pin a room's shuffle and stack its deck. Never do this in production.")

    routing {
        route("/api") {
            get("/health") {
                call.respondText(
                    """{"status":"ok","rooms":${registry.size()},"testHooks":$testHooks}""",
                    io.ktor.http.ContentType.Application.Json,
                )
            }

            get("/catalog") { call.respond(buildCatalog()) }

            post("/rooms") {
                val request = runCatching { call.receive<CreateRoomRequest>() }.getOrNull()
                val name = request?.name?.trim()?.take(MAX_NAME_LENGTH)
                if (name.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("a name is required"))
                    return@post
                }
                val room = registry.create(
                    seed = request?.seed?.takeIf { testHooks },
                    stack = request?.stack.orEmpty().take(64).takeIf { testHooks } ?: emptyList(),
                )
                call.respond(CreateRoomResponse(roomCode = room.code, playerId = newPlayerId()))
            }

            get("/rooms/{code}") {
                val code = call.parameters["code"].orEmpty()
                val room = registry.get(code)
                if (room == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("no game with that code"))
                    return@get
                }
                val state = room.state
                call.respond(
                    RoomInfoResponse(
                        roomCode = room.code,
                        players = state.players.size,
                        phase = state.phase,
                        joinable = room.canJoin(),
                    ),
                )
            }
        }

        webSocket("/ws/{code}") {
            val code = call.parameters["code"].orEmpty().uppercase()
            val room = registry.get(code)
            if (room == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no game with that code"))
                return@webSocket
            }

            val name = call.request.queryParameters["name"]
                ?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotBlank() } ?: "player"
            val playerId = call.request.queryParameters["playerId"]?.take(64) ?: newPlayerId()

            val outbound = Channel<String>(capacity = 64)
            val connection = Connection(playerId, outbound)
            val writer = launch {
                for (payload in outbound) send(Frame.Text(payload))
            }

            var attached = false
            try {
                attached = room.attach(playerId, name, connection)
                if (!attached) {
                    send(
                        Frame.Text(
                            appJson.encodeToString(
                                ServerMessage.serializer(),
                                ServerMessage.Error("that game is full or already underway"),
                            ),
                        ),
                    )
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "room unavailable"))
                    return@webSocket
                }

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = runCatching {
                        appJson.decodeFromString(ClientMessage.serializer(), frame.readText())
                    }.getOrNull() ?: continue
                    room.handle(playerId, message)
                }
            } finally {
                writer.cancel()
                outbound.close()
                if (attached) room.detach(playerId)
            }
        }

        if (hasBundledFrontend) {
            staticResources("/", "web") {
                // Anything that is not a real file falls through to the SPA shell.
                default("index.html")
            }
        } else {
            get("/") {
                call.respondText(
                    "Let It Ride backend is running, but no frontend bundle is packaged. " +
                        "Run the Vite dev server (pnpm --dir frontend dev) or build the Docker image.",
                )
            }
        }
    }
}
