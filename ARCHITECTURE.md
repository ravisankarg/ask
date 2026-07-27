# Ask Galaxy native architecture

## Runtime rule

The Android UI and scheduling layer may be Kotlin, but model inference and vector operations must be native:

```text
Kotlin UI / MediaStore / SQLite / work scheduling
                    |
                   JNI
          +---------+----------+
          |                    |
  LiteRT / LiteRT-LM C++   Rust turbovec
  model inference          TurboQuant 4-bit index
```

There is no Python, Transformers, PyTorch, Paddle, ONNX Runtime, or hosted vector database in the APK. Build-time conversion scripts may use those tools, but the shipped app may not.
The only indexing network enrichment is reverse geocoding. Android's system
Geocoder is primary; if it returns no result, an optional OpenStreetMap
Nominatim fallback receives coordinates rounded to roughly 110 m. The fallback
is serialized below one request per second, is switchable in Settings, and
never receives photo pixels, OCR, faces, filenames, or query text. Successful
readable places are cached durably and searched locally afterwards.
On Android 10+, photo EXIF is opened only after `ACCESS_MEDIA_LOCATION`
consent through `MediaStore.setRequireOriginal()`; a redacted ordinary URI is
never persisted as an authoritative no-GPS result.

Gallery preparation and Gemma provisioning are separate WorkManager
`CoroutineWorker`s promoted to `dataSync` foreground services. They hold a
partial wake lock while actively transferring or indexing, persist model
downloads as `*.part` files, rename only verified files into place, and record
every indexing batch in SQLite. This lets visual/OCR/face/location/episode
indexing begin while the multi-gigabyte Gemma artifact is still downloading.
The Activity is only a viewer of this state; closing it does not cancel work.

CPU-bound visual, OCR, and face extraction use one isolated interpreter per
available CPU worker. Model state and buffers are never shared across workers;
SQLite and native vector writes are serialized, and progress reporting is
monotonic. The large Gemma session remains single-session to keep memory
pressure predictable. Settings can clear/rebuild each derived index separately;
face rebuilds also refresh dependent episodes.

All arm64 native libraries are linked with 16 KiB maximum/common page sizes,
and the APK is checked with `zipalign -P 16`.

## Fixed vector contracts

The retrieval spaces get separate `turbovec::IdMapIndex` files because dimensions
and model versions must never be mixed. Face identity vectors stay in encrypted
SQLite instead of the retrieval index so the clustering pass can be rebuilt
without exposing identity features to search.

| Space | Dimension | Storage | Purpose |
| --- | ---: | --- | --- |
| SigLIP2 image | 768 | TurboQuant 4-bit | photo embeddings for local image retrieval |
| SigLIP2 text | 768 | transient query vector | text queries aligned with the image space |
| Face embedding | 512 | encrypted SQLite blobs | local face clustering and anonymous identity groups |

The model emits a floating-point vector. TurboQuant performs native 4-bit compression during index insertion; query vectors remain floating point for search. Index files are persisted atomically as `.tvim` files.

## Model artifact plan

| Model | Android artifact | Native role |
| --- | --- | --- |
| `litert-community/SigLIP2-base-patch16-224` | direct LiteRT FP16 image encoder | normalized 768-D image embeddings |
| `m-toman/siglip2-base-patch16-224-text` | separately generated LiteRT weight-only INT8 text encoder | normalized 768-D query embeddings |
| same pinned text source | `tokenizer.json` through Rust `tokenizers` | exact 64-token, 256k-vocabulary BPE IDs |
| ML Kit Text Recognition v2 Latin `16.0.1` | bundled on-device model | confidence-gated OCR text stored in searchable SQLite metadata |
| YuNet + FaceNet-512 | LiteRT exports | face boxes, 512-D embeddings, anonymous cluster IDs |
| `google/gemma-4-E4B-it` | quantized LiteRT-LM `.litertlm` | resident query planner, scoped answer, and follow-ups |

The direct SigLIP2 image artifact exposes a `[1,3,224,224]` NCHW FLOAT32 input
and a `[1,768]` FLOAT32 output. The Kotlin indexer applies the SigLIP `.5/.5`
RGB normalization, writes RGB planes in NCHW order, L2-normalizes the output,
and writes the vector under the stable MediaStore ID. Video rows use their
first keyframe as the initial visual representation.

`ModelCatalog` pins the direct LiteRT Community SigLIP2 download by revision and
checksum. The text-only source is pinned separately at revision
`7deedba28e0edb4fa9c22c889509447ddce7b23c`; `tools/convert_siglip2_text_litert.py`
loads only its text-tower tensors, exports a temporary FLOAT32 graph, and
applies weight-only INT8 quantization. The validated text contract is
`INT32 [1,64] -> FLOAT32 [1,768]`. The Kotlin boundary L2-normalizes the text
output before searching.

Face identity is mandatory. YuNet supplies the face box and five landmarks;
the landmark-alignment stage passes a square crop to `FaceNetEncoder`. The
pinned FaceNet artifact accepts `[1,160,160,3]` FLOAT32 RGB values normalized to
`[-1,1]` and emits `[1,512]` FLOAT32 identity features. `FaceNetEncoder` L2
normalizes those features before nearest-face matching or anonymous
`person_cluster_id` assignment. Face identity is kept in a separate 512-D
vector space from the 768-D SigLIP image space. After all pending face vectors
are written, an on-device cosine-centroid clustering pass creates stable
anonymous `person-*` groups. Existing group labels are reused when a refreshed
centroid matches, while the raw vectors remain encrypted by an Android Keystore
AES-GCM key. One additive `is_self` marker may identify exactly one normally
named cluster as the user. It survives matching rebuilds and lets the planner
resolve presence-oriented `I`/`me`/`my` references without treating ordinary
document phrases such as “my passport” or “I spent” as face constraints.

Gemma 4 E4B through LiteRT-LM is the only planner and answer path. No separate
GGUF planner is downloaded or loaded; SigLIP, OCR, and faces remain independent
indexing/retrieval components.

OCR preserves input aspect ratio. Ordinary photos use EXIF-oriented pixels;
very tall or wide inputs are region-decoded into overlapping 2048px strips.
ML Kit line confidence and a document-structure gate reject isolated short
hallucinations while retaining prices and punctuation inside credible
documents. Database v11 persists an engine/preprocessing `ocr_signature`.
`OcrReindexWorker` updates only stale image OCR rows and their derived
`doc`/`scenary` class; it never rebuilds locations, visual vectors, faces,
clusters, or episodes.

Database v12 repairs the orthogonal photo-location contract. It invalidates
only image GPS/place fields that older builds read through redacted MediaStore
URIs. `LocationReindexWorker` reopens original EXIF, rejects the `0°,0°`
missing-GPS sentinel, reverse geocodes/cache readable places, and leaves OCR,
vectors, faces, clusters, and episodes untouched.

## Search path

```text
user query
  -> Gemma 4 E4B emits one query_category plus the C-like execution AST
  -> recursive `,` / `+` / `-` / `&&` execution with postfix date/place sorting
  -> Rust BPE tokenizer emits fixed 64 INT32 IDs
  -> SigLIP2 text encoder emits a normalized 768-D query vector
  -> persisted OCR-derived doc/scenary or metadata class creates a hard allowlist
  -> resident TurboQuant searches the aligned image index (up to 512 candidates,
     accepting cosine scores >= 0.10 when confident matches exist; otherwise a
     positive-only nearest-neighbor fallback publishes up to 200 candidates
     inside the accumulated category/person/date/location/MIME allowlist)
  -> SQLite requires every planner-authored braced OCR word in the same row,
     plus exact person, MIME, capture-date, and readable-place branches
  -> doc retrieval places complete OCR conjunctions above semantic-only fallback
  -> category-aware overall relevance is preserved into the virtualized UI:
     scenery semantic score first, doc OCR tier first, person/location exact
     metadata scope first, then capture-date tie-breaking
  -> optional encrypted personal-context matcher runs only for a context scope
  -> persisted episode membership supplies cross-event diversity at query time
  -> Context Picker chooses at most 8 eligible records without decoding images;
     scenery alone uses persisted SigLIP embedding diversity
  -> doc/person/location/time use text only; scenery may attach up to 4
     images downscaled to a 512 px longest edge
  -> a clean Gemma 4 answer conversation writes the answer and follow-ups
```

The Gemma-emitted category controls search eligibility, answer metadata fields,
and whether bounded scenery inputs are attached. Planner and answer conversations share
the resident Gemma engine, but not the planner turn:
only the stable planner prefill is reused for query planning, which prevents
planner syntax and routing language from leaking into the natural-language answer.
The frozen QP and language graph stay on CPU. Only Gemma's image
encoder/adapter uses the LiteRT GPU backend, with a CPU-vision initialization
fallback for unsupported devices; this keeps the individual visual inputs below the
Samsung CPU-only process-memory spike. The answer prompt groups identical
structured rows and carries only category-selected metadata/OCR, reducing
prefill without removing any of the Context Picker's selected records.
The planner date gate rejects `from_date`/`to_date` unless the original query
contains explicit temporal intent; it requests one Gemma repair rather than
silently treating an undated query as today. A single exact day requires both
inclusive endpoints to equal that day, preventing a lone `from_date` from
widening retrieval into every later date.

The image vector index is built locally. While the search field is available, the
resident TurboQuant handle, SigLIP text tower, and Gemma 4 planner preface are
warmed off the UI thread. The text tower is released after retrieval; the persisted
index is never rebuilt or deleted by search. Search remains local and never silently
substitutes a cloud model.

The face-tagging path is separate from answer generation:

```text
FaceNet-512 vectors
  -> cosine centroid clustering
  -> anonymous person-* groups
  -> representative thumbnails + optional user labels
  -> label-aware local metadata search
```

Optional personal context is deliberately a separate path from gallery indexing:

```text
user enables Personal context + Android notification access
  -> future notifications only
  -> reject ongoing, OTP/PIN/password, and bank-security alerts
  -> classify travel/receipt/delivery/appointment signals
  -> encrypt payload with a separate Android Keystore AES-GCM key
  -> bounded local fact store with dedupe and 180-day retention
  -> query-aware in-memory matching (no SQL text search)
  -> relevant context records join the Gemma evidence prompt
```

The answer model may use bounded internal source labels to keep its multimodal
context aligned, but `AnswerTextSanitizer` removes those labels from user-visible
prose, model-reasoning tags, and task echoes. The UI always presents the full
bounded search browser rather than the private top-8 Context Picker set.
Answers are limited to 2–3 natural sentences without synthetic browse filler,
followed by useful contextual queries. Notification access is optional, can be
disabled independently, and the Settings screen can clear the personal-context
store without touching the gallery index.

Preparation is gated by a persisted flow state:

```text
MediaStore scan + metadata
  -> capture date/GPS extraction + cached online reverse geocoding
  -> SigLIP image embeddings
  -> bundled ML Kit OCR with aspect-preserving long-image tiles
  -> YuNet + FaceNet-512 embeddings
  -> anonymous cosine clustering only when new/unassigned faces invalidate it
  -> persisted episodes from time + readable place + anonymous people
  -> face-crop tagging UI
  -> READY: hybrid search enabled
```
