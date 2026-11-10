import Foundation

struct ExportContext {
    var project: String
    var points: [TrackPoint]
    var presence: [Presence]
    var groupCode: String
    var participantId: String
    var participantAlias: String
    var appVersion: String
}

/// Semua ekspor bersifat lokal. Tidak ada unggahan, tidak ada konversi datum.
enum Exporters {
    static let appName = "Lereng Offline"
    static let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()

    static func iso(_ milliseconds: Int64) -> String {
        isoFormatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1000))
    }

    static func isoNow() -> String {
        isoFormatter.string(from: Date())
    }

    private static func number(_ value: Double) -> String {
        if value == value.rounded() && abs(value) < 1e15 {
            return String(Int64(value))
        }
        return String(value)
    }

    private static func numberOrEmpty(_ value: Double?) -> String {
        value.map(number) ?? ""
    }

    // MARK: - CSV

    static func csv(_ context: ExportContext) -> String {
        var out = ""
        appendRow(&out, ["record_type", "group_code", "participant_id", "participant_alias",
                         "latitude", "longitude", "elevation_m", "accuracy_m",
                         "vertical_accuracy_m", "pressure_hpa",
                         "barometer_relative_elevation_m", "time_iso", "source",
                         "elevation_reference", "label", "peer_id"])
        for point in context.points {
            appendRow(&out, [
                point.isWaypoint ? "waypoint" : "track",
                point.groupCode, point.participantId, point.participantAlias,
                number(point.lat), number(point.lon),
                numberOrEmpty(point.altitude),
                numberOrEmpty(point.horizontalAccuracy),
                numberOrEmpty(point.verticalAccuracy),
                numberOrEmpty(point.pressure),
                numberOrEmpty(point.baroRelativeAltitude),
                iso(point.time), point.source, point.elevationReference, point.label, ""
            ])
        }
        for event in context.presence {
            let alias = event.observerParticipantId == context.participantId ? context.participantAlias : ""
            appendRow(&out, [
                "presence", event.groupCode, event.observerParticipantId, alias,
                "", "", "", "", "", "", "", iso(event.time), "BLE", "",
                "presence ID + timestamp", event.seenParticipantId
            ])
        }
        return out
    }

    private static func appendRow(_ out: inout String, _ fields: [String]) {
        out += fields.map(escapeCsv).joined(separator: ",") + "\r\n"
    }

    private static func escapeCsv(_ value: String) -> String {
        if value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r") {
            return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return value
    }

    // MARK: - GPX

    static func gpx(_ context: ExportContext, includeElevation: Bool) -> String {
        let points = context.points.filter { $0.hasValidCoordinate }.sorted { $0.time < $1.time }
        let uniqueReferences = Set(points.filter { !$0.isWaypoint }.compactMap { point -> String? in
            point.altitude == nil ? nil : point.elevationReference
        })
        let reference = includeElevation && uniqueReferences.count == 1
            ? (uniqueReferences.first ?? ElevationReference.unrecorded)
            : ElevationReference.unrecorded
        let withElevation = !reference.isEmpty && reference != ElevationReference.unrecorded

        var out = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        out += "<gpx version=\"1.1\" creator=\"\(appName)\""
        out += " xmlns=\"http://www.topografix.com/GPX/1/1\""
        out += " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
        out += " xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\""
        out += " xmlns:lereng=\"urn:lereng-offline:gpx:1\">\n"
        out += "  <metadata>\n"
        out += "    <name>\(escapeXml(context.project.isEmpty ? "Jalur Lereng Offline" : context.project))</name>\n"
        let note = withElevation
            ? "Satu jalur per proyek; track bukan kontur area. Referensi elevasi: \(reference). Elevasi tidak dikonversi."
            : "Satu jalur per proyek; track bukan kontur area. Ekspor tanpa elevasi; hanya koordinat dan waktu."
        out += "    <desc>\(escapeXml(note))</desc>\n"
        out += "    <time>\(isoNow())</time>\n"
        out += "    <extensions>\n"
        out += "      <lereng:elevationReference>\(escapeXml(reference))</lereng:elevationReference>\n"
        out += "    </extensions>\n"
        out += "  </metadata>\n"

        for point in points where point.isWaypoint {
            out += "  <wpt lat=\"\(String(format: "%.7f", point.lat))\" lon=\"\(String(format: "%.7f", point.lon))\">\n"
            appendMeasurements(&out, point, withElevation: withElevation, indent: "    ")
            let label = point.label.trimmingCharacters(in: .whitespaces)
            if !label.isEmpty { out += "    <name>\(escapeXml(label))</name>\n" }
            out += "    <sym>Flag, Blue</sym>\n"
            out += "  </wpt>\n"
        }

        let track = points.filter { !$0.isWaypoint }
        if !track.isEmpty {
            out += "  <trk>\n"
            out += "    <name>\(escapeXml(context.project.isEmpty ? "Jalur Lereng Offline" : context.project))</name>\n"
            out += "    <desc>Jalur proyek; bukan kontur area.</desc>\n"
            out += "    <trkseg>\n"
            for point in track {
                out += "      <trkpt lat=\"\(String(format: "%.7f", point.lat))\" lon=\"\(String(format: "%.7f", point.lon))\">\n"
                appendMeasurements(&out, point, withElevation: withElevation, indent: "        ")
                out += "      </trkpt>\n"
            }
            out += "    </trkseg>\n"
            out += "  </trk>\n"
        }
        out += "</gpx>\n"
        return out
    }

    private static func appendMeasurements(_ out: inout String, _ point: TrackPoint,
                                           withElevation: Bool, indent: String) {
        if withElevation, let altitude = point.altitude, altitude.isFinite {
            out += indent + "<ele>" + String(format: "%.2f", altitude) + "</ele>\n"
        }
        if point.time > 0 { out += indent + "<time>" + iso(point.time) + "</time>\n" }
    }

    private static func escapeXml(_ value: String) -> String {
        value.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }

    // MARK: - GeoJSON

    static func geoJSON(_ context: ExportContext) -> String {
        let valid = context.points.filter { $0.hasValidCoordinate }
        let track = valid.filter { !$0.isWaypoint }.sorted { $0.time < $1.time }
        var features: [[String: Any]] = []

        if !track.isEmpty {
            var references = Set<String>()
            var allElevated = true
            var distance = 0.0
            for (index, point) in track.enumerated() {
                if point.altitude == nil { allElevated = false } else { references.insert(point.elevationReference) }
                if index > 0 { distance += Geo.distanceMeters(track[index - 1], point) }
            }
            let uniform3D = allElevated && references.count == 1

            var coordinates: [[Double]] = []
            var times: [String] = []
            for point in track {
                var position = [point.lon, point.lat]
                if uniform3D, let altitude = point.altitude { position.append(altitude) }
                coordinates.append(position)
                times.append(iso(point.time))
            }

            var geometry: [String: Any] = ["type": track.count == 1 ? "Point" : "LineString"]
            if track.count == 1 {
                geometry["coordinates"] = coordinates[0]
            } else {
                geometry["coordinates"] = coordinates
            }

            var properties: [String: Any] = [
                "record_type": "route",
                "project": context.project,
                "point_count": track.count,
                "distance_m": (distance * 10).rounded() / 10,
                "start_time_iso": iso(track[0].time),
                "end_time_iso": iso(track[track.count - 1].time),
                "times_iso": times,
                "elevation_reference": uniform3D
                    ? (references.first ?? ElevationReference.unrecorded)
                    : (references.isEmpty ? "none" : "mixed/omitted from route geometry; see JSONL or CSV")
            ]
            if !context.groupCode.isEmpty { properties["group_code"] = context.groupCode }
            if !context.participantId.isEmpty { properties["participant_id"] = context.participantId }
            if !context.participantAlias.isEmpty { properties["participant_alias"] = context.participantAlias }

            features.append(["type": "Feature", "geometry": geometry, "properties": properties])
        }

        for point in valid where point.isWaypoint {
            var coordinates = [point.lon, point.lat]
            if let altitude = point.altitude { coordinates.append(altitude) }
            var properties: [String: Any] = [
                "record_type": "waypoint",
                "project": context.project,
                "group_code": point.groupCode,
                "participant_id": point.participantId,
                "participant_alias": point.participantAlias,
                "time_iso": iso(point.time),
                "time_ms": point.time,
                "source": point.source,
                "label": point.label
            ]
            if let altitude = point.altitude {
                properties["elevation_m"] = altitude
                properties["elevation_reference"] = point.elevationReference
            }
            if let value = point.horizontalAccuracy { properties["horizontal_accuracy_m"] = value }
            if let value = point.verticalAccuracy { properties["vertical_accuracy_m"] = value }
            if let value = point.pressure { properties["pressure_hpa"] = value }
            if let value = point.baroRelativeAltitude { properties["barometer_relative_elevation_m"] = value }
            features.append([
                "type": "Feature",
                "geometry": ["type": "Point", "coordinates": coordinates],
                "properties": properties
            ])
        }

        var meta: [String: Any] = [
            "app": appName,
            "version": context.appVersion,
            "schema": "lereng.geojson/1",
            "project": context.project,
            "exported_at": isoNow(),
            "scope": "single route only; not area-wide terrain contours; no basemap tiles"
        ]
        if !context.groupCode.isEmpty { meta["group_code"] = context.groupCode }
        if !context.participantId.isEmpty { meta["participant_id"] = context.participantId }
        if !context.participantAlias.isEmpty { meta["participant_alias"] = context.participantAlias }

        let collection: [String: Any] = [
            "type": "FeatureCollection",
            "name": context.project,
            "scope": "single route only; not area-wide terrain contours",
            "lereng": meta,
            "features": features
        ]
        return jsonString(collection, pretty: true)
    }

    // MARK: - JSONL (MongoDB)

    static func jsonLines(_ context: ExportContext) -> String {
        var out = ""
        let exportedAt = isoNow()
        for point in context.points where point.hasValidCoordinate {
            var coordinates = [point.lon, point.lat]
            if let altitude = point.altitude { coordinates.append(altitude) }
            var properties: [String: Any] = [
                "schema": "lereng.mongo/1",
                "project": context.project,
                "record_type": point.isWaypoint ? "waypoint" : "track",
                "waypoint": point.isWaypoint,
                "group_code": point.groupCode,
                "participant_id": point.participantId,
                "participant_alias": point.participantAlias,
                "time_iso": iso(point.time),
                "time_ms": point.time,
                "source": point.source,
                "label": point.label,
                "exported_at": exportedAt
            ]
            if let altitude = point.altitude {
                properties["elevation_m"] = altitude
                properties["elevation_reference"] = point.elevationReference
            }
            if let value = point.horizontalAccuracy { properties["horizontal_accuracy_m"] = value }
            if let value = point.verticalAccuracy { properties["vertical_accuracy_m"] = value }
            if let value = point.pressure { properties["pressure_hpa"] = value }
            if let value = point.baroRelativeAltitude { properties["barometer_relative_elevation_m"] = value }
            let feature: [String: Any] = [
                "type": "Feature",
                "geometry": ["type": "Point", "coordinates": coordinates],
                "properties": properties
            ]
            out += jsonString(feature, pretty: false) + "\n"
        }
        for event in context.presence {
            let alias = event.observerParticipantId == context.participantId ? context.participantAlias : ""
            let properties: [String: Any] = [
                "schema": "lereng.mongo/1",
                "project": context.project,
                "record_type": "presence",
                "group_code": event.groupCode,
                "participant_id": event.observerParticipantId,
                "participant_alias": alias,
                "seen_participant_id": event.seenParticipantId,
                "time_iso": iso(event.time),
                "time_ms": event.time,
                "source": "BLE",
                "label": "presence ID + timestamp",
                "exported_at": exportedAt
            ]
            let feature: [String: Any] = ["type": "Feature", "geometry": NSNull(), "properties": properties]
            out += jsonString(feature, pretty: false) + "\n"
        }
        return out
    }

    private static func jsonString(_ object: [String: Any], pretty: Bool) -> String {
        var options: JSONSerialization.WritingOptions = [.sortedKeys, .withoutEscapingSlashes]
        if pretty { options.insert(.prettyPrinted) }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: options),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }
}
