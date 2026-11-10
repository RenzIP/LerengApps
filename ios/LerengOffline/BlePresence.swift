import Foundation
import Combine
import CoreBluetooth

/// Pertukaran kehadiran BLE. Hanya ID samaran + waktu; tidak ada MAC, RSSI, jarak, atau posisi
/// yang disimpan. iOS tidak dapat menaruh Service Data kustom di advertisement, jadi:
/// - iOS meng-advertise service UUID dan menyediakan karakteristik GATT yang dibaca Android,
/// - iOS membaca Manufacturer Data dari Android (scan response) atau karakteristik GATT iOS lain.
final class BlePresence: NSObject, ObservableObject {
    static let serviceUUID = CBUUID(string: BleGroupCodec.serviceUUIDText)
    static let identityUUID = CBUUID(string: BleGroupCodec.identityCharacteristicUUIDText)

    @Published private(set) var status = "BLE nonaktif"
    @Published private(set) var running = false

    var onPeer: ((String) -> Void)?

    private var peripheralManager: CBPeripheralManager?
    private var centralManager: CBCentralManager?
    private var identityCharacteristic: CBMutableCharacteristic?
    private var payload: Data?
    private var groupCode = ""
    private var ownParticipantId = ""

    private var connecting: Set<UUID> = []
    private var lastAttempt: [UUID: Date] = [:]
    private let attemptInterval: TimeInterval = 45

    func start(groupCode: String, participantId: String) {
        guard let payload = BleGroupCodec.payload(groupCode: groupCode, participantId: participantId) else {
            status = "Kode outing belum sah; BLE tidak aktif."
            return
        }
        stop()
        self.payload = payload
        self.groupCode = BleGroupCodec.normalizeGroupCode(groupCode)
        self.ownParticipantId = participantId.uppercased()
        running = true
        status = "Menyiapkan BLE…"

        let characteristic = CBMutableCharacteristic(type: Self.identityUUID,
                                                     properties: [.read],
                                                     value: payload,
                                                     permissions: [.readable])
        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [characteristic]
        identityCharacteristic = characteristic

        peripheralManager = CBPeripheralManager(delegate: self, queue: .main,
                                                options: [CBPeripheralManagerOptionShowPowerAlertKey: true])
        centralManager = CBCentralManager(delegate: self, queue: .main,
                                          options: [CBCentralManagerOptionShowPowerAlertKey: true])
        pendingService = service
    }

    func stop() {
        running = false
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        centralManager?.stopScan()
        centralManager = nil
        peripheralManager = nil
        identityCharacteristic = nil
        pendingService = nil
        payload = nil
        connecting.removeAll()
        lastAttempt.removeAll()
        status = "BLE nonaktif"
    }

    private var pendingService: CBMutableService?

    private func record(payload: Data) {
        guard let peer = BleGroupCodec.parsePeerId(payload: payload, expectedGroupCode: groupCode) else { return }
        guard peer.uppercased() != ownParticipantId else { return }
        onPeer?(peer)
    }
}

extension BlePresence: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard running else { return }
        switch peripheral.state {
        case .poweredOn:
            if let service = pendingService {
                peripheral.add(service)
                pendingService = nil
            }
        case .unauthorized:
            status = "Izin Bluetooth ditolak; GPS tetap merekam."
        case .poweredOff:
            status = "Bluetooth nonaktif; GPS tetap merekam."
        default:
            status = "BLE menunggu Bluetooth siap…"
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil, running else {
            status = "Layanan BLE gagal dipasang; GPS tetap merekam."
            return
        }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID]])
        status = "BLE aktif: iPhone menyiarkan ID samaran."
    }
}

extension BlePresence: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard running else { return }
        if central.state == .poweredOn {
            central.scanForPeripherals(withServices: [Self.serviceUUID],
                                       options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
           let payload = BleGroupCodec.payload(fromManufacturerData: manufacturer) {
            record(payload: payload)
            return
        }
        let connectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue ?? false
        guard connectable else { return }
        let identifier = peripheral.identifier
        guard !connecting.contains(identifier) else { return }
        if let last = lastAttempt[identifier], Date().timeIntervalSince(last) < attemptInterval { return }
        lastAttempt[identifier] = Date()
        connecting.insert(identifier)
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connecting.remove(peripheral.identifier)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connecting.remove(peripheral.identifier)
    }
}

extension BlePresence: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        for service in peripheral.services ?? [] where service.uuid == Self.serviceUUID {
            peripheral.discoverCharacteristics([Self.identityUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let characteristic = service.characteristics?.first(where: { $0.uuid == Self.identityUUID }) else {
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if error == nil, characteristic.uuid == Self.identityUUID, let value = characteristic.value {
            record(payload: value)
        }
        centralManager?.cancelPeripheralConnection(peripheral)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didModifyServices invalidatedServices: [CBService]) {}
}
