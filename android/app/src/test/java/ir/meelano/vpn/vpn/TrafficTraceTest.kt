package ir.meelano.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window the speed chart draws from. Pure Kotlin, on purpose: if trimming or mark-shifting is wrong,
 * the chart silently lies about when the session started, and no device test would ever catch that.
 */
class TrafficTraceTest {

    @Test
    fun appendsUntilItIsFullThenSlides() {
        var t = TrafficTrace()
        repeat(TrafficTrace.CAPACITY) { i -> t = t.push(i.toFloat(), 0f, mark = false) }
        assertEquals(TrafficTrace.CAPACITY, t.down.size)
        assertEquals(0f, t.down[0], 0f)

        t = t.push(999f, 0f, mark = false)
        assertEquals("window must stay capped", TrafficTrace.CAPACITY, t.down.size)
        assertEquals("the oldest sample must leave first", 1f, t.down[0], 0f)
        assertEquals("newest sample is last", 999f, t.down[t.down.size - 1], 0f)
        assertEquals(TrafficTrace.CAPACITY + 1, t.totalSamples)
    }

    @Test
    fun marksAreWindowRelativeAndScrollOut() {
        var t = TrafficTrace()
        t = t.push(1f, 1f, mark = true)          // index 0
        t = t.push(2f, 2f, mark = false)
        assertEquals(1, t.marks.size)
        assertEquals(0, t.marks[0])

        // fill past the capacity: the mark at sample 0 must disappear, not stick to the left edge
        var u = TrafficTrace()
        u = u.push(1f, 1f, mark = true)
        repeat(TrafficTrace.CAPACITY) { i -> u = u.push(i.toFloat(), 0f, mark = false) }
        assertEquals("a scrolled-out mark is dropped", 0, u.marks.size)

        // a mark that survives the trim must be renumbered, or it points at the wrong sample
        var v = TrafficTrace()
        repeat(TrafficTrace.CAPACITY - 3) { i -> v = v.push(i.toFloat(), 0f, mark = false) }
        v = v.push(7f, 7f, mark = true)                       // index CAPACITY-3
        repeat(4) { i -> v = v.push(i.toFloat(), 0f, mark = false) }
        assertEquals(1, v.marks.size)
        assertTrue("mark must still address a real sample", v.marks[0] in v.down.indices)
        // The mark was written as sample #(CAPACITY-3). Four samples arrived; only two of them pushed the
        // window past its cap, so the window trimmed exactly twice: the mark must have shifted left by 2.
        // Written as arithmetic on the cap, because "obviously correct" chart offsets are the ones that rot.
        assertEquals(TrafficTrace.CAPACITY - 3 - 2, v.marks[0])
    }

    @Test
    fun peakHasAFloorAndCoversBothDirections() {
        val idle = TrafficTrace().push(1f, 2f, mark = false)
        assertEquals(TrafficTrace.MIN_PEAK, idle.peak(), 0f)
        val busy = TrafficTrace(floatArrayOf(1f, 90_000f), floatArrayOf(4f, 1_000f))
        assertEquals(90_000f, busy.peak(), 0f)
    }

    @Test
    fun upAndDownStayTheSameLength() {
        var t = TrafficTrace()
        repeat(40) { i -> t = t.push(i.toFloat(), (i * 2).toFloat(), mark = i % 7 == 0) }
        assertEquals(t.down.size, t.up.size)
        assertEquals(6, t.marks.size)
    }
}
