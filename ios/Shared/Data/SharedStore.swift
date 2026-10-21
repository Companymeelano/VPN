import Foundation

/// Everything the app and the Packet Tunnel extension must share, in one place: the feed cache, the
/// pinned/selected node, the user's switches, and the extension's write-backs. One App Group
/// `UserDefaults` suite - the app writes the plan, the extension executes it and reports back.
enum SharedStore {
    static let appGroup = "group.ir.meelano.vpn"
    static let suiteName = appGroup

    private enum K {
        static let nodes = "meelano.nodes" // [FeedNode] as JSON data
        static let etagVip = "meelano.etag.vip"
        static let etagFree = "meelano.etag.free"
        static let feedBase = "meelano.feedBase"
        static let feedKey = "meelano.feedKey"
        static let brand = "meelano.brand"
        static let selected = "meelano.selectedId"
        static let pinned = "meelano.pinned"
        static let regime = "meelano.regime"
        static let overrides = "meelano.overrides"
        static let active = "meelano.activeId" // written by the extension
        static let statusText = "meelano.statusText" // written by the extension
        static let lastReport = "meelano.lastProbeReport"
        static let bootstrapSeed = "meelano.bootstrapSeed"
    }

    static var defaults: UserDefaults {
        UserDefaults(suiteName: suiteName) ?? .standard
    }

    // MARK: - feed

    static var feedBase: String {
        get { defaults.string(forKey: K.feedBase) ?? "https://ainetmee.ir/v" }
        set { defaults.set(newValue, forKey: K.feedBase) }
    }

    static var feedKey: String {
        get { defaults.string(forKey: K.feedKey) ?? "" }
        set { defaults.set(newValue, forKey: K.feedKey) }
    }

    static var brand: String {
        get { defaults.string(forKey: K.brand) ?? "Vip Meelano" }
        set { defaults.set(newValue, forKey: K.brand) }
    }

    static func etag(_ kind: String) -> String? {
        defaults.string(forKey: kind == "vip" ? K.etagVip : K.etagFree)
    }

    static func setEtag(_ kind: String, _ value: String?) {
        defaults.set(value, forKey: kind == "vip" ? K.etagVip : K.etagFree)
    }

    static func saveNodes(_ nodes: [FeedNode]) {
        if let data = try? JSONEncoder().encode(nodes) {
            defaults.set(data, forKey: K.nodes)
        }
    }

    static func loadNodes() -> [FeedNode] {
        guard let data = defaults.data(forKey: K.nodes),
              let nodes = try? JSONDecoder().decode([FeedNode].self, from: data) else { return [] }
        return nodes
    }

    /// First-run seeds: a handful of well-known public configs so the very first launch has something
    /// to race, exactly like the Android bootstrap bundle.
    static func bootstrapSeed() -> String {
        defaults.string(forKey: K.bootstrapSeed) ?? ""
    }

    static func setBootstrapSeed(_ text: String) {
        defaults.set(text, forKey: K.bootstrapSeed)
    }

    // MARK: - selection

    static var selectedId: String? {
        get { defaults.string(forKey: K.selected) }
        set { defaults.set(newValue, forKey: K.selected) }
    }

    static var pinned: Set<String> {
        get { Set(defaults.stringArray(forKey: K.pinned) ?? []) }
        set { defaults.set(Array(newValue).sorted(), forKey: K.pinned) }
    }

    // MARK: - regime & switches

    static var regime: Regime {
        get { Regime.of(defaults.string(forKey: K.regime)) }
        set { defaults.set(newValue.rawValue, forKey: K.regime) }
    }

    static var overrides: Overrides {
        get {
            guard let data = defaults.data(forKey: K.overrides),
                  let o = try? JSONDecoder().decode(Overrides.self, from: data) else { return .default }
            return o
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: K.overrides)
            }
        }
    }

    // MARK: - extension write-backs

    static var activeId: String? {
        get { defaults.string(forKey: K.active) }
        set { defaults.set(newValue, forKey: K.active) }
    }

    static var statusText: String {
        get { defaults.string(forKey: K.statusText) ?? "" }
        set { defaults.set(newValue, forKey: K.statusText) }
    }

    static func saveReport(_ dict: [String: Any]) {
        if let data = try? JSONSerialization.data(withJSONObject: dict) {
            defaults.set(data, forKey: K.lastReport)
        }
    }

    static func loadReport() -> [String: Any]? {
        guard let data = defaults.data(forKey: K.lastReport),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj
    }
}
