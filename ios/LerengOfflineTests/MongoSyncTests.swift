import XCTest
@testable import LerengOffline

final class MongoSyncTests: XCTestCase {
    private let start: Int64 = 1_700_000_000_000

    private func point(id: Int64, waypoint: Bool) -> TrackPoint {
        TrackPoint(id: id,
                   surveyId: 7,
                   time: start + id * 1000,
                   lat: -7.7828,
                   lon: 110.3695,
                   altitude: 1200.5,
                   horizontalAccuracy: 8,
                   verticalAccuracy: 12,
                   pressure: 900,
                   baroRelativeAltitude: 15.5,
                   source: "iOS Core Location",
                   label: "uji",
                   participantId: "P001",
                   participantAlias: "Bagas",
                   groupCode: "ROMBONGAN1",
                   isWaypoint: waypoint)
    }

    private func context() -> ExportContext {
        ExportContext(project: "Gunung Uji",
                      points: [point(id: 1, waypoint: false), point(id: 2, waypoint: true)],
                      presence: [Presence(id: 1, surveyId: 7, time: start + 5000,
                                          observerParticipantId: "P001",
                                          seenParticipantId: "P002",
                                          groupCode: "ROMBONGAN1")],
                      groupCode: "ROMBONGAN1",
                      participantId: "P001",
                      participantAlias: "Bagas",
                      appVersion: "1.3.0")
    }

    func testEndpointBaseMenjadiApiIngest() throws {
        let url = try MongoSync.normalizeEndpoint("https://proyek.vercel.app")
        XCTAssertEqual(url.absoluteString, "https://proyek.vercel.app/api/ingest")
    }

    func testEndpointLengkapDipertahankan() throws {
        let url = try MongoSync.normalizeEndpoint(" https://proyek.vercel.app/api/ingest/ ")
        XCTAssertEqual(url.absoluteString, "https://proyek.vercel.app/api/ingest")
    }

    func testEndpointHttpDitolak() {
        XCTAssertThrowsError(try MongoSync.normalizeEndpoint("http://proyek.vercel.app/api/ingest"))
    }

    func testEndpointDenganKredensialDitolak() {
        XCTAssertThrowsError(try MongoSync.normalizeEndpoint("https://user:pass@proyek.vercel.app/api/ingest"))
    }

    func testDokumenBerisiSemuaJenisDanIdStabil() {
        let first = MongoSync.documents(clientId: "client-a", surveyId: 7,
                                        context: context(), platform: "ios")
        let second = MongoSync.documents(clientId: "client-a", surveyId: 7,
                                         context: context(), platform: "ios")
        XCTAssertEqual(first.count, 4)
        XCTAssertEqual(first.count, second.count)

        let firstIds = first.compactMap { ($0["properties"] as? [String: Any])?["record_id"] as? String }
        let secondIds = second.compactMap { ($0["properties"] as? [String: Any])?["record_id"] as? String }
        XCTAssertEqual(firstIds, secondIds)

        let types = first.compactMap { ($0["properties"] as? [String: Any])?["record_type"] as? String }
        XCTAssertEqual(types, ["project", "track", "waypoint", "presence"])
        XCTAssertTrue(first[0]["geometry"] is NSNull)
        XCTAssertTrue(first[3]["geometry"] is NSNull)

        let geometry = first[1]["geometry"] as? [String: Any]
        XCTAssertEqual(geometry?["type"] as? String, "Point")
        XCTAssertEqual((geometry?["coordinates"] as? [Double])?.count, 3)
    }

    func testTanpaPerubahanProyekMetadataTidakDikirim() {
        let documents = MongoSync.documents(clientId: "client-a", surveyId: 7,
                                            context: context(), platform: "ios",
                                            includeProject: false)
        XCTAssertEqual(documents.count, 3)
        let types = documents.compactMap {
            ($0["properties"] as? [String: Any])?["record_type"] as? String
        }
        XCTAssertEqual(types, ["track", "waypoint", "presence"])
    }

    func testPresenceMemakaiHashStabil() {
        let documents = MongoSync.documents(clientId: "client-a", surveyId: 7,
                                            context: context(), platform: "ios",
                                            includeProject: false)
        let presence = documents.last
        let recordId = (presence?["properties"] as? [String: Any])?["record_id"] as? String
        XCTAssertNotNil(recordId)
        XCTAssertTrue(recordId?.hasPrefix("client-a:7:presence:") == true)
        XCTAssertNotEqual(recordId, "client-a:7:presence:1")
    }
}
