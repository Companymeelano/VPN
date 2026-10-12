#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ساختِ کلید امضای M•A VPN + ست‌کردنِ چهار secret گیت‌هاب — یک‌بار برای همیشه.
#
# چرا این اسکریپت وجود دارد: مسیر دستی چندین جای خطا دارد (کوتیشنِ رمزها، پرچمِ
# متفاوتِ base64 در macOS، و مهم‌تر از همه فراموش‌کردنِ رمزها بعد از ساخت). این‌جا
# کلید و رمزها در پوشه‌ای *بیرونِ* مخزن ساخته می‌شوند تا اشتباهی کامیت نشوند، و
# کنارشان یک فایل اعاده‌ی رمز نوشته می‌شود.
#
# اجرا:  ./tools/make-signing-key.sh
# اجرای دوباره وقتی کلید از قبل هست: بازتولید نمی‌کند و فقط secretها را ست می‌کند
# (رمزها را از فایلِ اعاده می‌خواند) — مسیرِ ترمیم وقتی secretها جای اشتباه رفته‌اند.
#
# پیش‌نیاز:  JDK (keytool)، gh، openssl — و gh با حسابی که روی مخزن دسترسی admin دارد.
#
# ⚠️ فایلِ meelano.keystore و رمزهایش هویتِ انتشار توست. اگر گم شود، کاربرانِ نسخه‌ی
#    قبلی فقط با حذف و نصب دوباره می‌توانند آپدیت کنند. از پوشه‌ی خروجی بکاپ بگیر.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO="${REPO:-Companymeelano/VPN}"
OUT="${OUT:-$HOME/meelano-signing}"   # عمداً بیرون از مخزن
ALIAS="${ALIAS:-meelano}"
DAYS="${DAYS:-10950}"                 # ~۳۰ سال
KEYTOOL="${KEYTOOL:-keytool}"         # قابل override برای تست
GH="${GH:-gh}"

step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die()  { printf 'خطا: %s\n' "$*" >&2; exit 1; }

step "۰) بررسی پیش‌نیازها"
command -v "$KEYTOOL" >/dev/null 2>&1 || die "keytool پیدا نشد — یک JDK (۱۷) نصب کن"
command -v openssl  >/dev/null 2>&1 || die "openssl پیدا نشد"
"$GH" auth status >/dev/null 2>&1 || die "اول 'gh auth login' کن (حسابی با دسترسی admin روی $REPO)"

KS="$OUT/meelano.keystore"
CRED="$OUT/keystore-credentials.txt"
mkdir -p "$OUT"
chmod 700 "$OUT"

cred_get() { [ -f "$CRED" ] && sed -n "s/^$1 *: *//p" "$CRED" | head -1 || true; }

REUSE=0
FINGERPRINT=""
if [ -e "$KS" ]; then
  if [ "${FORCE:-0}" = "1" ]; then
    echo "FORCE=1 → کلیدِ موجود بازنویسی می‌شود: $KS"
    rm -f "$KS"
  else
    # مسیر دوباره‌اجرا: کلید را دست نزن، اما secretها را (دوباره) ست کن. رمزها از فایلِ
    # اعاده می‌آیند؛ اگر نبود، از محیط یا پرسشِ تعاملی. بدون هیچ‌کدام، توقف — چون رمزِ
    # اشتباهی که بی‌صدا ست شود، دقیقاً همان چیزی است که «بیلد امضاشده» را بی‌صدا می‌شکند.
    REUSE=1
    [ -n "${STORE_PASS:-}" ] || STORE_PASS="$(cred_get storePass || true)"
    [ -n "${KEY_PASS:-}" ]   || KEY_PASS="$(cred_get keyPass || true)"
    cred_alias="$(cred_get alias || true)"; [ -n "$cred_alias" ] && ALIAS="$cred_alias"
    FINGERPRINT="$(cred_get SHA-256 || true)"
    if [ -z "${STORE_PASS:-}" ] && [ -t 0 ]; then
      printf 'storePass (کلیدِ موجود در %s): ' "$KS"; read -r -s STORE_PASS; echo
    fi
    if [ -z "${KEY_PASS:-}" ] && [ -t 0 ]; then
      printf 'keyPass: '; read -r -s KEY_PASS; echo
    fi
    [ -n "${STORE_PASS:-}" ] || die "کلید موجود است ولی رمزش را ندارم.
  یا فایلِ اعاده ($CRED) را برگردان، یا رمز را در محیط بده: STORE_PASS='…' KEY_PASS='…' $0"
    [ -n "${KEY_PASS:-}" ] || die "keyPass را ندارم — همان بالا (STORE_PASS/KEY_PASS در محیط)."
    echo "کلیدِ موجود استفاده می‌شود (بدون بازتولید): $KS"
  fi
fi

if [ "$REUSE" = 0 ]; then
  umask 077
  step "۱) ساختِ کلید (RSA 4096، معتبر ${DAYS} روز)"
  STORE_PASS="$(openssl rand -hex 24)"
  KEY_PASS="$(openssl rand -hex 24)"
  # نوع پیش‌فرضِ keytool در JDK 9+ مخزنِ PKCS12 است؛ CI خودش نوع را از جادوی فایل تشخیص
  # می‌دهد، پس نیازی به -storetype نیست (و JKS هم اگر بدهی کار می‌کند).
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity "$DAYS" \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=M•A VPN, O=Meelano, C=IR" >/dev/null
fi

if [ -z "$FINGERPRINT" ]; then
  FINGERPRINT="$("$KEYTOOL" -list -v -keystore "$KS" -storepass "$STORE_PASS" -alias "$ALIAS" 2>/dev/null \
    | sed -n 's/.*SHA256: *//p' | head -1 | tr -d '\r')" || true
fi
[ -n "$FINGERPRINT" ] || FINGERPRINT="(نامشخص — با 'keytool -list -v' ببین)"

step "۲) نوشتنِ فایلِ اعاده‌ی رمزها (فقط روی همین ماشین)"
{
  echo "M•A VPN — کلید امضای انتشار"
  echo "ساخته‌شده: $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo
  echo "keystore : $KS"
  echo "alias    : $ALIAS"
  echo "storePass: $STORE_PASS"
  echo "keyPass  : $KEY_PASS"
  echo "SHA-256  : $FINGERPRINT"
  echo
  echo "این فایل و meelano.keystore را در جای امن نگه دار (بکاپِ رمزنگاری‌شده)."
  echo "اگر گم شوند: کاربرانِ نصب‌شده فقط با حذف و نصب دوباره می‌توانند آپدیت کنند."
  echo "اثر گواهی بالا باید در یادداشتِ هر ریلیز تکرار شود؛ تفاوت = آپدیتِ رد‌شده."
} > "$CRED"
chmod 600 "$CRED"
echo "  → $CRED"

step "۳) ست‌کردنِ چهار secret روی $REPO"
# چرا فایل موقت و نه پایپ: اگر gh بمیرد (نبودِ دسترسی/شبکه)، پایپ با SIGPIPE وسط کار
# می‌شکند و پیامِ خطا شبیه باگِ اسکریپت می‌شود. با -out و ورودیِ فایل، خطا صریح و مربوط است.
# و چرا stdin و نه --body: آرگومان‌های خط فرمان در `ps` دیده می‌شوند؛ رمز نباید آن‌جا باشد.
B64="$OUT/.keystore.b64"
openssl base64 -A -in "$KS" -out "$B64"
"$GH" secret set MEELANO_KEYSTORE_B64 --repo "$REPO" < "$B64" \
  || die "ست‌کردن MEELANO_KEYSTORE_B64 نشد — با حسابی که دسترسی admin به $REPO دارد وارد شده‌ای؟ ('gh auth status')"
rm -f "$B64"
printf '%s' "$STORE_PASS" | "$GH" secret set MEELANO_STORE_PASS --repo "$REPO" || die "MEELANO_STORE_PASS ست نشد"
printf '%s' "$KEY_PASS"   | "$GH" secret set MEELANO_KEY_PASS   --repo "$REPO" || die "MEELANO_KEY_PASS ست نشد"
printf '%s' "$ALIAS"      | "$GH" secret set MEELANO_KEY_ALIAS  --repo "$REPO" || die "MEELANO_KEY_ALIAS ست نشد"
echo "  ✔ MEELANO_KEYSTORE_B64 / MEELANO_STORE_PASS / MEELANO_KEY_PASS / MEELANO_KEY_ALIAS"
echo "  اثر گواهی: $FINGERPRINT"
echo "  تأیید از سمت خودت:  gh secret list --repo $REPO"

if [ -n "${MEELANO_FEED_SECRET:-}" ]; then
  step "۴) ست‌کردنِ کلید HMAC امضای فید"
  printf '%s' "$MEELANO_FEED_SECRET" | "$GH" secret set MEELANO_FEED_SECRET --repo "$REPO" \
    || die "MEELANO_FEED_SECRET ست نشد"
  echo "  ✔ MEELANO_FEED_SECRET"
else
  step "۴) کلید HMAC فید"
  echo "  (اختیاری) برای امضای version.json:"
  echo "  MEELANO_FEED_SECRET='…' ./tools/make-signing-key.sh   # فقط همین مرحله را هم می‌توان دستی زد"
  echo "  gh secret set MEELANO_FEED_SECRET --repo $REPO"
fi

step "تمام."
cat <<EOF
مرحله‌ی بعد:
  1) برچسبِ تمرین روی PR بگذار (بیلد release + امضا، بدون انتشار ریلیز):
       gh label create release-dry-run --repo $REPO
       gh api -X POST repos/$REPO/issues/<PR>/labels -f 'labels[]=release-dry-run'
     APK امضاشده را از artifact بردار:  gh run download <run-id> -n meelano-apk-release
  2) اگر تمرین سبز بود، تگِ تمیز روی main بزن:
       git tag -a v2.5.0 -m "M•A VPN v2.5.0" && git push origin v2.5.0
     ⇒ ریلیزِ عمومیِ امضاشده با لینک ثابت ساخته می‌شود.
EOF
