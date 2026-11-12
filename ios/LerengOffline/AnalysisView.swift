import SwiftUI
import UniformTypeIdentifiers

struct ElevationSample: Identifiable {
    let id: Int
    let distance: Double
    let altitude: Double
}

struct PeerSummary: Identifiable {
    let id: String
    let sightings: Int
    let firstSeen: Int64
    let lastSeen: Int64
}

struct AnalysisView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingReferenceImporter = false
    @State private var showingDeleteReference = false
    @State private var showingCustomInterval = false
    @State private var customIntervalText = ""
    /// 0 = otomatis; nilai lain = interval kontur dalam meter.
    @AppStorage("contourIntervalMeters") private var contourInterval: Double = 10

    private var summary: RouteSummary { model.summary }

    private var elevationSamples: [ElevationSample] {
        let track = model.points
            .filter { !$0.isWaypoint && $0.hasValidCoordinate && $0.altitude != nil }
            .sorted { $0.time < $1.time }
        guard track.count >= 2 else { return [] }
        guard Set(track.map { $0.elevationReference }).count == 1 else { return [] }

        var samples: [ElevationSample] = []
        var distance = 0.0
        for (index, point) in track.enumerated() {
            if index > 0 { distance += Geo.distanceMeters(track[index - 1], point) }
            guard let altitude = point.altitude else { continue }
            samples.append(ElevationSample(id: index, distance: distance, altitude: altitude))
        }
        return samples
    }

    private var peers: [PeerSummary] {
        var buckets: [String: (count: Int, first: Int64, last: Int64)] = [:]
        for event in model.presence {
            if var existing = buckets[event.seenParticipantId] {
                existing.count += 1
                existing.first = min(existing.first, event.time)
                existing.last = max(existing.last, event.time)
                buckets[event.seenParticipantId] = existing
            } else {
                buckets[event.seenParticipantId] = (1, event.time, event.time)
            }
        }
        return buckets.map { PeerSummary(id: $0.key, sightings: $0.value.count,
                                         firstSeen: $0.value.first, lastSeen: $0.value.last) }
            .sorted { $0.lastSeen > $1.lastSeen }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    mapCard
                    referenceCard
                    profileCard
                    presenceCard
                }
                .padding(16)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Analisis")
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(isPresented: $showingReferenceImporter,
                          allowedContentTypes: referenceTypes,
                          allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { model.importReferenceGPX(from: url) }
                case .failure(let error):
                    model.alertMessage = "Tidak dapat membuka file: \(error.localizedDescription)"
                }
            }
            .confirmationDialog("Pakai sebagai jalur pembanding?",
                                isPresented: pendingImportBinding,
                                titleVisibility: .visible) {
                Button("Pakai") { model.confirmReferenceImport() }
                Button("Batal", role: .cancel) { model.cancelReferenceImport() }
            } message: {
                Text(model.pendingReferenceImport?.message ?? "")
            }
            .confirmationDialog("Hapus jalur pembanding?",
                                isPresented: $showingDeleteReference,
                                titleVisibility: .visible) {
                Button("Hapus", role: .destructive) { model.deleteReferenceTrack() }
                Button("Batal", role: .cancel) {}
            } message: {
                Text("Hanya jalur pembanding yang dihapus. Titik rekaman aplikasi tidak terpengaruh.")
            }
            .alert("Interval kontur (meter)", isPresented: $showingCustomInterval) {
                TextField("10", text: $customIntervalText)
                    .keyboardType(.decimalPad)
                Button("Simpan") { applyCustomInterval() }
                Button("Batal", role: .cancel) {}
            } message: {
                Text("Isi 1–200 m. Garis kontur dan pita warna mengikuti selisih setinggi ini.")
            }
        }
    }

    /// Interval efektif: pilihan pengguna, atau otomatis dari rentang elevasi.
    private var effectiveInterval: Double {
        if contourInterval > 0 { return contourInterval }
        let altitudes = elevationSamples.map { $0.altitude }
        let span = (altitudes.max() ?? 0) - (altitudes.min() ?? 0)
        return ElevationChart.automaticInterval(span: span)
    }

    private var isCustomInterval: Bool {
        contourInterval > 0 && ![5, 10, 20].contains(contourInterval)
    }

    private var customChipTitle: String {
        guard isCustomInterval else { return "Lain…" }
        return contourInterval == contourInterval.rounded()
            ? String(format: "%.0f m", contourInterval)
            : String(format: "%.1f m", contourInterval)
    }

    private func applyCustomInterval() {
        let normalized = customIntervalText
            .replacingOccurrences(of: ",", with: ".")
            .trimmingCharacters(in: .whitespaces)
        guard let value = Double(normalized), value >= 1, value <= 200 else {
            model.alertMessage = "Interval harus angka 1–200 meter."
            return
        }
        contourInterval = (value * 10).rounded() / 10
    }

    private var contourIntervalRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Interval kontur")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("makin kecil, makin rapat")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    intervalChip("5 m", value: 5)
                    intervalChip("10 m", value: 10)
                    intervalChip("20 m", value: 20)
                    intervalChip("Otomatis", value: 0)
                    intervalChip(customChipTitle, value: -1)
                }
            }
        }
    }

    private func intervalChip(_ title: String, value: Double) -> some View {
        let selected = value < 0 ? isCustomInterval : abs(contourInterval - value) < 1e-9
        return Button {
            if value < 0 {
                customIntervalText = contourInterval > 0
                    ? String(format: "%.1f", contourInterval) : "10.0"
                showingCustomInterval = true
            } else {
                contourInterval = value
            }
        } label: {
            Text(title)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(selected ? Theme.accent.opacity(0.18) : Color.clear)
                .foregroundStyle(selected ? Theme.accent : Color.secondary)
                .clipShape(Capsule())
                .overlay(
                    Capsule().stroke(selected ? Theme.accent.opacity(0.5) : Theme.hairline,
                                     lineWidth: 0.7)
                )
        }
        .buttonStyle(.plain)
    }

    private var referenceTypes: [UTType] {
        var types: [UTType] = [.xml]
        if let gpx = UTType(filenameExtension: "gpx") { types.insert(gpx, at: 0) }
        return types
    }

    private var pendingImportBinding: Binding<Bool> {
        Binding(get: { model.pendingReferenceImport != nil },
                set: { shown in if !shown { model.cancelReferenceImport() } })
    }

    private var mapCard: some View {
        SectionCard("Peta rute offline") {
            if summary.trackCount == 0 && model.referencePoints.isEmpty {
                emptyHint("Belum ada titik jalur. Peta muncul setelah rekaman dimulai.")
            } else {
                RouteMapView(points: model.points,
                             reference: model.referencePoints,
                             referenceLabel: model.referenceTrack?.name ?? "")
                    .frame(height: 280)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text("Peta memakai kerangka peta bawaan iOS. Ubin peta yang sudah diunduh sistem dapat tampil tanpa jaringan; aplikasi tidak mengunduh ubin sendiri."
                     + (model.referencePoints.isEmpty ? "" : " Garis putus-putus adalah jalur pembanding, bukan hasil rekaman aplikasi ini."))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var referenceCard: some View {
        SectionCard("Jalur pembanding (GPX)") {
            if let track = model.referenceTrack {
                Text(track.name)
                    .font(.subheadline.weight(.medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("\(track.pointCount) titik • diimpor \(Format.time(track.importedAt))"
                     + (track.source.isEmpty ? "" : " • \(track.source)"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 10) {
                    Button {
                        showingReferenceImporter = true
                    } label: {
                        Label("Ganti GPX", systemImage: "arrow.triangle.2.circlepath")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    Button(role: .destructive) {
                        showingDeleteReference = true
                    } label: {
                        Label("Hapus", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            } else {
                emptyHint("Impor GPX dari aplikasi lain (mis. ekspor Strava) untuk dibandingkan dengan rekaman sendiri. "
                          + "Garis putus-putus; tidak digabung ke titik rekaman dan tidak ikut diekspor.")
                Button {
                    showingReferenceImporter = true
                } label: {
                    Label("Impor GPX", systemImage: "square.and.arrow.down")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var profileCard: some View {
        SectionCard("Profil & kontur elevasi") {
            if elevationSamples.count < 2 {
                emptyHint("Profil elevasi butuh minimal dua titik dengan referensi elevasi yang sama. Elevasi tidak dikonversi.")
            } else {
                let altitudes = elevationSamples.map { $0.altitude }
                HStack {
                    Text(Format.meters(altitudes.min() ?? 0))
                    Spacer()
                    Text("\(elevationSamples.count) titik")
                    Spacer()
                    Text(Format.meters(altitudes.max() ?? 0))
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                contourIntervalRow

                ElevationChart(samples: elevationSamples, interval: effectiveInterval)
                    .frame(height: 190)

                Text("Naik \(Format.meters(summary.ascentMeters)) • Turun \(Format.meters(summary.descentMeters))")
                    .font(.footnote.weight(.medium))

                Text("Garis digambar setiap \(Format.meters(effectiveInterval)); pita warna mengikuti interval yang sama. "
                     + "Kontur adalah interpolasi lokal sepanjang jalur, bukan DEM area luas.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var presenceCard: some View {
        SectionCard("Kehadiran BLE (\(peers.count))") {
            if peers.isEmpty {
                emptyHint("Belum ada peserta lain terdeteksi. BLE hanya bertukar ID samaran dan waktu — tanpa jarak, posisi, atau MAC.")
            } else {
                ForEach(peers) { peer in
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "person.2.fill")
                            .foregroundStyle(Theme.accent)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(peer.id)
                                .font(.subheadline.monospaced())
                            Text("\(peer.sightings) deteksi • terakhir \(Format.shortTime(peer.lastSeen))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    if peer.id != peers.last?.id { Divider() }
                }
            }
        }
    }

    private func emptyHint(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Grafik profil dengan pita elevasi dan garis kontur tiap interval.
struct ElevationChart: View {
    let samples: [ElevationSample]
    let interval: Double

    static func automaticInterval(span: Double) -> Double {
        guard span.isFinite, span > 0 else { return 10 }
        let raw = span / 8
        let power = pow(10, floor(log10(max(raw, 1))))
        let normalized = raw / power
        return (normalized >= 5 ? 5 : normalized >= 2 ? 2 : 1) * power
    }

    private var minAltitude: Double { samples.map { $0.altitude }.min() ?? 0 }
    private var maxAltitude: Double {
        max(samples.map { $0.altitude }.max() ?? 1, minAltitude + 1)
    }
    private var maxDistance: Double { max(samples.last?.distance ?? 1, 1) }

    private var levels: [Double] {
        guard interval > 0 else { return [] }
        var result: [Double] = []
        var level = ceil(minAltitude / interval) * interval
        while level <= maxAltitude + 1e-9 && result.count < 512 {
            result.append(level)
            level += interval
        }
        return result
    }

    var body: some View {
        Canvas { context, size in
            let rect = CGRect(x: 36, y: 10,
                              width: max(1, size.width - 44),
                              height: max(1, size.height - 30))
            guard rect.width > 2, rect.height > 2 else { return }

            func pointX(_ distance: Double) -> CGFloat {
                rect.minX + rect.width * CGFloat(distance / maxDistance)
            }
            func pointY(_ altitude: Double) -> CGFloat {
                rect.maxY - rect.height * CGFloat((altitude - minAltitude) / (maxAltitude - minAltitude))
            }

            var profile = Path()
            profile.move(to: CGPoint(x: rect.minX, y: rect.maxY))
            for sample in samples {
                profile.addLine(to: CGPoint(x: pointX(sample.distance), y: pointY(sample.altitude)))
            }
            profile.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            profile.closeSubpath()

            context.fill(Path(roundedRect: rect, cornerRadius: 8), with: .color(Theme.accentSoft))

            // Pita warna per interval, dipotong mengikuti bentuk profil.
            context.drawLayer { layer in
                layer.clip(to: profile)
                // Setiap selang [batas bawah, batas atas] mendapat satu warna
                // datar, sama seperti peta kontur Android.
                var boundaries: [Double] = [minAltitude]
                boundaries.append(contentsOf: levels.filter { $0 > minAltitude + 1e-9 })
                if let last = boundaries.last, last < maxAltitude { boundaries.append(maxAltitude) }
                for index in 0..<max(0, boundaries.count - 1) {
                    let lower = boundaries[index]
                    let upper = boundaries[index + 1]
                    let top = pointY(upper)
                    let bottom = pointY(lower)
                    guard bottom > top else { continue }
                    layer.fill(
                        Path(CGRect(x: rect.minX, y: top, width: rect.width, height: bottom - top)),
                        with: .color(bandColor((lower + upper) / 2))
                    )
                }
            }

            // Garis kontur horizontal dan label berkala.
            let spacing = rect.height * CGFloat(interval / (maxAltitude - minAltitude))
            let stride = max(1, Int(ceil(24 / max(spacing, 1))))
            for (index, level) in levels.enumerated() {
                let y = pointY(level)
                let isIndex = abs(level.truncatingRemainder(dividingBy: interval * 5)) < interval * 0.01
                var line = Path()
                line.move(to: CGPoint(x: rect.minX, y: y))
                line.addLine(to: CGPoint(x: rect.maxX, y: y))
                context.stroke(line,
                               with: .color(Color.primary.opacity(isIndex ? 0.35 : 0.16)),
                               style: StrokeStyle(lineWidth: isIndex ? 1.2 : 0.8,
                                                  dash: isIndex ? [] : [3, 4]))
                guard index % stride == 0 else { continue }
                let label = Text(String(format: "%.0f", level))
                    .font(.system(size: 9))
                    .foregroundStyle(.secondary)
                context.draw(label, at: CGPoint(x: rect.minX + 15, y: y - 7), anchor: .center)
            }

            // Garis profil di atas pita.
            var line = Path()
            for (index, sample) in samples.enumerated() {
                let point = CGPoint(x: pointX(sample.distance), y: pointY(sample.altitude))
                if index == 0 { line.move(to: point) } else { line.addLine(to: point) }
            }
            context.stroke(line,
                           with: .color(Theme.accent),
                           style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))

            // Sumbu jarak.
            context.draw(Text("0").font(.system(size: 9)).foregroundStyle(.secondary),
                         at: CGPoint(x: rect.minX + 4, y: rect.maxY + 10), anchor: .leading)
            context.draw(Text(Format.distance(maxDistance))
                            .font(.system(size: 9)).foregroundStyle(.secondary),
                         at: CGPoint(x: rect.maxX, y: rect.maxY + 10), anchor: .trailing)
        }
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityLabel("Profil elevasi dengan garis kontur setiap \(Format.meters(interval))")
    }

    /// Palet hijau–tanah yang dipakai Android agar kedua platform terasa sama.
    private func bandColor(_ elevation: Double) -> Color {
        let span = max(maxAltitude - minAltitude, 1e-6)
        let t = min(max((elevation - minAltitude) / span, 0), 1)
        let palette: [Color] = [
            Color(red: 0.18, green: 0.42, blue: 0.31),
            Color(red: 0.50, green: 0.64, blue: 0.36),
            Color(red: 0.79, green: 0.65, blue: 0.42),
            Color(red: 0.66, green: 0.45, blue: 0.25)
        ]
        let index = min(palette.count - 1, Int(t * Double(palette.count)))
        return palette[index].opacity(0.42)
    }
}
