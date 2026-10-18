package ob.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ob.Candle
import ob.DayContext
import ob.MarketClock
import ob.MarketState
import ob.Stock
import ob.bucket
import ob.istMinuteOfDay
import ob.market.Series
import ob.market.aggregate
import ob.r2
import ob.sessionOpenSec
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/** One stock as the board sees it this minute. */
@Serializable
data class Row(
    val symbol: String,
    val name: String,
    val sector: String,
    val lot: Int,
    val side: String,              // "bull" or "bear"
    val ltp: Double,
    val chg: Double,
    val chgAbs: Double,
    val score: Double,
    val rank: Int,                 // on its side; 0 when the stock is flat
    val sideCount: Int,
    val prevRank: Int? = null,     // rank a minute ago; null = new on the board
    val pct: Double,               // stronger than this % of the whole board (bearish read from the low end)
    val conv: Int,
    val rule: String = "",
    val pick: Int? = null,
    val event: Boolean = false,
    val vwap: Double,
    val buildUp: String? = null,   // futures open interest read: long/short build-up, short covering, long unwinding
    val oiChg: Double? = null,     // futures open interest change since yesterday, %
    val delta: Long = 0,           // volume in minutes that closed up minus minutes that closed down
    val setup: Setup? = null,
    val advice: Advice,
    val path: List<Double> = emptyList(),   // % change at each 5-minute close
    val profile: VolProfile? = null,
    val bars: List<Candle>? = null,         // 5-minute candles, sent for picks only (their tickets chart them)
)

@Serializable
data class UniverseStock(val symbol: String, val name: String, val sector: String, val lot: Int)

@Serializable
data class SectorRow(val name: String, val score: Double, val chg: Double, val up: Int, val down: Int, val moves: List<Double>)

@Serializable
data class Breadth(val up: Int, val down: Int, val flat: Int)

@Serializable
data class Dot(val s: String, val c: Double)

@Serializable
data class Board(
    val t: Long,
    val session: String,
    val market: MarketState,
    val breadth: Breadth,
    val bullCount: Int,
    val bearCount: Int,
    val sectors: List<SectorRow>,
    val bullish: List<Row>,
    val bearish: List<Row>,
    val dots: List<Dot>,
    val plans: List<PlanState>,
    val scorecard: Scorecard,
)

/** Latest price and % change per symbol, pushed every couple of seconds between board refreshes. */
@Serializable
data class Quotes(val t: Long, val q: Map<String, List<Double>>)

/** Every stock's % change at each 5-minute close since the open. */
@Serializable
data class FlowData(val start: Long, val step: Long, val t: Long, val s: Map<String, List<Double>>)

@Serializable
data class Chart(
    val row: Row,
    val prevClose: Double,
    val pdh: Double,
    val pdl: Double,
    val dayOpen: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val volume: Long,
    val candles: List<Candle>,      // 5-minute, the last one possibly still forming
    val formingT: Long? = null,
    val vwap: List<Pt>,
    val zones: List<Zone>,
    val liquidity: List<Liquidity>,
    val plan: PlanState? = null,
)

/**
 * Every minute: closes the minute's candles, ranks all F&O stocks by strength, draws order blocks
 * and levels for the strongest [TOP] on each side, picks the top three per side, and freezes a
 * trade plan for every pick with a tradeable setup.
 */
class Engine(
    private val stocks: List<Stock>,
    private val series: Map<String, Series>,
    private val ctx: Map<String, DayContext>,
    val session: LocalDate,
    private val clock: MarketClock,
    private val market: (Long) -> MarketState,
    planFile: File? = null,
) {
    companion object {
        const val TOP = 20
        private val TRADE = setOf("BUY", "SELL")
    }

    private val log = LoggerFactory.getLogger("engine")
    private val bySymbol = stocks.associateBy { it.symbol }
    private val book = PlanBook(planFile)
    private var prevRanks = emptyMap<String, Int>()

    @Volatile
    private var snap: Snap? = null

    @Volatile
    private var flowCache: FlowData? = null

    val board = MutableStateFlow<Board?>(null)
    val quotes = MutableStateFlow<Quotes?>(null)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
        launch {
            var last = Long.MIN_VALUE
            while (isActive) {
                val now = clock.nowMs()
                val minute = Math.floorDiv(now, 60_000L)
                if (minute != last) {
                    last = minute
                    series.values.forEach { it.closeBefore(minute * 60) }
                    try {
                        board.value = computeBoard(now)
                    } catch (e: Exception) {
                        log.error("Board update failed", e)
                    }
                }
                delay(100)
            }
        }
        launch {
            while (isActive) {
                val now = clock.nowMs()
                quotes.value = Quotes(now / 1000, live().associate { it.stock.symbol to listOf(r2(it.ltp), r2(it.chg)) })
                delay(2000)
            }
        }
    }

    private class Live(val stock: Stock, val cx: DayContext, val series: Series, val ltp: Double) {
        val chg = (ltp / cx.prevClose - 1) * 100
        val score = Strength.score(chg, cx.adrPct)
    }

    private class Snap(
        val nowSec: Long,
        val minute: Int,
        val all: List<Live>,
        val scores: DoubleArray,
        val bull: List<Live>,
        val bear: List<Live>,
        val sectorScore: Map<String, Double>,
        val upShare: Double,
        val downShare: Double,
    )

    private fun live(): List<Live> = stocks.mapNotNull { st ->
        val cx = ctx[st.symbol] ?: return@mapNotNull null
        val se = series[st.symbol] ?: return@mapNotNull null
        val p = se.lastPrice()
        if (p > 0) Live(st, cx, se, p) else null
    }

    private fun snapshot(nowMs: Long): Snap {
        val all = live()
        val up = all.count { it.ltp > it.cx.prevClose }
        val down = all.count { it.ltp < it.cx.prevClose }
        val moving = (up + down).coerceAtLeast(1).toDouble()
        return Snap(
            nowSec = nowMs / 1000,
            minute = istMinuteOfDay(nowMs),
            all = all,
            scores = all.map { it.score }.sorted().toDoubleArray(),
            bull = all.filter { it.score > 0 }.sortedByDescending { it.score },
            bear = all.filter { it.score < 0 }.sortedBy { it.score },
            sectorScore = all.groupBy { it.stock.sector }.mapValues { (_, xs) -> xs.sumOf { it.score } / xs.size },
            upShare = up / moving,
            downShare = down / moving,
        )
    }

    private fun percentile(s: Snap, score: Double): Double {
        val n = s.scores.size
        if (n <= 1) return 100.0
        val below = s.scores.count { it < score }
        return (1000.0 * below / (n - 1)).roundToLong() / 10.0
    }

    private fun dayLevels(minutes: List<Candle>, cx: DayContext): DayLevels {
        val hi = minutes.maxBy { it.h }
        val lo = minutes.minBy { it.l }
        return DayLevels(
            open = minutes.first().o, openT = bucket(minutes.first().t, 300),
            high = hi.h, highT = bucket(hi.t, 300),
            low = lo.l, lowT = bucket(lo.t, 300),
            prevClose = cx.prevClose,
        )
    }

    private fun buildUp(l: Live): String? {
        val oi = l.series.oi
        val prev = l.cx.prevOi ?: return null
        if (oi <= 0 || prev <= 0 || oi == prev) return null
        val up = l.ltp >= l.cx.prevClose
        return when {
            up && oi > prev -> "Long build-up"
            up -> "Short covering"
            oi > prev -> "Short build-up"
            else -> "Long unwinding"
        }
    }

    private fun view(l: Live, s: Snap, rank: Int, sideCount: Int, prevRank: Int?): Row {
        val bull = l.score >= 0
        val minutes = l.series.minutes()
        val five = aggregate(minutes)
        val event = abs(l.score) >= Rules.EVENT_SCORE
        val vwap = if (minutes.isEmpty()) l.ltp else Profile.vwap(minutes)
        val setup = if (event || minutes.isEmpty()) null else {
            val a = OrderBlocks.analyze(five.filter { it.t + 300 <= s.nowSec }, l.cx.pdh, l.cx.pdl)
            Levels.setup(a, bull, l.ltp, dayLevels(minutes, l.cx))
        }
        val pct = percentile(s, l.score)
        val conv = Rules.conviction(
            sidePct = if (bull) pct else 100 - pct,
            marketWith = if (bull) s.upShare else s.downShare,
            sectorScore = (s.sectorScore[l.stock.sector] ?: 0.0) * if (bull) 1 else -1,
            rightOfVwap = if (bull) l.ltp >= vwap else l.ltp <= vwap,
            setup = setup,
        )
        return Row(
            symbol = l.stock.symbol,
            name = l.stock.name,
            sector = l.stock.sector,
            lot = l.stock.lot,
            side = if (bull) "bull" else "bear",
            ltp = r2(l.ltp),
            chg = r2(l.chg),
            chgAbs = r2(l.ltp - l.cx.prevClose),
            score = r2(l.score),
            rank = rank,
            sideCount = sideCount,
            prevRank = prevRank,
            pct = pct,
            conv = conv,
            event = event,
            vwap = r2(vwap),
            buildUp = buildUp(l),
            oiChg = l.cx.prevOi?.takeIf { it > 0 && l.series.oi > 0 }?.let { r2((l.series.oi.toDouble() / it - 1) * 100) },
            delta = minutes.sumOf { if (it.c >= it.o) it.v else -it.v },
            setup = setup,
            advice = Rules.advice(
                bull, event, setup, l.ltp, vwap, s.minute,
                distAdr = setup?.let { abs(l.ltp - it.z) / (l.ltp * l.cx.adrPct / 100) } ?: 0.0,
            ),
            path = Profile.path(five, l.cx.prevClose),
            profile = Profile.volume(minutes),
        )
    }

    /** Top three per side, at most two per sector, no event-risk stocks and only with a setup. */
    private fun assignPicks(rows: List<Row>, label: String): List<Row> {
        val perSector = HashMap<String, Int>()
        var n = 0
        return rows.map { r ->
            val rule = when {
                r.event -> "EVENT RISK"
                r.setup == null -> "NOT PICKED · no zone yet"
                n >= Rules.PICKS -> "NOT PICKED · outside top ${Rules.PICKS}"
                (perSector[r.sector] ?: 0) >= Rules.SECTOR_CAP -> "NOT PICKED · sector cap"
                else -> null
            }
            if (rule == null) {
                n++
                perSector.merge(r.sector, 1, Int::plus)
                r.copy(rule = "PICK · $label #$n", pick = n, bars = series[r.symbol]?.let { aggregate(it.minutes()) })
            } else {
                r.copy(rule = rule, advice = notAPick(r.advice))
            }
        }
    }

    private fun notAPick(a: Advice) = if (a.action in TRADE) Advice("WATCH", "Valid setup, but not a top-${Rules.PICKS} pick") else a

    private fun planStates(symbol: String? = null): List<PlanState> = book.all()
        .filter { symbol == null || it.symbol == symbol }
        .map { p ->
            val se = series[p.symbol]
            Plans.replay(p, se?.minutes().orEmpty(), se?.lastPrice() ?: p.entry)
        }
        .sortedByDescending { it.plan.createdT }

    fun computeBoard(nowMs: Long): Board {
        val s = snapshot(nowMs)
        snap = s
        flowCache = null
        val ranks = HashMap<String, Int>()
        fun side(list: List<Live>, tag: String, label: String): List<Row> = assignPicks(
            list.take(TOP).mapIndexed { i, l ->
                val key = tag + l.stock.symbol
                ranks[key] = i + 1
                view(l, s, i + 1, list.size, if (prevRanks.isEmpty()) i + 1 else prevRanks[key])
            },
            label,
        )
        val bullish = side(s.bull, "B:", "BULLISH")
        val bearish = side(s.bear, "S:", "BEARISH")
        prevRanks = ranks

        val minuteStart = s.nowSec - Math.floorMod(s.nowSec, 60L)
        (bullish + bearish).filter { it.pick != null && it.advice.action in TRADE }.forEach { book.ensure(it, minuteStart) }
        val plans = planStates()

        val sectors = s.all.groupBy { it.stock.sector }.map { (name, xs) ->
            SectorRow(
                name = name,
                score = r2(xs.sumOf { it.score } / xs.size),
                chg = r2(xs.sumOf { it.chg } / xs.size),
                up = xs.count { it.ltp > it.cx.prevClose },
                down = xs.count { it.ltp < it.cx.prevClose },
                moves = xs.map { r2(it.chg) }.sortedDescending(),
            )
        }.sortedByDescending { it.score }

        return Board(
            t = s.nowSec,
            session = session.toString(),
            market = market(nowMs),
            breadth = Breadth(
                up = s.all.count { it.ltp > it.cx.prevClose },
                down = s.all.count { it.ltp < it.cx.prevClose },
                flat = s.all.count { it.ltp == it.cx.prevClose },
            ),
            bullCount = s.bull.size,
            bearCount = s.bear.size,
            sectors = sectors,
            bullish = bullish,
            bearish = bearish,
            dots = s.all.map { Dot(it.stock.symbol, r2(it.chg)) },
            plans = plans,
            scorecard = Plans.scorecard(plans),
        )
    }

    fun symbols(): List<String> = stocks.map { it.symbol }

    /** Every stock's name, sector and lot, for search and the sector lists. */
    fun universe(): List<UniverseStock> = stocks.map { UniverseStock(it.symbol, it.name, it.sector, it.lot) }

    fun flow(): FlowData {
        flowCache?.let { return it }
        val out = LinkedHashMap<String, List<Double>>()
        for (st in stocks) {
            val cx = ctx[st.symbol] ?: continue
            val se = series[st.symbol] ?: continue
            out[st.symbol] = Profile.path(aggregate(se.minutes()), cx.prevClose)
        }
        return FlowData(sessionOpenSec(session), 300, clock.nowMs() / 1000, out).also { flowCache = it }
    }

    fun chart(symbol: String): Chart? {
        val st = bySymbol[symbol] ?: return null
        val cx = ctx[symbol] ?: return null
        val se = series[symbol] ?: return null
        val nowMs = clock.nowMs()
        val s = snap ?: snapshot(nowMs)
        val ltp = se.lastPrice().takeIf { it > 0 } ?: cx.prevClose
        val l = Live(st, cx, se, ltp)
        val row = board.value?.let { b -> (b.bullish + b.bearish).firstOrNull { it.symbol == symbol } } ?: run {
            val list = if (l.score >= 0) s.bull else s.bear
            val r = view(l, s, list.indexOfFirst { it.stock.symbol == symbol } + 1, list.size, null)
            r.copy(rule = if (r.event) "EVENT RISK" else "NOT PICKED · outside top $TOP", advice = notAPick(r.advice))
        }
        val minutes = se.minutes()
        val five = aggregate(minutes)
        val nowSec = nowMs / 1000
        val a = OrderBlocks.analyze(five.filter { it.t + 300 <= nowSec }, cx.pdh, cx.pdl)
        return Chart(
            row = row,
            prevClose = r2(cx.prevClose),
            pdh = r2(cx.pdh),
            pdl = r2(cx.pdl),
            dayOpen = minutes.firstOrNull()?.o,
            dayHigh = minutes.maxOfOrNull { it.h },
            dayLow = minutes.minOfOrNull { it.l },
            volume = minutes.sumOf { it.v },
            candles = five,
            formingT = five.lastOrNull()?.takeIf { it.t + 300 > nowSec }?.t,
            vwap = Profile.vwapLine(minutes),
            zones = a.zones,
            liquidity = a.liquidity,
            plan = planStates(symbol).firstOrNull(),
        )
    }
}
