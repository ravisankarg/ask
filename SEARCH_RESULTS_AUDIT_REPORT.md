# Search execution and result-browser audit

Date: 2026-07-25
Device: Samsung SM-S938B (`RZCY92NW2AZ`)
Scope: structured retrieval and public result presentation. The verified
Gemma-only QP was frozen.

> Historical device snapshot: the counts and UI strings below record the
> 2026-07-25 audit. The current contract supersedes newest-first presentation:
> the public top 200 now preserves category-aware overall relevance, and the
> private answer budget is eight records. See `V0_2_VALIDATION_REPORT.md`.

## Final contract

```text
accepted Gemma execution AST
  -> category allowlist
  -> recursive hard predicates and set operators
  -> scoped semantic + SQLite metadata/OCR matching
  -> inclusive cosine gate >= 0.10
  -> positive-only nearest fallback only when strict + metadata are both empty
  -> complete evaluated set
  -> newest 200 published immediately to the sole public GridView

same complete evaluated set
  -> episode join + Context Picker
  -> at most 16 private answer records
  -> Gemma answer

answer completion
  -> updates prose/follow-ups/timing only
  -> never replaces or truncates the public GridView
```

The full search count and public browsing window are distinct:

- exact structured predicates remain exhaustive inside the index;
- semantic retrieval considers up to 512 relevant candidates;
- the user-visible gallery is capped at the newest 200 matches;
- the private answer context is capped at 16 and is never rendered;
- filenames are not used in public result captions.

## QP freeze proof

The four QP source hashes before and after indexing/search work are identical:

| File | SHA-256 |
| --- | --- |
| `QueryPlannerRuntime.kt` | `4bfc7afa2a6bf3067be2d87e94eeb3a73fa60a27959936423aba49b835db85b2` |
| `QueryScope.kt` | `2cc3762965422dcf068bf09fc1d1a000cbd62f280a37f9c541a10a0b50f11edd` |
| `QueryExecutionSpec.kt` | `46b864716704dd0047f509860e695501e54d5288823aa1d3260cec289c1525b0` |
| `QueryPlan.kt` | `ce8a62bfeb8a910ea82de3a842223d285d72a06e717bdb7256dbfefe1e838c65` |

No deterministic planner was enabled and no QP prompt, validator, parser, or
normalizer was edited.

## Strict semantic calibration without fallback

The device audit called the strict semantic API directly, bypassing the
structured executor and its non-empty fallback. For every query,
`searchScoredBlocking` returned exactly the nearest rows with cosine score
`>= 0.10`.

| Query | Hard scope | Top cosine | Strict matches |
| --- | --- | ---: | ---: |
| beach | 9,366 scenery photos | 0.126674 | 118 |
| sunset | 9,366 scenery photos | 0.124933 | 219 |
| car | 9,366 scenery photos | 0.122223 | 58 |
| swimming | 9,366 scenery photos | 0.137762 | 75 |
| receipt | 2,576 document photos | 0.166673 | 255 |
| restaurant bill | 2,576 document photos | 0.161183 | 116 |

Every one of these simple queries would return zero at a fixed `0.20` cutoff.
The production threshold remains the measured inclusive `0.10`; fallback is
not needed for these cases.

## Planner-independent structured-search matrix

Hard scope exhaustiveness:

| Execution scope | Expected | Returned |
| --- | ---: | ---: |
| `scenary && photos` | 9,366 | 9,366 |
| `doc && photos` | 2,576 | 2,576 |
| `person && photos` | 2,251 | 2,251 |
| `location && photos` | 9,834 | 9,834 |
| `time && photos` | 11,942 | 11,942 |

Operator/search checks:

- beach: 118;
- sunset: 219;
- beach-or-sunset union: 306, containing both complete branches;
- beach-minus-glasses: 118, a subset of its positive branch;
- receipt: 265 after semantic/OCR metadata fusion;
- one persisted readable-place predicate: 4,148 exact hard-scope matches;
- `SORT_LOC`: all 9,834 location photos in readable-place order;
- `SORT_DATE`: all 11,942 photos newest first.

Subtraction never uses low-confidence nearest fallback, so a weak negative
match cannot remove an otherwise relevant photo.

## Installed UI regression

Query: `Show photos`

Accepted frozen-QP spec:

```text
[query_category == scenary] && [mime type == photos]
```

Observed production state sequence:

1. `QP output • ready` with the executable spec and zero results;
2. public adapter becomes 200 with
   `Showing newest 200 of 9366 matches • scroll to browse`;
3. private answer generation starts with 16 selected records;
4. answer generation completes;
5. public adapter remains exactly 200.

The installed-device UI test also proved:

- QP output appeared before the first results;
- the production `GridView` advanced to first-visible item 80;
- tapping a result opened `MediaDetailActivity`;
- returning preserved all 200 results;
- the Activity did not finish during back navigation;
- no raw filename appeared in public captions;
- no `Answer context` or other private 16-image surface appeared;
- answer completion did not replace the grid.

The phone remained securely keyguard-locked. The instrumentation laid out the
real production `GridView` in a fixed test viewport to exercise virtualization
without unlocking or changing device credentials.

Timing for this run:

- Gemma planning: 65.412 s;
- structured retrieval: 1.213 s;
- private diversity selection: 1.786 s;
- evidence grouping: 0.011 s.

Planning remains the dominant latency; search/result presentation is not the
bottleneck.

## Build and install

- Complete JVM suite: passed.
- Android lint: passed.
- Rust/JNI release build: passed.
- Debug app and instrumentation assembly: passed.
- Search matrix/threshold/category/location tests: `OK (4 tests)`.
- Full 200-result UI/answer-isolation test: `OK (1 test)`.
- `git diff --check`: clean.
- Debug APK SHA-256:
  `bbbbe4e2d5fb4bb549de05a72ca1a6e52fe50c91ad046738ee12d85a9d0ed4f0`.
- Installed APK SHA-256: exact match.
- Installed package update: 2026-07-25 17:40:23.

## Search-module conclusion

The previously reported “top 8/top 16 only” behavior is no longer present in
either the data path or the UI path. Search publishes a useful result browser
first, keeps up to 200 results visible throughout the answer lifecycle, and
treats the Context Picker as private answer input only. The next module can be
reviewed without reopening QP or search unless a downstream test produces new
retrieval evidence.
