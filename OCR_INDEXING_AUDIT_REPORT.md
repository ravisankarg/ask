# OCR indexing quality audit

Date: 2026-07-25
Scope: indexing/OCR only. The frozen Gemma 4 E4B query planner was not changed.

## Decision

The prior PP-OCRv5 path was not good enough to keep.

Correcting its input resize would have fixed one major defect, but not the
whole quality problem: the app stretched every image to a square detector
input, reduced long screenshots to unreadable thumbnails, decoded CTC output
without recognition confidence, and classified every non-empty hallucination
as a document. The device database confirmed both high false-positive and
high false-negative rates.

Ask Galaxy now uses the **bundled ML Kit Text Recognition v2 Latin model
(`16.0.1`)** as the primary offline OCR engine. Bundling was chosen over the
Google Play services variant so OCR is available immediately, stays offline,
and does not depend on a later model download.

This follows Google's current Android guidance: the bundled artifact is
`com.google.mlkit:text-recognition:16.0.1`, works immediately after install,
and adds about 4 MB per script/architecture. Google also recommends roughly
16×16 pixels per character and notes that a full document may need around
720×1280 input pixels. See [ML Kit Text Recognition v2 for
Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
and [ML Kit model installation
paths](https://developers.google.com/ml-kit/tips/installation-paths).

## Baseline evidence

The pre-migration device index contained:

| Measure | Baseline |
| --- | ---: |
| All media rows | 16,197 |
| Images | 11,942 |
| Videos | 4,255 |
| Rows with any OCR text | 8,411 (51.93%) |
| Images classified as `doc` | 6,487 (54.32%) |
| Non-empty OCR rows with only 1–4 characters | 2,990 |
| Non-empty OCR rows with only 1–3 characters | 2,471 |

Visual review confirmed natural scenery rows whose entire OCR result was a
one- or two-character hallucination. It also found a 1440×11221 screenshot
with abundant readable text whose persisted OCR result was empty.

## Root causes in the retired implementation

1. The detector input was always resized directly to 640×640, destroying the
   source aspect ratio.
2. Gallery inputs were first bounded by their longest dimension. A very long
   screenshot therefore lost the pixels required for character recognition.
3. Detector boxes were mapped back through the distorted square image.
4. The hand-written CTC decoder retained argmax text without a recognition
   confidence threshold.
5. Any non-empty output immediately changed an image to `doc`, so tiny
   hallucinations corrupted the category index.
6. Videos were OCR processed even though only images participate in the
   OCR-derived `doc` contract.
7. The database stored only an `ocr_indexed` Boolean, so an OCR model or
   preprocessing change had no isolated versioned migration path.

## New module contract

```text
image row whose OCR signature is stale
  -> normal photo: aspect-preserving EXIF-oriented decode
  -> very tall/wide image: overlapping 2048px region-decoded strips
  -> bundled ML Kit Latin recognizer
  -> line confidence + document-structure quality gate
  -> overlap de-duplication without collapsing repeated document values
  -> atomic SQLite update:
       ocr_text + doc/scenary + indexed flag + OCR signature
```

Key implementation properties:

- `OcrIndexContract.SIGNATURE` versions the engine, input preparation, and
  quality gate.
- Database v11 adds `ocr_signature`; only mismatched image rows become pending.
- Existing OCR remains searchable until its replacement row commits.
- An inference failure stays pending instead of becoming a permanently blank
  result.
- Isolated short noise is rejected; short prices and punctuation are retained
  once the image has credible document structure.
- Long images are decoded by region, keeping memory bounded and characters
  readable.
- OCR indexing uses at most two independent recognizers and serializes SQLite
  writes.
- `OcrReindexWorker` invokes only OCR. It does not scan MediaStore or invoke
  location, visual, face, clustering, or episode indexing.
- Settings exposes **Rebuild text index only** and shows the remaining count.
- The three PP-OCR model files are no longer required downloads.

## Device A/B corpus

No recognized personal text was copied into logs or this report; only lengths,
line counts, and confidence were recorded.

| Sample class | Old chars | New chars | New lines | Mean confidence | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Very long screenshot | 0 | 2,731 | 151 | 0.848 | major false negative fixed |
| Dense document A | 413 | 790 | 61 | 0.832 | coverage improved |
| Dense document B | 290 | 426 | 52 | 0.821 | coverage improved |
| Dense document C | 446 | 795 | 40 | 0.810 | coverage improved |
| Compact screenshot/document | 264 | 282 | 30 | 0.878 | retained/improved |
| Identity-style document A | 238 | 305 | 20 | 0.754 | coverage improved |
| Identity-style document B | 253 | 422 | 24 | 0.835 | coverage improved |
| Identity-style document C | 321 | 385 | 25 | 0.743 | coverage improved |
| Natural scene noise A | 2 | 0 | 0 | — | false positive removed |
| Natural scene noise B | 1 | 0 | 0 | — | false positive removed |
| Natural scene noise C | 3 | 0 | 0 | — | false positive removed |
| Natural scene noise D | 2 | 0 | 0 | — | false positive removed |
| Small real equipment label | 3 (incorrect) | 4 | 1 | 0.807 | real text retained |
| Text-bearing road tag | 2 (incorrect) | 101 | 7 | 0.766 | major false negative fixed |

One sampled HEIC row changed from 312 characters of linguistically low-quality
legacy output to blank ML Kit output. It remains a review item for a future
script/codec-stratified corpus; it is not evidence for retaining the old
uncalibrated decoder.

## Verification

- JVM OCR quality-gate tests: pass.
- Complete JVM suite: pass.
- Android lint: completes with no errors (pre-existing compatibility/dependency
  warnings remain).
- Debug APK and instrumentation APK: build and install successfully.
- Device OCR quality audit: `OK (4 tests)`.
- Frozen QP source hashes are unchanged.
- Full OCR-only signature migration: `SUCCESS`, 11,942/11,942 images current,
  zero pending rows, and zero runtime skips.
- Final accepted OCR corpus: 2,576 document images (21.57%), zero videos with
  text, zero 1–3-character OCR rows, 330.54 mean characters per non-empty row,
  and zero `content_class` mismatches.
- OCR-only isolation assertion: pass. The before/after SHA-256 digests are
  identical for every non-OCR media column, face embeddings, face clusters,
  location cache, episodes, episode memberships, and the TurboQuant visual
  vector file.

Compared with the old index, the document rate fell from 54.32% to 21.57%,
while the average non-empty OCR payload rose from 38.5 to 330.54 characters.
Together with the representative A/B corpus, this is strong evidence that the
new index is retaining real documents instead of storing mostly tiny scene
hallucinations. It is not a universal OCR claim: Latin-script documents are
the accepted production scope, while HEIC/script-stratified evaluation remains
the gate for adding Devanagari or other script recognizers.
