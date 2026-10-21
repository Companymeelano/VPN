import SwiftUI

/// The support screen: what the tunnel last proved, what the core says it is, and what the feed
/// thinks of itself. A screenshot of this page should be enough for a first-line diagnosis.
struct DiagnosticsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var palette

    var body: some View {
        List {
            Section("وضعیت") {
                row("اتصال", model.statusLine)
                row("سرور انتخابی", model.selectedNode.map { "\($0.name) · \($0.host):\($0.port)" } ?? "—")
                row("تعداد سرورها", "\(model.nodes.count)")
                row("به‌روزرسانی فید", model.feedAge)
                if let err = model.feedError {
                    row("خطای فید", err)
                }
            }

            Section("هسته") {
                ForEach(model.diagnostics.sorted(by: { $0.key < $1.key }), id: \.key) { key, value in
                    row(key, value)
                }
            }

            Section {
                Button {
                    Task {
                        await model.loadDiagnostics()
                    }
                } label: {
                    HStack {
                        Image(systemName: "arrow.triangle.2.circlepath")
                        Text("به‌روزرسانی عیب‌یابی")
                    }
                }
                Button {
                    Task { await model.refreshFeed(force: true) }
                } label: {
                    HStack {
                        Image(systemName: "arrow.clockwise")
                        Text("دریافت دوبارهٔ فید")
                    }
                }
            }
        }
        .navigationTitle("عیب‌یابی")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            Task { await model.loadDiagnostics() }
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundColor(palette.faintC)
            Text(value)
                .font(.footnote)
                .foregroundColor(palette.textC)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 2)
    }
}
