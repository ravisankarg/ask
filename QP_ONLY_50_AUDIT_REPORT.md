# Gemma 4 E4B QP + Plan Validator: Final 50-Query Device Audit

Date: 2026-07-25
Device: Samsung SM-S938B (`RZCY92NW2AZ`)
Model: on-device `gemma-4-E4B-it.litertlm`
Scope: Gemma query planning, C-like spec parsing/compilation, and production validators only

No gallery search, semantic retrieval, Context Picker, answer generation, or
search-result UI was constructed by this audit. The harness supplies only the
query, current date, and controlled known-person vocabulary (`Ravi`, `Meghana`,
`Ramani`).

## Final outcome

| Category | Queries | Passed | Failed | Queries using repair | Mean latency | Median latency |
|---|---:|---:|---:|---:|---:|---:|
| `doc` | 10 | 10 | 0 | 0 | 41.2 s | 40.6 s |
| `scenary` | 10 | 10 | 0 | 1 | 43.1 s | 40.8 s |
| `person` | 10 | 10 | 0 | 0 | 40.7 s | 40.7 s |
| `location` | 10 | 10 | 0 | 4 | 56.4 s | 51.6 s |
| `time` | 10 | 10 | 0 | 2 | 46.4 s | 40.9 s |
| **Total** | **50** | **50 (100%)** | **0** | **7** | **45.6 s** | **40.8 s** |

The 50 planner calls took 2,278.046 seconds (37 minutes 58 seconds) on device.
Seven queries used at least one repair; two location queries needed the bounded
second repair. The final human review found no category inversion, invented
date, omitted exact-day upper bound, structured-field loss, relational semantic
filter, unrequested location sort, or malformed subtraction.

## Acceptance contract

Every row passed all of these checks:

1. Gemma 4 E4B authored one C-like execution expression; no deterministic
   planner or fallback was used.
2. Parse-render-parse round-tripped without change.
3. Exactly one valid `query_category` was present.
4. The compiler accepted every field and operator.
5. Category, structured values, exact date bounds, semantic contents,
   subtraction, and sort validators accepted the compiled plan.
6. Explicit people, MIME, single locations, date ranges, and negation survived
   as structured constraints.
7. Metadata-only co-occurrence and plural place-list queries contained no
   positive semantic filter.
8. `SORT_LOC` appeared only for a plural place list or explicit location order.
9. Human review confirmed that the accepted expression preserved the user's
   retrieval intent.

## Final result table

| ID | Complexity / ambiguity | Query | Accepted execution spec | Repair | Latency |
|---|---|---|---|---|---:|
| L01 | simple / clear | Where was this beach photo taken? | `[query_category == location] && [[mime type == photos] && [semantic == beach]]` | No | 60.6 s |
| L02 | simple / moderate | Where did I park the car? | `[query_category == location] && [semantic == car parking]` | Yes | 61.4 s |
| L03 | medium / clear | What places did I visit last year? | `[query_category == location] && [[from_date == 2025-01-01] && [to_date == 2025-12-31] SORT_LOC]` | No | 42.6 s |
| L04 | medium / moderate | Where did Ravi and I go for dinner? | `[query_category == location] && [[person == Ravi] && [semantic == dinner]]` | Yes | 61.8 s |
| L05 | medium / clear | Which national parks have I visited? | `[query_category == location] && [[semantic == national park visit] SORT_LOC]` | No | 40.4 s |
| L06 | medium / moderate | Where was the sunset photo with the lighthouse taken? | `[query_category == location] && [[mime type == photos] && [semantic == sunset with lighthouse]]` | No | 40.7 s |
| L07 | complex / clear | Which cities did I visit with Meghana but without Ravi? | `[query_category == location] && [[person == Meghana] - [person == Ravi] SORT_LOC]` | Yes | 83.8 s |
| L08 | complex / moderate | Where did we camp during the latest mountain trip? | `[query_category == location] && [[semantic == mountain campsite] SORT_DATE]` | No | 40.5 s |
| L09 | complex / ambiguous | Where did my Bengaluru-to-Goa road trip stop for lunch? | `[query_category == location] && [semantic == lunch stop on Bengaluru-to-Goa road trip]` | No | 40.9 s |
| L10 | complex / clear | Which places did I visit in July 2025, from north to south, excluding airport layovers? | `[query_category == location] && [[[from_date == 2025-07-01] && [to_date == 2025-07-31]] - [semantic == airport layover] SORT_LOC]` | Yes | 91.5 s |
| S01 | simple / typo | beach phootos | `[query_category == scenary] && [[mime type == photos] && [semantic == beach]]` | No | 40.8 s |
| S02 | simple / clear | Show dogs playing in the snow. | `[query_category == scenary] && [semantic == dogs playing in the snow]` | No | 40.3 s |
| S03 | medium / clear | Find photos with a red car at night. | `[query_category == scenary] && [[mime type == photos] && [semantic == red car at night]]` | No | 41.0 s |
| S04 | medium / clear | Show videos of fireworks over water. | `[query_category == scenary] && [[mime type == videos] && [semantic == fireworks over water]]` | No | 40.8 s |
| S05 | medium / ambiguous | Photos that would make great phone backgrounds. | `[query_category == scenary] && [[mime type == photos] && [semantic == beautiful phone wallpaper background]]` | No | 40.8 s |
| S06 | complex / clear | Show Ravi hiking without sunglasses. | `[query_category == scenary] && [[person == Ravi] && [semantic == hiking]] - [semantic == sunglasses]` | Yes | 62.6 s |
| S07 | complex / moderate | Find the best sunset from each mountain trip. | `[query_category == scenary] && [semantic == best sunset from mountain trip]` | No | 40.5 s |
| S08 | complex / clear | Show birthday-cake photos with blue decorations but exclude indoor scenes. | `[query_category == scenary] && [[mime type == photos] && [semantic == birthday cake with blue decorations] - [semantic == indoor scene]]` | No | 41.7 s |
| S09 | complex / moderate | What did I eat on my trip to Barcelona? | `[query_category == scenary] && [[location == Barcelona] && [semantic == food eaten during trip]]` | No | 40.9 s |
| S10 | complex / ambiguous | Show the clearest photo from every national park I visited, excluding selfies. | `[query_category == scenary] && [[mime type == photos] && [semantic == clearest national park landscape]] - [semantic == selfie]` | No | 41.4 s |
| P01 | simple / clear | Who is in these beach photos? | `[query_category == person] && [[mime type == photos] && [semantic == beach]]` | No | 40.7 s |
| P02 | medium / clear | Who else was with Meghana at the team outing? | `[query_category == person] && [[person == Meghana] && [semantic == team outing]]` | No | 40.9 s |
| P03 | complex / clear | Which people were at the wedding but not Ravi? | `[query_category == person] && [semantic == wedding] - [person == Ravi]` | No | 37.8 s |
| P04 | medium / moderate | Who appears most often with Ramani? | `[query_category == person] && [person == Ramani]` | No | 40.4 s |
| P05 | medium / moderate | Who was holding the birthday cake? | `[query_category == person] && [semantic == holding birthday cake]` | No | 40.0 s |
| P06 | complex / clear | Which people joined both the Goa trip and the mountain trek? | `[query_category == person] && [[location == Goa] && [semantic == mountain trek]]` | No | 40.7 s |
| P07 | complex / clear | Who was with me at dinner on 5 October 2025? | `[query_category == person] && [[from_date == 2025-10-05] && [to_date == 2025-10-05] && [semantic == dinner]]` | No | 43.1 s |
| P08 | complex / typo | Who appears with Meghna in Bengaluru without Ravi? | `[query_category == person] && [[person == Meghana] && [location == Bengaluru]] - [person == Ravi]` | No | 41.2 s |
| P09 | complex / ambiguous | Which person is wearing a red jacket beside the dog? | `[query_category == person] && [semantic == red jacket beside dog]` | No | 40.4 s |
| P10 | complex / moderate | Who else was present across Ravi's whole birthday celebration, excluding restaurant screenshots? | `[query_category == person] && [[person == Ravi] && [semantic == birthday celebration]] - [semantic == restaurant screenshot]` | No | 41.3 s |
| T01 | simple / clear | When did I visit Goa? | `[query_category == time] && [[location == Goa] SORT_DATE]` | No | 40.2 s |
| T02 | simple / clear | When was Ravi's birthday party? | `[query_category == time] && [[person == Ravi] && [semantic == birthday party] SORT_DATE]` | No | 40.9 s |
| T03 | simple / moderate | What date was the beach picnic? | `[query_category == time] && [[semantic == beach picnic] SORT_DATE]` | No | 40.3 s |
| T04 | medium / clear | When did Meghana and I go hiking? | `[query_category == time] && [[person == Meghana] && [semantic == hiking] SORT_DATE]` | No | 40.9 s |
| T05 | medium / clear | When was the last time I photographed the red car? | `[query_category == time] && [[semantic == red car photograph] SORT_DATE]` | No | 40.6 s |
| T06 | complex / clear | On which dates did we visit national parks last year? | `[query_category == time] && [[semantic == national park visit] && [from_date == 2025-01-01] && [to_date == 2025-12-31] SORT_DATE]` | No | 43.4 s |
| T07 | complex / clear | When did Ravi appear with Ramani but not Meghana? | `[query_category == time] && [[person == Ravi] && [person == Ramani] - [person == Meghana] SORT_DATE]` | No | 41.4 s |
| T08 | medium / ambiguous | What time of day did we reach the mountain campsite? | `[query_category == time] && [[semantic == mountain campsite arrival time] SORT_DATE]` | No | 40.6 s |
| T09 | complex / clear | When was the earliest sunset photo in Goa after June 2025? | `[query_category == time] && [[mime type == photos] && [location == Goa] && [semantic == sunset] && [from_date == 2025-07-01] SORT_DATE]` | Yes | 66.2 s |
| T10 | complex / clear | Between January and March 2025, on which day did the whole team meet at the Hyderabad offsite without Ravi? | `[query_category == time] && [[[from_date == 2025-01-01] && [to_date == 2025-03-31] && [location == Hyderabad] && [semantic == whole team meet at offsite]] - [person == Ravi] SORT_DATE]` | Yes | 69.6 s |
| D01 | simple / clear | What is the total on my hotel bill? | `[query_category == doc] && [semantic == hotel bill total]` | No | 40.2 s |
| D02 | simple / clear | How much did I pay for fuel? | `[query_category == doc] && [semantic == fuel payment amount]` | No | 40.4 s |
| D03 | medium / clear | What is my passport number in the passport photo? | `[query_category == doc] && [[mime type == photos] && [semantic == passport number]]` | No | 40.8 s |
| D04 | medium / clear | When does my driving licence expire? | `[query_category == doc] && [semantic == driving licence expiry date]` | No | 40.3 s |
| D05 | medium / clear | What coupon code is shown, and when does it expire? | `[query_category == doc] && [semantic == coupon code and expiry date]` | No | 41.4 s |
| D06 | medium / typo | How much did I spend on the odyssy movie ticket? | `[query_category == doc] && [semantic == Odyssey movie ticket price]` | No | 40.4 s |
| D07 | medium / clear | What was the Wi-Fi password on the hotel card? | `[query_category == doc] && [semantic == hotel card Wi-Fi password]` | No | 40.5 s |
| D08 | complex / clear | Find the booking reference and departure gate on my flight ticket. | `[query_category == doc] && [semantic == flight ticket booking reference and departure gate]` | No | 40.7 s |
| D09 | complex / moderate | Which restaurant receipt shows the highest total from last month? | `[query_category == doc] && [[semantic == restaurant receipt] && [from_date == 2026-06-01] && [to_date == 2026-06-30] SORT_DATE]` | No | 43.4 s |
| D10 | complex / clear | From the electricity bills photographed between June and August 2025, which account number and amount appear on the latest bill? | `[query_category == doc] && [[mime type == photos] && [semantic == electricity bill] && [from_date == 2025-06-01] && [to_date == 2025-08-31] SORT_DATE]` | No | 44.2 s |

Every row passed.

## Freeze checkpoint

QP is frozen after this audit. Downstream indexing, search, Context Picker,
answer-generation, and follow-up work must not modify these planner files unless
a new QP defect is separately reproduced and the freeze is explicitly reopened.

| Artifact | SHA-256 |
|---|---|
| `QueryPlannerRuntime.kt` | `4bfc7afa2a6bf3067be2d87e94eeb3a73fa60a27959936423aba49b835db85b2` |
| `QueryScope.kt` | `2cc3762965422dcf068bf09fc1d1a000cbd62f280a37f9c541a10a0b50f11edd` |
| `QueryExecutionSpec.kt` | `46b864716704dd0047f509860e695501e54d5288823aa1d3260cec289c1525b0` |
| `QueryPlan.kt` | `ce8a62bfeb8a910ea82de3a842223d285d72a06e717bdb7256dbfefe1e838c65` |
| Installed debug APK | `a30c173054caefbc22231e14ed0c3acef423ac1159be77bbb7596da28e53dcd1` |
| Instrumentation APK | `9532612f80c9f698758073bbc1280b8185d6d55942505e96ef6177f73fb61073` |

Validation before the freeze:

```bash
/tmp/ask-gradle-nXXZOz/gradle-8.13/bin/gradle \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest

adb shell am instrument -w -r \
  -e class 'com.ravi.askgalaxy.QueryPlannerOnlyAuditTest' \
  com.ravi.askgalaxy.test/androidx.test.runner.AndroidJUnitRunner
```

Final instrumentation result: `OK (5 tests)`, where each category test executes
and validates ten queries.
