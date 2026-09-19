#!/usr/bin/env bash
# The three mirrors of the palette must agree, or the light theme breaks again in silence.
#   1. android/app/src/main/java/ir/meelano/vpn/ui/theme/Color.kt   (Palette.Dark / Palette.Light)
#   2. android/app/src/main/res/values/colors.xml + values-night/colors.xml (what Android paints)
#   3. design/preview/index.html  (:root and body.light CSS custom properties - the prototype)
# Run it before every release:  ./tools/check-theme.sh
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/check-theme.py "$@"
