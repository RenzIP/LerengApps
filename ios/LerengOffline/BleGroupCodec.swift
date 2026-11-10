import Foundation
import CryptoKit

/// Wire format BLE yang sama dengan aplikasi Android.
///
/// Payload 10 byte: 6 byte pertama adalah potongan SHA-256 dari kode outing, 4 byte terakhir
/// ID peserta sekali-jalan. Tidak ada MAC, RSSI, jarak, atau posisi yang dipertukarkan.
enum BleGroupCodec {
    static let serviceUUIDText = "9d7a4f80-6f2b-4c21-9a11-4c6572656e67"
    static let identityCharacteristicUUIDText = "1a6cde71-9d0a-4c4e-a274-4c6572656e67"
    /// Company ID untuk manufacturer data di scan response (dipakai iOS untuk membaca Android).
    static let manufacturerCompanyId: UInt16 = 0xFFFF

    static let groupTagBytes = 6
    static let compactTagBytes = 4
    static let idBytes = 4
    static let payloadBytes = groupTagBytes + idBytes
    /// Payload ringkas 8 byte untuk manufacturer data: Android hanya punya sisa 10 byte di scan
    /// response setelah service UUID 128-bit (18 byte) dan batas paket 31 byte.
    static let compactPayloadBytes = compactTagBytes + idBytes

    private static let codeAlphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

    static func normalizeGroupCode(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    static func isValidGroupCode(_ value: String) -> Bool {
        normalizeGroupCode(value).range(of: "^[A-Z0-9]{8,16}$", options: .regularExpression) != nil
    }

    static func newGroupCode() -> String {
        String((0..<10).map { _ in codeAlphabet.randomElement() ?? "A" })
    }

    static func newParticipantId() -> String {
        String(format: "%08X", UInt32.random(in: UInt32.min...UInt32.max))
    }

    static func isValidParticipantId(_ value: String) -> Bool {
        value.range(of: "^[0-9a-fA-F]{8}$", options: .regularExpression) != nil
    }

    static func payload(groupCode: String, participantId: String) -> Data? {
        let normalized = normalizeGroupCode(groupCode)
        guard isValidGroupCode(normalized), isValidParticipantId(participantId) else { return nil }
        let digest = SHA256.hash(data: Data(normalized.utf8))
        var data = Data(digest.prefix(groupTagBytes))
        data.append(contentsOf: hexBytes(participantId))
        return data
    }

    /// Payload ringkas 8 byte untuk manufacturer data (bentuk yang disiarkan Android).
    static func compactPayload(groupCode: String, participantId: String) -> Data? {
        let normalized = normalizeGroupCode(groupCode)
        guard isValidGroupCode(normalized), isValidParticipantId(participantId) else { return nil }
        let digest = SHA256.hash(data: Data(normalized.utf8))
        var data = Data(digest.prefix(compactTagBytes))
        data.append(contentsOf: hexBytes(participantId))
        return data
    }

    /// Menerima payload 10 byte (Service Data Android) maupun 8 byte (manufacturer data).
    static func parsePeerId(payload: Data, expectedGroupCode: String) -> String? {
        guard isValidGroupCode(expectedGroupCode) else { return nil }
        let tagBytes: Int
        switch payload.count {
        case payloadBytes: tagBytes = groupTagBytes
        case compactPayloadBytes: tagBytes = compactTagBytes
        default: return nil
        }
        let digest = SHA256.hash(data: Data(normalizeGroupCode(expectedGroupCode).utf8))
        guard Data(digest.prefix(tagBytes)) == payload.prefix(tagBytes) else { return nil }
        return payload.suffix(idBytes).map { String(format: "%02X", $0) }.joined()
    }

    /// Manufacturer data dari Android: 2 byte company ID (little endian) + payload 8/10 byte.
    static func payload(fromManufacturerData data: Data) -> Data? {
        guard data.count == 2 + payloadBytes || data.count == 2 + compactPayloadBytes else { return nil }
        let company = UInt16(data[data.startIndex]) | (UInt16(data[data.startIndex + 1]) << 8)
        guard company == manufacturerCompanyId else { return nil }
        return data.subdata(in: (data.startIndex + 2)..<data.endIndex)
    }

    static func manufacturerData(payload: Data) -> Data? {
        guard payload.count == payloadBytes || payload.count == compactPayloadBytes else { return nil }
        var data = Data([UInt8(manufacturerCompanyId & 0xFF), UInt8(manufacturerCompanyId >> 8)])
        data.append(payload)
        return data
    }

    static func hexBytes(_ hex: String) -> [UInt8] {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex, let next = hex.index(index, offsetBy: 1, limitedBy: hex.endIndex) {
            let pair = hex[index...next]
            if let value = UInt8(pair, radix: 16) { bytes.append(value) }
            index = hex.index(after: next)
        }
        return bytes
    }
}
