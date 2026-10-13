#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# امضای APK نهایی — بیرون از CI.
#
# چرا لازم است: امضا در CI به چهار secret نیاز دارد (MEELANO_KEYSTORE_B64 و …). وقتی آن‌ها
# نرسند یا نخواهی کلید در GitHub بنشیند، مسیر جایگزین این است: CI بیلدِ release را
# *بی‌امضا* می‌دهد (برچسبِ release-dry-run روی PR → artifact `meelano-apk-release`) و همین
# اسکریپت با keystore خودت امضا و تأییدش می‌کند. نتیجه همان APK نهایی است — فقط کلید از
# دستِ تو خارج نمی‌شود.
#
# استفاده:
#   ./tools/sign-apk.sh <unsigned.apk> [<unsigned-universal.apk> …]
#         [--keystore ~/meelano-signing/meelano.keystore] [--alias meelano]
#         [--out DIR]
#   رمزها: از محیط (STORE_PASS / KEY_PASS) یا پرسشِ تعاملی — هیچ‌وقت آرگومان خط فرمان.
#
# ابزار امضا (به‌ترتیب): apksigner روی PATH → $ANDROID_HOME/build-tools → UBER_JAR
#   اگر هیچ‌کدام نبود: docs/ANDROID-BUILD.md §۳-پ
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

KEYSTORE="${KEYSTORE:-$HOME/meelano-signing/meelano.keystore}"
ALIAS="${ALIAS:-meelano}"
OUT_DIR="${OUT_DIR:-.}"
APKS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --keystore) KEYSTORE="$2"; shift 2 ;;
    --alias)    ALIAS="$2";    shift 2 ;;
    --out)      OUT_DIR="$2";  shift 2 ;;
    --key-pass)    KEY_PASS="$2";    shift 2 ;;
    --store-pass)  STORE_PASS="$2";  shift 2 ;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
    *)          APKS+=("$1"); shift ;;
  esac
done

die() { printf 'خطا: %s\n' "$*" >&2; exit 1; }
[ ${#APKS[@]} -gt 0 ] || die "هیچ APK ندادی. نمونه: ./tools/sign-apk.sh app-release-unsigned.apk"
[ -f "$KEYSTORE" ] || die "keystore پیدا نشد: $KEYSTORE (بساز: ./tools/make-signing-key.sh)"

# ── رمزها: از محیط، وگرنه پرسش. راهِ آرگومان را نمی‌گذاریم چون در `ps` دیده می‌شود.
if [ -z "${STORE_PASS:-}" ]; then
  if [ -t 0 ]; then printf 'storePass: '; read -r -s STORE_PASS; echo; else die "STORE_PASS را در محیط بده"; fi
fi
if [ -z "${KEY_PASS:-}" ]; then KEY_PASS="$STORE_PASS"; fi

# ── ابزار امضا
APKSIGNER=""
if command -v apksigner >/dev/null 2>&1; then
  APKSIGNER="$(command -v apksigner)"
else
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/local/lib/android/sdk; do
    [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || continue
    bt="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "${bt:-}" ] && [ -x "$bt/apksigner" ]; then APKSIGNER="$bt/apksigner"; break; fi
  done
fi
command -v java >/dev/null 2>&1 || die "java پیدا نشد (apksigner/uber-apk-signer به JDK نیاز دارند)"

# نوع keystore را از خودِ فایل بخوان (JKS یا PKCS12) — همان قاعده‌ی CI
magic="$(head -c 4 "$KEYSTORE" | od -An -tx1 | tr -d ' \n')"
case "$magic" in feedfeed) KS_TYPE=JKS ;; *) KS_TYPE=PKCS12 ;; esac

mkdir -p "$OUT_DIR"
for src in "${APKS[@]}"; do
  [ -f "$src" ] || die "فایل نیست: $src"
  base="$(basename "$src")"
  # نام نهایی: پسوندِ -unsigned حذف می‌شود تا همان قاعده‌ی Version.php (meelano-<name>-<code>.apk) بماند
  final="${base/-unsigned/}"
  [ "$final" = "$base" ] && final="${base%.apk}-signed.apk"
  tmp="$OUT_DIR/.$final"
  dst="$OUT_DIR/$final"

  echo "── امضا: $base → $final"
  if [ -n "$APKSIGNER" ]; then
    # zipalign قبل از امضا (اگر در دسترس باشد) وگرنه منابع فشرده‌نشده بی‌تراز می‌مانند
    aligned="$tmp.aligned"
    if command -v zipalign >/dev/null 2>&1; then
      zipalign -p -f 4 "$src" "$aligned"
    else
      cp "$src" "$aligned"
    fi
    "$APKSIGNER" sign \
      --ks "$KEYSTORE" --ks-type "$KS_TYPE" --ks-key-alias "$ALIAS" \
      --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
      --v2-signing-enabled true --v3-signing-enabled true \
      --out "$tmp" "$aligned"
    rm -f "$aligned"
  elif [ -n "${UBER_JAR:-}" ] && [ -f "$UBER_JAR" ]; then
    # uber-apk-signer خودش zipalign + sign + verify را انجام می‌دهد (یک jar، بدون SDK)
    tmpdir="$(mktemp -d)"; trap 'rm -rf "$tmpdir"' EXIT
    cp "$KEYSTORE" "$tmpdir/$ALIAS.keystore"
    java -jar "$UBER_JAR" -a "$src" -o "$tmpdir/out" \
      --ks "$tmpdir/$ALIAS.keystore" --ksAlias "$ALIAS" \
      --ksPass "$STORE_PASS" --ksKeyPass "$KEY_PASS" >/dev/null
    cp "$tmpdir/out/"*"$base" "$tmp" 2>/dev/null || cp "$tmpdir/out/"*.apk "$tmp"
  else
    die "نه apksigner روی PATH بود نه UBER_JAR داده شد — docs/ANDROID-BUILD.md §۳-پ"
  fi

  mv "$tmp" "$dst"
done

# ── تأیید و اثر گواهی: همان چیزی که CI چاپ می‌کند و در یادداشتِ ریلیز می‌رود
if [ -n "$APKSIGNER" ]; then
  for f in "$OUT_DIR"/*.apk; do
    case "$(basename "$f")" in *-unsigned*) continue ;; esac
    printf '\n── تأیید %s\n' "$(basename "$f")"
    "$APKSIGNER" verify --print-certs "$f" | sed -n 's/^\(Signer #1 certificate SHA-256 digest: \)\(.*\)/\1\2/p' | head -1 || true
    printf 'sha256: %s\n' "$(sha256sum "$f" | cut -d' ' -f1)"
    printf 'حجم: %s بایت\n' "$(stat -c%s "$f")"
  done
fi

cat <<'EOF'

مرحله‌ی بعد (انتشار):
  1) در یادداشتِ ریلیز، اثر گواهی (SHA-256) و sha256 فایل را بگذار — تفاوتِ اثر گواهی یعنی
     آپدیت روی نصب‌های قبلی رد می‌شود.
  2) نام فایل باید الگوی meelano-<versionName>-<versionCode>.apk را داشته باشد، بعد:
     gh release create vX.Y.Z --target main \
       meelano-X.Y.Z-<code>.apk meelano-X.Y.Z-<code>.apk.sha256 \
       meelano-X.Y.Z-<code>-universal.apk meelano-X.Y.Z-<code>-universal.apk.sha256 \
       --title "Meelano VPN — vX.Y.Z" --notes-file notes.md
  3) برای آپدیت خودکار، همان فایل‌ها را در public_html/v/apk/ هاست بگذار (§۵).
EOF
