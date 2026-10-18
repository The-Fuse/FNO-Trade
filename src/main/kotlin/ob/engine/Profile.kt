package ob.engine

import kotlinx.serialization.Serializable
import ob.Candle
import ob.bucket
import ob.r2
import kotlin.math.roundToInt

@Serializable
data class Pt(val t: Long, val v: Double)

/**
 * Where today's volume traded: [bins] from [lo] (index 0) to [hi], scaled 0–100; [poc] is the busiest price.
 * [up] and [dn] split each bin by minutes that closed up or down, on the same scale.
 */
@Serializable
data class VolProfile(val lo: Double, val hi: Double, val poc: Double, val bins: List<Int>, val up: List<Int>, val dn: List<Int>)

object Profile {
    fun vwap(minutes: List<Candle>): Double {
        var pv = 0.0
        var v = 0.0
        for (m in minutes) {
            pv += (m.h + m.l + m.c) / 3 * m.v
            v += m.v
        }
        return if (v > 0) pv / v else minutes.map { (it.h + it.l + it.c) / 3 }.average()
    }

    /** VWAP as of the end of each 5-minute candle, keyed by the candle's start. */
    fun vwapLine(minutes: List<Candle>): List<Pt> {
        val out = LinkedHashMap<Long, Double>()
        var pv = 0.0
        var v = 0.0
        var n = 0
        var tp = 0.0
        for (m in minutes) {
            val typical = (m.h + m.l + m.c) / 3
            pv += typical * m.v
            v += m.v
            tp += typical
            n++
            out[bucket(m.t, 300)] = if (v > 0) pv / v else tp / n
        }
        return out.map { (t, x) -> Pt(t, r2(x)) }
    }

    fun volume(minutes: List<Candle>, bins: Int = 32): VolProfile? {
        if (minutes.isEmpty()) return null
        val lo = minutes.minOf { it.l }
        val hi = minutes.maxOf { it.h }
        if (hi <= lo) return null
        val w = (hi - lo) / bins
        val up = DoubleArray(bins)
        val dn = DoubleArray(bins)
        for (m in minutes) {
            val a = ((m.l - lo) / w).toInt().coerceIn(0, bins - 1)
            val b = ((m.h - lo) / w).toInt().coerceIn(0, bins - 1)
            val share = m.v.toDouble() / (b - a + 1)
            for (i in a..b) if (m.c >= m.o) up[i] += share else dn[i] += share
        }
        val tot = DoubleArray(bins) { up[it] + dn[it] }
        val max = tot.max()
        if (max <= 0) return null
        fun scale(xs: DoubleArray) = xs.map { (it / max * 100).roundToInt() }
        val poc = lo + (tot.indices.maxBy { tot[it] } + 0.5) * w
        return VolProfile(r2(lo), r2(hi), r2(poc), scale(tot), scale(up), scale(dn))
    }

    /** % change from yesterday's close at the end of each 5-minute candle. */
    fun path(five: List<Candle>, prevClose: Double): List<Double> = five.map { r2((it.c / prevClose - 1) * 100) }
}
