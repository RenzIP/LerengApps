import SwiftUI
import UIKit

/// Token desain aplikasi. Warna mengikuti mode terang/gelap sistem.
enum Theme {
    static let accent = Color(red: 0.07, green: 0.52, blue: 0.45)
    static let accentSoft = Color(red: 0.07, green: 0.52, blue: 0.45).opacity(0.12)
    static let danger = Color(red: 0.78, green: 0.24, blue: 0.22)
    static let warning = Color(red: 0.80, green: 0.50, blue: 0.10)

    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    static let background = Color(uiColor: .systemGroupedBackground)
    static let hairline = Color(uiColor: .separator)

    static let radius: CGFloat = 18
    static let padding: CGFloat = 16
}

/// Pemformatan angka dan waktu dengan gaya Indonesia.
enum Format {
    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "id_ID")
        formatter.timeZone = .current
        formatter.dateFormat = "d MMM yyyy HH:mm:ss"
        return formatter
    }()

    private static let shortTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "id_ID")
        formatter.timeZone = .current
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    static func distance(_ meters: Double) -> String {
        guard meters.isFinite else { return "—" }
        if meters >= 1000 { return String(format: "%.2f km", meters / 1000) }
        return String(format: "%.0f m", meters)
    }

    static func meters(_ value: Double) -> String {
        guard value.isFinite else { return "—" }
        return String(format: "%.0f m", value)
    }

    static func decimal(_ value: Double, digits: Int = 5) -> String {
        guard value.isFinite else { return "—" }
        return String(format: "%.\(digits)f", value)
    }

    static func duration(milliseconds: Int64) -> String {
        guard milliseconds > 0 else { return "0 mnt" }
        let totalSeconds = milliseconds / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 { return "\(hours) j \(minutes) mnt" }
        if minutes > 0 { return "\(minutes) mnt \(seconds) dtk" }
        return "\(seconds) dtk"
    }

    static func time(_ milliseconds: Int64) -> String {
        timeFormatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1000))
    }

    static func shortTime(_ milliseconds: Int64) -> String {
        shortTimeFormatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1000))
    }
}

// MARK: - Komponen bersama

struct SectionCard<Content: View>: View {
    private let title: String?
    private let content: Content

    init(_ title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                Text(title.uppercased())
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .tracking(0.6)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.padding)
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                .stroke(Theme.hairline.opacity(0.5), lineWidth: 0.5)
        )
    }
}

struct StatTile: View {
    let label: String
    let value: String
    var footnote: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
            Text(value)
                .font(.title3.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let footnote {
                Text(footnote)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct StatusChip: View {
    let text: String
    let systemImage: String
    var tint: Color = Theme.accent

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: systemImage).font(.caption2.weight(.bold))
            Text(text).font(.caption.weight(.medium))
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(tint.opacity(0.14))
        .foregroundStyle(tint)
        .clipShape(Capsule())
    }
}

struct ExportItem: Identifiable {
    let id = UUID()
    let url: URL
    let title: String
}

struct ExportSheet: View {
    let item: ExportItem
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "doc.badge.arrow.up")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(Theme.accent)
                Text(item.title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(item.url.lastPathComponent)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Text("Berkas ditulis di folder aplikasi pada perangkat ini. Bagikan hanya bila memang diperlukan.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                ShareLink(item: item.url) {
                    Label("Bagikan atau simpan berkas", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.accent)
                Spacer()
            }
            .padding(24)
            .navigationTitle("Ekspor")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Tutup") { dismiss() }
                }
            }
        }
    }
}
