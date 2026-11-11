import UIKit
import UserNotifications
import BackgroundTasks
import FirebaseCore
import ComposeApp

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()
        CrashlyticsKt.installCrashlytics()
        AnalyticsBridgeKt.installAnalytics(analytics: PostHogBridge())
        application.registerForRemoteNotifications()
        // must be set before launch finishes, or a tap that launched the app is lost
        UNUserNotificationCenter.current().delegate = self
        registerBackgroundRefresh()
        return true
    }

    // without this, iOS doesn't show reminders while the app is in the foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        NotificationResponsesKt.onNotificationResponse(
            identifier: response.notification.request.identifier,
            actionIdentifier: response.actionIdentifier,
            completion: completionHandler
        )
    }

    // Must run before launch finishes. Main queue, like the APNs callbacks, since Kotlin startup touches UIKit.
    private func registerBackgroundRefresh() {
        guard let identifier = BackgroundRefreshKt.backgroundRefreshTaskId else { return }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            let cancel = BackgroundRefreshKt.onBackgroundRefresh { finished in
                task.setTaskCompleted(success: finished.boolValue)
            }
            task.expirationHandler = { cancel() }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        ApnsPushKt.onApnsToken(token: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NSLog("APNs registration failed: \(error.localizedDescription)")
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        ApnsPushKt.onApnsPush { finished in
            completionHandler(finished.boolValue ? .newData : .failed)
        }
    }
}
