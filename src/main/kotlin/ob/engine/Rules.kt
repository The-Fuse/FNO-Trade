package ob.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** What to do with a stock right now: BUY / SELL (a top pick with a tradeable setup), WATCH, WAIT or AVOID. */
@Serializable
data class Advice(val action: String, val reason: String)

object Rules {
    const val PICKS = 3                         // picks per side
    const val SECTOR_CAP = 2                    // picks from one sector per side
    const val EVENT_SCORE = 4.0                 // a move this many normal days is news, not structure
    const val MIN_RR = 1.5
    const val MAX_DIST_ADR = 0.5                // Z within half a normal day's range of price, or it's a chase
    const val LAST_ENTRY_MIN = 14 * 60 + 30     // no fresh entries after 2:30 PM
    const val EXIT_MIN = 15 * 60 + 15           // intraday positions close at 3:15 PM

    /**
     * 0–100 from six checks: strength percentile on its side (30), share of the market moving the
     * same way (15), its sector's strength (15), price on the right side of VWAP (15), R:R (15),
     * and a fresh, untested zone (10).
     */
    fun conviction(sidePct: Double, marketWith: Double, sectorScore: Double, rightOfVwap: Boolean, setup: Setup?): Int {
        var c = sidePct / 100 * 30 + marketWith * 15 + sectorScore.coerceIn(0.0, 1.0) * 15
        if (rightOfVwap) c += 15
        if (setup != null) {
            c += (setup.rr / 3).coerceIn(0.0, 1.0) * 15
            c += when (setup.zone.tests) { 0 -> 10; 1 -> 5; else -> 0 }
        }
        return c.roundToInt().coerceIn(0, 100)
    }

    /** [distAdr]: how far price is from Z, in normal-day ranges. */
    fun advice(bull: Boolean, event: Boolean, setup: Setup?, ltp: Double, vwap: Double, minuteOfDay: Int, distAdr: Double): Advice = when {
        event -> Advice("AVOID", "Event risk: moving over ${EVENT_SCORE.toInt()}× a normal day. No levels.")
        setup == null -> Advice("WAIT", "No order block yet")
        minuteOfDay >= LAST_ENTRY_MIN -> Advice("WAIT", "After 2:30 PM: no fresh entries")
        distAdr > MAX_DIST_ADR -> Advice("WAIT", "Price is ${"%.1f".format(abs(ltp / setup.z - 1) * 100)}% from Z: wait for a pullback")
        setup.rr < MIN_RR -> Advice("WAIT", "R:R 1:${setup.rr} is under 1:$MIN_RR")
        bull && ltp < vwap -> Advice("WAIT", "Price is below VWAP")
        !bull && ltp > vwap -> Advice("WAIT", "Price is above VWAP")
        setup.inZone -> Advice(if (bull) "BUY" else "SELL", "Price is in the zone now")
        else -> Advice(if (bull) "BUY" else "SELL", "${if (bull) "Buy" else "Sell"} on the retest of Z")
    }
}
