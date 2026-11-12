import Foundation

/// Impor GPX ke proyek. Duplikat dikenali dari waktu + peserta + jenis, lalu
/// toleransi 2 m untuk koordinat yang dibulatkan saat ekspor.
enum GpxImporter {
    static func run(pending: PendingTrackImport, survey: Survey, datum: String,
                    importStart: Int64, database: Database) -> String {
        let source = datum.isEmpty
            ? "GPX import"
            : "GPX import [elevation reference: \(datum)]"

        var index: [String: [(lat: Double, lon: Double)]] = [:]
        for existing in database.points(surveyId: survey.id) {
            index[key(time: existing.time, waypoint: existing.isWaypoint,
                      participantId: existing.participantId), default: []]
                .append((existing.lat, existing.lon))
        }

        var inserted = 0
        var duplicates = 0
        var lastTime: Int64?
        var sequence = 0

        for parsed in pending.points {
            let time: Int64
            if let value = parsed.time {
                time = value
            } else if let previous = lastTime {
                time = previous + 1000
            } else {
                time = importStart + Int64(sequence)
            }
            lastTime = time
            sequence += 1

            let lookup = key(time: time, waypoint: parsed.isWaypoint,
                             participantId: survey.participantId)
            var duplicate = false
            if let candidates = index[lookup] {
                for candidate in candidates {
                    if Geo.distanceMeters(lat1: candidate.lat, lon1: candidate.lon,
                                          lat2: parsed.lat, lon2: parsed.lon) <= 2.0 {
                        duplicate = true
                        break
                    }
                }
            }
            if duplicate { duplicates += 1; continue }

            var label = parsed.name
            if label.count > 80 { label = String(label.prefix(80)) }

            let point = NewPoint(surveyId: survey.id,
                                 time: time,
                                 lat: parsed.lat,
                                 lon: parsed.lon,
                                 altitude: datum.isEmpty ? nil : parsed.elevation,
                                 horizontalAccuracy: nil,
                                 verticalAccuracy: nil,
                                 pressure: nil,
                                 baroRelativeAltitude: nil,
                                 source: source,
                                 label: label,
                                 participantId: survey.participantId,
                                 participantAlias: survey.participantAlias,
                                 groupCode: survey.groupCode,
                                 isWaypoint: parsed.isWaypoint)
            if database.insertPointUnique(point) {
                inserted += 1
                index[lookup, default: []].append((parsed.lat, parsed.lon))
            } else {
                duplicates += 1
            }
        }

        var message = "\(inserted) titik ditambahkan ke proyek “\(survey.name)”."
        if duplicates > 0 { message += "\n\(duplicates) titik duplikat diabaikan." }
        if pending.skipped > 0 {
            message += "\n\(pending.skipped) baris dilewati karena koordinat tidak valid."
        }
        if !pending.hasTime {
            message += "\nFile tanpa waktu: urutan file dipakai sebagai waktu relatif."
        }
        message += "\n\nData tetap tersimpan lokal. GPX dianggap satu jalur; "
        message += "gunakan CSV grup untuk menggabungkan peserta."
        return message
    }

    private static func key(time: Int64, waypoint: Bool, participantId: String) -> String {
        "\(time)|\(waypoint ? "w" : "t")|\(participantId)"
    }
}

/// Impor CSV hasil ekspor Lereng Offline atau tabel sederhana dengan kolom
/// latitude/longitude/elevation_m. Berjalan di antrean latar.
enum CsvImporter {
    static let maxBytes = 32 * 1024 * 1024

    enum ImportError: LocalizedError {
        case tooLarge
        case encoding

        var errorDescription: String? {
            switch self {
            case .tooLarge: return "File CSV lebih dari 32 MB."
            case .encoding: return "File CSV tidak dapat dibaca sebagai teks UTF-8."
            }
        }
    }

    private struct Columns {
        var type = -1
        var group = -1
        var participantId = -1
        var alias = -1
        var lat = -1
        var lon = -1
        var altitude = -1
        var horizontalAccuracy = -1
        var verticalAccuracy = -1
        var pressure = -1
        var baro = -1
        var label = -1
        var time = -1
        var source = -1
        var peer = -1
        var waypoint = -1
        var datum = -1

        mutating func fill(from cells: [String]) {
            for (index, raw) in cells.enumerated() {
                let header = raw.trimmingCharacters(in: .whitespaces)
                    .replacingOccurrences(of: "\u{FEFF}", with: "")
                    .lowercased()
                    .replacingOccurrences(of: " ", with: "_")
                switch header {
                case "record_type": type = index
                case "group_code": group = index
                case "participant_id": participantId = index
                case "participant_alias": alias = index
                case "lat", "latitude": lat = index
                case "lon", "lng", "longitude": lon = index
                case "elevation_m", "elevation", "altitude", "alt", "ele", "height_m":
                    altitude = index
                case "accuracy_m", "h_acc", "horizontal_accuracy": horizontalAccuracy = index
                case "vertical_accuracy_m", "v_acc": verticalAccuracy = index
                case "pressure_hpa": pressure = index
                case "barometer_relative_elevation_m": baro = index
                case "label", "name": label = index
                case "time_iso", "timestamp", "time": time = index
                case "source": source = index
                case "elevation_reference", "vertical_datum": datum = index
                case "peer_id", "seen_participant_id": peer = index
                case "is_waypoint": waypoint = index
                default: break
                }
            }
        }

        static func legacy() -> Columns {
            var columns = Columns()
            columns.lat = 0
            columns.lon = 1
            columns.altitude = 2
            columns.horizontalAccuracy = 3
            columns.label = 4
            return columns
        }
    }

    static func run(data: Data, fileName: String, survey: Survey,
                    database: Database) throws -> String {
        guard data.count <= maxBytes else { throw ImportError.tooLarge }
        guard let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .isoLatin1) else {
            throw ImportError.encoding
        }
        return run(text: text, fileName: fileName, survey: survey, database: database)
    }

    static func run(text: String, fileName: String, survey: Survey,
                    database: Database) -> String {
        let fallbackId = BleGroupCodec.newParticipantId()
        let fallbackAlias = aliasFromFile(fileName, fallbackId: fallbackId)
        let iso = ISO8601DateFormatter()
        let isoFractional: ISO8601DateFormatter = {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return formatter
        }()
        let idPattern = "^[A-Za-z0-9_-]{4,32}$"

        func parseTime(_ value: String) -> Int64 {
            let clean = value.trimmingCharacters(in: .whitespaces)
            if clean.isEmpty { return Int64(Date().timeIntervalSince1970 * 1000) }
            if let milliseconds = Int64(clean) { return milliseconds }
            if let date = iso.date(from: clean) ?? isoFractional.date(from: clean) {
                return Int64(date.timeIntervalSince1970 * 1000)
            }
            return Int64(Date().timeIntervalSince1970 * 1000)
        }

        func optionalDouble(_ value: String) -> Double? {
            let clean = value.trimmingCharacters(in: .whitespaces)
            guard !clean.isEmpty else { return nil }
            return Double(clean)
        }

        var inserted = 0
        var presenceInserted = 0
        var invalid = 0
        var wrongGroup = 0
        var duplicates = 0
        var rowNumber = 0
        var headerChecked = false
        var columns = Columns()

        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            rowNumber += 1
            let line = String(rawLine).replacingOccurrences(of: "\r", with: "")
            if line.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            let cells = CsvCodec.parseLine(line)

            if !headerChecked {
                headerChecked = true
                if Double(cell(cells, 0).trimmingCharacters(in: .whitespaces)) != nil {
                    columns = Columns.legacy()
                } else {
                    columns.fill(from: cells)
                    continue
                }
            }

            let rawType = cell(cells, columns.type).trimmingCharacters(in: .whitespaces).lowercased()
            let recordType = rawType.isEmpty ? "track" : rawType
            let rowGroup = BleGroupCodec.normalizeGroupCode(cell(cells, columns.group))
            if !rowGroup.isEmpty,
               survey.groupCode.isEmpty || survey.groupCode.uppercased() != rowGroup.uppercased() {
                wrongGroup += 1
                continue
            }
            let effectiveGroup = rowGroup.isEmpty ? survey.groupCode : rowGroup

            var participantId = cell(cells, columns.participantId)
                .trimmingCharacters(in: .whitespaces)
            if participantId.isEmpty { participantId = fallbackId }
            guard participantId.range(of: idPattern, options: .regularExpression) != nil else {
                invalid += 1
                continue
            }
            var participantAlias = cell(cells, columns.alias)
                .trimmingCharacters(in: .whitespaces)
            if participantAlias.isEmpty { participantAlias = fallbackAlias }

            let time = parseTime(cell(cells, columns.time))

            if recordType == "presence" {
                let peer = cell(cells, columns.peer).trimmingCharacters(in: .whitespaces)
                guard !peer.isEmpty,
                      peer.range(of: idPattern, options: .regularExpression) != nil,
                      !effectiveGroup.isEmpty else {
                    invalid += 1
                    continue
                }
                if database.insertPresence(surveyId: survey.id, time: time,
                                           observer: participantId, seen: peer,
                                           groupCode: effectiveGroup) {
                    presenceInserted += 1
                } else {
                    duplicates += 1
                }
                continue
            }

            guard recordType == "track" || recordType == "waypoint" else {
                invalid += 1
                continue
            }

            guard let lat = optionalDouble(cell(cells, columns.lat)),
                  let lon = optionalDouble(cell(cells, columns.lon)),
                  lat.isFinite, lon.isFinite, lat >= -90, lat <= 90,
                  lon >= -180, lon <= 180 else {
                invalid += 1
                continue
            }
            let altitude = optionalDouble(cell(cells, columns.altitude))
            if let altitude, !altitude.isFinite || altitude < -12_000 || altitude > 100_000 {
                invalid += 1
                continue
            }
            let horizontalAccuracy = optionalDouble(cell(cells, columns.horizontalAccuracy))
            let verticalAccuracy = optionalDouble(cell(cells, columns.verticalAccuracy))
            let pressure = optionalDouble(cell(cells, columns.pressure))
            let baro = optionalDouble(cell(cells, columns.baro))

            var source = cell(cells, columns.source).trimmingCharacters(in: .whitespaces)
            if source.isEmpty { source = "CSV" }
            var importedDatum = cell(cells, columns.datum).trimmingCharacters(in: .whitespaces)
            if importedDatum.count > 80 { importedDatum = String(importedDatum.prefix(80)) }
            if !importedDatum.isEmpty,
               !source.lowercased().contains(importedDatum.lowercased()) {
                source += " [elevation reference: \(importedDatum)]"
            }

            let waypointCell = cell(cells, columns.waypoint)
            let waypoint = recordType == "waypoint"
                || waypointCell.lowercased() == "true"
                || waypointCell == "1"
            let label = cell(cells, columns.label).trimmingCharacters(in: .whitespaces)

            let point = NewPoint(surveyId: survey.id,
                                 time: time,
                                 lat: lat,
                                 lon: lon,
                                 altitude: altitude,
                                 horizontalAccuracy: horizontalAccuracy,
                                 verticalAccuracy: verticalAccuracy,
                                 pressure: pressure,
                                 baroRelativeAltitude: baro,
                                 source: source,
                                 label: label.isEmpty ? "Impor CSV • baris \(rowNumber)" : label,
                                 participantId: participantId,
                                 participantAlias: participantAlias,
                                 groupCode: effectiveGroup,
                                 isWaypoint: waypoint)
            if database.insertPointUnique(point) {
                inserted += 1
            } else {
                duplicates += 1
            }
        }

        var message = "\(inserted) titik track/waypoint dan \(presenceInserted) event presence "
        message += "ditambahkan ke proyek “\(survey.name)”."
        if duplicates > 0 { message += "\n\(duplicates) baris duplikat diabaikan." }
        if wrongGroup > 0 {
            message += "\n\(wrongGroup) baris outing lain/tanpa kode yang cocok dilewati. "
            message += "Atur kode rombongan yang sama sebelum mengimpor."
        }
        if invalid > 0 {
            message += "\n\(invalid) baris dilewati karena format/koordinat tidak valid."
        }
        message += "\n\nData tetap tersimpan lokal. Track peserta digabung untuk satu profil jalur, "
        message += "bukan kontur area."
        return message
    }

    private static func cell(_ cells: [String], _ column: Int) -> String {
        guard column >= 0, column < cells.count else { return "" }
        return cells[column]
    }

    private static func aliasFromFile(_ fileName: String, fallbackId: String) -> String {
        let base = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        var name = base.isEmpty
            ? "Peserta impor-\(fallbackId.suffix(4))"
            : base
        if name.lowercased().hasSuffix(".csv") { name = String(name.dropLast(4)) }
        if name.count > 32 { name = String(name.prefix(32)) }
        return name
    }
}
