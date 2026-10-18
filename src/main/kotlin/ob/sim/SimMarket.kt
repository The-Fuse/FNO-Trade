package ob.sim

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ob.Candle
import ob.IST
import ob.MarketClock
import ob.Stock
import ob.isTradingDay
import ob.market.Series
import ob.sessionCloseSec
import ob.sessionOpenSec
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * A made-up market for building and demoing without a broker login: 60 sessions of daily
 * history, then one session ticked every 5 market-seconds on a clock that runs [speed]× real time.
 * Prices are a random walk with a market factor, a sector factor, a drift for the day and
 * slowly-decaying momentum with occasional bursts, so trends, swings and breaks show up.
 */
class SimMarket(
    stocks: List<Stock>,
    date: LocalDate,
    startMinute: Int,
    private val speed: Double,
    seed: Long = 11,
) : MarketClock {
    private companion object {
        const val STEP_MS = 5_000L
        const val STEPS = 4_500 // 375 minutes × 12
        const val HISTORY = 60
    }

    private class Walk(
        val stock: Stock, val adr: Double, val beta: Double, val sector: Int,
        var price: Double, val drift: Double, val baseVol: Double,
        val prevOi: Long, val oiBias: Double, val lot: Int,
    ) {
        var mom = 0.0
        var vtt = 0L
        var oi = prevOi
    }

    private val rnd = Random(seed)
    private val closeMs = sessionCloseSec(date) * 1000
    private val startMs = date.atStartOfDay(IST).toInstant().toEpochMilli() + startMinute * 60_000L
    private val realStart = System.currentTimeMillis()
    private var next = sessionOpenSec(date) * 1000
    private val sectorCount: Int
    private val walks: List<Walk>

    /** Daily candles of the sessions before [date], oldest first. */
    val daily: Map<String, List<Candle>>

    /** Yesterday's futures open interest and the futures lot size, per symbol. */
    val prevOi: Map<String, Long> get() = walks.associate { it.stock.symbol to it.prevOi }
    val lots: Map<String, Int> get() = walks.associate { it.stock.symbol to it.lot }

    override fun nowMs(): Long = startMs + ((System.currentTimeMillis() - realStart) * speed).toLong()

    init {
        val sectors = stocks.map { it.sector }.distinct()
        sectorCount = sectors.size
        val days = generateSequence(date.minusDays(1)) { it.minusDays(1) }
            .filter { isTradingDay(it, emptySet()) }.take(HISTORY).toList().reversed()
        val marketBias = rnd.nextGaussian() * 0.5
        val history = LinkedHashMap<String, List<Candle>>()
        walks = stocks.map { st ->
            val adr = 1.3 + rnd.nextDouble() * 2.0
            var p = exp(ln(80.0) + rnd.nextDouble() * (ln(6000.0) - ln(80.0)))
            history[st.symbol] = days.map { day ->
                val o = p * (1 + rnd.nextGaussian() * adr * 0.15 / 100)
                val c = p * (1 + rnd.nextGaussian() * adr * 0.55 / 100)
                val h = max(o, c) * (1 + abs(rnd.nextGaussian()) * adr * 0.3 / 100)
                val l = min(o, c) * (1 - abs(rnd.nextGaussian()) * adr * 0.3 / 100)
                p = c
                Candle(day.atStartOfDay(IST).toEpochSecond(), tick(o), tick(h), tick(l), tick(c), (2e8 / c * (0.6 + rnd.nextDouble())).toLong())
            }
            val dayMovePct = (marketBias * 0.6 + rnd.nextGaussian() * 0.7) * adr
            val lot = max(25, (6e5 / p / 25).roundToLong().toInt() * 25)
            Walk(
                stock = st, adr = adr, beta = 0.6 + rnd.nextDouble() * 0.7, sector = sectors.indexOf(st.sector),
                price = p * (1 + rnd.nextGaussian() * adr * 0.2 / 100),
                drift = dayMovePct / 100 / STEPS, baseVol = 2e8 / p / STEPS,
                prevOi = lot * (2_000L + rnd.nextInt(20_000)), oiBias = rnd.nextGaussian() * 0.06, lot = lot,
            )
        }
        daily = history
    }

    /** Generates every tick up to market time [ms]; the first call backfills the session to the start time. */
    fun advanceTo(ms: Long, series: Map<String, Series>) {
        val end = min(ms, closeMs - 1)
        while (next <= end) {
            step(next, series)
            next += STEP_MS
        }
    }

    suspend fun run(series: Map<String, Series>) {
        while (currentCoroutineContext().isActive) {
            advanceTo(nowMs(), series)
            delay(50)
        }
    }

    private fun step(t: Long, series: Map<String, Series>) {
        val market = rnd.nextGaussian()
        val sector = DoubleArray(sectorCount) { rnd.nextGaussian() }
        for (w in walks) {
            // sized so a 5-minute candle spans roughly ADR / √75, like a real stock's
            val sigma = w.adr / 100 * 0.008
            w.mom = 0.985 * w.mom + rnd.nextGaussian() * sigma * 0.012
            if (rnd.nextDouble() < 0.001) {
                val up = rnd.nextDouble() < 0.5 + 0.2 * w.drift.sign
                w.mom += sigma * if (up) 0.6 else -0.6
            }
            val r = w.drift + w.mom + sigma * (0.45 * w.beta * market + 0.3 * sector[w.sector] + 0.8 * rnd.nextGaussian())
            w.price *= exp(r)
            w.vtt += (w.baseVol * (0.4 + rnd.nextDouble() * 1.2) * (1 + abs(r) / sigma * 0.4)).toLong()
            w.oi = max(w.lot.toLong(), w.oi + (w.prevOi * (w.oiBias / STEPS + rnd.nextGaussian() * 0.0006)).toLong())
            val s = series[w.stock.symbol] ?: continue
            s.oi = w.oi
            s.tick(t, tick(w.price), w.vtt)
        }
    }

    /** NSE equity tick size. */
    private fun tick(x: Double): Double = (x * 20).roundToLong() / 20.0
}
