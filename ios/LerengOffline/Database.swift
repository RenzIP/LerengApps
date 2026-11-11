import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

enum SQLValue {
    case int(Int64)
    case double(Double)
    case text(String)
    case null
}

/// SQLite lokal. Skema dan nama kolom sama dengan aplikasi Android supaya ekspor konsisten.
final class Database {
    private var handle: OpaquePointer?

    init(path: String) {
        var connection: OpaquePointer?
        if sqlite3_open(path, &connection) != SQLITE_OK {
            let message = connection.flatMap { sqlite3_errmsg($0) }.map { String(cString: $0) } ?? "unknown"
            if let connection { sqlite3_close(connection) }
            fatalError("Tidak dapat membuka database: \(message)")
        }
        handle = connection
        sqlite3_busy_timeout(handle, 3_000)
        _ = execute("PRAGMA foreign_keys = ON")
        migrate()
    }

    deinit {
        if let handle { sqlite3_close(handle) }
    }

    // MARK: - Primitif

    @discardableResult
    private func execute(_ sql: String) -> Bool {
        var error: UnsafeMutablePointer<CChar>?
        let result = sqlite3_exec(handle, sql, nil, nil, &error)
        if let error { sqlite3_free(error) }
        return result == SQLITE_OK
    }

    private func prepare(_ sql: String, _ binds: [SQLValue] = []) -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else { return nil }
        for (offset, value) in binds.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case .int(let number): sqlite3_bind_int64(statement, index, number)
            case .double(let number): sqlite3_bind_double(statement, index, number)
            case .text(let text): sqlite3_bind_text(statement, index, text, -1, sqliteTransient)
            case .null: sqlite3_bind_null(statement, index)
            }
        }
        return statement
    }

    private func text(_ statement: OpaquePointer?, _ index: Int32) -> String {
        guard let raw = sqlite3_column_text(statement, index) else { return "" }
        return String(cString: raw)
    }

    private func optionalDouble(_ statement: OpaquePointer?, _ index: Int32) -> Double? {
        sqlite3_column_type(statement, index) == SQLITE_NULL ? nil : sqlite3_column_double(statement, index)
    }

    // MARK: - Skema

    private func migrate() {
        _ = execute("""
        CREATE TABLE IF NOT EXISTS surveys (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            group_code TEXT NOT NULL DEFAULT '',
            participant_id TEXT NOT NULL DEFAULT '',
            participant_alias TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS points (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            survey_id INTEGER NOT NULL,
            time_ms INTEGER NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            gps_alt REAL,
            h_acc REAL,
            v_acc REAL,
            pressure_hpa REAL,
            baro_rel_alt REAL,
            source TEXT NOT NULL,
            label TEXT NOT NULL DEFAULT '',
            participant_id TEXT NOT NULL DEFAULT '',
            participant_alias TEXT NOT NULL DEFAULT '',
            group_code TEXT NOT NULL DEFAULT '',
            is_waypoint INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(survey_id) REFERENCES surveys(_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS points_by_survey_time ON points(survey_id, time_ms);
        CREATE TABLE IF NOT EXISTS presence (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            survey_id INTEGER NOT NULL,
            time_ms INTEGER NOT NULL,
            observer_participant_id TEXT NOT NULL,
            seen_participant_id TEXT NOT NULL,
            group_code TEXT NOT NULL,
            bucket_30s INTEGER NOT NULL,
            FOREIGN KEY(survey_id) REFERENCES surveys(_id) ON DELETE CASCADE,
            UNIQUE(survey_id, observer_participant_id, seen_participant_id, bucket_30s)
        );
        CREATE INDEX IF NOT EXISTS presence_by_survey_time ON presence(survey_id, time_ms);
        CREATE TABLE IF NOT EXISTS reference_tracks (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            survey_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            source TEXT NOT NULL,
            imported_at INTEGER NOT NULL,
            point_count INTEGER NOT NULL,
            FOREIGN KEY(survey_id) REFERENCES surveys(_id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS reference_points (
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_id INTEGER NOT NULL,
            seq INTEGER NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            ele REAL,
            time_ms INTEGER,
            FOREIGN KEY(track_id) REFERENCES reference_tracks(_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS reference_points_by_track ON reference_points(track_id, seq);
        CREATE INDEX IF NOT EXISTS reference_tracks_by_survey ON reference_tracks(survey_id);
        PRAGMA user_version = 4;
        """)
    }

    // MARK: - Proyek

    func ensureDefaultSurvey() -> Int64 {
        if let first = surveys().first { return first.id }
        return insertSurvey(name: "Pendakian saya", groupCode: "", participantId: "", alias: "")
    }

    @discardableResult
    func insertSurvey(name: String, groupCode: String, participantId: String, alias: String) -> Int64 {
        let statement = prepare("""
        INSERT INTO surveys (name, created_at, group_code, participant_id, participant_alias)
        VALUES (?, ?, ?, ?, ?)
        """, [.text(name), .int(Int64(Date().timeIntervalSince1970 * 1000)),
              .text(groupCode), .text(participantId), .text(alias)])
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else { return -1 }
        return sqlite3_last_insert_rowid(handle)
    }

    func surveys() -> [Survey] {
        let statement = prepare("SELECT _id, name, created_at, group_code, participant_id, participant_alias FROM surveys ORDER BY _id")
        defer { sqlite3_finalize(statement) }
        var result: [Survey] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            result.append(Survey(id: sqlite3_column_int64(statement, 0),
                                 name: text(statement, 1),
                                 createdAt: sqlite3_column_int64(statement, 2),
                                 groupCode: text(statement, 3),
                                 participantId: text(statement, 4),
                                 participantAlias: text(statement, 5)))
        }
        return result
    }

    func survey(_ id: Int64) -> Survey? {
        let statement = prepare("SELECT _id, name, created_at, group_code, participant_id, participant_alias FROM surveys WHERE _id = ?", [.int(id)])
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        return Survey(id: sqlite3_column_int64(statement, 0),
                      name: text(statement, 1),
                      createdAt: sqlite3_column_int64(statement, 2),
                      groupCode: text(statement, 3),
                      participantId: text(statement, 4),
                      participantAlias: text(statement, 5))
    }

    func renameSurvey(_ id: Int64, name: String) {
        let statement = prepare("UPDATE surveys SET name = ? WHERE _id = ?", [.text(name), .int(id)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }

    func updateIdentity(_ id: Int64, groupCode: String, participantId: String, alias: String) {
        let statement = prepare("""
        UPDATE surveys SET group_code = ?, participant_id = ?, participant_alias = ? WHERE _id = ?
        """, [.text(groupCode), .text(participantId), .text(alias), .int(id)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }

    func deleteSurvey(_ id: Int64) {
        let statement = prepare("DELETE FROM surveys WHERE _id = ?", [.int(id)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }

    // MARK: - Titik

    @discardableResult
    func insertPoint(_ point: NewPoint) -> Int64 {
        let statement = prepare("""
        INSERT INTO points (survey_id, time_ms, lat, lon, gps_alt, h_acc, v_acc, pressure_hpa,
                            baro_rel_alt, source, label, participant_id, participant_alias,
                            group_code, is_waypoint)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            .int(point.surveyId), .int(point.time), .double(point.lat), .double(point.lon),
            point.altitude.map { SQLValue.double($0) } ?? .null,
            point.horizontalAccuracy.map { SQLValue.double($0) } ?? .null,
            point.verticalAccuracy.map { SQLValue.double($0) } ?? .null,
            point.pressure.map { SQLValue.double($0) } ?? .null,
            point.baroRelativeAltitude.map { SQLValue.double($0) } ?? .null,
            .text(point.source), .text(point.label), .text(point.participantId),
            .text(point.participantAlias), .text(point.groupCode), .int(point.isWaypoint ? 1 : 0)
        ])
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else { return -1 }
        return sqlite3_last_insert_rowid(handle)
    }

    func points(surveyId: Int64) -> [TrackPoint] {
        let statement = prepare("""
        SELECT _id, survey_id, time_ms, lat, lon, gps_alt, h_acc, v_acc, pressure_hpa, baro_rel_alt,
               source, label, participant_id, participant_alias, group_code, is_waypoint
        FROM points WHERE survey_id = ? ORDER BY time_ms
        """, [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        var result: [TrackPoint] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            result.append(TrackPoint(
                id: sqlite3_column_int64(statement, 0),
                surveyId: sqlite3_column_int64(statement, 1),
                time: sqlite3_column_int64(statement, 2),
                lat: sqlite3_column_double(statement, 3),
                lon: sqlite3_column_double(statement, 4),
                altitude: optionalDouble(statement, 5),
                horizontalAccuracy: optionalDouble(statement, 6),
                verticalAccuracy: optionalDouble(statement, 7),
                pressure: optionalDouble(statement, 8),
                baroRelativeAltitude: optionalDouble(statement, 9),
                source: text(statement, 10),
                label: text(statement, 11),
                participantId: text(statement, 12),
                participantAlias: text(statement, 13),
                groupCode: text(statement, 14),
                isWaypoint: sqlite3_column_int64(statement, 15) != 0))
        }
        return result
    }

    func deletePoint(_ id: Int64) {
        let statement = prepare("DELETE FROM points WHERE _id = ?", [.int(id)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }

    func deletePoints(surveyId: Int64) {
        let statement = prepare("DELETE FROM points WHERE survey_id = ?", [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }

    // MARK: - Kehadiran BLE

    func insertPresence(surveyId: Int64, time: Int64, observer: String,
                        seen: String, groupCode: String) -> Bool {
        let statement = prepare("""
        INSERT OR IGNORE INTO presence
            (survey_id, time_ms, observer_participant_id, seen_participant_id, group_code, bucket_30s)
        VALUES (?, ?, ?, ?, ?, ?)
        """, [.int(surveyId), .int(time), .text(observer), .text(seen), .text(groupCode),
              .int(time / 30_000)])
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else { return false }
        // INSERT OR IGNORE juga mengembalikan SQLITE_DONE saat baris sudah ada (bucket 30 detik).
        return sqlite3_changes(handle) > 0
    }

    func presence(surveyId: Int64) -> [Presence] {
        let statement = prepare("""
        SELECT _id, survey_id, time_ms, observer_participant_id, seen_participant_id, group_code
        FROM presence WHERE survey_id = ? ORDER BY time_ms
        """, [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        var result: [Presence] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            result.append(Presence(id: sqlite3_column_int64(statement, 0),
                                   surveyId: sqlite3_column_int64(statement, 1),
                                   time: sqlite3_column_int64(statement, 2),
                                   observerParticipantId: text(statement, 3),
                                   seenParticipantId: text(statement, 4),
                                   groupCode: text(statement, 5)))
        }
        return result
    }

    func deletePresence(surveyId: Int64) {
        let statement = prepare("DELETE FROM presence WHERE survey_id = ?", [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }

    // MARK: - Jalur pembanding

    /// Satu jalur pembanding per proyek: impor ulang mengganti jalur sebelumnya.
    /// Data ini terpisah dari `points` sehingga tidak pernah ikut ekspor rekaman.
    @discardableResult
    func replaceReferenceTrack(surveyId: Int64, name: String, source: String,
                               points: [ReferencePoint]) -> Int64 {
        let usable = points.filter { $0.hasValidCoordinate }
        guard !usable.isEmpty else { return -1 }
        guard execute("BEGIN IMMEDIATE") else { return -1 }

        let delete = prepare("DELETE FROM reference_tracks WHERE survey_id = ?", [.int(surveyId)])
        sqlite3_step(delete)
        sqlite3_finalize(delete)

        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let insertTrack = prepare("""
        INSERT INTO reference_tracks (survey_id, name, source, imported_at, point_count)
        VALUES (?, ?, ?, ?, ?)
        """, [.int(surveyId),
              .text(cleanName.isEmpty ? "Jalur pembanding" : cleanName),
              .text(source),
              .int(Int64(Date().timeIntervalSince1970 * 1000)),
              .int(Int64(usable.count))])
        guard sqlite3_step(insertTrack) == SQLITE_DONE else {
            sqlite3_finalize(insertTrack)
            _ = execute("ROLLBACK")
            return -1
        }
        sqlite3_finalize(insertTrack)
        let trackId = sqlite3_last_insert_rowid(handle)

        let insertPoint = prepare("""
        INSERT INTO reference_points (track_id, seq, lat, lon, ele, time_ms)
        VALUES (?, ?, ?, ?, ?, ?)
        """)
        guard insertPoint != nil else {
            _ = execute("ROLLBACK")
            return -1
        }
        var failed = false
        for (index, point) in usable.enumerated() {
            sqlite3_reset(insertPoint)
            sqlite3_clear_bindings(insertPoint)
            sqlite3_bind_int64(insertPoint, 1, trackId)
            sqlite3_bind_int64(insertPoint, 2, Int64(index))
            sqlite3_bind_double(insertPoint, 3, point.lat)
            sqlite3_bind_double(insertPoint, 4, point.lon)
            if let elevation = point.elevation { sqlite3_bind_double(insertPoint, 5, elevation) }
            else { sqlite3_bind_null(insertPoint, 5) }
            if let time = point.time { sqlite3_bind_int64(insertPoint, 6, time) }
            else { sqlite3_bind_null(insertPoint, 6) }
            if sqlite3_step(insertPoint) != SQLITE_DONE { failed = true; break }
        }
        sqlite3_finalize(insertPoint)
        guard !failed, execute("COMMIT") else {
            _ = execute("ROLLBACK")
            return -1
        }
        return trackId
    }

    func referenceTrack(surveyId: Int64) -> ReferenceTrack? {
        let statement = prepare("""
        SELECT _id, survey_id, name, source, imported_at, point_count
        FROM reference_tracks WHERE survey_id = ? ORDER BY _id DESC LIMIT 1
        """, [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        return ReferenceTrack(id: sqlite3_column_int64(statement, 0),
                              surveyId: sqlite3_column_int64(statement, 1),
                              name: text(statement, 2),
                              source: text(statement, 3),
                              importedAt: sqlite3_column_int64(statement, 4),
                              pointCount: Int(sqlite3_column_int64(statement, 5)))
    }

    /// Koordinat dalam urutan file. Hanya lat/lon karena peta menggambar geometri.
    func referencePoints(surveyId: Int64) -> [ReferencePoint] {
        let statement = prepare("""
        SELECT p.lat, p.lon, p.ele, p.time_ms
        FROM reference_points p JOIN reference_tracks t ON t._id = p.track_id
        WHERE t.survey_id = ? ORDER BY p.track_id DESC, p.seq ASC
        """, [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        var result: [ReferencePoint] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let time = sqlite3_column_type(statement, 3) == SQLITE_NULL
                ? nil : sqlite3_column_int64(statement, 3)
            result.append(ReferencePoint(lat: sqlite3_column_double(statement, 0),
                                         lon: sqlite3_column_double(statement, 1),
                                         elevation: optionalDouble(statement, 2),
                                         time: time))
        }
        return result
    }

    func deleteReferenceTrack(surveyId: Int64) {
        let statement = prepare("DELETE FROM reference_tracks WHERE survey_id = ?", [.int(surveyId)])
        defer { sqlite3_finalize(statement) }
        _ = sqlite3_step(statement)
    }
}
