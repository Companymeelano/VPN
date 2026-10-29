/**
 * Node URIs (vless:// trojan:// ss://) and subscription payloads.
 *
 * This is the shared contract between the feed (backend/v) and the core profile builder: parse
 * once into a flat, total `NodeInfo`, then never look at the raw URI again. Every parse failure
 * returns null — a feed with one malformed line must still connect with the rest (the Android
 * app enforces the same rule in NodeUri.kt).
 *
 * Reality params (pbk/sid/fp) are parsed and kept: the builder emits reality settings when
 * security=reality, so a Reality node from a modern feed works as-is.
 */

export interface NodeInfo {
  id: string;
  name: string;
  protocol: "vless" | "trojan" | "ss";
  host: string;
  port: number;
  /** UUID for vless, password for trojan/ss. */
  auth: string;
  /** ss only: cipher name (e.g. aes-128-gcm). */
  cipher: string;
  /** tcp | ws | grpc | http | kcp | quic. */
  network: string;
  /** none | tls | reality. */
  security: string;
  sni: string;
  path: string;
  /** ws host header / grpc serviceName. */
  headerHost: string;
  alpn: string;
  fingerprint: string;
  /** reality: */
  publicKey: string;
  shortId: string;
  /** Raw URI kept for display/export only — never for building configs. */
  raw: string;
}

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

function parseName(hash: string, fallback: string): string {
  const name = safeDecode(hash.replace(/^#/, "")).trim();
  return name || fallback;
}

function base64ishDecode(input: string): string {
  const normalized = input.replace(/-/g, "+").replace(/_/g, "/");
  try {
    // atob exists in both Node 22 and browsers; Buffer path kept for padded edge cases.
    const pad = normalized.length % 4 === 0 ? "" : "=".repeat(4 - (normalized.length % 4));
    if (typeof atob === "function") return atob(normalized + pad);
  } catch {
    /* fall through */
  }
  try {
    return Buffer.from(normalized, "base64").toString("utf-8");
  } catch {
    return "";
  }
}

function fillCommon(q: URLSearchParams, info: Partial<NodeInfo>): void {
  info.network = (q.get("type") || q.get("headerType") || "tcp").toLowerCase();
  info.security = (q.get("security") || "none").toLowerCase();
  info.sni = q.get("sni") || q.get("serverName") || "";
  info.path = safeDecode(q.get("path") || q.get("serviceName") || "");
  info.headerHost = safeDecode(q.get("host") || "");
  info.alpn = safeDecode(q.get("alpn") || "");
  info.fingerprint = q.get("fp") || "";
  info.publicKey = q.get("pbk") || "";
  info.shortId = q.get("sid") || "";
}

/** Parse one URI. Unknown scheme or malformed shape → null. */
export function parseNodeUri(raw: string): NodeInfo | null {
  const uri = raw.trim();
  if (!uri) return null;
  try {
    const scheme = uri.slice(0, uri.indexOf("://")).toLowerCase();
    if (scheme === "vless" || scheme === "trojan") {
      const u = new URL(uri);
      const q = u.searchParams;
      const port = Number(u.port || (scheme === "vless" ? "443" : "443"));
      if (!u.hostname || !Number.isInteger(port) || port <= 0 || port > 65535) return null;
      const info: Partial<NodeInfo> = {
        id: `${scheme}-${u.hostname}-${port}-${u.username}`,
        name: parseName(u.hash, `${u.hostname}:${port}`),
        protocol: scheme,
        host: u.hostname,
        port,
        auth: safeDecode(u.username),
        cipher: "",
      };
      fillCommon(q, info);
      if (!info.auth) return null;
      return info as NodeInfo;
    }
    if (scheme === "ss") {
      // ss://base64(method:pass@host:port)#name  OR  ss://method:pass@host:port (plain)
      const hashIdx = uri.indexOf("#");
      const hash = hashIdx >= 0 ? uri.slice(hashIdx) : "";
      const body = uri.slice("ss://".length, hashIdx >= 0 ? hashIdx : undefined);
      let userInfo = "";
      let hostPort = "";
      const atIdx = body.lastIndexOf("@");
      if (atIdx >= 0) {
        userInfo = base64ishDecode(body.slice(0, atIdx)) || body.slice(0, atIdx);
        hostPort = body.slice(atIdx + 1);
      } else {
        const decoded = base64ishDecode(body);
        const dAt = decoded.lastIndexOf("@");
        if (dAt < 0) return null;
        userInfo = decoded.slice(0, dAt);
        hostPort = decoded.slice(dAt + 1);
      }
      const colon = userInfo.indexOf(":");
      if (colon <= 0) return null;
      const cipher = userInfo.slice(0, colon);
      const password = userInfo.slice(colon + 1);
      const hp = hostPort.replace(/^\[|\]$/g, "");
      const pIdx = hp.lastIndexOf(":");
      if (pIdx < 0) return null;
      const host = hp.slice(0, pIdx);
      const port = Number(hp.slice(pIdx + 1));
      if (!host || !Number.isInteger(port) || port <= 0 || port > 65535) return null;
      const q = new URLSearchParams(uri.includes("?") ? uri.slice(uri.indexOf("?") + 1, hashIdx >= 0 ? hashIdx : undefined) : "");
      const info: Partial<NodeInfo> = {
        id: `ss-${host}-${port}-${cipher}`,
        name: parseName(hash, `${host}:${port}`),
        protocol: "ss",
        host,
        port,
        auth: password,
        cipher,
      };
      fillCommon(q, info);
      return info as NodeInfo;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * A subscription payload: either base64-wrapped or plain text, one URI per line.
 * Comments (# …) and blank lines are skipped; one bad line never sinks the batch.
 */
export function parseSubscription(text: string): NodeInfo[] {
  const trimmed = text.trim();
  if (!trimmed) return [];
  let body = trimmed;
  if (!/^(vless|trojan|ss):\/\//i.test(trimmed.split("\n")[0] ?? "")) {
    const decoded = base64ishDecode(trimmed.replace(/\s+/g, ""));
    if (decoded && /^(vless|trojan|ss):\/\//i.test(decoded)) body = decoded;
  }
  const seen = new Set<string>();
  const out: NodeInfo[] = [];
  for (const line of body.split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith("#")) continue;
    const node = parseNodeUri(t);
    if (node && !seen.has(node.id)) {
      seen.add(node.id);
      out.push(node);
    }
  }
  return out;
}

/** Rank: reachable first, then lowest latency, then original order. Total on missing pings. */
export function rankByPing(nodes: NodeInfo[], pings: Map<string, number>): NodeInfo[] {
  return [...nodes].sort((a, b) => {
    const pa = pings.get(a.id);
    const pb = pings.get(b.id);
    const aOk = pa !== undefined && pa >= 0;
    const bOk = pb !== undefined && pb >= 0;
    if (aOk !== bOk) return aOk ? -1 : 1;
    if (aOk && bOk && pa !== pb) return pa - pb;
    return 0;
  });
}
