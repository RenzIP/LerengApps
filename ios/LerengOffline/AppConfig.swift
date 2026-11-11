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

    /// Endpoint dan kunci aplikasi yang ditanam saat build, supaya pengguna
    /// tidak perlu mengisi apa pun. Nilainya ikut di dalam IPA, jadi BUKAN
    /// rahasia; server yang memegang kredensial dan melakukan validasi serta
    /// pembatasan laju.
    static var defaultSyncEndpoint: String {
        (Bundle.main.object(forInfoDictionaryKey: "LerengSyncEndpoint") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static var defaultSyncToken: String {
        (Bundle.main.object(forInfoDictionaryKey: "LerengSyncToken") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
