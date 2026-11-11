import XCTest
@testable import LerengOffline

final class DatabaseTests: XCTestCase {
    private var database: Database!
    private var path: String!

    override func setUp() {
        super.setUp()
        path = NSTemporaryDirectory() + "lereng-test-\(UUID().uuidString).sqlite"
        database = Database(path: path)
    }

    override func tearDown() {
        database = nil
        try? FileManager.default.removeItem(atPath: path)
        super.tearDown()
    }

    private func newPoint(surveyId: Int64, time: Int64, waypoint: Bool = false) -> NewPoint {
        NewPoint(surveyId: surveyId, time: time, lat: -7.5, lon: 110.4, altitude: 1500,
                 horizontalAccuracy: 5, verticalAccuracy: 8, pressure: nil,
                 baroRelativeAltitude: nil, source: "iOS Core Location",
                 label: waypoint ? "Camp" : "", participantId: "1A2B3C4D",
                 participantAlias: "Renz", groupCode: "GUNUNG77", isWaypoint: waypoint)
    }

    func testDefaultSurveyCreatedOnce() {
        let first = database.ensureDefaultSurvey()
        let second = database.ensureDefaultSurvey()
        XCTAssertEqual(first, second)
        XCTAssertEqual(database.surveys().count, 1)
    }

    func testInsertAndReadPoints() {
        let surveyId = database.ensureDefaultSurvey()
        _ = database.insertPoint(newPoint(surveyId: surveyId, time: 1_000))
        _ = database.insertPoint(newPoint(surveyId: surveyId, time: 2_000, waypoint: true))

        let points = database.points(surveyId: surveyId)
        XCTAssertEqual(points.count, 2)
        XCTAssertEqual(points[0].time, 1_000)
        XCTAssertFalse(points[0].isWaypoint)
        XCTAssertTrue(points[1].isWaypoint)
        XCTAssertEqual(points[1].label, "Camp")
        XCTAssertEqual(points[0].altitude ?? 0, 1500, accuracy: 0.001)
    }

    func testDeletePointAndSurveyCascade() {
        let surveyId = database.ensureDefaultSurvey()
        let pointId = database.insertPoint(newPoint(surveyId: surveyId, time: 1_000))
        database.deletePoint(pointId)
        XCTAssertTrue(database.points(surveyId: surveyId).isEmpty)

        _ = database.insertPoint(newPoint(surveyId: surveyId, time: 2_000))
        _ = database.insertPoint(newPoint(surveyId: surveyId, time: 3_000))
        database.deletePoints(surveyId: surveyId)
        XCTAssertTrue(database.points(surveyId: surveyId).isEmpty)
    }

    func testIdentityAndRename() {
        let surveyId = database.ensureDefaultSurvey()
        database.updateIdentity(surveyId, groupCode: "GUNUNG77", participantId: "1A2B3C4D", alias: "Renz")
        database.renameSurvey(surveyId, name: "Gede via Cibodas")

        let survey = database.survey(surveyId)
        XCTAssertEqual(survey?.name, "Gede via Cibodas")
        XCTAssertEqual(survey?.groupCode, "GUNUNG77")
        XCTAssertEqual(survey?.participantId, "1A2B3C4D")
        XCTAssertEqual(survey?.participantAlias, "Renz")
    }

    func testPresenceDeduplicatesWithinBucket() {
        let surveyId = database.ensureDefaultSurvey()
        XCTAssertTrue(database.insertPresence(surveyId: surveyId, time: 30_000,
                                              observer: "1A2B3C4D", seen: "0A0B0C0D",
                                              groupCode: "GUNUNG77"))
        XCTAssertFalse(database.insertPresence(surveyId: surveyId, time: 30_500,
                                               observer: "1A2B3C4D", seen: "0A0B0C0D",
                                               groupCode: "GUNUNG77"))
        XCTAssertEqual(database.presence(surveyId: surveyId).count, 1)
    }

    func testDeletePresence() {
        let surveyId = database.ensureDefaultSurvey()
        _ = database.insertPresence(surveyId: surveyId, time: 30_000,
                                    observer: "1A2B3C4D", seen: "0A0B0C0D",
                                    groupCode: "GUNUNG77")
        database.deletePresence(surveyId: surveyId)
        XCTAssertTrue(database.presence(surveyId: surveyId).isEmpty)
    }

    func testReferenceTrackReplaceReadDelete() {
        let surveyId = database.ensureDefaultSurvey()
        let points = [
            ReferencePoint(lat: -7.5, lon: 110.4, elevation: 1500, time: 1_000),
            ReferencePoint(lat: -7.501, lon: 110.401, elevation: nil, time: nil)
        ]
        let firstId = database.replaceReferenceTrack(surveyId: surveyId, name: "Strava",
                                                      source: "StravaGPX", points: points)
        XCTAssertGreaterThan(firstId, 0)

        let track = database.referenceTrack(surveyId: surveyId)
        XCTAssertEqual(track?.name, "Strava")
        XCTAssertEqual(track?.source, "StravaGPX")
        XCTAssertEqual(track?.pointCount, 2)

        let stored = database.referencePoints(surveyId: surveyId)
        XCTAssertEqual(stored.count, 2)
        XCTAssertEqual(stored[0].lat, -7.5, accuracy: 1e-9)
        XCTAssertEqual(stored[0].lon, 110.4, accuracy: 1e-9)
        XCTAssertEqual(stored[0].elevation ?? 0, 1500, accuracy: 0.001)
        XCTAssertEqual(stored[0].time, 1_000)
        XCTAssertNil(stored[1].elevation)
        XCTAssertNil(stored[1].time)

        // Impor ulang mengganti jalur sebelumnya, bukan menumpuk.
        let secondId = database.replaceReferenceTrack(
            surveyId: surveyId, name: "Garmin", source: "",
            points: [ReferencePoint(lat: -7.6, lon: 110.5, elevation: nil, time: nil)])
        XCTAssertGreaterThan(secondId, 0)
        XCTAssertEqual(database.referenceTrack(surveyId: surveyId)?.name, "Garmin")
        XCTAssertEqual(database.referencePoints(surveyId: surveyId).count, 1)

        database.deleteReferenceTrack(surveyId: surveyId)
        XCTAssertNil(database.referenceTrack(surveyId: surveyId))
        XCTAssertTrue(database.referencePoints(surveyId: surveyId).isEmpty)
    }

    func testReferenceTrackRejectsEmptyWithoutDroppingPrevious() {
        let surveyId = database.ensureDefaultSurvey()
        _ = database.replaceReferenceTrack(
            surveyId: surveyId, name: "Strava", source: "StravaGPX",
            points: [ReferencePoint(lat: -7.5, lon: 110.4, elevation: nil, time: nil)])

        let rejected = database.replaceReferenceTrack(surveyId: surveyId, name: "Kosong",
                                                      source: "", points: [])
        XCTAssertEqual(rejected, -1)
        XCTAssertEqual(database.referenceTrack(surveyId: surveyId)?.name, "Strava")
        XCTAssertEqual(database.referencePoints(surveyId: surveyId).count, 1)
    }

    func testReferenceTrackCascadesWithSurveyDelete() {
        let surveyId = database.ensureDefaultSurvey()
        _ = database.replaceReferenceTrack(
            surveyId: surveyId, name: "Strava", source: "StravaGPX",
            points: [ReferencePoint(lat: -7.5, lon: 110.4, elevation: nil, time: nil)])
        database.deleteSurvey(surveyId)
        XCTAssertNil(database.referenceTrack(surveyId: surveyId))
        XCTAssertTrue(database.referencePoints(surveyId: surveyId).isEmpty)
    }
}
