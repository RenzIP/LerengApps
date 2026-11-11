import BackgroundTasks
import Foundation

/// Sinkron latar belakang milik iOS. Sistem yang menentukan kapan berjalan;
/// aplikasi hanya meminta kesempatan. Sinkron di foreground tetap berjalan
/// tanpa bergantung pada ini.
enum BackgroundSync {
    static let identifier = "id.bagas.lerengoffline.sync"

    static func register(model: AppModel) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            schedule()
            let work = Task { @MainActor in
                await model.performSync()
                refresh.setTaskCompleted(success: true)
            }
            refresh.expirationHandler = { work.cancel() }
        }
    }

    static func schedule() {
        guard MongoSync.isAutoEnabled(), MongoSync.currentConfig() != nil else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
