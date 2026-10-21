import SwiftUI

@main
struct MeelanoVPNApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                // Persian-first, right-to-left - mirrors the Android UI's reading order.
                .environment(\.layoutDirection, .rightToLeft)
                .environment(\.colorScheme, model.themeScheme)
                .onAppear { model.onAppear() }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refreshStatus() }
        }
    }
}
