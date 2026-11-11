# Endpoint MongoDB untuk Lereng Offline

Folder ini berisi satu fungsi serverless yang menerima data dari tombol
**Sinkron otomatis** di aplikasi. Server ini yang memegang `MONGODB_URI`;
APK dan IPA sudah membawa alamat endpoint serta kunci aplikasi sejak build, sehingga tidak ada
yang perlu diisi pengguna. Kunci itu ikut di dalam berkas aplikasi dan **bukan rahasia**—server
yang memegang `MONGODB_URI` dan melakukan validasi serta pembatasan.

Data ditulis sebagai satu dokumen per Fitur GeoJSON:

```
_id: "client:survey:track:123"
type: "Feature"
geometry: { type: "Point", coordinates: [lon, lat, alt?] }
properties: { schema, project, record_type, time_ms, ... }
ingest: { client_id, platform, app_version, project, survey_id, received_at }
```

`record_type` berisi `track`, `waypoint`, `presence`, atau `project`.
Fitur `presence` dan `project` tidak punya geometri. Setiap `_id` stabil,
jadi mengirim ulang data yang sama hanya memperbarui dokumen—tidak membuat
duplikat.

## 1. Siapkan MongoDB Atlas

1. Buat cluster gratis di <https://www.mongodb.com/atlas>.
2. **Database Access** → tambah user dengan password kuat.
3. **Network Access** → izinkan alamat server yang dipakai:
   - Vercel: `0.0.0.0/0`, karena IP keluar Vercel berubah-ubah.
   - Docker di rumah: cukup IP publik rumahmu (`whatismyip`), lebih ketat.
     Kalau IP dari ISP berubah, sinkron akan gagal sampai daftar diperbarui.
   Batasi juga dengan kredensial yang hanya bisa menulis ke satu database.
4. Salin connection string `mongodb+srv://...`.

## 2a. Jalankan dengan Docker (lokal)

Cara ini tidak butuh akun Vercel. Kontrak ingest tetap satu: `api/ingest.js`
dipakai apa adanya, dan `docker/serve.js` hanya menambahkan pembungkus HTTP.

```bash
cd server
cp .env.example .env      # lalu isi MONGODB_URI
docker compose up -d --build
curl http://127.0.0.1:8080/health
```

`docker compose ps` harus menampilkan status `healthy`. Port sengaja diikat ke
`127.0.0.1` supaya tidak terbuka ke jaringan lokal; akses dari HP lewat tunnel.

Ganti isi `.env` lalu buat ulang container agar variabel terbaca:

```bash
docker compose up -d --force-recreate
```

Perintah harian:

```bash
docker compose logs -f ingest     # lihat log
docker compose restart ingest     # mulai ulang
docker compose down               # hentikan
```

### Konsekuensi menjalankan di rumah

Server hanya hidup saat komputer menyala dan online. Kalau komputer mati,
sinkron berhenti—tetapi data tetap aman di SQLite perangkat dan dikirim saat
server kembali hidup. Untuk server yang selalu siap, pakai Vercel (bagian 2b).

### Alamat HTTPS untuk HP

HP tidak bisa memakai `http://127.0.0.1:8080`. Perlu tunnel HTTPS:

| Cara | URL stabil | Catatan |
| --- | --- | --- |
| ngrok (akun gratis) | ya, satu domain statis | paling praktis untuk pemakaian rutin |
| Cloudflare Tunnel | ya, butuh domain sendiri | paling rapi bila punya domain |
| Pinggy lewat `ssh` | tidak, acak tiap sesi | tanpa instalasi apa pun |

Kalau alamat endpoint berubah, aplikasi menganggapnya target baru dan mengirim
seluruh riwayat sekali lagi. Dokumen tetap tidak berganda karena `_id` stabil,
tetapi sebaiknya pakai URL yang tidak berubah.

### Menjaga tetap hidup (Windows)

Tiga hal harus hidup bersamaan: Docker Desktop, container `lereng-ingest`, dan
`cloudflared`. Satu skrip idempoten yang dipanggil Windows Task Scheduler tiap
5 menit cukup untuk ketiganya: skrip menyalakan yang mati dan tidak menyentuh
yang sudah jalan.

Jalankan lewat pembungkus VBS, **bukan** langsung `powershell.exe`. Windows
selalu membuat jendela konsol untuk `powershell.exe` sebelum sempat
menyembunyikannya, sehingga layar berkedip tiap 5 menit. `wscript.exe` adalah
aplikasi GUI, jadi tidak ada konsol yang dibuat sama sekali.

`run-hidden.vbs`:

```vbscript
Set shell = CreateObject("WScript.Shell")
shell.Run "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ""C:\path\autostart.ps1""", 0, True
```

`0` = jendela tersembunyi, `True` = tunggu sampai skrip selesai. Menunggu itu
penting: Task Scheduler mematikan proses anak begitu tugas dianggap selesai, dan
tanpa penantian skrip bisa terhenti saat menunggu mesin Docker siap.

Pasang task-nya sekali:

```powershell
schtasks /Create /TN "Lereng Offline Stack" `
  /TR 'wscript.exe //B //Nologo "C:\path\run-hidden.vbs"' `
  /SC MINUTE /MO 5 /RL LIMITED /F
```

Aktifkan juga **Start Docker Desktop when you sign in** di pengaturan Docker,
supaya container ikut hidup setelah reboot.

## 2b. Deploy ke Vercel

```bash
cd server
npm install
npx vercel
npx vercel --prod
```

Atau impor folder ini sebagai proyek baru di dashboard Vercel.

Tambahkan Environment Variables untuk **Production** (dan Preview):

| Nama | Wajib | Isi |
| --- | --- | --- |
| `MONGODB_URI` | ya | connection string Atlas |
| `INGEST_TOKEN` | ya | kunci aplikasi; harus sama dengan nilai yang ditanam di build (`lerengSyncToken` / `LERENG_SYNC_TOKEN`) |
| `MONGODB_DB` | tidak | default `lereng` |
| `MONGODB_COLL` | tidak | default `features` |

Redeploy setelah menambah variabel. Endpoint akhirnya:

```
https://<proyek>.vercel.app/api/ingest
```

## 3. Uji dari komputer

```bash
curl -X POST "https://<proyek>.vercel.app/api/ingest" \
  -H "Authorization: Bearer <INGEST_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "schema": "lereng.mongo/1",
    "client_id": "uji",
    "platform": "curl",
    "app_version": "test",
    "project": "uji",
    "survey_id": 1,
    "features": [{
      "type": "Feature",
      "geometry": {"type": "Point", "coordinates": [110.3695, -7.7828]},
      "properties": {
        "record_id": "uji:1:track:1",
        "schema": "lereng.mongo/1",
        "project": "uji",
        "record_type": "track",
        "time_ms": 1760000000000
      }
    }]
  }'
```

Balasan sukses:

```json
{"ok":true,"received":1,"inserted":1,"updated":0,"matched":0}
```

## 4. Arahkan aplikasi ke endpoint

Build rilis sudah membawa alamat dan kunci aplikasi, jadi aplikasi langsung mengirim setelah
dipasang. Yang perlu disiapkan hanya sisi server: `INGEST_TOKEN` harus sama dengan kunci yang
ditanam di `gradle.properties` (Android) dan `project.yml` (iOS).

Untuk mengarahkan ke server lain tanpa membangun ulang, buka tab **Data** → **Sinkron
otomatis** → **Pengaturan lanjutan**:

1. Isi endpoint, mis. `https://<proyek>.vercel.app/api/ingest`.
   Alamat dasar tanpa path juga diterima; aplikasi menambahkan `/api/ingest`.
2. Isi kunci yang sama dengan `INGEST_TOKEN`.
3. Tekan **Simpan pengaturan**.

Tombol **Kembalikan ke bawaan** menghapus timpaan dan memakai nilai tertanam lagi.

Setelah itu aplikasi mengirim sendiri data baru dari **semua proyek**: titik track,
waypoint, event presence BLE, dan metadata proyek. Tidak ada tombol kirim.
Pengiriman inkremental—hanya record di atas tanda air lokal yang dikirim—dan
`_id` stabil membuat percobaan ulang aman. Tanpa internet, data menunggu dan
dikirim saat koneksi kembali. Jalur pembanding GPX tidak pernah ikut dikirim.

## 5. Periksa data

Di Atlas, buka database `lereng`, koleksi `features`. Indeks `2dsphere`
dibuat otomatis pada `geometry` saat permintaan pertama.

Contoh query jalur terbaru:

```js
db.features
  .find({ "properties.record_type": "track", "properties.project": "Gunung X" })
  .sort({ "properties.time_ms": 1 })
```

Membentuk satu LineString dari titik satu proyek:

```js
db.features.aggregate([
  { $match: { "properties.record_type": "track", "properties.project": "Gunung X" } },
  { $sort: { "properties.time_ms": 1 } },
  { $group: {
      _id: "$properties.project",
      type: { $first: "Feature" },
      geometry: { $push: "$geometry" }
  } },
  { $project: {
      _id: 0,
      type: "Feature",
      properties: { project: "$_id" },
      geometry: { type: "LineString", coordinates: "$geometry.coordinates" }
  } }
])
```

Ekspor seluruh koleksi sebagai GeoJSON dari komputer:

```bash
mongoexport --uri "$MONGODB_URI" --db lereng --collection features \
  --out lereng.ndjson
```

## Batas dan keamanan

- Maksimum 800 Fitur per permintaan; aplikasi mengirim per 300 Fitur.
- Badan permintaan dibatasi 4 MB dan tiap dokumen 512 KB.
- Hanya `POST` dengan `Authorization: Bearer <INGEST_TOKEN>` yang diterima.
- **Kunci aplikasi bukan rahasia.** Nilainya ikut di APK/IPA dan dapat dibongkar siapa pun yang
  memegang berkas aplikasi. Karena itu pengamanan sebenarnya ada di server: validasi skema,
  batas ukuran dan jumlah Fitur, serta batas laju 120 permintaan/menit per IP (Docker;
  `RATE_LIMIT_PER_MINUTE`). Pengguna MongoDB sebaiknya dibatasi hanya ke database `lereng` dan
  koleksi `features`.
- Mengganti kunci berarti: perbarui `INGEST_TOKEN` di server, lalu bangun ulang APK/IPA dengan
  kunci baru. Versi lama akan ditolak `401`.
- Nama field yang mengandung `.`, diawali `$`, atau berisi null ditolak.
- Kunci salah → `401`; terlalu sering → `429 rate_limited`; konfigurasi server kurang → `500`;
  MongoDB gagal → `502`.
- Jangan commit `.env`, connection string, atau kunci. File `.gitignore`
  pada folder ini sudah menutup `.env` dan `node_modules`.
