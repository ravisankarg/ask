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

The preparation owner is a WorkManager `CoroutineWorker` promoted to a
`dataSync` foreground service. It holds a partial wake lock while actively
transferring or indexing, persists model downloads as `*.part` files, renames
only verified files into place, and records every indexing batch in SQLite.
The Activity is only a viewer of this state; closing it does not cancel work.

CPU-bound OCR and face extraction use a conservative pool of up to two worker
threads, with one independent LiteRT interpreter per worker. Interpreter input
and output buffers are never shared across workers; SQLite writes are serialized
and progress reporting is monotonic. Each interpreter also uses LiteRT/XNNPACK
internal threads, while the large Gemma session remains single-session to keep
memory pressure predictable.

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
| PP-OCRv5 mobile | LiteRT detector + recognizer | OCR text stored in searchable SQLite metadata |
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
AES-GCM key.

Gemma 4 E4B through LiteRT-LM is the only planner and answer path. No separate
GGUF planner is downloaded or loaded; SigLIP, OCR, and faces remain independent
indexing/retrieval components.

## Search path

```text
user query
  -> deterministic or Gemma QP emits the same C-like execution AST
  -> recursive `,` / `+` / `-` / `&&` execution with postfix date/place sorting
  -> Rust BPE tokenizer emits fixed 64 INT32 IDs
  -> SigLIP2 text encoder emits a normalized 768-D query vector
  -> resident TurboQuant searches the aligned image index (up to 100)
  -> SQLite executes indexed OCR, person, MIME, capture-date, and readable-place branches
  -> the full bounded match set is returned newest-first to the virtualized UI
  -> optional encrypted personal-context matcher runs only for a context scope
  -> persisted episode membership supplies cross-event diversity at query time
  -> Context Picker chooses at most 16 images across relevance, episode, visual,
     OCR, person, place, metadata, and timeline novelty
  -> one EXIF-correct 4x4 visual board joins those images with named face anchors
  -> a clean Gemma 4 answer conversation writes the answer and follow-ups
```

The answer evidence scope controls which metadata/OCR/personal-context fields
are authoritative; it does not suppress the bounded visual board. Planner and
answer conversations share the resident Gemma engine, but not the planner turn:
only the stable planner prefill is reused for query planning, which prevents
planner syntax and routing language from leaking into the natural-language answer.

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
prose. The UI always presents the full bounded search browser rather than the
private top-16 Context Picker set. Answers are limited to 2–3 natural sentences,
followed by useful contextual queries. Notification access is optional, can be
disabled independently, and the Settings screen can clear the personal-context
store without touching the gallery index.

Preparation is gated by a persisted flow state:

```text
MediaStore scan + metadata
  -> capture date/GPS extraction + cached online reverse geocoding
  -> SigLIP image embeddings
  -> PP-OCRv5 text extraction
  -> YuNet + FaceNet-512 embeddings
  -> anonymous cosine clustering only when new/unassigned faces invalidate it
  -> persisted episodes from time + readable place + anonymous people
  -> face-crop tagging UI
  -> READY: hybrid search enabled
```
