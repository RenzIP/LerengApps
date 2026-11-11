import SwiftUI

struct DataView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showDeleteAll = false
    @State private var showSendConfirm = false

    var body: some View {
        NavigationStack {
            List {
                projectSection
                pointsSection
                exportSection
                syncSection
                resetSection
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Data")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $model.exportItem) { item in
                ExportSheet(item: item)
            }
            .confirmationDialog("Hapus semua titik rekaman?", isPresented: $showDeleteAll, titleVisibility: .visible) {
                Button("Hapus semua titik", role: .destructive) {
                    model.deleteAllPoints()
                }
                Button("Batal", role: .cancel) {}
            } message: {
                Text("Tindakan ini tidak dapat dibatalkan. Berkas yang sudah diekspor tetap ada di folder aplikasi.")
            }
            .confirmationDialog("Kirim semua data ke MongoDB?", isPresented: $showSendConfirm,
                                titleVisibility: .visible) {
                Button("Kirim sekarang") { model.sendToMongo() }
                Button("Batal", role: .cancel) {}
            } message: {
                Text("\(trackCount) titik track, \(waypointCount) waypoint, \(model.presence.count) event presence BLE, dan metadata proyek akan dikirim ke \(model.syncEndpoint). Jalur pembanding GPX tidak ikut.")
            }
        }
    }

    private var trackCount: Int {
        model.points.filter { !$0.isWaypoint && $0.hasValidCoordinate }.count
    }

    private var waypointCount: Int {
        model.points.filter { $0.isWaypoint && $0.hasValidCoordinate }.count
    }

    private var projectSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(model.projectName)
                    .font(.headline)
                Text("\(model.points.count) titik • \(model.presence.count) catatan kehadiran")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)
        } header: {
            Text("Proyek aktif")
        }
    }

    private var pointsSection: some View {
        Section {
            if model.points.isEmpty {
                Text("Belum ada titik. Mulai rekaman di tab Rekam.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(model.points.reversed())) { point in
                    PointRow(point: point)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                model.deletePoint(point)
                            } label: {
                                Label("Hapus", systemImage: "trash")
                            }
                        }
                }
            }
        } header: {
            Text("Titik rekaman")
        } footer: {
            Text("Geser satu baris ke kiri untuk menghapus titik tertentu.")
        }
    }

    private var exportSection: some View {
        Section {
            Button {
                model.export(.csv)
            } label: {
                exportRow(title: "CSV lengkap", subtitle: "Titik + kehadiran BLE, pemisah koma", icon: "tablecells")
            }

            Button {
                model.export(.geoJSON)
            } label: {
                exportRow(title: "GeoJSON", subtitle: "FeatureCollection untuk peta atau MongoDB", icon: "globe.asia.australia")
            }

            Button {
                model.export(.jsonLines)
            } label: {
                exportRow(title: "JSON Lines", subtitle: "Satu Fitur per baris, ramah impor MongoDB", icon: "curlybraces")
            }

            Menu {
                Button("Dengan elevasi") { model.export(.gpxWithElevation) }
                Button("Tanpa elevasi") { model.export(.gpxWithoutElevation) }
            } label: {
                exportRow(title: "GPX jalur", subtitle: "GPX 1.1 untuk aplikasi peta luar ruang", icon: "map")
            }
        } header: {
            Text("Ekspor lokal")
        } footer: {
            Text("Ekspor bersifat lokal. Elevasi tidak pernah dikonversi antar datum; GPX hanya menulis <ele> bila semua titik memakai satu referensi. Unggahan hanya terjadi lewat tombol Kirim ke MongoDB di bawah.")
        }
    }

    private var syncSection: some View {
        Section {
            TextField("https://proyek.vercel.app/api/ingest", text: $model.syncEndpoint)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.URL)

            SecureField("Token yang sama dengan INGEST_TOKEN", text: $model.syncToken)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.password)

            Button {
                model.saveSyncSettings()
            } label: {
                Label("Simpan pengaturan", systemImage: "key")
            }

            Button {
                showSendConfirm = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.up.doc.on.clipboard")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 26)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.isSyncing ? "Mengirim…" : "Kirim ke MongoDB")
                            .font(.body)
                        Text("Semua titik, waypoint, presence BLE, dan metadata proyek")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
            }
            .disabled(model.isSyncing)

            if let status = model.syncStatus {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(status.hasPrefix("Terkirim") ? Color.green : Color.secondary)
            }

            Button(role: .destructive) {
                model.clearSyncSettings()
            } label: {
                Label("Hapus alamat & token", systemImage: "trash")
            }
        } header: {
            Text("Kirim ke MongoDB")
        } footer: {
            Text("Hanya berjalan saat tombol ditekan; tidak ada unggahan otomatis atau background. Endpoint HTTPS milikmu yang menyimpan URI MongoDB—aplikasi hanya menyimpan alamat dan token. Token disimpan di Keychain. Jalur pembanding GPX tidak pernah dikirim.")
        }
    }

    private var resetSection: some View {
        Section {
            Button(role: .destructive) {
                showDeleteAll = true
            } label: {
                Label("Hapus semua titik", systemImage: "trash")
            }
            .disabled(model.points.isEmpty)
        }
    }

    private func exportRow(title: String, subtitle: String, icon: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "square.and.arrow.up")
                .font(.footnote)
                .foregroundStyle(.tertiary)
        }
    }
}

struct PointRow: View {
    let point: TrackPoint

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: point.isWaypoint ? "flag.fill" : "circle.fill")
                .font(.system(size: point.isWaypoint ? 13 : 7))
                .foregroundStyle(point.isWaypoint ? Theme.warning : Theme.accent)
                .frame(width: 18, height: 20)

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(point.isWaypoint ? (point.label.isEmpty ? "Waypoint" : point.label) : "Titik jalur")
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    Spacer()
                    Text(Format.shortTime(point.time))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Text("\(Format.decimal(point.lat)), \(Format.decimal(point.lon))")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    if let altitude = point.altitude {
                        Text(Format.meters(altitude))
                    }
                    if let accuracy = point.horizontalAccuracy {
                        Text("±\(Format.meters(accuracy))")
                    }
                    if !point.participantAlias.isEmpty {
                        Text(point.participantAlias)
                    }
                }
                .font(.caption2)
                .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
    }
}
