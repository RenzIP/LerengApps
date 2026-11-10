# Catatan platform Android BLE

Disusun untuk fitur rombongan offline Lereng Offline. Sumber resmi yang dibuka pada 2026-11-06:

- Bluetooth runtime/manifest permissions, `neverForLocation`, legacy permissions, feature detection: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Foreground service types dan syarat `location`/`connectedDevice`: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground-service types wajib pada target Android 14+: https://developer.android.com/about/versions/14/changes/fgs-types-required

Rangkuman keputusan:

- Android 12+ memerlukan runtime `BLUETOOTH_SCAN` untuk scan dan `BLUETOOTH_ADVERTISE` untuk advertise. `BLUETOOTH_CONNECT` tidak diperlukan bila aplikasi tidak melakukan pairing/koneksi atau membaca alamat perangkat.
- Karena scan BLE tidak dipakai untuk menurunkan lokasi fisik, deklarasi `BLUETOOTH_SCAN` memakai `usesPermissionFlags="neverForLocation"`. Android memperingatkan bahwa beberapa jenis beacon dapat terfilter.
- Android 8–11 memakai `BLUETOOTH`/`BLUETOOTH_ADMIN` legacy dengan `maxSdkVersion=30`; Android 10–11 dapat memerlukan `ACCESS_BACKGROUND_LOCATION` untuk discovery saat aplikasi tidak terlihat. Aplikasi meminta izin ini hanya ketika grup BLE diaktifkan; penolakan tidak mematikan pencatatan GPS lokal.
- Android 14 target 34 mewajibkan tipe foreground service dan permission-nya. Location logger tetap memakai tipe `location`/`FOREGROUND_SERVICE_LOCATION`; discovery/advertising-only BLE tidak memerlukan tipe `connectedDevice` menurut panduan tipe service resmi. Mulai service dari Activity yang terlihat setelah izin diperoleh.
- Advertisement hanya memuat 6-byte hash outing + 4-byte random pseudonymous participant ID pada custom service UUID; maksimum legacy BLE payload 31 byte. Tidak ada nama, koordinat, altitude, MAC/address, RSSI, atau jarak. Scan callback memeriksa hanya service data, memvalidasi hash outing, mencatat ID + waktu, dan membuang `ScanResult` lainnya.
- Bluetooth LE adalah fitur opsional: cek hardware/adverstiser/scanner runtime, dan jika tidak tersedia/ditolak, GPS tetap berfungsi.
