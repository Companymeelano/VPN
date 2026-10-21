import Foundation

/// The extension-side orchestrator: pick a candidate, start the engine, *prove* the tunnel carries
/// traffic, keep proving it on a health loop, and fail over before the user notices anything. The
/// pacing mirrors `android/.../vpn/VpnOrchestrator.kt` (initial 8 s, healthy 12 s, first-failure
/// recheck 3 s, two failures to fail over) - on Irancell the difference between "flips every 30 s"
/// and "survives the evening" is exactly these numbers.
final class TunnelSession {

    static let proxyHost = "127.0.0.1"
    static let proxyPort = CoreProfiles.localPort

    private let queue = DispatchQueue(label: "ir.meelano.vpn.tunnel.session")
    private var candidates: [FeedNode] = []
    private var currentIndex = 0
    private var healthTask: Task<Void, Never>?
    private var consecutiveFailures = 0
    private var lastMedianRttMs: Int64 = -1
    private var feedback: [FeedClient.Report] = []
    private(set) var lastProbe: TunnelProbe.Result?

    /// Diagnostics line the app shows on its status card (written through SharedStore too).
    var onStatus: ((String) -> Void)?

    var activeNode: FeedNode? {
        queue.sync { candidates.indices.contains(currentIndex) ? candidates[currentIndex] : nil }
    }

    /// Order candidates the way Android does: pinned first, this phone's reachability next, then the
    /// Iran bucket (transport x1000 + geography x100 + port x10), server grade, measured latency, slot.
    static func orderedCandidates(from nodes: [FeedNode], pinned: Set<String>) -> [FeedNode] {
        nodes
            .filter { $0.isUsable }
            .sorted { a, b in
                let pa = pinned.contains(a.id) ? 0 : 1
                let pb = pinned.contains(b.id) ? 0 : 1
                if pa != pb { return pa < pb }
                let ba = NodeRanking.bucket(a)
                let bb = NodeRanking.bucket(b)
                if ba != bb { return ba < bb }
                let ga = gradeRank(a.grade)
                let gb = gradeRank(b.grade)
                if ga != gb { return ga < gb }
                let la = a.latencyMs ?? Int64.max
                let lb = b.latencyMs ?? Int64.max
                if la != lb { return la < lb }
                return a.slot < b.slot
            }
    }

    static func gradeRank(_ g: String) -> Int {
        switch g.uppercased() {
        case "A": return 0
        case "B": return 1
        case "C": return 2
        default: return 3
        }
    }

    /// Build the candidate ladder from the shared feed cache and start the first dialable node.
    /// Throws only when *nothing* can be started - a partial failure becomes a failover, not an error.
    func start(selectedId: String?) async throws -> FeedNode {
        let all = SharedStore.loadNodes()
        let pinned = SharedStore.pinned
        var ordered = TunnelSession.orderedCandidates(from: all, pinned: pinned)
        // The user's pin wins over everything, even if the pinned row is not the head of the ladder.
        if let sel = selectedId, let node = all.first(where: { $0.id == sel }), node.isUsable {
            ordered.removeAll { $0.id == sel }
            ordered.insert(node, at: 0)
        }
        guard !ordered.isEmpty else { throw XrayEngine.EngineError.coreNotLinked("no usable nodes") }
        queue.sync {
            candidates = ordered
            currentIndex = 0
            consecutiveFailures = 0
        }

        // Engine-grade race over the head of the ladder (five per pingBatch call): the curated order
        // decides *who gets raced*, the race decides who we actually dial first.
        let raceHead = Array(ordered.prefix(5))
        if XrayEngine.linked {
            var configs: [String] = []
            for n in raceHead {
                if let json = try? CoreProfiles.pingConfigJson(n, tune: tuneFor(n)) {
                    configs.append(json)
                } else {
                    configs.append("")
                }
            }
            if let delays = XrayEngine.pingBatch(configs: configs, timeoutSec: 5),
               delays.contains(where: { $0 < 10_000 }) {
                let best = delays.enumerated()
                    .filter { $0.element < 10_000 }
                    .min { $0.element < $1.element }?.offset ?? 0
                queue.sync {
                    let winner = raceHead[best]
                    if let idx = candidates.firstIndex(where: { $0.id == winner.id }) {
                        candidates.remove(at: idx)
                        candidates.insert(winner, at: 0)
                        currentIndex = 0
                    }
                }
            }
        }

        var lastError: Error?
        for idx in 0..<queue.sync(execute: { candidates.count }) {
            let node = queue.sync(execute: { candidates[idx] })
            do {
                try await launch(node)
                queue.sync { currentIndex = idx }
                SharedStore.activeId = node.id
                report("connected: \(node.name) \(node.host):\(node.port)")
                startHealthLoop()
                return node
            } catch {
                lastError = error
                report("candidate \(idx + 1) failed: \(error.localizedDescription)")
            }
        }
        throw lastError ?? XrayEngine.EngineError.coreNotLinked("no candidate could start")
    }

    func stop() {
        healthTask?.cancel()
        healthTask = nil
        XrayEngine.stop()
        SharedStore.activeId = nil
    }

    private func tuneFor(_ node: FeedNode) -> Tune {
        let patchDict = node.tune.mapValues { $0.value }
        return Tuner.resolve(
            regime: SharedStore.regime,
            patch: TunePatch.fromAny(patchDict),
            user: SharedStore.overrides
        )
    }

    /// Configure + run the engine for one node. A failure here is required to be loud: a core that is
    /// configured but not carrying traffic is the single symptom users cannot diagnose.
    private func launch(_ node: FeedNode) async throws {
        let json = try CoreProfiles.profileJson(node, tune: tuneFor(node))
        XrayEngine.stop()
        try XrayEngine.start(xrayJson: json)
        // Prove the chain before calling it connected. Partial (Cloudflare OK) counts as usable.
        let verdict = await TunnelProbe.probe(
            proxyHost: TunnelSession.proxyHost,
            proxyPort: TunnelSession.proxyPort
        )
        queue.sync { lastProbe = verdict }
        SharedStore.saveReport([
            "verdict": verdict.verdict,
            "ok": verdict.ok,
            "latencyMs": verdict.latencyMs,
            "at": verdict.at,
        ])
        guard verdict.usable else {
            XrayEngine.stop()
            throw XrayEngine.EngineError.invokeFailed("tunnel_not_usable:\(verdict.verdict)")
        }
    }

    // MARK: - health loop

    private func startHealthLoop() {
        healthTask?.cancel()
        healthTask = Task { [weak self] in
            guard let self = self else { return }
            var intervalNs: UInt64 = 8_000_000_000 // first check after 8 s
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: intervalNs)
                if Task.isCancelled { break }
                let verdict = await TunnelProbe.probe(
                    proxyHost: TunnelSession.proxyHost,
                    proxyPort: TunnelSession.proxyPort
                )
                self.queue.sync { self.lastProbe = verdict }
                if verdict.usable {
                    self.queue.sync { self.consecutiveFailures = 0 }
                    self.report("health ok (\(verdict.verdict))")
                    intervalNs = 12_000_000_000 // healthy cadence
                } else {
                    // First failure gets a quick recheck before anything drastic happens.
                    intervalNs = 3_000_000_000
                    let failures = self.queue.sync { () -> Int in
                        self.consecutiveFailures += 1
                        return self.consecutiveFailures
                    }
                    if failures >= 2 {
                        self.report("health failed twice (\(verdict.verdict)); failing over")
                        await self.failover()
                        intervalNs = 8_000_000_000
                    } else {
                        self.report("health soft-fail (\(verdict.verdict)); rechecking")
                    }
                }
            }
        }
    }

    /// Two failures mean this node is not carrying traffic right now. Move to the next candidate and
    /// keep going - the list is never emptied, a probe result is a demotion, never a deletion.
    private func failover() async {
        let count = queue.sync { candidates.count }
        guard count > 1 else {
            report("failover requested but only one candidate remains")
            return
        }
        var startIdx = queue.sync { (currentIndex + 1) % candidates.count }
        var attempts = 0
        while attempts < count {
            let node = queue.sync { candidates[startIdx] }
            do {
                try await launch(node)
                queue.sync {
                    currentIndex = startIdx
                    consecutiveFailures = 0
                }
                SharedStore.activeId = node.id
                report("failed over to \(node.name) \(node.host):\(node.port)")
                return
            } catch {
                report("failover candidate \(startIdx + 1) failed: \(error.localizedDescription)")
            }
            startIdx = (startIdx + 1) % candidates.count
            attempts += 1
        }
        report("every failover candidate failed")
    }

    private func report(_ line: String) {
        let stamped = "[\(Int(Date().timeIntervalSince1970))] \(line)"
        SharedStore.statusText = stamped
        onStatus?(stamped)
    }

    /// Best-effort quality feedback so the server's ranking reflects this phone's reality.
    func flushFeedback() async {
        let node = activeNode
        guard let node = node, let verdict = queue.sync(execute: { lastProbe }) else { return }
        let cloudflareMs = verdict.latencyMs["cloudflare"] ?? -1
        let rep = FeedClient.Report(
            sid: node.id,
            ok: verdict.usable,
            latencyMs: cloudflareMs > 0 ? cloudflareMs : 0,
            err: verdict.usable ? "" : verdict.verdict
        )
        await FeedClient().postFeedback([rep])
    }
}
