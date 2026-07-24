# Ask Galaxy design memory

Last reviewed: 2026-07-23

This is the concise architectural memory for future Ask Galaxy sessions. The
full module contracts, commands, live query matrix, and known issues are in
[`ASK_GALAXY_MODULE_REQUIREMENTS.md`](ASK_GALAXY_MODULE_REQUIREMENTS.md).

## Product contract

Ask Galaxy is a private, local-first gallery assistant. It must answer natural
questions about images, people, activities, dates, places, OCR, trips, and
optional personal context without exposing internal search machinery. Quality
comes from joining visual pixels, named faces, capture metadata, readable/raw
locations, OCR, and personal context in one grounded answer.

## End-to-end architecture

```text
WorkManager preparation
  -> exact model/artifact verification and resumable downloads
  -> MediaStore scan and incremental SQLite rows
  -> capture/GPS extraction and cached online reverse geocoding
  -> SigLIP2 image/frame vectors in TurboQuant
  -> PP-OCRv5 text in SQLite
  -> YuNet + FaceNet-512 encrypted face data and clustering
  -> persisted time/location/person episode index
  -> full virtualized face review/tagging snapshot
  -> search-ready state

Search field visible
  -> warm Gemma 4 CPU engine, SigLIP text encoder, and native index
  -> deterministic or Gemma canonical execution AST
  -> recursive set execution over native semantic + indexed metadata branches
  -> newest 200 matches shown newest-first in the scrollable grid
  -> persisted episode membership join
  -> Context Picker selects at most 16 cross-episode multimodal images
  -> one EXIF-correct 4x4 gallery/face evidence board
  -> clean Gemma answer turn
  -> concise answer, contextual follow-ups, live QP output, and Time stats
```

## Module contracts

- Preparation is background-owned, resumable, foreground-notified, and
  persisted. The Activity observes state; it does not own long-running work.
- Exact model contracts are fixed: SigLIP2 image/text at normalized 768-D,
  PP-OCRv5, YuNet, FaceNet-512, and Gemma 4 E4B LiteRT-LM on CPU. Do not
  silently substitute Gemma 3 270M, GGUF, another SigLIP export, or another
  face model.
- MediaStore IDs are stable identities across SQLite, TurboQuant, face rows,
  internal evidence labels. Incremental signatures preserve indexed
  work; normal scans must not delete stale rows or rebuild the index.
- Image encoding belongs to indexing. Search uses the SigLIP text encoder,
  SQLite, and the resident native vector index; it must not run the vision
  encoder or copy all vectors into Kotlin.
- OCR is indexing-time data and currently uses bounded SQLite keyword matching.
  Multi-word concepts stay phrase branches, and OCR exclusions run before
  evidence selection.
- Face vectors are encrypted and separate from the visual index. Only named
  clusters are authoritative person metadata. Face crops link a local name to
  a specific G record; they are identity aids, not extra gallery sources.
- Face UI virtualizes the complete stable cluster snapshot, supports one-pass
  multi-select merge progress, permits Skip after three named groups, and is
  revisitable from Settings.
- Query planning uses one bracketed C-like AST on both paths: semantic union
  `,`, fused addition `+`, subtraction `-`, intersection `&&`, and postfix
  `SORT_DATE`/`SORT_LOC`. Clear structured queries use the fast deterministic
  path; ambiguous/relational queries use Gemma 4. The AST executes recursively
  and is diagnostic-only.
- Named people, time, location, MIME, OCR exclusions, and visual negations are
  hard scopes. They are enforced before ranking, evidence grouping, diversity,
  and Gemma. A known-empty scope must not widen to unrelated semantic hits.
- Location keeps raw GPS and a readable name persisted during indexing.
  Android Geocoder is primary; the optional rate-limited OpenStreetMap fallback
  receives only coarse coordinates and is switchable in Settings. Successful
  reverse geocodes are cached durably; unresolved GPS stays retryable. Capture
  time is preferred over modified time.
- Retrieval is synchronous on the search executor, not on the UI thread. It
  combines native semantic search and SQLite metadata/OCR matching, dedupes
  variants, keeps the native index resident, and releases only memory-heavy
  encoders when appropriate.
- Episode construction is a derived SQLite index built after face clustering.
  It groups the full gallery by time, place, and anonymous face overlap.
  Query-time EvidenceBuilder only joins ranked IDs to episode memberships and
  chooses the highest-ranked matching member per episode.
- Resumable metadata-only passes reuse face clusters when every face assignment
  still joins a cluster. Empty or dangling cluster IDs are the rebuild signal.
- Context Picker privately selects at most 16 images. It preserves relevance, covers
  requested person/place/OCR/time facets, prefers unseen episodes, and fills
  remaining slots using visual, OCR, metadata, place, and timeline novelty.
- Gemma uses one resident CPU engine, a warmed planner preface/KV session, and
  a separate clean answer conversation. The planner session closes before
  retrieval, bitmap work, and answer assembly. Planner/answer generation is
  serialized and logs only privacy-safe timing/token counters.
- The answer prompt joins G labels, pixels, named people, face anchors,
  capture time, readable/raw location, MIME/duration, relevant OCR, episode
  rows, and optional C context. Gemma reasons over the complete board together,
  not one image at a time.
- Answers are natural and calibrated: visual tiles can support activity claims;
  metadata can establish presence/time but not activity; birthday-event dates
  are not birthdates; readable locations are preferred. Sanitization removes
  planner/evidence/record boilerplate and execution syntax/regex/code.
- Follow-ups are useful, deduplicated, and capped at three. G1-G16 and C1-C4
  user-visible results remain the newest 200 matches in the sole scrollable grid.
  The effective QP spec appears below the search bar immediately after planning;
  Time stats show completed phase durations beside it.
- Personal context is opt-in, separately encrypted, filtered, bounded, and
  queried only when the plan requests it. It must never become an implicit
  cloud or full-notification context path.
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

- The current location/episode/context-picker/QP refactor passes 19 JVM
  contract tests, Android lint, debug and release assembly, APK signature
  verification, install, launch, and live search on device `RZCY92NW2AZ`.
- The verified device database has integrity `ok`: 16,197 media rows, 100/100
  GPS rows resolved, 39 durable locality cache entries, no pending GPS rows,
  and 2,015 episodes whose 16,197 memberships cover every media row exactly
  once. Existing 3,494 face clusters were reused with no unassigned faces.
- Prior device measurements were approximately 5 ms fast-path planning,
  3.1–3.7 s retrieval, and 45 s Gemma answer generation. Do not claim device
  speed improvements without fresh phase traces.
- The face review surface now shows all 3,469 current clusters in one stable
  virtualized snapshot; nine are named, so search is unlocked. A live typo query
  compiled to `[person == Ravi] && [semantic == team outing] && [mime type == photos]`
  and displayed the spec below the search bar before retrieval. Continue the
  full ten-query matrix for latency, answer calibration, and location coverage.
