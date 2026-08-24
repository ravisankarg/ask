# Query Planner V2.2: 100-query live-device audit

## Scope and method

- Device: Samsung SM-S938B
- Installed app: Ask Galaxy 0.10, versionCode 10
- Test window: 2026-08-22, approximately 13:26-14:17 IST
- Corpus: 100 fixed queries spanning gallery, OCR/documents, Messages, SMS, Calendar, Contacts, call logs, files, people, dates, travel, follow-ups, exclusions, compound semantics, and typos
- Evidence captured per query: final raw E2B JSON, repair attempts, executable Kotlin plan, gallery semantic highest score and accepted count, private-source result count/top-source evidence where available, and final UI match count
- The installed build, prompt, planner code, retrieval code, and indexes were frozen during the run. No prompt fix, reinstall, index clear, reindex, instrumentation, or app-data mutation was performed.

The strict correctness label compares the complete executable plan with the frozen expected characteristics. A structurally valid JSON plan is not counted as correct if it drops, invents, reverses, or changes an operator.

## KPI summary

| KPI | Result |
|---|---:|
| Total queries | 100 |
| First-pass structurally accepted | 52/100 (52.0%) |
| Queries that invoked repair | 48/100 (48.0%) |
| Repair recovery | 5/48 (10.4%) |
| Final structurally executable plans | 57/100 (57.0%) |
| Final validator failures | 43/100 (43.0%) |
| Strictly correct complete plans | 19/100 (19.0%) |
| Strict correctness among executable plans | 19/57 (33.3%) |
| Executable but semantically/operationally wrong | 38/57 (66.7%) |
| Exact intent label | 88/100 (88.0%) |
| Accepted plans with non-empty UI results | 35/57 (61.4%) |
| Correct plans with non-empty UI results | 13/19 (68.4%) |
| Accepted plans with zero UI results | 21/57 (36.8%); one UI count unparsed |
| Semantic-query rows | 26 |
| Semantic rows with at least one score above 0.10 | 24/26 (92.3%) |
| Semantic rows with non-empty final results | 15/26 (57.7%) |
| Highest-cosine distribution | mean 0.133660; median 0.129709; min 0.074731; max 0.185962 |
| Private searches with non-empty results | 3/35 (8.6%) |
| Fully correct typo queries | 0/7 (0%) |
| Correct follow-ups | 1/4 (25.0%) |
| Correct person/self-bearing queries | 10/34 (29.4%) |

The high intent-label score is misleading by itself: all 16 expected `answer:text` labels were copied correctly, but both `answer:person` and both `answer:location` cases were wrong. Only 3/5 `answer:date` labels were correct.

## Results by family

| Family | Cases | Structurally executable | Strictly correct | Non-empty UI |
|---|---:|---:|---:|---:|
| Gallery, semantics, people | 25 | 19 (76.0%) | 11 (44.0%) | 13 (52.0%) |
| Document and answer | 20 | 16 (80.0%) | 4 (20.0%) | 9 (45.0%) |
| Private sources and conditions | 25 | 11 (44.0%) | 1 (4.0%) | 4 (16.0%) |
| Follow-up chains | 5 | 2 (40.0%) | 1 (20.0%) | 2 (40.0%) |
| Temporal, travel, and sorting | 15 | 7 (46.7%) | 2 (13.3%) | 7 (46.7%) |
| Typos, exclusions, maximum composition | 10 | 2 (20.0%) | 0 (0%) | 0 (0%) |

The isolated relative/absolute temporal block (cases 076-087) had only one fully correct case: `photos since 6 months` (1/12, 8.3%). The travel set `recent trip`, `trip to Goa`, `recent vacation`, and `latest outing photos` was correct in 2/4.

## Main findings

| Finding | Live evidence |
|---|---|
| Structural reliability is too low | 43 final QP failures; repair recovered only 5 of 48 repair-triggered queries. Repairs commonly repeated the same malformed tuple. |
| Kotlin validation is necessary but not sufficient | 57 plans executed, but only 19 were fully correct. The validator accepted 38 wrong plans, including missing conditions, reversed sort, dropped source constraints, and overbroad media-only plans. |
| Source omission directly creates irrelevant records | `next calendar appointment` emitted no Calendar/future operator. Private search returned eight records led by four Messages at 0.6989; the first Calendar result ranked fifth at 0.6857. |
| Gallery follow-up can jump to Messages | After 40 `Ramani birthday photos` matches, `where were these taken` omitted `prev`, searched `where/taken`, and returned eight Messages with a top private score of 0.7575. |
| Raw V2 operators can be lost during execution | At least five rows were explicitly flagged where the executable plan materially diverged from raw E2B operators. For `SMS from last 7 days`, the correct raw SMS/date operators became generic `messages + message conversation`, losing the date. |
| Compound semantics are split into mandatory intersections | `videos of dogs running`, `photos indoors at night`, `Ravi cycling in rain at Goa`, and several document subjects were split. Individual semantic sets scored above threshold, but their intersection often became zero. |
| Document retrieval targets the requested attribute | `flight ticket departure time` searched `departure`; `when was passport scanned` searched `scanned`; `PAN card number` searched `card`. Exact keyword intersection then eliminated candidates. |
| Context contamination is severe | Standalone `passport number` imported `ravi` and `prev:true`; `my passport number` leaked the known-people vocabulary; a later fresh `Ravi passport number` incorrectly used a face-person operator and unrelated `prev:true`. |
| Literal date/number copying is unreliable | E2B changed `last 30 days` to 30 years, `10 am` to 109/0 am, and repeatedly corrupted ISO dates. The UI field retained the exact submitted strings. |
| Typo correction does not produce valid plans | None of seven typo cases was fully correct. Some words were normalized, but modes/operators were invalid or the semantic subject was wrong. |
| The 0.10 gallery cutoff is not the main overall blocker | Two otherwise-correct gallery semantic cases missed the cutoff: birthday at 0.074731 and cycling-in-rain-this-year at 0.099981. Most zero-result failures instead came from wrong QP constraints, source loss, or keyword/semantic intersections. |

## Representative outcomes

| ID | Query | Structural status | Strict result | Retrieval evidence |
|---:|---|---|---|---|
| 001 | recent trip | PASS | Correct | 7,589 outside-normal travel media |
| 004 | cycling in rain | PASS | Correct | highest 0.150546; 11 results |
| 006 | Ramani dancing photos | FAIL | Wrong | malformed through two repairs |
| 013 | beach photos | PASS | Wrong/overbroad | semantic beach dropped; all 12,073 photos |
| 026 | Ravi passport number | PASS | Correct | 23 final matches; private search 0 |
| 028 | passport number | FAIL | Wrong/context leak | invented Ravi and `prev:true` |
| 043 | who sent passport photo | PASS | Wrong | raw plan became message-only execution; 0 results |
| 050 | next calendar appointment | PASS | Wrong source | 8 private results, led by irrelevant Messages |
| 060 | SMS from last 7 days | PASS | Raw correct, execution wrong | date dropped; generic message semantic; 0 results |
| 074 | where were these taken | PASS | Wrong follow-up | 8 unrelated Messages, top 0.7575 |
| 079 | photos last 30 days | REPAIRED | Wrong | compiled as 30 years; 12,073 photos |
| 089 | recent vacation | PASS | Correct | 7,589 outside-normal travel media |
| 094 | reciept total ammount | PASS | Wrong | semantic `total`, typo retained; 0 results |
| 100 | recent photos of Ramani cycling in rain at Goa since 3 months | PASS | Incomplete | person/media/semantic/location/date correct, newest sort missing; 0 scoped results |

## Evidence files

- `qp_device_audit_100.tsv`: frozen 100-query corpus and expected characteristics
- `qp_device_audit_results.tsv`: all 100 raw outputs, executable plans, scores, counts, and per-row classification notes

No remediation is included in this report, per the audit instruction.
