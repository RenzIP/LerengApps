import Foundation
import CryptoKit
import Security

/// Sinkron otomatis ke endpoint HTTPS milik pengguna.
///
/// Aplikasi tidak pernah menyimpan URI MongoDB. Yang disimpan hanya alamat
/// endpoint (UserDefaults) dan token (Keychain). Server yang memegang
/// kredensial basis data. Sinkron berjalan sendiri saat ada data baru, saat
/// aplikasi aktif, dan lewat BGAppRefreshTask bila sistem mengizinkan.
/// Tidak ada tombol kirim.
///
/// Pengiriman bersifat inkremental: tanda air per proyek menyimpan ID lokal
/// terakhir yang sudah terkirim, sehingga titik lama tidak dikirim ulang.
/// Setiap dokumen punya `record_id` stabil, jadi pengiriman ulang aman.
enum MongoSync {
    static let endpointKey = "lereng.sync.endpoint"
    static let autoKey = "lereng.sync.auto"
    private static let clientIdKey = "lereng.sync.clientId"
    static let chunkSize = 300

    struct Config {
        let endpoint: URL
        let token: String
    }

    struct Summary {
        let sent: Int
        let projects: Int
    }

    enum SyncError: LocalizedError {
        case missingEndpoint
        case invalidEndpoint
        case insecureEndpoint
        case missingToken
        case emptyPayload
        case server(Int, String)
        case invalidResponse

        var errorDescription: String? {
            switch self {
            case .missingEndpoint: return "Endpoint belum diisi."
            case .invalidEndpoint: return "Alamat endpoint tidak valid."
            case .insecureEndpoint: return "Endpoint harus memakai HTTPS."
            case .missingToken: return "Token belum diisi."
            case .emptyPayload: return "Tidak ada data yang bisa dikirim."
            case .server(let code, let detail):
                return detail.isEmpty ? "Server menolak (\(code))." : "Server menolak (\(code)): \(detail)"
            case .invalidResponse: return "Balasan server tidak dikenali."
            }
        }
    }

    // MARK: - Pengaturan

    /// ID pemasangan yang stabil; dibuat sekali dan tidak dikirim ke pihak lain.
    static func clientId() -> String {
        let defaults = UserDefaults.standard
        if let value = defaults.string(forKey: clientIdKey), !value.isEmpty { return value }
        let value = UUID().uuidString
        defaults.set(value, forKey: clientIdKey)
        return value
    }

    static func normalizeEndpoint(_ raw: String) throws -> URL {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { throw SyncError.missingEndpoint }
        guard var components = URLComponents(string: value) else { throw SyncError.invalidEndpoint }
        guard components.scheme?.lowercased() == "https" else { throw SyncError.insecureEndpoint }
        guard let host = components.host, !host.isEmpty else { throw SyncError.invalidEndpoint }
        guard components.user == nil, components.password == nil,
              components.fragment == nil, components.query == nil else {
            throw SyncError.invalidEndpoint
        }
        if components.path.isEmpty || components.path == "/" {
            components.path = "/api/ingest"
        } else if components.path.hasSuffix("/") {
            components.path = String(components.path.dropLast())
        }
        guard let url = components.url else { throw SyncError.invalidEndpoint }
        return url
    }

    /// Default aktif; tanpa endpoint/token, sinkron tidak melakukan apa pun.
    static func isAutoEnabled() -> Bool {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: autoKey) == nil { return true }
        return defaults.bool(forKey: autoKey)
    }

    static func setAutoEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: autoKey)
    }

    /// Konfigurasi siap pakai, atau `nil` bila alamat/token belum lengkap.
    static func currentConfig() -> Config? {
        let rawEndpoint = UserDefaults.standard.string(forKey: endpointKey) ?? ""
        let token = SyncTokenStore.load().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty,
              let endpoint = try? normalizeEndpoint(rawEndpoint) else { return nil }
        return Config(endpoint: endpoint, token: token)
    }

    /// Penanda endpoint agar tanda air tidak tercampur bila alamat diganti.
    private static func targetFingerprint(_ endpoint: URL) -> String {
        let digest = SHA256.hash(data: Data(endpoint.absoluteString.utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    private static func pointKey(_ target: String, _ surveyId: Int64) -> String {
        "lereng.sync.wp.\(target).\(surveyId)"
    }

    private static func presenceKey(_ target: String, _ surveyId: Int64) -> String {
        "lereng.sync.wr.\(target).\(surveyId)"
    }

    private static func projectKey(_ target: String, _ surveyId: Int64) -> String {
        "lereng.sync.pj.\(target).\(surveyId)"
    }

    // MARK: - Dokumen

    /// Satu dokumen per titik track, waypoint, event presence, dan metadata
    /// proyek. Jalur pembanding GPX sengaja tidak pernah masuk ke sini.
    static func documents(clientId: String, surveyId: Int64, context: ExportContext,
                          platform: String, includeProject: Bool = true) -> [[String: Any]] {
        var documents: [[String: Any]] = []
        let base = "\(clientId):\(surveyId)"

        if includeProject {
            var projectProperties: [String: Any] = [
                "schema": "lereng.mongo/1",
                "record_id": "\(base):project",
                "record_type": "project",
                "project": context.project,
                "group_code": context.groupCode,
                "participant_id": context.participantId,
                "participant_alias": context.participantAlias,
                "platform": platform,
                "app_version": context.appVersion
            ]
            if projectProperties["group_code"] as? String == "" {
                projectProperties.removeValue(forKey: "group_code")
            }
            documents.append(feature(geometry: NSNull(), properties: projectProperties))
        }

        for point in context.points where point.hasValidCoordinate {
            var coordinates = [point.lon, point.lat]
            if let altitude = point.altitude { coordinates.append(altitude) }
            var properties: [String: Any] = [
                "schema": "lereng.mongo/1",
                "record_id": "\(base):\(point.isWaypoint ? "waypoint" : "track"):\(point.id)",
                "record_type": point.isWaypoint ? "waypoint" : "track",
                "waypoint": point.isWaypoint,
                "project": context.project,
                "group_code": point.groupCode,
                "participant_id": point.participantId,
                "participant_alias": point.participantAlias,
                "time_iso": Exporters.iso(point.time),
                "time_ms": point.time,
                "source": point.source,
                "label": point.label,
                "platform": platform
            ]
            if let altitude = point.altitude {
                properties["elevation_m"] = altitude
                properties["elevation_reference"] = point.elevationReference
            }
            if let value = point.horizontalAccuracy { properties["horizontal_accuracy_m"] = value }
            if let value = point.verticalAccuracy { properties["vertical_accuracy_m"] = value }
            if let value = point.pressure { properties["pressure_hpa"] = value }
            if let value = point.baroRelativeAltitude { properties["barometer_relative_elevation_m"] = value }
            documents.append(feature(geometry: ["type": "Point", "coordinates": coordinates],
                                     properties: properties))
        }

        for event in context.presence {
            let tuple = "\(event.time)|\(event.observerParticipantId)|\(event.seenParticipantId)"
            let alias = event.observerParticipantId == context.participantId ? context.participantAlias : ""
            let properties: [String: Any] = [
                "schema": "lereng.mongo/1",
                "record_id": "\(base):presence:\(shortHash(tuple))",
                "record_type": "presence",
                "project": context.project,
                "group_code": event.groupCode,
                "participant_id": event.observerParticipantId,
                "participant_alias": alias,
                "seen_participant_id": event.seenParticipantId,
                "time_iso": Exporters.iso(event.time),
                "time_ms": event.time,
                "source": "BLE",
                "label": "presence ID + timestamp",
                "platform": platform
            ]
            documents.append(feature(geometry: NSNull(), properties: properties))
        }
        return documents
    }

    private static func feature(geometry: Any, properties: [String: Any]) -> [String: Any] {
        ["type": "Feature", "geometry": geometry, "properties": properties]
    }

    private static func shortHash(_ value: String) -> String {
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Sinkron

    /// Sinkron semua proyek secara inkremental. Hanya record di atas tanda air
    /// yang dikirim; metadata proyek dikirim ulang hanya bila berubah.
    ///
    /// Dijalankan di main actor karena `Database` memakai satu koneksi SQLite;
    /// operasi jaringan tetap asinkron sehingga UI tidak tertahan.
    @MainActor
    static func syncAll(database: Database, appVersion: String) async throws -> Summary {
        guard isAutoEnabled(), let config = currentConfig() else {
            return Summary(sent: 0, projects: 0)
        }
        let defaults = UserDefaults.standard
        let target = targetFingerprint(config.endpoint)
        let client = clientId()
        var sent = 0
        var projects = 0

        for survey in database.surveys() {
            let pointStorageKey = pointKey(target, survey.id)
            let presenceStorageKey = presenceKey(target, survey.id)
            let projectStorageKey = projectKey(target, survey.id)
            var pointMark = Int64(defaults.integer(forKey: pointStorageKey))
            var presenceMark = Int64(defaults.integer(forKey: presenceStorageKey))
            let maxPoint = database.maxPointId(surveyId: survey.id)
            let maxPresence = database.maxPresenceId(surveyId: survey.id)
            // Database dibuat ulang: ID kembali kecil, jadi mulai dari nol.
            if maxPoint > 0 && maxPoint < pointMark { pointMark = 0 }
            if maxPresence > 0 && maxPresence < presenceMark { presenceMark = 0 }

            let newPoints = database.points(surveyId: survey.id, after: pointMark)
            let newPresence = database.presence(surveyId: survey.id, after: presenceMark)
            let signature = [survey.name, survey.groupCode, survey.participantId,
                             survey.participantAlias].joined(separator: "\u{0}")
            let projectChanged = signature != defaults.string(forKey: projectStorageKey)
            if !projectChanged && newPoints.isEmpty && newPresence.isEmpty { continue }

            let context = ExportContext(project: survey.name,
                                        points: newPoints,
                                        presence: newPresence,
                                        groupCode: survey.groupCode,
                                        participantId: survey.participantId,
                                        participantAlias: survey.participantAlias,
                                        appVersion: appVersion)
            let payload = documents(clientId: client, surveyId: survey.id, context: context,
                                    platform: "ios", includeProject: projectChanged)
            guard !payload.isEmpty else { continue }

            _ = try await send(config: config, surveyId: survey.id, project: survey.name,
                               appVersion: appVersion, documents: payload) { _, _ in }

            let newPointMark = max(pointMark, newPoints.map(\.id).max() ?? pointMark)
            let newPresenceMark = max(presenceMark, newPresence.map(\.id).max() ?? presenceMark)
            defaults.set(Int(newPointMark), forKey: pointStorageKey)
            defaults.set(Int(newPresenceMark), forKey: presenceStorageKey)
            defaults.set(signature, forKey: projectStorageKey)
            sent += payload.count
            projects += 1
        }
        return Summary(sent: sent, projects: projects)
    }

    // MARK: - Kirim

    /// Mengirim dokumen per batch. `progress` dipanggil setelah setiap batch.
    static func send(config: Config, surveyId: Int64, project: String, appVersion: String,
                     documents: [[String: Any]],
                     progress: @escaping (Int, Int) -> Void) async throws -> Int {
        guard !documents.isEmpty else { throw SyncError.emptyPayload }
        let total = documents.count
        var sent = 0
        var start = 0

        while start < total {
            let end = min(start + chunkSize, total)
            let chunk = Array(documents[start..<end])
            let body: [String: Any] = [
                "schema": "lereng.mongo/1",
                "client_id": clientId(),
                "platform": "ios",
                "app_version": appVersion,
                "project": project,
                "survey_id": surveyId,
                "sent_at": Exporters.isoNow(),
                "features": chunk
            ]
            try await post(config: config, body: body)
            sent = end
            progress(sent, total)
            start = end
        }
        return sent
    }

    private static func post(config: Config, body: [String: Any]) async throws {
        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 45
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization")
        request.setValue("LerengOffline-iOS", forHTTPHeaderField: "User-Agent")
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SyncError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw SyncError.server(http.statusCode, errorDetail(data))
        }
    }

    private static func errorDetail(_ data: Data) -> String {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let text = String(data: data, encoding: .utf8) ?? ""
            return String(text.prefix(200))
        }
        let error = object["error"] as? String ?? ""
        let detail = object["detail"] as? String ?? ""
        let text = detail.isEmpty ? error : "\(error): \(detail)"
        return String(text.prefix(200))
    }
}

/// Token disimpan di Keychain, bukan UserDefaults.
enum SyncTokenStore {
    private static let service = "id.bagas.lerengoffline.sync"
    private static let account = "ingest-token"

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    static func load() -> String {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else { return "" }
        return value
    }

    static func save(_ token: String) {
        let data = Data(token.utf8)
        SecItemDelete(baseQuery as CFDictionary)
        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    static func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}
