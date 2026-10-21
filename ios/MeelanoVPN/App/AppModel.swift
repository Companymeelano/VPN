import Foundation
import SwiftUI
import Combine

/// The app's single source of truth: feed cache, node selection, regime switches, and the bridge to
/// the tunnel manager. Mirrors the responsibilities of the Android `VpnViewModel` + feed repository
/// but keeps the v1 surface honest: no fake "connected" state is ever published.
@MainActor
final class AppModel: ObservableObject {

    @Published var nodes: [FeedNode] = []
    @Published var selectedId: String?
    @Published var regime: Regime = SharedStore.regime
    @Published var overrides: Overrides = SharedStore.overrides
    @Published var feedError: String?
    @Published var feedBuilding = false
    @Published var feedAge: String = "—"
    @Published var statusLine: String = "آماده"
    @Published var isBusy = false
    @Published var prefersDark = true
    @Published var diagnostics: [String: String] = [:]

    let tunnel = TunnelManager()

    private var refreshTask: Task<Void, Never>?
    private var lastGeneratedAt: Int64 = 0

    var themeScheme: ColorScheme { prefersDark ? .dark : .light }

    var selectedNode: FeedNode? {
        nodes.first { $0.id == selectedId }
    }

    var connected: Bool { tunnel.status == .connected }

    func onAppear() {
        nodes = SharedStore.loadNodes()
        selectedId = SharedStore.selectedId
        statusLine = SharedStore.statusText.nilIfBlank ?? "آماده"
        Task {
            await tunnel.prepare()
            await refreshFeed(force: false)
            refreshStatus()
        }
    }

    // MARK: - feed

    func refreshFeed(force: Bool) async {
        if isBusy { return }
        isBusy = true
        defer { isBusy = false }
        let client = FeedClient()
        var merged = SharedStore.loadNodes()
        for kind in ["vip", "free"] {
            do {
                let payload = try await client.fetch(kind: kind)
                if payload.notModified { continue }
                feedBuilding = payload.building
                lastGeneratedAt = payload.generatedAt
                SharedStore.brand = payload.brand
                // A refresh replaces only this kind's rows; never clear the list on a bad response.
                let fresh = payload.nodes.map { node -> FeedNode in
                    var n = node
                    n.kind = kind
                    return n
                }
                merged.removeAll { $0.kind == kind }
                merged.append(contentsOf: fresh)
            } catch FeedClient.FeedError.slowDown(let s) {
                feedError = "سرور درخواست کندتر کرد (\(s) ثانیه)"
            } catch FeedClient.FeedError.schemaTooNew(_) {
                feedError = "نسخهٔ فید از این اپ جدیدتر است؛ اپ را به‌روز کنید"
            } catch {
                if !force { feedError = nil } else { feedError = error.localizedDescription }
            }
        }
        // Order: pinned first, Iran bucket, grade, latency - the same ladder the tunnel uses.
        let pinned = SharedStore.pinned
        let selected = selectedId
        merged = TunnelSession.orderedCandidates(from: merged, pinned: pinned)
        nodes = merged
        SharedStore.saveNodes(merged)
        if selected == nil {
            selectedId = merged.first?.id
            SharedStore.selectedId = selectedId
        }
        feedAge = Self.relativeAge(lastGeneratedAt)
    }

    private static func relativeAge(_ ts: Int64) -> String {
        if ts <= 0 { return "—" }
        let delta = Int(Date().timeIntervalSince1970) - Int(ts)
        if delta < 60 { return "همین حالا" }
        if delta < 3600 { return "\(delta / 60) دقیقه پیش" }
        return "\(delta / 3600) ساعت پیش"
    }

    // MARK: - selection & switches

    func select(_ node: FeedNode) {
        selectedId = node.id
        SharedStore.selectedId = node.id
    }

    func togglePin(_ node: FeedNode) {
        var pinned = SharedStore.pinned
        if pinned.contains(node.id) { pinned.remove(node.id) } else { pinned.insert(node.id) }
        SharedStore.pinned = pinned
        nodes = TunnelSession.orderedCandidates(from: nodes, pinned: pinned)
    }

    func isPinned(_ node: FeedNode) -> Bool { SharedStore.pinned.contains(node.id) }

    func setRegime(_ r: Regime) {
        regime = r
        SharedStore.regime = r
    }

    func setOverrides(_ o: Overrides) {
        overrides = o
        SharedStore.overrides = o
    }

    // MARK: - tunnel

    func toggleConnection() async {
        if connected {
            tunnel.disconnect()
            statusLine = "قطع شد"
            refreshStatus()
        } else {
            isBusy = true
            defer { isBusy = false }
            if nodes.isEmpty { await refreshFeed(force: true) }
            if selectedId == nil {
                statusLine = "هیچ سروری در دسترس نیست"
                return
            }
            await tunnel.connect()
            refreshStatus()
        }
    }

    func refreshStatus() {
        tunnel.refreshStatus()
        switch tunnel.status {
        case .connected:
            statusLine = SharedStore.statusText.nilIfBlank ?? "وصل است"
        case .connecting:
            statusLine = "در حال اتصال…"
        case .disconnecting:
            statusLine = "در حال قطع…"
        case .reasserting:
            statusLine = "اتصال دوباره برقرار می‌شود…"
        case .disconnected, .invalid:
            statusLine = "آماده"
        @unknown default:
            statusLine = "آماده"
        }
        if let err = tunnel.lastError {
            statusLine = err
        }
    }

    /// Pull the extension's live diagnostics card; used by the diagnostics screen.
    func loadDiagnostics() async {
        refreshStatus()
        var out: [String: String] = [:]
        out["core"] = XrayEngine.describe()
        if let live = await tunnel.requestDiagnostics() {
            for (k, v) in live {
                out[k] = "\(v)"
            }
        }
        if let report = SharedStore.loadReport() {
            out["lastProbe"] = "\(report)"
        }
        out["nodes"] = "\(nodes.count) سرور"
        out["regime"] = regime.rawValue
        diagnostics = out
    }
}
