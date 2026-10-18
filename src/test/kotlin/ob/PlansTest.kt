package ob

import ob.engine.Plan
import ob.engine.Plans
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlansTest {
    private val day = LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(IST).toEpochSecond()
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Candle(at(h, m), o, hi, lo, c, 100)

    // buy at 100, stop 99 (risk 1), T1 101.5 (1.5R), T2 103 (3R)
    private val buy = Plan("X:bull:0", "X", "Banks", "bull", 1, at(10, 0), 100.0, 99.0, 101.5, 103.0, 99.2, 100.4, 3.0, 80)

    @Test
    fun `waits until price comes back to the entry`() {
        val s = Plans.replay(buy, listOf(bar(10, 0, 100.6, 100.9, 100.3, 100.5)), 100.5)
        assertEquals("waiting", s.status)
        assertNull(s.r)
    }

    @Test
    fun `T1 books half and moves the stop to entry`() {
        val s = Plans.replay(buy, listOf(
            bar(10, 0, 100.4, 100.5, 99.9, 100.2),   // fills at 100
            bar(10, 1, 100.2, 101.6, 100.2, 101.4),  // T1
            bar(10, 2, 101.4, 101.4, 99.95, 100.1),  // back to entry: runner out flat
        ), 100.1)
        assertEquals("breakeven", s.status)
        assertEquals(0.75, s.r) // half at +1.5R, half at 0
    }

    @Test
    fun `both targets is a win`() {
        val s = Plans.replay(buy, listOf(bar(10, 0, 100.4, 100.5, 99.9, 100.2), bar(10, 5, 101.0, 103.2, 100.9, 103.1)), 103.1)
        assertEquals("won", s.status)
        assertEquals(2.25, s.r)
    }

    @Test
    fun `a stop touch is a full loss and a gap through it fills at the open`() {
        val s = Plans.replay(buy, listOf(bar(10, 0, 100.4, 100.5, 99.9, 100.2), bar(10, 3, 98.5, 98.8, 98.4, 98.6)), 98.6)
        assertEquals("stopped", s.status)
        assertEquals(98.5, s.exitPrice)
        assertEquals(-1.5, s.r)
    }

    @Test
    fun `running to T1 without a retest is a miss, not a win`() {
        val s = Plans.replay(buy, listOf(bar(10, 0, 100.6, 101.6, 100.4, 101.5)), 101.5)
        assertEquals("missed", s.status)
        assertNull(s.filledT)
    }

    @Test
    fun `no entry after 2-30 and open trades close at 3-15`() {
        assertEquals("expired", Plans.replay(buy, listOf(bar(14, 30, 100.6, 100.8, 100.4, 100.5)), 100.5).status)
        val s = Plans.replay(buy, listOf(bar(10, 0, 100.4, 100.5, 99.9, 100.2), bar(15, 15, 100.5, 100.6, 100.4, 100.5)), 100.5)
        assertEquals("closed", s.status)
        assertEquals(0.5, s.r)
    }

    @Test
    fun `a newer zone replaces a plan that never filled`() {
        val p = buy.copy(cancelT = at(10, 2))
        assertEquals("cancelled", Plans.replay(p, listOf(bar(10, 0, 100.6, 100.8, 100.4, 100.5), bar(10, 2, 100.2, 100.3, 99.9, 100.0)), 100.0).status)
    }

    @Test
    fun `sell plans mirror buy plans`() {
        val sell = Plan("Y:bear:0", "Y", "IT", "bear", 1, at(11, 0), 100.0, 101.0, 98.5, 97.0, 99.6, 100.8, 3.0, 75)
        val s = Plans.replay(sell, listOf(bar(11, 0, 99.6, 100.1, 99.5, 99.8), bar(11, 4, 99.0, 99.1, 96.9, 97.0)), 97.0)
        assertEquals("won", s.status)
        assertEquals(2.25, s.r)
        val open = Plans.replay(sell, listOf(bar(11, 0, 99.6, 100.1, 99.5, 99.8)), 99.5)
        assertEquals("active", open.status)
        assertEquals(0.5, open.openR)
    }
}
