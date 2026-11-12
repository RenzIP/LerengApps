import Foundation

/// Pembaca GPX untuk impor ke proyek: `<trkpt>`, `<rtept>`, dan `<wpt>`.
///
/// Berbeda dari `GpxReferenceParser` yang hanya memakai geometri jalur
/// pembanding, pembaca ini menyimpan waypoint, nama titik, dan referensi
/// elevasi yang dicantumkan file. Tidak ada konversi datum dan tidak ada
/// unggahan: elevasi dibaca apa adanya, lalu diberi label oleh pemanggil.
enum GpxTrackParser {
    static let maxBytes = 16 * 1024 * 1024

    struct ParsedPoint {
        let lat: Double
        let lon: Double
        let elevation: Double?
        let time: Int64?
        let isWaypoint: Bool
        let name: String
    }

    struct Result {
        let points: [ParsedPoint]
        let skipped: Int
        let creator: String
        let declaredReference: String

        var waypointCount: Int { points.filter { $0.isWaypoint }.count }
        var trackCount: Int { points.count - waypointCount }
        var hasElevation: Bool { points.contains { $0.elevation != nil } }
        var hasTime: Bool { points.contains { $0.time != nil } }
    }

    enum ParseError: LocalizedError {
        case tooLarge
        case doctype
        case notGpx
        case malformed(String)
        case empty

        var errorDescription: String? {
            switch self {
            case .tooLarge: return "File GPX lebih dari 16 MB."
            case .doctype: return "GPX dengan DOCTYPE tidak didukung."
            case .notGpx: return "File bukan GPX (akar <gpx> tidak ditemukan)."
            case .malformed(let message): return "XML tidak valid: \(message)"
            case .empty: return "File GPX kosong atau tidak dapat dibaca."
            }
        }
    }

    static func parse(url: URL) throws -> Result {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        return try parse(data: data)
    }

    static func parse(data: Data) throws -> Result {
        guard !data.isEmpty else { throw ParseError.empty }
        guard data.count <= maxBytes else { throw ParseError.tooLarge }

        let head = String(decoding: data.prefix(4096), as: UTF8.self).lowercased()
        if head.contains("<!doctype") { throw ParseError.doctype }

        let delegate = Delegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.shouldProcessNamespaces = false
        parser.shouldResolveExternalEntities = false
        guard parser.parse() else {
            let message = delegate.failureMessage
                ?? parser.parserError?.localizedDescription
                ?? "tidak diketahui"
            throw ParseError.malformed(message)
        }
        guard delegate.foundRoot else { throw ParseError.notGpx }
        return Result(points: delegate.points,
                      skipped: delegate.skipped,
                      creator: delegate.creator,
                      declaredReference: delegate.declaredReference)
    }

    private final class Delegate: NSObject, XMLParserDelegate {
        private struct Builder {
            var lat: Double?
            var lon: Double?
            var elevation: Double?
            var time: Int64?
            var name = ""
        }

        private(set) var points: [ParsedPoint] = []
        private(set) var skipped = 0
        private(set) var creator = ""
        private(set) var declaredReference = ""
        private(set) var foundRoot = false
        private(set) var failureMessage: String?

        private var depth = 0
        private var builder: Builder?
        private var builderIsWaypoint = false
        private var field = ""
        private var buffer = ""

        private let iso = ISO8601DateFormatter()
        private let isoWithFraction: ISO8601DateFormatter = {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return formatter
        }()

        func parser(_ parser: XMLParser, didStartElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?,
                    attributes: [String: String]) {
            let name = local(elementName)
            let lowered = name.lowercased()
            depth += 1
            if depth == 1 {
                foundRoot = lowered == "gpx"
                if foundRoot { creator = attributes["creator"] ?? "" }
                return
            }
            guard foundRoot else { return }

            switch lowered {
            case "trkpt", "rtept", "wpt":
                builder = Builder(lat: Double(attributes["lat"]?.trimmingCharacters(in: .whitespaces) ?? ""),
                                  lon: Double(attributes["lon"]?.trimmingCharacters(in: .whitespaces) ?? ""))
                builderIsWaypoint = lowered == "wpt"
            case "ele", "time", "name", "desc", "cmt", "elevationreference":
                // <name> milik <trk> juga muncul, tetapi hanya direkam saat
                // pembaca sedang berada di dalam satu titik.
                if lowered == "elevationreference" || builder != nil {
                    field = lowered
                    buffer = ""
                }
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            guard !field.isEmpty else { return }
            buffer += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?) {
            let name = local(elementName).lowercased()
            defer { depth = max(0, depth - 1) }

            if name == "elevationreference" {
                if field == name {
                    let value = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !value.isEmpty { declaredReference = value }
                    field = ""
                    buffer = ""
                }
                return
            }

            if name == "ele" || name == "time" || name == "name" || name == "desc" || name == "cmt" {
                if builder != nil, field == name {
                    switch name {
                    case "ele": builder?.elevation = parseElevation(buffer)
                    case "time": builder?.time = parseTime(buffer)
                    default:
                        let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
                        if builder?.name.isEmpty == true { builder?.name = text }
                    }
                    field = ""
                    buffer = ""
                }
                return
            }

            guard name == "trkpt" || name == "rtept" || name == "wpt",
                  let completed = builder else { return }
            builder = nil
            field = ""
            buffer = ""

            guard let lat = completed.lat, let lon = completed.lon,
                  lat.isFinite, lon.isFinite, lat >= -90, lat <= 90,
                  lon >= -180, lon <= 180 else {
                skipped += 1
                return
            }
            points.append(ParsedPoint(lat: lat, lon: lon,
                                      elevation: completed.elevation,
                                      time: completed.time,
                                      isWaypoint: builderIsWaypoint,
                                      name: completed.name))
        }

        func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
            failureMessage = parseError.localizedDescription
        }

        private func parseElevation(_ text: String) -> Double? {
            guard let value = Double(text.trimmingCharacters(in: .whitespaces)),
                  value.isFinite, value >= -12_000, value <= 100_000 else { return nil }
            return value
        }

        private func parseTime(_ text: String) -> Int64? {
            let clean = text.trimmingCharacters(in: .whitespaces)
            guard !clean.isEmpty else { return nil }
            if let milliseconds = Int64(clean) { return milliseconds }
            let date = iso.date(from: clean) ?? isoWithFraction.date(from: clean)
            guard let date else { return nil }
            return Int64(date.timeIntervalSince1970 * 1000)
        }

        private func local(_ name: String) -> String {
            guard let colon = name.lastIndex(of: ":") else { return name }
            return String(name[name.index(after: colon)...])
        }
    }
}

/// Label referensi elevasi yang boleh dipilih saat impor. Aplikasi tidak
/// pernah mengonversi nilai; label hanya mencegah datum tercampur.
enum ImportDatum {
    static let wgs84Ellipsoid = "WGS84 ellipsoid"
    static let egm96 = "EGM96"
    static let meanSeaLevel = "Mean sea level (orthometric)"
    static let none = ""
    static let unrecorded = "not recorded"

    struct Option: Identifiable {
        let label: String
        let value: String

        var id: String { value }
    }

    static let options: [Option] = [
        Option(label: "WGS84 ellipsoid (Location/GNSS Android)", value: wgs84Ellipsoid),
        Option(label: "EGM96 (geoid)", value: egm96),
        Option(label: "Mean sea level / orthometric (mis. iOS)", value: meanSeaLevel),
        Option(label: "Abaikan elevasi (buang <ele>)", value: none)
    ]
}
