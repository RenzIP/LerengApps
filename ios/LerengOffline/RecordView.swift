import SwiftUI
import CoreLocation

struct RecordView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showWaypointDialog = false
    @State private var waypointLabel = ""

    private var summary: RouteSummary { model.summary }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    statusCard
                    statsCard
                    actionsCard
                    identityCard
                }
                .padding(16)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("Rekam")
            .navigationBarTitleDisplayMode(.inline)
            .alert("Tambah waypoint", isPresented: $showWaypointDialog) {
                TextField("Nama waypoint", text: $waypointLabel)
                Button("Batal", role: .cancel) { waypointLabel = "" }
                Button("Simpan") {
                    model.addWaypoint(label: waypointLabel)
                    waypointLabel = ""
                }
            } message: {
                Text("Waypoint memakai posisi fix terakhir yang tersedia.")
            }
        }
    }

    private var statusCard: some View {
        SectionCard {
            HStack(alignment: .top, spacing: 14) {
                ZStack {
                    Circle()
                        .fill(model.isRecording ? Theme.danger.opacity(0.14) : Theme.accentSoft)
                        .frame(width: 46, height: 46)
                    Image(systemName: model.isRecording ? "dot.radiowaves.left.and.right" : "mountain.2.fill")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(model.isRecording ? Theme.danger : Theme.accent)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(statusTitle)
                        .font(.headline)
                    Text(model.statusText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 6) {
                        StatusChip(text: permissionLabel,
                                   systemImage: "location.fill",
                                   tint: permissionTint)
                        StatusChip(text: model.ble.running ? "BLE aktif" : "BLE mati",
                                   systemImage: "dot.radiowaves.left.and.right",
                                   tint: model.ble.running ? Theme.accent : .secondary)
                        StatusChip(text: model.networkState.label,
                                   systemImage: networkIcon,
                                   tint: networkTint)
                    }
                }
                Spacer(minLength: 0)
            }
            if model.recorder.authorizationStatus == .notDetermined {
                Button {
                    model.requestLocationPermission()
                } label: {
                    Label("Izinkan lokasi", systemImage: "location.badge.plus")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(Theme.accent)
            }
        }
    }

    private var statsCard: some View {
        SectionCard("Ringkasan jalur") {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                StatTile(label: "Jarak", value: Format.distance(summary.distanceMeters))
                StatTile(label: "Durasi", value: Format.duration(milliseconds: model.elapsedMilliseconds))
                StatTile(label: "Naik", value: Format.meters(summary.ascentMeters), footnote: "dari elevasi terekam")
                StatTile(label: "Turun", value: Format.meters(summary.descentMeters), footnote: "dari elevasi terekam")
                StatTile(label: "Titik jalur", value: "\(summary.trackCount)")
                StatTile(label: "Waypoint", value: "\(summary.waypointCount)")
            }
            Text("Elevasi tidak dikonversi. Naik/turun hanya dihitung antar titik dengan referensi elevasi yang sama.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var actionsCard: some View {
        SectionCard {
            Button {
                if model.isRecording { model.stopRecording() } else { model.startRecording() }
            } label: {
                Label(primaryLabel, systemImage: primaryIcon)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .tint(model.isRecording ? Theme.danger : Theme.accent)
            .disabled(model.isStarting)

            Button {
                showWaypointDialog = true
            } label: {
                Label("Tandai waypoint", systemImage: "flag.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .tint(Theme.accent)
        }
    }

    private var identityCard: some View {
        SectionCard("Rombongan") {
            if let survey = model.survey, !survey.groupCode.isEmpty {
                HStack {
                    Text("Kode outing").foregroundStyle(.secondary)
                    Spacer()
                    Text(survey.groupCode)
                        .font(.body.monospaced())
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                }
                HStack {
                    Text("ID peserta").foregroundStyle(.secondary)
                    Spacer()
                    Text(survey.participantId)
                        .font(.body.monospaced())
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                }
                HStack {
                    Text("Nama samaran").foregroundStyle(.secondary)
                    Spacer()
                    Text(survey.participantAlias.isEmpty ? "—" : survey.participantAlias)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Text(model.ble.status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("Belum ada kode outing. Tanpa kode, rekaman GPS tetap berjalan tetapi kehadiran BLE tidak dibagikan.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("Atur kode di tab Profil → Identitas rombongan.")
                    .font(.caption)
                    .foregroundStyle(Theme.accent)
            }
        }
    }

    private var statusTitle: String {
        if model.isStarting { return "Memulai…" }
        return model.isRecording ? "Sedang merekam" : "Siap merekam"
    }

    private var primaryLabel: String {
        if model.isStarting { return "Memulai…" }
        return model.isRecording ? "Hentikan rekaman" : "Mulai merekam"
    }

    private var primaryIcon: String {
        if model.isStarting { return "hourglass" }
        return model.isRecording ? "stop.circle.fill" : "play.circle.fill"
    }

    private var networkIcon: String {
        switch model.networkState {
        case .online: return "wifi"
        case .limited: return "wifi.exclamationmark"
        case .offline: return "wifi.slash"
        case .unknown: return "wifi"
        }
    }

    private var networkTint: Color {
        switch model.networkState {
        case .online: return Theme.accent
        case .limited: return Theme.warning
        case .offline, .unknown: return .secondary
        }
    }

    private var permissionLabel: String {
        switch model.recorder.authorizationStatus {
        case .authorizedAlways: return "Lokasi selalu"
        case .authorizedWhenInUse: return "Lokasi saat dipakai"
        case .denied: return "Lokasi ditolak"
        case .restricted: return "Lokasi dibatasi"
        case .notDetermined: return "Izin belum ada"
        @unknown default: return "Status lokasi tak dikenal"
        }
    }

    private var permissionTint: Color {
        switch model.recorder.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: return Theme.accent
        case .denied, .restricted: return Theme.danger
        default: return Theme.warning
        }
    }
}
