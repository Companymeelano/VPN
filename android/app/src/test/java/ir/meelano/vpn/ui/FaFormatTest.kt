package ir.meelano.vpn.ui

import ir.meelano.vpn.data.FeedNode
import ir.meelano.vpn.data.normalizeEpochMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numeric/bidi rendering rules as contracts — every case here is one screenshot bug:
 * «۲۰۷۳۵ روز پیش» (seconds read as millis), «زیرms۲۰۰» (unisolated mixed run), «860» with no
 * unit (Latin latency), «0/48» (Latin tab counts), «— روز پیش» (never-synced placeholder).
 */
class FaFormatTest {

    @Test fun `toFa maps digits and nothing else`() {
        assertEquals("۰۱۲۳۴۵۶۷۸۹", toFa("0123456789"))
        assertEquals("http · ۱۷۹۸۱", toFa("http · 17981"))
    }

    @Test fun `iso wraps with LRI and PDI`() {
        assertEquals("\u2066۲۰۰ms\u2069", faIso("200ms"))
    }

    @Test fun `seconds timestamps normalize to millis`() {
        // 1.8e9 is a PHP time() value; read as millis it becomes «۵۶ سال پیش».
        assertEquals(1_800_000_000_000L, normalizeEpochMs(1_800_000_000L))
        // already-millis values pass through untouched
        assertEquals(1_800_000_000_000L, normalizeEpochMs(1_800_000_000_000L))
        assertEquals(0L, normalizeEpochMs(0L))
    }

    @Test fun `relTime reads a seconds feed timestamp as recent`() {
        val secondsAgo = ((System.currentTimeMillis() - 5 * 60_000) / 1000)
        val label = relTime(secondsAgo)
        assertTrue("got $label", label.endsWith(" دقیقه پیش"))
    }

    @Test fun `relTime never syncs reads هرگز not a dash`() {
        assertEquals("هرگز", relTime(0L))
    }

    @Test fun `relTime renders Persian digits`() {
        val ts = System.currentTimeMillis() - (90 * 60_000) // 1.5 h ago
        assertEquals("۱ ساعت پیش", relTime(ts))
    }

    @Test fun `latencyLabel is Persian isolated and never unit-less`() {
        val fast = FeedNode(id = "a", name = "a", host = "h", port = 1, proto = "vless", latencyMs = 860)
        assertEquals("\u2066۸۶۰ms\u2069", fast.latencyLabel)
        val slow = FeedNode(id = "b", name = "b", host = "h", port = 1, proto = "vless", latencyMs = 2200)
        assertEquals("\u2066۲٫۲s\u2069", slow.latencyLabel)
        val none = FeedNode(id = "c", name = "c", host = "h", port = 1, proto = "vless", latencyMs = null)
        assertEquals("—", none.latencyLabel)
    }
}
