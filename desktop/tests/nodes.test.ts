import { describe, expect, it } from "vitest";
import { parseNodeUri, parseSubscription, rankByPing } from "../src/shared/nodes";

const VLESS =
  "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443?type=ws&security=tls&sni=edge.example.com&path=%2Fws&host=cdn.example.com&alpn=h2&fp=chrome#%D8%B3%D8%B1%D9%88%D8%B1%20%DB%8C%DA%A9";

describe("parseNodeUri", () => {
  it("parses a vless+ws+tls URI completely", () => {
    const n = parseNodeUri(VLESS);
    expect(n).not.toBeNull();
    expect(n!.protocol).toBe("vless");
    expect(n!.host).toBe("edge.example.com");
    expect(n!.port).toBe(443);
    expect(n!.auth).toBe("11111111-2222-3333-4444-555555555555");
    expect(n!.network).toBe("ws");
    expect(n!.security).toBe("tls");
    expect(n!.sni).toBe("edge.example.com");
    expect(n!.path).toBe("/ws");
    expect(n!.headerHost).toBe("cdn.example.com");
    expect(n!.name).toBe("سرور یک");
  });

  it("parses trojan with reality params", () => {
    const n = parseNodeUri(
      "trojan://secret@ex.ir:8443?security=reality&sni=www.microsoft.com&pbk=PUBKEY&sid=abcd&fp=chrome&type=tcp#reality",
    );
    expect(n!.protocol).toBe("trojan");
    expect(n!.auth).toBe("secret");
    expect(n!.security).toBe("reality");
    expect(n!.publicKey).toBe("PUBKEY");
    expect(n!.shortId).toBe("abcd");
  });

  it("parses ss:// with base64 userinfo and with plain userinfo", () => {
    const b64 = Buffer.from("aes-128-gcm:pass123", "utf-8").toString("base64");
    const n1 = parseNodeUri(`ss://${b64}@1.2.3.4:8388#ss1`);
    expect(n1!.protocol).toBe("ss");
    expect(n1!.cipher).toBe("aes-128-gcm");
    expect(n1!.auth).toBe("pass123");
    expect(n1!.host).toBe("1.2.3.4");
    expect(n1!.port).toBe(8388);

    const whole = Buffer.from("aes-256-gcm:pw@5.6.7.8:443", "utf-8").toString("base64");
    const n2 = parseNodeUri(`ss://${whole}#ss2`);
    expect(n2!.host).toBe("5.6.7.8");
    expect(n2!.cipher).toBe("aes-256-gcm");
  });

  it("rejects malformed URIs with null instead of throwing", () => {
    expect(parseNodeUri("")).toBeNull();
    expect(parseNodeUri("vless://@:0#x")).toBeNull();
    expect(parseNodeUri("vless://no-uuid@h:443#x")).not.toBeNull(); // auth = "no-uuid" (kept)
    expect(parseNodeUri("vmess://abc")).toBeNull();
    expect(parseNodeUri("not a uri")).toBeNull();
    expect(parseNodeUri("ss://broken")).toBeNull();
  });
});

describe("parseSubscription", () => {
  it("accepts plain URI lists and base64-wrapped payloads; skips comments and dupes", () => {
    const plain = ["# comment", VLESS, VLESS, "trojan://p@h.io:443?security=tls#t"].join("\n");
    expect(parseSubscription(plain)).toHaveLength(2);

    const wrapped = Buffer.from(VLESS + "\n", "utf-8").toString("base64");
    const nodes = parseSubscription(wrapped);
    expect(nodes).toHaveLength(1);
    expect(nodes[0]!.host).toBe("edge.example.com");
  });

  it("a payload of garbage yields an empty list, never an exception", () => {
    expect(parseSubscription("~~~")).toEqual([]);
    expect(parseSubscription("")).toEqual([]);
  });
});

describe("rankByPing", () => {
  it("reachable first, then lowest latency, stable otherwise", () => {
    const mk = (id: string) => ({ id, name: id, raw: "" }) as never;
    const nodes = [mk("a"), mk("b"), mk("c")];
    const pings = new Map<string, number>([
      ["a", 300],
      ["c", 80],
    ]);
    const ranked = rankByPing(nodes, pings);
    expect(ranked.map((n) => (n as { id: string }).id)).toEqual(["c", "a", "b"]);
  });
});
