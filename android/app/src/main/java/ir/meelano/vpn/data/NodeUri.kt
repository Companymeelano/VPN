package ir.meelano.vpn.data

import java.net.URLDecoder
import java.util.Locale

/**
 * In-app ingestion of raw proxy configs, so the app can build its own lists without the /v/ host.
 *
 * This is deliberately a *subset* of `backend/v/lib/Parser.php`, and the difference is on purpose: the
 * server also probes 900 candidates, gates them through CONNECT to Google/Cloudflare, keeps a ban ledger
 * and ranks by reliability over days. A phone must not do that - it would burn battery and the mobile
 * quota, and much of it would fail anyway, because the outbound path from a phone inside Iran is exactly
 * what the host was built to work around. What a phone *can* do cheaply:
 *
 *   - decode whatever the user pastes (their own configs: a VIP list needs no server at all);
 *   - read public lists and keep the lines that parse cleanly;
 *   - TCP-probe the head of the result (parallel, a few hundred ms - see DirectFeed);
 *   - mask the vendor's remark with the server's own rule: the app shows its brand + a country, never
 *     the upstream service's name.
 *
 * Pure JVM on purpose (no `android.*`, no org.json): that is what makes it unit-testable in CI, where
 * every Android and org.json call is a stub returning zero.
 */
object NodeUri {

    /** Ciphers a dialer can actually use. Kept in sync with `Parser::$ssMethods` on the server. */
    private val SS_METHODS = setOf(
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
        "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr",
        "chacha20-ietf", "chacha20-ietf-poly1305", "chacha20-poly1305", "xchacha20-ietf-poly1305",
        "sodium:chacha20-ietf-poly1305", "sodium:aes-256-gcm",
        "rc4-md5", "bf-cfb", "cast5-cfb", "idea-cfb", "rc2-cfb", "seed-cfb",
    )

    /** Protocols this app can hand to a core. Anything else is refused, not half-built. */
    private val TUNNEL_SCHEMES = setOf("vless", "vmess", "trojan", "ss", "hy2")

    private val URI_RE = Regex("""(?i)\b(vless|vmess|trojan|ss|hy2)://[^\s"'<>\\]+""")
    private val PROXY_LINE = Regex(
        """(?i)^(?:(socks5|socks4|http)://)?(?:([^\s:@/]+)(?::([^\s:@/]*))?@)?""" +
            """((?:\d{1,3}\.){3}\d{1,3}|[a-z0-9._-]+):(\d{2,5})(.*)$"""
    )
    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\n\r"

    /**
     * A blob of anything: pasted configs, a subscription link's body, an HTML page, a Telegram export,
     * or a JSON list. `tier` decides the label (and what the connect path is allowed to do with it).
     */
    fun parseBlob(blob: String, tier: String, brand: String): List<FeedNode> {
        val text = blob.trim()
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, FeedNode>()

        // 1) every scheme:// token, wherever it hides (works on HTML and exports, not just clean lists)
        for (m in URI_RE.findAll(text)) {
            parseUri(m.value.trim().trimEnd(',', ';', '"', '\'', ')', ']'))?.let { out.getOrPut(it.id) { it } }
        }

        // 2) one base64 line with no visible scheme: a subscription string. Unwrap it and try again.
        if (out.isEmpty() && !text.contains("://") && text.none { it == '\n' } && text.length in 24..400_000) {
            decodeBase64(text)?.let { inner ->
                for (m in URI_RE.findAll(inner)) {
                    parseUri(m.value.trim().trimEnd(',', ';', '"', '\'', ')', ']'))
                        ?.let { out.getOrPut(it.id) { it } }
                }
            }
        }

        // 3) bare proxy lines: ip:port, user:pass@ip:port, ip:port:user:pass
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trim(',', ';', '"', '\'', '<', '>', '(', ')')
            if (line.isEmpty() || line.startsWith("#") || line.contains("://")) return@forEach
            parseProxyLine(line)?.let { out.getOrPut(it.id) { it } }
        }

        // 4) a JSON list of {ip,port,proto,…} objects (monosans/jetkai shape - already geolocated upstream)
        if (text[0] == '[' || text[0] == '{') {
            jsonNodes(text)?.let { list -> list.forEach { out.getOrPut(it.id) { it } } }
        }

        return out.values.map { mask(it.copy(tier = tier), brand) }
    }

    /** One `scheme://…` line. Null when the line cannot possibly connect. */
    fun parseUri(token: String): FeedNode? {
        val schemeEnd = token.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = token.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme !in TUNNEL_SCHEMES) return null
        var rest = token.substring(schemeEnd + 3)
        val remark = rest.substringAfter('#', "")
        rest = rest.substringBefore('#')
        val q = parseQuery(rest.substringAfter('?', ""))
        val authority = rest.substringBefore('?')
        val cred = authority.substringBefore('@', "")
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':').trim('[', ']')
        val port = hostPort.substringAfter(':', "").takeWhile { it.isDigit() }.toIntOrNull()

        // vmess carries its whole body base64'd in the userinfo: host, port and everything else is inside
        if (scheme == "vmess") {
            val j = decodeBase64(cred.ifBlank { authority }) ?: return null
            val o = MiniJson.parse(j) as? Map<*, *> ?: return null
            val h = o["host"]?.toString()?.ifBlank { null } ?: return null
            val p = o["port"].toString().trim('"').toIntOrNull() ?: return null
            if (!isPublicAddress(h) || p !in 1..65535) return null
            val uid = o["id"]?.toString()?.ifBlank { null } ?: return null
            return FeedNode(
                id = id(scheme, h, p, uid, null), slot = 0, name = "", subtitle = "", cc = ccFrom(o["ps"]?.toString() ?: remark),
                tier = "vip", proto = scheme, host = h, port = p,
                userId = uid,
                alterId = o["aid"]?.toString()?.trim('"')?.toIntOrNull() ?: 0,
                cipher = (o["scy"]?.toString()?.ifBlank { null } ?: "auto"),
                network = o["net"]?.toString()?.ifBlank { null },
                hostHeader = o["host"]?.toString()?.ifBlank { null },
                path = o["path"]?.toString()?.ifBlank { null }?.let { unescape(it) },
                tls = o["tls"]?.toString()?.ifBlank { null },
                raw = token,
            )
        }

        if (port == null || port !in 1..65535 || !isPublicAddress(host)) return null
        val base = FeedNode(
            id = "", slot = 0, name = "", subtitle = "", cc = ccFrom(remark),
            tier = "vip", proto = scheme, host = host, port = port, raw = token,
        )
        return when (scheme) {
            "ss" -> {
                val cred2 = ssCredentials(cred) ?: return null
                base.copy(
                    password = cred2.second, method = cred2.first,
                    id = id(scheme, host, port, cred2.second, cred2.first),
                    network = "tcp",
                    tls = q["security"]?.takeIf { it.isNotBlank() && it != "none" }
                        ?: q["type"]?.takeIf { it == "tls" },
                    sni = q["sni"],
                )
            }

            else -> {
                val secret = cred.ifBlank { null }
                if (scheme != "vless" && secret == null) return null   // trojan/hy2 die without a password
                base.copy(
                    id = id(scheme, host, port, secret ?: q["pbk"], q["sid"]),
                    userId = if (scheme == "vless") secret else null,
                    password = if (scheme == "vless") null else secret,
                    tls = when {
                        q["security"] == "reality" -> "reality"
                        q["security"]?.isNotBlank() == true -> q["security"]
                        q["allowInsecure"] != null || q["insecure"] != null -> "tls"
                        else -> null
                    },
                    network = q["type"]?.ifBlank { null },
                    sni = q["sni"] ?: q["peer"],
                    path = q["path"]?.ifBlank { null }?.let { unescape(it) },
                    hostHeader = q["host"]?.ifBlank { null },
                    alpn = q["alpn"]?.ifBlank { null },
                    flow = q["flow"]?.ifBlank { null },
                    pbk = q["pbk"]?.ifBlank { null },
                    sid = q["sid"]?.ifBlank { null },
                    fingerprint = q["fp"]?.ifBlank { null },
                    insecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
                )
            }
        }
    }

    /** `ip:port`, `user:pass@ip:port`, `ip:port:user:pass`, and the `socks5://…` spelling. */
    fun parseProxyLine(line: String): FeedNode? {
        val m = PROXY_LINE.matchEntire(line) ?: return null
        val (scheme, user, pass, hostRaw, portRaw, tail) = m.destructured
        val host = hostRaw.trim('[', ']')
        val port = portRaw.toIntOrNull() ?: return null
        if (port !in 1..65535 || !isPublicAddress(host)) return null

        // `ip:port:user:pass` - the format half the free lists still ship
        var u = user.ifBlank { null }
        var p = pass?.ifBlank { null }
        if (u == null && tail.isNotBlank()) {
            val extra = tail.trimStart(':', '/').split(':', limit = 2)
            if (extra.size == 2) {
                u = extra[0].ifBlank { null }
                p = extra[1].ifBlank { null }
            }
        }
        val proto = scheme.lowercase(Locale.ROOT).ifBlank { if (u != null) "http" else "socks5" }
        if (proto !in setOf("http", "socks4", "socks5")) return null
        return FeedNode(
            id = id(proto, host, port, u, p), slot = 0, name = "", subtitle = "", cc = null,
            tier = "free", proto = proto, host = host, port = port,
            username = u, password = p, network = proto, tls = null,
            supportsUdp = false, raw = line,
        )
    }

    /**
     * `ss://` userinfo is either base64(`method:password`) (SIP002) or the literal `method:password`.
     * Null when neither gives a cipher we can dial. This exists because a UUID-style userinfo decodes to
     * garbage bytes - the server used to publish those as "grade B, 4ms, alive" nodes nobody could
     * connect to, and a phone has even less business trying.
     */
    fun ssCredentials(cred: String): Pair<String, String>? {
        if (cred.isBlank()) return null
        val direct = cred.split(':', limit = 2)
        if (direct.size == 2 && direct[0].trim().lowercase(Locale.ROOT) in SS_METHODS) {
            return direct[0].trim().lowercase(Locale.ROOT) to direct[1]
        }
        val decoded = decodeBase64(cred) ?: return null
        val parts = decoded.split(':', limit = 2)
        if (parts.size != 2) return null
        val method = parts[0].trim().lowercase(Locale.ROOT)
        if (method !in SS_METHODS) return null
        val password = parts[1].trim()
        if (password.isEmpty() || password.any { it.code < 0x20 || it.code == 0x7F }) return null
        return method to password
    }

    /** Free-list JSON (monosans/jetkai): `ip`+`port`, sometimes with `user`/`password` and a country. */
    private fun jsonNodes(text: String): List<FeedNode>? {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() ?: return null
        val arr = when (root) {
            is List<*> -> root
            is Map<*, *> -> (root["servers"] ?: root["proxies"] ?: root["data"]) as? List<*> ?: return null
            else -> return null
        }
        val out = ArrayList<FeedNode>(arr.size)
        for (row in arr) {
            val o = row as? Map<*, *> ?: continue
            val host = (o["ip"] ?: o["host"] ?: o["server"] ?: o["address"])?.toString()?.ifBlank { null } ?: continue
            val port = o["port"].toString().trim('"').toIntOrNull() ?: continue
            if (!isPublicAddress(host) || port !in 1..65535) continue
            val user = o["user"]?.toString()?.ifBlank { null } ?: o["username"]?.toString()?.ifBlank { null }
            val pass = o["pass"]?.toString()?.ifBlank { null } ?: o["password"]?.toString()?.ifBlank { null }
            val proto = when {
                user != null -> "http"
                else -> o["protocol"]?.toString()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "socks5"
            }
            if (proto !in setOf("http", "socks4", "socks5")) continue
            out += FeedNode(
                id = id(proto, host, port, user, pass), slot = 0, name = "", subtitle = "",
                cc = (o["country_code"] ?: o["country_code_1"] ?: o["country_code_2"])?.toString()
                    ?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 },
                tier = "free", proto = proto, host = host, port = port,
                username = user, password = pass, network = proto, tls = null,
                supportsUdp = false, raw = "$proto://$host:$port",
            )
        }
        return out.ifEmpty { null }
    }

    /** Server's `free.grades`, minus reliability: a phone has no multi-day ledger to average. */
    fun grade(latencyMs: Long?): String = when {
        latencyMs == null || latencyMs < 0 -> "D"
        latencyMs <= 400 -> "A"
        latencyMs <= 1000 -> "B"
        latencyMs <= 2200 -> "C"
        else -> "D"
    }

    /**
     * Replace whatever the vendor called the node with our brand + the country it sits in. A free list
     * labels rows "💂Germ-656 | 0.3Mbps | Telecom"; a paid panel says "SomeNet VIP 4". The second is a
     * commercial relationship this product must not surface, so the rule is the server's rule: keep a
     * country, drop the name.
     */
    fun mask(n: FeedNode, brand: String): FeedNode {
        val cc = n.cc ?: ccFrom(n.raw.orEmpty().substringAfterLast('#', ""))
        val country = cc?.let { COUNTRIES[it.lowercase(Locale.ROOT)] }
        val bits = listOfNotNull(country, n.proto, n.port.toString()).joinToString(" · ")
        return n.copy(
            name = brand,
            subtitle = bits,
            cc = cc,
            grade = n.grade.ifBlank { "D" },
        )
    }

    /** Flag emoji, a `DE`-style code, or a country word in the remark -> a 2-letter code. Nothing else survives. */
    fun ccFrom(remark: String): String? {
        if (remark.isBlank()) return null
        val flags = remark.filter { it.code in 0x1F1E6..0x1F1FF }
        if (flags.length >= 2) {
            val a = flags[0].code - 0x1F1E6
            val b = flags[1].code - 0x1F1E6
            if (a in 0..25 && b in 0..25) return "" + ('A' + a) + ('A' + b)
        }
        val upper = remark.uppercase(Locale.ROOT)
        Regex("""(?<![A-Z])([A-Z]{2})(?![A-Z])""").findAll(upper).firstOrNull { it.groupValues[1] in CC_SET }
            ?.let { return it.groupValues[1] }
        val lower = remark.lowercase(Locale.ROOT)
        return COUNTRIES.entries.firstOrNull { lower.contains(it.value) }?.key?.uppercase(Locale.ROOT)
    }

    /** Host a phone should not even dial: loopback, LAN, CGNAT, link-local, multicast, reserved. */
    fun isPublicAddress(host: String): Boolean {
        if (host.isBlank() || host.contains(':')) return false       // IPv6 literals: refused for now
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val octets = parts.map { it.toInt() }
            if (octets.any { it !in 0..255 }) return false
            val a = octets[0]; val b = octets[1]
            if (a in listOf(0, 10, 127)) return false
            if (a == 100 && b in 64..127) return false                 // carrier-grade NAT
            if (a == 169 && b == 254) return false                     // link-local
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
            if (a == 198 && (b == 18 || b == 19)) return false
            if (a == 8 && b == 8 && octets[2] == 8 && octets[3] == 8) return false   // level3 DNS, a probe magnet
            if (a >= 224) return false                                  // multicast / reserved
            return true
        }
        if (host.equals("localhost", ignoreCase = true) || !host.contains('.')) return false
        return host.length >= 4 && host.indexOf('.') in 1 until host.length - 1
    }

    /** Short, stable, and derived from what makes the node *this* node (same idea as the server's ids). */
    fun id(proto: String, host: String, port: Int, secret: String?, extra: String?): String {
        val src = listOf(proto, host, port.toString(), secret ?: "", extra ?: "").joinToString("|")
        var h = -0x7f0abb17                    // FNV-1a 32, as an Int
        for (c in src) {
            h = h xor c.code
            h *= 0x01000193
        }
        val tail = src.hashCode()
        // Padded, not toHexString(): a small hash formats to three or four characters, and an id whose
        // length depends on its value is the kind of thing that surprises a UI that truncates it.
        return (String.format(Locale.US, "%08x", h) + String.format(Locale.US, "%08x", tail)).take(12)
    }

    fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        q.split('&').forEach { pair ->
            if (pair.isNotEmpty()) {
                val k = pair.substringBefore('=').lowercase(Locale.ROOT)
                val v = pair.substringAfter('=', "")
                if (k.isNotBlank() && !out.containsKey(k)) out[k] = v
            }
        }
        return out
    }

    fun unescape(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Lenient base64: URL-safe alphabet, missing padding, and a refusal when it is not base64 at all. */
    fun decodeBase64(s: String): String? {
        val t = s.trim().replace('-', '+').replace('_', '/')
        if (t.length < 8 || t.any { !BASE64_CHARS.contains(it) }) return null
        val padded = t.replace("\n", "").replace("\r", "")
        val withPad = padded + "=".repeat((4 - padded.length % 4) % 4)
        return runCatching { String(java.util.Base64.getDecoder().decode(withPad), Charsets.UTF_8) }.getOrNull()
    }

    /** Cap a list so one cold sync can never turn into a memory incident on a 2 GB phone. */
    fun dedupe(nodes: List<FeedNode>, max: Int = 120): List<FeedNode> {
        val seen = HashSet<String>(nodes.size)
        val out = ArrayList<FeedNode>(minOf(max, nodes.size))
        for (n in nodes) {
            if (seen.add(n.proto + "|" + n.host + ":" + n.port)) {
                out += n
                if (out.size >= max) break
            }
        }
        return out
    }

    private val COUNTRIES: Map<String, String> = mapOf(
        "de" to "germany", "fr" to "france", "nl" to "netherlands", "se" to "sweden", "fi" to "finland",
        "no" to "norway", "dk" to "denmark", "gb" to "england", "uk" to "england", "us" to "america",
        "ca" to "canada", "tr" to "turkey", "ae" to "dubai", "sg" to "singapore", "jp" to "japan",
        "hk" to "hongkong", "kr" to "korea", "in" to "india", "au" to "australia", "ru" to "russia",
        "ua" to "ukraine", "pl" to "poland", "it" to "italy", "es" to "spain", "pt" to "portugal",
        "at" to "austria", "ch" to "switzerland", "be" to "belgium", "ro" to "romania", "hu" to "hungary",
        "cz" to "czech", "gr" to "greece", "lv" to "latvia", "lt" to "lithuania", "ee" to "estonia",
        "ir" to "iran", "cn" to "china", "tw" to "taiwan", "th" to "thailand", "my" to "malaysia",
        "za" to "southafrica", "br" to "brazil", "ar" to "argentina", "md" to "moldova", "lu" to "luxembourg",
        "bg" to "bulgaria", "rs" to "serbia", "sk" to "slovakia", "si" to "slovenia", "ie" to "ireland",
    )
    private val CC_SET: Set<String> = COUNTRIES.keys.map { it.uppercase(Locale.ROOT) }.toSet()

    /**
     * A deliberately tiny JSON reader for subscription payloads (an array of links, {"servers":[…]},
     * one base64'd vmess object). Hand-rolled rather than org.json because org.json is an Android stub in
     * JVM tests - it returns 0 for everything, so any logic behind it is untestable where we test.
     */
    internal object MiniJson {
        fun parse(text: String): Any? = Parser(text).read()

        private class Parser(private val s: String) {
            private var i = 0

            fun read(): Any? {
                skip()
                return when (peek()) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> num()
                }
            }

            private fun peek(): Char = if (i < s.length) s[i] else ' '
            private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

            private fun obj(): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>()
                i++
                skip()
                if (peek() == '}') { i++; return m }
                while (i < s.length) {
                    skip()
                    if (peek() != '"') return m
                    val k = str()
                    skip()
                    if (peek() == ':') i++
                    m[k] = read()
                    skip()
                    when (peek()) {
                        ',' -> i++
                        '}' -> { i++; return m }
                        else -> return m
                    }
                }
                return m
            }

            private fun arr(): List<Any?> {
                val a = ArrayList<Any?>()
                i++
                skip()
                if (peek() == ']') { i++; return a }
                while (i < s.length) {
                    a += read()
                    skip()
                    when (peek()) {
                        ',' -> i++
                        ']' -> { i++; return a }
                        else -> return a
                    }
                }
                return a
            }

            private fun str(): String {
                skip()
                if (peek() != '"') return ""
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    if (c == '"') break
                    if (c == '\\' && i < s.length) {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                i += hex.length
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> sb.append(e)
                        }
                    } else sb.append(c)
                }
                return sb.toString()
            }

            private fun num(): Any {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in ".-+eE")) i++
                val t = s.substring(start, i)
                return t.toLongOrNull() ?: t.toDoubleOrNull() ?: t
            }
        }
    }
}
