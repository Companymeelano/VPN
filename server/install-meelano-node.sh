#!/usr/bin/env bash
# =============================================================================
#  Meelano node installer — one-command, production-shaped Xray server.
#
#  What it puts on a fresh Debian/Ubuntu VPS:
#    1) VLESS + REALITY + Vision on TCP (default :443) — the primary transport.
#       No domain, no certificate: the handshake borrows a real site's TLS.
#    2) OPTIONAL (DOMAIN=...): VLESS + WebSocket behind Caddy's automatic TLS
#       on :443 (Reality then moves to :8443) — the CDN-frontable fallback for
#       the nights when everything non-web is dropped.
#    3) BBR congestion control, ufw firewall, systemd service, journald-only
#       logging (loglevel warning; no access log of users' destinations).
#
#  At the end it prints ready-to-paste client URIs and saves them to
#  /root/meelano-node.txt. Paste them into the app (VIP list) or into the
#  backend's data/vip_raw.txt — see server/README-fa.md.
#
#  Usage:
#    bash install-meelano-node.sh
#    DOMAIN=cdn.example.com bash install-meelano-node.sh
#    SNI=www.apple.com DEST=www.apple.com:443 bash install-meelano-node.sh
#
#  Re-running is safe: identity (uuid / reality keys / shortId) is kept in
#  /root/meelano-node.state, so already-distributed client URIs keep working.
#  Rotate identity deliberately with:  ROTATE=1 bash install-meelano-node.sh
# =============================================================================
set -euo pipefail

# ---- tunables (env) -----------------------------------------------------------
SNI="${SNI:-www.microsoft.com}"          # cover site the handshake pretends to visit
DEST="${DEST:-$SNI:443}"                 # where the server relays unauthenticated probes
PORT="${PORT:-443}"                      # Reality listen port
DOMAIN="${DOMAIN:-}"                     # optional: WS+TLS fallback behind Caddy
XRAY_VERSION="${XRAY_VERSION:-}"         # empty = latest pinned by the official installer
STATE_FILE="/root/meelano-node.state"
OUT_FILE="/root/meelano-node.txt"
CONF="/usr/local/etc/xray/config.json"

say() { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "run as root (sudo bash $0)"
command -v apt-get >/dev/null || die "Debian/Ubuntu (apt) is required"

if [ -n "$DOMAIN" ] && [ "$PORT" = "443" ]; then
  # Caddy needs 443 for real TLS; Reality keeps working fine on 8443.
  PORT=8443
fi

# ---- packages -----------------------------------------------------------------
say "Installing prerequisites"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl unzip openssl ca-certificates ufw >/dev/null

# ---- Xray (official installer) -------------------------------------------------
if ! command -v xray >/dev/null 2>&1; then
  say "Installing Xray-core (XTLS official installer)"
  if [ -n "$XRAY_VERSION" ]; then
    bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install --version "$XRAY_VERSION"
  else
    bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
  fi
fi
xray version | head -1 || true

# ---- identity (stable across re-runs, rotatable on request) -------------------
if [ -f "$STATE_FILE" ] && [ "${ROTATE:-0}" != "1" ]; then
  # shellcheck disable=SC1090
  . "$STATE_FILE"
  say "Keeping existing identity from $STATE_FILE (ROTATE=1 to replace)"
else
  say "Generating a fresh identity"
  UUID="$(cat /proc/sys/kernel/random/uuid)"
  KEYS="$(xray x25519)"
  PRIV="$(printf '%s' "$KEYS" | awk -F': ' '/PrivateKey|Private key/{print $2}' | tr -d ' \r')"
  PUB="$(printf '%s' "$KEYS" | awk -F': ' '/Password|Public key/{print $2}' | tr -d ' \r')"
  SID="$(openssl rand -hex 8)"
  WS_PATH="/$(openssl rand -hex 6)"
  [ -n "$PRIV" ] && [ -n "$PUB" ] || die "x25519 output not understood: $KEYS"
  umask 077
  cat > "$STATE_FILE" <<EOF
UUID=$UUID
PRIV=$PRIV
PUB=$PUB
SID=$SID
WS_PATH=$WS_PATH
EOF
fi

# ---- server config -------------------------------------------------------------
say "Writing $CONF (Reality on :$PORT${DOMAIN:+, WS fallback for $DOMAIN})"
mkdir -p "$(dirname "$CONF")"

WS_INBOUND=""
if [ -n "$DOMAIN" ]; then
  WS_INBOUND=$(cat <<EOF
,
    {
      "tag": "ws-in",
      "listen": "127.0.0.1",
      "port": 10000,
      "protocol": "vless",
      "settings": { "clients": [ { "id": "$UUID" } ], "decryption": "none" },
      "streamSettings": { "network": "ws", "wsSettings": { "path": "$WS_PATH" } }
    }
EOF
)
fi

cat > "$CONF" <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "reality-in",
      "listen": "0.0.0.0",
      "port": $PORT,
      "protocol": "vless",
      "settings": {
        "clients": [ { "id": "$UUID", "flow": "xtls-rprx-vision" } ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "$DEST",
          "xver": 0,
          "serverNames": [ "$SNI" ],
          "privateKey": "$PRIV",
          "shortIds": [ "", "$SID" ]
        }
      },
      "sniffing": { "enabled": true, "destOverride": [ "http", "tls", "quic" ], "routeOnly": true }
    }$WS_INBOUND
  ],
  "outbounds": [
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block",  "protocol": "blackhole" }
  ],
  "routing": {
    "rules": [
      { "type": "field", "protocol": [ "bittorrent" ], "outboundTag": "block" }
    ]
  }
}
EOF

xray run -test -config "$CONF" >/dev/null && echo "config OK"

# ---- optional Caddy front (real cert, automatic renewal) -----------------------
if [ -n "$DOMAIN" ]; then
  if ! command -v caddy >/dev/null 2>&1; then
    say "Installing Caddy (official apt repo)"
    apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https >/dev/null
    curl -fsSL 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -fsSL 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq && apt-get install -y -qq caddy >/dev/null
  fi
  cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    @ws path $WS_PATH
    reverse_proxy @ws 127.0.0.1:10000
    respond "ok" 200
}
EOF
  systemctl enable --quiet caddy
  systemctl restart caddy
fi

# ---- network tuning + firewall --------------------------------------------------
say "Enabling BBR + firewall"
cat > /etc/sysctl.d/99-meelano.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
EOF
sysctl --system >/dev/null 2>&1 || true
ufw allow ssh >/dev/null
ufw allow "$PORT"/tcp >/dev/null
[ -n "$DOMAIN" ] && { ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; }
ufw --force enable >/dev/null

# ---- go -------------------------------------------------------------------------
systemctl enable --quiet xray
systemctl restart xray
sleep 1
systemctl is-active --quiet xray || die "xray failed to start: journalctl -u xray -n 50"

IP="$(curl -fsS4 https://icanhazip.com 2>/dev/null || curl -fsS4 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
REALITY_URI="vless://$UUID@$IP:$PORT?encryption=none&security=reality&type=tcp&sni=$SNI&fp=chrome&pbk=$PUB&sid=$SID&flow=xtls-rprx-vision#Meelano-Reality"
{
  echo "# Meelano node — $IP — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# Paste into the app (VIP) or the backend's data/vip_raw.txt"
  echo "$REALITY_URI"
  if [ -n "$DOMAIN" ]; then
    echo "vless://$UUID@$DOMAIN:443?encryption=none&security=tls&type=ws&host=$DOMAIN&sni=$DOMAIN&path=$WS_PATH&fp=chrome#Meelano-WS"
  fi
} > "$OUT_FILE"
chmod 600 "$OUT_FILE" "$STATE_FILE"

say "Done. Client URIs (also saved to $OUT_FILE):"
cat "$OUT_FILE"
echo
echo "Next steps:"
echo "  1) Put the URI(s) into the backend data/vip_raw.txt (or paste in the app's VIP list)."
echo "  2) From a phone on the target carrier, connect and run a 204 check + a DNS leak test."
echo "  3) If this IP gets blocked later: order a fresh IP/VPS, re-run this script, update the list."
echo "     (Identity rotation on the same IP does NOT unblock an IP-level ban — the IP is the unit"
echo "      that gets blocked, which is why several small nodes beat one big one. See README-fa.)"
