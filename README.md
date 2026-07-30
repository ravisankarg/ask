# Ask Galaxy

On-device gallery RAG for Samsung Android phones.

For the durable module-by-module contracts, quality requirements, performance
tips, known open items, and reconnect validation runbook, see
[ASK_GALAXY_MODULE_REQUIREMENTS.md](ASK_GALAXY_MODULE_REQUIREMENTS.md).
For the concise architectural memory used when resuming work, see
[ASK_GALAXY_DESIGN_MEMORY.md](ASK_GALAXY_DESIGN_MEMORY.md).
For an interactive overview of the architecture, indexing pipeline, query
execution, Context Picker, and roadmap, see the
[Ask Galaxy architecture site](https://ravisankarg.github.io/ask/).
The latest on-device QP/category/picker evaluation is in
[QP_CONTEXT_PICKER_REPORT.md](QP_CONTEXT_PICKER_REPORT.md).
The indexing-quality decision and device OCR A/B evidence are in
[OCR_INDEXING_AUDIT_REPORT.md](OCR_INDEXING_AUDIT_REPORT.md).
The category-aware Context Picker, answer grounding, memory fix, and device
latency matrix are in
[ANSWER_GENERATION_AUDIT_REPORT.md](ANSWER_GENERATION_AUDIT_REPORT.md).
The Android photo-GPS correction, location-only migration, and device corpus
evidence are in
[LOCATION_INDEXING_AUDIT_REPORT.md](LOCATION_INDEXING_AUDIT_REPORT.md).
The current strict-threshold, structured-search, and 200-result UI proof is in
[SEARCH_RESULTS_AUDIT_REPORT.md](SEARCH_RESULTS_AUDIT_REPORT.md).
The consolidated v0.2 build, hybrid document retrieval, APK, device-state
preservation, and Pages checks are in
[V0_2_VALIDATION_REPORT.md](V0_2_VALIDATION_REPORT.md).

The current milestone is an automatic, background-safe foundation:

- gallery image/video metadata is indexed from `MediaStore` into SQLite;
- capture dates, unredacted raw GPS, and online reverse-geocoded place names
  are persisted in the search index after Android precise-photo-location
  consent;
- bounded image and first-video-frame decoding is implemented for background indexing;
- bundled ML Kit Text Recognition v2 extracts confidence-gated text from
  aspect-preserving photo/screenshot inputs for local OCR matching;
- the installed direct SigLIP2 LiteRT artifact can produce normalized 768-D embeddings;
- the aligned SigLIP2 text tower and pinned 256k BPE tokenizer turn queries into the same 768-D space;
- the required YuNet + FaceNet-512 face path provides on-device identity embeddings and anonymous person IDs;
- face embeddings are clustered locally into unique anonymous groups, then shown in a private tagging screen;
- a derived SQLite episode index groups media by time, location, and anonymous person overlap after preprocessing;
- vectors are stored and searched by the native Rust `turbovec` crate;
- the vector index is TurboQuant 4-bit, arm64-only, with stable external IDs;
- LiteRT/LiteRT-LM native model runtimes are linked, while model weights are installed separately;
- Gemma provisioning runs in its own WorkManager foreground worker with resumable `.part` files, so gallery indexing can start as soon as its indexing models are ready;
- gallery preparation resumes after process death and screen-off, with progress persisted for the UI;
- Settings exposes independent, guarded rebuilds for visual vectors, OCR, locations, faces, and photo episodes, with per-stage progress and ETA;
- search stays locked until the top frequent face groups have been reviewed; after
  three important groups are named, the remaining groups may be skipped and can
  be revisited from Settings;
- visual, OCR, and face inference use all available CPU workers with independent
  model instances and serialized SQLite/native-index commits;
- optional notification context captures only future, relevant travel/receipt/delivery/appointment alerts into a separate Android-Keystore-encrypted local store;
- Gemma answers stay natural and concise (2–3 sentences), never expose internal
  evidence IDs/reasoning/task echoes, and never add synthetic browse filler;
- Context Picker's selected records are prompt-compacted for repeated
  structured facts; QP/language remains on CPU while only Gemma vision uses GPU
  with a CPU-vision initialization fallback;
- all shipped arm64 ELF load segments and APK native-library entries are 16 KiB aligned;
- no Python, PyTorch, Paddle, ONNX Runtime, cloud retrieval, or cloud answer
  service is part of the APK; location enrichment tries Android's geocoder
  first, then an optional rate-limited OpenStreetMap fallback. The fallback
  receives only coordinates rounded to roughly 110 m during indexing—never
  photo pixels, OCR, faces, filenames, or queries—and successful place names
  are cached locally.

Preparation is deliberately staged: MediaStore scan, capture/GPS enrichment
and cached reverse geocoding, visual embeddings, bundled ML Kit OCR, FaceNet-512
vectors, anonymous face clustering, and persisted episode construction.
The home screen shows the active stage and a smooth waiting animation. After
clustering, it shows face-crop thumbnails and keeps search locked until the
important frequent groups have been reviewed. Once three groups are named, the
remaining groups may be skipped; they can be revisited later from Settings.
Names stay local and are used as metadata during later searches.

Android 10+ protects photo GPS separately from ordinary gallery access. Ask
Galaxy requests `ACCESS_MEDIA_LOCATION`, opens
`MediaStore.setRequireOriginal()` photo URIs, and leaves location rows pending
if consent is unavailable; it never treats redacted EXIF as proof that a photo
has no GPS. Settings can rebuild only photo locations without changing OCR,
vectors, faces, clusters, or episodes.

## Build

The workspace uses the installed Android SDK and NDK:

```bash
export JAVA_HOME=/home/ravi/AG/Android_SDK/jdk
export ANDROID_HOME=/home/ravi/AG/Android_SDK/android-sdk
export ANDROID_NDK_HOME=/home/ravi/AG/Android_SDK/android-sdk/ndk/27.2.12479018
export GRADLE_USER_HOME="$PWD/.gradle-user"

/home/ravi/.gradle/wrapper/dists/gradle-9.2.1-bin/2t0n5ozlw9xmuyvbp7dnzaxug/gradle-9.2.1/bin/gradle \
  :app:assembleDebug --stacktrace
```

AGP requires Gradle 8.13 or newer; the command above uses the verified local
9.2.1 distribution. The first native build fetches the pinned `turbovec`
revision and its Rust dependencies. The resulting APK contains only
`arm64-v8a` native code.

To verify the native alignment after a build:

```bash
unzip -q app/build/outputs/apk/debug/app-debug.apk 'lib/arm64-v8a/*.so' -d /tmp/ask-galaxy-libs
for f in /tmp/ask-galaxy-libs/lib/arm64-v8a/*.so; do readelf -lW "$f" | rg 'LOAD|Align'; done
/home/ravi/AG/Android_SDK/android-sdk/build-tools/35.0.0/zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

## Current UI

1. Open the app and grant gallery access.
2. Ask Galaxy silently installs available model packages and scans/indexes the gallery in the background.
3. While preparing, the home screen shows scan, location, visual, OCR, face,
   clustering, or episode-index progress; detailed model/index state lives
   under Settings.
4. After clustering, review the face groups. On your own group, enter your
   normal name and select **This is me**; Ask Galaxy then resolves
   `I`/`me`/`my`/`mine`/`myself` to that one local identity when the wording
   refers to your presence. Name other important groups; after three names,
   you may skip the remaining groups and revisit them later from Settings.

The app uses the direct `litert-community/SigLIP2-base-patch16-224` FP16 image artifact instead of converting the much larger combined checkpoint. Its aligned text tower is converted separately from the pinned text-only `m-toman/siglip2-base-patch16-224-text` source, then weight-only INT8 quantized to a 463 MiB LiteRT model; the exact tokenizer JSON is bundled alongside it. Both towers expose normalized 768-D vectors. Face identity remains required through the pinned FaceNet-512 TFLite encoder (`160x160x3` FLOAT32 -> `512` FLOAT32), paired with YuNet landmarks for alignment and local person IDs. ML Kit OCR is bundled in the APK; the downloader installs only the separately provisioned SigLIP, YuNet, FaceNet, and Gemma artifacts. The image/text/face contracts and low-memory conversion path are documented in [ARCHITECTURE.md](ARCHITECTURE.md).

Gemma 4 E4B is the only query planner. Every plan starts with one
`query_category` in `{doc, scenary, person, location, time}`, then emits the
bracketed execution language with `+`, `,`, `-`, `&&`, `SORT_DATE`, and
`SORT_LOC`. The AST executes directly against TurboQuant plus indexed
OCR/person/date/location/MIME metadata. MIME values are exactly `photos` or
`videos`; document-like concepts remain semantic/OCR content. Native semantic
matches use an inclusive `0.10` confidence cutoff. If a positive semantic
branch and its scoped metadata search are both empty at that cutoff, retrieval
publishes up to 200 nearest neighbors from the already-applied
category/person/date/location/MIME scope. Negative branches never use this
fallback. Gemma may emit `from_date` or `to_date` only when the user explicitly
supplies temporal intent; present tense, spending questions, and missing dates
must never be narrowed to today. An explicit single calendar day is always
compiled as a closed interval with equal `from_date` and `to_date`; a missing
endpoint is rejected and repaired before retrieval.
As soon as planning finishes, the effective C-like execution spec appears
directly below the search bar while retrieval continues; a neighboring timing
chip updates across later phases. The UI immediately displays up to the top
200 overall query matches in a virtualized, expandable, vertically scrollable grid without
exposing filenames. Result order is the executor's overall query relevance,
not a newest-first rewrite: scenery favors semantic score, documents put
complete OCR conjunctions first, and person/location retain authoritative
metadata scopes. Capture date breaks equal-score ties. OCR preprocessing also
persists one category per media row:
an image with any non-empty OCR is `doc`; every other image/video is `scenary`.
The QP category is a hard search-index scope, so the result grid itself contains
only eligible records. A query-aware Context Picker then chooses at most 8
records from those scoped results. `doc`, `person`, `location`, and `time`
stay text-only. Only `scenary` uses persisted SigLIP diversity and may attach
up to four images downscaled to a 512 px longest edge. Episode coverage and
relevance minimize duplicate context.
Gemma 4 stays resident, but planner and answer use
clean conversations: the planner session is closed immediately after planning,
and a separate answer system-prefix prefill may be warmed during retrieval;
neither role ever reuses the other's execution turn. If the user opts in under
Settings and grants Android notification access, relevant future travel, receipt,
delivery, and appointment alerts are searched separately only when the planner asks
for personal context. OTP/PIN/password and bank-security alerts are skipped, no
notification history is imported, and only bounded query-relevant facts enter
the private answer context. The Context Picker's 8 records are never shown as a
replacement for the full result grid.

## v0.2 query and answer contract

Ask Galaxy v0.2 intentionally uses a simple, inspectable hybrid path:

```text
user question
  -> Gemma 4 E4B QP -> required query_category + canonical C-like execution AST
  -> recursive set execution over category/person/MIME/date/location/semantic/OCR predicates
  -> prewarmed SigLIP text encoder -> semantic search over image embeddings
  -> SQLite matching over OCR, tagged people, and indexed place metadata
  -> optional keyword/structured matching over encrypted notification facts when requested
  -> category-scoped matches -> immediate overall-relevance virtualized result grid
  -> indexed episode membership join
  -> Context Picker -> at most 8 eligible text/metadata records; scenery alone uses persisted SigLIP embedding diversity
  -> doc/person/location/time text-only answers; scenery may attach up to 4 images downscaled to a 512 px longest edge
  -> scoped Gemma 4 E4B answer and follow-up generation in a clean answer conversation
  -> 2–3 sentence answer without internal evidence labels + contextual follow-ups
```

The v0.2 boundaries are deliberate:

- OCR is extracted by bundled ML Kit Text Recognition v2 and matched by a
  dedicated planner-authored `ocr` predicate; OCR text does not have its own
  embedding index. In `doc` plans, one conceptual SigLIP `semantic` predicate
  is fused with one short OCR conjunction using `+`. Canonical syntax such as
  `[ocr == {Ravi} && {passport}]` requires every word in the same photo OCR.
  A complete conjunction is a perfect match and always ranks above
  semantic-only document results in the UI and answer context.
- Image semantics come from the SigLIP text encoder searching the image vector index.
- Notification facts use local keyword and structured-field scoring; they are not sent through the SigLIP text encoder.
- Gemma 4 E4B is the sole planner. It gets one repair turn for an invalid
  expression; an unavailable model or a second invalid expression fails clearly
  instead of invoking a deterministic fallback. Its required `query_category`
  scopes indexed search and routes answer evidence: person, time, location,
  document/OCR, or visual scene. Structured fields can only be spelling-corrected or canonically
  formatted. `semantic` is rewritten as one conceptual retrieval phrase, while
  `ocr` is planner-authored from likely printed words. Every `doc` plan fuses
  those two branches with `+`; every braced OCR word is AND-required. Broad
  document aggregates use only likely co-occurring words such as `total` and
  `amount`, never mutually exclusive receipt/bill/invoice alternatives. A self
  identity document uses the actual tagged name plus the document word, never
  `person`, `self`, `me`, or `my`.
  The Context Picker uses
  at most 8 text records, and only scenery may attach up to 4 downscaled images.
  Planner and answer
  use clean Gemma conversations; the planner's stable prefill may reuse KV state, but its execution
  turn is never carried into answer generation.
- Personal context is opt-in, future-notification-only, encrypted locally, and
  included only when the QP asks for it.

This is the stable v1 baseline. OCR or notification semantic embeddings can be added later without changing the user-facing answer and source model.
