import { useCallback, useEffect, useMemo, useState } from "react";
import type { AppSettingsState, AppSnapshot, ConnectionState, MeelanoApi } from "../shared/contract";
import type { NodeInfo } from "../shared/nodes";
import { formatBytes, formatDuration, formatMs, formatSpeed, lri, toFa } from "../shared/format";
import { REGIME_LABELS, type RegimeId } from "../shared/regime";

declare global {
  interface Window {
    meelano: MeelanoApi;
  }
}

type Tab = "connect" | "servers" | "settings";

const api = () => window.meelano;

export default function App(): JSX.Element {
  const [snap, setSnap] = useState<AppSnapshot | null>(null);
  const [tab, setTab] = useState<Tab>("connect");
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    void api().getSnapshot().then(setSnap);
    return api().onSnapshot(setSnap);
  }, []);

  // One shared clock for every «مدت اتصال» chip — a ticker per component re-renders too much.
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const conn = snap?.connection ?? { status: "idle" };
  const busy = conn.status === "connecting" || conn.status === "reconnecting";

  const theme = snap?.settings.theme ?? "system";
  useEffect(() => {
    const dark =
      theme === "dark" ||
      (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }, [theme]);

  if (!snap) return <div className="boot">در حال بارگذاری…</div>;

  return (
    <div className="shell">
      <TitleBar />
      <div className="body">
        <nav className="rail">
          <RailButton id="connect" tab={tab} setTab={setTab} label="اتصال" icon="◉" />
          <RailButton id="servers" tab={tab} setTab={setTab} label="سرورها" icon="⬡" />
          <RailButton id="settings" tab={tab} setTab={setTab} label="تنظیمات" icon="⚙" />
          <div className="rail-spacer" />
          <div className="rail-version">{lri("v" + snap.appVersion)}</div>
        </nav>

        <main className="content">
          {tab === "connect" && (
            <ConnectPanel snap={snap} conn={conn} busy={busy} now={now} onOpenServers={() => setTab("servers")} />
          )}
          {tab === "servers" && <ServersPanel snap={snap} />}
          {tab === "settings" && <SettingsPanel snap={snap} />}
        </main>
      </div>
    </div>
  );
}

// ────────────────────────────────────────────────────────────── title bar
function TitleBar(): JSX.Element {
  return (
    <header className="titlebar">
      <div className="titlebar-brand">
        <span className="brand-mark">M</span>
        <span className="brand-name">MA VPN</span>
      </div>
      <div className="titlebar-drag" />
      <div className="titlebar-controls">
        <button className="winbtn" onClick={() => api().minimize()} aria-label="کمینه">
          ─
        </button>
        <button className="winbtn winbtn-close" onClick={() => api().close()} aria-label="بستن">
          ✕
        </button>
      </div>
    </header>
  );
}

function RailButton(props: {
  id: Tab;
  tab: Tab;
  setTab: (t: Tab) => void;
  label: string;
  icon: string;
}): JSX.Element {
  const active = props.tab === props.id;
  return (
    <button className={`rail-btn${active ? " active" : ""}`} onClick={() => props.setTab(props.id)}>
      <span className="rail-icon">{props.icon}</span>
      <span>{props.label}</span>
    </button>
  );
}

// ────────────────────────────────────────────────────────────── connect
function ConnectPanel(props: {
  snap: AppSnapshot;
  conn: ConnectionState;
  busy: boolean;
  now: number;
  onOpenServers: () => void;
}): JSX.Element {
  const { snap, conn, busy, now } = props;
  const connected = conn.status === "connected";
  const activeName =
    conn.status === "connected" || conn.status === "connecting" || conn.status === "reconnecting"
      ? conn.node
      : snap.nodes.find((n) => n.id === snap.activeNodeId)?.name ?? "—";

  const onToggle = () => {
    if (connected || busy) void api().disconnect();
    else void api().connect("best");
  };

  return (
    <div className="connect-grid">
      <section className="hero card">
        <GlowOrb state={connected ? "connected" : busy ? "busy" : "idle"} />
        <button
          className={`dial${connected ? " dial-on" : ""}${busy ? " dial-busy" : ""}`}
          onClick={onToggle}
        >
          <span className="dial-ring" />
          <span className="dial-core">
            <span className="dial-power">⏻</span>
            <span className="dial-label">{connected ? "قطع اتصال" : "اتصال"}</span>
          </span>
        </button>

        <h1 className="hero-state">
          {conn.status === "idle" && "آماده‌ی اتصال"}
          {conn.status === "connecting" && `در حال اتصال به ${conn.node}…`}
          {conn.status === "connected" && "متصل شد ✓"}
          {conn.status === "reconnecting" && "اتصال مجدد…"}
          {conn.status === "error" && "خطا در اتصال"}
        </h1>
        <p className="hero-sub">
          {conn.status === "connecting" && conn.step}
          {conn.status === "connected" && `سرور ${conn.node} · پینگ ${formatMs(conn.latencyMs)}`}
          {conn.status === "reconnecting" && conn.reason}
          {conn.status === "error" && conn.message}
          {conn.status === "idle" && (snap.nodes.length > 0 ? `سرور انتخاب‌شده: ${activeName}` : "ابتدا لینک اشتراک را وارد کنید")}
        </p>

        {conn.status === "error" && snap.nodes.length === 0 && (
          <button className="btn primary" onClick={props.onOpenServers}>
            رفتن به تنظیمات اشتراک
          </button>
        )}
      </section>

      <section className="stats">
        <StatCard label="مدت اتصال" value={connected ? formatDuration((now - conn.since) / 1000) : lri("—")} />
        <StatCard label="پینگ" value={connected ? formatMs(conn.latencyMs) : lri("—")} />
        <StatCard label="پروکسی سیستم" value={snap.settings.systemProxy ? "فعال" : "غیرفعال"} />
        <StatCard label="حالت ضدفیلتر" value={REGIME_LABELS[snap.settings.regime]} />
      </section>

      <SpeedCard speed={snap.speed} />
    </div>
  );
}

function GlowOrb({ state }: { state: "connected" | "busy" | "idle" }): JSX.Element {
  return <div className={`glow glow-${state}`} />;
}

function StatCard({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div className="stat card">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}

function SpeedCard({ speed }: { speed: AppSnapshot["speed"] }): JSX.Element {
  const points = speed.slice(-40);
  const max = Math.max(1, ...points.map((p) => p.down));
  const path = points
    .map((p, i) => {
      const x = (i / Math.max(1, points.length - 1)) * 100;
      const y = 40 - (p.down / max) * 36;
      return `${i === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(" ");
  const last = points[points.length - 1];
  const sessionTotal = speed.reduce((sum, p) => sum + p.down * 2, 0); // 2s sampling interval
  return (
    <div className="card speed-card">
      <div className="speed-head">
        <span className="speed-title">سرعت شبکه</span>
        <span className="speed-now">
          {last ? formatSpeed(last.down) : lri("—")} · جلسه: {formatBytes(sessionTotal)}
        </span>
      </div>
      <svg className="speed-chart" viewBox="0 0 100 42" preserveAspectRatio="none">
        <defs>
          <linearGradient id="speedFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.35" />
            <stop offset="100%" stopColor="var(--accent)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {points.length > 1 && (
          <>
            <path d={`${path} L100,42 L0,42 Z`} fill="url(#speedFill)" />
            <path d={path} fill="none" stroke="var(--accent)" strokeWidth="1.4" />
          </>
        )}
      </svg>
    </div>
  );
}

// ────────────────────────────────────────────────────────────── servers
function ServersPanel({ snap }: { snap: AppSnapshot }): JSX.Element {
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");
  const [msg, setMsg] = useState<string | null>(null);

  const refresh = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const count = await api().refreshNodes();
      if (count === 0) setMsg("سروری دریافت نشد — لینک اشتراک را در تنظیمات بررسی کنید");
      else {
        setMsg(`تست پینگ ${toFa(count)} سرور…`);
        await api().testPings();
        setMsg(null);
      }
    } catch {
      setMsg("دریافت اشتراک ناموفق بود — اتصال اینترنت یا لینک را بررسی کنید");
    } finally {
      setBusy(false);
    }
  };

  const testPings = async () => {
    setBusy(true);
    try {
      await api().testPings();
    } finally {
      setBusy(false);
    }
  };

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = snap.nodes;
    if (!q) return list;
    return list.filter((n) => n.name.toLowerCase().includes(q) || n.host.toLowerCase().includes(q));
  }, [snap.nodes, query]);

  return (
    <div className="servers">
      <div className="servers-head">
        <h2>فهرست سرورها</h2>
        <div className="servers-tools">
          <input
            className="input"
            placeholder="جستجو…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <button className="btn" disabled={busy} onClick={() => void testPings()}>
            تست پینگ
          </button>
          <button className="btn primary" disabled={busy} onClick={() => void refresh()}>
            به‌روزرسانی
          </button>
        </div>
      </div>
      {msg && <div className="banner">{msg}</div>}
      {snap.nodes.length === 0 && !msg && (
        <div className="empty card">
          <p>هنوز سروری اضافه نشده است.</p>
          <p className="muted">در بخش تنظیمات، لینک اشتراک (VIP یا رایگان) را وارد کنید.</p>
        </div>
      )}
      <div className="node-list">
        {filtered.map((node) => (
          <NodeRow
            key={node.id}
            node={node}
            ping={snap.pings[node.id]}
            active={snap.activeNodeId === node.id}
          />
        ))}
      </div>
    </div>
  );
}

function NodeRow({ node, ping, active }: { node: NodeInfo; ping?: number; active: boolean }): JSX.Element {
  const reachable = ping !== undefined && ping >= 0;
  return (
    <div className={`node card${active ? " node-active" : ""}`}>
      <div className="node-main">
        <div className="node-name">{node.name}</div>
        <div className="node-sub">
          {lri(`${node.protocol} · ${node.network} · ${node.security}`)} · {lri(`${node.host}:${node.port}`)}
        </div>
      </div>
      <div className={`ping-badge${reachable ? " ping-ok" : ""}`}>
        {reachable ? formatMs(ping) : "ناموجود"}
      </div>
      <button className="btn primary sm" onClick={() => void api().connect(node.id)}>
        اتصال
      </button>
    </div>
  );
}

// ────────────────────────────────────────────────────────────── settings
function SettingsPanel({ snap }: { snap: AppSnapshot }): JSX.Element {
  const [saved, setSaved] = useState(false);
  const s = snap.settings;

  const patch = useCallback(async (p: Partial<AppSettingsState>) => {
    await api().updateSettings(p);
    setSaved(true);
    setTimeout(() => setSaved(false), 1600);
  }, []);

  return (
    <div className="settings">
      <h2>تنظیمات</h2>
      {saved && <div className="banner banner-ok">تنظیمات ذخیره شد ✓</div>}

      <section className="card set-card">
        <h3>اتصال</h3>
        <Row label="پروکسی سیستم ویندوز">
          <Toggle on={s.systemProxy} onChange={(v) => void patch({ systemProxy: v })} />
        </Row>
        <Row label="اتصال خودکار هنگام اجرا">
          <Toggle on={s.autoConnect} onChange={(v) => void patch({ autoConnect: v })} />
        </Row>
        <Row label="شروع با ویندوز">
          <Toggle on={s.autoStart} onChange={(v) => void patch({ autoStart: v })} />
        </Row>
        <Row label="دسترسی شبکه‌ی محلی (LAN)">
          <Toggle on={s.lanAccess} onChange={(v) => void patch({ lanAccess: v })} />
        </Row>
      </section>

      <section className="card set-card">
        <h3>ضدفیلتر</h3>
        <Row label="حالت">
          <div className="chips">
            {(Object.keys(REGIME_LABELS) as RegimeId[]).map((id) => (
              <button
                key={id}
                className={`chip${s.regime === id ? " chip-on" : ""}`}
                onClick={() => void patch({ regime: id })}
              >
                {REGIME_LABELS[id]}
              </button>
            ))}
          </div>
        </Row>
        <p className="muted">
          حالت «سفت» برای شبکه‌های ایران بهینه است؛ اگر اتصال برقرار نشد «سیاهه» را امتحان کنید.
        </p>
      </section>

      <section className="card set-card">
        <h3>ظاهر</h3>
        <Row label="پوسته">
          <div className="chips">
            {(["dark", "light", "system"] as const).map((t) => (
              <button
                key={t}
                className={`chip${s.theme === t ? " chip-on" : ""}`}
                onClick={() => void patch({ theme: t })}
              >
                {t === "dark" ? "تیره" : t === "light" ? "روشن" : "سیستم"}
              </button>
            ))}
          </div>
        </Row>
      </section>

      <section className="card set-card">
        <h3>اشتراک</h3>
        <Row label="لینک اشتراک">
          <input
            className="input wide"
            dir="ltr"
            defaultValue={s.subscriptionUrl}
            placeholder="https://…"
            onBlur={(e) => void patch({ subscriptionUrl: e.target.value.trim() })}
          />
        </Row>
        <p className="muted">
          لینک اشتراک شما فقط روی همین رایانه ذخیره می‌شود و هرگز در برنامه قرار نمی‌گیرد.
        </p>
      </section>

      <section className="card set-card">
        <h3>درباره</h3>
        <Row label="نسخه‌ی برنامه">{lri("MA VPN " + snap.appVersion)}</Row>
        <Row label="هسته">{lri("Xray-core " + snap.coreVersion)}</Row>
        <button className="btn" onClick={() => api().openExternal("https://github.com/Companymeelano/VPN/releases")}>
          صفحه‌ی نسخه‌ها
        </button>
      </section>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }): JSX.Element {
  return (
    <div className="row">
      <div className="row-label">{label}</div>
      <div className="row-control">{children}</div>
    </div>
  );
}

function Toggle({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }): JSX.Element {
  return (
    <button className={`toggle${on ? " toggle-on" : ""}`} onClick={() => onChange(!on)} aria-pressed={on}>
      <span className="knob" />
    </button>
  );
}
