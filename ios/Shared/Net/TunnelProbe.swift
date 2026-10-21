import Foundation

/// Does this tunnel *provably carry internet traffic*? Swift twin of the two-tier verdict in
/// `android/.../vpn/TunnelProbe.kt`: three generate_204 endpoints, `clean` when every platform answers
/// exactly as expected, `usable` when the majority or Cloudflare does. Connect/health decisions gate
/// on `usable`; `clean` only ranks. On iOS the probes go *through the tunnel's local proxy*, so an
/// answer is proof the whole chain (Xray + node + egress) works - not just that a socket opened.
struct TunnelProbe {

    struct Endpoint {
        var name: String
        var url: URL
    }

    static let endpoints: [Endpoint] = [
        Endpoint(name: "cloudflare", url: URL(string: "https://cp.cloudflare.com/generate_204")!),
        Endpoint(name: "google", url: URL(string: "https://www.google.com/generate_204")!),
        Endpoint(name: "gstatic", url: URL(string: "https://connectivitycheck.gstatic.com/generate_204")!),
    ]

    struct Result: Codable, Equatable {
        var ok: [String: Bool]
        var latencyMs: [String: Int64]
        var at: Int64

        func okCount(_ names: [String]) -> Int { names.reduce(0) { $0 + (ok[$1] == true ? 1 : 0) } }

        var cloudflareOk: Bool { ok["cloudflare"] == true }
        var googleOk: Bool { ok["google"] == true }
        var gstaticOk: Bool { ok["gstatic"] == true }

        var clean: Bool { cloudflareOk && googleOk && gstaticOk }
        var platformBlocked: Bool { cloudflareOk && (!googleOk || !gstaticOk) }

        /// The tunnel provably carries internet traffic: the dedicated Cloudflare check alone is
        /// enough - Google being unreachable from some paths is a platform fact, not a tunnel fault.
        var usable: Bool { okCount(["cloudflare", "google", "gstatic"]) >= 2 || cloudflareOk }

        var verdict: String {
            if clean { return "clean" }
            if platformBlocked { return "platform_blocked" }
            if usable { return "partial" }
            return "dead"
        }
    }

    /// Run the three checks through the tunnel's local proxy. `proxyPort` is the mixed inbound port.
    static func probe(proxyHost: String = "127.0.0.1", proxyPort: Int = 10808,
                      timeout: TimeInterval = 6) async -> Result {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.connectionProxyDictionary = [
            // Raw string keys on purpose: the kCFNetworkProxies* constants are macOS-only headers;
            // iOS URLSession honours these dictionary keys (the standard proxy-mode recipe).
            "HTTPEnable": 1,
            "HTTPProxy": proxyHost,
            "HTTPPort": proxyPort,
            "HTTPSEnable": 1,
            "HTTPSProxy": proxyHost,
            "HTTPSPort": proxyPort,
            "SOCKSEnable": 1,
            "SOCKSProxy": proxyHost,
            "SOCKSPort": proxyPort,
        ]
        let session = URLSession(configuration: config)

        var ok: [String: Bool] = [:]
        var latency: [String: Int64] = [:]
        let started = DispatchTime.now()

        await withTaskGroup(of: (String, Bool, Int64).self) { group in
            for e in endpoints {
                group.addTask {
                    let t0 = DispatchTime.now()
                    do {
                        var req = URLRequest(url: e.url)
                        req.cachePolicy = .reloadIgnoringLocalCacheData
                        let (data, resp) = try await session.data(for: req)
                        let ms = Int64(DispatchTime.now().uptimeNanoseconds - t0.uptimeNanoseconds) / 1_000_000
                        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
                        // 204 with an empty body is the contract anything else is a captive portal.
                        return (e.name, code == 204 && data.isEmpty, ms)
                    } catch {
                        let ms = Int64(DispatchTime.now().uptimeNanoseconds - t0.uptimeNanoseconds) / 1_000_000
                        return (e.name, false, ms)
                    }
                }
            }
            for await (name, success, ms) in group {
                ok[name] = success
                latency[name] = ms
            }
        }
        _ = started
        return Result(ok: ok, latencyMs: latency, at: Int64(Date().timeIntervalSince1970))
    }
}
