package ob.engine

import kotlinx.serialization.Serializable
import ob.Candle
import ob.r2
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class Zone(
    val side: String,           // "bull": support below price, "bear": resistance above
    val t: Long,                // the order-block candle
    val lo: Double,
    val hi: Double,
    val body: Double,           // Z: the block's body edge facing price, the level the setup is built around
    val formedT: Long,          // the candle whose close broke structure
    val bos: Double,            // the swing level that close broke
    val origin: Double,         // where the leg began: lowest low (bull) / highest high (bear), block to break
    val brokenT: Long? = null,  // first 5-minute close through the far edge; the zone is dead after it
    val tests: Int = 0,         // times price came back into the zone
)

@Serializable
data class Liquidity(
    val side: String,           // "buy": stops resting above highs, "sell": below lows
    val price: Double,
    val kind: String,           // "swing", "equal" (2+ swings at one price), "pdh", "pdl"
    val t: Long,
    val count: Int,
    val takenT: Long? = null,   // first 5-minute close beyond it
)

class Analysis(val zones: List<Zone>, val liquidity: List<Liquidity>)

/**
 * Order blocks and liquidity on finished 5-minute candles.
 *
 * - Swing high: a high above the [PIVOT] candles before it and not exceeded by the [PIVOT] after it.
 *   The first 15 minutes' high and low count as the first structure of the day.
 * - Break of structure: a candle *closes* beyond the latest swing (a wick is not enough).
 * - Order block: the last opposite-colour candle where that leg began (around its extreme).
 * - Proof: the move away leaves a fair value gap, or the break closes at least one average
 *   candle beyond the block. Without proof there is no zone.
 * - A zone dies on the first 5-minute close beyond its far edge.
 * - Liquidity: swing highs/lows and the previous day's high/low no candle has closed beyond yet;
 *   swings within a small tolerance merge into equal highs/lows.
 */
object OrderBlocks {
    const val PIVOT = 2
    private const val OPENING_BARS = 3
    private const val AVG_BARS = 20
    private const val ORIGIN_SLACK = 3

    private class Pivot(val idx: Int, val price: Double)

    private class Z(
        val bull: Boolean, val idx: Int, val lo: Double, val hi: Double, val body: Double,
        val formedIdx: Int, val bos: Double, val origin: Double,
    ) {
        var brokenIdx = -1
        var tests = 0
        var inside = false
    }

    private class Pool(val buy: Boolean, var price: Double, val idx: Int, var kind: String) {
        var count = 1
        var takenIdx = -1
    }

    fun analyze(c: List<Candle>, pdh: Double? = null, pdl: Double? = null): Analysis {
        if (c.isEmpty()) return Analysis(emptyList(), emptyList())
        val tol = max(c.sumOf { it.h - it.l } / c.size * 0.15, c.last().c * 0.0003)
        val zones = ArrayList<Z>()
        val pools = ArrayList<Pool>()
        pdh?.let { pools += Pool(true, it, -1, "pdh") }
        pdl?.let { pools += Pool(false, it, -1, "pdl") }
        var lastHigh: Pivot? = null
        var lastLow: Pivot? = null

        for (j in c.indices) {
            val bar = c[j]
            val sh = lastHigh
            if (sh != null && bar.c > sh.price) {
                bullZone(c, sh, j)?.let(zones::add)
                lastHigh = null
            }
            val sl = lastLow
            if (sl != null && bar.c < sl.price) {
                bearZone(c, sl, j)?.let(zones::add)
                lastLow = null
            }
            for (z in zones) {
                if (z.brokenIdx >= 0 || z.formedIdx >= j) continue
                if (if (z.bull) bar.c < z.lo else bar.c > z.hi) {
                    z.brokenIdx = j
                    continue
                }
                val touching = if (z.bull) bar.l <= z.hi else bar.h >= z.lo
                if (touching && !z.inside) z.tests++
                z.inside = touching
            }
            for (p in pools) {
                if (p.takenIdx >= 0 || p.idx >= j) continue
                if (if (p.buy) bar.c > p.price else bar.c < p.price) p.takenIdx = j
            }
            // the opening range (9:15–9:30) is the first structure to break
            if (j == OPENING_BARS - 1) {
                val hi = (0..j).maxBy { c[it].h }
                val lo = (0..j).minBy { c[it].l }
                if (lastHigh == null) lastHigh = Pivot(hi, c[hi].h)
                if (lastLow == null) lastLow = Pivot(lo, c[lo].l)
            }
            // confirm the swing PIVOT candles back, now that the candles after it are known
            val p = j - PIVOT
            if (p >= PIVOT) {
                if (isPivotHigh(c, p)) {
                    lastHigh = Pivot(p, c[p].h)
                    addPool(pools, true, c[p].h, p, tol)
                }
                if (isPivotLow(c, p)) {
                    lastLow = Pivot(p, c[p].l)
                    addPool(pools, false, c[p].l, p, tol)
                }
            }
        }

        return Analysis(
            zones = zones.map { z ->
                Zone(
                    side = if (z.bull) "bull" else "bear",
                    t = c[z.idx].t, lo = r2(z.lo), hi = r2(z.hi), body = r2(z.body),
                    formedT = c[z.formedIdx].t, bos = r2(z.bos), origin = r2(z.origin),
                    brokenT = z.brokenIdx.takeIf { it >= 0 }?.let { c[it].t }, tests = z.tests,
                )
            },
            liquidity = pools.map { p ->
                Liquidity(
                    side = if (p.buy) "buy" else "sell", price = r2(p.price), kind = p.kind,
                    t = c[max(p.idx, 0)].t, count = p.count,
                    takenT = p.takenIdx.takeIf { it >= 0 }?.let { c[it].t },
                )
            },
        )
    }

    private fun isPivotHigh(c: List<Candle>, p: Int): Boolean =
        (p - PIVOT until p).all { c[it].h < c[p].h } && (p + 1..p + PIVOT).all { c[it].h <= c[p].h }

    private fun isPivotLow(c: List<Candle>, p: Int): Boolean =
        (p - PIVOT until p).all { c[it].l > c[p].l } && (p + 1..p + PIVOT).all { c[it].l >= c[p].l }

    private fun addPool(pools: MutableList<Pool>, buy: Boolean, price: Double, idx: Int, tol: Double) {
        val near = pools.firstOrNull {
            it.buy == buy && it.takenIdx < 0 && (it.kind == "swing" || it.kind == "equal") && abs(it.price - price) <= tol
        }
        if (near == null) {
            pools += Pool(buy, price, idx, "swing")
            return
        }
        near.count++
        near.kind = "equal"
        near.price = if (buy) max(near.price, price) else minOf(near.price, price)
    }

    private fun avgRange(c: List<Candle>, j: Int): Double {
        val from = max(0, j - AVG_BARS)
        if (from >= j) return c[j].h - c[j].l
        return (from until j).sumOf { c[it].h - c[it].l } / (j - from)
    }

    private fun bullZone(c: List<Candle>, sh: Pivot, j: Int): Z? {
        val from = sh.idx + 1
        var m = from
        for (i in from..j) if (c[i].l < c[m].l) m = i
        val avg = avgRange(c, j)
        for (i in minOf(m + 1, j - 1) downTo max(from, m - ORIGIN_SLACK)) {
            val b = c[i]
            if (b.c >= b.o || b.h >= sh.price) continue
            if ((i + 1 until j).any { c[it].c < b.l }) continue
            val gap = (i + 1 until j).any { x -> c[x + 1].l > c[x - 1].h }
            if (!gap && c[j].c - b.h < avg) continue
            return Z(true, i, b.l, b.h, max(b.o, b.c), j, sh.price, (i..j).minOf { c[it].l })
        }
        return null
    }

    private fun bearZone(c: List<Candle>, sl: Pivot, j: Int): Z? {
        val from = sl.idx + 1
        var m = from
        for (i in from..j) if (c[i].h > c[m].h) m = i
        val avg = avgRange(c, j)
        for (i in minOf(m + 1, j - 1) downTo max(from, m - ORIGIN_SLACK)) {
            val b = c[i]
            if (b.c <= b.o || b.l <= sl.price) continue
            if ((i + 1 until j).any { c[it].c > b.h }) continue
            val gap = (i + 1 until j).any { x -> c[x + 1].h < c[x - 1].l }
            if (!gap && b.l - c[j].c < avg) continue
            return Z(false, i, b.l, b.h, minOf(b.o, b.c), j, sl.price, (i..j).maxOf { c[it].h })
        }
        return null
    }
}
