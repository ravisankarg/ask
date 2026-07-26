# Ask Galaxy v0.2 validation report

Date: 2026-07-26
Device: Samsung SM-S938B (`RZCY92NW2AZ`)
Package: `com.ravi.askgalaxy` versionCode `2`, versionName `0.2`

## Scope

This checkpoint covers the complete current working tree:

- independent foreground model download and parallel gallery indexing;
- separate OCR, SigLIP, location, face, and episode progress/rebuild controls;
- bundled ML Kit OCR and isolated OCR migration;
- self-identity face tagging plus fixed finish/skip actions;
- five-way Gemma query routing and canonical execution AST;
- document retrieval with one SigLIP semantic branch plus one OCR-only
  2–6-keyword OR branch;
- semantic/OCR score fusion with a bonus for records found by both;
- at most eight answer records, full stored OCR/metadata text, text-only
  non-scenery answers, and at most four 512 px scenery images;
- v0.2 UI/version badge and refreshed architecture site.

## Automated checks

Command:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user" \
  /tmp/gradle-8.13/gradle-8.13/bin/gradle \
  :app:testDebugUnitTest :app:assembleDebug
```

Result: `BUILD SUCCESSFUL`; 46 actionable Gradle tasks, including the complete
debug JVM contract suite, Rust native build, APK packaging, and debug assembly.

The query/retrieval tests specifically verify:

- document-category routing for passports, licences, SSN, PAN, Aadhaar, IDs,
  passwords, DOB, age, marks, bills, receipts, and ticket prices;
- self label insertion into identity-document OCR keywords without adding a
  face-presence filter;
- OCR OR recall, duplicate-keyword removal, matched-term scoring, and the
  semantic/OCR fusion bonus. `[ocr == Ravi passport]` is explicitly tokenized
  as `Ravi OR passport`; neither word depends on the other being present;
- Odyssey ticket and broad monthly-spending hybrid plans;
- eight-record evidence selection and text-only document answer context;
- four-image scenery bounds and answer-output sanitation.

Additional checks:

- `git diff --check`: pass;
- `node --check docs/app.js`: pass;
- GitHub Pages rendered successfully in headless Chrome at 1440 px desktop and
  412 px mobile widths;
- APK signature: Android APK Signature Scheme v2 verified;
- APK zip alignment: verified.

## APK and data-preserving device check

Artifact:

```text
app/build/outputs/apk/debug/app-debug.apk
SHA-256 3f0943dc8bdb3d85896e1649dcd97d158449a097a9c334261abf38e8c8a0f6e4
```

Install command:

```bash
adb -s RZCY92NW2AZ install -r -d \
  app/build/outputs/apk/debug/app-debug.apk
```

Result: `Success`. No uninstall, package clear, or instrumentation was used.
The app launched normally with `MainActivity` focused and no fatal exception or
out-of-memory event in the launch log window.

Private-state checks before and after install were identical:

```text
gallery.db
2a804f5b9a26f7f193bf947f6fa8caa17e229c7c5a9ffbc383c2ae830170d539

files/indexes/siglip2-768-4bit.tvim
05f54d80eea6bf4cb92a1ad128a5fb40d892c59ed495c617af73bd5cb7ecae5d

files/models/gemma-4-E4B-it.litertlm
3,659,530,240 bytes
```

Private storage remained approximately 97 MB of databases and 6.3 GB of files.

## Remaining live validation

The new hybrid document plan is source- and JVM-contract-verified. The next
device quality gate is to rerun the representative document queries—especially
the Odyssey ticket and self-passport questions—and record the live QP output,
ranking, answer text, peak memory, and latency without clearing any index.
