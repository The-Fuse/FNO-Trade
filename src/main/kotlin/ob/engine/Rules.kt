package ob.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** What to do with a stock right now: BUY / SELL (a top pick with a tradeable setup), WATCH, WAIT or AVOID. */
@Serializable
data class Advice(val action: String, val reason: String)

/**
 * The strategy's dials. The defaults are the setting that survived the 40-session backtest of 24 Aug–21 Oct 2026
 * for a small account (see README). Any dial can be overridden from the environment / .env (same names) or a
 * backtest's --set, so variations can be tested without touching code.
 */
object Rules {
    var PICKS = 1                         // picks per side: only the strongest stock each way
    var SECTOR_CAP = 2                    // picks from one sector per side
    var EVENT_SCORE = 4.0                 // a move this many normal days is news, not structure
    var MIN_RR = 1.5
    var MAX_DIST_ADR = 0.5                // Z within half a normal day's range of price, or it's a chase
    var FIRST_ENTRY_MIN = 9 * 60 + 15     // no entries before this (the open is noisy)
    var LAST_ENTRY_MIN = 14 * 60 + 30     // no fresh entries after 2:30 PM
    var EXIT_MIN = 15 * 60 + 15           // intraday positions close at 3:15 PM
    var MIN_STOP_PCT = 1.0                // widen the stop to at least this % of price (0: the block's edge + buffer)
    var MIN_CONV = 0                      // trade only at this conviction or above
    var MIN_MARKET = 0.6                  // at least this share (0–1) of the market must be moving the same way
    var TARGET = "t2"                     // half: book half at T1 and the rest at T2 · t1: all at T1 · t2: all at T2

    private val defaults = snapshot()

    private fun snapshot() = linkedMapOf<String, Any>(
        "PICKS" to PICKS, "SECTOR_CAP" to SECTOR_CAP, "EVENT_SCORE" to EVENT_SCORE, "MIN_RR" to MIN_RR,
        "MAX_DIST_ADR" to MAX_DIST_ADR, "FIRST_ENTRY_MIN" to FIRST_ENTRY_MIN, "LAST_ENTRY_MIN" to LAST_ENTRY_MIN,
        "EXIT_MIN" to EXIT_MIN, "MIN_STOP_PCT" to MIN_STOP_PCT, "MIN_CONV" to MIN_CONV, "MIN_MARKET" to MIN_MARKET,
        "TARGET" to TARGET,
    )

    /** Times may be given as minutes or "HH:MM". Unknown names are ignored. */
    fun configure(get: (String) -> String?) {
        fun minutes(s: String) = if (':' in s) s.substringBefore(':').toInt() * 60 + s.substringAfter(':').toInt() else s.toInt()
        get("PICKS")?.let { PICKS = it.toInt() }
        get("SECTOR_CAP")?.let { SECTOR_CAP = it.toInt() }
        get("EVENT_SCORE")?.let { EVENT_SCORE = it.toDouble() }
        get("MIN_RR")?.let { MIN_RR = it.toDouble() }
        get("MAX_DIST_ADR")?.let { MAX_DIST_ADR = it.toDouble() }
        get("FIRST_ENTRY_MIN")?.let { FIRST_ENTRY_MIN = minutes(it) }
        get("LAST_ENTRY_MIN")?.let { LAST_ENTRY_MIN = minutes(it) }
        get("EXIT_MIN")?.let { EXIT_MIN = minutes(it) }
        get("MIN_STOP_PCT")?.let { MIN_STOP_PCT = it.toDouble() }
        get("MIN_CONV")?.let { MIN_CONV = it.toInt() }
        get("MIN_MARKET")?.let { MIN_MARKET = it.toDouble() }
        get("TARGET")?.let { require(it in setOf("half", "t1", "t2")) { "TARGET must be half, t1 or t2" }; TARGET = it }
    }

    /** Back to the defaults (tests and tools). */
    fun reset() = configure { defaults[it]?.toString() }

    /** Every dial with its value, for the UI. */
    fun dials(): Map<String, String> = snapshot().mapValues { it.value.toString() }

    /** All dials in one line; keys cached backtests. */
    fun signature(): String = dials().entries.joinToString(" ") { "${it.key}=${it.value}" }

    /** The dials that differ from the defaults, e.g. "MIN_STOP_PCT=0.3 TARGET=t1"; "default" when none do. */
    fun describe(): String =
        snapshot().filter { (k, v) -> v != defaults[k] }.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "default" }

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

    /** [distAdr]: how far price is from Z, in normal-day ranges. [marketWith]: share of stocks moving this way. */
    fun advice(
        bull: Boolean, event: Boolean, setup: Setup?, ltp: Double, vwap: Double, minuteOfDay: Int, distAdr: Double,
        conv: Int = 100, marketWith: Double = 1.0,
    ): Advice = when {
        event -> Advice("AVOID", "Event risk: moving over ${EVENT_SCORE.toInt()}× a normal day. No levels.")
        setup == null -> Advice("WAIT", "No order block yet")
        minuteOfDay < FIRST_ENTRY_MIN -> Advice("WAIT", "Before ${hhmm(FIRST_ENTRY_MIN)}: no entries yet")
        minuteOfDay >= LAST_ENTRY_MIN -> Advice("WAIT", "After ${hhmm(LAST_ENTRY_MIN)}: no fresh entries")
        distAdr > MAX_DIST_ADR -> Advice("WAIT", "Price is ${"%.1f".format(abs(ltp / setup.z - 1) * 100)}% from Z: wait for a pullback")
        setup.rr < MIN_RR -> Advice("WAIT", "R:R 1:${setup.rr} is under 1:$MIN_RR")
        conv < MIN_CONV -> Advice("WAIT", "Conviction $conv is under $MIN_CONV")
        marketWith < MIN_MARKET -> Advice("WAIT", "Only ${(marketWith * 100).roundToInt()}% of the market is moving this way")
        bull && ltp < vwap -> Advice("WAIT", "Price is below VWAP")
        !bull && ltp > vwap -> Advice("WAIT", "Price is above VWAP")
        setup.inZone -> Advice(if (bull) "BUY" else "SELL", "Price is in the zone now")
        else -> Advice(if (bull) "BUY" else "SELL", "${if (bull) "Buy" else "Sell"} on the retest of Z")
    }

    private fun hhmm(m: Int): String {
        val h = m / 60
        return "${if (h > 12) h - 12 else h}:${"%02d".format(m % 60)} ${if (h >= 12) "PM" else "AM"}"
    }
}
