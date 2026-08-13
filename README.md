# Ask Galaxy

Ask Galaxy is a private, on-device search and answer experience for a
personal digital life. A natural question can span gallery media, files,
messages, calls, and other indexed sources. The product turns that question
into scoped retrieval, shows the real merged result order, and then answers
from a small evidence set.

The design goal is simple: search remains inspectable and useful on its own;
answer generation is a grounded layer on top.

## End-to-end design

```text
Question
   ↓
Query understanding
   input: natural language + conversation state
   output: intent, source scope, positive/negative constraints, time, person,
           place, media type, and requested answer fields
   ↓
Scope compilation
   input: structured intent
   output: executable predicates with hard filters and explicit exclusions
   ↓
Parallel retrieval
   input: compiled scope + source indexes
   output: ranked candidates from each source
   ↓
Fusion and ranking
   input: per-source candidates + scope predicates
   output: one merged result order with stale records removed
   ↓
Evidence selection
   input: merged results + question
   output: bounded top evidence with full available text and metadata
   ↓
Answer and follow-ups
   input: question + evidence + optional visual context
   output: concise answer, sources, and diverse next questions
   ↓
Product UI
   input: planner state, timings, result order, answer state
   output: transparent progress, results, answer, and recovery actions
```

## High-level modules

| Module | Input | Output |
| --- | --- | --- |
| Source ingestion | Media, files, messages, calls, and source metadata | Stable records, change signatures, and source ownership |
| Preparation | New or changed records | OCR text, people/place/time metadata, searchable terms, semantic representations, and resumable progress |
| Query understanding | User question and conversation context | Intent category, source scope, positive terms, exclusions, and requested fields |
| Scope compiler | Structured intent | Hard predicates for source, person, place, time, media type, and negation |
| Source retrieval | Predicates and the relevant source index | Candidate matches with lexical, semantic, and structured scores |
| Fusion and ranking | Candidate lists from all sources | One merged order; constraints are applied before lexical or semantic expansion |
| Evidence selector | Merged results and question | Small, diverse evidence window with record identity preserved |
| Answer layer | Question, evidence text, metadata, and permitted images | Direct answer, grounded sources, and follow-up suggestions |
| UI state | Progress, query plan, timings, results, and answer events | Search bar, visible plan, merged results, answer, and recovery controls |

## Search-time flow

1. The user enters a natural question. The planner identifies what kind of
   information is being requested and which sources can answer it.
2. The scope compiler turns that intent into explicit predicates. Positive
   constraints are applied first; exclusions subtract matching records.
3. Retrieval runs independently in each eligible source so a slow or empty
   source does not block the others.
4. The fusion layer applies hard scope rules, removes stale media, and merges
   candidates into the actual cross-source order shown to the user.
5. The answer layer selects a bounded evidence set from that same order. It
   receives the question, complete available record text, metadata, and visual
   context when relevant.
6. A grounded review pass checks field-to-value relationships, competing
   records, exclusions, and answer completeness before the UI publishes text.
7. Follow-up questions start a fresh retrieval state while retaining only the
   natural conversation meaning required for the next turn.

## Index-time flow

Preparation is incremental and resumable. Each source keeps its own progress,
stable record identity, and rebuild boundary. A changed record is reprocessed;
unchanged records and unrelated indexes remain available. Removal detection
also cleans stale records so deleted media cannot continue appearing in the
result window.

## Privacy and correctness boundaries

- Search, indexing, OCR, metadata joins, query planning, and answer generation
  stay on the device.
- Source scope and explicit exclusions are hard retrieval constraints, not
  suggestions to the answer writer.
- The answer writer sees the evidence selected from the displayed merged order,
  not an unrelated hidden result set.
- Field labels remain attached to their values so nearby dates, numbers, or
  metadata cannot silently replace the requested fact.
- The UI can show the plan, timing stages, result order, sources, and answer
  state without exposing internal prompt or protocol text.

## Build

Use the repository's Android toolchain and a connected Android device or
emulator:

```bash
./gradlew assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

The install command upgrades the existing package in place. It does not remove
application storage or indexed data.

## Repository layout

- `app/src/main` — Android product code and source orchestration.
- `native/askgalaxy-native` — the native search boundary.
- `docs` — the public high-level design page.
- `app/src/test` and `app/src/androidTest` — retained contract and UI checks.

See the [Ask Galaxy design page](https://ravisankarg.github.io/ask/) for the
visual module/input/output flow.
