package ob

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import ob.engine.Rules
import ob.web.module
import org.slf4j.LoggerFactory

fun main() {
    val cfg = loadConfig()
    Rules.configure(::envValue)
    val app = App(cfg)
    app.start()
    LoggerFactory.getLogger("ob").info("OB Terminal on http://localhost:{} (feed: {})", cfg.port, cfg.feed)
    embeddedServer(Netty, port = cfg.port) { module(app, cfg.password) }.start(wait = true)
}
