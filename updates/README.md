# Pembaruan aplikasi (in-app update)

Aplikasi bisa memeriksa satu berkas JSON publik berisi versi terbaru. Jika versi di manifes
lebih tinggi dari yang terpasang, aplikasi menampilkan dialog pembaruan. Jika pembaruan
ditandai wajib, dialog tidak bisa ditutup sampai versi baru dipasang (tombol hanya
**Perbarui sekarang** dan **Keluar**).

Ini **bukan** Play Store In-App Updates. Aplikasi dipasang lewat sideload, jadi mekanismenya:
manifes JSON → tautan APK HTTPS → pemasang paket Android.

## Prinsip yang dijaga

- **Fail-open.** Offline, DNS gagal, TLS gagal, HTTP error, atau JSON rusak → aplikasi tetap
  berjalan normal. Pemeriksaan wajib tidak pernah mengunci aplikasi saat tidak ada jaringan.
- **Tidak ada data pengguna yang dikirim.** Hanya `GET` satu berkas JSON. Tidak ada ID perangkat,
  lokasi, atau track yang ikut. Header hanya `User-Agent: LerengOffline/<versi>`.
- **HTTPS saja.** Manifes dan tautan APK harus `https://`; pengalihan ke non-HTTPS ditolak.
- **Batas ukuran.** Manifes maksimal 64 KB dan dibaca dengan timeout 7 detik.
- **Nonaktif secara bawaan.** Jika `lerengUpdateUrl` kosong saat build, APK tidak menghubungi
  server mana pun dan tombol "Cek pembaruan" hanya menjelaskan bahwa fitur belum dikonfigurasi.

## Format manifes (`lereng.update/1`)

```json
{
  "schema": "lereng.update/1",
  "versionCode": 10,
  "versionName": "2.5.1",
  "mandatory": false,
  "minSupportedVersionCode": 9,
  "apkUrl": "https://contoh.example/lereng/LerengOffline-2.5.1.apk",
  "sizeBytes": 298736,
  "sha256": "…",
  "notes": ["Perbaikan peta rute offline", "Ekspor JSONL lebih cepat"],
  "publishedAt": "2026-11-09T00:00:00Z"
}
```

| Field | Wajib | Arti |
| --- | --- | --- |
| `schema` | ya | Harus `lereng.update/1`. Skema lain ditolak. |
| `versionCode` | ya | Harus lebih besar dari `versionCode` yang terpasang agar dianggap pembaruan. |
| `versionName` | tidak | Ditampilkan di dialog; jika kosong dipakai `versionCode`. |
| `mandatory` | tidak | `true` = dialog tidak bisa ditutup. |
| `minSupportedVersionCode` | tidak | Build dengan kode di bawah nilai ini dipaksa memperbarui, walau `mandatory` false. |
| `apkUrl` | ya | Tautan **langsung** ke file `.apk`, harus HTTPS. |
| `sizeBytes`, `sha256` | tidak | Informasi rilis; ukuran dipakai untuk label di dialog. |
| `notes` | tidak | String atau array string; ditampilkan sebagai daftar. |
| `publishedAt` | tidak | Keterangan waktu publikasi. |

Cara memaksa pembaruan:

- rilis tertentu: `"mandatory": true`;
- memutus dukungan versi lama secara permanen: naikkan `minSupportedVersionCode`
  (mis. rilis 3.0.0 memasang `minSupportedVersionCode: 10`, sehingga semua build ≤ 9 wajib
  memperbarui). Cara ini lebih baik daripada `mandatory` karena tetap berlaku untuk rilis
  berikutnya.

## Membuat manifes

```powershell
python tools\publish_update.py `
  --apk app\build\outputs\apk\debug\app-debug.apk `
  --version-code 10 --version-name 2.5.1 `
  --apk-url https://contoh.example/lereng/LerengOffline-2.5.1.apk `
  --mandatory --notes "Perbaikan peta rute" `
  --out updates\update.json
```

Skrip menghitung `sizeBytes` + `sha256`, memvalidasi HTTPS, lalu menulis `updates/update.json`.
APK-nya sendiri diunggah terpisah.

## Menyambungkan URL ke APK

URL dibaca saat build, bukan disimpan di database:

```powershell
.\gradlew.bat assembleDebug -PlerengUpdateUrl="https://contoh.example/lereng/update.json"
```

atau isi di `gradle.properties` (lokal, jangan berisi kredensial—URL ini publik):

```properties
lerengUpdateUrl=https://contoh.example/lereng/update.json
```

URL kosong = fitur mati.

## Pilihan hosting

| Cara | Manifes | APK | Catatan |
| --- | --- | --- | --- |
| GitHub Releases + raw | `raw.githubusercontent.com/.../update.json` | aset Release | gratis, publik, tanpa server |
| GitHub Pages | `https://user.github.io/repo/update.json` | sama atau Release | perlu repo publik |
| Object storage (R2/S3) | URL publik | URL publik | butuh akun |
| Web server sendiri | URL apa pun asal HTTPS | sama | butuh sertifikat |

## Yang wajib diperhatikan

1. **Kunci tanda tangan harus tetap.** Android hanya mau memasang pembaruan jika tanda tangan
   APK sama dengan yang terpasang. Debug APK memakai `~/.android/debug.keystore` di komputer ini; kalau
   keystore hilang atau build dari komputer lain, pembaruan akan gagal dengan
   "Aplikasi tidak terpasang". Untuk distribusi nyata, buat keystore rilis tetap.
2. **Izin pemasangan.** Pengguna harus mengizinkan browser/aplikasi file memasang APK
   ("Instal aplikasi tidak dikenal"). Aplikasi tidak meminta izin `REQUEST_INSTALL_PACKAGES`
   karena unduhan dibuka lewat browser.
3. **Jangan menaikkan `versionCode` tanpa merilis manifes.** Jika tidak, pengguna tidak pernah
   tahu ada versi baru.
4. **Uji dulu dengan `mandatory: false`.** Pembaruan wajib yang salah konfigurasi bisa membuat
   pengguna tidak bisa memakai aplikasi (mereka masih bisa keluar, tetapi tidak bisa memakai
   fitur).
5. **Manifes bisa di-cache.** Aplikasi mengirim `Cache-Control: no-cache`; jika memakai CDN,
   lakukan purge setelah mengganti manifes.

## Perilaku di aplikasi

- Diperiksa saat aplikasi dibuka dan saat kembali ke depan, maksimal sekali per 6 jam.
- Tombol **Cek pembaruan** di kartu "Tentang aplikasi" memaksa pemeriksaan segera.
- Pembaruan opsional bisa ditunda 24 jam; pembaruan wajib tidak bisa ditunda.
- Saat pengguna kembali dari browser tanpa memasang versi baru, dialog wajib muncul lagi.

## iOS (`updates/ios-update.json`)

Aplikasi iOS memakai manifes terpisah dengan skema `lereng.ios-update/1` karena iOS tidak
dapat memasang IPA sendiri. Aplikasi hanya membuka App Store atau TestFlight.

```json
{
  "schema": "lereng.ios-update/1",
  "build": 2,
  "version": "1.1.0",
  "minSupportedBuild": 1,
  "mandatory": false,
  "appStoreUrl": "https://apps.apple.com/app/id0000000000",
  "testFlightUrl": "https://testflight.apple.com/join/00000000",
  "notes": ["Perbaikan peta rute offline"],
  "publishedAt": "2026-11-10T00:00:00Z"
}
```

| Field | Wajib | Arti |
| --- | --- | --- |
| `schema` | ya | Harus `lereng.ios-update/1`. |
| `build` | ya | `CFBundleVersion` terbaru; harus lebih besar dari build terpasang. |
| `version` | tidak | Ditampilkan di lembar pembaruan. |
| `minSupportedBuild` | tidak | Build di bawah nilai ini dipaksa memperbarui. |
| `mandatory` | tidak | `true` = lembar tidak bisa ditutup. |
| `appStoreUrl` / `testFlightUrl` | ya (salah satu) | Harus HTTPS. |
| `notes` | tidak | String atau array string. |

Manifes yang dipublikasikan sekarang memakai `build: 1`, sama dengan build aplikasi, jadi tidak
ada dialog yang muncul. Ganti URL toko dengan tautan App Store yang sebenarnya setelah aplikasi
lolos App Review.

## iOS vs Android

| Aspek | Android | iOS |
| --- | --- | --- |
| Skema manifes | `lereng.update/1` | `lereng.ios-update/1` |
| Tindakan | unduh + pasang APK | buka App Store/TestFlight |
| Pemasangan mandiri | ya (lewat pemasang paket) | tidak (batasan iOS) |
| Penandatanganan | kunci APK sama | sertifikat Apple, ditangani App Store |

