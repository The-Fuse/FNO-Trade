package ob

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

const val PREOPEN_MIN = 9 * 60
const val OPEN_MIN = 9 * 60 + 15
const val CLOSE_MIN = 15 * 60 + 30

/** Market time. Live feeds use the wall clock; the simulator runs its own, faster one. */
fun interface MarketClock {
    fun nowMs(): Long
}

val SystemClock = MarketClock { System.currentTimeMillis() }

fun sessionOpenSec(date: LocalDate): Long = date.atTime(9, 15).atZone(IST).toEpochSecond()
fun sessionCloseSec(date: LocalDate): Long = date.atTime(15, 30).atZone(IST).toEpochSecond()

fun istDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(IST).toLocalDate()
fun istMinuteOfDay(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(IST).let { it.hour * 60 + it.minute }

fun bucket(sec: Long, size: Long): Long = Math.floorDiv(sec, size) * size

fun isTradingDay(d: LocalDate, holidays: Set<LocalDate>): Boolean =
    d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d !in holidays

/** Today from pre-open onwards on a trading day; otherwise the last trading day, whose closing board is shown. */
fun sessionDate(nowMs: Long, holidays: Set<LocalDate>): LocalDate {
    var d = istDate(nowMs)
    if (isTradingDay(d, holidays) && istMinuteOfDay(nowMs) >= PREOPEN_MIN) return d
    do d = d.minusDays(1) while (!isTradingDay(d, holidays))
    return d
}

private val DAY = DateTimeFormatter.ofPattern("d MMM")

fun marketState(nowMs: Long, session: LocalDate, prefix: String? = null): MarketState {
    val m = istMinuteOfDay(nowMs)
    val s = when {
        session != istDate(nowMs) -> MarketState("closed", "Market closed · ${session.format(DAY)} session")
        m < PREOPEN_MIN -> MarketState("closed", "Opens 9:15")
        m < OPEN_MIN -> MarketState("pre", "Pre-open")
        m < CLOSE_MIN -> MarketState("open", "Market open")
        else -> MarketState("closed", "Market closed")
    }
    return if (prefix == null) s else s.copy(label = "$prefix · ${s.label}")
}
