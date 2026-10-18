package ob.web

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ob.App
import java.io.File

val json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

fun Application.module(app: App, password: String? = null) {
    install(ContentNegotiation) { json(json) }
    install(SSE)
    if (password != null) passwordLock(password)

    routing {
        get("/health") { call.respondText("ok") }

        // run from the project folder, the UI is served straight from source so edits show on reload
        val source = File("src/main/resources/static")
        if (source.isDirectory) staticFiles("/", source) else staticResources("/", "static")

        get("/api/status") { call.respond(app.status.value) }

        get("/api/board") {
            val b = app.engine.value?.board?.value
            if (b == null) call.respond(HttpStatusCode.ServiceUnavailable, app.status.value) else call.respond(b)
        }

        get("/api/symbols") { call.respond(app.engine.value?.symbols().orEmpty()) }

        get("/api/universe") { call.respond(app.engine.value?.universe().orEmpty()) }

        get("/api/flow") {
            val e = app.engine.value
            if (e == null) call.respond(HttpStatusCode.ServiceUnavailable, app.status.value) else call.respond(e.flow())
        }

        get("/api/chart/{symbol}") {
            val c = app.engine.value?.chart(call.parameters["symbol"].orEmpty().uppercase())
            if (c == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown symbol")) else call.respond(c)
        }

        get("/login/upstox") {
            val api = app.upstox
            if (api == null) call.respondText("Upstox feed is off (FEED=sim)", status = HttpStatusCode.BadRequest)
            else call.respondRedirect(api.loginUrl())
        }

        get("/upstox/callback") {
            val code = call.request.queryParameters["code"]
            if (code == null) {
                call.respondText("Missing ?code from Upstox", status = HttpStatusCode.BadRequest)
                return@get
            }
            try {
                app.onUpstoxCode(code)
                call.respondRedirect("/")
            } catch (e: Exception) {
                call.respondText("Upstox login failed: ${e.message}", status = HttpStatusCode.BadGateway)
            }
        }

        // One stream per open page: "status", then the board every minute and quotes every ~2 s.
        sse("/api/stream") { stream(app) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun ServerSSESession.stream(app: App) = coroutineScope {
    val out = Channel<ServerSentEvent>(Channel.BUFFERED)
    launch { app.status.collect { out.send(ServerSentEvent(json.encodeToString(it), event = "status")) } }
    launch {
        app.engine.filterNotNull().flatMapLatest { it.board.filterNotNull() }
            .collect { out.send(ServerSentEvent(json.encodeToString(it), event = "board")) }
    }
    launch {
        app.engine.filterNotNull().flatMapLatest { it.quotes.filterNotNull() }
            .collect { out.send(ServerSentEvent(json.encodeToString(it), event = "quotes")) }
    }
    launch {
        while (true) {
            delay(15_000)
            out.send(ServerSentEvent(comments = "ping"))
        }
    }
    for (e in out) send(e)
}
