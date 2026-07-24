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

The current milestone is an automatic, background-safe foundation:

- gallery image/video metadata is indexed from `MediaStore` into SQLite;
- capture dates, raw GPS, and online reverse-geocoded place names are persisted in the search index;
- bounded image and first-video-frame decoding is implemented for background indexing;
- PP-OCRv5 text is extracted and stored per media item for local OCR matching;
- the installed direct SigLIP2 LiteRT artifact can produce normalized 768-D embeddings;
- the aligned SigLIP2 text tower and pinned 256k BPE tokenizer turn queries into the same 768-D space;
- the required YuNet + FaceNet-512 face path provides on-device identity embeddings and anonymous person IDs;
- face embeddings are clustered locally into unique anonymous groups, then shown in a private tagging screen;
- a derived SQLite episode index groups media by time, location, and anonymous person overlap after preprocessing;
- vectors are stored and searched by the native Rust `turbovec` crate;
- the vector index is TurboQuant 4-bit, arm64-only, with stable external IDs;
- LiteRT/LiteRT-LM native model runtimes are linked, while model weights are installed separately;
- model provisioning is owned by a WorkManager foreground worker with resumable `.part` files;
- gallery preparation resumes after process death and screen-off, with progress persisted for the UI;
- search stays locked until the top frequent face groups have been reviewed; after
  three important groups are named, the remaining groups may be skipped and can
  be revisited from Settings;
- OCR and face inference use bounded parallel LiteRT workers with independent interpreter buffers;
- optional notification context captures only future, relevant travel/receipt/delivery/appointment alerts into a separate Android-Keystore-encrypted local store;
- Gemma answers stay natural and concise (2–3 sentences), never expose internal
  evidence IDs, and suggest useful contextual follow-up queries;
- all shipped arm64 ELF load segments and APK native-library entries are 16 KiB aligned;
- no Python, PyTorch, Paddle, ONNX Runtime, cloud retrieval, or cloud answer
  service is part of the APK; location enrichment tries Android's geocoder
  first, then an optional rate-limited OpenStreetMap fallback. The fallback
  receives only coordinates rounded to roughly 110 m during indexing—never
  photo pixels, OCR, faces, filenames, or queries—and successful place names
  are cached locally.

Preparation is deliberately staged: MediaStore scan, capture/GPS enrichment
and cached reverse geocoding, visual embeddings, PP-OCRv5 text, FaceNet-512
vectors, anonymous face clustering, and persisted episode construction.
The home screen shows the active stage and a smooth waiting animation. After
clustering, it shows face-crop thumbnails and keeps search locked until the
important frequent groups have been reviewed. Once three groups are named, the
remaining groups may be skipped; they can be revisited later from Settings.
Names stay local and are used as metadata during later searches.

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
4. After clustering, review the displayed frequent face groups. Name the important
   groups; after three names, you may skip the remaining groups and revisit them later
   from Settings.

The app uses the direct `litert-community/SigLIP2-base-patch16-224` FP16 image artifact instead of converting the much larger combined checkpoint. Its aligned text tower is converted separately from the pinned text-only `m-toman/siglip2-base-patch16-224-text` source, then weight-only INT8 quantized to a 463 MiB LiteRT model; the exact tokenizer JSON is bundled alongside it. Both towers expose normalized 768-D vectors. Face identity remains required through the pinned FaceNet-512 TFLite encoder (`160x160x3` FLOAT32 -> `512` FLOAT32), paired with YuNet landmarks for alignment and local person IDs. The downloader installs the pinned public OCR, YuNet, FaceNet, and Gemma artifacts automatically. The image/text/face contracts and low-memory conversion path are documented in [ARCHITECTURE.md](ARCHITECTURE.md).

Both deterministic and Gemma query processors emit the same bracketed execution
language with `+`, `,`, `-`, `&&`, `SORT_DATE`, and `SORT_LOC`. The AST executes
directly against TurboQuant plus indexed OCR/person/date/location/MIME metadata.
As soon as planning finishes, the effective C-like execution spec appears
directly below the search bar while retrieval continues; a neighboring timing
chip updates across later phases. The UI immediately displays up to the newest
200 matches in a virtualized, expandable, vertically scrollable grid without
exposing filenames. A query-aware Context
Picker then chooses at most 16 answer images: it preserves strong relevance,
selects the best retrieved member from distinct persisted episodes, and balances
visual, OCR, place, person, metadata, and timeline novelty before allowing
duplicate episode views. The one multimodal board also contains named face
anchors; only query-needed OCR/metadata fields are joined, with readable location
always included when available. Gemma 4 stays resident, but planner and answer use
clean conversations: only the stable planner prefill is reused, never the
planner's execution turn. If the user opts in under
Settings and grants Android notification access, relevant future travel, receipt,
delivery, and appointment alerts are searched separately only when the planner asks
for personal context. OTP/PIN/password and bank-security alerts are skipped, no
notification history is imported, and only bounded query-relevant facts enter
the private answer context. The Context Picker's 16 images are never shown as a
replacement for the full result grid.

## v1 final query and answer contract

Ask Galaxy v1 intentionally uses a simple, inspectable hybrid path:

```text
user question
  -> deterministic or Gemma QP -> canonical C-like execution AST
  -> recursive set execution over person/MIME/date/location/semantic predicates
  -> prewarmed SigLIP text encoder -> semantic search over image embeddings
  -> SQLite matching over OCR, tagged people, and indexed place metadata
  -> optional keyword/structured matching over encrypted notification facts when requested
  -> all bounded matches -> immediate newest-first virtualized result grid
  -> indexed episode membership join
  -> Context Picker -> at most 16 cross-episode, multimodally diverse images
  -> one EXIF-correct 4x4 visual board plus face crops and query-needed metadata
  -> scoped Gemma 4 E4B answer and follow-up generation in a clean answer conversation
  -> 2–3 sentence answer without internal evidence labels + contextual follow-ups
```

The v1 boundaries are deliberate:

- OCR is extracted by PP-OCRv5 and matched through the semantic predicate's SQLite branch; OCR text does not have its own embedding index.
- Image semantics come from the SigLIP text encoder searching the image vector index.
- Notification facts use local keyword and structured-field scoring; they are not sent through the SigLIP text encoder.
- Gemma 4 E4B plans the same formal execution AST as the deterministic path and an answer-only evidence scope. Metadata/date/location/who
  questions identify the requested metadata fields (people, capture time, or location),
  written-text questions identify OCR, and visual assertions/negations use at most 16 diverse
  image representatives across indexed episodes. Those fields scope the authoritative text context; a bounded visual
  board is still attached whenever selected gallery images decode successfully. Planner and answer
  use clean Gemma conversations; the planner's stable prefill may reuse KV state, but its execution
  turn is never carried into answer generation.
- Personal context is opt-in, future-notification-only, encrypted locally, and
  included only when the QP asks for it.

This is the stable v1 baseline. OCR or notification semantic embeddings can be added later without changing the user-facing answer and source model.
