package ir.meelano.vpn.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One parser for the /v/ payload, used by the repository AND by the boot-time reconnect path.
 *
 * Lenient on purpose: a node with an unknown protocol must not crash the app, and one broken
 * node must not throw away the other 40. Unknown fields are ignored, which is also what lets you
 * ship new fields from the server without releasing an APK.
 */
object FeedJson {

    /** refuse a payload from a future server we cannot understand instead of guessing */
    const val SCHEMA_MAX = 9

    fun payload(body: String): FeedPayload? = runCatching {
        val o = JSONObject(body)
        if (o.optInt("schema", 2) > SCHEMA_MAX) return null
        val arr: JSONArray = o.optJSONArray("servers") ?: return null
        val nodes = ArrayList<FeedNode>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { node(it) }?.let { nodes += it }
        }
        FeedPayload(
            kind = o.optString("kind"),
            generatedAt = o.optLong("generatedAt"),
            ttl = o.optInt("ttl", 300),
            nodes = nodes,
        )
    }.getOrNull()

    fun node(s: JSONObject): FeedNode? {
        val host = s.optString("host")
        val port = s.optInt("port")
        if (host.isBlank() || port !in 1..65535) return null
        val q = s.optJSONObject("quality")
        return FeedNode(
            id = s.optString("id"),
            slot = s.optInt("slot", 0),
            // Display name comes from the server: the vendor's own remark never reaches the
            // payload, so there is nothing to hide client-side (and nothing to leak in a screenshot).
            name = s.optString("title").ifBlank { s.optString("name") }.ifBlank { "Vip Meelano" },
            subtitle = s.optString("subtitle"),
            cc = s.optString("cc").ifBlank { null },
            kind = s.optString("tier", "vip"),
            tier = s.optString("tier", "vip"),
            proto = s.optString("proto"),
            host = host,
            port = port,
            tls = s.optString("tls").ifBlank { null },
            network = s.optString("network").ifBlank { null },
            sni = s.optString("sni").ifBlank { null },
            path = s.optString("path").ifBlank { null },
            hostHeader = s.optString("hostHeader").ifBlank { null },
            alpn = s.optString("alpn").ifBlank { null },
            flow = s.optString("flow").ifBlank { null },
            pbk = s.optString("pbk").ifBlank { null },
            sid = s.optString("sid").ifBlank { null },
            fingerprint = s.optString("fingerprint").ifBlank { null },
            userId = s.optString("userId").ifBlank { null },
            alterId = if (s.has("alterId")) s.optInt("alterId") else null,
            password = s.optString("password").ifBlank { null },
            username = s.optString("username").ifBlank { null },
            method = s.optString("method").ifBlank { null },
            cipher = s.optString("cipher").ifBlank { null },
            insecure = s.optBoolean("insecure", false),
            supportsUdp = s.optBoolean("supportsUdp", true),
            raw = s.optString("raw").ifBlank { null },
            grade = q?.optString("grade").orEmpty().ifBlank { "D" },
            latencyMs = if (q != null && q.has("latencyMs") && !q.isNull("latencyMs")) q.optLong("latencyMs") else null,
            reliability = (q?.optDouble("reliability", 0.0) ?: 0.0).toFloat(),
            samples = q?.optInt("samples", 0) ?: 0,
            config = s.optJSONObject("config")?.toString(),
        )
    }

    fun encode(n: FeedNode): JSONObject = JSONObject()
        .put("id", n.id).put("slot", n.slot).put("title", n.name).put("subtitle", n.subtitle)
        .put("cc", n.cc ?: "").put("tier", n.tier).put("proto", n.proto)
        .put("host", n.host).put("port", n.port).put("tls", n.tls ?: "")
        .put("network", n.network ?: "").put("sni", n.sni ?: "").put("path", n.path ?: "")
        .put("hostHeader", n.hostHeader ?: "").put("alpn", n.alpn ?: "").put("flow", n.flow ?: "")
        .put("pbk", n.pbk ?: "").put("sid", n.sid ?: "").put("fingerprint", n.fingerprint ?: "")
        .put("userId", n.userId ?: "").put("password", n.password ?: "")
        .put("method", n.method ?: "").put("cipher", n.cipher ?: "")
        .put("insecure", n.insecure).put("supportsUdp", n.supportsUdp)
        .put("raw", n.raw ?: "")
        .put(
            "quality",
            JSONObject().put("grade", n.grade).put("reliability", n.reliability.toDouble())
                .put("samples", n.samples).put("latencyMs", n.latencyMs ?: JSONObject.NULL)
        )
}
