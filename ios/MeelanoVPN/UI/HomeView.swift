import SwiftUI

/// The connect screen: one big honest ring, the active node card, and a compact server entry point.
/// Layout is adaptive on purpose - the ring shrinks on small devices and the cards stack, mirroring
/// the "کاملاً رسپانسیو" rule from the desktop work.
struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var palette
    @State private var showServers = false

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                statusRing
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)

                Text(model.statusLine)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundColor(palette.mutedC)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                nodeCard

                Button {
                    Task { await model.toggleConnection() }
                } label: {
                    HStack {
                        if model.isBusy {
                            ProgressView().tint(palette.accentInkC)
                        } else {
                            Image(systemName: model.connected ? "power" : "bolt.fill")
                        }
                        Text(model.connected ? "قطع اتصال" : "اتصال")
                            .fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(model.connected ? palette.dangerC : palette.accentC)
                    .foregroundColor(model.connected ? Color.white : palette.accentInkC)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .disabled(model.isBusy)
                .padding(.horizontal, 24)

                HStack(spacing: 12) {
                    statChip(title: "سرورها", value: "\(model.nodes.count)")
                    statChip(title: "رژیم", value: regimeLabel)
                    statChip(title: "به‌روزرسانی", value: model.feedAge)
                }
                .padding(.horizontal, 16)

                Button {
                    showServers = true
                } label: {
                    HStack {
                        Image(systemName: "list.bullet.rectangle")
                        Text("انتخاب سرور")
                            .fontWeight(.semibold)
                        Spacer()
                        Image(systemName: "chevron.left")
                    }
                    .padding()
                    .background(palette.surfaceC)
                    .foregroundColor(palette.textC)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(palette.lineC, lineWidth: 1)
                    )
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 32)
        }
        .background(palette.bgC.ignoresSafeArea())
        .sheet(isPresented: $showServers) {
            ServerListView()
        }
    }

    private var regimeLabel: String {
        switch model.regime {
        case .calm: return "آرام"
        case .tight: return "فشرده"
        case .blackout: return "قطعی"
        }
    }

    private var statusRing: some View {
        ZStack {
            Circle()
                .stroke(palette.wellC, lineWidth: 10)
            Circle()
                .trim(from: 0, to: model.connected ? 1 : 0.12)
                .stroke(
                    AngularGradient(
                        gradient: Gradient(colors: [palette.accentC, palette.accentDeepC, palette.accentC]),
                        center: .center
                    ),
                    style: StrokeStyle(lineWidth: 10, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .animation(.easeInOut(duration: 0.5), value: model.connected)
            VStack(spacing: 6) {
                Image(systemName: model.connected ? "lock.shield.fill" : "lock.open")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundColor(model.connected ? palette.accentC : palette.faintC)
                Text(model.connected ? "محافظت‌شده" : "محافظت خاموش")
                    .font(.system(.headline, design: .rounded))
                    .foregroundColor(palette.textC)
            }
        }
        .frame(width: 220, height: 220)
    }

    private var nodeCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("سرور فعال")
                .font(.caption)
                .foregroundColor(palette.faintC)
            if let node = model.selectedNode {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(node.name.nilIfBlank ?? "M•A VPN")
                            .font(.system(.headline, design: .rounded))
                            .foregroundColor(palette.textC)
                        Text(node.subtitle)
                            .font(.footnote)
                            .foregroundColor(palette.mutedC)
                    }
                    Spacer()
                    Text(node.grade)
                        .font(.system(.headline, design: .rounded))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(gradeColor(node.grade).opacity(0.18))
                        .foregroundColor(gradeColor(node.grade))
                        .clipShape(Capsule())
                }
            } else {
                Text("هنوز سروری انتخاب نشده است")
                    .font(.subheadline)
                    .foregroundColor(palette.mutedC)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceC)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(palette.lineC, lineWidth: 1)
        )
        .padding(.horizontal, 16)
    }

    private func gradeColor(_ g: String) -> Color {
        switch g.uppercased() {
        case "A", "B": return palette.accentC
        case "C": return palette.warnC
        default: return palette.dangerC
        }
    }

    private func statChip(title: String, value: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.system(.subheadline, design: .rounded).weight(.bold))
                .foregroundColor(palette.textC)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(title)
                .font(.caption2)
                .foregroundColor(palette.faintC)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(palette.surfaceC)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
