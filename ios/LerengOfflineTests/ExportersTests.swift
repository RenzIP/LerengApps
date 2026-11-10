import XCTest
@testable import LerengOffline

final class ExportersTests: XCTestCase {
    private let start: Int64 = 1_700_000_000_000

    private func point(offsetSeconds: Int64,
                       lat: Double = -7.5000,
                       lon: Double = 110.4000,
                       altitude: Double? = 1500,
                       source: String = "iOS Core Location",
                       waypoint: Bool = false,
                       label: String = "") -> TrackPoint {
        TrackPoint(id: offsetSeconds,
                   surveyId: 1,
                   time: start + offsetSeconds * 1000,
                   lat: lat,
                   lon: lon,
                   altitude: altitude,
                   horizontalAccuracy: 5,
                   verticalAccuracy: 8,
                   pressure: nil,
                   baroRelativeAltitude: nil,
                   source: source,
                   label: label,
                   participantId: "1A2B3C4D",
                   participantAlias: "Renz",
                   groupCode: "GUNUNG77",
                   isWaypoint: waypoint)
    }

    private func context(_ points: [TrackPoint]) -> ExportContext {
        ExportContext(project: "Gede",
                      points: points,
                      presence: [Presence(id: 1, surveyId: 1, time: start + 60_000,
                                          observerParticipantId: "1A2B3C4D",
                                          seenParticipantId: "0A0B0C0D",
                                          groupCode: "GUNUNG77")],
                      groupCode: "GUNUNG77",
                      participantId: "1A2B3C4D",
                      participantAlias: "Renz",
                      appVersion: "1.0.0")
    }

    func testCsvHeaderAndRows() {
        let csv = Exporters.csv(context([point(offsetSeconds: 0), point(offsetSeconds: 10)]))
        let lines = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines.count, 4) // 1 header + 2 titik + 1 kehadiran
        XCTAssertTrue(lines[0].hasPrefix("record_type,group_code,participant_id"))
        XCTAssertTrue(lines[1].hasPrefix("track,GUNUNG77,1A2B3C4D"))
        XCTAssertTrue(lines[2].hasPrefix("track,GUNUNG77,1A2B3C4D"))
        XCTAssertTrue(lines[3].hasPrefix("presence,GUNUNG77,1A2B3C4D"))
        XCTAssertTrue(csv.contains("0A0B0C0D"))
    }

    func testGpxWithUniformElevationKeepsEle() {
        let gpx = Exporters.gpx(context([point(offsetSeconds: 0), point(offsetSeconds: 10, altitude: 1520)]),
                                includeElevation: true)
        XCTAssertTrue(gpx.contains("<ele>1500.00</ele>"))
        XCTAssertTrue(gpx.contains("<ele>1520.00</ele>"))
        XCTAssertTrue(gpx.contains("xmlns:lereng="))
        XCTAssertTrue(gpx.contains(ElevationReference.coreLocation))
        XCTAssertTrue(gpx.contains("<trkpt lat=\"-7.5000000\" lon=\"110.4000000\">"))
    }

    func testGpxOmitsElevationWhenReferencesMixed() {
        let mixed = [
            point(offsetSeconds: 0),
            point(offsetSeconds: 10, source: "Android GPS/GNSS")
        ]
        let gpx = Exporters.gpx(context(mixed), includeElevation: true)
        XCTAssertFalse(gpx.contains("<ele>"))
        XCTAssertTrue(gpx.contains(ElevationReference.unrecorded))
    }

    func testGpxWithoutElevationNeverWritesEle() {
        let gpx = Exporters.gpx(context([point(offsetSeconds: 0)]), includeElevation: false)
        XCTAssertFalse(gpx.contains("<ele>"))
    }

    func testGeoJsonStructure() throws {
        let text = Exporters.geoJSON(context([point(offsetSeconds: 0), point(offsetSeconds: 10)]))
        let data = try XCTUnwrap(text.data(using: .utf8))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["type"] as? String, "FeatureCollection")
        let lereng = try XCTUnwrap(root["lereng"] as? [String: Any])
        XCTAssertEqual(lereng["schema"] as? String, "lereng.geojson/1")
        let features = try XCTUnwrap(root["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 1)
        let geometry = try XCTUnwrap(features[0]["geometry"] as? [String: Any])
        XCTAssertEqual(geometry["type"] as? String, "LineString")
        let coordinates = try XCTUnwrap(geometry["coordinates"] as? [[Double]])
        XCTAssertEqual(coordinates.first?.count, 3)
    }

    func testGeoJsonWaypointIsPointFeature() throws {
        let waypoint = point(offsetSeconds: 5, waypoint: true, label: "Camp")
        let text = Exporters.geoJSON(context([point(offsetSeconds: 0), waypoint]))
        let data = try XCTUnwrap(text.data(using: .utf8))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let features = try XCTUnwrap(root["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 2)
        let waypointFeature = try XCTUnwrap(features.first { ($0["properties"] as? [String: Any])?["record_type"] as? String == "waypoint" })
        let geometry = try XCTUnwrap(waypointFeature["geometry"] as? [String: Any])
        XCTAssertEqual(geometry["type"] as? String, "Point")
    }

    func testJsonLinesHasOneLinePerFeature() {
        let text = Exporters.jsonLines(context([point(offsetSeconds: 0), point(offsetSeconds: 10)]))
        let lines = text.split(separator: "\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines.count, 3) // 2 titik + 1 kehadiran
        for line in lines {
            XCTAssertTrue(line.hasPrefix("{"))
        }
    }

    func testRouteSummaryDistanceAndElevation() {
        let points = [point(offsetSeconds: 0, lat: -7.5000, lon: 110.4000, altitude: 1500),
                      point(offsetSeconds: 60, lat: -7.5010, lon: 110.4000, altitude: 1530)]
        let summary = RouteSummary.build(from: points)
        XCTAssertGreaterThan(summary.distanceMeters, 100)
        XCTAssertLessThan(summary.distanceMeters, 120)
        XCTAssertEqual(summary.ascentMeters, 30, accuracy: 0.001)
        XCTAssertEqual(summary.descentMeters, 0, accuracy: 0.001)
        XCTAssertEqual(summary.trackCount, 2)
    }

    func testRouteSummaryIgnoresMixedReferencesForAscent() {
        let points = [point(offsetSeconds: 0, altitude: 1500),
                      point(offsetSeconds: 60, altitude: 1600, source: "Android GPS/GNSS")]
        let summary = RouteSummary.build(from: points)
        XCTAssertEqual(summary.ascentMeters, 0, accuracy: 0.001)
    }
}
