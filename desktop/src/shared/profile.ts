/**
 * Xray profile builder — the desktop twin of the Android app's CoreApi.xray().
 *
 * The shape is copied field-for-field from the config that proved itself on Iranian carriers
 * (Android CoreProfilesTest pins the same dialect against the v26 core line):
 *  - proxy outbound dials through a freedom "fragment" dialer when the regime wants TLS
 *    ClientHello splitting (`sockopt.dialerProxy`); Reality and plaintext shapes are never
 *    fragmented (splitting a borrowed handshake adds failures and buys nothing);
 *  - DNS is DoH *by IP* (five providers — the 2.8.1 set), so no bootstrap lookup can be
 *    poisoned; queryStrategy UseIPv4 (the v6 hang guard);
 *  - LAN routing is a user choice, not a hard rule: the desktop default is LAN *direct*
 *    (routers, printers and NAS keep working — a desktop is not a phone), and the
 *    «دسترسی شبکه محلی» switch turns it into the Android kill-switch semantics (LAN → block)
 *    when the user wants zero local escape. [ProfileOptions.allowLan] carries that choice.
 *
 * Pure data in, JSON out: no fs, no net — this module is unit-tested on every PR.
 */

import type { NodeInfo } from "./nodes";
import type { Tune } from "./regime";
import { wantsFragment } from "./regime";

/** DoH resolvers dialed by IP literal — mirrored from Android net/NetGuard.kt (2.8.1 set). */
export const DOH_SERVERS: Array<{ address: string; domains?: string[] }> = [
  { address: "https://8.8.8.8/dns-query" },
  { address: "https://1.1.1.1/dns-query" },
  { address: "https://223.5.5.5/dns-query" },
  { address: "https://94.140.14.14/dns-query" },
  { address: "https://9.9.9.9/dns-query" },
];

const LAN_CIDR = [
  "10.0.0.0/8",
  "172.16.0.0/12",
  "192.168.0.0/16",
  "127.0.0.0/8",
  "169.254.0.0/16",
  "fc00::/7",
  "fe80::/10",
];

const FRAGMENT_TAG = "meelano-fragment";

/** "100-200" style range: variable widens it, fixed pins it. Same numbers as Android. */
export function fragRange(size: number, strategy: string, count: number): string {
  if (strategy === "random" || strategy === "variable") {
    return `${Math.floor(size * 0.75)}-${Math.floor(size * 1.25)}`;
  }
  return count > 1 ? `${size}-${size}` : String(size);
}

export function fragInterval(delayMs: number, strategy: string): string {
  if (strategy === "random" || strategy === "variable") {
    return `${delayMs}-${Math.max(delayMs * 2, delayMs + 10)}`;
  }
  return String(delayMs);
}

export interface ProfileOptions {
  /** Local mixed (socks+http) inbound port. */
  localPort: number;
  /** Extra domain suffixes routed direct (e.g. [".ir"]) — «متناسب با ایران». */
  directDomains: string[];
  /** Allow LAN traffic instead of blocking it (kill-switch off). */
  allowLan: boolean;
}

export const DEFAULT_PROFILE_OPTIONS: ProfileOptions = {
  localPort: 10808,
  directDomains: [".ir"],
  allowLan: true,
};

function xrayFragment(tune: Tune): Record<string, unknown> {
  return {
    packets: "tlshello",
    length: fragRange(tune.fragSize, tune.fragStrategy, tune.fragCount),
    interval: fragInterval(tune.fragDelayMs, tune.fragStrategy),
  };
}

function outboundSettings(node: NodeInfo): Record<string, unknown> {
  switch (node.protocol) {
    case "vless":
      return {
        vnext: [
          {
            address: node.host,
            port: node.port,
            users: [
              {
                id: node.auth,
                ...(node.security === "reality" ? { flow: "xtls-rprx-vision" } : {}),
                encryption: "none",
              },
            ],
          },
        ],
      };
    case "trojan":
      return {
        servers: [{ address: node.host, port: node.port, password: node.auth }],
      };
    case "ss":
      return {
        servers: [
          { address: node.host, port: node.port, method: node.cipher, password: node.auth },
        ],
      };
  }
}

function transportSettings(node: NodeInfo): Record<string, unknown> | null {
  switch (node.network) {
    case "ws":
      return {
        path: node.path || "/",
        ...(node.headerHost ? { headers: { Host: node.headerHost } } : {}),
      };
    case "grpc":
      return {
        serviceName: node.path || "",
        ...(node.headerHost ? { authority: node.headerHost } : {}),
      };
    case "http":
      return {
        path: node.path || "/",
        ...(node.headerHost ? { host: [node.headerHost] } : {}),
      };
    case "httpupgrade":
      // Xray's own transport (stream network "httpupgrade") — the single-stream HTTP upgrade
      // shape some CDNs only allow. Downcasing it to "tcp" (the old shortcut) silently dropped
      // the transport and produced a config the server rejects.
      return {
        path: node.path || "/",
        ...(node.headerHost ? { host: node.headerHost } : {}),
      };
    default:
      return null;
  }
}

/**
 * The full run-config for `xray.exe run -c <this>`. Deterministic key order so two builds of
 * the same node diff cleanly in diagnostics.
 */
export function buildConfig(
  node: NodeInfo,
  tune: Tune,
  opts: ProfileOptions = DEFAULT_PROFILE_OPTIONS,
): Record<string, unknown> {
  const stream: Record<string, unknown> = {
    network: node.network || "tcp",
    sockopt: { tcpNoDelay: true, tcpFastOpen: tune.tfo },
  };
  const transport = transportSettings(node);
  if (transport) stream[`${stream["network"]}Settings`] = transport;

  const isTls = node.security === "tls" || node.security === "reality";
  if (isTls) stream["security"] = node.security;

  if (node.security === "tls") {
    const tls: Record<string, unknown> = { serverName: node.sni || node.host };
    if (node.alpn) tls["alpn"] = node.alpn;
    // Empty fingerprint is intentional: the pinned core reads it as Chrome Auto.
    tls["fingerprint"] = node.fingerprint || "";
    stream["tlsSettings"] = tls;
  }
  if (node.security === "reality") {
    stream["realitySettings"] = {
      fingerprint: node.fingerprint || "chrome",
      serverName: node.sni || node.host,
      publicKey: node.publicKey,
      shortId: node.shortId,
      spiderX: "",
    };
  }

  // Fragmentation: the proxy dials *through* the freedom dialer (dialerProxy chain link).
  // Reality/plaintext never fragment — see the Android rationale (CoreApi.kt).
  const fragmentWanted = wantsFragment(tune, node.security);
  if (fragmentWanted) {
    (stream["sockopt"] as Record<string, unknown>)["dialerProxy"] = FRAGMENT_TAG;
  }

  const outbound: Record<string, unknown> = {
    protocol: node.protocol === "ss" ? "shadowsocks" : node.protocol,
    tag: "proxy",
    settings: outboundSettings(node),
    streamSettings: stream,
  };

  const outbounds: Array<Record<string, unknown>> = [outbound];
  if (fragmentWanted) {
    outbounds.push({
      protocol: "freedom",
      tag: FRAGMENT_TAG,
      settings: { fragment: xrayFragment(tune) },
    });
  }
  outbounds.push({ protocol: "dns", tag: "dns-out", settings: {} });
  outbounds.push({ protocol: "freedom", tag: "direct" });
  outbounds.push({ protocol: "blackhole", tag: "block" });

  const rules: Array<Record<string, unknown>> = [
    {
      type: "field",
      ip: LAN_CIDR,
      outboundTag: opts.allowLan ? "direct" : "block",
    },
    // The tunnel must own DNS or the local resolver keeps leaking what we look up.
    {
      type: "field",
      network: "tcp,udp",
      port: "53",
      outboundTag: "dns-out",
    },
  ];
  // «کاملاً متناسب با ایران»: .ir و دامنه‌های داخلی مستقیم می‌روند — سرعتِ سایت‌های داخلی
  // کم نمی‌شود و پهنای باند تونل برای فیلترشکن می‌ماند.
  for (const domain of opts.directDomains) {
    if (domain) {
      rules.push({ type: "field", domain: [domain], outboundTag: "direct" });
    }
  }

  return {
    log: { loglevel: "warning" },
    inbounds: [
      {
        listen: "127.0.0.1",
        port: opts.localPort,
        protocol: "mixed",
        sniffing: {
          enabled: true,
          destOverride: ["http", "tls", "quic"],
          routeOnly: true,
        },
      },
    ],
    outbounds,
    dns: {
      queryStrategy: "UseIPv4",
      servers: DOH_SERVERS.map((s) => ({ address: s.address, timeoutMs: 4000 })),
    },
    routing: { domainStrategy: "IPIfNonMatch", rules },
  };
}
