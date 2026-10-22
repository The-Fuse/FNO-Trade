package ob.trade

import kotlinx.serialization.Serializable
import ob.engine.PlanState
import ob.engine.Rules
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How a plan was sized for the account: shares, rupees at risk, and the result once it has exited. */
@Serializable
data class Sized(
    val qty: Int,              // 0: not taken, see note
    val risked: Double,        // rupees lost if the stop is hit
    val gross: Double,         // result before charges (0 while open)
    val costs: Double,         // brokerage, STT, exchange, SEBI, stamp duty, GST for the legs
    val net: Double,           // gross − costs
    val note: String? = null,  // why it was skipped or cut
)

/** The same arithmetic sizes trades in the app and in the backtest, so the two agree to the rupee. */
object Sizing {
    const val MARGIN = 0.20          // intraday margin blocked, as a share of the position (5×)
    const val MAX_BROKERAGE = 20.0   // per executed order, or 0.1% if lower

    /**
     * Sizes filled plans in fill order against a running equity: [riskPct] of equity at the stop, capped by the
     * margin still free (open positions hold theirs until they exit), skipped under [minRisk]. Keyed by plan id.
     */
    fun sizeAll(filled: List<PlanState>, equity0: Double, riskPct: Double, minRisk: Double): Map<String, Sized> {
        val out = HashMap<String, Sized>()
        val open = ArrayList<Pair<PlanState, Double>>()   // position, margin it blocks
        for (ps in filled.sortedBy { it.filledT }) {
            val at = ps.filledT ?: continue
            val fill = ps.fill ?: continue
            open.removeAll { (o, _) -> o.exitT != null && o.exitT <= at }
            val realised = filled.filter { it.exitT != null && it.exitT <= at }.sumOf { out[it.plan.id]?.net ?: 0.0 }
            val equity = equity0 + realised
            val used = open.sumOf { it.second }
            val perShare = abs(fill - ps.plan.stop)
            if (perShare <= 0) continue
            val byRisk = floor(equity * riskPct / 100 / perShare).toInt()
            val byMargin = floor((equity - used) / (fill * MARGIN)).toInt()
            val qty = max(0, min(byRisk, byMargin))
            if (qty < 1) {
                out[ps.plan.id] = Sized(0, 0.0, 0.0, 0.0, 0.0, if (byRisk < 1) "too expensive for this account" else "no free margin, another trade was open")
                continue
            }
            if (qty * perShare < minRisk) {
                out[ps.plan.id] = Sized(0, 0.0, 0.0, 0.0, 0.0, "could only risk ₹${"%,.0f".format(qty * perShare)}, under ₹${"%,.0f".format(minRisk)} the charges would eat it")
                continue
            }
            val (gross, costs) = legs(ps, qty, fill)
            out[ps.plan.id] = Sized(qty, qty * perShare, gross, costs, gross - costs, if (byMargin < byRisk) "cut from $byRisk to $qty shares by margin" else null)
            open += ps to qty * fill * MARGIN
        }
        return out
    }

    /** Gross result and charges from the actual legs: half at T1 when the plan scales out, the rest at the exit. */
    fun legs(ps: PlanState, qty: Int, fill: Double): Pair<Double, Double> {
        val p = ps.plan
        val buy = p.side == "bull"
        val half = if (Rules.TARGET == "half" && ps.t1Hit && qty >= 2) qty / 2 else 0
        val rest = qty - half
        val exit = ps.exitPrice ?: fill
        val dir = if (buy) 1 else -1
        val gross = dir * (half * (p.t1 - fill) + rest * (exit - fill))
        val entryValue = qty * fill
        val exitLegs = listOfNotNull(if (half > 0) half * p.t1 else null, rest * exit)
        val exitValue = exitLegs.sum()
        val brokerage = (listOf(entryValue) + exitLegs).sumOf { min(MAX_BROKERAGE, it * 0.001) }
        val buyValue = if (buy) entryValue else exitValue
        val sellValue = if (buy) exitValue else entryValue
        val turnover = buyValue + sellValue
        val stt = sellValue * 0.00025
        val exchange = turnover * 0.0000297
        val sebi = turnover * 0.000001
        val stamp = buyValue * 0.00003
        val gst = (brokerage + exchange + sebi) * 0.18
        return gross to brokerage + stt + exchange + sebi + stamp + gst
    }
}
