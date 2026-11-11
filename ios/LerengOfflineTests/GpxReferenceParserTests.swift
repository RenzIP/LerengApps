import XCTest
@testable import LerengOffline

final class GpxReferenceParserTests: XCTestCase {
    private func parse(_ xml: String) throws -> GpxReferenceParser.Result {
        try GpxReferenceParser.parse(data: Data(xml.utf8))
    }

    func testParsesTrackPointsElevationTimeAndCreator() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="StravaGPX" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>Gede</name><trkseg>
            <trkpt lat="-7.50000" lon="110.40000">
              <ele>1500.5</ele>
              <time>2024-01-01T00:00:00Z</time>
            </trkpt>
            <trkpt lat="-7.50100" lon="110.40100">
              <ele>1510</ele>
            </trkpt>
          </trkseg></trk>
        </gpx>
        """
        let result = try parse(xml)
        XCTAssertEqual(result.creator, "StravaGPX")
        XCTAssertEqual(result.points.count, 2)
        XCTAssertEqual(result.skipped, 0)
        XCTAssertEqual(result.points[0].lat, -7.5, accuracy: 1e-9)
        XCTAssertEqual(result.points[0].lon, 110.4, accuracy: 1e-9)
        XCTAssertEqual(result.points[0].elevation ?? 0, 1500.5, accuracy: 0.001)
        XCTAssertEqual(result.points[0].time, 1_704_067_200_000)
        XCTAssertNil(result.points[1].time)
    }

    func testAcceptsFractionalSeconds() throws {
        let xml = """
        <gpx version="1.1" creator="Garmin"><trk><trkseg>
          <trkpt lat="-7.5" lon="110.4"><time>2024-01-01T00:00:00.250Z</time></trkpt>
        </trkseg></trk></gpx>
        """
        let result = try parse(xml)
        XCTAssertEqual(result.points.count, 1)
        XCTAssertEqual(result.points[0].time, 1_704_067_200_250)
    }

    func testSkipsInvalidCoordinatesAndCountsWaypoints() throws {
        let xml = """
        <gpx version="1.1" creator="Uji">
          <wpt lat="-7.5" lon="110.4"><name>Camp</name></wpt>
          <trk><trkseg>
            <trkpt lat="abc" lon="110.4"/>
            <trkpt lat="-95" lon="110.4"/>
            <trkpt lat="-7.5" lon="110.4"/>
          </trkseg></trk>
        </gpx>
        """
        let result = try parse(xml)
        XCTAssertEqual(result.points.count, 1)
        XCTAssertEqual(result.skipped, 2)
        XCTAssertEqual(result.waypointCount, 1)
    }

    func testRejectsNonGpxRoot() {
        XCTAssertThrowsError(try parse("<kml><Placemark/></kml>"))
    }

    func testRejectsDoctype() {
        let xml = """
        <?xml version="1.0"?>
        <!DOCTYPE gpx [<!ENTITY x "y">]>
        <gpx version="1.1"><trk><trkseg><trkpt lat="-7.5" lon="110.4"/></trkseg></trk></gpx>
        """
        XCTAssertThrowsError(try parse(xml))
    }

    func testRejectsMalformedXml() {
        XCTAssertThrowsError(try parse("<gpx><trk><trkseg>"))
    }

    func testEmptyDataRejected() {
        XCTAssertThrowsError(try GpxReferenceParser.parse(data: Data()))
    }
}
