# E2B gallery-search QP live-device audit — 2026-08-24

## Scope and method

- Device: connected Samsung SM-S938B
- Corpus: 100 pure gallery-search queries; no user query asks for an answer
- Coverage: scenes, objects, photo/video scope, OCR-like gallery text, people, exclusivity, location, time, sorting, multi-constraint composition, negation, ambiguity, typos, and colloquial phrasing
- Execution: launch once, then select-all/delete the search field and submit the next query while reusing the warm E2B planner
- Evidence: filtered live `adb logcat` only — raw E2B V2 JSON, validator/repair result, accepted plan summary, executable QP spec, and fatal process exits
- No UI/system dump, instrumentation, reinstall, permission change, data clear, reindex, database read, or intentional per-case app stop was used

The installed app terminated itself during result rendering 17 times. The runner recorded those exits and relaunched only to continue. It never force-stopped the app during this official run and reused the warm E2B process whenever it survived. The app was alive after the audit.

## KPI summary

| KPI | Result |
|---|---:|
| Total queries with captured E2B output | 100/100 |
| Strictly correct complete plans | 25/100 (25.0%) |
| Strict failures | 75/100 (75.0%) |
| Structurally executable after validation | 67/100 (67.0%) |
| Validator failures after the one repair | 33/100 (33.0%) |
| Repair recovery | 0/33 (0.0%) |
| Executable but semantically wrong | 42/67 (62.7%) |
| Correct among executable plans | 25/67 (37.3%) |
| Raw intent `browse` | 89/100 |
| Wrong answer intent for a pure search | 3/100 |
| Raw output not valid JSON | 8/100 |
| Spontaneous installed-app fatal exits | 17 |
| QP wall time | mean 2,486 ms; median 2,381 ms; p95 3,266 ms |

## Strict results by family

| Family | Pass | Fail |
|---|---:|---:|
| Scene and object | 2/10 | 8/10 |
| Media and gallery OCR | 3/10 | 7/10 |
| People and exclusivity | 2/10 | 8/10 |
| Location and travel | 5/10 | 5/10 |
| Time and sorting | 3/15 | 12/15 |
| Multi-constraint | 2/20 | 18/20 |
| Ambiguity | 6/15 | 9/15 |
| Typo and colloquial | 2/10 | 8/10 |

## Main findings

1. A valid plan is not a reliable quality signal. E2B produced 67 executable plans, but 42 of those dropped, invented, or misrouted intent.
2. Simple visual nouns are frequently lost or misclassified. `sunset pictures` became every photo; `beach photos` and `show beach pics` treated beach as a hard location; `portrait photos` and `blurry photos` dropped the visual concept.
3. Person grounding is unsafe. Accepted Ramani searches repeatedly selected unrelated local face references, compiling to `chinni` or `meghana`. A search for `photos last 3 years` also invented self/Ravi.
4. Exclusivity and negation are weak. `me only`, `Ramani only`, `me alone`, `without`, `except`, and `not in` often lost `people_only`, used OCR terms, or failed validation.
5. Temporal handling works for simple anchors but collapses on richer wording. Today, yesterday, and this year passed; this week, last month, open-ended years, bounded month ranges, and temporal seasons mostly failed or became invalid.
6. Compound visual semantics are frequently split or converted into mandatory OCR intersections. This narrows gallery search incorrectly even when the plan passes validation.
7. Multi-constraint composition is the worst practical area: only 2/20 cases passed completely.
8. Typo correction is inconsistent. `sunset picturs` and `pics frm yesterday` passed, but person typos selected the wrong face, other typos became malformed, and `oldest` was rewritten as `oldestest` despite a correct executable plan.
9. Ambiguity is best handled when E2B stays broad: `pics from there` correctly avoided inventing a location. It failed when it invented a meaning, such as interpreting `last summer` as travel plus newest sort.

## Representative live outputs

| ID | Query | Verdict | Raw E2B or executable evidence |
|---:|---|---|---|
| 001 | sunset pictures | FAIL | Raw ops contained only `media=photos`; sunset was dropped. |
| 002 | beach photos | FAIL | Executed `photos AND location=beach`; beach is visual content here. |
| 010 | red car | FAIL | Used `answer:text`, dropped red, and required car as OCR text. |
| 014 | selfies at the beach | FAIL | Invented self/Ravi and made beach a location. |
| 022 | Ravi and Ramani together | FAIL | Used `answer:person`; Ramani compiled to face label `chinni`. |
| 041 | photos from today | PASS | Executed photos with exact date `2026-08-24..2026-08-24`. |
| 046 | photos last 3 years | FAIL | Invented person Ravi in a person-free query. |
| 058 | videos of Ravi dancing at Goa | PASS | Preserved Ravi, videos, dancing, and Goa. |
| 066 | oldest videos from Kerala | PASS | Correct executable scope and oldest sort; raw `q` said `oldestest`. |
| 075 | photos with text happy birthday | FAIL | Used visual semantic terms instead of mandatory written text. |
| 078 | last summer | FAIL | Invented outside-normal travel and newest sort; lost season timing. |
| 080 | pics from there | PASS | Kept broad photo scope and did not invent a place. |
| 091 | sunset picturs | PASS | Correctly retained photo scope and semantic sunset. |
| 097 | latest Ramani snaps | FAIL | Ramani compiled to unrelated face label `meghana`. |
| 098 | no Ravi just Ramani photos | FAIL | Used OCR keywords for both names; no person exclusion or `people_only`. |
| 100 | find my night videos | FAIL | Structurally invalid output; repair did not recover. |

## Evidence files

- `qp_gallery_search_live_100_2026-08-24.tsv` — frozen corpus and expected interpretation
- `qp_gallery_search_live_results_2026-08-24.tsv` — raw E2B output, repair state, validator status, accepted summary, and executable plan
- `qp_gallery_search_live_scored_2026-08-24.tsv` — all 100 PASS/FAIL verdicts with strict failure reasons

This report scores only query planning. It does not treat gallery result availability or retrieval relevance as a QP pass criterion.
