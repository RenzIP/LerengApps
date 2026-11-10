import Foundation

/// Konfigurasi yang dibekukan saat build.
enum AppConfig {
    /// Manifes pembaruan iOS. Terpisah dari manifes Android karena iOS tidak dapat memasang
    /// aplikasi sendiri: manifes hanya menautkan ke App Store atau TestFlight.
    static let updateManifestURL = URL(string: "https://raw.githubusercontent.com/RenzIP/LerengApps/main/updates/ios-update.json")

    static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    static var buildNumber: Int {
        let raw = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return Int(raw) ?? 1
    }
}
