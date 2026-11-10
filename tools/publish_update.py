#!/usr/bin/env python3
"""Tulis manifes pembaruan Lereng Offline (`lereng.update/1`) dari sebuah APK.

APK tidak diunggah oleh skrip ini: unggah APK-nya sendiri (GitHub Releases, object storage,
atau web server), lalu jalankan skrip ini dengan URL HTTPS hasil unggahan.

Contoh:
  python tools/publish_update.py --apk app\\build\\outputs\\apk\\debug\\app-debug.apk `
      --version-code 10 --version-name 2.5.1 `
      --apk-url https://contoh.github.io/lereng/LerengOffline-2.5.1.apk `
      --mandatory --notes "Perbaikan peta rute" --notes "Ekspor JSONL lebih cepat"
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Buat updates/update.json yang dibaca aplikasi untuk cek pembaruan."
    )
    parser.add_argument("--apk", type=Path, required=True, help="file APK yang akan dirilis")
    parser.add_argument("--version-code", type=int, required=True,
                        help="harus sama dengan versionCode di app/build.gradle")
    parser.add_argument("--version-name", required=True, help="mis. 2.5.1")
    parser.add_argument("--apk-url", required=True, help="URL HTTPS langsung ke file APK")
    parser.add_argument("--notes", action="append", default=[],
                        help="satu baris catatan rilis; boleh diulang")
    parser.add_argument("--mandatory", action="store_true",
                        help="paksa pengguna memperbarui sebelum memakai aplikasi")
    parser.add_argument("--min-supported", type=int, default=0,
                        help="build di bawah kode ini wajib memperbarui")
    parser.add_argument("--out", type=Path, default=Path("updates/update.json"))
    args = parser.parse_args()

    if not args.apk.is_file():
        fail(f"APK tidak ditemukan: {args.apk}")
    if args.version_code <= 0:
        fail("--version-code harus lebih besar dari 0")
    if not args.apk_url.lower().startswith("https://"):
        fail("--apk-url harus HTTPS; aplikasi menolak tautan non-HTTPS")
    if args.min_supported < 0:
        fail("--min-supported tidak boleh negatif")

    manifest = {
        "schema": "lereng.update/1",
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "mandatory": bool(args.mandatory),
        "minSupportedVersionCode": args.min_supported,
        "apkUrl": args.apk_url,
        "sizeBytes": args.apk.stat().st_size,
        "sha256": sha256_of(args.apk),
        "notes": args.notes,
        "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds")
        .replace("+00:00", "Z"),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8")

    wa = "wajib" if manifest["mandatory"] else "opsional"
    print(f"manifes ditulis: {args.out}")
    print(f"  versi {args.version_name} (kode {args.version_code}), pembaruan {wa}")
    print(f"  ukuran {manifest['sizeBytes']} byte, sha256 {manifest['sha256'][:16]}…")
    print("langkah berikutnya: unggah APK ke URL di atas, lalu unggah berkas manifes ini.")


if __name__ == "__main__":
    main()
