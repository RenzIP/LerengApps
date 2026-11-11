# Lereng Offline untuk iOS

Aplikasi native SwiftUI dengan perilaku yang sama seperti aplikasi Android: merekam jalur
pendakian secara offline, menandai waypoint, mencatat kehadiran BLE, dan mengekspor data ke
berkas lokal. Tidak ada konversi datum. Endpoint HTTPS dan kunci aplikasi sudah **tertanam saat
build**, jadi data baru langsung dikirim **otomatis** ke MongoDB milik pengguna—tanpa isian apa
pun dan tanpa tombol kirim. Jalur pembanding GPX tidak ikut.

## Status

- Kode sumber lengkap, target iOS 16.
- Proyek Xcode **dibuat** oleh [XcodeGen](https://github.com/yonaskolb/XcodeGen) dari
  `project.yml` — tidak ada `.xcodeproj` yang di-commit, sehingga tidak ada konflik proyek.
- Dikompilasi dan diuji di GitHub Actions (`.github/workflows/ios.yml`, runner `macos-latest`,
  simulator iPhone dipilih otomatis) karena tidak ada Mac di mesin pengembangan. Status terakhir:
  build sukses dan seluruh uji unit lulus.

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
| `MongoSync.swift` | Sinkron otomatis inkremental ke endpoint HTTPS; endpoint + kunci bawaan dari Info.plist |
| `BackgroundSync.swift` | `BGAppRefreshTask` best effort untuk sinkron latar belakang |
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
  baris) sehingga dapat diimpor dengan `tools/geojson_to_mongodb.py`. Sinkron otomatis memakai
  `lereng.mongo/1` yang sama dengan `_id` stabil per record.

## Sinkron otomatis

Tab **Data** → bagian **Sinkron otomatis ke MongoDB** langsung aktif: alamat endpoint dan kunci
aplikasi dibaca dari `Info.plist` (`LerengSyncEndpoint`, `LerengSyncToken`) yang diisi
`project.yml` saat build. Tidak ada yang perlu diketik setelah aplikasi terpasang.

Kunci itu ikut di dalam IPA, jadi **bukan rahasia**; pengamanannya ada di server (validasi
skema, batas ukuran, batas jumlah Fitur, dan batas laju per IP). Mengganti kunci berarti membangun
ulang aplikasi dan memperbarui `INGEST_TOKEN` di server. Alamat dan kunci masih dapat ditimpa
lewat **Pengaturan lanjutan** (disimpan di UserDefaults dan Keychain); tombol
**Kembalikan ke bawaan** menghapus timpaan itu.

Sinkron berjalan untuk **semua proyek** dan hanya mengirim record di atas tanda air lokal per
proyek, sehingga titik lama tidak dikirim ulang.

- Pemicu: titik/waypoint/presence baru (ditunda agar beruntun tergabung), aplikasi kembali aktif,
  rekaman dihentikan, dan `BGAppRefreshTask` (`id.bagas.lerengoffline.sync`) bila iOS memberi
  kesempatan.
- iOS tidak menjamin eksekusi background. Sinkron foreground tetap berjalan tanpa itu.
- Tanpa internet, data menunggu dan dikirim saat koneksi kembali. Sakelar dapat dijeda kapan saja.
- Metadata proyek dikirim ulang hanya bila nama atau identitas berubah.
- Jalur pembanding GPX dan dataset elevasi tidak pernah disinkronkan.

## BLE antar platform

| Arah | Mekanisme |
| --- | --- |
| iOS → Android | iOS meng-advertise service UUID dan menyediakan karakteristik GATT `...4c6572656e67`; Android terhubung lalu membaca 10 byte tersebut. |
| iOS → iOS | Sama, lewat GATT (iOS tidak mengizinkan Service Data kustom di advertisement). |
| Android → iOS | Android (v2.5.2 ke atas) menaruh Manufacturer Data `company ID 0xFFFF` + payload **8 byte** di scan response; iOS membacanya langsung dari `advertisementData`. |

Payload Service Data 10 byte = 6 byte pertama SHA-256 dari kode outing + 4 byte ID peserta.
Manufacturer data memakai bentuk ringkas 8 byte (4 + 4) karena scan response hanya 31 byte:
service UUID 128-bit sudah memakan 18 byte, sehingga payload 10 byte + 2 byte company ID tidak
muat. Keduanya diterima pembaca; tidak ada MAC, RSSI, jarak, atau posisi yang dipertukarkan
maupun disimpan.

Aplikasi iOS build 2 (v1.1.0) adalah versi pertama yang membaca bentuk 8 byte tersebut. Build 1
hanya memahami bentuk 10 byte, jadi Android→iOS baru terlihat setelah aplikasi diperbarui.

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
