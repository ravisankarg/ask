# Answer Generation Audit

Date: 2026-07-25
Target: Samsung Galaxy S25 Ultra (`SM-S938B`, Android 16)
App: `com.ravi.askgalaxy`
Scope: Context Picker output through public answer text. Follow-up quality is
intentionally deferred to the next module pass.

## Frozen upstream contract

The accepted Gemma-only QP and plan-validator sources were not edited during
this audit:

| Source | SHA-256 |
|---|---|
| `QueryPlannerRuntime.kt` | `4bfc7afa2a6bf3067be2d87e94eeb3a73fa60a27959936423aba49b835db85b2` |
| `QueryScope.kt` | `2cc3762965422dcf068bf09fc1d1a000cbd62f280a37f9c541a10a0b50f11edd` |
| `QueryExecutionSpec.kt` | `46b864716704dd0047f509860e695501e54d5288823aa1d3260cec289c1525b0` |
| `QueryPlan.kt` | `ce8a62bfeb8a910ea82de3a842223d285d72a06e717bdb7256dbfefe1e838c65` |

The completed OCR, location, search-result, and 200-item UI contracts were also
left intact.

## Architecture reviewed

```text
full ranked search set
  -> EvidenceBuilder joins persisted episode membership
  -> AnswerContextPicker applies category eligibility
  -> reserve relevance + requested-facet + unseen-episode coverage
  -> visual novelty fills remaining doc/scenary slots
  -> maximum 16 private records
  -> doc/scenary: one board containing up to 16 tiles and bounded face crops
  -> person/location/time: metadata-only records
  -> compact duplicate-aware joined prompt
  -> fresh Gemma answer conversation
  -> planner/task leak detection + one clean retry
  -> public answer parsing and sanitation
  -> 2-3 sentence answer; full public result grid remains unchanged
```

The picker remains separate from the public gallery. The app never renders the
private 16-record set or replaces the up-to-200 result browser with it.

## Defects found and fixed

1. `AnswerTextSanitizer` appended “You can browse the matching moments below.”
   to every valid one-sentence answer. The synthetic boilerplate is removed.
2. Reasoning tags and task echoes were not explicitly removed. Complete or
   dangling `<think>` protocol, `ANSWER_TASK`, final-answer wrappers, and code
   fences are now stripped before length limiting.
3. The answer prompt repeated the same grounding rules several times and
   repeated identical metadata values for up to 16 records. Static instructions
   were consolidated and exact duplicate structured rows now share a label
   list. OCR remains query-centered and bounded.
4. CPU-only Gemma vision exceeded the Samsung process guard. The first document
   audit was killed after Samsung measured about `7,776,564 KiB` anonymous
   memory against a `6,291,456 KiB` global-kill threshold. Language and QP stay
   on the verified CPU path; only the image encoder/adapter now uses the LiteRT
   GPU backend. The manifest declares the optional OpenCL/VNDK libraries and
   engine initialization falls back to CPU vision on unsupported devices.
5. Planner-shaped output was checked on the primary multimodal call but not on
   its text-only recovery call. Both paths now use the same checked generation
   function, one clean retry, and a grounded natural fallback.
6. Public sanitation could theoretically reduce a nonblank raw response to an
   empty string. It now switches to the grounded fallback rather than rendering
   an empty answer card.
7. The rare fallback always called matches “photos,” even for videos. It now
   uses photo, video, or gallery item from the actual selected MIME types.

## Category policy verified

| QP category | Private context | Visual board | Required grounding check |
|---|---|---:|---|
| `doc` | OCR-bearing image rows only | yes | answer overlaps meaningful selected OCR |
| `scenary` | non-document image rows only | yes | no OCR/document row enters the board |
| `person` | named-person rows | no | answer names a selected local person tag |
| `location` | geocoded rows | no | answer explicitly names a selected place |
| `time` | dated rows | no | answer states the selected capture day/year |

All cases additionally assert 1–16 context records, 1–16 public source records,
two or three concise sentences on the tested queries, no `G/C/E/F` IDs, no
planner schema, no reasoning tags, and no evidence/record boilerplate. Test
logs contain only aggregate counts and latency; private OCR, names, locations,
queries, answers, coordinates, and media IDs are not logged.

## Real-device result matrix

These tests bypass `QueryPlannerRuntime` and construct a canonical spec directly,
so they exercise only the frozen plan contract's downstream answer path.

| Category | Candidates | Context | Prompt chars / rows | Sentences | Before | Final | Change |
|---|---:|---:|---:|---:|---:|---:|---:|
| document receipt | 265 | 16 | 4,609 / 16 | 2 | 68,011 ms | 40,209 ms | -40.9% |
| beach scenery | 118 | 16 | 2,594 / 16 | 2 | 53,477 ms | 46,984 ms | -12.1% |
| common named person | 1,032 | 16 | 2,076 / 14 | 2 | 40,334 ms | 21,491 ms | -46.7% |
| common indexed place | 4,112 | 16 | 2,592 / 16 | 2 | 40,866 ms | 29,765 ms | -27.2% |
| common capture day | 201 | 16 | 2,435 / 10 | 2 | 68,061 ms | 26,156 ms | -61.6% |

The visual cases remain slower because one Gemma vision prefill is real work,
not because the Context Picker sends 16 separate model images. The request
contains one bounded board; all temporary bitmaps are recycled before text
decoding. The GPU split is supported by LiteRT-LM's Android Kotlin API and
keeps the QP/language backend on CPU:
<https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md>.

## Code review conclusions

- The episode database is used at the correct layer: indexing precomputes
  membership; query time chooses the best retrieved member per episode.
- Relevance cannot be entirely displaced by diversity: the first anchor is
  reserved, a second cross-episode anchor is attempted for a 16-slot budget,
  and only a bounded two-thirds coverage budget is spent before fill.
- Visual duplication is handled by native embedding diversity and episode
  coverage; structured duplication is additionally removed from the prompt.
- A failed image decode cannot shift G labels: answer records are rebuilt from
  the successfully decoded board order.
- One high-confidence crop per distinct named person is placed on the lower
  board only when applicable. Face crops are identity aids, not extra sources.
- OCR is sent only for `doc`; location is named when applicable; metadata-only
  categories cannot claim visible activities.
- Search results and answer sources are independent: answer generation never
  truncates or replaces the public result adapter.

## Remaining boundary

The answer module is closed for the verified target and category matrix.
Follow-up generation remains a separate module: its query usefulness,
deduplication, privacy sanitation, and click-through execution must be reviewed
next without reopening QP or answer-context selection.
