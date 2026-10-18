package ob.engine

import kotlinx.serialization.Serializable
import ob.r2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

@Serializable
data class Level(val price: Double, val kind: String, val t: Long? = null)

/** The day so far: open, extremes and yesterday's close (for open gaps). */
data class DayLevels(val open: Double, val openT: Long, val high: Double, val highT: Long, val low: Double, val lowT: Long, val prevClose: Double)

/**
 * The setup on one stock: the order block to trade from and the levels around it.
 * Bullish: buy at [z], wrong below [stop] (S1), targets R1/R2 above, S2–S4 behind.
 * Bearish: sell at [z], wrong above [stop] (R1), targets S1/S2 below, R2–R4 behind.
 */
@Serializable
data class Setup(
    val side: String,
    val zone: Zone,
    val z: Double,
    val stop: Double,
    val targets: List<Level>,   // exactly two, nearest first
    val behind: List<Level>,    // up to three past the stop, nearest first
    val rr: Double,             // room from Z to the second target ÷ risk from Z to the stop
    val inZone: Boolean,
)

object Levels {
    fun tick(x: Double): Double = (x * 20).roundToLong() / 20.0

    /** Gap between the block's far edge and the stop: half a basis point of price, at least one tick. */
    fun buffer(price: Double): Double = max(0.05, tick(price * 0.0005))

    fun setup(a: Analysis, bull: Boolean, ltp: Double, day: DayLevels): Setup? {
        val side = if (bull) "bull" else "bear"
        val live = a.zones.filter { it.brokenT == null && it.side == side && (if (bull) it.lo < ltp else it.hi > ltp) }
        val zone = live.maxByOrNull { it.formedT } ?: return null
        val z = zone.body
        val stop = r2(if (bull) zone.lo - buffer(ltp) else zone.hi + buffer(ltp))
        val risk = abs(z - stop)
        if (risk <= 0) return null
        val tol = ltp * 0.001
        val untaken = a.liquidity.filter { it.takenT == null }

        // ahead: untaken highs (lows), the day's extreme, and opposing blocks
        val aheadAll = buildList {
            untaken.filter { it.side == if (bull) "buy" else "sell" }.forEach { add(Level(it.price, it.kind, it.t)) }
            add(if (bull) Level(day.high, "dayHigh", day.highT) else Level(day.low, "dayLow", day.lowT))
            a.zones.filter { it.brokenT == null && it.side != side }.forEach { add(Level(if (bull) it.lo else it.hi, "zone", it.t)) }
        }
        val floor = if (bull) max(ltp, z) else min(ltp, z)
        val targets = dedupe(
            aheadAll.filter { if (bull) it.price > floor + tol else it.price < floor - tol }.sortedBy { if (bull) it.price else -it.price },
            tol,
        ).take(2).toMutableList()
        var k = 2
        while (targets.size < 2) {
            val p = r2(if (bull) z + k * risk else z - k * risk)
            val beyond = targets.lastOrNull()?.price ?: floor
            if (if (bull) p > beyond + tol else p < beyond - tol) targets += Level(p, "${k}R")
            k++
        }

        // behind: where price may react if it comes back further than planned
        val behindAll = buildList {
            untaken.filter { it.side == if (bull) "sell" else "buy" }.forEach { add(Level(it.price, it.kind, it.t)) }
            add(Level(day.open, "open", day.openT))
            if (bull && day.prevClose < day.low) add(Level(day.prevClose, "gap", day.openT))
            if (!bull && day.prevClose > day.high) add(Level(day.prevClose, "gap", day.openT))
            live.filter { it !== zone }.forEach { add(Level(if (bull) it.hi else it.lo, "zone", it.t)) }
        }
        val behind = dedupe(
            behindAll.filter { if (bull) it.price < stop - tol else it.price > stop + tol }.sortedBy { if (bull) -it.price else it.price },
            tol,
        ).take(3)

        return Setup(
            side = side,
            zone = zone,
            z = z,
            stop = stop,
            targets = targets,
            behind = behind,
            rr = r2(abs(targets[1].price - z) / risk),
            inZone = ltp >= zone.lo && ltp <= zone.hi,
        )
    }

    private fun dedupe(sorted: List<Level>, tol: Double): List<Level> {
        val out = ArrayList<Level>()
        for (l in sorted) if (out.none { abs(it.price - l.price) <= tol }) out += l
        return out
    }
}
