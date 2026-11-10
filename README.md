# Lereng Offline 2.5.1

Aplikasi Android untuk merekam **satu jalur pendakian bersama rombongan** dan menampilkan profil elevasinya. Track, event BLE, serta dataset elevasi pilihan pengguna tersimpan di SQLite privat pada perangkat. Tidak ada API elevasi pihak ketiga, unduhan dataset otomatis, atau unggahan track.

## Permukaan terrain lokal Android

Kartu **Permukaan Terrain Lokal** menyediakan kontur 2D dan permukaan 3D oblique/isometric yang dihitung hanya dari titik elevasi lokal milik pengguna: titik GPS/GNSS ber-elevasi dari proyek/sesi saat ini atau sampel CSV dataset lokal aktif. Tidak ada DEM bawaan, data geografis contoh, internet elevation API, atau sumber pihak ketiga. Perhitungan berjalan asynchronous agar impor besar tidak membekukan UI.

Mesin memproyeksikan WGS84 secara lokal ke meter, membatasi grid pada bounding box sampel, dan tidak melakukan extrapolation di luar dukungan. Grid maksimal 56×56 node; sebuah node hanya sah bila memiliki sedikitnya empat sampel dalam radius 250 m yang tersebar di empat kuadran, lalu memakai paling banyak delapan tetangga terdekat dengan IDW. Cell hanya sah bila keempat sudutnya sah. Selain itu, permukaan/kontur baru dirender bila coverage sekurangnya 25%, terdapat minimal 8 cell valid, dan setidaknya 60% cell valid tersambung; angka ini adalah gate tampilan konservatif, bukan klaim akurasi. Di bawahnya, cell interpolasi yang terpecah disembunyikan dan hanya titik elevasi asli tampil. Sedikitnya 12 lokasi distinct diperlukan; lintasan hampir segaris hanya menampilkan marker. Blank berarti belum ada coverage—tambahkan survei silang atau CSV yang lebih rapat.

Datum dinormalisasi konservatif: Android `WGS84`/`ellipsoid` berada dalam satu kelompok; `EGM2008`/`mean sea level` hanya cocok dengan label yang jelas sama; datum unknown tetap kelompok terpisah. Kelompok datum berbeda tidak pernah digabung. Kontur adalah interpolasi lokal di dalam dukungan sampel, bukan DEM area luas dan bukan hasil survey-grade.

## Impor dataset elevasi milikmu

Di kartu **Data Elevasi Lokal**, pilih **Impor CSV elevasi** lalu pilih file melalui pemilih dokumen Android. Aplikasi menyalin sampel ke database lokal, meminta nama dataset dan label datum vertikal, lalu otomatis memilihnya untuk proyek yang sedang aktif. Proyek lain dapat memilih dataset yang sama, melepasnya, atau memakai dataset lain. Penghapusan dataset meminta konfirmasi dan melepasnya dari semua proyek; rekaman track asli tidak terhapus.

Format yang diterima adalah daftar titik CSV dengan header seperti:

```csv
latitude,longitude,elevation_m
```

Tambahkan satu baris per sampel dengan nilai numerik, misalnya `<latitude>,<longitude>,<elevasi_meter>`.

Gunakan latitude/longitude WGS84 dalam derajat desimal dan elevasi meter. Parser juga menerima alias umum seperti `lat`, `lon`/`lng`, serta `elevation`, `elev`/`elev_m`, `altitude`, `z`, atau `height_m`. Diperlukan minimal 4 sampel valid; maksimum 250.000 sampel per file. Baris koordinat/elevasi tidak valid dan sentinel NoData `-9999` dilewati. Aplikasi **tidak membaca raster GeoTIFF langsung**; jika dataset sumbermu raster atau format lain, siapkan/ekspor dahulu sebagai CSV titik XYZ milikmu.

Nama datum—misalnya EGM96, tinggi lokal, atau "Tidak diketahui"—disimpan sebagai keterangan saja. Aplikasi tidak mengubah atau mengonversi datum. Pastikan satu file memakai datum dan satuan yang konsisten; track mentah tidak diubah. Database aplikasi tidak dicadangkan otomatis dan file dataset tidak ikut dalam ekspor track CSV/GeoJSON/GPX/JSONL.

## Cara koreksi profil

Sesudah track dan dataset tersedia, profil dihitung ulang secara lokal. Untuk setiap stasiun rute, aplikasi mencari sampel terdekat dalam radius maksimum 150 m dan memakai hingga 4 sampel dengan pembobotan jarak (IDW); kecuali ada sampel tepat di titik rute, dibutuhkan setidaknya 3 sampel tetangga. Nilai NoData tidak dipakai.

Koreksi diterapkan **hanya bila setiap stasiun profil tertutup oleh dataset**. Jika cakupan parsial, seluruh profil GPS yang lengkap dipertahankan dan layar menjelaskan jumlah stasiun yang tercakup; bila GPS juga tidak menyediakan elevasi lengkap, profil elevasi tidak ditampilkan daripada menyajikan garis parsial/NaN. Bila cakupan penuh, profil serta total naik/turun dihitung dari dataset, termasuk saat GNSS tidak memberi altitude; keterangan nama dan datum dataset ditampilkan. Track GPS/rombongan asli tetap tersimpan untuk diperiksa atau diekspor.

Profil tetap merupakan perkiraan dari dataset yang kamu sediakan, bukan survei resmi. Resolusi, kualitas, keselarasan horizontal, NoData, dan datum sumber memengaruhi hasil. Untuk koreksi yang stabil, gunakan dataset milikmu yang mencakup seluruh rute dengan sampel cukup rapat. Aplikasi tidak memilih gunung atau sumber data untukmu.

## Perekaman dan rombongan

- Lokasi Android berbasis jaringan dan GPS/GNSS diminta bersamaan bila tersedia. Jika internet tervalidasi, provider jaringan sistem dapat membantu memberi fix. Saat internet putus, fix jaringan lama dibuang dan GNSS tetap diminta; perekaman tidak dijeda. Ketersediaan tetap bergantung perangkat dan layanan lokasi Android.
- Track, waktu, akurasi, elevasi yang tersedia, dan tekanan barometer opsional disimpan lokal. Elevasi Android `Location` memakai ellipsoid WGS84 dan bukan otomatis tinggi permukaan laut.
- Untuk rombongan, gunakan kode outing sama pada setiap ponsel, rekam tiap peserta, lalu gabungkan CSV secara manual setelah perjalanan.
- BLE opsional mendukung Android↔Android, iPhone↔iPhone, dan Android↔iPhone. Android menyiarkan Service Data berisi tag hash outing + ID peserta samaran; iPhone dapat membacanya. Untuk iPhone, Android v2.3 menemukan service UUID lalu membaca characteristic GATT read-only. Aplikasi hanya mencatat ID dan waktu event; tidak menyimpan alamat/RSSI dan tidak menghitung jarak, posisi, elevasi, atau triangulasi.
- Android 12+ meminta izin Nearby Devices **Scan, Advertise, Connect** untuk BLE. Android 14+ memakai tipe foreground service `connectedDevice` saat BLE aktif, bersama tipe `location` untuk perekaman.
- iOS dapat membatasi scan/advertising di background dan memindahkan service UUID iklan ke overflow area yang tidak terlihat Android. Karena itu deteksi silang paling dapat diandalkan saat app iPhone foreground; Android tidak dapat menjamin mendeteksi iPhone saat layar terkunci. BLE bukan alat keselamatan atau pencatat keberadaan dengan jaminan waktu nyata.
- Profil jalur mengikuti track peserta dengan titik terbanyak. Tanpa dataset lokal aktif, elevasi mentah peserta hanya digabung bila dekat secara lokasi/waktu **dan datumnya sejenis**; Android WGS84 ellipsoid dipisahkan dari iOS Core Location mean sea level. Dataset lokal yang mencakup seluruh jalur dapat menjadi acuan bersama. Ambang 3 m membantu mengurangi sebagian noise, bukan menjamin akurasi.

Saat provider Network Android digunakan, layanan sistem/vendor dapat memproses konektivitas/lokasi di luar aplikasi. Aplikasi ini sendiri tidak mengirim track atau meminta elevation API. Jika perangkat tak mendapat fix jaringan maupun GNSS, aplikasi tak dapat menciptakan lokasi.

## Ekspor, GPX, dan MongoDB

Tab **Data** menyediakan empat jalur ekspor:

- **CSV grup** — semua titik dan event BLE, untuk digabungkan antar peserta.
- **GPX** — track dan waypoint; jika proyek memuat lebih dari satu referensi elevasi, aplikasi meminta satu referensi dan titik lain diekspor tanpa `<ele>`.
- **GeoJSON** — satu `LineString` rute plus `Point` waypoint dan metadata `lereng`. Geometri rute dibuat 2D bila referensi elevasi tidak seragam, agar datum tidak tercampur diam-diam.
- **JSONL (MongoDB)** — satu Feature GeoJSON per baris untuk track, waypoint, dan presence BLE.

Aplikasi tidak menghubungi MongoDB. Impor dilakukan dari komputer memakai URI milikmu:

```powershell
python -m pip install -r tools/requirements.txt
$env:MONGODB_URI = "mongodb+srv://..."
python tools/geojson_to_mongodb.py --db lereng --collection tracks "D:\ekspor\Gede_mongo.jsonl"
```

Skrip membuat index `2dsphere`, memberi `_id` deterministik agar impor ulang tidak menggandakan data, dan mendukung `.geojson` maupun `.jsonl`. Detail, contoh query, dan catatan keamanan ada di [tools/README-mongodb.md](tools/README-mongodb.md). **Jangan menaruh URI atau kredensial di dalam APK/repo.**

## Pembaruan aplikasi

Versi 2.5.0 menambahkan pemeriksaan pembaruan lewat satu manifes JSON publik. Aplikasi hanya
melakukan `GET` HTTPS ke manifes itu (±7 detik timeout, maksimal 64 KB) dan membandingkan
`versionCode`. Tidak ada data pengguna yang dikirim dan tidak ada unggahan.

- Jika versi di manifes lebih baru, muncul dialog pembaruan.
- Jika `mandatory: true` atau build terpasang di bawah `minSupportedVersionCode`, dialog tidak
  bisa ditutup: hanya **Perbarui sekarang** (membuka tautan APK di browser) atau **Keluar**.
- Offline atau server gagal = aplikasi tetap berjalan normal (fail-open).
- Tanpa `lerengUpdateUrl` saat build, fitur ini nonaktif dan APK tidak menghubungi apa pun.

```powershell
# hasilkan manifes dari APK yang akan dirilis
python tools\publish_update.py --apk app\build\outputs\apk\debug\app-debug.apk `
  --version-code 10 --version-name 2.5.1 `
  --apk-url https://contoh.example/lereng/LerengOffline-2.5.1.apk --mandatory

# bangun APK yang menunjuk ke manifes tersebut
.\gradlew.bat assembleDebug -PlerengUpdateUrl="https://contoh.example/lereng/update.json"
```

Detail format, pilihan hosting, dan jebakan (kunci tanda tangan harus tetap) ada di
[updates/README.md](updates/README.md).

Rilis proyek ini memakai GitHub:

- manifes: `https://raw.githubusercontent.com/RenzIP/LerengApps/main/updates/update.json`
- APK: halaman [Releases](https://github.com/RenzIP/LerengApps/releases)

## Instalasi dan build

Pasang `LerengOffline-v2.5.1.apk` pada Android 8.0 atau lebih baru. Ini APK debug untuk sideload/pengujian. Source dapat dibangun dengan JDK 17+ dan Android SDK platform/build-tools 35:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

## Rujukan platform Android

- [LocationManager](https://developer.android.com/reference/android/location/LocationManager) — provider jaringan dan GPS/GNSS.
- [Location](https://developer.android.com/reference/android/location/Location) — akurasi dan datum altitude WGS84.
- [Status koneksi jaringan](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) dan [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
- [Advertiser BLE dan scan response](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser) — lihat juga [catatan kompatibilitas Android/iOS](ANDROID_BLE_CROSS_PLATFORM_REFERENCE.md).
