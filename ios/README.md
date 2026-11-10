# Lereng Offline untuk iOS

Aplikasi native SwiftUI dengan perilaku yang sama seperti aplikasi Android: merekam jalur
pendakian secara offline, menandai waypoint, mencatat kehadiran BLE, dan mengekspor data ke
berkas lokal. Tidak ada unggahan, tidak ada sinkronisasi awan, dan tidak ada konversi datum.

## Status

- Kode sumber lengkap, target iOS 16.
- Proyek Xcode **dibuat** oleh [XcodeGen](https://github.com/yonaskolb/XcodeGen) dari
  `project.yml` — tidak ada `.xcodeproj` yang di-commit, sehingga tidak ada konflik proyek.
- Dikompilasi dan diuji di GitHub Actions (`.github/workflows/ios.yml`, runner `macos-latest`,
  simulator iPhone dipilih otomatis) karena tidak ada Mac di mesin pengembangan. Status terakhir:
  build sukses, 31 uji unit lulus.

## Menjalankan di Mac

```bash
brew install xcodegen
cd ios
xcodegen generate
open LerengOffline.xcodeproj
```

Pilih tim penandatangan di tab *Signing & Capabilities* (atau isi `DEVELOPMENT_TEAM` pada
`project.yml`), lalu jalankan pada perangkat. Simulator tidak punya Bluetooth, jadi kehadiran
BLE hanya dapat diuji di perangkat fisik.

## Struktur

| Berkas | Isi |
| --- | --- |
| `AppConfig.swift` | URL manifes pembaruan, versi, nomor build |
| `Models.swift` | `Survey`, `TrackPoint`, `Presence`, `ElevationReference`, `RouteSummary` |
| `Database.swift` | SQLite lokal, skema sama dengan Android (`surveys`, `points`, `presence`) |
| `LocationRecorder.swift` | Core Location, akurasi terbaik, latar belakang |
| `BlePresence.swift` | CoreBluetooth: advertise GATT + baca manufacturer data |
| `BleGroupCodec.swift` | Format kabel BLE identik dengan Android |
| `Exporters.swift` | CSV, GPX 1.1, GeoJSON, JSON Lines |
| `UpdateChecker.swift` | Manifes pembaruan `lereng.ios-update/1` |
| `AppModel.swift` | Keadaan aplikasi, rekaman, ekspor, identitas |
| `*View.swift` | Rekam, Analisis, Data, Profil, peta rute, gerbang pembaruan |
| `Assets.xcassets/` | Ikon aplikasi (dibuat oleh `tools/make_ios_icon.py`) |
| `LerengOfflineTests/` | Uji codec BLE, ekspor, SQLite, dan manifes pembaruan |

## Kesetaraan data dengan Android

- Tabel dan kolom SQLite sama, sehingga berkas ekspor dari kedua platform seragam.
- `source` untuk titik iOS adalah `iOS Core Location`; referensi elevasi diturunkan menjadi
  "Core Location altitude; approximate mean sea level (orthometric)". Android memakai
  "WGS84 ellipsoid". Keduanya **tidak** dikonversi.
- GPX hanya menulis `<ele>` bila semua titik jalur memakai satu referensi elevasi. Referensi
  ditulis di `metadata/extensions/lereng:elevationReference`.
- GeoJSON memakai skema `lereng.geojson/1`, JSON Lines memakai `lereng.mongo/1` (satu Fitur per
  baris) sehingga dapat diimpor dengan `tools/geojson_to_mongodb.py`.

## BLE antar platform

| Arah | Mekanisme |
| --- | --- |
| iOS → Android | iOS meng-advertise service UUID dan menyediakan karakteristik GATT `...4c6572656e67`; Android terhubung lalu membaca 10 byte tersebut. |
| iOS → iOS | Sama, lewat GATT (iOS tidak mengizinkan Service Data kustom di advertisement). |
| Android → iOS | iOS membaca Manufacturer Data (`company ID 0xFFFF` + 10 byte) dari scan response. **Android belum menyiarkannya**; tanpa perubahan itu iOS hanya melihat perangkat iOS. |

Payload 10 byte = 6 byte pertama SHA-256 dari kode outing + 4 byte ID peserta. Tidak ada MAC,
RSSI, jarak, atau posisi yang dipertukarkan maupun disimpan.

Untuk menutup celah Android → iOS, `BlePresenceController` di Android perlu menambahkan
`AdvertiseData` manufacturer `0xFFFF` berisi payload yang sama sebagai scan response.

## Pembaruan

`AppConfig.updateManifestURL` menunjuk ke
`https://raw.githubusercontent.com/RenzIP/LerengApps/main/updates/ios-update.json`.

- Skema `lereng.ios-update/1`; pemeriksaan otomatis paling sering setiap 6 jam, plus tombol
  manual di tab Profil.
- Semua kegagalan bersifat fail-open: aplikasi tetap dapat dipakai.
- `mandatory: true` atau `minSupportedBuild` di atas build terpasang menampilkan lembar yang
  tidak dapat ditutup.
- iOS **tidak** dapat memasang IPA sendiri, dan tanpa akun berbayar juga tidak ada App Store.
  Karena itu manifes memakai `downloadUrl` yang menunjuk ke halaman GitHub Release; lembar
  pembaruan menampilkan tombol "Buka halaman unduhan".
- `appStoreUrl`/`testFlightUrl` tetap didukung bila kelak aplikasi terbit di toko Apple.
- Manifest yang dipublikasikan saat ini memakai `build: 1`, sama dengan build aplikasi, sehingga
  tidak menampilkan dialog apa pun.

## Distribusi

### Tanpa akun Apple Developer (jalur saat ini)

Apple mewajibkan akun berbayar hanya untuk App Store dan TestFlight. Untuk pemakaian sendiri
dan rombongan kecil, IPA tanpa tanda tangan bisa dipasang dengan Apple ID biasa (gratis) lewat
Sideloadly atau AltStore.

1. Workflow `.github/workflows/ios-release.yml` membuat IPA tanpa tanda tangan setiap kali tag
   `ios-v*` di-push, lalu melampirkannya ke GitHub Release.
2. Unduh IPA dari halaman Release, lalu tanda tangani ulang dengan Apple ID sendiri:
   - Windows: [Sideloadly](https://sideloadly.io) + Apple Devices/iTunes dari Apple.
   - Windows/Mac: [AltStore](https://altstore.io) (bisa memperbarui otomatis selama tersambung).
3. Di iPhone, percayai sertifikat developer di **Pengaturan → Umum → VPN & Manajemen Perangkat**.

Batasan yang tidak bisa diakali tanpa akun berbayar:

| Batas | Akun gratis |
| --- | --- |
| Masa berlaku aplikasi | 7 hari, harus ditandatangani ulang |
| Jumlah aplikasi sideload | 3 per Apple ID |
| Distribusi ke orang lain | hanya dengan Apple ID masing-masing |
| TestFlight / App Store | tidak tersedia |

Karena itu, pembaruan iOS tidak bisa dipaksakan seperti Android. Manifes hanya dapat membuka
halaman unduhan; pengguna tetap harus memasang ulang dari komputer.

### Dengan akun berbayar (opsional, untuk distribusi rapi)

1. Daftar Apple Developer Program, buat App ID `id.bagas.lerengoffline.ios`.
2. Di Xcode: *Product → Archive* → *Distribute App* → App Store Connect.
3. Isi TestFlight untuk penguji internal, lalu ajukan ke App Review untuk publik.
4. Setelah lolos, perbarui `updates/ios-update.json` dengan URL App Store yang sebenarnya,
   naikkan `build`, dan setel `mandatory`/`minSupportedBuild` sesuai kebutuhan. Naikkan juga
   `CURRENT_PROJECT_VERSION` di `ios/project.yml` agar nomor build cocok.

Kebijakan privasi yang perlu disiapkan untuk App Review: aplikasi mengakses lokasi di latar
belakang hanya saat merekam, memakai Bluetooth hanya untuk bertukar ID samaran, dan tidak
mengirim data ke server mana pun selain satu permintaan JSON manifes pembaruan.

## Batasan yang diketahui

- Uji otomatis berjalan pada simulator; BLE, GPS fisik, dan perilaku latar belakang harus diuji
  di perangkat.
- `allowsBackgroundLocationUpdates` membutuhkan mode latar belakang `location` (sudah ada di
  `project.yml`).
- Aplikasi tidak menyediakan ubin peta offline sendiri; peta memakai kerangka peta sistem dan
  hanya menampilkan wilayah yang sudah di-cache iOS.
