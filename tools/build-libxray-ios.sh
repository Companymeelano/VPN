#!/usr/bin/env bash
# Build the pinned XTLS/libXray tunnel core for iOS and wire the Xcode project.
#
#   tools/build-libxray-ios.sh            # build core + generate ios/MeelanoVPN.xcodeproj
#   tools/build-libxray-ios.sh core-only  # just the xcframework
#
# Needs macOS with Xcode (xcrun, lipo, xcodebuild), git, go and python3. The same pinned CalVer tag
# as the Android build (.github/workflows/apk.yml) - one engine, one version, both platforms.
# The `apple go` (cgo) flavor is deliberate: it ships libXray.h + module.modulemap so Swift just
# `import LibXray` and calls CGoInvoke/CGoFree (docs/IOS-BUILD.md).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
LIBXRAY_TAG="${LIBXRAY_TAG:-v26.9.9}"
MODE="${1:-all}"
WORK="/tmp/libxray-ios"

echo "== libXray $LIBXRAY_TAG -> ios/LibXray.xcframework (apple go / cgo)"

if [ "$(uname)" != "Darwin" ]; then
  echo "ERROR: the iOS core can only be built on macOS (xcrun/lipo/xcodebuild)." >&2
  exit 1
fi

command -v go >/dev/null || { echo "ERROR: go is required (brew install go)" >&2; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required" >&2; exit 1; }

rm -rf "$WORK"
git clone --depth 1 --branch "$LIBXRAY_TAG" https://github.com/XTLS/libXray "$WORK"
cd "$WORK"

# The cgo bridge (CGoInvoke/CGoFree) is the only entry point; pin the contract before building so an
# upstream rename fails here, in a named step, instead of at the user's phone.
grep -q 'func CGoInvoke' cgo_bridge/main.go
grep -q 'func CGoFree' cgo_bridge/main.go

python3 build/main.py apple go

# apple_go.py writes the xcframework to the repo root (Builder.lib_dir = build/..), same place the
# android builder drops libXray.aar. Not under lib/.
XCFRAMEWORK="$WORK/LibXray.xcframework"
[ -d "$XCFRAMEWORK" ] || { echo "ERROR: build produced no LibXray.xcframework at $XCFRAMEWORK" >&2; exit 1; }
HEADER="$(find "$XCFRAMEWORK" -name libXray.h -print -quit)"
[ -n "$HEADER" ] || { echo "ERROR: no libXray.h inside the xcframework" >&2; exit 1; }
grep -q 'CGoInvoke' "$HEADER"
grep -q 'CGoFree' "$HEADER"

rm -rf "$ROOT/ios/LibXray.xcframework"
cp -R "$XCFRAMEWORK" "$ROOT/ios/LibXray.xcframework"
echo "core: ios/LibXray.xcframework"
shasum -a 256 "$ROOT/ios/LibXray.xcframework/Info.plist" | awk '{print "xcframework Info.plist sha256: " $1}'

if [ "$MODE" = "core-only" ]; then
  exit 0
fi

command -v xcodegen >/dev/null || { echo "ERROR: xcodegen is required (brew install xcodegen)" >&2; exit 1; }
cd "$ROOT/ios"
xcodegen generate
echo "project: ios/MeelanoVPN.xcodeproj"
echo
echo "Next: open ios/MeelanoVPN.xcodeproj (see docs/IOS-BUILD.md for signing & TestFlight)."
