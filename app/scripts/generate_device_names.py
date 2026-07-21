#!/usr/bin/env python3
"""Regenerates app/src/main/assets/device_names.json.gz from Google's Play
supported-devices catalog. The asset maps Build.MODEL -> marketing name
(e.g. "SM-S928B" -> "Galaxy S24 Ultra") for the concise device spec shown in
the network peers list; models whose marketing name adds nothing (Pixels)
are omitted and fall back to Build.MODEL at runtime.

Run from the repo (network required), then commit the regenerated asset:
    python3 scripts/generate_device_names.py
"""

import csv
import gzip
import io
import json
import os
import urllib.request

CATALOG_URL = "https://storage.googleapis.com/play_public/supported_devices.csv"
ASSET_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "device_names.json.gz",
)


def main():
    with urllib.request.urlopen(CATALOG_URL, timeout=120) as response:
        raw = response.read()

    # the catalog is published utf-16; tolerate a plain utf-8 copy
    for encoding in ("utf-16", "utf-8-sig"):
        try:
            rows = list(csv.reader(io.StringIO(raw.decode(encoding))))
            if rows and len(rows[0]) >= 4:
                break
        except (UnicodeError, csv.Error):
            continue
    else:
        raise SystemExit("could not parse the catalog")

    # columns: Retail Branding, Marketing Name, Device, Model
    names = {}
    for row in rows[1:]:
        if len(row) < 4:
            continue
        marketing, model = row[1].strip(), row[3].strip()
        if not model or not marketing:
            continue
        if marketing.lower() == model.lower():
            continue
        # first-wins on carrier-variant duplicates
        names.setdefault(model, marketing)

    data = json.dumps(names, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    os.makedirs(os.path.dirname(ASSET_PATH), exist_ok=True)
    with open(ASSET_PATH, "wb") as f:
        # mtime=0 keeps the output byte-stable for identical inputs
        with gzip.GzipFile(fileobj=f, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(data)

    print(f"{len(names)} models -> {ASSET_PATH} ({os.path.getsize(ASSET_PATH) // 1024} KB)")


if __name__ == "__main__":
    main()
