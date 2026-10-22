package ob

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** One OHLCV bar; [t] is the bar's start in epoch seconds. */
@Serializable
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Long = 0)

/**
 * An F&O stock. [key] is the feed's cash-market instrument key (Upstox: "NSE_EQ|<ISIN>"),
 * [futKey] its near-month future (for open interest), [lot] the futures lot size.
 */
@Serializable
data class Stock(val symbol: String, val name: String, val key: String, val sector: String, val lot: Int = 0, val futKey: String = "")

/** What earlier sessions say about a stock: yesterday's levels, its normal daily range, yesterday's futures OI. */
data class DayContext(val prevClose: Double, val pdh: Double, val pdl: Double, val adrPct: Double, val prevOi: Long? = null)

@Serializable
data class MarketState(val state: String, val label: String)

fun r2(x: Double): Double = (x * 100).roundToLong() / 100.0
