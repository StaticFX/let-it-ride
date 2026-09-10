package com.letitride.account

import com.letitride.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The API a server with no accounts configured actually serves.
 *
 * Which is: all of it, answering honestly. The client asks one question on
 * every load — "can anybody sign in here?" — and a server that cannot has to
 * say so rather than 404, because a 404 is indistinguishable from an older
 * server and the front door would have to guess. Everything past that point
 * refuses, and refuses in a way a browser can read.
 */
class AccountRoutesTest {

    @Test
    fun `the front door is told there is no sign-in rather than left to guess`() = testApplication {
        application { module() }

        val response = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("\"enabled\":false" in body, "was: $body")
        assertTrue("account" !in body, "nobody is signed in, so there is nobody to describe: $body")
    }

    @Test
    fun `health says whether this server keeps anything`() = testApplication {
        application { module() }
        assertTrue("\"accounts\":false" in client.get("/api/health").bodyAsText())
    }

    @Test
    fun `there is no door to walk through`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/auth/login").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/auth/callback?code=x&state=y").status)
    }

    @Test
    fun `asking for your own stats without an account is refused and not invented`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/stats/me").status)
    }

    @Test
    fun `an empty leaderboard is an empty leaderboard and not an error`() = testApplication {
        application { module() }
        val response = client.get("/api/stats/leaderboard")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"rows":[]}""", response.bodyAsText())
    }

    @Test
    fun `signing out of nothing is not a failure`() = testApplication {
        application { module() }
        // No session plugin is installed at all on this server, so this is also
        // the test that clearing one cannot throw when there is none to clear.
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/logout").status)
    }

    @Test
    fun `a table still opens under a name a guest typed`() = testApplication {
        application { module() }
        val response = client.post("/api/rooms") {
            header("Content-Type", "application/json")
            setBody("""{"name":"devin"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue("roomCode" in response.bodyAsText())
    }
}
