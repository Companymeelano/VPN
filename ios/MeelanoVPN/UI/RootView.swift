import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = Theme.palette(dark: scheme == .dark)
        NavigationView {
            HomeView()
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        Text("M•A VPN")
                            .font(.system(.headline, design: .rounded).weight(.heavy))
                            .foregroundColor(palette.textC)
                    }
                    ToolbarItem(placement: .navigationBarLeading) {
                        NavigationLink(destination: SettingsView()) {
                            Image(systemName: "gearshape.fill")
                                .foregroundColor(palette.mutedC)
                        }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        NavigationLink(destination: DiagnosticsView()) {
                            Image(systemName: "stethoscope")
                                .foregroundColor(palette.mutedC)
                        }
                    }
                }
        }
        .navigationViewStyle(.stack)
        .environment(\.palette, palette)
        .background(palette.bgC.ignoresSafeArea())
    }
}
