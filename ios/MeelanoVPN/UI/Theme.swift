import SwiftUI

/// The M•A palette, mirrored one more time for iOS. **Fourth mirror of the same tokens** -
/// `tools/check-theme.py` parses this file next to `ui/theme/Color.kt`, the XML colours and the
/// design prototype, and fails the build on drift. A token that exists in one mirror and not another
/// is exactly the bug class that check guards (dark worked, light was invisible).
struct Palette {
    let bg: UInt32
    let surface: UInt32
    let surfaceHigh: UInt32
    let well: UInt32
    let line: UInt32
    let hairline: UInt32
    let bezelTop: UInt32
    let bezelBottom: UInt32
    let text: UInt32
    let muted: UInt32
    let faint: UInt32
    let accent: UInt32
    let accentDeep: UInt32
    let accentInk: UInt32
    let info: UInt32
    let warn: UInt32
    let danger: UInt32

    var bgC: Color { Color(hex: bg) }
    var surfaceC: Color { Color(hex: surface) }
    var surfaceHighC: Color { Color(hex: surfaceHigh) }
    var wellC: Color { Color(hex: well) }
    var lineC: Color { Color(hex: line) }
    var hairlineC: Color { Color(hex: hairline) }
    var textC: Color { Color(hex: text) }
    var mutedC: Color { Color(hex: muted) }
    var faintC: Color { Color(hex: faint) }
    var accentC: Color { Color(hex: accent) }
    var accentDeepC: Color { Color(hex: accentDeep) }
    var accentInkC: Color { Color(hex: accentInk) }
    var infoC: Color { Color(hex: info) }
    var warnC: Color { Color(hex: warn) }
    var dangerC: Color { Color(hex: danger) }
}

enum Theme {
    // Values below are the literal mirror of res/values/colors.xml (light) and res/values-night (dark).
    static let light = Palette(
        bg: 0xFFF2F5F0,
        surface: 0xFFFBFCFA,
        surfaceHigh: 0xFFE8EDE5,
        well: 0xFFE3E9DF,
        line: 0xFFD9E0D5,
        hairline: 0x0E141C16,
        bezelTop: 0xFFFFFFFF,
        bezelBottom: 0xFFCBD5C6,
        text: 0xFF141C16,
        muted: 0xFF4E5C51,
        faint: 0xFF71806F,
        accent: 0xFF0C7A50,
        accentDeep: 0xFF075138,
        accentInk: 0xFFFFFFFF,
        info: 0xFF0B6A90,
        warn: 0xFF8A5A00,
        danger: 0xFFB3382E
    )

    static let dark = Palette(
        bg: 0xFF080D15,
        surface: 0xFF101821,
        surfaceHigh: 0xFF192431,
        well: 0xFF050810,
        line: 0xFF22303E,
        hairline: 0x0EFFFFFF,
        bezelTop: 0xFF2B3A49,
        bezelBottom: 0xFF0B1118,
        text: 0xFFEDF3F7,
        muted: 0xFF9FB0C0,
        faint: 0xFF687E93,
        accent: 0xFF4ADE9B,
        accentDeep: 0xFF128C5A,
        accentInk: 0xFF03281B,
        info: 0xFF8BD9F5,
        warn: 0xFFF6B14A,
        danger: 0xFFFF6B7A
    )

    static func palette(dark: Bool) -> Palette { dark ? self.dark : light }
}

extension Color {
    /// AARRGGBB (the Android spelling) -> SwiftUI color.
    init(hex: UInt32) {
        let a = Double((hex >> 24) & 0xFF) / 255
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8) & 0xFF) / 255
        let b = Double(hex & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

/// Reads the live palette for the current scheme, exactly like `LocalPalette` on Android.
struct PaletteKey: EnvironmentKey {
    static let defaultValue = Theme.dark
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}
