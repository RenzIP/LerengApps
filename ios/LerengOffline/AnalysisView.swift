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
        }
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
        SectionCard("Profil elevasi") {
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

                ElevationChart(samples: elevationSamples)
                    .frame(height: 170)

                Text("Naik \(Format.meters(summary.ascentMeters)) • Turun \(Format.meters(summary.descentMeters))")
                    .font(.footnote.weight(.medium))
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

/// Grafik profil elevasi sederhana tanpa dependensi tambahan.
struct ElevationChart: View {
    let samples: [ElevationSample]

    var body: some View {
        GeometryReader { geo in
            let minAltitude = samples.map { $0.altitude }.min() ?? 0
            let maxAltitude = samples.map { $0.altitude }.max() ?? 1
            let span = max(maxAltitude - minAltitude, 1)
            let maxDistance = max(samples.last?.distance ?? 1, 1)

            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Theme.accentSoft)

                Path { path in
                    for (index, sample) in samples.enumerated() {
                        let x = geo.size.width * sample.distance / maxDistance
                        let ratio = (sample.altitude - minAltitude) / span
                        let y = geo.size.height * (1 - ratio)
                        if index == 0 {
                            path.move(to: CGPoint(x: x, y: y))
                        } else {
                            path.addLine(to: CGPoint(x: x, y: y))
                        }
                    }
                }
                .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                .padding(6)
            }
        }
    }
}
