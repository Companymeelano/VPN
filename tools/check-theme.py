#!/usr/bin/env python3
"""
Palette mirror check. Fails (exit 1) when a colour is written differently in two of the three places.

Why a script and not a comment: the light theme broke precisely because "match the other file" was a
sentence. A token that exists in one mirror and not another is reported too - a missing light value is
exactly the bug class this guards (dark worked, light was invisible).
"""
import re, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COLOR_KT = "android/app/src/main/java/ir/meelano/vpn/ui/theme/Color.kt"
XML_LIGHT = "android/app/src/main/res/values/colors.xml"
XML_DARK = "android/app/src/main/res/values-night/colors.xml"
HTML = "design/preview/index.html"

def resolve(v, block):
    """Substitute var(--x) inside a value using the same block, so --hairline:rgba(var(--film),.055)
    can be compared: one indirection is all the token system uses, and resolving it here is what makes
    the film-token trick verifiable instead of merely plausible."""
    for _ in range(3):
        m = re.search(r"var\(--([a-z0-9-]+)\)", v)
        if not m:
            break
        rep = block.get(m.group(1))
        if rep is None:
            return v
        v = v[:m.start()] + rep + v[m.end():]
    return v


def norm(v):
    """#RGB / #RRGGBB / #AARRGGBB / rgba() -> comparable lowercase form with alpha as a 0..255 hex pair."""
    v = v.strip().lower().replace(" ", "")
    m = re.match(r"^rgba\(([\d.]+),([\d.]+),([\d.]+),([\d.]+)\)$", v)
    if m:
        r, g, b, a = m.group(1), m.group(2), m.group(3), m.group(4)
        ai = int(round(float(a) * 255))
        return "#%02x%02x%02x%02x" % (int(r), int(g), int(b), ai)   # rrggbbaa, like the hex branch below
    v = v.lstrip("#")
    if len(v) == 3:
        v = "".join(c * 2 for c in v)
    if len(v) == 6:
        v = "ff" + v
    if len(v) != 8 or not re.match(r"^[0-9a-f]{8}$", v):
        return v
    # Color.kt / XML write AARRGGBB; CSS writes RRGGBBAA-ish via rgba(); compare on RGBA
    return "#%02x%02x%02x%02x" % (int(v[2:4], 16), int(v[4:6], 16), int(v[6:8], 16), int(v[0:2], 16))

def kt_palette(text, name):
    m = re.search(r"val %s = Palette\((.*?)\n        \)" % name, text, re.S)
    if not m:
        return None
    out = {}
    for k, v in re.findall(r"(\w+) = Color\(0x([0-9A-Fa-f]{8})\)", m.group(1)):
        out[k] = "#" + v
    return out

def xml_colors(path):
    if not os.path.isfile(path):
        return None
    out = {}
    for n, v in re.findall(r'<color name="([a-z_0-9]+)">([^<]+)</color>', open(path).read()):
        out[n] = v
    return out

def css_vars(text, selector):
    m = re.search(re.escape(selector) + r"\s*\{(.*?)\}", text, re.S)
    if not m:
        return None
    return dict(re.findall(r"--([a-z0-9-]+)\s*:\s*([^;]+);", m.group(1)))

# name maps: Kotlin field -> xml color name -> css var (only entries that must exist everywhere)
MAP = [
    ("bg", "bg", "bg"), ("surface", "surface", "surface"), ("surfaceHigh", "surface_high", "surface-2"),
    ("well", "well", "well"), ("line", "line", "line"), ("hairline", "hairline", "hairline"),
    ("bezelTop", "bezel_top", "bezel-1"), ("bezelBottom", "bezel_bottom", "bezel-2"),
    ("text", "text", "text"), ("muted", "muted", "muted"), ("faint", "muted_faint", "muted-2"),
    ("accent", "accent", "accent"), ("accentDeep", "accent_deep", "accent-deep"),
    ("accentInk", "accent_ink", "accent-ink"), ("info", "info", "info"), ("warn", "warn", "warn"),
    ("danger", "danger", "danger"),
]

def main():
    problems = []
    kt = open(os.path.join(ROOT, COLOR_KT)).read()
    dark = kt_palette(kt, "Dark")
    light = kt_palette(kt, "Light")
    if dark is None or light is None:
        print("FAIL: cannot read Palette.Dark/Palette.Light from %s" % COLOR_KT)
        return 1
    xd, xl = xml_colors(os.path.join(ROOT, XML_DARK)), xml_colors(os.path.join(ROOT, XML_LIGHT))
    if xd is None:
        problems.append("%s is missing: the dark half must be an override, not the default" % XML_DARK)
        xd = {}
    if xl is None:
        problems.append("%s is missing" % XML_LIGHT)
        xl = {}
    html = open(os.path.join(ROOT, HTML)).read()
    css_dark, css_light = css_vars(html, ":root"), css_vars(html, "body.light")
    if css_light is None:
        problems.append("%s: no body.light block - the prototype has a theme toggle and nothing to toggle to" % HTML)
        css_light = {}

    def check(where, name, got, want):
        """Compare one value across mirrors, ignoring #rgb/#rrggbb/rgba() spelling differences."""
        if got is None:
            return
        if norm(got) != norm(want):
            problems.append("%s: --%s/%s is %s, Color.kt says %s" % (where, name, name, got, want))

    for kn, xn, cn in MAP:
        # XML is what Android paints (window background, notification accent, QS tile, launcher field)
        for xml, label, path in ((xd, "night", XML_DARK), (xl, "light", XML_LIGHT)):
            want = (dark if label == "night" else light)[kn]
            if xn not in xml:
                problems.append("%s: <color name=\"%s\"> missing, so the %s theme keeps a dark value" % (path, xn, label))
                continue
            check("%s (%s)" % (path, label), xn, xml[xn], want)
        # CSS is the prototype: a var present in :root but absent from body.light is the original bug
        for css, label in ((css_dark, "dark"), (css_light, "light")):
            want = (dark if label == "dark" else light)[kn]
            if cn not in css:
                # a value defined as rgba(var(--film),...) is theme-derived by construction: the film
                # flips, so it does not need its own body.light entry. Anything literal does.
                if label == "light" and "var(--" not in (css_dark.get(cn) or ""):
                    problems.append("%s: --%s missing from body.light (light theme would inherit the dark value)" % (HTML, cn))
                continue
            block = css_light if label == "light" else css_dark
            check("%s (body.%s)" % (HTML, label) if label == "light" else "%s (:root)" % HTML,
                  cn, resolve(css[cn], block), want)

    # the launcher field must be identical in both xml files on purpose (brand decision)
    if xd.get("icon_field") and xl.get("icon_field") and norm(xd["icon_field"]) != norm(xl["icon_field"]):
        problems.append("icon_field differs between themes: the launcher tile must not invert")
    if "icon_field" not in (xd or {}) or "icon_field" not in (xl or {}):
        problems.append("icon_field missing from a colors.xml (adaptive <background> references it)")

    if problems:
        print("theme mirrors disagree:")
        for p in problems:
            print("  - " + p)
        print("\nfix the value in every mirror; the mapping table lives in tools/check-theme.py MAP")
        return 1
    print("theme OK: Color.kt, values/-night colors.xml and the prototype agree on %d tokens" % len(MAP))
    return 0

sys.exit(main())
