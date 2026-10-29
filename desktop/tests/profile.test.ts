import { describe, expect, it } from "vitest";
import { buildConfig, DOH_SERVERS, fragInterval, fragRange } from "../src/shared/profile";
import { tuneFor } from "../src/shared/regime";
import { parseNodeUri } from "../src/shared/nodes";

const tlsNode = parseNodeUri(
  "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443?type=ws&security=tls&sni=edge.example.com&path=%2Fws&host=cdn.example.com#n",
)!;
const realityNode = parseNodeUri(
  "trojan://pw@ex.com:8443?security=reality&sni=www.microsoft.com&pbk=PK&sid=ab&fp=chrome&type=tcp#r",
)!;

describe("fragRange / fragInterval (Android parity)", () => {
  it("variable widens both ends; fixed pins them", () => {
    expect(fragRange(200, "variable", 2)).toBe("150-250");
    expect(fragRange(100, "", 1)).toBe("100");
    expect(fragRange(100, "", 3)).toBe("100-100");
    expect(fragInterval(20, "variable")).toBe("20-40");
    expect(fragInterval(35, "variable")).toBe("35-70");
    expect(fragInterval(5, "")).toBe("5");
  });
});

describe("buildConfig", () => {
  it("emits the proven v26 dialect: mixed inbound, DoH-by-IP dns, kill-switch LAN rule, dns-out", () => {
    const cfg = buildConfig(tlsNode, tuneFor("tight").tune);
    const inbounds = cfg["inbounds"] as Array<Record<string, unknown>>;
    expect(inbounds[0]!["protocol"]).toBe("mixed");
    expect(inbounds[0]!["listen"]).toBe("127.0.0.1");

    const dns = cfg["dns"] as Record<string, unknown>;
    expect(dns["queryStrategy"]).toBe("UseIPv4");
    const servers = dns["servers"] as Array<Record<string, unknown>>;
    expect(servers).toHaveLength(DOH_SERVERS.length);
    for (const s of servers) expect(String(s["address"])).toMatch(/^https:\/\/\d/);

    const routing = cfg["routing"] as Record<string, unknown>;
    const rules = routing["rules"] as Array<Record<string, unknown>>;
    expect(rules.some((r) => r["port"] === "53" && r["outboundTag"] === "dns-out")).toBe(true);
    expect(rules.some((r) => r["outboundTag"] === "direct" && Array.isArray(r["domain"]))).toBe(true);
  });

  it("TIGHT on TLS chains the fragment dialer; the fragment freedom carries tlshello", () => {
    const cfg = buildConfig(tlsNode, tuneFor("tight").tune);
    const outbounds = cfg["outbounds"] as Array<Record<string, unknown>>;
    const proxy = outbounds[0]!;
    const stream = proxy["streamSettings"] as Record<string, unknown>;
    expect((stream["sockopt"] as Record<string, unknown>)["dialerProxy"]).toBe("meelano-fragment");

    const frag = outbounds.find((o) => o["tag"] === "meelano-fragment")!;
    const settings = frag["settings"] as Record<string, Record<string, unknown>>;
    expect(settings["fragment"]!["packets"]).toBe("tlshello");
    expect(settings["fragment"]!["length"]).toBe("150-250");
    expect(settings["fragment"]!["interval"]).toBe("20-40");
    // the app-only echo must never leak into engine config
    expect(proxy["meelanoFragment"]).toBeUndefined();
    expect(proxy["meelanoTune"]).toBeUndefined();
  });

  it("Reality never fragments and uses realitySettings; ws transport carries Host header", () => {
    const cfg = buildConfig(realityNode, tuneFor("blackout").tune);
    const outbounds = cfg["outbounds"] as Array<Record<string, unknown>>;
    const stream = outbounds[0]!["streamSettings"] as Record<string, unknown>;
    expect(stream["security"]).toBe("reality");
    expect(stream["realitySettings"]).toBeDefined();
    expect(stream["sockopt"]).not.toHaveProperty("dialerProxy");
    expect(outbounds.some((o) => o["tag"] === "meelano-fragment")).toBe(false);

    const wsCfg = buildConfig(tlsNode, tuneFor("blackout").tune);
    const wsStream = (wsCfg["outbounds"] as Array<Record<string, unknown>>)[0]!["streamSettings"] as Record<
      string,
      unknown
    >;
    const ws = wsStream["wsSettings"] as Record<string, unknown>;
    expect(ws["path"]).toBe("/ws");
    expect((ws["headers"] as Record<string, string>)["Host"]).toBe("cdn.example.com");
  });

  it("CALM emits no fragment chain at all", () => {
    const cfg = buildConfig(tlsNode, tuneFor("calm").tune);
    const outbounds = cfg["outbounds"] as Array<Record<string, unknown>>;
    expect(outbounds.some((o) => o["tag"] === "meelano-fragment")).toBe(false);
  });

  it("keep-alive on → tcpFastOpen on; the ss protocol maps to shadowsocks", () => {
    const ss = parseNodeUri(
      `ss://${Buffer.from("aes-128-gcm:pw").toString("base64")}@1.2.3.4:8388#s`,
    )!;
    const cfg = buildConfig(ss, tuneFor("calm").tune);
    const proxy = (cfg["outbounds"] as Array<Record<string, unknown>>)[0]!;
    expect(proxy["protocol"]).toBe("shadowsocks");
    const stream = proxy["streamSettings"] as Record<string, unknown>;
    expect((stream["sockopt"] as Record<string, unknown>)["tcpFastOpen"]).toBe(true); // calm has keepAlive
  });
});
