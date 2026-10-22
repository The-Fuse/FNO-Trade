package ob.trade

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ob.engine.PlanState
import ob.web.json
import java.io.File
import java.time.LocalDate

@Serializable
data class AccountSettings(val capital: Double = 10_000.0, val riskPct: Double = 4.0, val minRisk: Double = 250.0)

@Serializable
data class AccountUpdate(val capital: Double? = null, val riskPct: Double? = null)

/** One session's result after charges. */
@Serializable
data class DayResult(val date: String, val trades: Int, val net: Double)

@Serializable
data class AccountView(
    val capital: Double,       // starting capital
    val riskPct: Double,
    val minRisk: Double,
    val startOfDay: Double,    // balance carried into today
    val realised: Double,      // today's exited trades, after charges
    val balance: Double,       // startOfDay + realised
    val days: List<DayResult>, // oldest first, today included
)

@Serializable
private data class AccountFile(val settings: AccountSettings = AccountSettings(), val days: Map<String, DayResult> = emptyMap())

/**
 * A paper account that carries its balance from day to day: each session's net result (after charges) is added,
 * and the next session's trades are sized from the new balance, as in the backtest. Saved in data/account.json.
 */
class Account(private val file: File) {
    private var data: AccountFile = runCatching { json.decodeFromString<AccountFile>(file.readText()) }.getOrElse { AccountFile() }

    @Synchronized
    fun settings(): AccountSettings = data.settings

    @Synchronized
    fun update(capital: Double?, riskPct: Double?): AccountSettings {
        val s = data.settings
        data = data.copy(settings = s.copy(capital = capital?.coerceIn(1_000.0, 1e9) ?: s.capital, riskPct = riskPct?.coerceIn(0.1, 5.0) ?: s.riskPct))
        save()
        return data.settings
    }

    /** Clears the day history; the next session starts from the starting capital again. */
    @Synchronized
    fun reset() {
        data = data.copy(days = emptyMap())
        save()
    }

    @Synchronized
    fun startOfDay(date: LocalDate): Double = data.settings.capital + data.days.values.filter { it.date < date.toString() }.sumOf { it.net }

    @Synchronized
    fun view(date: LocalDate): AccountView = view(date, startOfDay(date), data.days[date.toString()]?.net ?: 0.0)

    /** Sizes the day's plans from the balance carried into it, records the day's result so far, and returns both. */
    @Synchronized
    fun sizeDay(date: LocalDate, plans: List<PlanState>): Pair<List<PlanState>, AccountView> {
        val s = data.settings
        val start = startOfDay(date)
        val sized = Sizing.sizeAll(plans.filter { it.filledT != null }, start, s.riskPct, s.minRisk)
        val out = plans.map { ps -> sized[ps.plan.id]?.let { ps.copy(size = it) } ?: ps }
        val done = out.filter { it.exitT != null && (it.size?.qty ?: 0) > 0 }
        val today = DayResult(date.toString(), done.size, done.sumOf { it.size!!.net })
        if (data.days[today.date] != today) {
            data = data.copy(days = data.days + (today.date to today))
            save()
        }
        return out to view(date, start, today.net)
    }

    private fun view(date: LocalDate, start: Double, realised: Double) = AccountView(
        capital = data.settings.capital,
        riskPct = data.settings.riskPct,
        minRisk = data.settings.minRisk,
        startOfDay = start,
        realised = realised,
        balance = start + realised,
        days = data.days.values.filter { it.date <= date.toString() }.sortedBy { it.date }.takeLast(60),
    )

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(data))
        }
    }
}
