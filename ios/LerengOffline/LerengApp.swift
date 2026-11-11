import SwiftUI

@main
struct LerengApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model: AppModel

    init() {
        let model = AppModel()
        _model = StateObject(wrappedValue: model)
        BackgroundSync.register(model: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active:
                        // Kirim sisa data yang tertunda saat aplikasi dibuka kembali.
                        model.scheduleSync(delay: 2, force: true)
                        BackgroundSync.schedule()
                    case .background:
                        BackgroundSync.schedule()
                    default:
                        break
                    }
                }
        }
    }
}
