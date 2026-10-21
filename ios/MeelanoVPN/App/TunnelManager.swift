import Foundation
import NetworkExtension
import Combine

/// Owns the `NETunnelProviderManager`: install the VPN profile once, start/stop it, and translate
/// `NEVPNStatus` into something the UI can render. The provider bundle id must match the extension's
/// `PRODUCT_BUNDLE_IDENTIFIER` in project.yml.
@MainActor
final class TunnelManager: ObservableObject {

    static let providerBundleId = "ir.meelano.vpn.tunnel"
    static let profileName = "M•A VPN"

    @Published var status: NEVPNStatus = .invalid
    @Published var lastError: String?

    private var manager: NETunnelProviderManager?
    private var observer: AnyCancellable?

    init() {
        observer = NotificationCenter.default.publisher(for: .NEVPNStatusDidChange)
            .receive(on: RunLoop.main)
            .sink { [weak self] note in
                if let conn = note.object as? NEVPNConnection {
                    self?.status = conn.status
                }
            }
    }

    /// Load or create the VPN profile. Needs the Network Extension entitlement (docs/IOS-BUILD.md).
    func prepare() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            if let existing = managers.first {
                manager = existing
            } else {
                let m = NETunnelProviderManager()
                m.localizedDescription = TunnelManager.profileName
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = TunnelManager.providerBundleId
                proto.serverAddress = "M•A VPN"
                m.protocolConfiguration = proto
                m.isEnabled = true
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
                manager = m
            }
            status = manager?.connection.status ?? .invalid
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    func connect() async {
        if manager == nil { await prepare() }
        guard let manager = manager else {
            lastError = "پروفایل VPN نصب نشد"
            return
        }
        do {
            try await manager.loadFromPreferences()
            try manager.connection.startVPNTunnel()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
        refreshStatus()
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
        refreshStatus()
    }

    func refreshStatus() {
        status = manager?.connection.status ?? .invalid
    }

    /// Round-trip a diagnostics message to the extension process.
    func requestDiagnostics() async -> [String: Any]? {
        guard let manager = manager,
              let session = manager.connection as? NETunnelProviderSession,
              session.status == .connected else { return nil }
        return await withCheckedContinuation { cont in
            do {
                try session.sendProviderMessage(Data("diag".utf8)) { data in
                    if let data = data,
                       let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                        cont.resume(returning: obj)
                    } else {
                        cont.resume(returning: nil)
                    }
                }
            } catch {
                cont.resume(returning: nil)
            }
        }
    }
}
