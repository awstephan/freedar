# Bundled offline places

Freedar derives its gazetteer from **GeoNames**, licensed under
[Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
Source: <https://www.geonames.org/>; [source descriptions and schema](https://download.geonames.org/export/dump/readme.txt).
The app links GeoNames in persistent map credits and the license in Search.
The database is modified from the source: selected columns, joined display names,
normalized/deduplicated search aliases, and a SQLite index.

## Pinned inputs and reproduction

Retrieved **2026-10-31** from:

| Input | URL | SHA-256 |
| --- | --- | --- |
| cities500.zip | https://download.geonames.org/export/dump/cities500.zip | `fc89c44c54a66fe05d1d0060209bb1dd5090ee1cc86f0bd92c198a606c81103e` |
| admin1CodesASCII.txt | https://download.geonames.org/export/dump/admin1CodesASCII.txt | `2e4867f5397acb056ea26377601b57af37cb4c81399770225018e9e439e91ad3` |
| countryInfo.txt | https://download.geonames.org/export/dump/countryInfo.txt | `5c710efde90ee4cca15001c9f6a97ae7a74c7020b365504913ddc3afeb6d3671` |

Upstream URLs change daily. **Original input bytes are archived in
`tools/places/sources.zip`**, with hashes enforced by the generator. Normal Android
builds package the checked-in asset and never download GeoNames data. The source
archive and tools are not packaged in the app.

```sh
python3 tools/places/generate.py
python3 tools/places/check_generation.py
```

The latter rebuilds in a temporary directory and compares the complete compressed
asset byte-for-byte. Reference environment: Python **3.14.8**, Unicode **16.0.0**,
SQLite **3.51.2**, zlib **1.3.1.zlib-ng** on Linux. Different SQLite/compressor/Unicode
versions can produce different bytes; use these versions for byte-identical output.
There are no Python package dependencies. Determinism comes from sorted IDs/aliases,
fixed schema/page size, VACUUM, and gzip with no filename or timestamp metadata.

Output: `app/src/main/assets/places-v1.sqlite.gzip`, SHA-256
`13fa3d3c36fd49af813135aa8d382d9dc06d715f4c7f6553207c67162baa5a93`.
It contains **236,857 places** and **1,142,197 normalized name/place pairs**.

To update intentionally, download all three inputs together, update the pinned
hashes, then use `generate.py --capture /path/to/downloads`. Review the changed
coverage and attribution, regenerate, and rerun checks. Bump the version in the
asset and installed filenames (and schema version if needed) so upgrades cannot
reuse the previous installed database. Remove superseded generated assets.

## Data and matching contract

- Keep every cities500 row, its GeoNames ID, primary display name, WGS84 latitude
  and longitude, population for ranking, ISO country code, and country/admin1 code.
- Display country names from `countryInfo.txt` and region names from
  `admin1CodesASCII.txt`. Missing region names fall back to the source code, or
  “Region unspecified” when no code exists. GeoNames admin1 codes are not uniformly
  ISO subdivision codes; the stored identifier is `country.admin1`.
- Index primary name, ASCII name, and **every comma-separated alternate name in
  cities500**. This convenience field includes transliterations and names in other
  scripts, without the larger alternateNamesV2 file's language/preference/historic
  metadata. We do not guess a preferred localized display name, fabricate
  transliterations, or filter historic spellings. Aliases collapse to one result
  per GeoNames ID. This is not every alias in the full GeoNames database.
- Normalize both input and indexed aliases with NFKD, remove Unicode marks,
  lowercase independently of user locale, replace non-letter/non-number runs with
  a space, and trim. For example `MÜNCHEN`, `Munich`, `東京`, `Tokyo`, `Москва`,
  `Moscow`, `São Paulo`, `sao paulo`, `St. Ives`, and `Reykjavik` are tested against
  known place IDs on API 26. Script-specific letters remain; aliases supply spelling
  equivalence beyond normalization. Android and Python Unicode versions differ;
  newly encoded characters absent from older Android may not match. This is not
  fuzzy matching, arbitrary transliteration, token reordering, or substring search.
- Match the **start of a complete normalized name/alias** using a binary range on
  the `names(name, place)` primary-key B-tree. Aggregate aliases before joining
  place details. Exact names rank before prefixes, then larger population, then ID.
  Return at most 50 places, with a refine-name hint at that limit. Queries are
  parameterized, limited to 200 input characters, and empty normalization returns
  no results. No SQL wildcard syntax is exposed.
- Search runs only on keyboard submission, on an IO worker. Opening, typing, and
  editing make no search requests. Recreation may rerun the last submitted query.
  Replacement submissions/dismissal cancel UI delivery of obsolete work; database
  handles close inside the worker even if delivery is cancelled. There is no network
  client or fallback in the search module.

GeoNames describes cities500 as cities with population over 500 **or** administrative
seats down to PPLA4. It is not a guarantee that every settlement over 500 is present,
nor that every row is an independent incorporated town. Population and coverage can
be incomplete/outdated. Small-town checks include St Ives (Cornwall, GB), Twizel
(Canterbury, NZ), and Quorn (South Australia, AU). No addresses or businesses are
indexed. Empty results explain these limits and leave manual navigation available.

## Storage and integration

The compressed asset is stored without a second APK compression layer (`gzip` in
`androidResources.noCompress`). The `.gzip` extension avoids AAPT's special `.gz`
decompression/rename behavior. On first submitted search it is extracted on an IO
worker through a temporary file and atomic rename to
`Context.noBackupFilesDir/place-search/places-v1.sqlite`, then opened read-only.
Subsequent submissions reuse the installed file. No external storage or backup is
involved. Extraction requires space for the installed database in addition to the APK.

**Clear downloaded data targets imagery storage only**, never deletes
`noBackupFilesDir` wholesale. The packaged asset and its installed derivative are
application resources, not downloaded imagery. CARTO device reuse is now bounded by
the [imagery store](downloaded-imagery.md); radar persistence remains blocked.
Device checks also remove the Android cache directory between offline queries.
Android's system Clear storage/uninstall can delete the extracted copy; the next
search can reconstruct it offline from the APK.

Search occupies the existing controls region, holds controls visible until dismissal
or selection, and preserves typed/submitted text across recreation. Frame/status,
coverage guidance, and linked credits remain separate. Selection calls
`MainActivity.navigationStarted()` **before** camera navigation, rejecting pending
Locate callbacks. It reuses zoom **9** local-area framing and the existing Latest
command (newest paused/following). It does not promise imagery exists offline or add
radar detail. A later initial style load cannot overwrite a selected place with the
startup overview.

Measured APK/installed sizes, first-use extraction, and API-26 query timings are in
[issue #7 validation](validation/issue-7.md).
