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
    @Published var isRecording = false
    @Published var statusText = "Siap merekam"
    @Published var exportItem: ExportItem?
    @Published var alertMessage: String?
    @Published var update: IosUpdateInfo?
    @Published var updateNote: String?
    @Published var isCheckingUpdate = false

    private var bag = Set<AnyCancellable>()
    private var lastPointTime: Int64 = 0
    private var lastAutoCheck: Date?

    private let autoCheckInterval: TimeInterval = 6 * 60 * 60

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
            return
        }
        points = database.points(surveyId: survey.id)
        presence = database.presence(surveyId: survey.id)
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
        guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100 else { return }
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
