import XCTest
@testable import LerengOffline

final class UpdateCheckerTests: XCTestCase {
    private func manifest(build: Int,
                          version: String = "1.1.0",
                          mandatory: Bool = false,
                          minSupported: Int = 0,
                          store: String = "https://apps.apple.com/app/id1234567890") -> Data {
        let object: [String: Any] = [
            "schema": UpdateChecker.schema,
            "build": build,
            "version": version,
            "mandatory": mandatory,
            "minSupportedBuild": minSupported,
            "appStoreUrl": store,
            "notes": ["Perbaikan peta", "Ekspor lebih cepat"],
            "publishedAt": "2026-11-10T00:00:00Z"
        ]
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    func testNewerBuildReported() throws {
        let info = try UpdateChecker.parse(manifest(build: 3), currentBuild: 2)
        XCTAssertNotNil(info)
        XCTAssertEqual(info?.build, 3)
        XCTAssertEqual(info?.version, "1.1.0")
        XCTAssertEqual(info?.notes.count, 2)
        XCTAssertFalse(info?.mandatory ?? true)
        XCTAssertEqual(info?.storeURL.scheme, "https")
    }

    func testSameBuildIsNotAnUpdate() throws {
        XCTAssertNil(try UpdateChecker.parse(manifest(build: 2), currentBuild: 2))
        XCTAssertNil(try UpdateChecker.parse(manifest(build: 1), currentBuild: 2))
    }

    func testMandatoryFlagAndMinimumBuild() throws {
        let flagged = try UpdateChecker.parse(manifest(build: 5, mandatory: true), currentBuild: 2)
        XCTAssertEqual(flagged?.mandatory, true)

        let belowMinimum = try UpdateChecker.parse(manifest(build: 5, minSupported: 4), currentBuild: 2)
        XCTAssertEqual(belowMinimum?.mandatory, true)
    }

    func testWrongSchemaThrows() {
        let data = (try? JSONSerialization.data(withJSONObject: [
            "schema": "lereng.update/1",
            "build": 5,
            "appStoreUrl": "https://apps.apple.com/app/id1"
        ])) ?? Data()
        XCTAssertThrowsError(try UpdateChecker.parse(data, currentBuild: 1)) { error in
            guard let updateError = error as? UpdateError, case .badSchema = updateError else {
                return XCTFail("Galat yang diharapkan: badSchema, diterima \(error)")
            }
        }
    }

    func testMissingStoreLinkThrows() {
        let data = (try? JSONSerialization.data(withJSONObject: [
            "schema": UpdateChecker.schema,
            "build": 5
        ])) ?? Data()
        XCTAssertThrowsError(try UpdateChecker.parse(data, currentBuild: 1)) { error in
            XCTAssertEqual(error as? UpdateError, .badStoreURL)
        }
    }

    func testDownloadUrlPreferredForSideload() throws {
        let data = (try? JSONSerialization.data(withJSONObject: [
            "schema": UpdateChecker.schema,
            "build": 7,
            "version": "1.2.0",
            "downloadUrl": "https://github.com/RenzIP/LerengApps/releases/tag/ios-v1.2.0",
            "appStoreUrl": "https://apps.apple.com/app/id0000000000",
            "notes": ["Perbaikan BLE"]
        ])) ?? Data()
        let info = try XCTUnwrap(try UpdateChecker.parse(data, currentBuild: 1))
        XCTAssertEqual(info.storeURL.absoluteString, "https://github.com/RenzIP/LerengApps/releases/tag/ios-v1.2.0")
        XCTAssertEqual(info.version, "1.2.0")
    }

    func testInsecureStoreLinkThrows() {
        XCTAssertThrowsError(try UpdateChecker.parse(manifest(build: 9, store: "http://example.com/app"),
                                                     currentBuild: 1)) { error in
            XCTAssertEqual(error as? UpdateError, .badStoreURL)
        }
    }

    func testNonJsonThrows() {
        XCTAssertThrowsError(try UpdateChecker.parse(Data("bukan json".utf8), currentBuild: 1))
    }

    func testMandatoryNotesTextIsBulleted() throws {
        let info = try XCTUnwrap(try UpdateChecker.parse(manifest(build: 4), currentBuild: 1))
        XCTAssertEqual(info.notesText, "• Perbaikan peta\n• Ekspor lebih cepat")
    }
}
