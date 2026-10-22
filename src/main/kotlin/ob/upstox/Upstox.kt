package ob.upstox

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import ob.Candle
import ob.Config
import ob.IST
import ob.Sectors
import ob.Stock
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream

class UpstoxAuthException(message: String) : Exception(message)

/**
 * Upstox REST: OAuth login, the instrument master, daily and 1-minute candles, and the
 * one-time URL for the market data websocket. Calls are paced under the 500/minute limit.
 */
class Upstox(private val cfg: Config) {
    private companion object {
        const val API = "https://api.upstox.com"
        const val PACE_MS = 125L
    }

    private val http = HttpClient(CIO) {
        expectSuccess = false
        engine { requestTimeout = 60_000 }
    }
    private val lenient = Json { ignoreUnknownKeys = true }
    private val tokenFile = File(cfg.dataDir, "upstox-token.json")
    private val gate = Mutex()
    private var lastCall = 0L

    @Volatile
    var token: String? = null
        private set

    @Volatile
    private var issuedMs = 0L

    /** Upstox ends every login at the next 3:30 AM IST. */
    fun loggedIn(nowMs: Long = System.currentTimeMillis()) = token != null && nowMs < expiry(issuedMs)

    private fun expiry(issued: Long): Long {
        val at = Instant.ofEpochMilli(issued).atZone(IST)
        val cutoff = LocalTime.of(3, 30)
        val day = if (at.toLocalTime() < cutoff) at.toLocalDate() else at.toLocalDate().plusDays(1)
        return day.atTime(cutoff).atZone(IST).toInstant().toEpochMilli()
    }

    fun loginUrl(): String =
        "$API/v2/login/authorization/dialog?response_type=code&client_id=${enc(cfg.apiKey)}&redirect_uri=${enc(cfg.redirectUri)}"

    /** Loads a saved token if Upstox hasn't expired it yet (tokens die at 3:30 AM IST). */
    fun loadToken(): Boolean {
        if (!tokenFile.exists()) return false
        val o = runCatching { Json.parseToJsonElement(tokenFile.readText()).jsonObject }.getOrNull() ?: return false
        val issued = o["issuedMs"]?.jsonPrimitive?.long ?: return false
        if (System.currentTimeMillis() >= expiry(issued)) return false
        token = o["token"]?.jsonPrimitive?.content
        issuedMs = issued
        return token != null
    }

    suspend fun exchange(code: String) {
        val r = http.submitForm(
            "$API/v2/login/authorization/token",
            formParameters = parameters {
                append("code", code)
                append("client_id", cfg.apiKey)
                append("client_secret", cfg.apiSecret)
                append("redirect_uri", cfg.redirectUri)
                append("grant_type", "authorization_code")
            },
        ) { accept(ContentType.Application.Json) }
        val text = r.bodyAsText()
        if (!r.status.isSuccess()) throw UpstoxAuthException("Upstox login failed (${r.status.value}): ${text.take(300)}")
        val t = Json.parseToJsonElement(text).jsonObject["access_token"]?.jsonPrimitive?.content
            ?: throw UpstoxAuthException("Upstox returned no access_token")
        val now = System.currentTimeMillis()
        token = t
        issuedMs = now
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(buildJsonObject { put("token", t); put("issuedMs", now) }.toString())
    }

    @Serializable
    private data class Instrument(
        val segment: String? = null,
        val instrument_type: String? = null,
        val instrument_key: String? = null,
        val underlying_type: String? = null,
        val underlying_key: String? = null,
        val underlying_symbol: String? = null,
        val name: String? = null,
        val lot_size: Int? = null,
        val expiry: Long? = null,
    )

    /** Every stock with NSE futures, as its cash-market instrument plus its nearest live future. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fnoStocks(): List<Stock> {
        val bytes: ByteArray = http.get("https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz").body()
        val all = GZIPInputStream(bytes.inputStream()).use { lenient.decodeFromStream<List<Instrument>>(it) }
        val names = all.filter { it.segment == "NSE_EQ" }.associate { it.instrument_key to it.name }
        val now = System.currentTimeMillis()
        return all
            .filter { it.segment == "NSE_FO" && it.instrument_type == "FUT" && it.underlying_type == "EQUITY" }
            .filter { it.underlying_key != null && it.underlying_symbol != null && it.instrument_key != null && (it.expiry ?: 0) > now }
            .groupBy { it.underlying_key!! }
            .map { (key, futs) ->
                val near = futs.minBy { it.expiry ?: Long.MAX_VALUE }
                val sym = near.underlying_symbol!!
                Stock(sym, names[key] ?: sym, key, Sectors.of(sym), lot = near.lot_size ?: 0, futKey = near.instrument_key!!)
            }
            .sortedBy { it.symbol }
    }

    /** Open interest at the last close before [session], from the future's daily candles. */
    suspend fun lastOi(futKey: String, session: LocalDate): Long? =
        getJson("$API/v3/historical-candle/${enc(futKey)}/days/1/${session.minusDays(1)}/${session.minusDays(10)}")["data"]
            ?.jsonObject?.get("candles")?.jsonArray.orEmpty()
            .maxByOrNull { OffsetDateTime.parse(it.jsonArray[0].jsonPrimitive.content).toEpochSecond() }
            ?.jsonArray?.getOrNull(6)?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()

    /** Dates NSE is shut, this year. */
    suspend fun holidays(): Set<LocalDate> =
        getJson("$API/v2/market/holidays")["data"]?.jsonArray.orEmpty().mapNotNull { e ->
            val h = e.jsonObject
            val closed = h["closed_exchanges"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            if ("NSE" in closed) h["date"]?.jsonPrimitive?.content?.let(LocalDate::parse) else null
        }.toSet()

    suspend fun daily(key: String, from: LocalDate, to: LocalDate): List<Candle> =
        candles("$API/v3/historical-candle/${enc(key)}/days/1/$to/$from")

    /** 1-minute candles of [date]: the intraday endpoint for today, the historical one for past sessions. */
    suspend fun minutes(key: String, date: LocalDate, today: Boolean): List<Candle> =
        if (today) candles("$API/v3/historical-candle/intraday/${enc(key)}/minutes/1")
        else candles("$API/v3/historical-candle/${enc(key)}/minutes/1/$date/$date")

    /** One-time wss:// URL for the market data feed. */
    suspend fun feedUrl(): String {
        val d = getJson("$API/v3/feed/market-data-feed/authorize")["data"]?.jsonObject
        return (d?.get("authorizedRedirectUri") ?: d?.get("authorized_redirect_uri"))?.jsonPrimitive?.content
            ?: throw IOException("Upstox feed authorize returned no URL")
    }

    private suspend fun candles(url: String): List<Candle> =
        getJson(url)["data"]?.jsonObject?.get("candles")?.jsonArray.orEmpty().map { e ->
            val a = e.jsonArray
            fun d(i: Int) = a[i].jsonPrimitive.content.toDouble()
            Candle(OffsetDateTime.parse(a[0].jsonPrimitive.content).toEpochSecond(), d(1), d(2), d(3), d(4), d(5).toLong())
        }.sortedBy { it.t }

    private suspend fun getJson(url: String): JsonObject {
        repeat(5) { attempt ->
            pace()
            val t = token ?: throw UpstoxAuthException("Not logged in to Upstox")
            val r = try {
                http.get(url) {
                    header(HttpHeaders.Authorization, "Bearer $t")
                    accept(ContentType.Application.Json)
                }
            } catch (e: IOException) {
                // a dropped or timed-out connection: wait and try again, up to the attempt limit
                if (attempt == 4) throw e
                delay(1000L shl attempt)
                return@repeat
            }
            when {
                r.status == HttpStatusCode.Unauthorized -> throw UpstoxAuthException("Upstox session expired; log in again")
                r.status == HttpStatusCode.TooManyRequests -> delay(1000L shl attempt)
                r.status.isSuccess() -> return Json.parseToJsonElement(r.bodyAsText()).jsonObject
                else -> throw IOException("Upstox ${r.status.value} on ${url.substringAfter(API)}: ${r.bodyAsText().take(300)}")
            }
        }
        throw IOException("Upstox kept rate-limiting ${url.substringAfter(API)}")
    }

    private suspend fun pace() = gate.withLock {
        val wait = lastCall + PACE_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
