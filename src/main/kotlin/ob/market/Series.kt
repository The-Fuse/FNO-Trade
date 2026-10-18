package ob.market

import ob.Candle
import ob.Stock
import ob.bucket
import kotlin.math.max
import kotlin.math.min

/**
 * One stock's session: closed 1-minute candles plus the minute still forming.
 * Fed by ticks (live feed, simulator) and by whole candles (REST backfill, exchange minute bars).
 * Only trades inside the session [openSec, closeSec) make candles; earlier ticks still move the price.
 */
class Series(val stock: Stock, private val openSec: Long, private val closeSec: Long) {
    private val closed = ArrayList<Candle>()
    private var live: Candle? = null
    private var lastVtt = -1L
    private var tickPrice = Double.NaN

    /** Open interest of the stock's near-month future, -1 until the feed reports it. */
    @Volatile
    var oi: Long = -1

    /** [vtt] is the cumulative volume traded today, or -1 when the tick doesn't carry it. */
    @Synchronized
    fun tick(ms: Long, price: Double, vtt: Long = -1) {
        if (!(price > 0)) return
        tickPrice = price
        var vol = 0L
        if (vtt >= 0) {
            if (lastVtt >= 0 && vtt > lastVtt) vol = vtt - lastVtt
            if (vtt > lastVtt) lastVtt = vtt
        }
        val sec = ms / 1000
        if (sec < openSec || sec >= closeSec) return
        val m = bucket(sec, 60)
        val cur = live
        when {
            cur != null && m == cur.t -> live = Candle(m, cur.o, max(cur.h, price), min(cur.l, price), price, cur.v + vol)
            cur != null && m < cur.t -> {} // late tick for a minute already moved past
            closed.isNotEmpty() && m <= closed.last().t -> {}
            else -> {
                if (cur != null) closed += cur
                live = Candle(m, price, price, price, price, vol)
            }
        }
    }

    /** Puts a whole 1-minute candle in place, replacing any candle for that minute. */
    @Synchronized
    fun upsert(c: Candle, nowSec: Long) {
        if (c.t < openSec || c.t >= closeSec) return
        val cur = live
        if (cur != null && c.t == cur.t) {
            live = c
            return
        }
        if (cur != null && c.t > cur.t) {
            closed += cur
            live = null
        }
        if (c.t + 60 > nowSec && live == null && (closed.isEmpty() || c.t > closed.last().t)) {
            live = c
            return
        }
        val i = closed.binarySearchBy(c.t) { it.t }
        if (i >= 0) closed[i] = c else closed.add(-i - 1, c)
    }

    /** Closes the forming minute once the clock has passed it, even if no trade arrived to roll it over. */
    @Synchronized
    fun closeBefore(sec: Long) {
        val cur = live ?: return
        if (cur.t + 60 <= sec) {
            closed += cur
            live = null
        }
    }

    @Synchronized
    fun minutes(): List<Candle> = ArrayList<Candle>(closed.size + 1).apply {
        addAll(closed)
        live?.let { add(it) }
    }

    @Synchronized
    fun lastPrice(): Double = if (tickPrice > 0) tickPrice else (live ?: closed.lastOrNull())?.c ?: Double.NaN
}

/** Rolls 1-minute candles up into [size]-second candles (5-minute buckets line up with the 9:15 open). */
fun aggregate(minutes: List<Candle>, size: Long = 300): List<Candle> {
    val out = ArrayList<Candle>()
    for (m in minutes) {
        val b = bucket(m.t, size)
        val last = out.lastOrNull()
        if (last != null && last.t == b) {
            out[out.size - 1] = Candle(b, last.o, max(last.h, m.h), min(last.l, m.l), m.c, last.v + m.v)
        } else {
            out += Candle(b, m.o, m.h, m.l, m.c, m.v)
        }
    }
    return out
}
