package ob

import ob.market.Series
import ob.market.aggregate
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesTest {
    private val day = LocalDate.of(2026, 10, 16)
    private val open = sessionOpenSec(day)
    private fun series() = Series(Stock("SBIN", "SBI", "K", "Banks"), open, sessionCloseSec(day))

    @Test
    fun `ticks build minute candles with volume from the running total`() {
        val s = series()
        s.tick((open + 5) * 1000, 100.0, 1000)
        s.tick((open + 20) * 1000, 101.0, 1500)
        s.tick((open + 59) * 1000, 99.5, 1600)
        s.tick((open + 61) * 1000, 100.2, 2000)
        s.tick((open + 30) * 1000, 150.0, 2100) // late tick for a finished minute: no candle change

        val m = s.minutes()
        assertEquals(2, m.size)
        assertEquals(Candle(open, 100.0, 101.0, 99.5, 99.5, 600), m[0])
        assertEquals(Candle(open + 60, 100.2, 100.2, 100.2, 100.2, 400), m[1])
        assertEquals(150.0, s.lastPrice())
    }

    @Test
    fun `ticks outside the session move the price but make no candles`() {
        val s = series()
        s.tick((open - 300) * 1000, 98.0)
        assertEquals(0, s.minutes().size)
        assertEquals(98.0, s.lastPrice())
    }

    @Test
    fun `five-minute candles line up with the open`() {
        val s = series()
        for (i in 0 until 7) s.upsert(Candle(open + i * 60, 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 10), open + 3600)
        val five = aggregate(s.minutes())
        assertEquals(2, five.size)
        assertEquals(Candle(open, 100.0, 105.0, 99.0, 104.5, 50), five[0])
        assertEquals(Candle(open + 300, 105.0, 107.0, 104.0, 106.5, 20), five[1])
    }

    @Test
    fun `exchange minute bars replace tick-built ones`() {
        val s = series()
        s.tick((open + 5) * 1000, 100.0)
        s.tick((open + 65) * 1000, 101.0)
        s.upsert(Candle(open, 99.0, 102.0, 98.0, 100.5, 500), open + 70)
        assertEquals(Candle(open, 99.0, 102.0, 98.0, 100.5, 500), s.minutes()[0])
        assertEquals(2, s.minutes().size)
    }

    @Test
    fun `quiet minutes close on the clock`() {
        val s = series()
        s.tick((open + 5) * 1000, 100.0)
        s.closeBefore(open + 59)
        s.tick((open + 50) * 1000, 100.4)
        s.closeBefore(open + 60)
        s.tick((open + 55) * 1000, 120.0) // the closed minute stays closed
        assertEquals(Candle(open, 100.0, 100.4, 100.0, 100.4, 0), s.minutes().single())
    }
}
