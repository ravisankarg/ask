#!/usr/bin/env python3
import csv
import json
import re
import sys
from pathlib import Path


def main() -> None:
    corpus_path = Path(sys.argv[1])
    log_path = Path(sys.argv[2])
    output_path = Path(sys.argv[3])

    with corpus_path.open(encoding="utf-8", newline="") as source:
        cases = list(csv.DictReader(source, delimiter="\t"))
    by_id = {row["id"]: row for row in cases}
    skipped = {"021", "074", "078", "080", "098"}
    ordered_ids = [row["id"] for row in cases if row["id"] not in skipped]
    ordered_ids.extend(["021", "074", "078", "080", "098"])

    outcomes = []
    active = None
    raw_pattern = re.compile(r"rawOutput=(.*)$")
    invalid_pattern = re.compile(r"invalid spec;.*?output=(.*)$")
    for line in log_path.read_text(encoding="utf-8").splitlines():
        if "raw-session generation:" in line:
            match = raw_pattern.search(line)
            if not match:
                continue
            active = {
                "raw_e2b_output": match.group(1).strip(),
                "repairs": 0,
                "first_invalid_output": "",
                "planner_status": "",
                "accepted_summary": "",
                "executable_plan": "",
            }
        elif active is not None and "invalid spec;" in line:
            active["repairs"] += 1
            match = invalid_pattern.search(line)
            if match:
                active["first_invalid_output"] = match.group(1).strip()
        elif active is not None and "plan accepted:" in line:
            active["planner_status"] = "ACCEPTED"
            active["accepted_summary"] = line.split("plan accepted:", 1)[1].strip()
        elif active is not None and "Executing QP spec:" in line:
            active["executable_plan"] = line.split("Executing QP spec:", 1)[1].strip()
            outcomes.append(active)
            active = None
        elif active is not None and "query planning failed" in line:
            active["planner_status"] = "VALIDATOR_FAILED"
            outcomes.append(active)
            active = None

    if active is not None:
        raise SystemExit("Unfinished QP outcome at end of live log")
    if len(outcomes) != len(ordered_ids):
        raise SystemExit(f"Expected {len(ordered_ids)} outcomes, found {len(outcomes)}")

    fields = [
        "id",
        "family",
        "query",
        "expected",
        "planner_status",
        "repairs",
        "raw_e2b_output",
        "first_invalid_output",
        "accepted_summary",
        "executable_plan",
    ]
    outcome_by_id = dict(zip(ordered_ids, outcomes))
    with output_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fields, delimiter="\t")
        writer.writeheader()
        for case in cases:
            case_id = case["id"]
            outcome = outcome_by_id[case_id]
            writer.writerow({
                "id": case_id,
                "family": case["family"],
                "query": case["query"],
                "expected": case["expected"],
                **outcome,
            })

    # Verify the chronological assignment against the model's resolved q. This
    # is advisory because typo cases legitimately rewrite q.
    mismatches = []
    for case_id, outcome in zip(ordered_ids, outcomes):
        try:
            resolved = json.loads(outcome["raw_e2b_output"])["q"]
        except Exception:
            continue
        expected_query = by_id[case_id]["query"]
        if resolved.lower() != expected_query.lower():
            mismatches.append((case_id, expected_query, resolved))
    print(f"outcomes={len(outcomes)} accepted={sum(o['planner_status'] == 'ACCEPTED' for o in outcomes)} "
          f"validator_failed={sum(o['planner_status'] == 'VALIDATOR_FAILED' for o in outcomes)}")
    print(f"resolved_query_differences={len(mismatches)}")
    for case_id, expected_query, resolved in mismatches:
        print(f"{case_id}\t{expected_query}\t=>\t{resolved}")


if __name__ == "__main__":
    main()
