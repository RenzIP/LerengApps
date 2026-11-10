import XCTest
@testable import LerengOffline

final class BleGroupCodecTests: XCTestCase {
    func testNormalizeAndValidate() {
        XCTAssertEqual(BleGroupCodec.normalizeGroupCode(" gunung77 "), "GUNUNG77")
        XCTAssertTrue(BleGroupCodec.isValidGroupCode("GUNUNG77"))
        XCTAssertTrue(BleGroupCodec.isValidGroupCode("ABCD2345"))
        XCTAssertFalse(BleGroupCodec.isValidGroupCode("pendek"))
        XCTAssertFalse(BleGroupCodec.isValidGroupCode("kode-outing"))
    }

    func testParticipantIdValidation() {
        XCTAssertTrue(BleGroupCodec.isValidParticipantId("1A2B3C4D"))
        XCTAssertTrue(BleGroupCodec.isValidParticipantId("deadbeef"))
        XCTAssertFalse(BleGroupCodec.isValidParticipantId("1A2B3C4"))
        XCTAssertFalse(BleGroupCodec.isValidParticipantId("ZZZZZZZZ"))
    }

    func testPayloadRoundTrip() throws {
        let payload = try XCTUnwrap(BleGroupCodec.payload(groupCode: "gunung77", participantId: "1A2B3C4D"))
        XCTAssertEqual(payload.count, BleGroupCodec.payloadBytes)
        XCTAssertEqual(BleGroupCodec.parsePeerId(payload: payload, expectedGroupCode: "GUNUNG77"), "1A2B3C4D")
    }

    func testPayloadRejectsOtherGroup() throws {
        let payload = try XCTUnwrap(BleGroupCodec.payload(groupCode: "GUNUNG77", participantId: "1A2B3C4D"))
        XCTAssertNil(BleGroupCodec.parsePeerId(payload: payload, expectedGroupCode: "GUNUNG88"))
    }

    func testPayloadRejectsInvalidInput() {
        XCTAssertNil(BleGroupCodec.payload(groupCode: "x", participantId: "1A2B3C4D"))
        XCTAssertNil(BleGroupCodec.payload(groupCode: "GUNUNG77", participantId: "bukanhex"))
    }

    func testManufacturerDataRoundTrip() throws {
        let payload = try XCTUnwrap(BleGroupCodec.payload(groupCode: "GUNUNG77", participantId: "0A0B0C0D"))
        let manufacturer = try XCTUnwrap(BleGroupCodec.manufacturerData(payload: payload))
        XCTAssertEqual(manufacturer.count, 12)
        XCTAssertEqual(manufacturer[manufacturer.startIndex], 0xFF)
        XCTAssertEqual(manufacturer[manufacturer.startIndex + 1], 0xFF)

        let parsed = try XCTUnwrap(BleGroupCodec.payload(fromManufacturerData: manufacturer))
        XCTAssertEqual(parsed, payload)
        XCTAssertEqual(BleGroupCodec.parsePeerId(payload: parsed, expectedGroupCode: "GUNUNG77"), "0A0B0C0D")
    }

    func testManufacturerDataRejectsWrongCompany() {
        let bogus = Data([0x4C, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
        XCTAssertNil(BleGroupCodec.payload(fromManufacturerData: bogus))
        XCTAssertNil(BleGroupCodec.payload(fromManufacturerData: Data([0xFF, 0xFF])))
    }

    func testCompactManufacturerDataFromAndroid() throws {
        let compact = try XCTUnwrap(BleGroupCodec.compactPayload(groupCode: "gunung77", participantId: "1A2B3C4D"))
        XCTAssertEqual(compact.count, BleGroupCodec.compactPayloadBytes)
        XCTAssertEqual(compact.count, 8)
        let manufacturer = try XCTUnwrap(BleGroupCodec.manufacturerData(payload: compact))
        XCTAssertEqual(manufacturer.count, 10)
        let parsed = try XCTUnwrap(BleGroupCodec.payload(fromManufacturerData: manufacturer))
        XCTAssertEqual(BleGroupCodec.parsePeerId(payload: parsed, expectedGroupCode: "GUNUNG77"), "1A2B3C4D")
    }

    func testCompactPayloadRejectsOtherGroupAndLengths() throws {
        let compact = try XCTUnwrap(BleGroupCodec.compactPayload(groupCode: "GUNUNG77", participantId: "1A2B3C4D"))
        XCTAssertNil(BleGroupCodec.parsePeerId(payload: compact, expectedGroupCode: "GUNUNG88"))
        XCTAssertNil(BleGroupCodec.parsePeerId(payload: compact + Data([0x00]), expectedGroupCode: "GUNUNG77"))
        XCTAssertNil(BleGroupCodec.payload(fromManufacturerData: Data([0xFF, 0xFF, 0x01])))
        XCTAssertNil(BleGroupCodec.compactPayload(groupCode: "pendek", participantId: "1A2B3C4D"))
    }

    func testNewIdentifiers() {
        let code = BleGroupCodec.newGroupCode()
        XCTAssertEqual(code.count, 10)
        XCTAssertTrue(BleGroupCodec.isValidGroupCode(code))
        XCTAssertTrue(BleGroupCodec.isValidParticipantId(BleGroupCodec.newParticipantId()))
    }
}
