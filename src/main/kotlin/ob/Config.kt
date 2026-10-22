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
    /** When set, the site shows a sign-in page and remembers the device for a year (see web/Login.kt). */
    val password: String?,
)

/** A setting from the environment, else from a local .env file. */
fun envValue(k: String): String? = System.getenv(k)?.takeIf { it.isNotBlank() } ?: dotEnv[k]?.takeIf { it.isNotBlank() }

private val dotEnv: Map<String, String> by lazy {
    File(".env").takeIf { it.exists() }?.readLines().orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"") }
}

/** Environment variables win over a local .env file. */
fun loadConfig(): Config {
    val v = ::envValue

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
