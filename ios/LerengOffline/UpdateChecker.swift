import Foundation

/// Manifes pembaruan iOS. Skema sengaja berbeda dari Android (`lereng.update/1`) karena
/// tindakan yang mungkin dilakukan berbeda: iOS tidak dapat memasang IPA sendiri, jadi aplikasi
/// hanya dapat membuka App Store atau TestFlight.
struct IosUpdateInfo: Identifiable, Equatable {
    var build: Int
    var version: String
    var mandatory: Bool
    var storeURL: URL
    var minSupportedBuild: Int
    var notes: [String]
    var publishedAt: String

    var id: Int { build }

    var notesText: String {
        notes.map { "• " + $0 }.joined(separator: "\n")
    }
}

enum UpdateError: Error, Equatable {
    case disabled
    case badSchema(String)
    case badBuild
    case badStoreURL
    case http(Int)
    case transport(String)
    case oversized
    case malformed(String)

    var message: String {
        switch self {
        case .disabled: return "URL manifes pembaruan belum dikonfigurasi."
        case .badSchema(let value): return "Skema manifes tidak dikenal (\(value))."
        case .badBuild: return "Nomor build pada manifes tidak valid."
        case .badStoreURL: return "Tautan toko aplikasi harus memakai HTTPS."
        case .http(let code): return "Server menjawab HTTP \(code)."
        case .transport(let text): return "Gagal menghubungi server: \(text)"
        case .oversized: return "Manifes terlalu besar."
        case .malformed(let text): return text
        }
    }
}

/// Pemeriksaan pembaruan opsional. Aplikasi tetap tidak mengunggah apa pun: hanya satu GET HTTPS
/// ke berkas JSON publik. Semua kegagalan bersifat fail-open — aplikasi tetap dapat dipakai.
enum UpdateChecker {
    static let schema = "lereng.ios-update/1"
    static let maxManifestBytes = 64 * 1024
    static let timeout: TimeInterval = 7

    static func parse(_ data: Data, currentBuild: Int) throws -> IosUpdateInfo? {
        guard data.count <= maxManifestBytes else { throw UpdateError.oversized }

        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            throw UpdateError.malformed("Manifes bukan JSON yang valid.")
        }
        guard let root = object as? [String: Any] else {
            throw UpdateError.malformed("Manifes harus berupa objek JSON.")
        }

        let schemaValue = (root["schema"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        guard schemaValue == schema else {
            throw UpdateError.badSchema(schemaValue)
        }

        guard let build = intValue(root["build"]), build > 0 else {
            throw UpdateError.badBuild
        }

        guard let storeText = storeLink(root), let storeURL = URL(string: storeText),
              storeURL.scheme?.lowercased() == "https" else {
            throw UpdateError.badStoreURL
        }

        guard build > currentBuild else { return nil }

        let minSupported = intValue(root["minSupportedBuild"]) ?? 0
        let mandatory = (root["mandatory"] as? Bool ?? false) || currentBuild < minSupported
        let version = (root["version"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        let notes = noteLines(root["notes"])
        let publishedAt = root["publishedAt"] as? String ?? ""

        return IosUpdateInfo(build: build,
                             version: version.isEmpty ? String(build) : version,
                             mandatory: mandatory,
                             storeURL: storeURL,
                             minSupportedBuild: minSupported,
                             notes: notes,
                             publishedAt: publishedAt)
    }

    static func check(manifestURL: URL?, currentBuild: Int,
                      completion: @escaping (Result<IosUpdateInfo?, UpdateError>) -> Void) {
        guard let manifestURL else {
            completion(.failure(.disabled))
            return
        }
        guard manifestURL.scheme?.lowercased() == "https" else {
            completion(.failure(.badStoreURL))
            return
        }

        var request = URLRequest(url: manifestURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue("LerengOffline-iOS/\(AppConfig.version)", forHTTPHeaderField: "User-Agent")

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(.transport(error.localizedDescription)))
                return
            }
            guard let http = response as? HTTPURLResponse else {
                completion(.failure(.malformed("Server tidak memberi jawaban HTTP.")))
                return
            }
            guard http.statusCode == 200 else {
                completion(.failure(.http(http.statusCode)))
                return
            }
            guard let data else {
                completion(.failure(.malformed("Manifes kosong.")))
                return
            }
            do {
                completion(.success(try parse(data, currentBuild: currentBuild)))
            } catch let updateError as UpdateError {
                completion(.failure(updateError))
            } catch {
                completion(.failure(.malformed(error.localizedDescription)))
            }
        }.resume()
    }

    // MARK: - Pembantu

    private static func intValue(_ raw: Any?) -> Int? {
        if let number = raw as? Int { return number }
        if let number = raw as? NSNumber { return number.intValue }
        if let text = raw as? String { return Int(text.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    /// `appStoreUrl` diutamakan; `testFlightUrl` dipakai bila App Store belum tersedia.
    private static func storeLink(_ root: [String: Any]) -> String? {
        for key in ["appStoreUrl", "testFlightUrl"] {
            let text = (root[key] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return text }
        }
        return nil
    }

    private static func noteLines(_ raw: Any?) -> [String] {
        if let array = raw as? [Any] {
            return array.compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        }
        if let text = raw as? String, !text.isEmpty {
            return text.components(separatedBy: .newlines)
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        }
        return []
    }
}
