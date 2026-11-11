import Foundation
import Combine
import CoreLocation

enum ExportKind {
    case csv
    case gpxWithElevation
    case gpxWithoutElevation
    case geoJSON
    case jsonLines

    var fileExtension: String {
        switch self {
        case .csv: return "csv"
        case .gpxWithElevation, .gpxWithoutElevation: return "gpx"
        case .geoJSON: return "geojson"
        case .jsonLines: return "jsonl"
        }
    }

    var fileStem: String {
        switch self {
        case .csv: return "csv"
        case .gpxWithElevation: return "gpx"
        case .gpxWithoutElevation: return "gpx-tanpa-elevasi"
        case .geoJSON: return "geojson"
        case .jsonLines: return "jsonl"
        }
    }

    var title: String {
        switch self {
        case .csv: return "CSV lengkap (titik + kehadiran BLE)"
        case .gpxWithElevation: return "GPX jalur dengan elevasi"
        case .gpxWithoutElevation: return "GPX jalur tanpa elevasi"
        case .geoJSON: return "GeoJSON (peta, MongoDB)"
        case .jsonLines: return "JSON Lines (impor MongoDB)"
        }
    }
}

/// Keadaan aplikasi. Semua data berada pada SQLite lokal di folder dokumen aplikasi.
final class AppModel: ObservableObject {
    let database: Database
    let recorder = LocationRecorder()
    let ble = BlePresence()

    @Published private(set) var survey: Survey?
    @Published private(set) var points: [TrackPoint] = []
    @Published private(set) var presence: [Presence] = []
    @Published private(set) var referenceTrack: ReferenceTrack?
    @Published private(set) var referencePoints: [ReferencePoint] = []
    @Published var pendingReferenceImport: PendingReferenceImport?
    @Published var isRecording = false
    @Published var statusText = "Siap merekam"
    @Published var exportItem: ExportItem?
    @Published var syncEndpoint = UserDefaults.standard.string(forKey: MongoSync.endpointKey) ?? ""
    @Published var syncToken = SyncTokenStore.load()
    @Published var syncStatus: String?
    @Published var isSyncing = false
    @Published var alertMessage: String?
    @Published var update: IosUpdateInfo?
    @Published var updateNote: String?
    @Published var isCheckingUpdate = false

    private var bag = Set<AnyCancellable>()
    private var lastPointTime: Int64 = 0
    private var lastAutoCheck: Date?

    private let autoCheckInterval: TimeInterval = 6 * 60 * 60

    /// Titik dengan akurasi horizontal di atas nilai ini tidak disimpan.
    static let maxHorizontalAccuracyMeters = 30.0

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        database = Database(path: documents.appendingPathComponent("lereng.sqlite").path)

        let surveyId = database.ensureDefaultSurvey()
        survey = database.survey(surveyId)
        refresh()

        recorder.onFix = { [weak self] location in self?.handle(location) }
        ble.onPeer = { [weak self] peer in self?.recordPeer(peer) }

        // Objek bersarang ikut memicu render ulang tampilan.
        recorder.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &bag)
        ble.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &bag)
    }

    // MARK: - Data

    var projectName: String { survey?.name ?? "Lereng Offline" }

    var identityReady: Bool {
        guard let survey else { return false }
        return BleGroupCodec.isValidGroupCode(survey.groupCode)
            && BleGroupCodec.isValidParticipantId(survey.participantId)
    }

    var summary: RouteSummary { RouteSummary.build(from: points) }

    var elapsedMilliseconds: Int64 {
        let track = points.filter { !$0.isWaypoint }
        guard let first = track.first, let last = track.last, last.time > first.time else { return 0 }
        return last.time - first.time
    }

    func refresh() {
        guard let survey else {
            points = []
            presence = []
            referenceTrack = nil
            referencePoints = []
            return
        }
        points = database.points(surveyId: survey.id)
        presence = database.presence(surveyId: survey.id)
        refreshReference()
    }

    private func refreshReference() {
        guard let survey else {
            referenceTrack = nil
            referencePoints = []
            return
        }
        let track = database.referenceTrack(surveyId: survey.id)
        referenceTrack = track
        referencePoints = track == nil ? [] : database.referencePoints(surveyId: survey.id)
    }

    // MARK: - Rekaman

    func startRecording() {
        guard survey != nil else { return }
        if identityReady, let survey {
            ble.start(groupCode: survey.groupCode, participantId: survey.participantId)
        }
        lastPointTime = points.last?.time ?? 0
        recorder.start()
        isRecording = true
        switch recorder.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            statusText = "Merekam; menunggu fix GNSS"
        case .notDetermined:
            statusText = "Menunggu izin lokasi"
        default:
            statusText = "Izin lokasi belum aktif; rekaman belum berjalan"
        }
    }

    func stopRecording() {
        recorder.stop()
        ble.stop()
        isRecording = false
        statusText = "Rekaman berhenti"
        refresh()
    }

    func requestLocationPermission() {
        recorder.requestPermission()
    }

    private func handle(_ location: CLLocation) {
        guard isRecording, let survey else { return }
        guard location.horizontalAccuracy >= 0 else { return }
        if location.horizontalAccuracy > Self.maxHorizontalAccuracyMeters {
            // Titik buruk tidak disimpan supaya jalur tidak melompat; fix berikutnya tetap dicoba.
            statusText = String(format: "Titik dilewati: akurasi ±%.0f m di atas batas %.0f m",
                                location.horizontalAccuracy, Self.maxHorizontalAccuracyMeters)
            return
        }
        let time = Int64(location.timestamp.timeIntervalSince1970 * 1000)
        guard time > lastPointTime else { return }
        lastPointTime = time

        let verticalAccuracy = location.verticalAccuracy >= 0 ? location.verticalAccuracy : nil
        let altitude = verticalAccuracy == nil ? nil : location.altitude

        let point = NewPoint(surveyId: survey.id,
                             time: time,
                             lat: location.coordinate.latitude,
                             lon: location.coordinate.longitude,
                             altitude: altitude,
                             horizontalAccuracy: location.horizontalAccuracy,
                             verticalAccuracy: verticalAccuracy,
                             pressure: nil,
                             baroRelativeAltitude: nil,
                             source: "iOS Core Location",
                             label: "",
                             participantId: survey.participantId,
                             participantAlias: survey.participantAlias,
                             groupCode: survey.groupCode,
                             isWaypoint: false)
        _ = database.insertPoint(point)
        refresh()
        statusText = "Merekam • \(points.filter { !$0.isWaypoint }.count) titik"
    }

    func addWaypoint(label: String) {
        guard let survey else { return }
        let cleanLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let latitude: Double
        let longitude: Double
        let altitude: Double?
        let time: Int64

        if let fix = recorder.lastLocation {
            latitude = fix.coordinate.latitude
            longitude = fix.coordinate.longitude
            altitude = fix.verticalAccuracy >= 0 ? fix.altitude : nil
            time = Int64(fix.timestamp.timeIntervalSince1970 * 1000)
        } else if let last = points.last(where: { !$0.isWaypoint && $0.hasValidCoordinate }) ?? points.last {
            latitude = last.lat
            longitude = last.lon
            altitude = last.altitude
            time = Int64(Date().timeIntervalSince1970 * 1000)
        } else {
            alertMessage = "Belum ada fix GPS. Waypoint membutuhkan posisi."
            return
        }

        let point = NewPoint(surveyId: survey.id,
                             time: time,
                             lat: latitude,
                             lon: longitude,
                             altitude: altitude,
                             horizontalAccuracy: recorder.lastLocation?.horizontalAccuracy,
                             verticalAccuracy: recorder.lastLocation?.verticalAccuracy,
                             pressure: nil,
                             baroRelativeAltitude: nil,
                             source: "iOS Core Location",
                             label: cleanLabel.isEmpty ? "Waypoint" : cleanLabel,
                             participantId: survey.participantId,
                             participantAlias: survey.participantAlias,
                             groupCode: survey.groupCode,
                             isWaypoint: true)
        _ = database.insertPoint(point)
        refresh()
    }

    private func recordPeer(_ peer: String) {
        guard isRecording, let survey, !survey.participantId.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        _ = database.insertPresence(surveyId: survey.id,
                                    time: now,
                                    observer: survey.participantId.uppercased(),
                                    seen: peer,
                                    groupCode: survey.groupCode)
        presence = database.presence(surveyId: survey.id)
    }

    // MARK: - Identitas outing

    /// Mengembalikan pesan galat, atau `nil` bila berhasil.
    func saveIdentity(groupCode: String, participantId: String, alias: String) -> String? {
        guard var survey else { return "Proyek tidak ditemukan." }
        let code = BleGroupCodec.normalizeGroupCode(groupCode)
        var identifier = participantId.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let cleanAlias = alias.trimmingCharacters(in: .whitespacesAndNewlines)

        if !code.isEmpty, !BleGroupCodec.isValidGroupCode(code) {
            return "Kode outing harus 8–16 karakter A–Z atau 0–9."
        }
        if !identifier.isEmpty, !BleGroupCodec.isValidParticipantId(identifier) {
            return "ID peserta harus 8 digit heksadesimal, contoh 1A2B3C4D."
        }
        if !code.isEmpty, identifier.isEmpty {
            identifier = BleGroupCodec.newParticipantId()
        }

        database.updateIdentity(survey.id, groupCode: code, participantId: identifier, alias: cleanAlias)
        survey.groupCode = code
        survey.participantId = identifier
        survey.participantAlias = cleanAlias
        self.survey = survey

        ble.stop()
        if isRecording, !code.isEmpty, !identifier.isEmpty {
            ble.start(groupCode: code, participantId: identifier)
        }
        return nil
    }

    func renameProject(_ name: String) {
        guard var survey else { return }
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        database.renameSurvey(survey.id, name: clean)
        survey.name = clean
        self.survey = survey
    }

    // MARK: - Hapus data

    func deletePoint(_ point: TrackPoint) {
        database.deletePoint(point.id)
        refresh()
    }

    func deleteAllPoints() {
        guard let survey else { return }
        database.deletePoints(surveyId: survey.id)
        database.deletePresence(surveyId: survey.id)
        lastPointTime = 0
        refresh()
    }

    // MARK: - Jalur pembanding

    /// Membaca GPX pihak lain (mis. ekspor Strava) tanpa OAuth dan tanpa jaringan.
    /// Hasilnya belum disimpan; pengguna mengonfirmasi lewat `confirmReferenceImport()`.
    func importReferenceGPX(from url: URL) {
        do {
            let result = try GpxReferenceParser.parse(url: url)
            guard !result.points.isEmpty else {
                alertMessage = "GPX terbaca, tetapi tidak ada <trkpt> dengan koordinat valid."
                    + (result.skipped > 0 ? "\n\n\(result.skipped) baris dilewati karena koordinat tidak valid." : "")
                return
            }
            pendingReferenceImport = PendingReferenceImport(
                name: url.deletingPathExtension().lastPathComponent,
                source: result.creator,
                points: result.points,
                skipped: result.skipped)
        } catch {
            alertMessage = "Impor GPX pembanding gagal. Pilih file GPX 1.1 yang valid (akar <gpx>). "
                + "Tidak ada data yang diubah.\n\n\(error.localizedDescription)"
        }
    }

    func confirmReferenceImport() {
        guard let pending = pendingReferenceImport, let survey else {
            pendingReferenceImport = nil
            return
        }
        let id = database.replaceReferenceTrack(surveyId: survey.id,
                                                name: pending.name,
                                                source: pending.source,
                                                points: pending.points)
        pendingReferenceImport = nil
        guard id >= 0 else {
            alertMessage = "Jalur pembanding gagal disimpan."
            return
        }
        refreshReference()
        alertMessage = "Jalur pembanding tersimpan (\(pending.points.count) titik)."
    }

    func cancelReferenceImport() {
        pendingReferenceImport = nil
    }

    func deleteReferenceTrack() {
        guard let survey else { return }
        database.deleteReferenceTrack(surveyId: survey.id)
        refreshReference()
    }

    // MARK: - Ekspor

    func export(_ kind: ExportKind) {
        guard let survey else { return }
        let context = ExportContext(project: survey.name,
                                    points: points,
                                    presence: presence,
                                    groupCode: survey.groupCode,
                                    participantId: survey.participantId,
                                    participantAlias: survey.participantAlias,
                                    appVersion: AppConfig.version)
        let payload: String
        switch kind {
        case .csv: payload = Exporters.csv(context)
        case .gpxWithElevation: payload = Exporters.gpx(context, includeElevation: true)
        case .gpxWithoutElevation: payload = Exporters.gpx(context, includeElevation: false)
        case .geoJSON: payload = Exporters.geoJSON(context)
        case .jsonLines: payload = Exporters.jsonLines(context)
        }

        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let folder = documents.appendingPathComponent("exports", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            alertMessage = "Gagal menyiapkan folder ekspor: \(error.localizedDescription)"
            return
        }

        let stamp = Exporters.isoNow()
            .replacingOccurrences(of: ":", with: "-")
            .replacingOccurrences(of: ".", with: "-")
        let slug = Self.slug(survey.name)
        let url = folder.appendingPathComponent("lereng-\(slug)-\(stamp).\(kind.fileExtension)")

        do {
            try payload.write(to: url, atomically: true, encoding: .utf8)
            exportItem = ExportItem(url: url, title: kind.title)
        } catch {
            alertMessage = "Gagal menulis berkas: \(error.localizedDescription)"
        }
    }

    private static func slug(_ name: String) -> String {
        let allowed = CharacterSet.alphanumerics
        let lowered = name.lowercased().unicodeScalars.map { scalar -> Character in
            allowed.contains(scalar) ? Character(scalar) : "-"
        }
        let text = String(lowered)
        let collapsed = text.split(separator: "-").joined(separator: "-")
        let trimmed = collapsed.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return trimmed.isEmpty ? "proyek" : String(trimmed.prefix(40))
    }

    // MARK: - Kirim ke MongoDB

    var syncConfigured: Bool {
        !syncEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !syncToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Menyimpan alamat dan token secara lokal. Token masuk Keychain.
    @discardableResult
    func saveSyncSettings() -> Bool {
        do {
            let url = try MongoSync.normalizeEndpoint(syncEndpoint)
            let token = syncToken.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !token.isEmpty else { throw MongoSync.SyncError.missingToken }
            UserDefaults.standard.set(url.absoluteString, forKey: MongoSync.endpointKey)
            SyncTokenStore.save(token)
            syncEndpoint = url.absoluteString
            syncToken = token
            syncStatus = "Pengaturan tersimpan di perangkat ini."
            return true
        } catch {
            syncStatus = error.localizedDescription
            return false
        }
    }

    func clearSyncSettings() {
        UserDefaults.standard.removeObject(forKey: MongoSync.endpointKey)
        SyncTokenStore.clear()
        syncEndpoint = ""
        syncToken = ""
        syncStatus = "Alamat dan token dihapus dari perangkat."
    }

    /// Hanya berjalan saat pengguna menekan tombol dan menyetujui konfirmasi.
    func sendToMongo() {
        guard let survey, !isSyncing else { return }
        guard saveSyncSettings() else { return }
        let config: MongoSync.Config
        do {
            config = MongoSync.Config(endpoint: try MongoSync.normalizeEndpoint(syncEndpoint),
                                      token: syncToken)
        } catch {
            syncStatus = error.localizedDescription
            return
        }

        let context = ExportContext(project: survey.name,
                                    points: points,
                                    presence: presence,
                                    groupCode: survey.groupCode,
                                    participantId: survey.participantId,
                                    participantAlias: survey.participantAlias,
                                    appVersion: AppConfig.version)
        let documents = MongoSync.documents(clientId: MongoSync.clientId(),
                                            surveyId: survey.id,
                                            context: context,
                                            platform: "ios")
        guard !documents.isEmpty else {
            syncStatus = "Tidak ada data yang bisa dikirim."
            return
        }

        isSyncing = true
        syncStatus = "Menyiapkan \(documents.count) data…"
        Task {
            do {
                let total = try await MongoSync.send(config: config,
                                                     surveyId: survey.id,
                                                     project: survey.name,
                                                     appVersion: AppConfig.version,
                                                     documents: documents) { sent, total in
                    DispatchQueue.main.async { [weak self] in
                        self?.syncStatus = "Mengirim \(sent) dari \(total) data…"
                    }
                }
                await MainActor.run {
                    self.isSyncing = false
                    self.syncStatus = "Terkirim \(total) data ke MongoDB. Tekan lagi setelah merekam titik baru."
                }
            } catch {
                await MainActor.run {
                    self.isSyncing = false
                    self.syncStatus = error.localizedDescription
                }
            }
        }
    }

    // MARK: - Pembaruan

    func checkForUpdate(manual: Bool = false) {
        if !manual, let last = lastAutoCheck, Date().timeIntervalSince(last) < autoCheckInterval {
            return
        }
        guard let url = AppConfig.updateManifestURL else {
            if manual { updateNote = UpdateError.disabled.message }
            return
        }
        isCheckingUpdate = true
        if manual { updateNote = nil }

        UpdateChecker.check(manifestURL: url, currentBuild: AppConfig.buildNumber) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isCheckingUpdate = false
                self.lastAutoCheck = Date()
                switch result {
                case .success(let info):
                    self.update = info
                    if manual, info == nil {
                        self.updateNote = "Aplikasi sudah memakai versi terbaru (build \(AppConfig.buildNumber))."
                    }
                case .failure(let error):
                    // Fail-open: kegagalan pemeriksaan tidak menghalangi pemakaian.
                    if manual { self.updateNote = error.message }
                }
            }
        }
    }
}
