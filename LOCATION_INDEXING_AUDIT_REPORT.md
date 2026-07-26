# Photo location indexing audit

Date: 2026-07-25
Device: Samsung SM-S938B (`RZCY92NW2AZ`)
Scope: GPS/place indexing and location-scoped retrieval only. The frozen
Gemma 4 E4B query planner and the completed OCR index were not changed.

## Decision

The geocoder was not the primary defect. Android was redacting photo EXIF GPS
before the geocoder saw it.

On Android 10 and newer, ordinary media-read access does not expose unredacted
photo location metadata. The app must hold the runtime
`ACCESS_MEDIA_LOCATION` permission and open a
`MediaStore.setRequireOriginal()` URI. Ask Galaxy previously did neither, then
persisted every redacted photo as `LOCATION_COMPLETE_NO_GPS`. Video location
metadata followed a different API and therefore appeared to work, masking the
photo-specific failure.

The implementation now follows Android's documented media-location contract:

- [Access media files from shared storage — media location
  permission](https://developer.android.com/training/data-storage/shared/media#media-location-permission)
- [`MediaStore.setRequireOriginal`
  reference](https://developer.android.com/reference/android/provider/MediaStore#setRequireOriginal(android.net.Uri))

## Corrected pipeline

```text
image location row needing enrichment
  -> require explicit ACCESS_MEDIA_LOCATION consent
  -> MediaStore.setRequireOriginal(content URI)
  -> read unredacted EXIF through a file descriptor
  -> reject invalid/out-of-range coordinates and the 0°,0° missing-GPS sentinel
  -> Android Geocoder
  -> if empty: serialized OpenStreetMap reverse lookup at rounded 0.001°
  -> if a detailed address object is absent: one coarse regional lookup
  -> cache locality-sized result in SQLite
  -> atomically persist raw GPS + readable location_name + completion state
```

Privacy and failure behavior:

- no photo pixels, OCR, faces, filenames, or queries enter either geocoder;
- the optional OpenStreetMap edge receives only coordinates rounded to three
  decimal places, roughly locality precision;
- successful place names are cached on device;
- missing consent leaves image rows pending rather than falsely marking them
  as no-GPS;
- genuine unresolved GPS stays retryable;
- the common `0,0` EXIF sentinel is treated as missing GPS and is never sent to
  a geocoder.

## Isolated migration

Database v12 invalidates only `location_raw`, `location_name`, and
`location_enrichment_state` for image rows. It retains capture dates, OCR
payloads/classes/signatures, visual embedding state, face data, clusters,
episodes, and the native vector file.

`LocationReindexWorker` and `LocationReindexScheduler` provide the matching
location-only WorkManager path. Settings exposes:

- current permission/index status;
- **Allow precise photo locations**;
- **Rebuild photo locations only**;
- the existing opt-out switch for the OpenStreetMap fallback.

## Installed-device evidence

Before the fix:

| Measure | Device result |
| --- | ---: |
| Total media | 16,197 |
| Rows marked no GPS | 16,097 |
| Resolved rows | 100 |
| Resolved photos | 0 |
| Resolved videos | 100 |
| Durable geocode cache | 39 |
| Location-scoped photos | 0 |

The v12 migration then marked exactly 11,942/11,942 image rows pending. The
first unredacted pass immediately found that most camera photos did contain
GPS. Fifteen remaining rows shared one coordinate: the `0°,0°` missing-value
sentinel. After rejecting that sentinel, the bounded worker completed with no
pending rows.

Final index:

| Measure | Device result |
| --- | ---: |
| Valid GPS-bearing photos | 9,834 |
| Photos with readable persisted names | 9,834 |
| Videos with readable persisted names | 100 |
| Total resolved media | 9,934 |
| Durable locality cache entries | 1,324 |
| Pending location rows | 0 |
| Location-scoped photo retrieval | 9,834 |

Resolved photo MIME coverage was 5,461 HEIC, 4,337 JPEG, and 36 DNG rows.
No personal coordinate or place-name values were copied into test logs or this
report.

## Isolation proof

The location-only rebuild produced byte-identical logical SHA-256 digests
before and after for:

- non-location media columns:
  `3bbcb2b99844f584f5dd5e8bd855adbe9b8882c3593716f94b95324623415b4d`;
- face embeddings:
  `3ccdd2e3f30627b8cebdf34efb082e088ca1c16539b115433a1253b2b8466444`;
- face clusters:
  `bdb5eaf915ec5b8a815537d55f21ae04cd9ee4ae861eaafc0669ed68f34315d9`;
- episodes:
  `5c7018b4373193e78b3ef1989a644ac0e97b581d0351dc14b55126ead2d64d12`;
- episode memberships:
  `4940e9bee006c511ded5accb8b9dc872e5a648f0b24ee7c0f33d0f09a6911d4d`;
- TurboQuant visual vector file:
  `0d7f0624b8b4c366c7111a0413576b4ca831fcf6b36a77df950d0683f3be42c2`.

## Search verification

The planner-independent structured-search matrix now returns:

- `scenary + photos`: 9,366/9,366 expected rows;
- `doc + photos`: 2,576/2,576;
- `person + photos`: 2,251/2,251;
- `location + photos`: 9,834/9,834;
- `time + photos`: 11,942/11,942.

An indexed readable place predicate returned a non-empty exact hard scope, and
`SORT_LOC` ordered all 9,834 photo records by persisted readable place. The
same matrix retained non-empty strict semantic searches for beach, sunset, and
receipt and preserved union/subtraction set invariants.

## Verification status

- Complete JVM suite: passed.
- Android lint: passed.
- Debug app and instrumentation APK assembly: passed.
- Runtime permission present in installed package: passed.
- v11 to v12 location-only migration: passed.
- WorkManager location-only completion: passed.
- Final location corpus and category scope: passed.
- Non-location isolation digest audit: passed.
- Frozen QP source hashes: unchanged.
