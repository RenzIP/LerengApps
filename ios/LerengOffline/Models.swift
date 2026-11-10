import Foundation

// MARK: - Rekaman

struct Survey: Identifiable, Equatable {
    var id: Int64
    var name: String
    var createdAt: Int64
    var groupCode: String
    var participantId: String
    var participantAlias: String
}

struct TrackPoint: Identifiable, Equatable {
    var id: Int64
    var surveyId: Int64
    var time: Int64
    var lat: Double
    var lon: Double
    var altitude: Double?
    var horizontalAccuracy: Double?
    var verticalAccuracy: Double?
    var pressure: Double?
    var baroRelativeAltitude: Double?
    var source: String
    var label: String
    var participantId: String
    var participantAlias: String
    var groupCode: String
    var isWaypoint: Bool

    var date: Date { Date(timeIntervalSince1970: Double(time) / 1000) }

    var elevationReference: String { ElevationReference.of(self) }

    var hasValidCoordinate: Bool {
        lat.isFinite && lon.isFinite && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
    }
}

struct NewPoint {
    var surveyId: Int64
    var time: Int64
    var lat: Double
    var lon: Double
    var altitude: Double?
    var horizontalAccuracy: Double?
    var verticalAccuracy: Double?
    var pressure: Double?
    var baroRelativeAltitude: Double?
    var source: String
    var label: String
    var participantId: String
    var participantAlias: String
    var groupCode: String
    var isWaypoint: Bool
}

struct Presence: Identifiable, Equatable {
    var id: Int64
    var surveyId: Int64
    var time: Int64
    var observerParticipantId: String
    var seenParticipantId: String
    var groupCode: String
}

// MARK: - Referensi elevasi

/// Elevasi tidak pernah dikonversi. Label hanya keterangan asal-usul.
enum ElevationReference {
    static let coreLocation = "Core Location altitude; approximate mean sea level (orthometric)"
    static let androidLocation = "Android Location altitude; WGS84 ellipsoid"
    static let unrecorded = "not recorded"

    static func of(_ point: TrackPoint) -> String {
        guard point.altitude != nil else { return "" }
        return normalize(point.source)
    }

    static func normalize(_ source: String) -> String {
        let lower = source.lowercased()
        if let start = lower.range(of: "[elevation reference:") {
            let rest = source[start.upperBound...]
            if let end = rest.firstIndex(of: "]") {
                let value = rest[..<end].trimmingCharacters(in: .whitespaces)
                if !value.isEmpty { return value }
            }
        }
        if lower.contains("core location") || lower.contains("ios")
            || lower.contains("orthometric") || lower.contains("mean sea level") {
            return coreLocation
        }
        if lower == "csv" { return "as imported; datum not recorded" }
        return androidLocation
    }
}

// MARK: - Geometri

enum Geo {
    static let earthRadius = 6_371_008.8

    static func distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let phi1 = lat1 * .pi / 180
        let phi2 = lat2 * .pi / 180
        let dPhi = (lat2 - lat1) * .pi / 180
        let dLambda = (lon2 - lon1) * .pi / 180
        let a = sin(dPhi / 2) * sin(dPhi / 2)
            + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * earthRadius * atan2(sqrt(a), sqrt(max(0, 1 - a)))
    }

    static func distanceMeters(_ a: TrackPoint, _ b: TrackPoint) -> Double {
        distanceMeters(lat1: a.lat, lon1: a.lon, lat2: b.lat, lon2: b.lon)
    }
}

// MARK: - Ringkasan jalur

struct RouteSummary {
    var distanceMeters: Double
    var ascentMeters: Double
    var descentMeters: Double
    var trackCount: Int
    var waypointCount: Int

    static func build(from points: [TrackPoint]) -> RouteSummary {
        let track = points.filter { !$0.isWaypoint && $0.hasValidCoordinate }.sorted { $0.time < $1.time }
        var distance = 0.0
        var ascent = 0.0
        var descent = 0.0
        var previous: TrackPoint?
        for point in track {
            if let previous {
                distance += Geo.distanceMeters(previous, point)
                if let a = previous.altitude, let b = point.altitude,
                   ElevationReference.of(previous) == ElevationReference.of(point) {
                    let delta = b - a
                    if delta > 0 { ascent += delta } else { descent -= delta }
                }
            }
            previous = point
        }
        return RouteSummary(distanceMeters: distance, ascentMeters: ascent, descentMeters: descent,
                            trackCount: track.count, waypointCount: points.filter { $0.isWaypoint }.count)
    }
}
