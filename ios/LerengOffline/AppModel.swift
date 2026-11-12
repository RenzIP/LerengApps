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

/// GPX yang sudah dibaca tetapi belum ditulis ke proyek. Pengguna memilih
/// referensi elevasi lebih dulu; tidak ada konversi nilai.
struct PendingTrackImport: Identifiable {
    let id = UUID()
    let name: String
    let creator: String
    let declaredReference: String
    let points: [GpxTrackParser.ParsedPoint]
    let skipped: Int

    var trackCount: Int { points.filter { !$0.isWaypoint }.count }
    var waypointCount: Int { points.filter { $0.isWaypoint }.count }
    var hasElevation: Bool { points.contains { $0.elevation != nil } }
    var hasTime: Bool { points.contains { $0.time != nil } }

    var summary: String {
        var text = "\(trackCount) titik track dan \(waypointCount) waypoint dibaca"
        if !creator.isEmpty { text += " (dibuat oleh \(creator))" }
        text += "."
        if skipped > 0 {
            text += " \(skipped) baris dilewati karena koordinat tidak valid."
        }
        if !hasTime {
            text += " File tanpa waktu: urutan file dipakai sebagai waktu relatif."
        }
        text += "\n\nPilih referensi elevasi file ini. Aplikasi tidak mengonversi elevasi; "
        text += "label hanya dicatat agar profil dan terrain tidak mencampur datum."
        if !declaredReference.isEmpty,
           declaredReference.lowercased() != ImportDatum.unrecorded {
            text += "\n\nFile mencantumkan referensi: “\(declaredReference)”. Verifikasi sebelum memilih."
        }
        text += "\n\nDuplikat (waktu + koordinat sama) diabaikan."
        return text
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
    @Published private(set) var trackPointCount = 0
    @Published private(set) var networkState: NetworkMonitor.State = .unknown
    @Published var pendingReferenceImport: PendingReferenceImport?
    @Published var pendingTrackImport: PendingTrackImport?
    @Published var isRecording = false
    @Published var isStarting = false
    @Published var isImporting = false
    @Published var isExporting = false
    @Published var statusText = "Siap merekam"
    @Published var exportItem: ExportItem?
    @Published var syncEndpoint = MongoSync.effectiveEndpointString()
    @Published var syncToken = MongoSync.effectiveToken()
    @Published var autoSyncEnabled = MongoSync.isAutoEnabled()
    @Published var syncStatus: String?
    @Published var isSyncing = false
    @Published var lastSyncAt: Date?
    @Published var alertMessage: String?
    @Published var update: IosUpdateInfo?
    @Published var updateNote: String?
    @Published var isCheckingUpdate = false

    private var bag = Set<AnyCancellable>()
    private var lastPointTime: Int64 = 0
    private var lastFixAt: Date?
    private var watchdog: Timer?
    private var lastAutoCheck: Date?
    private var scheduledSync: DispatchWorkItem?
    private var syncTask: Task<Void, Never>?

    private let network = NetworkMonitor()
    /// Pembacaan data layar berjalan di sini supaya perekaman tidak tersendat.
    private let snapshotQueue = DispatchQueue(label: "id.bagas.lerengoffline.snapshot",
                                              qos: .userInitiated)
    /// Ekspor dan impor berkas berjalan di sini agar layar tidak membeku.
    private let ioQueue = DispatchQueue(label: "id.bagas.lerengoffline.io",
                                        qos: .userInitiated)
    private var refreshWork: DispatchWorkItem?
    private var snapshotGeneration: UInt64 = 0
    /// Jeda singkat untuk menggabungkan beberapa permintaan beruntun.
    private static let refreshDebounce: TimeInterval = 0.12

    private let autoCheckInterval: TimeInterval = 6 * 60 * 60

    /// Titik dengan akurasi horizontal di atas nilai ini tidak disimpan.
    static let maxHorizontalAccuracyMeters = 30.0
    /// Peringatan "tidak ada fix" muncul setelah selama ini tanpa titik baru.
    private static let fixStallSeconds: TimeInterval = 120

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        database = Database(path: documents.appendingPathComponent("lereng.sqlite").path)

        let surveyId = database.ensureDefaultSurvey()
        survey = database.survey(surveyId)
        trackPointCount = database.points(surveyId: surveyId).filter { !$0.isWaypoint }.count
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
        recorder.$authorizationStatus
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in self?.handleAuthorization(status) }
            .store(in: &bag)

        network.onChange = { [weak self] state in self?.networkState = state }
        network.start()
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

    /// Meminta penyegaran layar. Pembacaan basis data berjalan di antrean latar,
    /// lalu hasilnya dipasang di main thread. Permintaan beruntun digabung.
    func refresh() {
        guard survey != nil else {
            points = []
            presence = []
            referenceTrack = nil
            referencePoints = []
            trackPointCount = 0
            return
        }
        refreshWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.readSnapshot() }
        refreshWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.refreshDebounce, execute: work)
    }

    private func readSnapshot() {
        guard let survey else { return }
        let surveyId = survey.id
        snapshotGeneration &+= 1
        let generation = snapshotGeneration

        snapshotQueue.async { [weak self] in
            guard let self else { return }
            let loadedPoints = self.database.points(surveyId: surveyId)
            let loadedPresence = self.database.presence(surveyId: surveyId)
            let track = self.database.referenceTrack(surveyId: surveyId)
            let loadedReference = track == nil
                ? [] : self.database.referencePoints(surveyId: surveyId)
            DispatchQueue.main.async {
                guard generation == self.snapshotGeneration,
                      self.survey?.id == surveyId else { return }
                self.points = loadedPoints
                self.presence = loadedPresence
                self.referenceTrack = track
                self.referencePoints = loadedReference
                self.trackPointCount = loadedPoints.filter { !$0.isWaypoint }.count
            }
        }
    }

    private func refreshReference() {
        refresh()
    }

    // MARK: - Rekaman

    func startRecording() {
        guard survey != nil, !isRecording, !isStarting else { return }
        if identityReady, let survey {
            ble.start(groupCode: survey.groupCode, participantId: survey.participantId)
        }
        lastPointTime = points.last?.time ?? 0
        trackPointCount = points.filter { !$0.isWaypoint }.count
        lastFixAt = Date()
        recorder.start()
        isRecording = true
        isStarting = !recorder.isRunning
        switch recorder.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            statusText = "Merekam; menunggu fix GNSS"
        case .notDetermined:
            statusText = "Menunggu izin lokasi"
        default:
            statusText = "Izin lokasi belum aktif; rekaman belum berjalan"
        }
        startWatchdog()
        guard isStarting else { return }
        // Jaring pengaman: jangan biarkan tombol terkunci bila sistem tidak
        // pernah menyalakan pembaruan lokasi.
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self, self.isStarting else { return }
            self.isStarting = false
            guard !self.recorder.isRunning else { return }
            switch self.recorder.authorizationStatus {
            case .denied, .restricted:
                self.isRecording = false
                self.stopWatchdog()
                self.statusText = "Perekaman tidak dapat dimulai: izin lokasi belum diberikan. "
                    + "Aktifkan di Pengaturan → Lereng Offline → Lokasi."
            case .notDetermined:
                self.statusText = "Menunggu izin lokasi. Rekaman berjalan setelah izin diberikan."
            default:
                self.statusText = "Perekaman belum aktif: menunggu sumber lokasi. "
                    + "Periksa izin lokasi presisi dan pastikan GPS aktif."
            }
        }
    }

    func stopRecording() {
        guard isRecording else { return }
        recorder.stop()
        ble.stop()
        isRecording = false
        isStarting = false
        stopWatchdog()
        statusText = "Rekaman berhenti. Data yang sudah tersimpan tetap ada di proyek ini."
        refresh()
        scheduleSync(delay: 1, force: true)
    }

    func requestLocationPermission() {
        recorder.requestPermission()
    }

    private func handleAuthorization(_ status: CLAuthorizationStatus) {
        switch status {
        case .authorizedAlways, .authorizedWhenInUse:
            if isRecording {
                recorder.start()
                if !isStarting {
                    statusText = "Merekam; menunggu fix GNSS"
                }
            }
        case .denied, .restricted:
            guard isRecording else { return }
            recorder.stop()
            ble.stop()
            isRecording = false
            isStarting = false
            stopWatchdog()
            statusText = "Izin lokasi ditolak. Rekaman belum berjalan; "
                + "aktifkan di Pengaturan → Lereng Offline → Lokasi."
        default:
            break
        }
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
        lastFixAt = Date()

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
        trackPointCount += 1
        statusText = "Merekam • \(trackPointCount) titik"
        refresh()
        scheduleSync()
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
        scheduleSync()
    }

    private func recordPeer(_ peer: String) {
        guard isRecording, let survey, !survey.participantId.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        _ = database.insertPresence(surveyId: survey.id,
                                    time: now,
                                    observer: survey.participantId.uppercased(),
                                    seen: peer,
                                    groupCode: survey.groupCode)
        refresh()
        scheduleSync()
    }

    // MARK: - Pengawas fix

    private func startWatchdog() {
        stopWatchdog()
        let timer = Timer(timeInterval: 30, repeats: true) { [weak self] _ in
            self?.checkFixWatchdog()
        }
        RunLoop.main.add(timer, forMode: .common)
        watchdog = timer
    }

    private func stopWatchdog() {
        watchdog?.invalidate()
        watchdog = nil
    }

    private func checkFixWatchdog() {
        guard isRecording else {
            stopWatchdog()
            return
        }
        let last = lastFixAt ?? Date()
        guard Date().timeIntervalSince(last) > Self.fixStallSeconds else { return }
        statusText = "Belum ada titik baru lebih dari 2 menit. GPS mungkin belum mendapat fix: "
            + "coba area terbuka, atau periksa apakah GPS aktif dan izin lokasi masih diberikan."
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
        scheduleSync()
        return nil
    }

    func renameProject(_ name: String) {
        guard var survey else { return }
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        database.renameSurvey(survey.id, name: clean)
        survey.name = clean
        self.survey = survey
        scheduleSync()
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
        trackPointCount = 0
        refresh()
    }

    // MARK: - Jalur pembanding

    /// Membaca GPX pihak lain (mis. ekspor Strava) tanpa OAuth dan tanpa jaringan.
    /// Hasilnya belum disimpan; pengguna mengonfirmasi lewat `confirmReferenceImport()`.
    func importReferenceGPX(from url: URL) {
        do {
            let result = try GpxReferenceParser.parse(url: url)
            guard !result.points.isEmpty else {
                alertMessage = "GPX terbaca, tetapi tidak ada <trkpt> atau <rtept> dengan koordinat valid."
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

    // MARK: - Impor ke proyek

    /// Membaca GPX rekaman di latar. Pengguna memilih referensi elevasi lebih
    /// dulu; tidak ada yang ditulis sebelum dikonfirmasi.
    func importTrackGPX(from url: URL) {
        guard survey != nil, !isImporting else { return }
        isImporting = true
        let fileName = url.deletingPathExtension().lastPathComponent
        ioQueue.async { [weak self] in
            guard let self else { return }
            do {
                let result = try GpxTrackParser.parse(url: url)
                DispatchQueue.main.async {
                    self.isImporting = false
                    guard !result.points.isEmpty else {
                        self.alertMessage = "GPX terbaca, tetapi tidak ada <trkpt>, <rtept>, atau <wpt> "
                            + "dengan koordinat valid."
                            + (result.skipped > 0
                               ? "\n\n\(result.skipped) baris dilewati karena koordinat tidak valid." : "")
                        return
                    }
                    self.pendingTrackImport = PendingTrackImport(
                        name: fileName,
                        creator: result.creator,
                        declaredReference: result.declaredReference,
                        points: result.points,
                        skipped: result.skipped)
                }
            } catch {
                DispatchQueue.main.async {
                    self.isImporting = false
                    self.alertMessage = "Impor GPX gagal. Pilih file GPX 1.1 yang valid (akar <gpx>). "
                        + "Tidak ada data yang diubah.\n\n\(error.localizedDescription)"
                }
            }
        }
    }

    func cancelTrackImport() {
        pendingTrackImport = nil
    }

    func confirmTrackImport(datum: String) {
        guard let pending = pendingTrackImport, let survey else {
            pendingTrackImport = nil
            return
        }
        pendingTrackImport = nil
        isImporting = true
        let identity = survey
        let importStart = Int64(Date().timeIntervalSince1970 * 1000)
        ioQueue.async { [weak self] in
            guard let self else { return }
            let message = GpxImporter.run(pending: pending, survey: identity, datum: datum,
                                          importStart: importStart, database: self.database)
            DispatchQueue.main.async {
                self.isImporting = false
                self.refresh()
                self.scheduleSync(delay: 1, force: true)
                self.alertMessage = message
            }
        }
    }

    /// Membaca CSV hasil ekspor Lereng Offline atau tabel latitude/longitude.
    func importCSV(from url: URL) {
        guard let survey, !isImporting else { return }
        isImporting = true
        let identity = survey
        let fileName = url.deletingPathExtension().lastPathComponent
        ioQueue.async { [weak self] in
            guard let self else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let message = try CsvImporter.run(data: data, fileName: fileName,
                                                  survey: identity, database: self.database)
                DispatchQueue.main.async {
                    self.isImporting = false
                    self.refresh()
                    self.scheduleSync(delay: 1, force: true)
                    self.alertMessage = message
                }
            } catch {
                DispatchQueue.main.async {
                    self.isImporting = false
                    self.alertMessage = "Impor CSV gagal. Pilih CSV hasil ekspor Lereng Offline "
                        + "atau tabel dengan latitude, longitude, elevation_m.\n\n"
                        + error.localizedDescription
                }
            }
        }
    }

    // MARK: - Ekspor

    /// Menyusun dan menulis berkas di antrean latar. Data yang diekspor
    /// diambil dari snapshot saat tombol ditekan, bukan dari proyek yang
    /// mungkin berganti di tengah proses.
    func export(_ kind: ExportKind) {
        guard let survey, !isExporting else { return }
        let context = ExportContext(project: survey.name,
                                    points: points,
                                    presence: presence,
                                    groupCode: survey.groupCode,
                                    participantId: survey.participantId,
                                    participantAlias: survey.participantAlias,
                                    appVersion: AppConfig.version)
        isExporting = true
        ioQueue.async { [weak self] in
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
            var written: URL?
            var failure: String?
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let stamp = Exporters.isoNow()
                    .replacingOccurrences(of: ":", with: "-")
                    .replacingOccurrences(of: ".", with: "-")
                let slug = AppModel.slug(context.project)
                let url = folder.appendingPathComponent(
                    "lereng-\(slug)-\(stamp).\(kind.fileExtension)")
                try payload.write(to: url, atomically: true, encoding: .utf8)
                written = url
            } catch {
                failure = "Gagal menulis berkas: \(error.localizedDescription)"
            }

            DispatchQueue.main.async {
                guard let self else { return }
                self.isExporting = false
                if let written {
                    self.exportItem = ExportItem(url: written, title: kind.title)
                } else {
                    self.alertMessage = failure ?? "Ekspor gagal."
                }
            }
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

    // MARK: - Sinkron otomatis ke MongoDB

    var syncConfigured: Bool {
        MongoSync.currentConfig() != nil
    }

    /// True bila build ini sudah membawa endpoint dan kunci bawaan.
    var syncBuiltIn: Bool {
        MongoSync.hasBuiltInTarget
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
            syncStatus = autoSyncEnabled
                ? "Pengaturan tersimpan. Sinkron otomatis aktif."
                : "Pengaturan tersimpan. Sinkron otomatis sedang dijeda."
            if autoSyncEnabled { scheduleSync(delay: 1, force: true) }
            return true
        } catch {
            syncStatus = error.localizedDescription
            return false
        }
    }

    func setAutoSync(_ enabled: Bool) {
        autoSyncEnabled = enabled
        MongoSync.setAutoEnabled(enabled)
        scheduledSync?.cancel()
        scheduledSync = nil
        if enabled {
            if syncConfigured {
                syncStatus = "Sinkron otomatis aktif. Data baru dikirim sendiri."
                scheduleSync(delay: 1, force: true)
            } else {
                syncStatus = "Sinkron otomatis aktif. Isi endpoint dan token untuk mulai."
            }
        } else {
            syncStatus = "Sinkron otomatis dijeda. Data tetap tersimpan lokal."
        }
    }

    /// Menghapus pengaturan simpanan. Kalau build membawa endpoint bawaan,
    /// aplikasi kembali ke bawaan dan tetap menyinkron; kalau tidak, sinkron
    /// dimatikan.
    func clearSyncSettings() {
        UserDefaults.standard.removeObject(forKey: MongoSync.endpointKey)
        SyncTokenStore.clear()
        scheduledSync?.cancel()
        scheduledSync = nil
        syncEndpoint = MongoSync.effectiveEndpointString()
        syncToken = MongoSync.effectiveToken()
        if syncConfigured {
            autoSyncEnabled = true
            MongoSync.setAutoEnabled(true)
            syncStatus = "Kembali ke pengaturan bawaan. Sinkron otomatis aktif."
            scheduleSync(delay: 1, force: true)
        } else {
            autoSyncEnabled = false
            MongoSync.setAutoEnabled(false)
            syncStatus = "Alamat dan kunci dihapus. Sinkron otomatis dimatikan."
        }
    }

    /// Menunda sinkron sebentar supaya banyak titik beruntun dikirim sekali.
    /// Permintaan yang sudah antre tidak ditunda lagi oleh titik berikutnya.
    func scheduleSync(delay: TimeInterval = 20, force: Bool = false) {
        guard autoSyncEnabled, syncConfigured else { return }
        if scheduledSync != nil && !force { return }
        scheduledSync?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.scheduledSync = nil
            self?.syncNow()
        }
        scheduledSync = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    func syncNow() {
        guard autoSyncEnabled, syncConfigured, !isSyncing else { return }
        syncTask = Task { await self.performSync() }
    }

    /// Dipakai juga oleh BGAppRefreshTask. Aman dipanggil kapan saja.
    @MainActor
    func performSync() async {
        guard autoSyncEnabled, !isSyncing else { return }
        guard MongoSync.currentConfig() != nil else { return }
        isSyncing = true
        syncStatus = "Menyinkronkan data baru…"
        do {
            let summary = try await MongoSync.syncAll(database: database,
                                                      appVersion: AppConfig.version)
            isSyncing = false
            lastSyncAt = Date()
            syncStatus = summary.sent == 0
                ? "Sinkron otomatis aktif • semua data sudah terkirim"
                : "Terkirim \(summary.sent) data dari \(summary.projects) proyek"
        } catch {
            isSyncing = false
            syncStatus = "Sinkron otomatis gagal: \(error.localizedDescription). Akan dicoba lagi."
            scheduleSync(delay: 120, force: true)
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
