#!/usr/bin/env python3
"""Regenerate the offline notice bundle from resolved artifacts and upstream texts.

Run :app:exportLicenseArtifacts first. Network access is needed only for this
explicit maintenance command, never for loading notices or ordinary APK builds.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
NATIVE = "https://raw.githubusercontent.com/maplibre/maplibre-native/android-v11.13.5/"
SOURCES = {
    "Apache License 2.0": "https://www.apache.org/licenses/LICENSE-2.0.txt",
    "Kotlin notice": "https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/NOTICE.txt",
    "Kotlin stdlib GWT portions": "https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/third_party/gwt_license.txt",
    "Kotlin stdlib Guava portions": "https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/third_party/guava_license.txt",
    "Kotlin stdlib time portions": "https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/third_party/threetenbp_license.txt",
    "Kotlin stdlib math portions": "https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/third_party/boost_LICENSE.txt",
    "Kotlin coroutines notice": "https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/license/NOTICE.txt",
    "Kotlin serialization notice": "https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.8.1/license/NOTICE.txt",
    "MapLibre Native Android 11.13.5 and bundled native components": NATIVE + "platform/android/LICENSE.md",
    "MapLibre Native ICU notice": NATIVE + "vendor/icu/LICENSE",
    "MapLibre Native nunicode notice": NATIVE + "vendor/nunicode/LICENSE",
    "MapLibre gestures": "https://raw.githubusercontent.com/maplibre/maplibre-gestures-android/0.0.4/LICENSE.md",
    "MapLibre unordered_dense": "https://raw.githubusercontent.com/martinus/unordered_dense/729896c7ba8bbd9da5573679270133086d05b5dd/LICENSE",
    "MapLibre PMTiles": "https://raw.githubusercontent.com/protomaps/PMTiles/60f3331ca9d5cfa4fcdf1e076a3d3059aebb17bb/LICENSE",
    "MapLibre jni.hpp": "https://raw.githubusercontent.com/mapbox/jni.hpp/57ca9ed4bbeb22ed8d20a55063dcaa217ba47f42/LICENSE.txt",
    "MapLibre filesystem": "https://raw.githubusercontent.com/gulrak/filesystem/9fda7b0afbd0640f482f4aea8720a8c0afd18740/LICENSE",
    "FreeType license": "https://raw.githubusercontent.com/freetype/freetype/42608f77f20749dd6ddc9e0536788eaad70ea4b5/docs/FTL.TXT",
    "HarfBuzz notices": "https://raw.githubusercontent.com/harfbuzz/harfbuzz/c1eb66d4159fec311334aee5c0a59384491d3989/COPYING",
    "LLVM libc++ runtime": "https://raw.githubusercontent.com/llvm/llvm-project/llvmorg-18.1.8/libcxx/LICENSE.TXT",
    "Mozilla Public License 2.0 (OkHttp public suffix data)": "https://raw.githubusercontent.com/spdx/license-list-data/v3.27.0/text/MPL-2.0.txt",
    "Creative Commons Attribution 4.0": "https://raw.githubusercontent.com/spdx/license-list-data/v3.27.0/text/CC-BY-4.0.txt",
}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_licenses(pom_path, cache):
    pom = ET.parse(pom_path).getroot()
    licenses = [element.findtext("m:name", namespaces=NS) for element in pom.findall("m:licenses/m:license", NS)]
    if licenses:
        return licenses
    parent = pom.find("m:parent", NS)
    if parent is None:
        return []
    directory = cache.joinpath(*(parent.findtext("m:" + key, namespaces=NS)
                                 for key in ("groupId", "artifactId", "version")))
    parents = list(directory.glob("*/*.pom"))
    if len(parents) != 1:
        raise ValueError(f"Expected cached parent POM: {directory}")
    return pom_licenses(parents[0], cache)


def archive_notices(data, prefix=""):
    """Preserve license/notice/copyright files, including nested AAR JARs."""
    found = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith("/"):
                continue
            if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                found.extend(archive_notices(archive.read(name), prefix + name + "!/"))
            elif not name.startswith("res/") and re.search(r"(^|/)(license|licence|notice|copyright)([._-]|$)", name, re.I):
                found.append((prefix + name, archive.read(name).decode("utf-8")))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check the current inventory and bundle offline")
    parser.add_argument("--apk", type=Path, help="Also check the bundle packaged in an APK (with --check)")
    args = parser.parse_args()
    target = ROOT / "app/src/main/assets/open-source-notices.txt"
    record = ROOT / "tools/licenses/provenance.json"
    sections = [("Freedar", (ROOT / "LICENSE").read_text())]
    inventory = []
    provenance = {}
    notices = {}
    for line in (ROOT / "app/build/reports/licenses/artifacts.tsv").read_text().splitlines():
        coordinate, filename = line.split("\t")
        artifact = Path(filename)
        if coordinate.startswith("freedar:"):
            continue
        poms = list(artifact.parent.parent.glob("*/*.pom"))
        if len(poms) != 1:
            raise ValueError(f"Expected one POM for {coordinate}: {poms}")
        licenses = pom_licenses(poms[0], artifact.parents[4])
        if not licenses:
            raise ValueError(f"Review missing license metadata: {coordinate}")
        if not all(any(word in license.lower() for word in ("apache", "bsd")) for license in licenses):
            raise ValueError(f"Review new license: {coordinate}: {licenses}")
        inventory.append(f"{coordinate} — {', '.join(licenses)}")
        provenance[coordinate] = {"sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "licenses": licenses}
        for name, text in archive_notices(artifact.read_bytes()):
            notices.setdefault(text, []).append(f"{coordinate} / {name}")

    if "org.maplibre.gl:android-sdk-opengl:11.13.5" not in provenance:
        raise ValueError("MapLibre changed: review and update the pinned native notices before regenerating")
    if args.check:
        saved = json.loads(record.read_text())
        saved_artifacts = {key: value for key, value in saved.items() if "licenses" in value}
        if saved_artifacts != provenance:
            raise ValueError("Runtime artifacts changed; review and regenerate notices")
        if saved["bundle"]["sha256"] != hashlib.sha256(target.read_bytes()).hexdigest():
            raise ValueError("Notice bundle differs from the recorded hash")
        if (ROOT / "LICENSE").read_text().strip() not in target.read_text():
            raise ValueError("Freedar license changed; regenerate notices")
        if args.apk:
            with zipfile.ZipFile(args.apk) as apk:
                if apk.read("assets/open-source-notices.txt") != target.read_bytes():
                    raise ValueError("APK contains a different notice bundle")
        print(f"Verified {len(provenance)} runtime artifacts and the notice bundle" +
              (f" in {args.apk}" if args.apk else ""))
        return
    if args.apk:
        parser.error("--apk requires --check")
    sections.extend(("\n".join(names), text) for text, names in notices.items())

    sections.insert(1, ("Runtime dependency inventory", "\n".join(inventory)))
    sections.insert(2, ("GeoNames place data", """Contains modified GeoNames data, retrieved 2026-10-31.
Source: https://www.geonames.org/
Downloads: https://download.geonames.org/export/dump/
License: Creative Commons Attribution 4.0 (full text below).
https://creativecommons.org/licenses/by/4.0/
Inputs: cities500.zip, admin1CodesASCII.txt, countryInfo.txt.
Changes: selected columns, joined display names, normalized/deduplicated
search aliases, and a SQLite index. GeoNames does not endorse Freedar.
Input hashes and reproduction: https://github.com/awstephan/freedar/blob/HEAD/docs/place-search.md

Map/radar services are separate from these software and dataset licenses:
OpenStreetMap contributors: https://www.openstreetmap.org/copyright
CARTO: https://carto.com/attribution/
RainViewer: https://www.rainviewer.com/

OkHttp includes Mozilla Public Suffix List data under MPL 2.0.
Source and updates: https://publicsuffix.org/list/
OkHttp distribution: https://github.com/square/okhttp/tree/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix

SQLite in MapLibre is public domain: https://www.sqlite.org/copyright.html
Portions of this software are copyright The FreeType Project
(https://freetype.org/). All rights reserved.
"""))
    for title, url in SOURCES.items():
        print(f"Fetching {url}", flush=True)
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        provenance[title] = {"url": url, "sha256": hashlib.sha256(data).hexdigest()}
        sections.append((title + "\nSource: " + url, data.decode("utf-8")))

    target.write_text("Freedar — software licenses and third-party notices\n\n" +
                      "\n\n".join(f"{'=' * 72}\n{title}\n{'=' * 72}\n\n{text.strip()}" for title, text in sections) + "\n")
    provenance["bundle"] = {"sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
    record.write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    print(f"Wrote {target.relative_to(ROOT)}: {len(inventory)} runtime artifacts, {len(sections)} notice sections")


if __name__ == "__main__":
    main()
