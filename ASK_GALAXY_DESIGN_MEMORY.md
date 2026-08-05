# Ask Galaxy design memory

Last reviewed: 2026-07-25

This is the concise architectural memory for future Ask Galaxy sessions. The
full module contracts, commands, live query matrix, and known issues are in
[`ASK_GALAXY_MODULE_REQUIREMENTS.md`](ASK_GALAXY_MODULE_REQUIREMENTS.md).

## Product contract

Ask Galaxy is a private, local-first gallery assistant. It must answer natural
questions about images, people, activities, dates, places, OCR, and trips
without exposing internal search machinery. Quality
comes from joining visual pixels, named faces, capture metadata, readable/raw
locations, and OCR in one grounded answer.

## End-to-end architecture

```text
WorkManager preparation
  -> exact model/artifact verification and resumable downloads
  -> MediaStore scan and incremental SQLite rows
  -> capture/GPS extraction and cached online reverse geocoding
  -> SigLIP2 image/frame vectors in TurboQuant
  -> bundled ML Kit OCR text in SQLite
  -> YuNet + FaceNet-512 encrypted face data and clustering
  -> persisted time/location/person episode index
  -> full virtualized face review/tagging snapshot
  -> search-ready state

Search field visible
  -> warm Gemma 4 CPU-language engine, SigLIP text encoder, and native index
  -> Gemma-only query_category + canonical execution AST
  -> validate spelling, structured values, temporal intent, and semantic content
  -> show the accepted QP output and planning time below the search field
  -> recursively execute hard category/person/date/location/MIME sets
  -> search TurboQuant + SQLite OCR/metadata inside the accumulated hard scope
  -> keep semantic cosine >= 0.10; use scoped positive fallback only if empty
  -> publish up to 200 category-aware overall-relevance matches in the sole grid
  -> persisted episode membership join
  -> Context Picker selects at most 8 eligible cross-episode records without decoding images
  -> scenery uses persisted SigLIP embedding diversity and up to 4 downscaled
     answer images; document images are paired with OCR text while
     person/location/time stay text-only
  -> clean Gemma answer turn
  -> concise answer and contextual follow-ups while the full grid stays visible
```

## Module contracts

- Preparation is background-owned, resumable, foreground-notified, and
  persisted. The Activity observes state; it does not own long-running work.
- Exact model contracts are fixed: SigLIP2 image/text at normalized 768-D,
  bundled ML Kit Text Recognition v2, YuNet, FaceNet-512, and Gemma 4 E4B
  LiteRT-LM on CPU. Do not
  silently substitute Gemma 3 270M, GGUF, another SigLIP export, or another
  face model.
- MediaStore IDs are stable identities across SQLite, TurboQuant, face rows,
  internal evidence labels. Incremental signatures preserve indexed
  work; normal scans must not delete stale rows or rebuild the index.
- Image encoding belongs to indexing. Search uses the SigLIP text encoder,
  SQLite, and the resident native vector index; it must not run the vision
  encoder or copy all vectors into Kotlin.
- OCR is indexing-time data and uses a distinct planner-authored `ocr`
  predicate. A doc plan fuses one conceptual SigLIP semantic phrase with one
  explicit conjunction such as `[ocr == {Ravi} && {passport}]` using `+`.
  Every OCR word must occur in the same photo; a complete match is the perfect
  tier above semantic-only fallback in both UI results and answer context.
  OCR exclusions run before evidence selection.
- OCR inputs preserve aspect ratio; long images use overlapping region-decoded
  tiles. Confidence/structure gating removes isolated hallucinations.
  `ocr_signature` enables OCR-only migrations without touching visual,
  location, face, cluster, or episode state.
- Face vectors are encrypted and separate from the visual index. Only named
  clusters are authoritative person metadata. Face crops link a local name to
  a specific G record; they are identity aids, not extra gallery sources.
- Face UI virtualizes the complete stable cluster snapshot, supports one-pass
  multi-select merge progress, permits Skip after three named groups, and is
  revisitable from Settings.
- Query planning uses Gemma 4 E4B only and one bracketed C-like AST: semantic union
  `,`, fused addition `+`, subtraction `-`, intersection `&&`, and postfix
  `SORT_DATE`/`SORT_LOC`. Every plan begins with exactly one `query_category`
  (`doc`, `scenary`, `person`, `location`, or `time`). Invalid output gets one
  Gemma repair turn and then fails explicitly; deterministic fallback planning
  is disabled. The AST executes recursively and is diagnostic-only.
- Date predicates require explicit user temporal intent. Undated present-tense,
  amount, and spending queries must not acquire an implicit today range. A
  single exact date must emit equal `from_date` and `to_date`; reject and repair
  a missing or unequal endpoint before search. Day-first forms such as
  `24 July 2026` and `24/07/2026` resolve to one closed day. Explicit after,
  before, and since queries may remain one-sided.
- Content words are not MIME values. MIME remains exactly `photos` or `videos`;
  terms such as movie, movie ticket, receipt, scan, and document may remain in
  semantic/OCR content. Semantic cleanup removes modality words only when an
  actual matching MIME predicate already represents them.
- Named people, time, location, MIME, OCR exclusions, and visual negations are
  hard scopes. They are enforced before ranking, evidence grouping, diversity,
  and Gemma. A known-empty scope must not widen to unrelated semantic hits.
- Face tagging stores one normal display name plus an exclusive `is_self`
  marker. Presence-oriented I/me/my queries resolve to that person; document
  ownership or agency such as “my passport” and “I spent” remains OCR-only.
- Identity/document fields including passport, DL, SSN, PAN, Aadhaar, ID
  number, passwords, user IDs, DOB, exact age, and marks route to `doc`.
  A named document subject remains OCR wording rather than an automatic face
  constraint.
- Location keeps raw GPS and a readable name persisted during indexing.
  Android 10+ requires `ACCESS_MEDIA_LOCATION` plus
  `MediaStore.setRequireOriginal()` before photo EXIF is authoritative.
  Android Geocoder is primary; the optional rate-limited OpenStreetMap fallback
  receives only coarse coordinates and is switchable in Settings. Successful
  reverse geocodes are cached durably; unresolved GPS stays retryable, while
  the `0°,0°` missing-value sentinel is discarded. Capture time is preferred
  over modified time.
- Retrieval is synchronous on the search executor, not on the UI thread. It
  combines native semantic search and SQLite metadata/OCR matching, dedupes
  variants, and treats `0.10` as the primary inclusive confidence cutoff. When
  a positive semantic and scoped metadata branch would otherwise be empty, it
  publishes at most 200 nearest neighbors inside the accumulated hard scope.
  Never apply that fallback to a subtraction branch. Keep the native index
  resident and release only memory-heavy encoders when appropriate.
- Episode construction is a derived SQLite index built after face clustering.
  It groups the full gallery by time, place, and anonymous face overlap.
  Query-time EvidenceBuilder only joins ranked IDs to episode memberships and
  chooses the highest-ranked matching member per episode.
- Resumable metadata-only passes reuse face clusters when every face assignment
  still joins a cluster. Empty or dangling cluster IDs are the rebuild signal.
- OCR indexing persists `doc` for images with any recognized text and `scenary`
  otherwise. The QP category hard-scopes retrieval. Context Picker privately
  selects at most 8 eligible records, preserves relevance, prefers unseen
  episodes, and uses persisted SigLIP embedding novelty only for scenary.
  It never decodes images. Answer prompts pass
  the complete selected OCR and metadata text without prompt-side truncation.
- Gemma uses one resident engine with the frozen QP/language path on CPU and
  only the vision encoder/adapter on GPU (CPU-vision initialization fallback),
  a warmed planner preface/KV session, and a separate clean answer
  conversation. The planner session closes before retrieval, bitmap work, and
  answer assembly. Planner/answer generation is serialized and logs only
  privacy-safe aggregate timing/token/prompt-size counters.
- The answer prompt joins G labels, named people,
  capture time, readable/raw location, MIME/duration, relevant OCR, episode
  rows, optional C context, and at most four downscaled scenery images. Exact
  duplicate structured rows are grouped so
  the model receives the same facts once, and only category-needed fields are
  carried. Gemma reasons over the complete selected context together.
- Answers are natural and calibrated: visual tiles can support activity claims;
  metadata can establish presence/time but not activity; birthday-event dates
  are not birthdates; readable locations are preferred. Sanitization removes
  planner/evidence/record boilerplate, reasoning/task echoes, and execution
  syntax/regex/code. A valid one-sentence model result is never padded with a
  generic browse sentence.
- Follow-ups are useful, deduplicated, and capped at three. G1-G8 and C1-C4
  are private join labels and never appear in UI text. User-visible results
  remain the top 200 overall query matches in the sole scrollable grid before,
  during, and after answer generation. The effective QP spec appears below the search bar
  immediately after planning; Time stats show completed phase durations beside it.
- Rust/JNI owns tokenizer and TurboQuant hot paths. Stable IDs, dimensions,
  bit width, arm64 packaging, and alignment are explicit boundary contracts.

## Non-negotiable invariants

1. Never delete or rebuild the resident gallery index as a search fix.
2. Never allow a high semantic score to bypass an active hard scope.
3. Never invent a named identity without a local face tag.
4. Never reuse planner conversation state for the answer.
5. Bound candidates, images, face crops, OCR, prompt text, caches, and episode
   groups before they reach UI or Gemma.
6. Never expose internal planner/evaluation language to the user.

## Current validation memory

- The current v0.2 pipeline passes 47 JVM contract tests plus the Rust/JNI and
  debug APK build. The APK passes v2 signature and zip-alignment verification,
  installs with `adb install -r -d`, and launches normally on Samsung SM-S938B
  device `RZCY92NW2AZ`.
- The verified device database has integrity `ok`: 16,197 media rows, 9,934
  valid GPS rows resolved (9,834 photos plus 100 videos), 1,324 durable
  locality cache entries, no pending GPS rows,
  and 2,015 episodes whose 16,197 memberships cover every media row exactly
  once. Existing 3,494 face clusters were reused with no unassigned faces.
- The persisted OCR category partition is 2,576 `doc` rows plus 13,621
  `scenary` rows. The semantic threshold is inclusive `0.10`: current direct
  no-fallback photo counts are beach 118, sunset 219, car 58, swimming 75,
  receipt 255, and restaurant bill 116.
- Exact-day live QP:
  `Show photos taken on 5 October 2025` compiled to equal
  `from_date`/`to_date` values of `2025-10-05` and returned 21 scoped results.
- The document-retrieval contract now requires
  `[query_category == doc] && [[semantic == conceptual phrase] +
  [ocr == {word1} && {word2}]]`. JVM tests verify complete same-photo OCR
  conjunctions, perfect-tier precedence over semantic-only results,
  self-name-only passport planning, alias rejection, and the Odyssey ticket
  plan. Re-run the live query matrix before claiming device answer quality.
- The installed and local v0.2 debug APK SHA-256 is
  `6b7953a3227fb4b0c8da3383d8ea2f512852681c3973214964e9a0a93950e40b`.
  Data-preserving reinstall kept the gallery DB hash
  `2a804f5b9a26f7f193bf947f6fa8caa17e229c7c5a9ffbc383c2ae830170d539`,
  SigLIP index hash
  `05f54d80eea6bf4cb92a1ad128a5fb40d892c59ed495c617af73bd5cb7ecae5d`,
  and the 3,659,530,240-byte Gemma model unchanged.
- Current device evidence and the ten-query audit are recorded in
  [`QP_CONTEXT_PICKER_REPORT.md`](QP_CONTEXT_PICKER_REPORT.md). Re-run live
  instrumentation before claiming new latency, model, index, or APK state.
