# Referensi lokasi jaringan Android (v2.1.0)

Implementasi Lereng Offline menggunakan `LocationManager.NETWORK_PROVIDER` dan `LocationManager.GPS_PROVIDER` secara paralel. Pemilihan provider jaringan adalah fasilitas lokasi sistem Android; dokumentasi resmi menyebutnya dapat memakai titik akses seluler/Wi‑Fi dan dapat memerlukan koneksi data. Jika provider jaringan tidak tersedia atau koneksi terputus, service tetap meminta GNSS dan tidak menghentikan perekaman.

Koneksi divalidasi dengan `ConnectivityManager` dan `NET_CAPABILITY_VALIDATED`, mengikuti panduan Android. Pemantauan ini hanya untuk menampilkan status dan membuang kandidat fix jaringan yang sudah tidak relevan; aplikasi tidak mengirim track atau mengunduh data elevasi sendiri.

`Location.getAltitude()` didokumentasikan Android sebagai meter di atas ellipsoid referensi WGS84. Akurasi lokasi horizontal bukan ukuran akurasi elevasi.

Sumber resmi:

- [Android `LocationManager`](https://developer.android.com/reference/android/location/LocationManager) — daftar provider; dokumentasi `NETWORK_PROVIDER` menyebut basis menara seluler/titik akses Wi‑Fi dan kemungkinan memerlukan koneksi data.
- [Android `Location`](https://developer.android.com/reference/android/location/Location) — keterangan `getAltitude()` serta definisi akurasi horizontal.
- [Membaca status jaringan](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) — `ConnectivityManager.NetworkCallback`, `NET_CAPABILITY_INTERNET`, dan `NET_CAPABILITY_VALIDATED`.

Ketersediaan dan mutu provider jaringan bergantung perangkat, Android, pengaturan lokasi, dan penyedia sistem yang terpasang. Saat tidak ada provider jaringan yang memberi fix, lokasi hanya berasal dari GNSS dan tetap dapat meleset.