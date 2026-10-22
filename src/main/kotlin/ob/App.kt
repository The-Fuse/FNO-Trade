package ob

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ob.engine.Engine
import ob.engine.Rules
import ob.engine.Strength
import ob.market.Series
import ob.sim.SimMarket
import ob.upstox.Upstox
import ob.upstox.UpstoxAuthException
import ob.upstox.UpstoxFeed
import ob.web.json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

@Serializable
data class Status(
    val feed: String,
    val state: String,
    val message: String = "",
    val needsLogin: Boolean = false,
    val rules: Map<String, String> = Rules.dials(),   // the strategy's dials, so the UI describes what it's doing
)

/** Picks the feed, loads the session, and keeps one [Engine] running for the UI. */
class App(private val cfg: Config) {
    private val log = LoggerFactory.getLogger("app")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipeline: Job? = null

    @Volatile
    private var holidays: Set<LocalDate> = emptySet()

    val status = MutableStateFlow(Status(cfg.feed, "starting", "Starting"))
    val engine = MutableStateFlow<Engine?>(null)
    val upstox: Upstox? = if (cfg.feed == "upstox") Upstox(cfg) else null

    fun start() {
        val api = upstox
        when {
            api == null -> runSim()
            cfg.apiKey.isBlank() || cfg.apiSecret.isBlank() ->
                status.value = Status("upstox", "error", "Set UPSTOX_API_KEY and UPSTOX_API_SECRET in .env, then restart")
            else -> {
                if (api.loadToken()) runUpstox(api) else askLogin("Connect your Upstox account to load market data.")
                watchSessions(api)
            }
        }
    }

    suspend fun onUpstoxCode(code: String) {
        val api = upstox ?: error("Upstox feed is not enabled")
        api.exchange(code)
        runUpstox(api)
    }

    private fun askLogin(message: String) {
        status.value = Status("upstox", "login", message, needsLogin = true)
    }

    private fun loading(message: String) {
        status.value = Status(cfg.feed, "loading", message)
    }

    /** The simulator plays a whole day at SIM_SPEED; a minute after its close a new simulated day starts. */
    private fun runSim() {
        pipeline = scope.launch {
            var seed = 11L
            while (isActive) {
                simDay(seed)
                seed = System.currentTimeMillis()
            }
        }
    }

    private suspend fun simDay(seed: Long) = coroutineScope {
        val date = sessionDate(System.currentTimeMillis(), emptySet())
        val sim = SimMarket(Sectors.simStocks(), date, cfg.simStart, cfg.simSpeed, seed)
        val stocks = Sectors.simStocks().map { it.copy(lot = sim.lots[it.symbol] ?: 0) }
        val series = stocks.associate { it.symbol to Series(it, sessionOpenSec(date), sessionCloseSec(date)) }
        sim.advanceTo(sim.nowMs(), series)
        val ctx = sim.daily.mapNotNull { (sym, bars) ->
            Strength.context(bars)?.let { sym to it.copy(prevOi = sim.prevOi[sym]) }
        }.toMap()
        val speed = if (cfg.simSpeed % 1.0 == 0.0) cfg.simSpeed.toLong().toString() else cfg.simSpeed.toString()
        val label = "Simulated ${speed}×"
        val eng = Engine(stocks, series, ctx, date, sim, { marketState(it, date, label) })
        eng.start(this)
        engine.value = eng
        status.value = Status("sim", "ready", label)
        launch { sim.run(series) }
        while (sim.nowMs() < sessionCloseSec(date) * 1000) delay(1_000)
        delay(60_000)
        coroutineContext.cancelChildren()
    }

    private fun runUpstox(api: Upstox) {
        pipeline?.cancel()
        engine.value = null
        pipeline = scope.launch {
            try {
                loading("Loading the F&O stock list")
                val stocks = api.fnoStocks()
                holidays = try {
                    api.holidays()
                } catch (e: UpstoxAuthException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Holiday list unavailable, assuming weekdays trade: {}", e.message)
                    emptySet()
                }
                val nowMs = System.currentTimeMillis()
                val session = sessionDate(nowMs, holidays)
                val today = session == istDate(nowMs)
                log.info("{} F&O stocks, session {}", stocks.size, session)

                val daily = dailyHistory(api, stocks, session)
                val prevOi = futuresOi(api, stocks, session)
                val ctx = stocks.mapNotNull { st ->
                    daily[st.symbol]?.let(Strength::context)?.let { st.symbol to it.copy(prevOi = prevOi[st.symbol]) }
                }.toMap()

                val series = stocks.associate { it.symbol to Series(it, sessionOpenSec(session), sessionCloseSec(session)) }
                stocks.forEachIndexed { i, st ->
                    if (i % 10 == 0) loading("Loading the ${session.dayOfMonth} ${session.month.name.lowercase().replaceFirstChar { it.uppercase() }} session: $i/${stocks.size}")
                    val now = System.currentTimeMillis() / 1000
                    api.minutes(st.key, session, today).forEach { series.getValue(st.symbol).upsert(it, now) }
                }

                val planFile = File(cfg.dataDir, "plans-$session.json")
                val eng = Engine(stocks, series, ctx, session, SystemClock, { marketState(it, session) }, planFile)
                eng.start(this)
                engine.value = eng
                status.value = Status("upstox", "ready", "Upstox")

                if (today && istMinuteOfDay(System.currentTimeMillis()) < CLOSE_MIN) {
                    val byKey = stocks.associate { it.key to series.getValue(it.symbol) }
                    val byFut = stocks.filter { it.futKey.isNotEmpty() }.associate { it.futKey to series.getValue(it.symbol) }
                    UpstoxFeed(api, byKey, byFut).run { istMinuteOfDay(System.currentTimeMillis()) <= CLOSE_MIN }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpstoxAuthException) {
                log.warn(e.message)
                askLogin(e.message ?: "Log in to Upstox again.")
            } catch (e: Exception) {
                log.error("Upstox pipeline failed", e)
                status.value = Status("upstox", "error", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Keeps a server that runs for days on the right session: after Upstox's 3:30 AM logout it asks to connect
     * again, and from 9:00 on a trading day it reloads into that day.
     */
    private fun watchSessions(api: Upstox) = scope.launch {
        while (isActive) {
            delay(30_000)
            if (status.value.state != "ready") continue
            if (!api.loggedIn()) {
                askLogin("Upstox logs everyone out at 3:30 AM. Connect again to load today's market.")
                continue
            }
            val shown = engine.value?.session ?: continue
            if (sessionDate(System.currentTimeMillis(), holidays) != shown) runUpstox(api)
        }
    }

    /** Yesterday's open interest of each stock's near-month future, cached like the daily history. */
    private suspend fun futuresOi(api: Upstox, stocks: List<Stock>, session: LocalDate): Map<String, Long> {
        val file = File(cfg.dataDir, "oi-$session.json")
        if (file.exists()) {
            runCatching { return json.decodeFromString<Map<String, Long>>(file.readText()) }
                .onFailure { log.warn("Ignoring unreadable {}: {}", file, it.message) }
        }
        val out = LinkedHashMap<String, Long>()
        stocks.filter { it.futKey.isNotEmpty() }.forEachIndexed { i, st ->
            if (i % 10 == 0) loading("Loading futures open interest: $i/${stocks.size}")
            api.lastOi(st.futKey, session)?.let { out[st.symbol] = it }
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(out))
        return out
    }

    /** 50+ sessions of daily candles per stock, fetched once per session date and cached in data/. */
    private suspend fun dailyHistory(api: Upstox, stocks: List<Stock>, session: LocalDate): Map<String, List<Candle>> {
        val file = File(cfg.dataDir, "daily-$session.json")
        if (file.exists()) {
            runCatching { return json.decodeFromString<Map<String, List<Candle>>>(file.readText()) }
                .onFailure { log.warn("Ignoring unreadable {}: {}", file, it.message) }
        }
        val out = LinkedHashMap<String, List<Candle>>()
        stocks.forEachIndexed { i, st ->
            if (i % 10 == 0) loading("Loading 50-session history (first start of the day): $i/${stocks.size}")
            out[st.symbol] = api.daily(st.key, session.minusDays(110), session.minusDays(1))
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(out))
        return out
    }
}
