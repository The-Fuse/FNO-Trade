package ob.backtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import ob.Candle
import ob.IST
import ob.Stock
import ob.engine.Engine
import ob.engine.PlanState
import ob.engine.Rules
import ob.engine.Strength
import ob.istDate
import ob.isTradingDay
import ob.loadConfig
import ob.market.Series
import ob.marketState
import ob.sessionCloseSec
import ob.sessionOpenSec
import ob.upstox.Upstox
import ob.web.json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Replays past sessions through the live engine, minute by minute, from Upstox's 1-minute history: the same picks,
 * plans and outcomes the server would have produced on those days. Each filled plan is then sized for an account
 * (a % of current equity at risk to the stop, intraday margin at ~20% of the position, Upstox-style charges).
 *
 *   ./gradlew backtest --args="--days 40 --capital 10000 --risk 2"
 *
 *   --dates 2026-10-07,2026-10-08       exact sessions instead of the last N
 *   --set MIN_STOP_PCT=0.3,TARGET=t1    strategy dials to try (names as in Rules)
 *   --check 10                          hold the newest 10 sessions out: tune on the rest, then see if it held up
 *   --capital 10000,50000 --risk 1,2    every combination
 *   --minrisk 250                       skip a trade when, after the margin cap, it could risk under ₹250 (charges would eat it)
 *   --fetch                             only download and cache the data
 *
 * Raw candles are cached under data/cache (downloaded once; needs the Upstox login the server saved), and each
 * replayed day under data/replays/<dials>/, so a new idea costs seconds, not downloads. Every run is appended
 * to data/experiments.txt.
 */

private const val MARGIN = 0.20          // intraday margin as a share of position value
private const val MAX_BROKERAGE = 20.0   // per executed order, or 0.1% if lower
private val CACHE = File("data/cache")

private class Trade(val day: LocalDate, val ps: PlanState) {
    val p = ps.plan
    val buy = p.side == "bull"
    var qty = 0
    var risked = 0.0
    var gross = 0.0
    var costs = 0.0
    var skipped: String? = null
    val net get() = gross - costs
}

fun main(argv: Array<String>) = runBlocking {
    val args = HashMap<String, String>()
    var i = 0
    while (i < argv.size) {
        val k = argv[i].removePrefix("--")
        if (i + 1 < argv.size && !argv[i + 1].startsWith("--")) { args[k] = argv[i + 1]; i += 2 } else { args[k] = ""; i++ }
    }
    val capitals = (args["capital"] ?: "10000").split(',').map { it.toDouble() }
    val risks = (args["risk"] ?: "1").split(',').map { it.toDouble() }
    val minRisk = args["minrisk"]?.toDouble() ?: 0.0
    val overrides = args["set"].orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    Rules.configure { overrides[it] }
    val dials = Rules.describe()

    val data = Data(Upstox(loadConfig()))
    val dates = args["dates"]?.split(',')?.map(LocalDate::parse) ?: data.lastSessions(args["days"]?.toInt() ?: 10)
    println("Strategy: $dials")
    println("Sessions: ${dates.first()} → ${dates.last()} (${dates.size})")
    data.ensure(dates)
    if ("fetch" in args) return@runBlocking

    val replays = replayAll(data, dates, Rules.signature())
    val check = args["check"]?.toInt() ?: 0
    val sets = if (check in 1 until dates.size) {
        listOf("tune" to dates.dropLast(check), "check" to dates.takeLast(check), "all" to dates)
    } else {
        listOf("all" to dates)
    }
    fun trades(ds: List<LocalDate>) = ds.flatMap { d -> replays.getValue(d).map { Trade(d, it) } }
    fun filled(ts: List<Trade>) = ts.filter { it.ps.filledT != null && it.ps.exitT != null }

    val out = StringBuilder()
    out.appendLine()
    out.appendLine("STRATEGY $dials  ·  ${dates.first()} → ${dates.last()}  ·  margin ${(MARGIN * 100).toInt()}% · Upstox-style charges" + (if (minRisk > 0) " · min risk ${inr(minRisk)}" else ""))
    out.appendLine(Stats.header())
    val log = File("data/experiments.txt")
    for (c in capitals) for (r in risks) for ((label, ds) in sets) {
        val ts = trades(ds)
        simulate(filled(ts), c, r, minRisk)
        val st = Stats.of(label, ds.size, ts, c)
        out.appendLine(st.line(c, r))
        log.appendText("${LocalDate.now()}  ${dates.first()}→${dates.last()}  $dials${if (minRisk > 0) " minrisk=${minRisk.toInt()}" else ""}  |  ${st.line(c, r)}\n")
    }
    print(out)
    if (capitals.size == 1 && risks.size == 1) {
        val ts = trades(dates)
        simulate(filled(ts), capitals[0], risks[0], minRisk)
        val f = File("data/backtest-${dates.first()}-${dates.last()}.txt")
        f.writeText(report(dates, ts, capitals[0], risks[0], dials))
        println("\nTrade-by-trade log: $f")
    }
}

/* ---------- data: downloaded once, then read from data/cache ---------- */

private class Data(private val api: Upstox) {
    private val loggedIn by lazy { api.loadToken() }
    private fun need() = check(loggedIn) { "No valid Upstox login. Start the server, click Connect Upstox, then run this again." }
    private var stocks: List<Stock>? = null

    suspend fun stocks(): List<Stock> = stocks ?: load(File(CACHE, "stocks.json")) { need(); api.fnoStocks() }.also { stocks = it }

    suspend fun holidays(): Set<LocalDate> =
        load<List<String>>(File(CACHE, "holidays.json")) { need(); api.holidays().map { it.toString() } }.map(LocalDate::parse).toSet()

    suspend fun lastSessions(n: Int): List<LocalDate> {
        val h = holidays()
        val out = ArrayList<LocalDate>()
        var d = istDate(System.currentTimeMillis()).minusDays(1)
        while (out.size < n) {
            if (isTradingDay(d, h)) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /** Daily candles per symbol over [from, to], one request per stock. */
    suspend fun daily(from: LocalDate, to: LocalDate): Map<String, List<Candle>> =
        load(File(CACHE, "daily-$from-$to.json.gz")) {
            need()
            val st = stocks()
            println("Downloading daily history $from → $to for ${st.size} stocks…")
            val out = LinkedHashMap<String, List<Candle>>()
            st.forEachIndexed { i, s ->
                if (i % 50 == 0) println("  $i/${st.size}")
                out[s.symbol] = api.daily(s.key, from, to)
            }
            out
        }

    /** 1-minute candles per symbol for one session. */
    suspend fun minutes(day: LocalDate): Map<String, List<Candle>> =
        load(File(CACHE, "minutes/$day.json.gz")) {
            need()
            val st = stocks()
            println("Downloading $day minutes for ${st.size} stocks…")
            val out = LinkedHashMap<String, List<Candle>>()
            st.forEachIndexed { i, s ->
                if (i % 50 == 0) println("  $i/${st.size}")
                out[s.symbol] = api.minutes(s.key, day, today = false)
            }
            out
        }

    fun dailyRange(dates: List<LocalDate>) = dates.first().minusDays(120) to dates.last().minusDays(1)

    suspend fun ensure(dates: List<LocalDate>) {
        stocks()
        dailyRange(dates).let { (a, b) -> daily(a, b) }
        for (d in dates) minutes(d)
    }

    private suspend inline fun <reified T> load(f: File, produce: () -> T): T {
        if (f.exists()) return json.decodeFromString(read(f))
        val v = produce()
        f.parentFile?.mkdirs()
        write(f, json.encodeToString(v))
        return v
    }

    private fun read(f: File) = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } else f.readText()
    private fun write(f: File, s: String) {
        if (f.name.endsWith(".gz")) GZIPOutputStream(f.outputStream()).bufferedWriter().use { it.write(s) } else f.writeText(s)
    }
}

/* ---------- replay: each session through the engine, cached per strategy ---------- */

private suspend fun replayAll(data: Data, dates: List<LocalDate>, signature: String): Map<LocalDate, List<PlanState>> = coroutineScope {
    val dir = File("data/replays", signature.replace(Regex("[^A-Za-z0-9=._-]"), "_"))
    val stocks = data.stocks()
    val daily = data.dailyRange(dates).let { (a, b) -> data.daily(a, b) }
    val gate = Semaphore(4)
    val todo = dates.count { !File(dir, "$it.json").exists() }
    if (todo > 0) println("Replaying $todo of ${dates.size} sessions…")
    dates.map { day ->
        async(Dispatchers.Default) {
            gate.withPermit {
                val f = File(dir, "$day.json")
                if (f.exists()) {
                    day to json.decodeFromString<List<PlanState>>(f.readText())
                } else {
                    val plans = replay(stocks, daily, data.minutes(day), day)
                    f.parentFile?.mkdirs()
                    f.writeText(json.encodeToString(plans))
                    println("  $day: ${plans.size} plans, ${plans.count { it.filledT != null }} filled")
                    day to plans
                }
            }
        }
    }.awaitAll().toMap()
}

/** Candles are revealed a minute at a time and the board is rebuilt each minute, as on a live day. */
private fun replay(stocks: List<Stock>, daily: Map<String, List<Candle>>, bars: Map<String, List<Candle>>, day: LocalDate): List<PlanState> {
    val dayStart = day.atStartOfDay(IST).toEpochSecond()
    val ctx = stocks.mapNotNull { st ->
        daily[st.symbol]?.filter { it.t < dayStart }?.let(Strength::context)?.let { st.symbol to it }
    }.toMap()
    val open = sessionOpenSec(day)
    val close = sessionCloseSec(day)
    val series = stocks.associate { it.symbol to Series(it, open, close) }
    var nowMs = open * 1000
    val engine = Engine(stocks, series, ctx, day, { nowMs }, { marketState(it, day) })
    val next = stocks.associate { it.symbol to 0 }.toMutableMap()

    var plans = emptyList<PlanState>()
    var m = open + 60
    while (m <= close) {
        nowMs = m * 1000 + 1
        for (st in stocks) {
            val list = bars[st.symbol] ?: continue
            var i = next.getValue(st.symbol)
            while (i < list.size && list[i].t < m) series.getValue(st.symbol).upsert(list[i++], m)
            next[st.symbol] = i
            series.getValue(st.symbol).closeBefore(m)
        }
        plans = engine.computeBoard(nowMs).plans
        m += 60
    }
    return plans
}

/* ---------- sizing: what a real account could have done with those plans ---------- */

/** Sizes each filled plan in time order: risk % of current equity, capped by the margin still free. */
private fun simulate(filled: List<Trade>, capital: Double, riskPct: Double, minRisk: Double = 0.0) {
    val open = ArrayList<Pair<Trade, Double>>()   // trade, margin it blocks
    for (t in filled.sortedBy { it.ps.filledT }) {
        val at = t.ps.filledT!!
        open.removeAll { (o, _) -> o.ps.exitT!! <= at }
        val realised = filled.filter { it.qty > 0 && it.ps.exitT!! <= at }.sumOf { it.net }
        val equity = capital + realised
        val used = open.sumOf { it.second }
        val fill = t.ps.fill!!
        val perShare = abs(fill - t.p.stop)
        val byRisk = floor(equity * riskPct / 100 / perShare).toInt()
        val byMargin = floor((equity - used) / (fill * MARGIN)).toInt()
        t.qty = max(0, min(byRisk, byMargin))
        if (t.qty < 1) {
            t.skipped = if (byRisk < 1) "risk too small for 1 share" else "not enough free margin"
            continue
        }
        if (t.qty * perShare < minRisk) {
            t.qty = 0
            t.skipped = "could only risk ${inr(min(byRisk, byMargin) * perShare)}, under ${inr(minRisk)}"
            continue
        }
        if (byMargin < byRisk) t.skipped = "cut from $byRisk to ${t.qty} shares by margin"
        t.risked = t.qty * perShare
        legs(t, fill)
        open += t to t.qty * fill * MARGIN
    }
}

/** Gross result and charges from the actual legs: half at T1 when the plan scales out, the rest at the exit. */
private fun legs(t: Trade, fill: Double) {
    val half = if (Rules.TARGET == "half" && t.ps.t1Hit && t.qty >= 2) t.qty / 2 else 0
    val rest = t.qty - half
    val exit = t.ps.exitPrice ?: fill
    val dir = if (t.buy) 1 else -1
    t.gross = dir * (half * (t.p.t1 - fill) + rest * (exit - fill))
    val entryValue = t.qty * fill
    val exitLegs = listOfNotNull(if (half > 0) half * t.p.t1 else null, rest * exit)
    val exitValue = exitLegs.sum()
    val brokerage = (listOf(entryValue) + exitLegs).sumOf { min(MAX_BROKERAGE, it * 0.001) }
    val buyValue = if (t.buy) entryValue else exitValue
    val sellValue = if (t.buy) exitValue else entryValue
    val turnover = buyValue + sellValue
    val stt = sellValue * 0.00025
    val exchange = turnover * 0.0000297
    val sebi = turnover * 0.000001
    val stamp = buyValue * 0.00003
    val gst = (brokerage + exchange + sebi) * 0.18
    t.costs = brokerage + stt + exchange + sebi + stamp + gst
}

/* ---------- results ---------- */

private fun inr(x: Double) = (if (x < 0) "−₹" else "₹") + "%,.0f".format(abs(x))
private fun hhmm(sec: Long?) = sec?.let { Instant.ofEpochSecond(it).atZone(IST).toLocalTime().toString().take(5) } ?: "  -  "

private class Stats(
    val label: String, val days: Int, val plans: Int, val filled: Int, val rPerPlan: Double, val taken: Int, val winPct: Double,
    val gross: Double, val costs: Double, val net: Double, val ret: Double, val worst: Double,
) {
    fun line(capital: Double, risk: Double) = "%-5s %4d %5d %6d %+7.3f %6d %5.0f%% %10s %10s %10s %+8.2f%% %7.1f%%  %s %.1f%%".format(
        label, days, plans, filled, rPerPlan, taken, winPct, inr(gross), inr(costs), inr(net), ret, worst, inr(capital), risk,
    )

    companion object {
        fun header() = "%-5s %4s %5s %6s %7s %6s %6s %10s %10s %10s %9s %8s  %s".format(
            "set", "days", "plans", "filled", "R/plan", "taken", "wins", "gross", "costs", "net", "return", "worst", "capital risk",
        )

        fun of(label: String, days: Int, all: List<Trade>, capital: Double): Stats {
            val filled = all.filter { it.ps.filledT != null }
            val taken = all.filter { it.qty > 0 }
            var equity = capital
            var peak = capital
            var dd = 0.0
            taken.sortedBy { it.ps.exitT }.forEach {
                equity += it.net
                peak = max(peak, equity)
                dd = max(dd, (peak - equity) / peak)
            }
            return Stats(
                label, days, all.size, filled.size,
                if (filled.isEmpty()) 0.0 else filled.sumOf { it.ps.r ?: 0.0 } / filled.size,
                taken.size, if (taken.isEmpty()) 0.0 else taken.count { it.net > 0 } * 100.0 / taken.size,
                taken.sumOf { it.gross }, taken.sumOf { it.costs }, taken.sumOf { it.net }, (equity / capital - 1) * 100, dd * 100,
            )
        }
    }
}

private fun report(dates: List<LocalDate>, all: List<Trade>, capital: Double, riskPct: Double, dials: String): String {
    val out = StringBuilder()
    fun line(s: String = "") = out.appendLine(s)
    line("BACKTEST  ${dates.first()} → ${dates.last()}  ·  strategy $dials  ·  capital ${inr(capital)}  ·  risk ${riskPct}% of equity per trade")
    line("Intraday margin assumed ${(MARGIN * 100).toInt()}% of position · charges: brokerage ₹20 or 0.1%/order, STT, exchange, SEBI, stamp, GST")
    var equity = capital
    for (day in dates) {
        val ts = all.filter { it.day == day }
        line()
        line("── $day ── ${ts.size} plans: " + ts.groupingBy { it.ps.status }.eachCount().entries.joinToString { "${it.value} ${it.key}" })
        ts.filter { it.ps.filledT != null }.sortedBy { it.ps.filledT }.forEach { t ->
            val side = if (t.buy) "BUY " else "SELL"
            val res = if (t.qty < 1) "skipped: ${t.skipped}" else {
                equity += t.net
                "${t.qty} sh · risked ${inr(t.risked)} · gross ${inr(t.gross)} · costs ${inr(t.costs)} · net ${inr(t.net)}" +
                    (t.skipped?.let { "  ($it)" } ?: "")
            }
            line("  ${hhmm(t.ps.filledT)}→${hhmm(t.ps.exitT)} $side %-11s in %.2f stop %.2f out %s  %-9s %+.2fR  %s".format(
                t.p.symbol, t.ps.fill, t.p.stop, t.ps.exitPrice?.let { "%.2f".format(it) } ?: "-", t.ps.status, t.ps.r ?: 0.0, res))
        }
        line("  end of day equity ${inr(equity)}")
    }
    line()
    line(Stats.header())
    line(Stats.of("all", dates.size, all, capital).line(capital, riskPct))
    return out.toString()
}
