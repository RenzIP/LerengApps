# README — Analisis `waleavenza.kml`

## Apa ini?

`waleavenza.kml` adalah file peta digital berformat KML. File seperti ini bisa dibuka di Google Earth, QGIS, dan aplikasi peta lain yang mendukung KML. Isinya bukan gambar peta saja, melainkan data lokasi: titik, garis jalur, dan rekaman GPS.

## Ringkasan isi

- Nama dokumen di dalam file: `SURVEY jalur`.
- Ada sekitar 283 placemark KML, termasuk 212 titik, 20 garis, dan 51 rekaman `gx:Track`. Sejumlah folder dan track merupakan salinan/duplikat.
- Rekaman track berisi sekitar 49.012 sampel GPS bertanggal 18 Desember 2022 sampai 14 Desember 2024. Data track juga menyimpan waktu, ketinggian, kecepatan, serta perkiraan akurasi horizontal dan vertikal.
- Nama penanda antara lain basecamp, CP 1–8, camp merah, camp siswa, persimpangan, cabang jalur, titik SAR, lokasi korban, dan rencana evakuasi.
- Nama jalur yang terlihat termasuk `FULL JALUR DIKSAR 38`, `ELNINO-KOPI BARU`, dan `KOPI BARU - TUNGGUL`.

## Wilayah dan kemungkinan kegunaannya

Koordinat mayoritas berkumpul di sekitar Bukit Tunggul/Bincarung, Jawa Barat. Dari nama-nama titiknya, file ini tampaknya dibuat untuk survei jalur pendakian, menandai pos dan persimpangan, merencanakan camp, serta mencatat titik SAR atau evakuasi.

File ini berguna untuk:

1. Menampilkan dan membandingkan jalur pendakian.
2. Menemukan titik seperti basecamp, camp, pos, dan cabang jalur.
3. Meninjau rekaman GPS lama beserta waktu dan ketinggian yang tercatat.
4. Membantu perencanaan survei dan respons SAR.

## Batasan dan hal yang perlu diperiksa

- Ini bukan DEM atau raster elevasi seluruh gunung. Jadi file ini dapat menunjukkan jalur dan titik ukur, tetapi tidak cukup untuk membuat permukaan 3D atau kontur lengkap di luar area yang benar-benar terukur.
- Beberapa garis memakai mode `clampToGround`; angka elevasi `0` pada garis seperti itu bukan berarti lokasinya berada di ketinggian 0 meter.
- Ada dua penanda yang tampak jauh dari kumpulan data di Jawa Barat dan kemungkinan salah tersimpan:
  - `Tanda tempat 1`: sekitar 55.023° LU, 58.465° BT.
  - `Tanda tempat 2`: sekitar 80.928° LS, 80.445° BT.
- Folder dan sebagian rekaman jalur terduplikasi. Sebaiknya periksa sebelum menghitung jumlah jalur atau menggabungkan statistik.
- Ketinggian dan akurasi GPS dalam file adalah hasil perangkat saat merekam, bukan hasil ukur survei profesional.

## Kesimpulan

Perlakukan `waleavenza.kml` sebagai arsip jalur pendakian dan waypoint survei/SAR. Untuk membuat kontur atau permukaan 3D yang lebih luas, masih dibutuhkan sampel elevasi yang mencakup area itu secara rapat atau dataset DEM terpisah.
