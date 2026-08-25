#!/usr/bin/env python3
import csv
import sys
from collections import Counter
from pathlib import Path


PASS_IDS = {
    "003", "009", "011", "012", "017", "023", "028", "031", "032", "033",
    "037", "038", "041", "042", "045", "058", "066", "076", "077", "079",
    "080", "082", "083", "091", "096",
}

ACCEPTED_FAILURES = {
    "001": "dropped semantic sunset and broadened to every photo",
    "002": "misrouted visual beach as a hard location",
    "004": "added mandatory OCR keywords to a visual compound",
    "005": "added mandatory written-text intersection to a visual scene",
    "006": "split one compound scene and added mandatory OCR keywords",
    "007": "split the requested compound scene into separate constraints",
    "008": "dropped city and added mandatory OCR keywords",
    "010": "used answer intent, dropped red, and added a car keyword constraint",
    "013": "lost video scope and split the compound scene into unrelated terms",
    "014": "invented self-face intent and treated beach as location",
    "015": "dropped portrait and broadened to every photo",
    "018": "dropped the explicit picture or photo media scope",
    "019": "dropped photo scope and split the document-on-table concept",
    "020": "dropped blurry and broadened to every photo",
    "022": "used answer intent and resolved Ramani to the wrong face label chinni",
    "024": "omitted people_only for me only",
    "025": "used answer intent, omitted people_only, and chose the wrong face label",
    "027": "resolved Ramani to the wrong face label chinni",
    "035": "treated photos as semantic and OCR content instead of media scope",
    "036": "resolved Ramani to the wrong face label chinni",
    "039": "added outside-normal travel despite the named destination Ooty",
    "040": "dropped vacation travel intent and kept only photo media",
    "046": "invented self person Ravi for a person-free time query",
    "052": "encoded morning as visual semantic instead of a daypart filter",
    "053": "encoded night as visual semantic instead of a daypart filter",
    "054": "dropped sunset and retained only photo plus oldest sort",
    "055": "dropped explicit picture or photo media scope",
    "057": "dropped explicit self presence",
    "065": "resolved Ramani to the wrong face label chinni",
    "069": "omitted people_only for me alone",
    "073": "dropped receipt content and retained only photo plus date",
    "075": "did not require written happy birthday text and added a spurious text semantic",
    "078": "misread a temporal season as travel plus newest sort",
    "081": "added a mandatory OCR keyword to a broad visual birthday search",
    "084": "reduced the compound blue thing to blue",
    "085": "invented self, dropped photo media, and failed to use home as location",
    "089": "resolved Ramani to the wrong face label chinni",
    "092": "corrected the typo but resolved Ramani to the wrong face label chinni",
    "093": "added outside-normal travel despite the named destination Goa",
    "097": "resolved Ramani to the wrong face label meghana",
    "098": "used OCR keywords instead of person inclusion, exclusion, and people_only",
    "099": "misrouted visual beach as a hard location",
}


def bucket(case_id: str) -> str:
    value = int(case_id)
    if value <= 10:
        return "scene and object"
    if value <= 20:
        return "media and gallery OCR"
    if value <= 30:
        return "people and exclusivity"
    if value <= 40:
        return "location and travel"
    if value <= 55:
        return "time and sorting"
    if value <= 75:
        return "multi-constraint"
    if value <= 90:
        return "ambiguity"
    return "typo and colloquial"


def main() -> None:
    source_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    with source_path.open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))

    for row in rows:
        case_id = row["id"]
        if case_id in PASS_IDS:
            row["verdict"] = "PASS"
            row["reason"] = {
                "066": "complete executable plan; resolved q says oldestest but execution is correct",
                "080": "conservatively kept broad photo scope without inventing a location for there",
            }.get(case_id, "complete executable plan preserves the requested search constraints")
        elif row["planner_status"] == "VALIDATOR_FAILED":
            row["verdict"] = "FAIL"
            row["reason"] = "E2B output was structurally invalid and the single repair did not recover"
        else:
            row["verdict"] = "FAIL"
            row["reason"] = ACCEPTED_FAILURES[case_id]
        row["bucket"] = bucket(case_id)

    fieldnames = ["id", "bucket", "family", "query", "expected", "verdict", "reason"] + [
        name for name in rows[0].keys()
        if name not in {"id", "bucket", "family", "query", "expected", "verdict", "reason"}
    ]
    with output_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fieldnames, delimiter="\t")
        writer.writeheader()
        writer.writerows(rows)

    verdicts = Counter(row["verdict"] for row in rows)
    statuses = Counter(row["planner_status"] for row in rows)
    print(f"pass={verdicts['PASS']} fail={verdicts['FAIL']}")
    print(f"accepted={statuses['ACCEPTED']} validator_failed={statuses['VALIDATOR_FAILED']}")
    for name in dict.fromkeys(row["bucket"] for row in rows):
        selected = [row for row in rows if row["bucket"] == name]
        passed = sum(row["verdict"] == "PASS" for row in selected)
        print(f"{name}\t{passed}/{len(selected)}")


if __name__ == "__main__":
    main()
