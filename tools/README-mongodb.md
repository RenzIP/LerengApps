# Impor Lereng Offline ke MongoDB

Aplikasi **tidak** menghubungi MongoDB secara langsung. APK tetap offline-first dan tidak menyimpan
URI/kredensial. Alurnya: ekspor file dari aplikasi → jalankan skrip ini di komputer → data masuk ke
MongoDB.

Alasan:

- URI MongoDB di dalam APK dapat diekstrak siapa pun yang memegang APK.
- Kredensial database di klien menghilangkan batas keamanan server; satu APK bocor = satu database bocor.
- Aplikasi ini tidak memiliki kode HTTP dan menyatakan “tidak ada unggahan”. Menambah driver Mongo
  langsung akan melanggar janji tersebut.

Jika nanti ingin sinkronisasi dari dalam aplikasi, gunakan backend HTTPS kecil yang memegang URI,
bukan driver MongoDB di APK.

## Format yang didukung

| Ekspor di aplikasi | Isi | Cocok untuk |
| --- | --- | --- |
| **Ekspor jalur sebagai GeoJSON** | 1 `LineString` rute + `Point` waypoint + metadata `lereng` | peta, QGIS, satu dokumen per jalur |
| **Ekspor JSONL untuk MongoDB** | 1 Feature per baris: track, waypoint, presence BLE | impor per titik, query geospasial |
| **Ekspor GPX** | track + waypoint | aplikasi peta/outdoor |
| **Ekspor CSV grup** | semua titik + event BLE | arsip lengkap dan penggabungan peserta |

Semua elevasi tetap membawa label referensinya. Tidak ada konversi datum.

## Persiapan

```powershell
python -m pip install -r tools/requirements.txt
$env:MONGODB_URI = "mongodb+srv://USER:PASSWORD@cluster/..."   # hanya untuk sesi terminal
```

Jangan commit URI ke repo, jangan simpan di APK, dan jangan tempel ke chat. Buat user database
dengan hak `readWrite` hanya pada database tujuan. Untuk Atlas, tambahkan IP komputer ke IP Access
List.

## Impor

```powershell
# validasi file tanpa koneksi
python tools/geojson_to_mongodb.py --dry-run "D:\ekspor\Gede_mongo.jsonl"

# impor (URI dari env MONGODB_URI)
python tools/geojson_to_mongodb.py --db lereng --collection tracks "D:\ekspor\Gede_mongo.jsonl"

# beberapa file sekaligus, termasuk GeoJSON FeatureCollection
python tools/geojson_to_mongodb.py --db lereng --collection tracks `
  "D:\ekspor\Gede_mongo.jsonl" "D:\ekspor\Gede_jalur.geojson"

# ke namespace yang sudah ada, misalnya db-gis.geojson
python tools/geojson_to_mongodb.py --db "db-gis" --collection geojson "D:\ekspor\Gede_mongo.jsonl"
```

`--db` dan `--collection` bebas dipilih; skrip membuat database/collection bila belum ada.

Skrip akan:

1. membaca `.jsonl`/`.geojson`, menambahkan `properties.imported_at` dan `properties.source_file`;
2. memberi `_id` deterministik dari proyek + jenis + peserta + waktu + geometri, sehingga impor
   ulang tidak menggandakan data;
3. membuat index `2dsphere` pada `geometry` (index penuh; dokumen `geometry: null` otomatis
   diabaikan index) dan index pada `properties.project`, `record_type`, `time_ms`,
   `participant_id`;
4. melakukan `ReplaceOne(..., upsert=True)` per batch.

Alternatif tanpa skrip:

```powershell
mongoimport --uri "$env:MONGODB_URI" --db lereng --collection tracks `
  --type json --file "D:\ekspor\Gede_mongo.jsonl"
```

`mongoimport` tidak membuat index. Setelah itu jalankan di mongosh/Atlas:

```javascript
db.tracks.createIndex({ geometry: "2dsphere" })
db.tracks.createIndex({ "properties.project": 1 })
db.tracks.createIndex({ "properties.time_ms": 1 })
```

## Contoh query

```javascript
// titik track terdekat dari satu posisi (meter)
db.tracks.find({
  "properties.record_type": "track",
  geometry: {
    $nearSphere: {
      $geometry: { type: "Point", coordinates: [106.9, -6.7] },
      $maxDistance: 500
    }
  }
})

// ringkasan per proyek
db.tracks.aggregate([
  { $match: { "properties.record_type": "track" } },
  { $group: {
      _id: "$properties.project",
      titik: { $sum: 1 },
      terakhir: { $max: "$properties.time_ms" }
  } }
])
```

Dokumen presence BLE tidak punya `geometry` (BLE tidak mengukur posisi). Dokumen tetap tersimpan
dengan `geometry: null` dan tidak ikut masuk index `2dsphere`, jadi `$nearSphere` hanya
mengembalikan dokumen yang benar-benar punya posisi.

## Catatan index 2dsphere

Pakai index **penuh**, bukan parsial:

```javascript
db.geojson.createIndex({ geometry: "2dsphere" }, { name: "geometry_2dsphere" })
```

Index parsial (`partialFilterExpression`) akan membuat `$nearSphere` gagal dengan
`unable to find index for $geoNear query` kecuali setiap query mengulang predikat parsialnya.
Skrip impor otomatis menghapus index `geometry` parsial lama dan menggantinya dengan index penuh.

## Bentuk dokumen

```json
{
  "_id": "lereng:...",
  "type": "Feature",
  "geometry": { "type": "Point", "coordinates": [106.9, -6.7, 1200.5] },
  "properties": {
    "schema": "lereng.mongo/1",
    "project": "Gede",
    "record_type": "track",
    "participant_id": "ABCD",
    "time_ms": 1762563723000,
    "elevation_reference": "WGS84 ellipsoid",
    "source": "Android Location"
  }
}
```

`geometry` mengikuti GeoJSON RFC 7946: urutan koordinat `[longitude, latitude, elevation?]`.
