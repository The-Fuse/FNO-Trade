package ob

import java.io.File

data class Config(
    val port: Int,
    val feed: String,
    val apiKey: String,
    val apiSecret: String,
    val redirectUri: String,
    val dataDir: File,
    val simStart: Int,
    val simSpeed: Double,
    /** When set, every page and API call asks for this password (browser sign-in box, any user name). */
    val password: String?,
)

/** Environment variables win over a local .env file. */
fun loadConfig(): Config {
    val file = File(".env").takeIf { it.exists() }?.readLines().orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"") }
    fun v(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: file[k]?.takeIf { it.isNotBlank() }

    val key = v("UPSTOX_API_KEY") ?: ""
    val port = v("PORT")?.toInt() ?: 8080
    val feed = v("FEED") ?: if (key.isNotEmpty()) "upstox" else "sim"
    require(feed == "sim" || feed == "upstox") { "FEED must be sim or upstox, was $feed" }
    val start = (v("SIM_START") ?: "10:45").split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    return Config(
        port = port,
        feed = feed,
        apiKey = key,
        apiSecret = v("UPSTOX_API_SECRET") ?: "",
        redirectUri = v("UPSTOX_REDIRECT_URI") ?: "http://localhost:$port/upstox/callback",
        dataDir = File(v("DATA_DIR") ?: "data"),
        simStart = start,
        simSpeed = v("SIM_SPEED")?.toDouble() ?: 30.0,
        password = v("APP_PASSWORD"),
    )
}
