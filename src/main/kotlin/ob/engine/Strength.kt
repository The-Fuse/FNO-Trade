package ob.engine

import ob.Candle
import ob.DayContext
import kotlin.math.abs

/**
 * How hard a stock is moving today against its own normal day.
 * score = today's % change / median daily true range % of the last 50 sessions.
 * The median keeps one split, bonus or result-day spike from inflating the normal range.
 */
object Strength {
    const val SESSIONS = 50

    fun adrPct(daily: List<Candle>, sessions: Int = SESSIONS): Double {
        val trs = (1 until daily.size).map { i ->
            val pc = daily[i - 1].c
            val d = daily[i]
            maxOf(d.h - d.l, abs(d.h - pc), abs(d.l - pc)) / pc * 100
        }.takeLast(sessions).sorted()
        if (trs.isEmpty()) return Double.NaN
        val n = trs.size
        return if (n % 2 == 1) trs[n / 2] else (trs[n / 2 - 1] + trs[n / 2]) / 2
    }

    /** [before] holds daily candles of sessions before the one being traded, oldest first. */
    fun context(before: List<Candle>): DayContext? {
        if (before.size < 2) return null
        val adr = adrPct(before)
        if (!(adr > 0)) return null
        val y = before.last()
        return DayContext(prevClose = y.c, pdh = y.h, pdl = y.l, adrPct = adr)
    }

    fun score(chgPct: Double, adrPct: Double): Double = chgPct / adrPct
}
