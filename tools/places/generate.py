#!/usr/bin/env python3
"""Rebuild the bundled gazetteer from the checked-in, checksum-pinned snapshot."""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import sqlite3
import tempfile
import unicodedata
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "cities500.zip": "fc89c44c54a66fe05d1d0060209bb1dd5090ee1cc86f0bd92c198a606c81103e",
    "admin1CodesASCII.txt": "2e4867f5397acb056ea26377601b57af37cb4c81399770225018e9e439e91ad3",
    "countryInfo.txt": "5c710efde90ee4cca15001c9f6a97ae7a74c7020b365504913ddc3afeb6d3671",
}


def normalize(value):
    value = "".join(c for c in unicodedata.normalize("NFKD", value)
                    if not unicodedata.category(c).startswith("M")).lower()
    return " ".join("".join(c if unicodedata.category(c)[0] in "LN" else " "
                            for c in value).split())


def generate(snapshot, output):
    with zipfile.ZipFile(snapshot) as archive:
        sources = {name: archive.read(name) for name in SOURCES}
    for name, content in sources.items():
        if hashlib.sha256(content).hexdigest() != SOURCES[name]:
            raise ValueError(f"Source checksum mismatch: {name}")
    regions = {r[0]: r[1] for line in sources["admin1CodesASCII.txt"].decode().splitlines()
               if (r := line.split("\t"))}
    countries = {r[0]: r[4] for line in sources["countryInfo.txt"].decode().splitlines()
                 if not line.startswith("#") and len(r := line.split("\t")) > 4}
    with zipfile.ZipFile(io.BytesIO(sources["cities500.zip"])) as cities:
        rows = [line.split("\t") for line in cities.read("cities500.txt").decode().splitlines()]
    with tempfile.TemporaryDirectory() as temp:
        path = Path(temp) / "places.sqlite"
        with sqlite3.connect(path) as db:
            db.executescript("""
                PRAGMA page_size=4096;
                PRAGMA user_version=1;
                CREATE TABLE places(id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                    region TEXT NOT NULL, region_code TEXT NOT NULL, country TEXT NOT NULL,
                    country_code TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
                    population INTEGER NOT NULL);
                CREATE TABLE names(name TEXT NOT NULL, place INTEGER NOT NULL,
                    PRIMARY KEY(name, place)) WITHOUT ROWID;
            """)
            for row in sorted(rows, key=lambda r: int(r[0])):
                region_code = row[8] + "." + row[10]
                db.execute("INSERT INTO places VALUES(?,?,?,?,?,?,?,?,?)", (
                    int(row[0]), row[1], regions.get(region_code, row[10] or "Region unspecified"),
                    region_code, countries.get(row[8], row[8]), row[8], float(row[4]),
                    float(row[5]), int(row[14])))
                names = sorted({normalize(n) for n in [row[1], row[2], *row[3].split(",")]} - {""})
                db.executemany("INSERT INTO names VALUES(?,?)", ((n, int(row[0])) for n in names))
            db.commit()
            db.execute("VACUUM")
            print(f"places={len(rows)} names={db.execute('SELECT count(*) FROM names').fetchone()[0]}")
        output.parent.mkdir(parents=True, exist_ok=True)
        # No filename or wall-clock metadata in the compressed asset.
        with output.open("wb") as target, gzip.GzipFile(filename="", mode="wb", fileobj=target,
                                                       mtime=0, compresslevel=9) as compressed:
            compressed.write(path.read_bytes())
        print(f"SQLite={path.stat().st_size} gzip={output.stat().st_size}")
        print(f"sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, help="Archive original downloaded inputs once")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/places-v1.sqlite.gzip")
    args = parser.parse_args()
    snapshot = Path(__file__).with_name("sources.zip")
    if args.capture:
        with zipfile.ZipFile(snapshot, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, digest in SOURCES.items():
                data = (args.capture / name).read_bytes()
                assert hashlib.sha256(data).hexdigest() == digest, name
                archive.writestr(zipfile.ZipInfo(name, date_time=(2026, 10, 31, 0, 0, 0)), data)
    generate(snapshot, args.output)
