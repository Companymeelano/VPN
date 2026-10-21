import SwiftUI

/// The user's switches: regime, fragmentation, mux, keepalive, fingerprint. Anything left on
/// "خودکار" means the regime decides (`Tuner.resolve` precedence: regime <- feed patch <- user).
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var palette

    var body: some View {
        Form {
            Section("رژیم شبکه") {
                Picker("رژیم", selection: Binding(
                    get: { model.regime },
                    set: { model.setRegime($0) }
                )) {
                    Text("خودکار (فشرده)").tag(Regime.tight)
                    Text("آرام").tag(Regime.calm)
                    Text("قطعی سراسری").tag(Regime.blackout)
                }
                .pickerStyle(.segmented)
                Text("در شرایط ایران پیش‌فرض «فشرده» است: Reality اول، فرگمنت TLS فعال، keepalive کوتاه.")
                    .font(.caption)
                    .foregroundColor(palette.faintC)
            }

            Section("ترنسپورت") {
                triState("فرگمنت TLS", \.fragment)
                triState("Mux", \.mux)
                stepperRow("keepalive (ثانیه)", \.keepAliveSec, range: 0...120)
                Picker("اثر انگشت TLS", selection: Binding(
                    get: { model.overrides.fingerprint ?? "" },
                    set: { o in
                        var ov = model.overrides
                        ov.fingerprint = o.isEmpty ? nil : o
                        model.setOverrides(ov)
                    }
                )) {
                    Text("خودکار (chrome)").tag("")
                    Text("chrome").tag("chrome")
                    Text("firefox").tag("firefox")
                    Text("safari").tag("safari")
                    Text("ios").tag("ios")
                    Text("android").tag("android")
                }
            }

            Section("اتصال") {
                Toggle("Failover خودکار", isOn: Binding(
                    get: { model.overrides.autoFailover ?? true },
                    set: { v in
                        var ov = model.overrides
                        ov.autoFailover = v
                        model.setOverrides(ov)
                    }
                ))
                Text("با دو شکست پیاپی، اپ خودش به سرور بعدی می‌رود؛ هیچ سروری حذف نمی‌شود، فقط عقب می‌رود.")
                    .font(.caption)
                    .foregroundColor(palette.faintC)
            }

            Section("درباره") {
                infoRow("نسخه", value: "2.6.0 (206000)")
                infoRow("هسته", value: XrayEngine.describe())
                Text("برای نصب روی آیفون، اپ باید با گواهی اپل امضا شود (TestFlight). جزئیات: docs/IOS-BUILD.md")
                    .font(.caption)
                    .foregroundColor(palette.faintC)
            }
        }
        .navigationTitle("تنظیمات")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Plain labeled row (LabeledContent is iOS 16+; we support 15).
    private func infoRow(_ title: String, value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundColor(palette.mutedC)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .multilineTextAlignment(.trailing)
        }
    }

    /// Three-state switch: nil = خودکار (the regime decides).
    private func triState(_ title: String, _ keyPath: WritableKeyPath<Overrides, Bool?>) -> some View {
        let current = model.overrides[keyPath: keyPath]
        return Picker(title, selection: Binding(
            get: { current.map { $0 ? "on" : "off" } ?? "auto" },
            set: { v in
                var ov = model.overrides
                ov[keyPath: keyPath] = v == "auto" ? nil : (v == "on")
                model.setOverrides(ov)
            }
        )) {
            Text("خودکار").tag("auto")
            Text("روشن").tag("on")
            Text("خاموش").tag("off")
        }
        .pickerStyle(.segmented)
    }

    private func stepperRow(_ title: String, _ keyPath: WritableKeyPath<Overrides, Int?>,
                            range: ClosedRange<Int>) -> some View {
        Stepper(value: Binding(
            get: { model.overrides[keyPath: keyPath] ?? range.lowerBound },
            set: { v in
                var ov = model.overrides
                ov[keyPath: keyPath] = v
                model.setOverrides(ov)
            }
        ), in: range) {
            HStack {
                Text(title)
                Spacer()
                Text(model.overrides[keyPath: keyPath].map { String($0) } ?? "خودکار")
                    .foregroundColor(palette.mutedC)
            }
        }
    }
}
