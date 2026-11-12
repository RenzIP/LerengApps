import Foundation

/// Pembaca GPX minimal untuk jalur pembanding.
///
/// `<trkpt>` dan `<rtept>` dipakai sebagai geometri; sebagian aplikasi
/// mengekspor jalur sebagai rute, bukan track. `<wpt>` dihitung lalu dilewati
/// karena pembanding tidak menambahkan waypoint ke proyek. Tidak ada unggahan
/// dan tidak ada konversi datum: elevasi file dibaca apa adanya, lalu diabaikan
/// oleh peta.
enum GpxReferenceParser {
    static let maxBytes = 16 * 1024 * 1024

    struct Result {
        let points: [ReferencePoint]
        let skipped: Int
        let waypointCount: Int
        let creator: String
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
                      waypointCount: delegate.waypointCount,
                      creator: delegate.creator)
    }

    private final class Delegate: NSObject, XMLParserDelegate {
        private struct Builder {
            var lat: Double?
            var lon: Double?
            var elevation: Double?
            var time: Int64?
        }

        private(set) var points: [ReferencePoint] = []
        private(set) var skipped = 0
        private(set) var waypointCount = 0
        private(set) var creator = ""
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
            depth += 1
            if depth == 1 {
                foundRoot = name.lowercased() == "gpx"
                if foundRoot { creator = attributes["creator"] ?? "" }
                return
            }
            guard foundRoot else { return }

            switch name.lowercased() {
            case "trkpt", "rtept", "wpt":
                builder = Builder(lat: Double(attributes["lat"]?.trimmingCharacters(in: .whitespaces) ?? ""),
                                  lon: Double(attributes["lon"]?.trimmingCharacters(in: .whitespaces) ?? ""),
                                  elevation: nil,
                                  time: nil)
                builderIsWaypoint = name.lowercased() == "wpt"
            case "ele", "time":
                guard builder != nil else { return }
                field = name.lowercased()
                buffer = ""
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            guard builder != nil, !field.isEmpty else { return }
            buffer += string
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?) {
            let name = local(elementName).lowercased()
            defer { depth = max(0, depth - 1) }

            if name == "ele" || name == "time" {
                if builder != nil, field == name {
                    if name == "ele" { builder?.elevation = parseElevation(buffer) }
                    else { builder?.time = parseTime(buffer) }
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

            if builderIsWaypoint {
                waypointCount += 1
                return
            }
            guard let lat = completed.lat, let lon = completed.lon,
                  lat.isFinite, lon.isFinite, lat >= -90, lat <= 90, lon >= -180, lon <= 180 else {
                skipped += 1
                return
            }
            points.append(ReferencePoint(lat: lat, lon: lon,
                                         elevation: completed.elevation, time: completed.time))
        }

        func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
            failureMessage = parseError.localizedDescription
        }

        private func parseElevation(_ text: String) -> Double? {
            guard let value = Double(text.trimmingCharacters(in: .whitespaces)), value.isFinite else {
                return nil
            }
            return value
        }

        private func parseTime(_ text: String) -> Int64? {
            let clean = text.trimmingCharacters(in: .whitespaces)
            guard !clean.isEmpty else { return nil }
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
