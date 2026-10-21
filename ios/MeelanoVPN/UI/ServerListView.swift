import SwiftUI

/// The node ladder as the user sees it: same order the tunnel will dial (pinned → Iran bucket →
/// grade → latency), with the badges a support conversation needs (proto, transport, port, grade).
struct ServerListView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.palette) private var palette
    @Environment(\.presentationMode) private var presentation

    var body: some View {
        NavigationView {
            Group {
                if model.nodes.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 40))
                            .foregroundColor(palette.faintC)
                        Text("فهرست سرورها خالی است")
                            .foregroundColor(palette.mutedC)
                        Button("تلاش دوباره") {
                            Task { await model.refreshFeed(force: true) }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(model.nodes) { node in
                            row(node)
                                .listRowBackground(palette.surfaceC)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .background(palette.bgC.ignoresSafeArea())
            .navigationTitle("سرورها")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("بستن") { presentation.wrappedValue.dismiss() }
                        .foregroundColor(palette.accentC)
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        Task { await model.refreshFeed(force: true) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .foregroundColor(palette.mutedC)
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func row(_ node: FeedNode) -> some View {
        Button {
            model.select(node)
            presentation.wrappedValue.dismiss()
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(node.name.nilIfBlank ?? "M•A VPN")
                            .font(.system(.subheadline, design: .rounded).weight(.bold))
                            .foregroundColor(palette.textC)
                        if model.isPinned(node) {
                            Image(systemName: "pin.fill")
                                .font(.caption2)
                                .foregroundColor(palette.accentC)
                        }
                        if node.id == model.selectedId {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.caption)
                                .foregroundColor(palette.accentC)
                        }
                    }
                    Text(node.subtitle)
                        .font(.caption)
                        .foregroundColor(palette.mutedC)
                    Text("\(node.proto) · \(node.tls ?? "بدون TLS") · \(node.network ?? "tcp")")
                        .font(.caption2)
                        .foregroundColor(palette.faintC)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text(node.grade)
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(palette.wellC)
                        .foregroundColor(palette.textC)
                        .clipShape(Capsule())
                    Text(node.latencyLabel)
                        .font(.caption2)
                        .foregroundColor(palette.faintC)
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .simultaneousGesture(LongPressGesture().onEnded { _ in
            model.togglePin(node)
        })
    }
}
