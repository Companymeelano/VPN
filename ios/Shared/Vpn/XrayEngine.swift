import Foundation
#if canImport(LibXray)
import LibXray
#endif

/// The only place on iOS that knows the *names* inside the linked core.
///
/// The pinned XTLS/libXray `apple go` build (docs/IOS-BUILD.md) exposes a C ABI:
///   `CGoInvoke(requestJSON) -> responseJSON` + `CGoFree(ptr)`
/// and one entry point carries every command as the same JSON envelope the Android bridge speaks:
///   request : {"apiVersion":3,"method":"runXray","payload":{"xrayJson":"..."}}
///   response: {"success":true,"data":{...},"error":""}
///
/// When `LibXray.xcframework` is absent (`scripts/build-libxray-ios.sh` not run), this type still
/// compiles and every call fails as a *named* error - never a silent green ring. A build that cannot
/// tunnel must refuse to pretend, exactly like `CoreApi.LINKED == false` on Android.
enum XrayEngine {

    static let apiVersion = 3

    enum EngineError: Error, LocalizedError {
        case coreNotLinked(String)
        case invokeFailed(String)

        var errorDescription: String? {
            switch self {
            case .coreNotLinked(let why): return "core_not_linked: \(why)"
            case .invokeFailed(let why): return "invoke_failed: \(why)"
            }
        }
    }

    /// One-line diagnosis for the diagnostics screen; never throws.
    static func describe() -> String {
        #if canImport(LibXray)
        return "ready (LibXray cgo bridge)"
        #else
        return "LibXray.xcframework missing: run scripts/build-libxray-ios.sh (docs/IOS-BUILD.md)"
        #endif
    }

    static var linked: Bool {
        #if canImport(LibXray)
        return true
        #else
        return false
        #endif
    }

    /// Raw request/response through the C bridge. On the string boundary: libXray owns the response
    /// buffer (CGoFree), the request is ours (free()).
    private static func invokeRaw(_ requestJson: String) throws -> String {
        #if canImport(LibXray)
        guard let req = strdup(requestJson) else { throw EngineError.invokeFailed("out of memory") }
        defer { free(req) }
        guard let resp = CGoInvoke(req) else {
            throw EngineError.invokeFailed("CGoInvoke returned NULL")
        }
        defer { CGoFree(resp) }
        return String(cString: resp)
        #else
        throw EngineError.coreNotLinked(describe())
        #endif
    }

    private struct InvokeResponse {
        var success: Bool
        var error: String
        var data: Any?
    }

    private static func invoke(_ method: String, payload: [String: Any]?) throws -> Any? {
        var request: [String: Any] = ["apiVersion": apiVersion, "method": method]
        if let payload = payload { request["payload"] = payload }
        let raw = try invokeRaw(try CoreProfiles.render(request))
        guard let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw EngineError.invokeFailed("\(method): unreadable response (\(raw.prefix(120)))")
        }
        let success = (obj["success"] as? Bool) ?? false
        guard success else {
            let err = ((obj["error"] as? String).flatMap { $0.nilIfBlank }) ?? "unknown error from \(method)"
            throw EngineError.invokeFailed(err)
        }
        return obj["data"]
    }

    /// Stage the profile and start the core. The mixed inbound on 127.0.0.1:10808 starts listening
    /// here - there is no TUN fd on iOS; the Packet Tunnel's proxy settings point at that port.
    static func start(xrayJson: String) throws {
        _ = try invoke("runXray", payload: ["xrayJson": xrayJson])
    }

    static func stop() {
        _ = try? invoke("stopXray", payload: nil)
    }

    static func version() -> String? {
        guard let data = try? invoke("xrayVersion", payload: nil),
              let obj = data as? [String: Any] else { return nil }
        return ((obj["version"] as? String).flatMap { $0.nilIfBlank }) ?? String(describing: data).prefix(80).description
    }

    static func running() -> Bool {
        guard let data = try? invoke("getXrayState", payload: nil),
              let obj = data as? [String: Any] else { return false }
        return (obj["running"] as? Bool) ?? false
    }

    /// libXray's temporary-core latency race: dials a real HEAD through every config concurrently
    /// (engine-grade probe - TLS and transport included - not a bare socket open). Hard API cap:
    /// five configs per call. Returns per-config delays in ms (>= 10000 = error/timeout), or nil when
    /// the engine cannot race at all - callers then keep the curated order, never "everyone failed".
    static func pingBatch(configs: [String], timeoutSec: Int = 5,
                          url: String = "https://cp.cloudflare.com/generate_204") -> [Int64]? {
        guard !configs.isEmpty else { return nil }
        let chunk = Array(configs.prefix(5))
        do {
            let arr = chunk.map { ["xrayJson": $0] }
            let data = try invoke("pingBatch", payload: [
                "configs": arr,
                "timeout": timeoutSec,
                "url": url,
            ])
            let results: [[String: Any]]?
            if let a = data as? [[String: Any]] {
                results = a
            } else if let o = data as? [String: Any] {
                results = (o["results"] as? [[String: Any]]) ?? (o["items"] as? [[String: Any]])
            } else {
                results = nil
            }
            guard let results = results else { return nil }
            return (0..<chunk.count).map { i in
                guard i < results.count else { return 11_000 }
                return FeedNode.int64(results[i]["delay"]) ?? 11_000
            }
        } catch {
            return nil
        }
    }
}
