#!/usr/bin/env python3
"""Import Lereng Offline GeoJSON/JSONL files into MongoDB.

The Android app stays offline: it writes a file through Android's document picker and this
companion script performs the upload. Never put a MongoDB URI (especially one with a password)
inside the APK.

Supported inputs:
  * GeoJSON FeatureCollection (*.geojson) -> one document per feature
  * newline-delimited GeoJSON Features (*.jsonl / *.ndjson) -> one document per line

Usage:
  python -m pip install -r tools/requirements.txt
  $env:MONGODB_URI = "mongodb+srv://..."
  python tools/geojson_to_mongodb.py --db lereng --collection tracks path\\to\\project_mongo.jsonl

Re-running the same file is idempotent: each feature gets a deterministic _id.

Security notes:
  * The URI is read from --uri or the MONGODB_URI environment variable; it is never written
    to disk or printed by this script.
  * Use a database user limited to readWrite on the target database, plus Atlas IP access lists.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

GEOJSON_TYPES = {
    "Point",
    "MultiPoint",
    "LineString",
    "MultiLineString",
    "Polygon",
    "MultiPolygon",
    "GeometryCollection",
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_documents(path: Path) -> tuple[list[dict], dict]:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except OSError as exc:
        fail(f"{path}: tidak dapat dibaca: {exc}")

    stripped = text.lstrip()
    if not stripped:
        return [], {}

    metadata: dict = {}
    if path.suffix.lower() in {".jsonl", ".ndjson"}:
        documents = []
        for line_number, line in enumerate(text.splitlines(), 1):
            line = line.strip()
            if not line:
                continue
            try:
                documents.append(json.loads(line))
            except json.JSONDecodeError as exc:
                fail(f"{path}:{line_number}: JSON tidak valid: {exc}")
        return [doc for doc in documents if isinstance(doc, dict)], metadata

    try:
        payload = json.loads(stripped)
    except json.JSONDecodeError as exc:
        fail(f"{path}: JSON tidak valid: {exc}")

    if isinstance(payload, dict) and payload.get("type") == "FeatureCollection":
        documents = payload.get("features") or []
        metadata = payload.get("lereng") or {}
        if not metadata and payload.get("name"):
            metadata = {"project": payload.get("name")}
    elif isinstance(payload, dict) and payload.get("type") == "Feature":
        documents = [payload]
    elif isinstance(payload, list):
        documents = payload
    elif isinstance(payload, dict) and "features" in payload:
        documents = payload.get("features") or []
    else:
        fail(f"{path}: bukan GeoJSON Feature/FeatureCollection atau JSONL")
    return [doc for doc in documents if isinstance(doc, dict)], metadata


def stable_id(document: dict) -> str:
    properties = document.get("properties") or {}
    geometry = document.get("geometry")
    parts = [
        str(properties.get("project", "")),
        str(properties.get("record_type", "")),
        str(properties.get("participant_id", "")),
        str(properties.get("seen_participant_id", "")),
        str(properties.get("time_ms", "")),
        str(properties.get("waypoint", "")),
        json.dumps(geometry, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        if geometry
        else "",
    ]
    digest = hashlib.sha1("\x1f".join(parts).encode("utf-8")).hexdigest()
    return f"lereng:{digest}"


def normalize(document: dict, metadata: dict, source_file: str) -> dict | None:
    properties = document.get("properties")
    if not isinstance(properties, dict):
        properties = {}
    properties = dict(properties)

    geometry = document.get("geometry")
    if geometry is not None:
        if not isinstance(geometry, dict) or geometry.get("type") not in GEOJSON_TYPES:
            return None

    properties.setdefault("schema", "lereng.mongo/1")
    if not properties.get("project"):
        properties["project"] = metadata.get("project") or metadata.get("name") or source_file
    properties["source_file"] = source_file
    properties["imported_at"] = now_iso()

    normalized = dict(document)
    normalized["type"] = "Feature"
    normalized["properties"] = properties
    normalized["_id"] = stable_id(normalized)
    return normalized


def ensure_indexes(collection) -> None:
    # A *partial* 2dsphere index only serves $nearSphere when the query repeats the partial
    # predicate, which is a confusing failure mode ("unable to find index for $geoNear query").
    # A plain index accepts documents with geometry:null / no geometry and simply skips them,
    # so presence records can stay in the same collection.
    for name, spec in list(collection.index_information().items()):
        if not spec.get("partialFilterExpression"):
            continue
        if any(key == "geometry" for key, _ in spec.get("key", [])):
            collection.drop_index(name)

    collection.create_index([("geometry", "2dsphere")], name="geometry_2dsphere")
    collection.create_index([("properties.project", 1)], name="project_1")
    collection.create_index([("properties.record_type", 1)], name="record_type_1")
    collection.create_index([("properties.time_ms", 1)], name="time_ms_1")
    collection.create_index([("properties.participant_id", 1)], name="participant_id_1")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Impor GeoJSON/JSONL Lereng Offline ke MongoDB (idempoten)."
    )
    parser.add_argument("files", nargs="+", type=Path, help="file .geojson / .jsonl")
    parser.add_argument("--uri", default=os.environ.get("MONGODB_URI"),
                        help="MongoDB URI; default dari env MONGODB_URI")
    parser.add_argument("--db", default="lereng", help="nama database (default: lereng)")
    parser.add_argument("--collection", default="tracks", help="nama collection (default: tracks)")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true",
                        help="validasi file tanpa menghubungi MongoDB")
    args = parser.parse_args()

    normalized: dict[str, dict] = {}
    skipped = 0
    raw_total = 0
    for path in args.files:
        if not path.is_file():
            fail(f"file tidak ditemukan: {path}")
        documents, metadata = read_documents(path)
        raw_total += len(documents)
        for document in documents:
            item = normalize(document, metadata, path.name)
            if item is None:
                skipped += 1
                continue
            normalized[item["_id"]] = item
        print(f"dibaca: {path} ({len(documents)} feature)")

    collapsed = raw_total - skipped - len(normalized)
    print(f"total: {len(normalized)} dokumen unik, {collapsed} duplikat dinormalkan, {skipped} dilewati")

    if args.dry_run:
        print("dry-run: tidak ada koneksi MongoDB. Contoh _id:",
              next(iter(normalized), "(tidak ada dokumen)"))
        return

    if not args.uri:
        fail("URI MongoDB belum diberikan. Set env MONGODB_URI atau pakai --uri. "
             "Jangan menaruh URI di dalam APK atau commit ke repo.")

    try:
        from pymongo import MongoClient, ReplaceOne
    except ImportError:
        fail("pymongo belum terpasang. Jalankan: python -m pip install -r tools/requirements.txt")

    client = MongoClient(args.uri, serverSelectionTimeoutMS=12_000,
                         appname="lereng-geojson-import")
    try:
        client.admin.command("ping")
        collection = client[args.db][args.collection]
        ensure_indexes(collection)

        documents = list(normalized.values())
        batch_size = max(1, args.batch_size)
        inserted = 0
        matched = 0
        for start in range(0, len(documents), batch_size):
            batch = documents[start:start + batch_size]
            operations = [ReplaceOne({"_id": doc["_id"]}, doc, upsert=True) for doc in batch]
            result = collection.bulk_write(operations, ordered=False)
            inserted += result.upserted_count
            matched += result.matched_count
            print(f"  {min(start + batch_size, len(documents))}/{len(documents)} dokumen")

        print(f"selesai: {inserted} baru, {matched} cocok/diperbarui, "
              f"{len(documents)} total di {args.db}.{args.collection}")
        print("index 2dsphere + index properti siap.")
    except Exception as exc:  # pymongo raises several subclasses here
        fail(f"impor gagal: {exc}")
    finally:
        client.close()


if __name__ == "__main__":
    main()
