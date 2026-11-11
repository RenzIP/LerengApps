import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var model: AppModel

    @State private var projectName = ""
    @State private var groupCode = ""
    @State private var participantId = ""
    @State private var alias = ""
    @State private var identityError: String?
    @State private var identitySaved = false

    var body: some View {
        NavigationStack {
            Form {
                projectSection
                identitySection
                privacySection
                updateSection
                aboutSection
            }
            .navigationTitle("Profil")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear(perform: load)
            .onChange(of: model.survey) { _ in load() }
        }
    }

    private var projectSection: some View {
        Section {
            TextField("Nama proyek", text: $projectName)
                .textInputAutocapitalization(.words)
                .onSubmit { model.renameProject(projectName) }
            Button("Simpan nama proyek") {
                model.renameProject(projectName)
            }
            .disabled(projectName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } header: {
            Text("Proyek")
        } footer: {
            Text("Satu proyek berisi satu jalur. Nama proyek dipakai pada berkas GPX/GeoJSON.")
        }
    }

    private var identitySection: some View {
        Section {
            TextField("Kode outing (8–16 karakter)", text: $groupCode)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()

            HStack {
                TextField("ID peserta (8 digit hex)", text: $participantId)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.body.monospaced())
                Button {
                    participantId = BleGroupCodec.newParticipantId()
                } label: {
                    Image(systemName: "dice.fill")
                }
                .buttonStyle(.borderless)
            }

            TextField("Nama samaran (opsional)", text: $alias)

            HStack(spacing: 10) {
                Button("Buat kode outing") {
                    groupCode = BleGroupCodec.newGroupCode()
                    if participantId.isEmpty { participantId = BleGroupCodec.newParticipantId() }
                }
                .buttonStyle(.bordered)
                .tint(Theme.accent)

                Button("Simpan identitas") { save() }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
            }

            if let identityError {
                Text(identityError)
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
            }
            if identitySaved {
                Label("Identitas tersimpan.", systemImage: "checkmark.seal.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.accent)
            }

            HStack {
                Text("Status BLE").foregroundStyle(.secondary)
                Spacer()
                Text(model.ble.status)
                    .font(.caption)
                    .multilineTextAlignment(.trailing)
            }
        } header: {
            Text("Identitas rombongan")
        } footer: {
            Text("BLE hanya bertukar potongan hash kode outing dan ID peserta sekali-jalan. Tidak ada MAC, RSSI, jarak, atau posisi yang disimpan. Event kehadiran ikut sinkron otomatis ke endpoint HTTPS milikmu bila sinkron otomatis aktif.")
        }
    }

    private var privacySection: some View {
        Section {
            Label("Data tetap di perangkat ini", systemImage: "lock.shield")
            Label("Tidak ada sinkronisasi awan otomatis", systemImage: "icloud.slash")
            Label("Elevasi tidak dikonversi antar datum", systemImage: "ruler")
            Label("Ekspor hanya lewat lembar berbagi sistem", systemImage: "square.and.arrow.up")
        } header: {
            Text("Privasi")
        } footer: {
            Text("Satu-satunya permintaan jaringan adalah berkas JSON kecil untuk memeriksa pembaruan. Bila gagal, aplikasi tetap bekerja.")
        }
    }

    private var updateSection: some View {
        Section {
            HStack {
                Text("Terpasang")
                Spacer()
                Text("v\(AppConfig.version) (build \(AppConfig.buildNumber))")
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Button {
                model.checkForUpdate(manual: true)
            } label: {
                HStack {
                    Label("Periksa pembaruan", systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                    if model.isCheckingUpdate { ProgressView() }
                }
            }
            .disabled(model.isCheckingUpdate)

            if let note = model.updateNote {
                Text(note)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Pembaruan")
        } footer: {
            Text("Pemeriksaan otomatis berjalan paling sering setiap 6 jam. Pembaruan wajib memakai dialog yang tidak dapat ditutup sampai aplikasi diperbarui lewat App Store.")
        }
    }

    private var aboutSection: some View {
        Section {
            HStack {
                Text("Berkas basis data")
                Spacer()
                Text("lereng.sqlite")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            HStack {
                Text("Folder ekspor")
                Spacer()
                Text("Documents/exports")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Tentang")
        } footer: {
            Text("Lereng Offline — perekam jalur pendakian offline. Skema data sama dengan aplikasi Android sehingga berkas ekspor dapat dibuka di kedua platform.")
        }
    }

    private func load() {
        projectName = model.projectName
        groupCode = model.survey?.groupCode ?? ""
        participantId = model.survey?.participantId ?? ""
        alias = model.survey?.participantAlias ?? ""
    }

    private func save() {
        identitySaved = false
        if let error = model.saveIdentity(groupCode: groupCode, participantId: participantId, alias: alias) {
            identityError = error
            return
        }
        identityError = nil
        identitySaved = true
        load()
    }
}
