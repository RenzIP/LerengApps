package id.bagas.lerengoffline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class BlePresenceController {
    public interface Listener {
        void onStatus(String status);
        void onPeerSeen(String participantId);
    }

    private final Context context;
    private final SurveyDatabase database;
    private final long surveyId;
    private final String groupCode;
    private final String ownParticipantId;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Device handles are kept only in memory for the duration of a GATT read; no address is persisted.
    private final ArrayDeque<BluetoothDevice> pendingGattDevices = new ArrayDeque<>();
    private final Set<BluetoothDevice> queuedOrReadGattDevices = new HashSet<>();
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGatt activeGatt;
    private BluetoothDevice activeDevice;
    private boolean advertisingStarted;
    private boolean scanningStarted;
    private boolean running;

    public BlePresenceController(Context context, SurveyDatabase database, long surveyId,
                                 String groupCode, String ownParticipantId, Listener listener) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.surveyId = surveyId;
        this.groupCode = BleGroupCodec.normalizeGroupCode(groupCode);
        this.ownParticipantId = ownParticipantId == null ? "" : ownParticipantId;
        this.listener = listener;
    }

    public void start() {
        if (!BleGroupCodec.isValidGroupCode(groupCode) || !BleGroupCodec.isValidParticipantId(ownParticipantId)) {
            status("BLE nonaktif: atur kode outing rombongan terlebih dahulu.");
            return;
        }
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            status("Ponsel tidak mendukung Bluetooth LE; GPS tetap merekam.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                 context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                 context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            status("Izin Nearby Devices belum lengkap; GPS tetap merekam.");
            return;
        }
        if (Build.VERSION.SDK_INT <= 30 &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status("Izin lokasi diperlukan Android untuk scan BLE lawas; GPS tetap merekam.");
            return;
        }
        try {
            BluetoothManager manager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                status("Bluetooth tidak tersedia; GPS tetap merekam.");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            scanner = adapter.getBluetoothLeScanner();
            if (advertiser == null && scanner == null) {
                status("Bluetooth nonaktif atau BLE tidak didukung; GPS tetap merekam.");
                return;
            }
            running = true;
            if (advertiser != null) startAdvertising();
            if (scanner != null) startScanning();
            status((advertiser != null && scanner != null)
                    ? "BLE aktif • Android dan iPhone dapat terdeteksi; hanya ID samaran dan waktu, tanpa jarak."
                    : "BLE sebagian tersedia • GPS tetap merekam; cek dukungan Bluetooth ponsel.");
        } catch (SecurityException | IllegalStateException e) {
            stop();
            status("BLE tidak dapat dimulai; GPS tetap merekam.");
        }
    }

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        byte[] payload = BleGroupCodec.createPayload(groupCode, ownParticipantId);
        if (payload == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(false)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(BleGroupCodec.serviceUuid(), payload)
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(BleGroupCodec.serviceUuid())
                .build();
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
    }

    @SuppressLint("MissingPermission")
    private void startScanning() {
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(BleGroupCodec.serviceUuid()).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        scanningStarted = true;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertisingStarted = true;
            status("BLE iklan aktif; Android dan iPhone dapat mendeteksi outing ini.");
        }
        @Override public void onStartFailure(int errorCode) {
            advertisingStarted = false;
            status("Iklan BLE tidak didukung/dapat dimulai (" + errorCode + "); GPS tetap merekam.");
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { process(result); }
        @Override public void onBatchScanResults(java.util.List<ScanResult> results) {
            for (ScanResult result : results) process(result);
        }
        @Override public void onScanFailed(int errorCode) {
            scanningStarted = false;
            status("Scan BLE gagal (" + errorCode + "); GPS tetap merekam.");
        }
    };

    private void process(ScanResult result) {
        if (result == null) return;
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        byte[] data = record.getServiceData(BleGroupCodec.serviceUuid());
        if (data != null) {
            String peerId = BleGroupCodec.parsePeerId(data, groupCode);
            if (peerId != null && !peerId.equalsIgnoreCase(ownParticipantId))
                mainHandler.post(() -> recordPresence(peerId));
            return;
        }
        // iOS advertises the outing service UUID but cannot place custom Service Data in its advertisement.
        // Read its pseudonymous ID from the iOS read-only GATT characteristic; no RSSI/address is logged.
        BluetoothDevice device = result.getDevice();
        if (device != null) mainHandler.post(() -> enqueueGattRead(device));
    }

    private void enqueueGattRead(BluetoothDevice device) {
        if (!running || queuedOrReadGattDevices.contains(device)) return;
        queuedOrReadGattDevices.add(device);
        pendingGattDevices.addLast(device);
        connectNextGattDevice();
    }

    @SuppressLint("MissingPermission")
    private void connectNextGattDevice() {
        if (!running || activeGatt != null || pendingGattDevices.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status("Izin Bluetooth Connect belum tersedia; GPS tetap merekam.");
            pendingGattDevices.clear();
            queuedOrReadGattDevices.clear();
            return;
        }
        BluetoothDevice device = pendingGattDevices.removeFirst();
        activeDevice = device;
        try {
            activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (activeGatt == null) finishGatt(null, true);
        } catch (SecurityException | IllegalStateException e) {
            queuedOrReadGattDevices.remove(device);
            activeDevice = null;
            activeGatt = null;
            mainHandler.postDelayed(this::connectNextGattDevice, 250L);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            mainHandler.post(() -> {
                if (gatt != activeGatt) { closeGatt(gatt); return; }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!gatt.discoverServices()) finishGatt(gatt, true);
                    } catch (SecurityException | IllegalStateException e) { finishGatt(gatt, true); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    finishGatt(gatt, true);
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            mainHandler.post(() -> {
                if (gatt != activeGatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { finishGatt(gatt, true); return; }
                BluetoothGattService service = gatt.getService(BleGroupCodec.serviceUuid().getUuid());
                BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(BleGroupCodec.identityCharacteristicUuid());
                if (characteristic == null || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                    finishGatt(gatt, false);
                    return;
                }
                try {
                    if (!gatt.readCharacteristic(characteristic)) finishGatt(gatt, true);
                } catch (SecurityException | IllegalStateException e) { finishGatt(gatt, true); }
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            byte[] value = characteristic == null ? null : characteristic.getValue();
            handleGattRead(gatt, characteristic, value, status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            handleGattRead(gatt, characteristic, value, status);
        }
    };

    private void handleGattRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        mainHandler.post(() -> {
            if (gatt != activeGatt) return;
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null &&
                    BleGroupCodec.identityCharacteristicUuid().equals(characteristic.getUuid())) {
                String peerId = BleGroupCodec.parsePeerId(value, groupCode);
                if (peerId != null && !peerId.equalsIgnoreCase(ownParticipantId)) recordPresence(peerId);
            }
            finishGatt(gatt, status != BluetoothGatt.GATT_SUCCESS);
        });
    }

    private void finishGatt(BluetoothGatt gatt, boolean allowRetry) {
        BluetoothGatt target = gatt == null ? activeGatt : gatt;
        if (target == activeGatt) {
            if (allowRetry && activeDevice != null) queuedOrReadGattDevices.remove(activeDevice);
            activeGatt = null;
            activeDevice = null;
        }
        closeGatt(target);
        if (running) mainHandler.postDelayed(this::connectNextGattDevice, 250L);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt(BluetoothGatt gatt) {
        if (gatt == null) return;
        try { gatt.disconnect(); } catch (SecurityException | IllegalStateException ignored) {}
        try { gatt.close(); } catch (SecurityException | IllegalStateException ignored) {}
    }

    private void recordPresence(String peerId) {
        if (!running || peerId == null || peerId.equalsIgnoreCase(ownParticipantId)) return;
        long now = System.currentTimeMillis();
        if (database.recordPresence(surveyId, groupCode, ownParticipantId, peerId, now) && listener != null) {
            listener.onPeerSeen(peerId);
        }
    }

    public void stop() {
        running = false;
        try { if (advertiser != null && advertisingStarted) advertiser.stopAdvertising(advertiseCallback); }
        catch (SecurityException | IllegalStateException ignored) {}
        try { if (scanner != null && scanningStarted) scanner.stopScan(scanCallback); }
        catch (SecurityException | IllegalStateException ignored) {}
        advertisingStarted = false;
        scanningStarted = false;
        pendingGattDevices.clear();
        queuedOrReadGattDevices.clear();
        BluetoothGatt old = activeGatt;
        activeGatt = null;
        activeDevice = null;
        closeGatt(old);
        advertiser = null;
        scanner = null;
    }

    private void status(String message) {
        if (listener != null) mainHandler.post(() -> listener.onStatus(message));
    }
}
