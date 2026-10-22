package ob.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ob.Candle
import ob.istMinuteOfDay
import ob.r2
import ob.web.json
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs

/**
 * A suggestion as it was made: frozen when a top pick first shows a tradeable setup.
 * The plan: limit order at [entry]; stop at [stop]; book half at [t1] and move the stop to entry;
 * exit the rest at [t2]; no entry after 2:30 PM; anything open closes at 3:15 PM.
 */
@Serializable
data class Plan(
    val id: String,
    val symbol: String,
    val sector: String,
    val side: String,
    val pick: Int,
    val createdT: Long,
    val entry: Double,
    val stop: Double,
    val t1: Double,
    val t2: Double,
    val zoneLo: Double,
    val zoneHi: Double,
    val rr: Double,
    val conv: Int,
    var cancelT: Long? = null,   // a newer zone replaced it; applies only while still waiting
)

/**
 * Where the plan stands, replayed from 1-minute candles since it was made.
 * status: waiting, active, t1 (half booked, stop at entry), won, breakeven, stopped, missed (ran to T1
 * without a retest), expired (no entry by 2:30), cancelled, closed (3:15 exit).
 */
@Serializable
data class PlanState(
    val plan: Plan,
    val status: String,
    val filledT: Long? = null,
    val fill: Double? = null,
    val exitT: Long? = null,
    val exitPrice: Double? = null,
    val r: Double? = null,       // realised, in multiples of the planned risk
    val openR: Double? = null,   // marked to the last price while in the trade
    val t1Hit: Boolean = false,
)

@Serializable
data class Scorecard(val plans: Int, val triggered: Int, val wins: Int, val losses: Int, val open: Int, val netR: Double, val openR: Double)

object Plans {
    val OPEN = setOf("active", "t1")

    fun replay(p: Plan, minutes: List<Candle>, ltp: Double): PlanState {
        val bull = p.side == "bull"
        fun hit(level: Double, x: Double) = if (bull) x <= level else x >= level        // price came back to it
        fun reached(level: Double, x: Double) = if (bull) x >= level else x <= level    // price got there
        var status = "waiting"
        var fill = p.entry
        var filledT: Long? = null
        var t1Hit = false
        var exitT: Long? = null
        var exitPx: Double? = null

        for (c in minutes) {
            if (c.t < p.createdT) continue
            val mod = istMinuteOfDay(c.t * 1000)
            val adverse = if (bull) c.l else c.h
            val favorable = if (bull) c.h else c.l
            if (status == "waiting") {
                val cancel = p.cancelT
                if (cancel != null && c.t >= cancel) { status = "cancelled"; exitT = cancel; break }
                if (hit(p.entry, adverse)) {
                    fill = if (hit(p.entry, c.o)) c.o else p.entry // opened through the entry: filled at the open
                    filledT = c.t
                    status = "active"
                    if (hit(p.stop, adverse)) { status = "stopped"; exitT = c.t; exitPx = if (hit(p.stop, c.o)) c.o else p.stop; break }
                } else if (reached(p.t1, favorable)) { status = "missed"; exitT = c.t; break }
                else if (mod >= Rules.LAST_ENTRY_MIN) { status = "expired"; exitT = c.t; break }
                continue
            }
            if (status == "active") {
                if (hit(p.stop, adverse)) { status = "stopped"; exitT = c.t; exitPx = if (hit(p.stop, c.o)) c.o else p.stop; break }
                if (reached(p.t1, favorable)) t1Hit = true
                when (Rules.TARGET) {
                    "t1" -> if (t1Hit) { status = "won"; exitT = c.t; exitPx = p.t1; break }
                    "t2" -> if (reached(p.t2, favorable)) { status = "won"; exitT = c.t; exitPx = p.t2; break }  // stop stays put
                    else -> if (t1Hit) {
                        status = "t1"
                        if (reached(p.t2, favorable)) { status = "won"; exitT = c.t; exitPx = p.t2; break }
                        continue
                    }
                }
                if (mod >= Rules.EXIT_MIN) { status = "closed"; exitT = c.t; exitPx = c.c; break }
                continue
            }
            // t1: the runner, with the stop at entry
            if (hit(p.entry, adverse)) { status = "breakeven"; exitT = c.t; exitPx = p.entry; break }
            if (reached(p.t2, favorable)) { status = "won"; exitT = c.t; exitPx = p.t2; break }
            if (mod >= Rules.EXIT_MIN) { status = "closed"; exitT = c.t; exitPx = c.c; break }
        }

        val risk = abs(p.entry - p.stop)
        fun r(px: Double) = (if (bull) px - fill else fill - px) / risk
        val half = Rules.TARGET == "half"
        val result = when (status) {
            "stopped" -> r(exitPx!!)
            "won" -> if (half) 0.5 * r(p.t1) + 0.5 * r(p.t2) else r(exitPx!!)
            "breakeven" -> 0.5 * r(p.t1) + 0.5 * r(p.entry)
            "closed" -> if (half && t1Hit) 0.5 * r(p.t1) + 0.5 * r(exitPx!!) else r(exitPx!!)
            else -> null
        }
        val open = when (status) {
            "active" -> r(ltp)
            "t1" -> 0.5 * r(p.t1) + 0.5 * r(ltp)
            else -> null
        }
        return PlanState(
            plan = p,
            status = status,
            filledT = filledT,
            fill = filledT?.let { r2(fill) },
            exitT = exitT,
            exitPrice = exitPx?.let(::r2),
            r = result?.let(::r2),
            openR = open?.let(::r2),
            t1Hit = t1Hit,
        )
    }

    fun scorecard(states: List<PlanState>) = Scorecard(
        plans = states.size,
        triggered = states.count { it.filledT != null },
        wins = states.count { (it.r ?: 0.0) > 0 },
        losses = states.count { (it.r ?: 0.0) < 0 },
        open = states.count { it.status in OPEN },
        netR = r2(states.sumOf { it.r ?: 0.0 }),
        openR = r2(states.sumOf { it.openR ?: 0.0 }),
    )
}

/** The day's suggestions, saved to [file] (live feed) so a restart keeps the scorecard. */
class PlanBook(private val file: File?) {
    private val log = LoggerFactory.getLogger("plans")
    private val plans = ArrayList<Plan>()

    init {
        val f = file
        if (f != null && f.exists()) {
            runCatching { plans += json.decodeFromString<List<Plan>>(f.readText()) }
                .onFailure { log.warn("Ignoring unreadable {}: {}", f, it.message) }
        }
    }

    /** Freezes a plan for a pick's current setup, once per order block. */
    @Synchronized
    fun ensure(r: Row, createdT: Long) {
        val s = r.setup ?: return
        val id = "${r.symbol}:${s.side}:${s.zone.t}"
        if (plans.any { it.id == id }) return
        plans.filter { it.symbol == r.symbol && it.side == s.side && it.cancelT == null }.forEach { it.cancelT = createdT }
        plans += Plan(
            id = id, symbol = r.symbol, sector = r.sector, side = s.side, pick = r.pick ?: 0, createdT = createdT,
            entry = s.z, stop = s.stop, t1 = s.targets[0].price, t2 = s.targets[1].price,
            zoneLo = s.zone.lo, zoneHi = s.zone.hi, rr = s.rr, conv = r.conv,
        )
        save()
    }

    @Synchronized
    fun all(): List<Plan> = plans.map { it.copy() }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(plans))
        }.onFailure { log.warn("Couldn't save plans: {}", it.message) }
    }
}
