import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            RecordView()
                .tabItem { Label("Rekam", systemImage: "location.fill") }
            AnalysisView()
                .tabItem { Label("Analisis", systemImage: "chart.xyaxis.line") }
            DataView()
                .tabItem { Label("Data", systemImage: "tray.full") }
            ProfileView()
                .tabItem { Label("Profil", systemImage: "person.crop.circle") }
        }
        .tint(Theme.accent)
        .task { model.checkForUpdate() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.checkForUpdate() }
        }
        .sheet(item: $model.update) { info in
            UpdateSheet(info: info)
        }
        .alert("Lereng Offline", isPresented: alertBinding) {
            Button("Mengerti", role: .cancel) {}
        } message: {
            Text(model.alertMessage ?? "")
        }
    }

    private var alertBinding: Binding<Bool> {
        Binding(get: { model.alertMessage != nil },
                set: { shown in if !shown { model.alertMessage = nil } })
    }
}

struct UpdateSheet: View {
    let info: IosUpdateInfo
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: info.mandatory ? "exclamationmark.triangle.fill" : "arrow.down.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(info.mandatory ? Theme.warning : Theme.accent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(info.mandatory ? "Pembaruan wajib" : "Pembaruan tersedia")
                            .font(.title3.weight(.semibold))
                        Text("\(info.notes.count) catatan perubahan")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("Terpasang: v\(AppConfig.version) (build \(AppConfig.buildNumber))")
                    Text("Tersedia: v\(info.version) (build \(info.build))")
                }
                .font(.subheadline)
                .monospacedDigit()

                if !info.notesText.isEmpty {
                    ScrollView {
                        Text(info.notesText)
                            .font(.callout)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 160)
                }

                Text(info.mandatory
                     ? "Versi ini tidak lagi didukung. Pasang pembaruan untuk melanjutkan. Data yang sudah terekam tetap tersimpan di perangkat."
                     : "iOS tidak memasang aplikasi secara otomatis. Tombol di bawah membuka halaman unduhan; pasang ulang lewat Sideloadly/AltStore.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Link(destination: info.storeURL) {
                    Label("Buka halaman unduhan", systemImage: "arrow.up.forward.app")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.accent)

                if !info.mandatory {
                    Button("Nanti saja") { dismiss() }
                        .frame(maxWidth: .infinity)
                }
                Spacer()
            }
            .padding(24)
            .navigationTitle("Pembaruan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !info.mandatory {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Tutup") { dismiss() }
                    }
                }
            }
            .interactiveDismissDisabled(info.mandatory)
        }
    }
}
