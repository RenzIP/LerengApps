import Foundation
import Combine
import CoreLocation

/// Perekam lokasi. Hanya menulis ke perangkat; tidak ada unggahan.
final class LocationRecorder: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var wantsUpdates = false

    @Published private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published private(set) var lastLocation: CLLocation?
    @Published private(set) var isRunning = false

    var onFix: ((CLLocation) -> Void)?

    override init() {
        super.init()
        authorizationStatus = manager.authorizationStatus
        manager.delegate = self
        // Best-for-navigation meminta GNSS bekerja pada presisi tertinggi, sama seperti aplikasi
        // olahraga populer. Baterai lebih cepat terkuras; rekaman lapangan tetap prioritas.
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = 2
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .fitness
    }

    func requestPermission() {
        manager.requestAlwaysAuthorization()
    }

    func start() {
        wantsUpdates = true
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestAlwaysAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            beginUpdates()
        default:
            break
        }
    }

    func stop() {
        wantsUpdates = false
        manager.stopUpdatingLocation()
        isRunning = false
    }

    private func beginUpdates() {
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        isRunning = true
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            if wantsUpdates { beginUpdates() }
        default:
            isRunning = false
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        lastLocation = location
        onFix?(location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Kegagalan satu fix tidak menghentikan sesi; rekaman menunggu fix berikutnya.
    }
}
