import NetworkExtension
import Foundation

/// The Packet Tunnel Provider: installs a real iOS VPN profile and routes app traffic into the
/// in-process Xray core.
///
/// Traffic model (v1, deliberately chosen and documented in docs/IOS-BUILD.md): iOS hands proxied
/// sockets to us through `NEProxySettings` pointing at the core's mixed inbound on
/// 127.0.0.1:10808. Hostnames travel to the node *inside* the proxy protocol and resolve there
/// (remote DNS), so covered traffic neither leaks DNS nor needs a userspace TCP/IP stack. The TUN
/// (`packetFlow`) carries no app traffic in this mode - that is also why the profile installs no
/// default route: a hijacked route with nothing reading `packetFlow` would black-hole the device.
///
/// What this covers honestly: every app that honours the system proxy (browsers, most apps using
/// URLSession/WebView stacks). What it does not: raw-UDP games and apps that bypass system proxy
/// settings. The seams for a future tun2socks `packetFlow` engine are kept (`packetFlow` is untouched).
final class PacketTunnelProvider: NEPacketTunnelProvider {

    private let session = TunnelSession()
    private var startContinuation: ((Error?) -> Void)?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let selected = SharedStore.selectedId
        Task { [weak self] in
            guard let self = self else { return }
            do {
                let node = try await self.session.start(selectedId: selected)
                self.setTunnelNetworkSettings(self.makeSettings()) { error in
                    if let error = error {
                        completionHandler(error)
                        return
                    }
                    self.session.onStatus = { line in
                        SharedStore.statusText = line
                    }
                    // The chain is up and proven; report success with the node that won.
                    SharedStore.statusText = "connected: \(node.name) \(node.host):\(node.port)"
                    completionHandler(nil)
                    Task { await self.session.flushFeedback() }
                }
            } catch {
                completionHandler(error)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        Task { [weak self] in
            await self?.session.flushFeedback()
            self?.session.stop()
            completionHandler()
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        // Tiny diagnostics channel: the app asks for the live status card.
        var out: [String: Any] = [
            "status": SharedStore.statusText,
            "core": XrayEngine.describe(),
            "coreVersion": XrayEngine.version() ?? "unknown",
        ]
        if let node = session.activeNode {
            out["node"] = ["id": node.id, "name": node.name, "host": node.host, "port": node.port] as [String: Any]
        }
        if let probe = session.lastProbe {
            out["verdict"] = probe.verdict
        }
        completionHandler?(try? JSONSerialization.data(withJSONObject: out))
    }

    /// Proxy-mode network settings. No default route on purpose - see the file header.
    private func makeSettings() -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")

        // A loopback-only tunnel address so the OS has a valid interface; app traffic reaches the
        // core via the proxy settings below, not via this address.
        let v4 = NEIPv4Settings(addresses: ["10.8.0.2"], subnetMasks: ["255.255.255.255"])
        v4.includedRoutes = []
        v4.excludedRoutes = []
        settings.ipv4Settings = v4

        let proxy = NEProxySettings()
        proxy.httpEnabled = true
        proxy.httpServer = NEProxyServer(address: TunnelSession.proxyHost, port: TunnelSession.proxyPort)
        proxy.httpsEnabled = true
        proxy.httpsServer = NEProxyServer(address: TunnelSession.proxyHost, port: TunnelSession.proxyPort)
        // No SOCKS fields here: NEProxySettings exposes socks only on macOS. The mixed inbound still
        // speaks SOCKS for anything that dials it by hand; the *system* proxy is HTTP/HTTPS.
        // matchDomains [""] = "use this proxy for the whole device" - the default route of proxies.
        proxy.matchDomains = [""]
        proxy.excludeSimpleHostnames = true
        settings.proxySettings = proxy

        // Do *not* install NEDNSSettings here: proxied traffic resolves at the node (remote DNS),
        // and a system-wide DNS pointed at 1.1.1.1 would be plain-DNS straight into the carrier's
        // hands - the exact leak we are avoiding. docs/IOS-BUILD.md spells out the boundary.
        settings.mtu = 1500
        return settings
    }
}
