package ob.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val COOKIE = "ob_session"
private const val YEAR_SECONDS = 365 * 24 * 3600

/**
 * Password lock for a public deploy. The password is asked once on a sign-in page; the browser then keeps a
 * cookie for a year. The cookie value is derived from the password, so changing APP_PASSWORD signs everyone out.
 * /health stays open for the host's checks.
 */
fun Application.passwordLock(password: String) {
    val token = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(password.toByteArray(), "HmacSHA256"))
        doFinal("ob-terminal session".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun signedIn(call: ApplicationCall) =
        call.request.cookies[COOKIE]?.let { MessageDigest.isEqual(it.toByteArray(), token.toByteArray()) } == true

    intercept(ApplicationCallPipeline.Plugins) {
        val call = context
        val path = call.request.path()
        if (path == "/health" || path == "/login" || signedIn(call)) return@intercept
        if (path.startsWith("/api/") || call.request.httpMethod != HttpMethod.Get) {
            call.respond(HttpStatusCode.Unauthorized)
        } else {
            call.respondRedirect("/login?next=" + URLEncoder.encode(call.request.uri, Charsets.UTF_8))
        }
        finish()
    }

    routing {
        get("/login") {
            if (signedIn(call)) call.respondRedirect(safeNext(call.request.queryParameters["next"]))
            else call.respondText(loginPage(call.request.queryParameters["e"] != null), ContentType.Text.Html)
        }
        post("/login") {
            val form = call.receiveParameters()
            val next = safeNext(form["next"])
            if (MessageDigest.isEqual(form["password"].orEmpty().toByteArray(), password.toByteArray())) {
                val https = call.request.headers["X-Forwarded-Proto"] == "https"
                val secure = if (https) "; Secure" else ""
                call.response.header(HttpHeaders.SetCookie, "$COOKIE=$token; Max-Age=$YEAR_SECONDS; Path=/; HttpOnly; SameSite=Lax$secure")
                call.respondRedirect(next)
            } else {
                delay(800) // slows guessing
                call.respondRedirect("/login?e=1&next=" + URLEncoder.encode(next, Charsets.UTF_8))
            }
        }
    }
}

/** Only same-site paths, so the sign-in page can't be used to bounce people elsewhere. */
private fun safeNext(next: String?) =
    next?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/login") } ?: "/"

private fun loginPage(wrong: Boolean) = """<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Sign in · OB Terminal</title>
<meta name="color-scheme" content="dark light">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Crect width='16' height='16' rx='4' fill='%230e121a'/%3E%3Cpath d='M4 11h3V5H4zM9 9h3V5H9z' fill='%2334d399'/%3E%3Cpath d='M9 11h3V9H9z' fill='%23f87171'/%3E%3C/svg%3E">
<script>try { const t = localStorage.getItem('ob:theme'); if (t) document.documentElement.dataset.theme = t; } catch (e) {}</script>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter+Tight:wght@400;500;600;700&display=swap">
<style>
:root, [data-theme="dark"] { --bg: #07090d; --panel: #0e121a; --panel-2: #141a25; --line: #1d2533; --line-2: #2a3446; --text: #e8ecf3; --muted: #8e98ab; --pos: #34d399; --neg: #f87171; --accent: #a78bfa; color-scheme: dark; }
[data-theme="light"] { --bg: #f3f5f8; --panel: #ffffff; --panel-2: #f2f5f9; --line: #e1e6ee; --line-2: #cfd7e3; --text: #0f1419; --muted: #566074; --pos: #059669; --neg: #dc2626; --accent: #7c3aed; color-scheme: light; }
* { box-sizing: border-box; }
html, body { margin: 0; min-height: 100%; background: var(--bg); color: var(--text); font: 15px/1.4 "Inter Tight", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
body { display: grid; place-items: center; min-height: 100vh; min-height: 100dvh; padding: 24px max(16px, env(safe-area-inset-left, 0px)); }
form { width: 100%; max-width: 340px; display: grid; gap: 14px; padding: 24px; border: 1px solid var(--line); border-radius: 16px; background: var(--panel); }
.brand { display: flex; align-items: center; gap: 10px; font-size: 18px; font-weight: 700; letter-spacing: -.01em; }
.mark { width: 26px; height: 26px; border-radius: 7px; background:
  linear-gradient(var(--pos), var(--pos)) 6px 7px / 5px 13px no-repeat,
  linear-gradient(var(--pos), var(--pos)) 14px 7px / 5px 7px no-repeat,
  linear-gradient(var(--neg), var(--neg)) 14px 14px / 5px 6px no-repeat,
  var(--panel-2); box-shadow: inset 0 0 0 1px var(--line-2); }
label { display: grid; gap: 6px; color: var(--muted); font-size: 13px; }
input { height: 44px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--bg); color: var(--text); font: inherit; font-size: 16px; }
input:focus { outline: 2px solid var(--accent); outline-offset: 1px; border-color: transparent; }
button { height: 44px; border: 0; border-radius: 10px; background: var(--accent); color: #fff; font: inherit; font-weight: 600; cursor: pointer; }
.err { margin: 0; color: var(--neg); font-size: 13px; }
.note { margin: 0; color: var(--muted); font-size: 12.5px; }
</style>
</head>
<body>
<form method="post" action="/login">
  <div class="brand"><span class="mark"></span>OB Terminal</div>
  <label for="pw">Password
    <input id="pw" name="password" type="password" autocomplete="current-password" required autofocus>
  </label>
  ${if (wrong) "<p class=\"err\" role=\"alert\">Wrong password. Try again.</p>" else ""}
  <input type="hidden" name="next" id="next">
  <button type="submit">Sign in</button>
  <p class="note">You stay signed in on this device for a year.</p>
</form>
<script>document.getElementById('next').value = (new URLSearchParams(location.search).get('next') || '/') + location.hash;</script>
</body>
</html>
"""
