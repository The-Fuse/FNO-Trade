package ob

import ob.engine.DayLevels
import ob.engine.Level
import ob.engine.Levels
import ob.engine.OrderBlocks
import ob.engine.Rules
import ob.engine.Strength
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineRulesTest {
    // the level cases below describe the raw block levels; the live defaults widen stops and filter by market
    @BeforeTest fun rawLevels() = Rules.configure { mapOf("MIN_STOP_PCT" to "0", "MIN_MARKET" to "0", "PICKS" to "3")[it] }
    @AfterTest fun restore() = Rules.reset()

    private fun bars(vararg ohlc: Double): List<Candle> =
        ohlc.toList().chunked(4).mapIndexed { i, (o, h, l, c) -> Candle(i * 300L, o, h, l, c, 100) }

    // swing high at 2 (103), a three-candle pullback to 99.8, then a gap-leaving rally that closes above 103
    private val rally = bars(
        100.0, 101.0, 99.5, 100.5,
        100.5, 102.0, 100.2, 101.8,
        101.8, 103.0, 101.5, 102.6,
        102.6, 102.8, 101.4, 101.6,
        101.6, 101.9, 100.6, 100.8,
        100.8, 101.0, 99.8, 100.0,   // 5: last red candle where the rise began
        100.0, 101.8, 99.9, 101.7,
        101.7, 103.6, 101.6, 103.5,  // 7: closes above 103, leaves a gap over candle 5
    )

    @Test
    fun `bullish order block is the last red candle of the leg, proven by a gap`() {
        val z = OrderBlocks.analyze(rally).zones.single()
        assertEquals("bull", z.side)
        assertEquals(5 * 300L, z.t)
        assertEquals(99.8, z.lo)
        assertEquals(101.0, z.hi)
        assertEquals(100.8, z.body)
        assertEquals(7 * 300L, z.formedT)
        assertEquals(103.0, z.bos)
        assertEquals(99.8, z.origin)
        assertNull(z.brokenT)
    }

    @Test
    fun `a close below the zone kills it and a retest is counted first`() {
        val more = rally + bars(
            103.5, 104.0, 102.9, 103.8,
            103.8, 103.9, 100.8, 101.2,  // dips into the zone
            101.2, 101.3, 99.0, 99.2,    // closes below 99.8
        ).map { it.copy(t = it.t + 8 * 300) }
        val zones = OrderBlocks.analyze(more).zones
        val bull = zones.single { it.side == "bull" }
        assertEquals(1, bull.tests)
        assertEquals(10 * 300L, bull.brokenT)
        // the same close broke the swing low at 99.8: candle 8, the last green one, is the bearish block
        val bear = zones.single { it.side == "bear" }
        assertEquals(8 * 300L, bear.t)
        assertEquals(102.9, bear.lo)
        assertEquals(104.0, bear.hi)
    }

    @Test
    fun `a break without a clean move away draws no zone`() {
        val weak = bars(
            100.0, 101.0, 99.5, 100.5,
            100.5, 102.0, 100.2, 101.8,
            101.8, 103.0, 101.5, 102.6,
            102.6, 102.8, 101.9, 102.0,
            102.0, 102.6, 101.8, 101.9,
            101.9, 102.5, 101.6, 101.7,
            101.7, 102.7, 101.6, 102.6,
            102.6, 103.3, 102.0, 103.1,  // closes above 103 by a hair, no gap
        )
        assertTrue(OrderBlocks.analyze(weak).zones.none { it.side == "bull" })
    }

    @Test
    fun `two swing highs at one price become equal-highs liquidity`() {
        val c = bars(
            100.0, 100.5, 99.5, 100.2,
            100.2, 101.0, 100.0, 100.8,
            100.8, 102.0, 100.6, 101.5,
            101.5, 101.6, 100.8, 101.0,
            101.0, 101.2, 100.5, 100.7,
            100.7, 101.5, 100.6, 101.3,
            101.3, 102.02, 101.1, 101.6,
            101.6, 101.7, 101.0, 101.2,
            101.2, 101.4, 100.9, 101.0,
        )
        val liq = OrderBlocks.analyze(c, pdh = 105.0, pdl = 98.0).liquidity
        val eqh = liq.single { it.kind == "equal" }
        assertEquals("buy", eqh.side)
        assertEquals(102.02, eqh.price)
        assertEquals(2, eqh.count)
        assertNull(eqh.takenT)
        assertNotNull(liq.singleOrNull { it.kind == "pdh" && it.takenT == null })
    }

    @Test
    fun `setup builds Z, the stop just past the block, targets ahead and levels behind`() {
        val a = OrderBlocks.analyze(rally + Candle(8 * 300L, 103.5, 104.0, 102.9, 103.8))
        val day = DayLevels(open = 100.0, openT = 0, high = 104.0, highT = 8 * 300L, low = 99.5, lowT = 0, prevClose = 99.0)
        val s = assertNotNull(Levels.setup(a, bull = true, ltp = 103.8, day = day))
        assertEquals(100.8, s.z)        // the red block's open, its body edge facing price
        assertEquals(99.75, s.stop)     // one tick under the block's low
        // the day's high is the only level ahead; the second target is projected (4R clears it)
        assertEquals(listOf(Level(104.0, "dayHigh", 8 * 300L), Level(105.0, "4R")), s.targets)
        assertEquals(4.0, s.rr)
        // the untaken swing low at 99.8 is too close to the stop; the open gap down at 99.0 counts
        assertEquals(listOf(Level(99.0, "gap", 0)), s.behind)
        assertNull(Levels.setup(a, bull = false, ltp = 103.8, day = day))
    }

    @Test
    fun `only clean setups are actionable`() {
        val a = OrderBlocks.analyze(rally + Candle(8 * 300L, 103.5, 104.0, 102.9, 103.8))
        val day = DayLevels(100.0, 0, 104.0, 8 * 300L, 99.5, 0, 99.0)
        val s = assertNotNull(Levels.setup(a, true, 103.8, day))
        val morning = 10 * 60
        val near = 0.3
        assertEquals("BUY", Rules.advice(true, false, s, 103.8, 102.0, morning, near).action)
        assertEquals("AVOID", Rules.advice(true, true, s, 103.8, 102.0, morning, near).action)
        assertEquals("WAIT", Rules.advice(true, false, s, 103.8, 102.0, morning, 0.8).action)        // a chase, not a retest
        assertEquals("WAIT", Rules.advice(true, false, s, 103.8, 104.5, morning, near).action)       // below VWAP
        assertEquals("WAIT", Rules.advice(true, false, s, 103.8, 102.0, 14 * 60 + 31, near).action) // too late
        assertEquals("WAIT", Rules.advice(true, false, s.copy(rr = 1.2), 103.8, 102.0, morning, near).action)
        assertEquals("WAIT", Rules.advice(true, false, null, 103.8, 102.0, morning, near).action)
    }

    @Test
    fun `normal day is the median true range, so one outlier day can't inflate it`() {
        val daily = (0 until 51).map { i ->
            if (i == 30) Candle(i.toLong(), 100.0, 150.0, 100.0, 100.0) // split-like spike
            else Candle(i.toLong(), 100.0, 101.0, 99.0, 100.0)
        }
        assertEquals(2.0, Strength.adrPct(daily), 1e-9)
        val cx = assertNotNull(Strength.context(daily))
        assertEquals(1.5, Strength.score(3.0, cx.adrPct), 1e-9)
    }

    @Test
    fun `a minimum stop distance widens tight stops and the targets follow`() {
        Rules.configure { mapOf("MIN_STOP_PCT" to "2.0")[it] }
        val a = OrderBlocks.analyze(rally + Candle(8 * 300L, 103.5, 104.0, 102.9, 103.8))
        val day = DayLevels(100.0, 0, 104.0, 8 * 300L, 99.5, 0, 99.0)
        val s = assertNotNull(Levels.setup(a, true, 103.8, day))
        assertEquals(98.78, s.stop)   // 2% under Z (100.8) instead of a tick under the block
        assertEquals(2.0, s.rr)       // the projected second target moves out with the wider risk
    }

    @Test
    fun `no trade against the market`() {
        Rules.configure { mapOf("MIN_MARKET" to "0.6")[it] }
        val a = OrderBlocks.analyze(rally + Candle(8 * 300L, 103.5, 104.0, 102.9, 103.8))
        val day = DayLevels(100.0, 0, 104.0, 8 * 300L, 99.5, 0, 99.0)
        val s = assertNotNull(Levels.setup(a, true, 103.8, day))
        assertEquals("WAIT", Rules.advice(true, false, s, 103.8, 102.0, 10 * 60, 0.3, marketWith = 0.5).action)
        assertEquals("BUY", Rules.advice(true, false, s, 103.8, 102.0, 10 * 60, 0.3, marketWith = 0.7).action)
    }
}
