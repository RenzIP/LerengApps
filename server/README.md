# Endpoint MongoDB untuk Lereng Offline

Folder ini berisi satu fungsi serverless yang menerima data dari tombol
**Sinkron otomatis** di aplikasi. Server ini yang memegang `MONGODB_URI`;
APK dan IPA hanya menyimpan alamat endpoint dan token.

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
3. **Network Access** → izinkan `0.0.0.0/0` (Vercel memakai IP dinamis).
   Batasi dengan kredensial yang hanya bisa menulis ke satu database.
4. Salin connection string `mongodb+srv://...`.

## 2. Deploy ke Vercel

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
| `INGEST_TOKEN` | ya | token acak, mis. hasil `openssl rand -hex 32` |
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

## 4. Isi alamat di aplikasi

Buka tab **Data** → kartu **Sinkron otomatis**:

1. Isi endpoint, mis. `https://<proyek>.vercel.app/api/ingest`.
   Alamat dasar tanpa path juga diterima; aplikasi menambahkan `/api/ingest`.
2. Isi token yang sama dengan `INGEST_TOKEN`.
3. Tekan **Simpan pengaturan**.

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
- Nama field yang mengandung `.`, diawali `$`, atau berisi null ditolak.
- Token salah → `401`; konfigurasi server kurang → `500`; MongoDB gagal → `502`.
- Jangan commit `.env`, connection string, atau token. File `.gitignore`
  pada folder ini sudah menutup `.env` dan `node_modules`.
