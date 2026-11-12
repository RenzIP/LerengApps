import Foundation
import Network

/// Status jaringan untuk chip di layar rekam.
///
/// "Terbatas" berarti jalur tersedia tetapi mahal atau dibatasi sistem
/// (mis. hotspot atau jaringan captive). Rekaman tidak pernah menunggu
/// jaringan: data tetap ditulis lokal dan dikirim saat jalur tersedia.
final class NetworkMonitor {
    enum State {
        case unknown
        case online
        case limited
        case offline

        var label: String {
            switch self {
            case .unknown: return "Jaringan…"
            case .online: return "Jaringan ada"
            case .limited: return "Jaringan terbatas"
            case .offline: return "Offline"
            }
        }
    }

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "id.bagas.lerengoffline.network")
    private var started = false

    var onChange: ((State) -> Void)?

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { [weak self] path in
            let state: State
            if path.status != .satisfied {
                state = .offline
            } else if path.isExpensive || path.isConstrained {
                state = .limited
            } else {
                state = .online
            }
            DispatchQueue.main.async { self?.onChange?(state) }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}
