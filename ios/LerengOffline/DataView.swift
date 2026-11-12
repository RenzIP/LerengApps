import SwiftUI
import UniformTypeIdentifiers

struct DataView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showDeleteAll = false
    @State private var showCsvImporter = false
    @State private var showGpxImporter = false

    var body: some View {
        NavigationStack {
            List {
                projectSection
                pointsSection
                exportSection
                importSection
                syncSection
                resetSection
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Data")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $model.exportItem) { item in
                ExportSheet(item: item)
            }
            .fileImporter(isPresented: $showCsvImporter,
                          allowedContentTypes: csvTypes,
                          allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { model.importCSV(from: url) }
                case .failure(let error):
                    model.alertMessage = "Tidak dapat membuka file: \(error.localizedDescription)"
                }
            }
            .fileImporter(isPresented: $showGpxImporter,
                          allowedContentTypes: gpxTypes,
                          allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { model.importTrackGPX(from: url) }
                case .failure(let error):
                    model.alertMessage = "Tidak dapat membuka file: \(error.localizedDescription)"
                }
            }
            .confirmationDialog("Referensi elevasi GPX",
                                isPresented: pendingTrackImportBinding,
                                titleVisibility: .visible) {
                ForEach(ImportDatum.options) { option in
                    Button(option.label) { model.confirmTrackImport(datum: option.value) }
                }
                Button("Batal", role: .cancel) { model.cancelTrackImport() }
            } message: {
                Text(model.pendingTrackImport?.summary ?? "")
            }
            .confirmationDialog("Hapus semua titik rekaman?", isPresented: $showDeleteAll, titleVisibility: .visible) {
                Button("Hapus semua titik", role: .destructive) {
                    model.deleteAllPoints()
                }
                Button("Batal", role: .cancel) {}
            } message: {
                Text("Tindakan ini tidak dapat dibatalkan. Berkas yang sudah diekspor tetap ada di folder aplikasi.")
            }
        }
    }

    private var pendingTrackImportBinding: Binding<Bool> {
        Binding(get: { model.pendingTrackImport != nil },
                set: { shown in if !shown { model.cancelTrackImport() } })
    }

    private var csvTypes: [UTType] {
        var types: [UTType] = [.commaSeparatedText, .plainText]
        if let csv = UTType(filenameExtension: "csv") { types.insert(csv, at: 0) }
        return types
    }

    private var gpxTypes: [UTType] {
        var types: [UTType] = [.xml]
        if let gpx = UTType(filenameExtension: "gpx") { types.insert(gpx, at: 0) }
        return types
    }

    private var projectSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(model.projectName)
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text("\(model.points.count) titik • \(model.presence.count) catatan kehadiran")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
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
            if model.isExporting {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Menyusun berkas…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Button {
                model.export(.csv)
            } label: {
                exportRow(title: "CSV lengkap", subtitle: "Titik + kehadiran BLE, pemisah koma", icon: "tablecells")
            }
            .disabled(model.isExporting)

            Button {
                model.export(.geoJSON)
            } label: {
                exportRow(title: "GeoJSON", subtitle: "FeatureCollection untuk peta atau MongoDB", icon: "globe.asia.australia")
            }
            .disabled(model.isExporting)

            Button {
                model.export(.jsonLines)
            } label: {
                exportRow(title: "JSON Lines", subtitle: "Satu Fitur per baris, ramah impor MongoDB", icon: "curlybraces")
            }
            .disabled(model.isExporting)

            Menu {
                Button("Dengan elevasi") { model.export(.gpxWithElevation) }
                Button("Tanpa elevasi") { model.export(.gpxWithoutElevation) }
            } label: {
                exportRow(title: "GPX jalur", subtitle: "GPX 1.1 untuk aplikasi peta luar ruang", icon: "map")
            }
            .disabled(model.isExporting)
        } header: {
            Text("Ekspor lokal")
        } footer: {
            Text("Ekspor bersifat lokal dan berjalan di latar. Elevasi tidak pernah dikonversi antar datum; GPX hanya menulis <ele> bila semua titik memakai satu referensi. Sinkron ke MongoDB berjalan otomatis di bagian bawah.")
        }
    }

    private var importSection: some View {
        Section {
            Button {
                showCsvImporter = true
            } label: {
                exportRow(title: "Impor CSV",
                          subtitle: "Titik, waypoint, dan kehadiran BLE",
                          icon: "square.and.arrow.down")
            }
            .disabled(model.isImporting)

            Button {
                showGpxImporter = true
            } label: {
                exportRow(title: "Impor GPX",
                          subtitle: "Track, rute, atau waypoint dari aplikasi lain",
                          icon: "arrow.down.doc")
            }
            .disabled(model.isImporting)

            if model.isImporting {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Membaca berkas…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Impor ke proyek")
        } footer: {
            Text("Impor menambah titik ke proyek aktif, bukan mengganti rekaman. Duplikat dengan waktu dan koordinat sama diabaikan. Referensi elevasi hanya dicatat, tidak dikonversi.")
        }
    }

    private var syncSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { model.autoSyncEnabled },
                set: { model.setAutoSync($0) }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sinkron otomatis")
                        .font(.body)
                    Text("Semua proyek, tanpa tombol kirim")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if model.isSyncing {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Menyinkronkan…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else if let status = model.syncStatus {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(statusColor(status))
            }

            if model.syncBuiltIn {
                Text("Endpoint sudah terpasang di aplikasi ini. Tidak ada yang perlu diisi.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            DisclosureGroup("Pengaturan lanjutan") {
                TextField("https://proyek.vercel.app/api/ingest", text: $model.syncEndpoint)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.URL)

                SecureField("Kunci aplikasi", text: $model.syncToken)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.password)

                Button {
                    model.saveSyncSettings()
                } label: {
                    Label("Simpan pengaturan", systemImage: "key")
                }

                Button(role: .destructive) {
                    model.clearSyncSettings()
                } label: {
                    Label(model.syncBuiltIn ? "Kembalikan ke bawaan" : "Hapus alamat & kunci",
                          systemImage: "trash")
                }
            }
        } header: {
            Text("Sinkron otomatis ke MongoDB")
        } footer: {
            Text("Semua proyek dikirim sendiri: titik, waypoint, presence BLE, dan metadata proyek. Endpoint dan kunci aplikasi sudah tertanam di build ini, jadi tidak ada yang perlu diisi; kunci itu bukan rahasia dan pengamanan ada di server. Jalur pembanding GPX tidak pernah dikirim. Tanpa internet, data menunggu dan dikirim saat koneksi kembali.")
        }
    }

    private func statusColor(_ status: String) -> Color {
        if status.lowercased().contains("gagal") { return .red }
        if status.lowercased().contains("terkirim") { return .green }
        return .secondary
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
