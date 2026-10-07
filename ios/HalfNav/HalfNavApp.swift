import SwiftUI
import UserNotifications
import HalfNavCore

@main
struct HalfNavApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model = AppModel()
    @Environment(\.colorScheme) private var systemScheme
    @State private var now = Date()
    private let minuteTick = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some Scene {
        WindowGroup {
            let dark = isDark
            ContentView(model: model, dark: dark)
                .preferredColorScheme(dark ? .dark : .light)
                .background(Color(dark ? windowDark : windowLight).ignoresSafeArea())
                .onReceive(minuteTick) { now = $0 }
                .onChange(of: dark, initial: true) { _, d in model.settings.lastDark = d }
        }
    }

    /// Auto: dark from official sunset to sunrise where you are (shared core's SunTimes). Until the
    /// location is known, keep whatever the app last showed, so launching doesn't flip themes.
    private var isDark: Bool {
        switch model.settings.theme {
        case .light: return false
        case .dark: return true
        case .auto:
            if let here = model.location.here {
                return SunTimes.shared.isDarkAt(epochMillis: Int64(now.timeIntervalSince1970 * 1000),
                                                lat: here.lat, lng: here.lng, zoneId: TimeZone.current.identifier)
            }
            return model.settings.lastDark ?? (systemScheme == .dark)
        }
    }
}

/// Opens Google Maps (or Apple Maps) when you tap the "Almost there" notification.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        if let lat = info["lat"] as? Double, let lng = info["lng"] as? Double {
            await MainActor.run { MapsLauncher.navigate(to: LatLng(lat: lat, lng: lng)) }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
