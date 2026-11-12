import XCTest
@testable import LerengOffline

final class ImportTests: XCTestCase {
    private var database: Database!
    private var path: String!

    override func setUp() {
        super.setUp()
        path = NSTemporaryDirectory() + "lereng-import-\(UUID().uuidString).sqlite"
        database = Database(path: path)
    }

    override func tearDown() {
        database = nil
        try? FileManager.default.removeItem(atPath: path)
        super.tearDown()
    }

    private func identitySurvey() -> Survey {
        let surveyId = database.ensureDefaultSurvey()
        database.updateIdentity(surveyId, groupCode: "GUNUNG77",
                                participantId: "1A2B3C4D", alias: "Renz")
        return database.survey(surveyId)!
    }

    func testCsvParserKeepsQuoteInsideLabel() {
        let cells = CsvCodec.parseLine(
            "track,GUNUNG77,1A2B3C4D,Renz \"The Hiker\",-7.5,110.4,1500")
        XCTAssertEqual(cells.count, 7)
        XCTAssertEqual(cells[3], "Renz \"The Hiker\"")
        XCTAssertEqual(cells[4], "-7.5")
        XCTAssertEqual(cells[5], "110.4")
        XCTAssertEqual(cells[6], "1500")
    }

    func testCsvParserHandlesQuotedCommaAndDoubledQuote() {
        let cells = CsvCodec.parseLine("\"Camp, atas\",\"Renz \"\"RH\"\"\",x")
        XCTAssertEqual(cells, ["Camp, atas", "Renz \"RH\"", "x"])
    }

    func testCsvImportRoundTripsExportAndSkipsDuplicates() throws {
        let survey = identitySurvey()
        let points = [
            TrackPoint(id: 1, surveyId: survey.id, time: 1_700_000_000_000,
                       lat: -7.5, lon: 110.4, altitude: 1500, horizontalAccuracy: 5,
                       verticalAccuracy: 8, pressure: nil, baroRelativeAltitude: nil,
                       source: "iOS Core Location", label: "", participantId: "1A2B3C4D",
                       participantAlias: "Renz", groupCode: "GUNUNG77", isWaypoint: false),
            TrackPoint(id: 2, surveyId: survey.id, time: 1_700_000_010_000,
                       lat: -7.501, lon: 110.401, altitude: 1510, horizontalAccuracy: 6,
                       verticalAccuracy: 9, pressure: nil, baroRelativeAltitude: nil,
                       source: "iOS Core Location", label: "Camp", participantId: "1A2B3C4D",
                       participantAlias: "Renz", groupCode: "GUNUNG77", isWaypoint: true)
        ]
        let context = ExportContext(project: "Gede", points: points,
                                    presence: [Presence(id: 1, surveyId: survey.id,
                                                        time: 1_700_000_060_000,
                                                        observerParticipantId: "1A2B3C4D",
                                                        seenParticipantId: "0A0B0C0D",
                                                        groupCode: "GUNUNG77")],
                                    groupCode: "GUNUNG77", participantId: "1A2B3C4D",
                                    participantAlias: "Renz", appVersion: "1.4.2")
        let csv = Exporters.csv(context)
        XCTAssertTrue(csv.contains("track,GUNUNG77"), csv)
        XCTAssertEqual(csv.split(omittingEmptySubsequences: true,
                                 whereSeparator: { $0.isNewline }).count, 4, csv)

        let first = CsvImporter.run(text: csv, fileName: "ekspor.csv",
                                    survey: survey, database: database)
        XCTAssertTrue(first.contains("2 titik track/waypoint"), first)
        XCTAssertTrue(first.contains("1 event presence"), first)
        XCTAssertEqual(database.points(surveyId: survey.id).count, 2)
        XCTAssertEqual(database.presence(surveyId: survey.id).count, 1)

        let second = CsvImporter.run(text: csv, fileName: "ekspor.csv",
                                     survey: survey, database: database)
        XCTAssertTrue(second.contains("duplikat"), second)
        XCTAssertEqual(database.points(surveyId: survey.id).count, 2)
        XCTAssertEqual(database.presence(surveyId: survey.id).count, 1)
    }

    func testGpxTrackParserReadsRouteWaypointAndDeclaredReference() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Uji" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:lereng="urn:lereng-offline:gpx:1">
          <metadata><extensions>
            <lereng:elevationReference>EGM96</lereng:elevationReference>
          </extensions></metadata>
          <rte>
            <rtept lat="-7.50000" lon="110.40000">
              <ele>1500</ele>
              <time>2024-01-01T00:00:00Z</time>
            </rtept>
            <rtept lat="-7.50100" lon="110.40100"><ele>1510</ele></rtept>
          </rte>
          <wpt lat="-7.60000" lon="110.50000"><name>Camp</name></wpt>
        </gpx>
        """
        let result = try GpxTrackParser.parse(data: Data(xml.utf8))
        XCTAssertEqual(result.creator, "Uji")
        XCTAssertEqual(result.declaredReference, "EGM96")
        XCTAssertEqual(result.points.count, 3)
        XCTAssertEqual(result.trackCount, 2)
        XCTAssertEqual(result.waypointCount, 1)
        XCTAssertEqual(result.points[0].time, 1_704_067_200_000)
        XCTAssertFalse(result.points[0].isWaypoint)
        XCTAssertTrue(result.points[2].isWaypoint)
        XCTAssertEqual(result.points[2].name, "Camp")
        XCTAssertTrue(result.hasElevation)
    }

    func testGpxImporterKeepsDatumLabelAndSkipsSecondRun() throws {
        let survey = identitySurvey()
        let xml = """
        <gpx version="1.1" creator="Uji">
          <trk><trkseg>
            <trkpt lat="-7.5" lon="110.4"><ele>1500</ele><time>2024-01-01T00:00:00Z</time></trkpt>
            <trkpt lat="-7.501" lon="110.401"><ele>1510</ele><time>2024-01-01T00:00:10Z</time></trkpt>
          </trkseg></trk>
        </gpx>
        """
        let result = try GpxTrackParser.parse(data: Data(xml.utf8))
        let pending = PendingTrackImport(name: "uji", creator: result.creator,
                                         declaredReference: result.declaredReference,
                                         points: result.points, skipped: result.skipped)

        _ = GpxImporter.run(pending: pending, survey: survey,
                            datum: ImportDatum.egm96, importStart: 1_700_000_000_000,
                            database: database)
        let stored = database.points(surveyId: survey.id)
        XCTAssertEqual(stored.count, 2)
        XCTAssertEqual(stored[0].source, "GPX import [elevation reference: EGM96]")
        XCTAssertEqual(stored[0].elevationReference, "EGM96")
        XCTAssertEqual(stored[0].altitude ?? 0, 1500, accuracy: 0.001)

        let second = GpxImporter.run(pending: pending, survey: survey,
                                     datum: ImportDatum.egm96,
                                     importStart: 1_700_000_100_000,
                                     database: database)
        XCTAssertTrue(second.contains("duplikat"), second)
        XCTAssertEqual(database.points(surveyId: survey.id).count, 2)
    }

    func testGpxImporterDropsElevationWhenIgnored() throws {
        let survey = identitySurvey()
        let xml = """
        <gpx version="1.1" creator="Uji"><trk><trkseg>
          <trkpt lat="-7.5" lon="110.4"><ele>1500</ele></trkpt>
        </trkseg></trk></gpx>
        """
        let result = try GpxTrackParser.parse(data: Data(xml.utf8))
        let pending = PendingTrackImport(name: "uji", creator: result.creator,
                                         declaredReference: result.declaredReference,
                                         points: result.points, skipped: result.skipped)
        _ = GpxImporter.run(pending: pending, survey: survey, datum: ImportDatum.none,
                            importStart: 1_700_000_000_000, database: database)
        let stored = database.points(surveyId: survey.id)
        XCTAssertEqual(stored.count, 1)
        XCTAssertNil(stored[0].altitude)
        XCTAssertEqual(stored[0].source, "GPX import")
    }
}
