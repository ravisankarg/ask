# Ask Galaxy: durable module requirements, context, and runbook

Last updated: 2026-07-23

This note is the durable handoff for the Ask Galaxy checkout. It records the
product intent, module contracts, performance constraints, failure modes, and
the live-device validation procedure. Treat current source and runtime output
as authoritative when this note and an older document disagree.

## 1. Product north star

Ask Galaxy is a private, on-device gallery assistant. A user may ask an
arbitrary natural-language question about photos, people, activities, dates,
places, written text, trips, or personal context. The result should feel like
a thoughtful assistant answer, not like an exposed search query or an ML demo.

The pipeline must optimize both:

- answer quality: combine visual pixels, named faces, capture metadata, OCR,
  locations, and optional personal context as one joined context;
- speed and memory: keep hot paths bounded, reuse the existing index, avoid
  image encoding during search, and avoid sending redundant visual inputs to
  Gemma.

The user does not need to know which channel was missing or which model was
used. Expose a calibrated useful answer or a concise limitation, never an
internal planner/evidence disclaimer.

## 2. Non-negotiable contracts

1. Search is local-first. No cloud retrieval, cloud OCR, cloud face matching,
   hosted vector database, or hidden network answer path belongs in the APK.
2. Search must reuse the persisted gallery database and TurboQuant index. A
   query must never rebuild, delete, or re-encode the image index.
3. The image encoder is an indexing-time component. Search uses the SigLIP2
   text encoder, SQLite metadata/OCR matching, and native TurboQuant search;
   it must not run a vision encoder on every query.
4. Person, time, location, MIME type, OCR exclusions, and negations are hard
   scope operations. Unrelated records must not survive merely because their
   semantic score is high.
5. Person names are never hard-coded. Resolve any tagged name through the
   local face-cluster labels and apply the same logic for every name.
6. Answer context is bounded to at most 8 query-aware, cross-episode records.
   Doc and scenary may attach at most 4 downscaled images from those records;
   each selected document image includes its query-relevant OCR text.
   Person/location/time remain text-only.
7. Gemma must reason jointly over the query, selected OCR/metadata, people,
   personal context, and any bounded document or scenery images.
8. The final answer is natural language only. Never show the planner execution
   spec, regex,
   routing keys, code, “based on provided evidence,” “supplied records,”
   “gallery records,” or similar internal/evaluation language.
9. An exact fact that is absent must become a useful calibrated answer: give
   the closest supported event/date/place and explain the distinction briefly.
10. EXIF orientation is presentation-only unless the indexing contract is
    deliberately migrated. Stored face boxes currently use raw bitmap
    coordinates and must be transformed when an oriented bitmap is displayed.
11. Face tagging must stay usable with thousands of clusters: show one stable,
    virtualized snapshot of every current cluster so all merge candidates can
    be selected in one pass; never replace it with a shifting top-20 window.
12. After at least three important face groups are named, show Skip. Remaining
    groups must remain revisitable from Settings.
13. Long merge/index operations need visible progress or a spinner and must
    leave the button/state unambiguous until completion.
14. The search page must expose live QP output and a timing chip directly below
    the search bar. The canonical effective spec appears before retrieval;
    expanded timing shows completed phases through total.

## 3. Current end-to-end pipeline

### Preparation/indexing

```text
WorkManager foreground preparation
  -> model/artifact verification and resumable downloads
  -> MediaStore scan into SQLite
  -> capture date/GPS extraction + cached online reverse geocoding into SQLite
  -> SigLIP2 image/frame embeddings into TurboQuant 4-bit index
  -> aspect-preserving bundled ML Kit text extraction into SQLite
  -> YuNet detection + FaceNet-512 embeddings into encrypted SQLite
  -> local face clustering and representative crops
  -> persisted episode index from time, readable location, and anonymous people
  -> full virtualized face review/tagging gate
  -> READY: search enabled
```

### Search and answer

```text
search field visible
  -> resident full-GPU Gemma 4 E4B engine + planner preface warm-up
  -> Gemma-only QP: required query_category + canonical C-like execution AST
  -> effective QP output shown below the search bar before retrieval
  -> recursive set execution over person/date/location/MIME/semantic predicates
  -> SigLIP2 text vector + native TurboQuant search and SQLite metadata branches
  -> top 200 category-aware overall-relevance matches shown immediately
  -> persisted episode membership join
  -> QP category hard-scopes the indexed result set
  -> Context Picker selects at most 8 eligible representatives without decoding images
  -> scenary: persisted SigLIP embedding diversity + at most 4 EXIF-correct
     answer images downscaled to a 512 px longest edge
  -> doc: top 4 OCR-retrieved images downscaled to a 512 px longest edge,
     joined with their query-relevant OCR text
  -> person/location/time: selected metadata text with no image decode
  -> clean Gemma answer turn and bounded follow-ups
  -> 2–3 sentence answer, contextual follow-ups, and expandable Time stats
```

## 4. Module-level contracts

### 4.1 App lifecycle and preparation owner

Primary files:

- `PreparationScheduler.kt`
- `PreparationWorker.kt`
- `PreparationStore.kt`
- `PreparationNotifier.kt`
- `MainActivity.kt`

Requirements:

- WorkManager owns long-running model installation and indexing. The Activity
  observes persisted state; closing the Activity must not cancel preparation.
- Downloads use resumable `*.part` files and rename only after checksum/size
  verification. A failed transfer must be retryable without corrupting the
  final model artifact.
- Persist every meaningful phase and progress counter. The UI must recover
  after process death, screen-off, or reopening the app.
- Preparation phases are MediaStore scan, capture/GPS/place enrichment, visual
  embedding, OCR, face embedding, clustering, persisted episode construction,
  and face review/tagging.
- Search remains locked until clustering is complete and the face review gate
  is satisfied or explicitly skipped after the three-name threshold.
- Do not put model inference or full scans on the main/UI thread.
- A phone disconnect is not an indexing failure. Resume from the persisted
  state when the device returns.

Tips:

- Verify `PreparationStore` before proposing a re-index. “Model installed”
  and “index ready” are separate facts.
- Trust persisted progress and actual artifact checksums over a stale UI label.
- Preserve already indexed work. A repaired search or answer path should not
  invalidate the 16,194-vector resident index unless a model/dimension
  contract really changed.

### 4.2 Model catalog and provisioning

Primary files:

- `ModelCatalog.kt`
- `ModelInstaller.kt`
- `LiteRtModel.kt`
- `app/src/main/assets/` and `model-artifacts/`

Fixed model roles:

| Role | Contract |
| --- | --- |
| SigLIP2 image | direct `SigLIP2-base-patch16-224`, normalized 768-D image vector |
| SigLIP2 text | aligned text tower, tokenizer IDs `[1,64]`, normalized 768-D vector |
| OCR | bundled ML Kit Text Recognition v2 Latin `16.0.1`, confidence-gated text stored in SQLite |
| face detection | YuNet boxes + five landmarks |
| face identity | FaceNet-512, aligned `160x160x3` RGB -> 512-D vector |
| planner/answer | `google/gemma-4-E4B-it` LiteRT-LM, GPU resident for text and vision |

Requirements:

- Exact model IDs, revisions, dimensions, input normalization, and checksums
  are authoritative. Do not silently substitute Gemma 3 270M, a GGUF planner,
  a different SigLIP export, or a different face encoder.
- The APK may contain native LiteRT/LiteRT-LM/Rust code but no Python,
  PyTorch, Paddle, ONNX Runtime, Transformers, or cloud runtime.
- Provisioning must distinguish blank/missing download URLs, partial files,
  checksum failures, and already-valid artifacts.
- Never delete a valid model or index as a generic recovery action.

### 4.3 MediaStore scan and SQLite database

Primary files:

- `GalleryIndexer.kt` (`scanBlockingInternal`)
- `GalleryDatabase.kt`
- `GalleryMedia` in `GalleryDatabase.kt`

`GalleryMedia` currently carries indexed fields plus lazy answer fields:

```text
mediaStoreId, contentUri, mimeType, displayName,
dateModifiedSeconds, sizeBytes, width, height, durationMs,
personClusterId, personLabel, ocrText,
dateTakenMs, location, locationName
```

Requirements:

- MediaStore ID is the stable external identity across SQLite, vector index,
  face occurrences, result details, and internal prompt labels.
- Keep image and video rows. Videos use a first frame for visual indexing and
  retain duration/metadata.
- Capture time, raw GPS, and readable location are enriched during preparation
  and indexed in SQLite; they are not part of the visual vector contract.
- Face embeddings remain encrypted in SQLite and must not be placed in the
  visual TurboQuant index.
- DB migrations must preserve existing labels and indexed flags. Increment
  `DATABASE_VERSION` only with an explicit migration.
- Current database schema version is 9; verify before adding a column.
- `scanBlockingInternal` enumerates the current MediaStore image/video rows and
  calls `GalleryDatabase.upsert` by stable `content_uri`. The database computes
  a media signature from modified time, byte size, width, and height; an
  unchanged signature preserves all indexed flags, while a changed signature
  resets image, OCR, and face work for that row only. This is the intended
  incremental/resume path and must remain the default after search fixes.
- The current scan does not prune SQLite rows that disappeared from
  MediaStore. Treat stale-row pruning as a separate, explicitly validated
  maintenance operation: it must reconcile the vector index, face rows, OCR,
  labels, episodes, and result details atomically before being enabled. Never add a broad
  delete to the normal scan as a quick fix.

Hot-path rule: query-time database work should use bounded projections,
indexed IDs, and small candidate windows. Do not open every photo merely to
answer a metadata question unless an explicit, cached fallback requires it.

Preparation queue rule: pending image/OCR/face workers use a core SQLite
projection without the correlated named-face aggregation. The full person-label
projection remains reserved for search/evidence rows that actually need it.

### 4.4 Bitmap loading and orientation

Primary files:

- `MediaBitmapLoader.kt`
- `FaceCropper` in `YuNetFaceDetector.kt`
- display helpers in `GalleryIndexer.kt`

Two coordinate contracts must remain separate:

1. Indexing/face detection loads the raw bitmap (`applyExifOrientation=false`)
   so stored normalized face boxes match the original coordinate system.
2. Answer boards, source thumbnails, and face previews load with
   `applyExifOrientation=true`; `ImageOrientation.transformBox` maps raw face
   boxes into the oriented bitmap before cropping.

Requirements:

- Decode at a bounded maximum dimension. Never retain full-resolution images
  for Gemma context.
- Preserve aspect ratio when drawing contact sheets/boards. Never stretch a
  portrait photo into a landscape tile.
- Recycle temporary contact sheets and crops after JPEG encoding.
- A decode failure must drop that visual record from the board without
  shifting G-labels onto a different record.
- Keep video rotation handling separate; EXIF image rotation does not imply
  that all video metadata uses the same path.

Common regression: applying orientation during indexing without transforming
existing boxes silently corrupts face crops and forces a full re-index. Avoid
that migration unless explicitly required.

### 4.5 SigLIP2 image indexing

Primary files:

- `GallerySemanticIndexer.kt`
- `SigLipImageEncoder.kt`
- `SigLipTextEncoder.kt`
- `NativeTokenizer.kt`
- `NativeVectorIndex.kt`

Fixed vector contract:

```text
image input: [1,3,224,224] FLOAT32, SigLIP normalization
image output: [1,768] FLOAT32, L2-normalized
text input: exact tokenizer IDs [1,64] INT32
text output: [1,768] FLOAT32, L2-normalized
storage: TurboQuant 4-bit, stable MediaStore IDs
```

Requirements:

- Index each pending image/frame once and persist the result atomically.
- Mark damaged/unsupported items handled without discarding other batches.
- Keep image encoder resident only during indexing. Release the SigLIP text
  encoder after retrieval when the memory policy calls for it; never use the
  image encoder in the search hot path.
- Use the native Rust/TurboQuant index for search and diversity; do not copy
  all vectors into Kotlin or rank by a slow Kotlin loop.
- Never mix dimensions, model versions, or index files. The known resident
  index artifact is `siglip2-768-4bit.tvim`; verify its actual device path on
  reconnect rather than assuming a workspace asset exists.

### 4.6 OCR indexing and matching

Primary files:

- `OcrIndexer.kt`
- `MlKitOcrEngine.kt`
- `OcrMediaReader.kt`
- `OcrIndexContract.kt`
- `OcrReindexWorker.kt`
- `GalleryDatabase.kt`

Requirements:

- OCR is extracted at indexing time and stored per media item.
- The bundled ML Kit Text Recognition v2 Latin model is available immediately
  offline. OCR does not require a model download after installation.
- Preserve source aspect ratio. Region-decode very tall/wide images as
  overlapping strips so a long screenshot never becomes an unreadable
  thumbnail or a huge full-image allocation.
- Apply line confidence and document-structure validation before a row becomes
  `doc`; isolated short hallucinations are not indexed.
- Version the engine, preprocessing, and quality gate with `ocr_signature`.
  A signature migration must enqueue only image OCR rows.
- Commit `ocr_text`, `content_class`, `ocr_indexed`, and `ocr_signature`
  atomically. A failed inference stays pending for retry.
- The OCR-only worker must never invoke MediaStore scan, geocoding, visual
  embedding, face indexing/clustering, or episode rebuilding.
- OCR-derived `doc` classification applies to images. Do not spend OCR
  inference on video keyframes.
- Current OCR retrieval is keyword/SQLite matching, not a semantic OCR vector
  index. Do not describe it as semantic search.
- Every `doc` query must fuse one conceptual semantic branch with one
  planner-authored OCR conjunction using `+`. Every braced OCR word must match
  the same `ocr_text`; partial matches are excluded. A complete OCR conjunction
  is the perfect tier above every semantic-only document result.
- OCR exclusions must be applied before answer evidence selection.
- For selected document evidence, pass the complete OCR text to Gemma; do not
  use query-term-centered windows or prompt-side OCR truncation.
- OCR inference uses bounded parallel workers with independent recognizers;
  SQLite writes remain serialized.

### 4.7 Face detection, identity, clustering, and encryption

Primary files:

- `YuNetFaceDetector.kt`
- `FaceNetEncoder.kt`
- `FaceIndexer.kt`
- `FaceClusterer.kt`
- `FaceEmbeddingCrypto.kt`
- `FaceModels.kt`
- face tables/methods in `GalleryDatabase.kt`

Requirements:

- YuNet detects boxes/landmarks; FaceNet receives a square aligned crop and
  emits a normalized 512-D identity vector.
- Raw face embeddings are encrypted with Android Keystore AES-GCM and kept
  separate from the visual retrieval index.
- Clustering creates anonymous stable groups. Existing labels must survive
  centroid refresh when the refreshed centroid matches the prior group.
- One normally named cluster may carry the exclusive local `is_self` marker.
  Rebuilds and merges must preserve it with the matched identity; changing it
  clears the marker from every other cluster without touching vectors or
  labels.
- Only named clusters become authoritative person metadata for query scoping
  and answer identity links.
- `TaggedFaceOccurrence` is the bridge from a named local cluster to a
  specific G record and raw normalized face box.
- Face detection count is bounded per image; do not let crowded photos create
  an unbounded memory/UI payload.

Identity rule for Gemma: a local `person=Ramani` tag is authoritative even if
the name is not written in the pixels. The face board helps Gemma visually
connect the tag to people in the gallery board; it must not be treated as a
new gallery source or as permission to invent an identity for an untagged
person.

### 4.8 Face review/tagging UI

Primary files:

- `FaceClustersActivity.kt`
- `FaceModels.kt`
- `MainActivity.kt`
- `SettingsActivity.kt`

Product requirements:

- When thousands of groups exist, show the complete current snapshot in a
  virtualized list with one representative crop per group.
- Each group needs select/name/merge affordances that remain responsive across
  the full cluster count. Keep selection stable so the user can choose every
  merge candidate before committing once.
- The naming dialog must ask whether the group is **This is me**. Store one
  actual display name plus the exclusive self marker rather than separate
  `I`, `me`, and `my` labels. Explain that those pronouns map to this identity.
- “Merge groups” must visibly enter a working state, show a polished spinner,
  disable duplicate taps, persist all selected merges, then refresh the stable
  list and selection state. Errors must restore an actionable button and
  explain what failed.
- Names are optional. After `FaceTaggingPolicy.MIN_NAMED_GROUPS_FOR_SKIP` (3)
  groups are named, show Skip and allow search to continue without naming all
  groups.
- Settings must provide a later “revisit face grouping/tagging” entry point;
  it must load current DB state rather than reconstructing clusters in the UI.
- Face thumbnails must be EXIF-correct and use transformed stored boxes.
- Never force the user to tag anonymous groups or expose raw face embeddings.

### 4.9 Query planner and plan schema

Primary files:

- `QueryPlan.kt`
- `QueryPlannerRuntime.kt`
- `QueryScope.kt`

Gemma 4 E4B converts every query into a category plus set operations:

```text
required routing field: query_category
positive semantic/OCR branches: ADD or UNION
hard person/time/location/MIME fields: INTERSECT
without/not/excluding branches: SUBTRACT
last/latest requests: SORT by time
```

There is one query processor: Gemma 4 E4B. Every expression begins with exactly
one `[query_category == ...]` predicate joined to retrieval with `&&`. The
allowed category values are `doc`, `scenary`, `person`, `location`, and `time`.
Retrieval predicates use only `person`, `mime type`, `from_date`, `to_date`,
`location`, `semantic`, and `ocr`.
Operators and precedence are:

```text
postfix SORT_DATE / SORT_LOC
then + and -
then &&
then ,
```

`,` is alternative union, `+` is fused positive retrieval, `&&` is hard
intersection, and `-` is subtraction. Brackets delimit predicates and explicit
groups. Dates are canonical ISO `yyyy-MM-dd`.

Required examples:

```text
Ravi without glasses
  [query_category == scenary]
    && [[[person == Ravi] && [mime type == photos]] - [semantic == glasses]]

Meghana photos from last team outing
  [query_category == scenary]
    && [[person == Meghana] && [semantic == team outing]] SORT_DATE

last Goa trip photos without Ramani
  [query_category == scenary]
    && [[[location == Goa] && [mime type == photos]] - [person == Ramani]] SORT_DATE

electricity bills from June 2024
  [query_category == doc]
    && [[[from_date == 2024-06-01] && [to_date == 2024-06-30]]
    && [semantic == electricity bill]]

where did I go last month
  [query_category == location]
    && [[from_date == LAST_MONTH_START] && [to_date == LAST_MONTH_END]] SORT_DATE

when did Ravi go swimming
  [query_category == time]
    && [[person == Ravi] && [semantic == swimming]] SORT_DATE
```

Planner requirements:

- Do not hard-code Ramani, Ravi, Goa, or any example name/place.
- Classify by the information requested in the answer: OCR/written facts are
  `doc`, who/identity is `person`, where/visited places is `location`, when/date
  is `time`, and visual activities/content use `scenary`.
- `mime type` is exactly `photos` or `videos`. Documents, receipts, bills,
  invoices, scans, and screenshots are semantic/OCR concepts, never MIME.
- `semantic` is one compact, conceptual, semantically searchable phrase. It
  must not contain question/routing words, dates, time ranges, person names,
  place names, or MIME words already represented by structured fields. For a
  broad untargeted document aggregate or collection query, use one conceptual
  phrase such as `purchase receipt payment record`; place likely printed
  alternatives such as receipt, bill, invoice, payment, paid, purchase, total,
  or amount in the dedicated `ocr` predicate. Never use abstract intent such
  as spending, expenses, finances, paperwork, or documents as the only
  semantic value. Keep the fused retrieval group intersected with any requested
  date range.
- Every `doc` plan must contain exactly one conceptual `semantic` predicate
  and one planner-authored `ocr` predicate joined by `+` in the same group.
  The `ocr` value contains 2–6 essential likely co-occurring words in explicit
  syntax: `[ocr == {Ravi} && {passport}]`. Every braced word must occur in the
  same `ocr_text`; partial matches do not enter the OCR branch. The outer `+`
  retains semantic-only fallback, but every complete OCR match ranks above it.
  For self identity documents, use only the actual tagged name and document
  words; reject `person`, `self`, `me`, `my`, and similar aliases.
- Passport, driving licence/DL, SSN/social-security number, PAN, Aadhaar/Aadhar,
  ID number, Wi-Fi password, password, user ID, DOB/date of birth, exact age,
  marks, grades, scores, and report-card fields request written facts and must
  route to `doc`. If a doc query names a known person as the document owner or
  subject, keep that name in the OCR keywords instead of adding a face
  predicate merely because the local face label exists.
- For presence-oriented self references such as photos of me, who was with me,
  where I went, what I wore, or my birthday photos, compile the exclusive
  self label as the person predicate. Do not add a face predicate for ordinary
  document ownership/agency such as my passport, my password, or how much I
  spent.
- Emit `from_date`/`to_date` only when the original query contains an explicit
  date, month, year, weekday, season, relative-time phrase, or time of day.
  Present tense, amount/spending wording, `when`, `latest`, and a missing date
  do not authorize an inferred today range. Reject and repair any such invented
  date predicates.
- For one exact calendar day, including today or yesterday, emit both
  `from_date` and `to_date` and make both equal the resolved ISO date. Reject a
  missing or unequal endpoint. Explicitly open-ended wording such as after,
  before, or since may use one boundary.
- Required undated example:
  `how much I spend on car repair` →
  `[query_category == doc] &&
  [[semantic == car repair payment record] + [ocr == {car} && {repair}]]`.
- Required broad aggregate example:
  `how much did I spend last month` →
  `[query_category == doc] && [[from_date == LAST_MONTH_START] &&
  [to_date == LAST_MONTH_END] &&
  [[semantic == purchase receipt payment record] +
  [ocr == {total} && {amount}]]]`.
- `semantic` may be clarified or paraphrased into one conceptual phrase, and
  `ocr` is planner-authored from likely printed words. Preserve exact names,
  places, time intent, and negation; every other structured value may receive
  only spelling correction or required date/MIME canonicalization. Never widen a
  person exclusion into a broad semantic subtraction that removes unrelated
  images merely containing the name.
- Use Gemma 4 E4B for every query, including clear structured queries. The
  deterministic compiler and fallback are disabled. If the model is missing,
  fail clearly. If its first expression is invalid, request one repair in the
  same conversation; if repair is invalid, fail rather than widening search.
- The effective execution spec must describe the sanitized AST actually sent to
  retrieval, not only the raw model output.
- Never flatten a model AST into field lists before execution; nested union,
  intersection, addition, subtraction, and postfix sorting retain their formal
  semantics.
- The canonical effective execution spec shown in the live QP panel is
  diagnostic UI only; raw planner JSON remains internal, and neither may leak
  into the answer text.
- Clean planner and answer conversations are intentionally separate. Reusing
  a planner conversation for the answer can leak grammar/schema language.

### 4.10 Hard scopes and structured matching

Primary file: `QueryScope.kt`, with enforcement in `GalleryIndexer.searchBlocking`.

Requirements:

- Person inclusion is an allowlist of MediaStore IDs from named cluster labels.
- Person exclusion is a subtraction allowlist and must be applied before
  fusion/answer selection.
- Time scopes use MediaStore capture dates first, then bounded lazy metadata
  fallback only when the provider exposes no usable dates. Prefer capture time
  over modified time in answers.
- MIME scopes accept exactly `photos` or `videos` and execute as exact
  image/video filters. `documents` is never a MIME value.
- Location scopes must match human place names against provider strings or
  GPS, not merely semantic similarity. Use a native/provider allowlist when
  available, then exact lazy EXIF matching.
- All active hard scopes must be reflected in the effective plan and logs.
- A scope failure must not silently fall back to unrelated semantic results.

### 4.11 Metadata and human-readable locations

Primary files: `MediaLocationAccess.kt`, `GalleryMetadataReader.kt`,
`LocationIndexer.kt`, `LocationReindexWorker.kt`, and `GalleryDatabase.kt`.

Location contract:

```text
raw location is retained for audit/filtering, e.g. GPS lat, lon
locationName is a cached reverse-geocoded human form, e.g. Panaji, Goa, India
prompt/source display may show: Panaji, Goa, India (GPS lat, lon)
```

Requirements:

- Keep valid raw GPS and readable location separately. Reject out-of-range
  coordinates and the `0°,0°` EXIF missing-value sentinel.
- On Android 10+, declare/request `ACCESS_MEDIA_LOCATION` and open photos
  through `MediaStore.setRequireOriginal()` before reading EXIF. Ordinary
  media-read access is redacted and must never be persisted as authoritative
  proof that a photo has no GPS.
- If precise-photo-location consent is unavailable, leave image location rows
  pending while allowing the rest of indexing/search to remain usable.
- During initial/resumable indexing, extract capture time and GPS, try Android's
  online geocoder first, then use the optional OpenStreetMap Nominatim fallback
  when the system service returns no result. Persist the readable place
  directly in `media_items.location_name`.
- The fallback may receive only coordinates rounded to three decimal places
  (roughly 110 m), never media bytes, OCR, faces, filenames, or query text. It
  must identify the app, serialize requests below one request per second, be
  switchable at runtime, show OpenStreetMap attribution, and remain outside the
  query path.
- Cache successful lookups durably by a locality-sized coordinate key so burst
  photos do not make repeated online calls. A GPS row that cannot resolve
  remains pending for a later preparation retry; it must not be marked as a
  human-readable location.
- If the detailed reverse lookup has no address object, make at most one
  serialized coarser regional lookup and keep only regional trailing display
  components, never a house/street fallback label.
- Database v12 and the dedicated location-only WorkManager path may invalidate
  and rebuild only image `location_raw`, `location_name`, and enrichment state.
  They must not modify capture time, OCR, vectors, faces, clusters, or episodes.
- Final evidence enrichment may use a bounded compatibility fallback, but
  query-time search must normally consume the persisted place index.
- For location filtering, direct normalized text matching is free and comes
  first; forward geocode the requested place once, then compare coordinates
  using an appropriate locality/region radius.
- Cache a normalized location request for five minutes and at most eight
  entries. A successful MediaStore coordinate scan with no matching IDs is an
  authoritative empty hard scope; only provider/geocoder/coordinate
  unavailability may return `null` and permit bounded EXIF fallback.
- Apply the same distinction to capture time: a successful MediaStore scan
  with usable dates and no matches is an authoritative empty time scope; a
  provider failure or a dataset with no usable capture dates returns `null` and
  may use the bounded fallback path. Never widen a known-empty date scope with
  unrelated semantic matches.
- MediaStore photo GPS columns are preferred for a fast allowlist; EXIF is the
  fallback. Videos may require a separate metadata path.
- Coordinates for matching MediaStore rows are retained in the bounded
  per-process media cache, so final evidence enrichment does not reopen those
  same files through EXIF. Do not turn this into an unbounded all-gallery
  string cache.
- Geocoder availability/network failure is not a reason to return unrelated
  photos. If no readable place can be derived, show raw GPS or say location is
  unavailable in natural language.

Device validation on 2026-07-25 superseded the earlier provider-only
observation. Before unredacted access, only 100 video GPS rows were visible and
location-scoped photo retrieval was empty. After the isolated v12 migration,
explicit permission, and `setRequireOriginal()` EXIF pass, 9,834 photos plus
100 videos have valid raw GPS and readable persisted names, 1,324 locality
cache entries exist, and no row remains pending. Fifteen `0°,0°` photo
sentinels were correctly classified as missing GPS. Keep comma-separated,
ISO-6709, bounds, and Null Island parser contracts covered by tests.

### 4.12 Retrieval and hybrid fusion

Primary files:

- `GallerySemanticIndexer.kt`
- `NativeVectorIndex.kt`
- `GalleryDatabase.kt`
- `GalleryIndexer.kt`

Current execution model:

```text
canonical AST -> recursive union/add/intersection/subtraction evaluation
semantic predicate -> TurboQuant image search + SQLite OCR/place/person branch
ocr predicate -> OCR-only SQLite AND over all braced words in one row
semantic + ocr -> OCR-perfect tier followed by semantic-only fallback
semantic vector branch -> prefer cosine scores at or above 0.10
empty positive semantic + metadata branch -> up to 200 nearest candidates
  inside the accumulated hard scope
negative semantic branch -> strict cutoff only; never subtract nearest fallback
hard predicate -> indexed person, MIME, date, or location ID set
postfix sort -> capture date or readable place ordering
full evaluated set -> top 200 category-aware overall relevance shown immediately
```

Requirements:

- `StructuredSearchExecutor` executes the AST directly. It must not flatten a
  union into simultaneous field intersections.
- Use the native vector index for cosine-like search. Do not implement a
  Kotlin per-vector loop for hot paths.
- Semantic query variants are bounded and deduplicated. Once hard scope is
  active, one sanitized semantic branch is usually enough; extra branches
  must not escape the scope or multiply latency.
- Normalize and deduplicate planner-authored OCR words, then require all of
  them in the same `ocr_text`. Treat a complete conjunction as a perfect match
  above semantic-only fallback. Use only essential co-occurring words; do not
  AND mutually exclusive document-type synonyms.
- Preserve executor order in the public grid. Scenery ranks semantic score
  first; documents rank complete OCR conjunctions first; person/location
  retain exact metadata scopes and use semantic relevance within that scope.
  Capture date is an equal-score tie-breaker, not a replacement ranking.
- Apply person/time/location/MIME/negative operations before evidence curation,
  diversity, or answer generation.
- Keep the index resident and release only the text encoder/model component
  when memory policy requires it. Do not unload the vector index between
  queries unless the database/index itself is being rebuilt.
- Cache a small bounded set of normalized SigLIP text embeddings for repeated
  or follow-up queries; never cache image embeddings in the search path or
  mutate the persisted gallery index for a query.
- Log candidate counts and scope application without logging sensitive raw
  face vectors or full notification bodies.

Performance reality to preserve in future work: recent live fast-path queries
showed planner around 5 ms, retrieval around 3.1–3.7 s, and Gemma answer
generation around 45 s even after reducing multimodal input to one board. The
retrieval target is still lower than this; measure each phase before changing
the algorithm. Do not claim “under milliseconds” without a device trace.

### 4.13 Persisted episode index and query join

Primary files: `EpisodeIndex.kt`, `GalleryDatabase.kt`, and `EvidenceBuilder.kt`.

Purpose: move time/person/place episode construction out of the search hot path,
then cheaply choose the most query-relevant retrieved member from each episode.

Current behavior:

- after location enrichment and face clustering, preprocessing groups the full
  gallery chronologically;
- close bursts group when places do not conflict; longer outings require place
  compatibility or anonymous person overlap; multi-day bridges require both;
- SQLite v9 stores episode headers and one indexed membership row per media ID;
- query time performs one bounded indexed membership join over the retrieved
  IDs, with no EXIF reads, geocoding, or chronological reclustering;
- each query uses its highest-ranked matching member as that episode's
  representative;
- explicit count/list event questions may expose up to 12 E-group summaries to
  Gemma; all queries may use episode membership for Context Picker diversity.

Requirements:

- Episode construction is a derived, atomically replaceable index. Rebuild it
  only at the final preprocessing stage, never in response to a query.
- Select group representatives only after hard query scopes are applied.
- Count episode groups rather than raw near-duplicate photos.
- Tell Gemma the exact candidate-group count and ask it to distinguish
  candidate episodes from visually confirmed requested events.
- Do not call every group a confirmed human trip unless the joined time,
  location, and visual context justify it.
- Keep this pass bounded and deterministic. If a true iterative evidence agent
  is added later, impose a fixed step/token/time budget and preserve a final
  deterministic safety boundary.

### 4.14 Category-scoped search and Context Picker

Primary files:

- `AnswerContextPicker.kt`
- `MultimodalDiversitySelector.kt`
- `GallerySemanticIndexer.kt`
- `NativeVectorIndex.kt`

Requirements:

- During OCR indexing, persist `content_class=doc` for every image with any
  non-empty OCR text; persist `content_class=scenary` for all other
  images/videos. Filename heuristics must not override this classification.
- Apply `query_category` as a hard retrieval allowlist before rendering the
  full result browser or selecting private answer context:
  - `doc`: indexed `doc` records;
  - `scenary`: indexed `scenary` records;
  - `person`: records with named-person metadata;
  - `location`: records with resolved readable locations;
  - `time`: records with indexed capture/modified time.
- Select at most 8 eligible records and treat this as the complete LLM gallery
  context; never fall back to ineligible results or append an uncurated tail.
- Attach pixels only for `doc` and `scenary`. `person`, `location`, and `time`
  must skip bitmap decode and visual reranking.
- For visual categories, keep one strong relevance anchor, allocate no more
  than two-thirds of the budget to requested facet/episode coverage, and retain
  the remainder for visual/multimodal novelty.
- Give each requested evidence dimension one representative before one
  dimension consumes its full allowance.
- Prefer one query-ranked image from every unseen persisted episode before
  selecting a second view from an already represented episode.
- Visual diversity must use native persisted vectors or a bounded equivalent;
  it must not re-encode images during search. Metadata-only categories must not
  invoke the visual selector.
- Balance native visual order with retrieval relevance, OCR vocabulary
  novelty, readable-location novelty, person/media metadata, and chronological
  spread.
- Exact media IDs, episode views, and repeated facet keys must be deduplicated.
- Join OCR only for OCR/text intent, time only for time intent, people when
  identified, and readable location whenever applicable.
- Diversity must not replace all high-relevance candidates with visually
  unrelated outliers.
- Record the phase timing and selected IDs so Time stats are auditable.
- If native diversity fails, use a bounded deterministic fallback and log it;
  never crash the answer path.

### 4.15 Gemma 4 runtime, residency, and KV cache

Primary file: `GemmaRuntime.kt`.

Fixed runtime contract:

- model file: `gemma-4-E4B-it.litertlm`;
- GPU backend for both the frozen QP/language graph and vision encoder/adapter
  on the target Samsung; no CPU fallback is permitted for E4B;
- resident singleton engine per app process;
- planner system preface warmed while the search field is available;
- planner prefill session is taken for the planning turn;
- close the consumed planner session immediately after the planning turn,
  before SQLite/native retrieval, EXIF decode, board construction, or answer
  prompt assembly, so its KV memory cannot compete with later phases;
- while retrieval and answer-context selection run, warm a separate clean
  answer system-prefix session; never seed it with planner history or the
  planner execution turn;
- answer uses a clean conversation to prevent planner syntax leakage;
- planner sampling is deliberately constrained (`topK=16`, `topP=0.90`,
  temperature `0.10`); answer sampling is low-variance but natural (`topK=40`,
  `topP=0.92`, temperature `0.35`);
- compiled graph image capacity remains 8, while each request sends no images
  for doc/person/location/time and at most 4 downscaled images for scenary;
- configured input/output graph capacity is 8192 tokens so complete selected
  OCR/metadata and up to four scenery image inputs fit without answer-stage
  prompt truncation.

Requirements:

- Serialize generation calls with the runtime lock. Do not run planner and
  answer generations concurrently on the same resident engine.
- Close consumed planner sessions and release unused prefilled sessions.
- Do not carry the planner session through retrieval: the prefilled system KV
  is reused for planning, then the session is closed immediately.
- Keep residency predictable and measure native, graphics, swap, and total PSS
  on the actual phone. CPU-only vision crossed Samsung's per-process guard;
  do not regress the verified split backend without a device memory audit.
- Log LiteRT-LM's privacy-safe planner/answer benchmark counters on device:
  wall time, prefill/decode token counts, time to first token, and token rates;
  never log the query, prompt, OCR, face labels, or image bytes.
- A vision graph failure should retry with the same joined metadata/OCR/person
  context in a text-only answer turn, not show a generic empty UI error.
- Detect planner-shaped answer output and retry a clean natural-language turn;
  if it repeats, use a direct natural fallback without internal terminology.
- Apply the same leak check to the text-only recovery turn. If sanitation
  removes every public token, use the grounded fallback rather than an empty
  answer card.
- Do not lower image count/graph settings casually to hide memory failures;
  first measure image bytes, prompt tokens, model residency, and native memory.

### 4.16 Final answer context and prompt

Primary file: `GalleryIndexer.kt` (`buildAnswerPrompt`, `answerDirective`,
`parseAnswer`).

Every selected G record should join as appropriate:

```text
G label + attached pixels (if decoded)
person tag(s)
capture time and modified time
human location + raw GPS when available
MIME/duration
complete OCR text
episode/group reference when applicable
```

Image-input contract:

- doc and scenary pass at most four individual images, each strictly
  downscaled to a 512 px longest edge;
- document images carry the paired OCR text selected for the query;
- person/location/time pass no image input;
- the prompt explicitly maps image-input order to G references.

Prompt requirements:

- Tell Gemma to reason over the complete joined text and any attached images.
- Use local person tags as authoritative identity links.
- For “when” prefer capture time; for “where” use readable location/raw GPS;
  for “who” use named tags; for OCR use only supplied OCR.
- Keep complete OCR text in the answer prompt only when the planner identifies an
  OCR/text need. Visual records still carry pixels, people, capture time, and
  location so general answers remain joined without paying for unrelated OCR
  tokens.
- Group exact duplicate structured field rows while preserving all associated
  G join labels. Repetition is not context coverage and must not consume
  avoidable prefill time.
- For activity questions, visual tiles can prove an activity; metadata alone
  can establish presence/time but not what a person was doing.
- For birthday queries, distinguish a birthday-event photo date from a stored
  birthdate; never infer a birthdate from the earliest photo.
- For counts, report candidate episode count first when EvidenceBuilder gives
  one, then the smaller visually confirmed count if appropriate.
- Keep G/C labels internal; never expose them in the user-visible answer.
- Strip complete/dangling model reasoning tags and task echoes as well as
  planner syntax. Never append generic browse filler merely to manufacture a
  second sentence.
- Reply in 2–3 concise sentences and add at most three useful, contextual
  follow-up queries such as who else was present or what happened that day.

Output sanitation is a second line of defense. `AnswerTextSanitizer.clean`
must strip or rewrite variants of:

```text
based on the provided/available/supplied evidence
evidence you provided
according to supplied records
gallery records
the evidence does not specify/confirm/show
```

The UI fallback strings must obey the same rule; do not fix only Gemma output.

### 4.17 Follow-ups and private answer context

Primary files:

- `GalleryIndexer.kt` (`defaultFollowUps`, source construction)
- `MainActivity.kt`
- `AnswerModels.kt`

Requirements:

- Follow-ups must be generated from the current query/answer context and should
  be interesting gallery queries, not source-review actions.
- Merge model follow-ups with deterministic useful defaults, deduplicate, and
  cap at three.
- Gallery source IDs G1–G8 and personal-context IDs C1–C4 are prompt-internal
  alignment aids only and must be removed from visible answer text.
- Do not render the top-8 answer context as a second gallery or a source-chip
  strip. The user-visible grid remains the full bounded search result set.
- Clicking a result opens its EXIF-correct detail view; back navigation returns
  to the same search surface.

### 4.18 Main UI, progress, and Time stats

Primary file: `MainActivity.kt`.

Requirements:

- Search-bar submission must dispatch search, never the Settings button or
  personal-context controls. This was a previous live-screen regression.
- Show progress for planning, hybrid retrieval, diversity/evidence preparation,
  and Gemma answer generation without blocking the main thread.
- Show `QP output • planning` immediately below the search bar after submit.
  Replace it with the corrected canonical execution spec at the exact planning
  boundary, before structured retrieval begins.
- Keep the QP output visible during retrieval and answer generation so the user
  can inspect how the query is being executed.
- The merge button and any long indexing action need a visible spinner until
  completion.
- Show a timing chip beside the QP output from query start. Expansion must include
  the available completed phases:

```text
query planning
search/retrieval
evidence curation
  top-8/context selection
answer generation
follow-up phase
total
```

- The effective execution spec belongs in the live QP panel, not hidden inside
  end-of-answer timing details. Raw planner JSON remains diagnostic-only.
- Display up to the top 200 overall query-ranked results, keep `GridView` as the sole vertical
  scroller, and never replace that grid with the private top-8 answer context.
- UI errors should be conversational and actionable. Avoid “evidence” as a
  generic error noun; say “matching photos or details” instead.
- Keep QP and time telemetry compact so they do not consume the browsing area or
  inflate the answer layout.

### 4.19 Personal context and notifications

Primary files:

- `PersonalContextNotificationListener.kt`
- `NotificationContextParser.kt`
- `PersonalContextDatabase.kt`
- `PersonalContextCrypto.kt`
- `PersonalContextAccess.kt`

Requirements:

- Personal context is opt-in and separate from gallery indexing.
- Only future relevant notifications are captured after access is enabled.
- Reject OTP/PIN/password/bank-security and unrelated ongoing alerts.
- Encrypt stored notification facts with a separate Android Keystore AES-GCM
  key; do not mix this key with face encryption.
- Keep bounded retention (currently 180 days / bounded rows) and deduplicate.
- Search personal context only when the planner requests context and settings
  plus notification access allow it.
- Never send notification history or full sensitive bodies by default. Prompt
  only bounded relevant fields/snippets; keep C source IDs internal.
- Clearing personal context must not touch gallery DB, face labels, or vectors.

### 4.20 Native boundary and Rust TurboQuant

Primary native areas:

- `app/src/main/cpp/`
- `native/askgalaxy-native/`
- `NativeVectorIndex.kt`
- `NativeTokenizer.kt`

Requirements:

- JNI/native code owns TurboQuant vector operations and tokenizer hot paths.
- Keep the native index handle lifecycle explicit: open once for search,
  close/reopen only around a deliberate indexing replacement.
- Stable IDs must round-trip without truncation.
- Keep image/text dimensions and bit-width checks at the boundary.
- Retain arm64-only packaging and 16 KiB ELF/APK alignment checks.
- Avoid allocating large temporary Kotlin arrays for all 16K vectors.
- If native search/diversity fails, return a bounded fallback or a clear error;
  do not silently return an unscoped full-gallery list.

## 5. Privacy and security rules

- Face vectors and person labels stay local; no face data is uploaded.
- Notification facts are opt-in, encrypted, bounded, and independently
  clearable.
- Raw GPS is sensitive; retain it only for the local filter/source contract and
  do not spam it into logs. Prefer human names in the answer.
- Log counts, phase timings, scope booleans, and model failures, not full
  notification bodies, raw face vectors, or unnecessary personal content.
- Do not add a cloud fallback merely because Gemma is slow or a geocoder is
  unavailable.

## 6. Performance and memory checklist

Hot paths:

1. query planning;
2. SigLIP text encoding;
3. native TurboQuant search;
4. SQLite metadata/OCR matching;
5. hard-scope filtering;
6. native diversity selection;
7. bounded bitmap decode/board encoding;
8. Gemma prompt prefill/decode.

Optimization rules:

- Measure each phase separately with `PhaseTimings`; do not optimize based on
  total wall time alone.
- Prewarm Gemma and SigLIP text only after the search field is available, off
  the UI thread.
- Keep planner prompts compact and prefill stable instructions in KV.
- Reuse the stable Gemma planner prefill and keep its output grammar compact;
  never introduce a deterministic planning bypass.
- Keep semantic variants bounded; do not run the same query through the text
  encoder repeatedly.
- Use hard allowlists to reduce native search work and candidate enrichment.
- Enrich/reverse-geocode only the final small set.
- For doc and scenary, pass at most four separate images with a strict 512 px
  longest-edge bound; document inputs also retain their query-relevant OCR text.
- Recycle all temporary bitmaps and close conversations in `finally` blocks.
- Keep Gemma generation serialized and monitor system low-memory kills.

Verified answer-only device matrix after prompt compaction and split vision:

```text
doc, one board + OCR: 40.209 s
scenary, one board: 46.984 s
person, metadata only: 21.491 s
location, metadata only: 29.765 s
time, metadata only: 26.156 s
```

All five produced two grounded natural sentences without private IDs or prompt
leakage. Detailed before/after evidence is in
`ANSWER_GENERATION_AUDIT_REPORT.md`. Visual latency remains material and must
be improved with device traces, not by hiding phases or weakening context.

## 7. Build and handoff runbook

The checkout is not a normal Git worktree; `git status` may report that no
repository exists. Preserve files directly and use `rg`/`rg --files` for
inspection.

Build/test/lint:

```bash
/home/ravi/.gradle/wrapper/dists/gradle-9.2.1-bin/2t0n5ozlw9xmuyvbp7dnzaxug/gradle-9.2.1/bin/gradle \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Expected current test state: all nine `QueryAndDiversityContractTest` cases
pass; lint passes with the known Android EXIF `getLatLong` deprecation warning.
A new warning/error must be investigated rather than assumed harmless.

Connected-device handoff (serial used in the last session: `RZCY92NW2AZ`):

```bash
adb -s RZCY92NW2AZ install -r app/build/outputs/apk/debug/app-debug.apk
adb -s RZCY92NW2AZ shell am force-stop com.ravi.askgalaxy
adb -s RZCY92NW2AZ shell monkey -p com.ravi.askgalaxy 1 >/dev/null
adb -s RZCY92NW2AZ logcat -c
```

Do not ask for command permission in the normal implementation loop. If the
phone is disconnected, continue source/build work and resume live validation
after reconnect; do not delete or rebuild the resident index as a reaction.

Last handoff snapshot before disconnect:

- last installed debug APK SHA-256:
  `108546ac9aeb88a60cf80e84556ee29dab914ae50e9aad7adbbe14ead506e214`;
- latest source-only verification APK SHA-256:
  `2d29db6d3621739f8bf3eb01d7c1d8d7d79c44a34a18d685a9bd3da08df72d04`;
  it is not installed because the phone is disconnected;
- last package update observed: 2026-07-21 08:11:48;
- last known Ask Galaxy process: PID 26314 (stale after reconnect);
- existing index previously verified at 16,194 vectors with hash
  `127557725a58d72ce4d72ddf14e064a58a9895392f122435fee9c1da4b9bb080`;
- verify all four facts again on the phone; they are handoff snapshots, not
  substitutes for current runtime evidence.

## 8. Required live query matrix after reconnect

Run at least these queries and inspect both the UI and filtered logcat:

1. `ramani birthday photos`
2. `last Goa trip photos without Ramani`
3. `Ravi without glasses`
4. `last month electricity bill`
5. `when is Ramani birthday`
6. `when did Ravi go for swimming`
7. `how many treks did I visit this year so far`
8. `where were the photos taken`
9. `show photos with Ravi but not Ramani`
10. `which trips had Ravi without glasses`

For every query verify:

- raw and effective execution specs parse and preserve the expected operator precedence;
- the effective spec becomes visible below the search bar before retrieval
  completion and remains visible through answer generation;
- effective spec contains the expected person/time/location/MIME/subtraction
  scopes and no hard-coded name behavior;
- unrelated records do not survive a named person/place/time scope;
- visual and document queries attach no more than four 512 px images selected
  from no more than eight G records, with the correct paired OCR/face metadata;
- metadata/OCR facts are present in the same G records used by Gemma;
- answer has no planner/evidence/record boilerplate and no execution syntax/regex;
- activity claims are visually calibrated and dates are capture dates;
- location answers prefer readable place names and retain raw GPS only as a
  supporting detail;
- portrait/landscape images and face crops are correctly oriented;
- Time stats expands with every completed phase while the effective spec stays
  in the adjacent QP panel;
- follow-ups are useful, deduplicated, and capped at three;
- the UI remains responsive and no wrong Settings/personal-context click is
  triggered.

## 9. Known open items at the time of writing

These are intentionally recorded so a future session does not mistake the
previous green build for full completion:

1. Follow-up generation is the next isolated module. Verify useful episode-aware
   queries, dedupe, sanitation, click-through planning/search, and latency
   without reopening the frozen QP or closed answer-context policy.
2. Visual Gemma generation remains about 40-47 seconds on the target even
   after duplicate-aware prompt compaction and GPU vision. Further work needs
   LiteRT prefill/decode traces and memory measurements, not fewer public
   results or hidden timing.
3. OCR now uses bundled ML Kit Text Recognition v2 Latin with EXIF-aware,
   aspect-preserving input, overlapping long-image tiles, confidence gating,
   and a versioned OCR-only migration. Keep a script/codec-stratified review
   item for HEIC and Indian-language text before adding another script model;
   do not widen the current Latin index from anecdotal samples.

The sanitation rule now has a pure unit-tested implementation for source IDs,
reasoning tags, task echoes, boilerplate, and sentence limits, so output cleanup
can be regression-tested without opening the Android gallery database.

Source-side fixes already verified in the latest build: diversity now retains
retrieval relevance while selecting visual variety; location allowlists cache
forward-geocoded requests and distinguish a true empty match from provider
unavailability; time allowlists now make a usable-provider zero match a true
empty hard scope instead of widening to unrelated semantic results; repeated
text queries use a bounded embedding cache; planner KV is released immediately
after planning; and planner/answer sampling is explicitly tuned for structured
routing versus natural prose; the runtime now logs privacy-safe prefill/decode
counters; named identity boards choose the highest-confidence face crop per
person; answer rows are duplicate-compacted; E4B language/QP and vision run on
GPU with no CPU fallback; and answer sanitization is
pure and unit-tested. The former
deterministic/fallback planner is now disabled: Gemma emits every category and
execution AST, gets one grammar-repair turn, and otherwise fails explicitly.
Hard validation constrains MIME to photos/videos and keeps routing/time/place/
person words out of semantic predicates. The visual evidence board now uses its full
canvas when no identity face board is needed, preserving scene detail without
adding image inputs. General visual prompts now omit unrelated OCR snippets
while preserving OCR for explicit text questions. These reduce avoidable work
but do not replace the live device Gemma profile. The location allowlist scans
the unified MediaStore Files
collection for both image and video rows, matching the time-scope universe;
the previous Images-only query could silently omit valid video location
matches. The photo-GPS path is now separately device-validated through
`ACCESS_MEDIA_LOCATION`, original MediaStore URIs, a location-only migration,
and isolation digests; do not regress it to ordinary redacted photo URIs.

## 10. Change discipline

Before editing:

- inspect the current file and live state;
- preserve unrelated user changes;
- identify whether the change touches an indexed model/coordinate contract;
- prefer a bounded, reversible change.

After editing:

- build, unit-test, and lint;
- install only when the phone is connected;
- run the relevant live query matrix;
- inspect logs and rendered UI, not only compiler output;
- report the exact APK path, checksum, install state, and any unverified
  device-dependent item.

Never use destructive repository-wide cleanup or reset commands to solve a
search/index issue. The existing index and user labels are valuable state.
