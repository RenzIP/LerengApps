import Foundation

/// Pembaca dan penulis CSV minimal, setara dengan `CsvCodec.java` di Android.
///
/// Aturan penting saat membaca: tanda kutip pembuka hanya sah di awal sel.
/// Tanpa aturan ini, label seperti `Renz "The Hiker"` membuat seluruh kolom
/// sesudahnya bergeser.
enum CsvCodec {
    static func parseLine(_ line: String) -> [String] {
        var result: [String] = []
        var value = ""
        var quoted = false
        let characters = Array(line)
        var index = 0

        while index < characters.count {
            let character = characters[index]
            if character == "\"" {
                if quoted {
                    if index + 1 < characters.count, characters[index + 1] == "\"" {
                        value.append("\"")
                        index += 1
                    } else {
                        quoted = false
                    }
                } else if value.isEmpty {
                    quoted = true
                } else {
                    value.append("\"")
                }
            } else if character == "," && !quoted {
                result.append(value)
                value = ""
            } else {
                value.append(character)
            }
            index += 1
        }

        result.append(value)
        return result
    }

    static func escape(_ value: String) -> String {
        if value.contains(",") || value.contains("\"")
            || value.contains("\n") || value.contains("\r") {
            return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return value
    }
}
