import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                // backups opened from Files, the share sheet or AirDrop, on cold and warm launch
                .onOpenURL { url in OpenBackupFileKt.openBackupFile(url: url) }
        }
        .onChange(of: scenePhase) { phase in
            if #available(iOS 27.0, *), phase == .active || phase == .background {
                _Concurrency.Task { try? await SpotlightIndex.reindexAll() }
            }
        }
    }
}
