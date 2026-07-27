# QP and Context Picker device report

Date: 2026-07-25
Device: Samsung SM-S938B (`RZCY92NW2AZ`)
Planner: on-device Gemma 4 E4B only
Semantic cosine cutoff: `0.10` inclusive, with a positive-only scoped
nearest-neighbor fallback when both strict semantic and metadata results are
empty

> Historical device snapshot: this report preserves the 2026-07-25 measured
> behavior. The current contract uses an eight-record context, text-only
> non-scenery answers, category-aware overall result ranking, and complete
> same-photo OCR conjunctions. See `V0_2_VALIDATION_REPORT.md`.

## Final pipeline contract

1. The OCR indexing pass atomically persists OCR text and `content_class`.
   Every image with any non-empty OCR is `doc`; every other image/video is
   `scenary`.
2. The database migration backfills this class for existing indexed rows and
   adds an indexed `content_class` column.
3. Gemma emits one `query_category`. The formal parser preserves every
   model-authored retrieval predicate/operator and only canonicalizes the
   single category predicate to the outer `category && retrieval` envelope.
4. The category becomes a hard native/SQLite allowlist. The executor also
   intersects final IDs with that allowlist as a safety boundary.
5. The UI receives up to 200 scoped matches in overall-relevance order. The
   current private Context Picker independently selects at most eight eligible
   records.
6. Only `scenary` may attach pixels, capped at four images downscaled to a
   512 px longest edge. `doc`, `person`, `location`, and `time` use text and
   metadata only.

## Installed-index verification

| Check | Device result |
|---|---:|
| Total indexed media | 16,197 |
| `doc` rows | 2,576 |
| `scenary` rows | 13,621 |
| Partition invariant | 2,576 + 13,621 = 16,197 |
| Location-scoped photos | 9,834 |

A populated-path query, `Show photos`, compiled to:

```text
[query_category == scenary] && [mime type == photos]
```

It returned 9,366 category-scoped matches, exposed 200 in the UI result
window, and privately selected 16/16 eligible visual records. No picked record
fell outside the category.

## Ten complex Gemma QP cases

| # | Query | Category | Effective execution spec | Planning |
|---:|---|---|---|---:|
| 1 | How much did I spend on food today from my screenshots? | `doc` | `[query_category == doc] && [[from_date == 2026-07-24] && [to_date == 2026-07-24] && [mime type == photos] && [semantic == food receipt total]]` | 44.16 s |
| 2 | What was the restaurant name on the bill from last week? | `doc` | `[query_category == doc] && [[from_date == 2026-07-13] && [to_date == 2026-07-19] && [semantic == restaurant name bill]]` | 33.64 s |
| 3 | Show Ravi swimming in Goa last summer without glasses. | `scenary` | `[query_category == scenary] && [[[person == Ravi] && [semantic == swimming] && [location == Goa]] - [semantic == glasses] && [from_date == 2025-07-01] && [to_date == 2025-09-30]]` | 44.39 s |
| 4 | Who else was with Meghana during the latest team outing? | `person` | `[query_category == person] && [[person == Meghana] && [semantic == team outing]]` | 34.62 s |
| 5 | Which people appeared with Ravi but not Ramani at the wedding? | `person` | `[query_category == person] && [[person == Ravi] && [semantic == wedding]] - [person == Ramani]` | 32.43 s |
| 6 | Where did I go with Ravi last month? | `location` | `[query_category == location] && [[person == Ravi] && [from_date == 2026-06-01] && [to_date == 2026-06-30]]` | 37.81 s |
| 7 | What all places did I visit during 2025 without Meghana? | `location` | `[query_category == location] && [[from_date == 2025-01-01] && [to_date == 2025-12-31]] - [person == Meghana]` | 37.79 s |
| 8 | When did Ravi go swimming in Goa? | `time` | `[query_category == time] && [[person == Ravi] && [location == Goa] && [semantic == swimming]] SORT_DATE` | 36.35 s |
| 9 | When was the last birthday party with Meghana? | `time` | `[query_category == time] && [[person == Meghana] && [semantic == birthday party]] SORT_DATE` | 35.76 s |
| 10 | Show videos of mountain trekking at sunset, excluding Ravi. | `scenary` | `[query_category == scenary] && [[mime type == videos] && [semantic == mountain trekking sunset]] - [person == Ravi]` | 35.57 s |

Final category accuracy was 10/10. Visual routing was correct in 10/10:
`doc/scenary` enabled visual context and `person/location/time` stayed
metadata-only. Every full-result and private-context category-leak counter was
zero.

The ten intentionally narrow cases produced no matching rows in the current
gallery for their combined people/place/date/content constraints. The separate
populated-path smoke test above therefore verifies the 200-result UI window and
16-record Context Picker behavior with real matches.

Planning latency for the ten complex cases was 37.25 seconds on average
(32.43–44.39 seconds). Retrieval and Context Picker work completed after the
plan; planner decode is currently the dominant query latency.

## Undated-query regression

The installed Gemma planner was tested with:

```text
How much I spend on car repair
```

The accepted effective plan was:

```text
[query_category == doc] && [semantic == car repair invoice total]
```

Both `from_date` and `to_date` were empty. Prompt instructions and a hard
post-plan validator now reject any model-created date range unless the original
query contains an explicit date, month, year, weekday, season, relative-time
phrase, or time of day.

## Exact single-day regression

The installed Gemma planner was tested with:

```text
Show photos taken on 5 October 2025
```

The accepted and executed plan was:

```text
[query_category == scenary] && [[from_date == 2025-10-05] && [to_date == 2025-10-05] && [mime type == photos]]
```

It returned 21 records from that closed day scope. The post-plan validator now
rejects a missing or unequal endpoint for one exact date and triggers Gemma's
single repair turn. Explicit open-ended wording such as after, before, or since
may still use one boundary. Named day-first and numeric day-first inputs such as
`24 July 2026` and `24/07/2026` are covered by parser regressions.

## Movie-ticket semantic regression

The exact live query:

```text
how much did I spend on the odyssy movie ticket
```

originally failed because two separate cleanup layers treated the content word
`movie` as though it were a MIME-routing word. The installed fix preserves
content words unless an actual matching media predicate already represents
them. Gemma spelling-corrected the title and produced:

```text
[query_category == doc] && [semantic == Odyssey movie ticket price]
```

The effective execution spec retained that complete semantic phrase, emitted no
invented date and no MIME predicate, returned 73 document results, and selected
16 private answer-context records.

## Misspelled broad-search regression

The exact installed-device query:

```text
beach phootos
```

was correctly spelling-normalized by Gemma to:

```text
[query_category == scenary] && [[mime type == photos] && [semantic == beach]]
```

The original failure was retrieval-side, not UI-side: the top category- and
MIME-scoped semantic match scored `0.12040177`, so the former strict `0.20`
gate emptied the result set. Retrieval now uses the device-calibrated `0.10`
primary cutoff, but
when an eligible positive semantic branch and its scoped metadata branch are
both empty, it publishes up to 200 nearest neighbors within the accumulated
hard scope. Subtraction branches remain strict so low-confidence neighbors can
never remove unrelated results.

The current OCR-scoped device regression returns 118 strict results, publishes
all 118 to the user-visible grid, and privately selects 16 for answer
generation; no nearest-neighbor fallback is involved. The separate populated
`Show photos` UI test exposes the newest 200 of 9,366 matches, scrolls the real
production grid to item 80, opens details, returns with the same 200 records,
and retains the grid through answer completion.

## Strict semantic-threshold audit

The semantic index was queried directly on the installed device, bypassing the
structured executor and its 200-result fallback. For each query, the raw nearest
512 candidates were compared with `searchScoredBlocking`; the strict API
returned exactly the raw candidates whose cosine score was at least `0.10`.

| Query | Scope | Top score | `>= 0.10` | `>= 0.12` | `>= 0.14` | `>= 0.16` | `>= 0.20` |
|---|---|---:|---:|---:|---:|---:|---:|
| beach | scenary photos | 0.126674 | 118 | 4 | 0 | 0 | 0 |
| sunset | scenary photos | 0.124933 | 219 | 15 | 0 | 0 | 0 |
| car | scenary photos | 0.122223 | 58 | 1 | 0 | 0 | 0 |
| swimming | scenary photos | 0.137762 | 75 | 22 | 0 | 0 | 0 |
| receipt | doc photos | 0.166673 | 255 | 84 | 24 | 6 | 0 |
| restaurant bill | doc photos | 0.161183 | 116 | 35 | 13 | 1 | 0 |

The former `0.20` gate was implemented correctly but was not calibrated to this
installed SigLIP/TurboQuant score distribution: it rejects every result for all
six simple queries. If retrieval must operate without the non-empty fallback,
`0.10` is the only tested fixed threshold that retains results across all six
queries. Production therefore uses the tested inclusive `0.10` cutoff. A future
category-specific or top-score-relative threshold may preserve more precision
than one global constant.

After installing the `0.10` build, the same direct no-fallback audit returned
strict counts of 75 (`beach`), 174 (`sunset`), 37 (`car`), 57 (`swimming`),
261 (`receipt`), and 119 (`restaurant bill`). After the ML Kit OCR-only
reclassification, the current counts are 118 (`beach`), 219 (`sunset`), 58
(`car`), 75 (`swimming`), 255 (`receipt`), and 116 (`restaurant bill`). The
complete `beach phootos` planner-to-retrieval regression returned 118 strict
results and selected 16
private answer-context images; no fallback log was emitted.

## Verification performed

- Complete JVM contract suite: passed.
- Debug Android lint: passed.
- Rust/JNI native release build: passed.
- Debug app and instrumentation APK assembly: passed.
- Database v9 → v10 category migration, v11 OCR migration, and isolated v12
  photo-location migration on the installed index: passed.
- Installed-index partition instrumentation: passed.
- Populated 9,366-result / 200-UI / 16-context smoke test: passed.
- Exact `beach phootos` planner/retrieval/UI regression: passed with all 118
  strict results shown.
- Direct strict-threshold device audit with fallback bypassed: passed; `0.20`
  rejected all six tested simple queries.
- Installed `0.10` strict-threshold audit: passed with non-empty results for all
  six queries.
- Installed exact-day QP/retrieval regression: passed with equal inclusive
  endpoints and 21 scoped results.
- Location-only index repair: passed with 9,834 readable photo places, 100
  readable video places, zero pending rows, and unchanged non-location
  isolation digests.
- Exact misspelled movie-ticket QP/retrieval regression: passed with 73 results.
- Ten complex live Gemma QP scenarios plus the repaired grammar/category
  regression cases: passed.

## Remaining quality note

The requested classification deliberately treats any OCR text as `doc`.
Consequently, OCR false positives will also classify an otherwise ordinary
photo as `doc`; improving the OCR model will directly improve this category
index without changing the query-time design.
