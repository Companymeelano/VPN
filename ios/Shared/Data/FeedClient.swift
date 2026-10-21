import Foundation

/// The `/v/` client, built to the contract in docs/API-CONTRACT.md (schema 2):
/// ETag conditional polling (304 = zero bytes), 429 slow-down with `retryAfter`, `servers[]` rows,
/// feedback reports so the server's per-node quality reflects this phone's reality. Unknown fields
/// are ignored by design; a `schema` above what we know is *refused*, never guessed at.
struct FeedClient {

    struct Payload {
        var kind: String
        var nodes: [FeedNode]
        var brand: String
        var generatedAt: Int64
        var ttl: Int
        var etag: String?
        var building: Bool
        var retryAfter: Int
        var notModified: Bool
    }

    enum FeedError: Error, LocalizedError {
        case schemaTooNew(Int)
        case http(Int)
        case slowDown(Int)
        case transport(String)

        var errorDescription: String? {
            switch self {
            case .schemaTooNew(let v): return "schema \(v) newer than this app supports"
            case .http(let code): return "HTTP \(code)"
            case .slowDown(let s): return "server asked to slow down (\(s)s)"
            case .transport(let m): return m
            }
        }
    }

    static let schemaMax = 2

    var base: String = SharedStore.feedBase
    var feedKey: String = SharedStore.feedKey

    /// `GET /v/?action=vip|free` with conditional headers. Throws `.slowDown` on 429.
    func fetch(kind: String, session: URLSession = .shared) async throws -> Payload {
        var comps = URLComponents(string: base.hasSuffix("/") ? base : base + "/")!
        comps.queryItems = [URLQueryItem(name: "action", value: kind)]
        guard let url = comps.url else { throw FeedError.transport("bad feed base") }
        var req = URLRequest(url: url)
        req.setValue("gzip", forHTTPHeaderField: "Accept-Encoding")
        if !feedKey.isEmpty { req.setValue(feedKey, forHTTPHeaderField: "X-Feed-Key") }
        if let etag = SharedStore.etag(kind) { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }

        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw FeedError.transport("no HTTP response") }
        if http.statusCode == 304 {
            return Payload(kind: kind, nodes: [], brand: SharedStore.brand, generatedAt: 0, ttl: 600,
                           etag: SharedStore.etag(kind), building: false, retryAfter: 0, notModified: true)
        }
        if http.statusCode == 429 {
            let retry = http.value(forHTTPHeaderField: "Retry-After").flatMap(Int.init) ?? 60
            throw FeedError.slowDown(retry)
        }
        guard http.statusCode == 200 else { throw FeedError.http(http.statusCode) }

        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw FeedError.transport("unreadable feed body")
        }
        let schema = FeedNode.int(obj["schema"]) ?? 1
        if schema > schemaMax { throw FeedError.schemaTooNew(schema) }

        let rows = obj["servers"] as? [[String: Any]] ?? []
        var nodes: [FeedNode] = []
        for row in rows {
            guard let n = FeedNode(feedJson: row) else { continue }
            nodes.append(n)
        }
        let payload = Payload(
            kind: kind,
            nodes: nodes,
            brand: (obj["brand"] as? String)?.nilIfBlank ?? SharedStore.brand,
            generatedAt: FeedNode.int64(obj["generatedAt"]) ?? 0,
            ttl: FeedNode.int(obj["ttl"]) ?? 600,
            etag: http.value(forHTTPHeaderField: "ETag") ?? (obj["etag"] as? String),
            building: (obj["building"] as? Bool) ?? false,
            retryAfter: FeedNode.int(obj["retryAfter"]) ?? 0,
            notModified: false
        )
        if let e = payload.etag { SharedStore.setEtag(kind, e) }
        return payload
    }

    /// `POST /v/?action=feedback` - `{sid, ok, latencyMs, err}` rows. Best-effort; failures never
    /// surface to the user (the tunnel is what they are looking at, not our metrics pipeline).
    struct Report: Codable {
        var sid: String
        var ok: Bool
        var latencyMs: Int64
        var err: String
    }

    func postFeedback(_ reports: [Report], session: URLSession = .shared) async {
        guard !reports.isEmpty else { return }
        var comps = URLComponents(string: base.hasSuffix("/") ? base : base + "/")!
        comps.queryItems = [URLQueryItem(name: "action", value: "feedback")]
        guard let url = comps.url else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !feedKey.isEmpty { req.setValue(feedKey, forHTTPHeaderField: "X-Feed-Key") }
        // Keys stay camelCase (`latencyMs`), exactly like the Android client's JSONObject.
        if let body = try? JSONEncoder().encode(["reports": reports]) {
            req.httpBody = body
        }
        _ = try? await session.data(for: req)
    }
}
