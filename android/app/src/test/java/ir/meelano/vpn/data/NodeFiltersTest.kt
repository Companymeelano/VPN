package ir.meelano.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFiltersTest {

    private fun node(
        id: String,
        cc: String? = null,
        latency: Long? = null,
        grade: String = "D",
        reliability: Float = 0f,
        usable: Boolean = true,
    ) = FeedNode(
        id = id, cc = cc, grade = grade, latencyMs = latency, reliability = reliability,
        proto = "http", platformStatus = if (usable) ProxyScanner.CLEAN else ProxyScanner.DEAD,
    )

    private val rows = listOf(
        node("a", cc = "DE", latency = 120, grade = "A", reliability = 0.9f),
        node("b", cc = "TR", latency = 380, grade = "B"),
        node("c", cc = "DE", latency = 900, grade = "C"),
        node("d", cc = null, latency = 50, grade = "A"),
        node("e", cc = "US", latency = null, grade = "D"),
    )

    @Test fun `kind filters measure only what is known`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), NodeFilters.apply(rows, NodeFilters.KIND_ALL, null, null).map { it.id })
        // near = has a measurement
        assertEquals(listOf("a", "b", "c", "d"), NodeFilters.apply(rows, NodeFilters.KIND_NEAR, null, null).map { it.id })
        // fast = measured and <= 400 ms; the never-measured row must NOT sneak in
        assertEquals(listOf("a", "b", "d"), NodeFilters.apply(rows, NodeFilters.KIND_FAST, null, null).map { it.id })
        assertEquals(listOf("a", "b", "d"), NodeFilters.apply(rows, NodeFilters.KIND_WORKS, null, null).map { it.id })
    }

    @Test fun `country chip matches case-insensitively and hides unknown rows`() {
        assertEquals(listOf("a", "c"), NodeFilters.apply(rows, NodeFilters.KIND_ALL, "DE", null).map { it.id })
        assertEquals(listOf("a", "c"), NodeFilters.apply(rows, NodeFilters.KIND_ALL, "de", null).map { it.id })
        // unknown-cc rows only appear when no country filter is active
        assertEquals(listOf("d"), NodeFilters.apply(rows, NodeFilters.KIND_ALL, null, null).filter { it.cc == null }.map { it.id })
        assertTrue(NodeFilters.apply(rows, NodeFilters.KIND_ALL, "TR", null).none { it.cc == null })
    }

    @Test fun `ping ceiling hides unmeasured rows too`() {
        assertEquals(listOf("a", "d"), NodeFilters.apply(rows, NodeFilters.KIND_ALL, null, 200L).map { it.id })
        // combined with country
        assertEquals(listOf("a"), NodeFilters.apply(rows, NodeFilters.KIND_ALL, "DE", 200L).map { it.id })
    }

    @Test fun `countries are ordered by presence then name`() {
        assertEquals(listOf("DE", "TR", "US"), NodeFilters.countries(rows))
    }

    @Test fun `median ping ignores unmeasured rows`() {
        // measured = [50, 120, 380, 900] → upper-middle = 380
        assertEquals(380L, NodeFilters.medianPingMs(rows))
        assertNull(NodeFilters.medianPingMs(rows.filter { it.latencyMs == null }))
    }

    @Test fun `sort keeps pinned first and usable before broken`() {
        val sorted = NodeFilters.sorted(
            rows.map { if (it.id == "c") it.copy(grade = "A") else it },
            pinned = setOf("c"),
        )
        assertEquals("c", sorted.first().id)
    }
}
