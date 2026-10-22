package ob

import ob.engine.Plan
import ob.engine.PlanState
import ob.trade.Account
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountTest {
    private val day1 = LocalDate.of(2026, 10, 22)
    private val day2 = LocalDate.of(2026, 10, 23)
    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(IST).toEpochSecond()

    // buy at 100, stop 99, filled and stopped out
    private val plan = Plan("X:bull:0", "X", "Banks", "bull", 1, at(day1, 10, 0), 100.0, 99.0, 101.5, 103.0, 99.2, 100.4, 3.0, 80)
    private val stopped = PlanState(plan, "stopped", filledT = at(day1, 10, 5), fill = 100.0, exitT = at(day1, 10, 20), exitPrice = 99.0, r = -1.0)

    @Test
    fun `the balance carries a day's result into the next day, and survives a restart`() {
        val file = File(createTempDirectory("account").toFile(), "account.json")
        val a = Account(file)
        a.update(capital = 10_000.0, riskPct = 4.0)

        val (sized, v1) = a.sizeDay(day1, listOf(stopped))
        val z = sized.single().size!!
        assertEquals(400, z.qty)                       // ₹400 at risk ÷ ₹1 a share; margin allows 500
        assertEquals(-400.0, z.gross)
        assertTrue(z.costs in 55.0..70.0, "charges ${z.costs}")
        assertEquals(z.net, v1.realised)
        assertEquals(10_000.0 + z.net, v1.balance)

        val (_, v2) = a.sizeDay(day2, emptyList())
        assertEquals(v1.balance, v2.startOfDay, 1e-6)  // the next day starts from what was left
        assertEquals(2, v2.days.size)

        assertEquals(v2.startOfDay, Account(file).startOfDay(day2), 1e-6)
        Account(file).reset()
        assertEquals(10_000.0, Account(file).startOfDay(day2))
    }
}
