#!/usr/bin/env python3
import csv
import re
import sys
from collections import Counter
from pathlib import Path


MARKER = re.compile(r"AskGalaxyAudit: AUDIT_START__(\d{3})__(.*)$")
RAW = re.compile(r"rawOutput=(.*)$")
INVALID = re.compile(r"invalid spec;.*?output=(.*)$")


def blank_attempt(case_id: str, query: str) -> dict[str, object]:
    return {
        "id": case_id,
        "submitted_query": query,
        "planner_status": "",
        "repairs": 0,
        "raw_e2b_output": "",
        "first_invalid_output": "",
        "accepted_summary": "",
        "executable_plan": "",
        "done": False,
    }


def main() -> None:
    corpus_path = Path(sys.argv[1])
    log_path = Path(sys.argv[2])
    output_path = Path(sys.argv[3])

    with corpus_path.open(encoding="utf-8", newline="") as source:
        cases = list(csv.DictReader(source, delimiter="\t"))
    valid_ids = {row["id"] for row in cases}

    active: dict[str, object] | None = None
    outcomes: dict[str, dict[str, object]] = {}
    attempt_counts: Counter[str] = Counter()

    for line in log_path.read_text(encoding="utf-8").splitlines():
        marker = MARKER.search(line)
        if marker:
            case_id, query = marker.groups()
            if case_id in valid_ids:
                attempt_counts[case_id] += 1
                active = blank_attempt(case_id, query)
            continue
        if active is None:
            continue

        if "raw-session generation:" in line:
            match = RAW.search(line)
            if match:
                active["raw_e2b_output"] = match.group(1).strip()
        elif "invalid spec;" in line and not active["done"]:
            active["repairs"] = int(active["repairs"]) + 1
            match = INVALID.search(line)
            if match and not active["first_invalid_output"]:
                active["first_invalid_output"] = match.group(1).strip()
        elif "plan accepted:" in line and not active["done"]:
            active["planner_status"] = "ACCEPTED"
            active["accepted_summary"] = line.split("plan accepted:", 1)[1].strip()
            active["done"] = True
            outcomes[str(active["id"])] = active
        elif "Executing QP spec:" in line and active["planner_status"] == "ACCEPTED":
            active["executable_plan"] = line.split("Executing QP spec:", 1)[1].strip()
        elif "query planning failed" in line and not active["done"]:
            active["planner_status"] = "VALIDATOR_FAILED"
            active["done"] = True
            outcomes[str(active["id"])] = active
        elif "FATAL EXCEPTION: main" in line and not active["done"]:
            active["planner_status"] = "PROCESS_LOST"
            active["done"] = True
            outcomes[str(active["id"])] = active

    missing = sorted(valid_ids - outcomes.keys())
    if missing:
        raise SystemExit(f"No terminal live attempt for: {', '.join(missing)}")
    non_qp = sorted(
        case_id
        for case_id, outcome in outcomes.items()
        if outcome["planner_status"] not in {"ACCEPTED", "VALIDATOR_FAILED"}
    )
    if non_qp:
        raise SystemExit(f"Final attempt was not a QP outcome for: {', '.join(non_qp)}")

    fields = [
        "id", "family", "query", "expected", "attempts", "planner_status", "repairs",
        "raw_e2b_output", "first_invalid_output", "accepted_summary", "executable_plan",
    ]
    with output_path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fields, delimiter="\t")
        writer.writeheader()
        for case in cases:
            case_id = case["id"]
            outcome = outcomes[case_id]
            writer.writerow({
                "id": case_id,
                "family": case["family"],
                "query": case["query"],
                "expected": case["expected"],
                "attempts": attempt_counts[case_id],
                "planner_status": outcome["planner_status"],
                "repairs": outcome["repairs"],
                "raw_e2b_output": outcome["raw_e2b_output"],
                "first_invalid_output": outcome["first_invalid_output"],
                "accepted_summary": outcome["accepted_summary"],
                "executable_plan": outcome["executable_plan"],
            })

    statuses = Counter(str(row["planner_status"]) for row in outcomes.values())
    print(f"outcomes={len(outcomes)} accepted={statuses['ACCEPTED']} "
          f"validator_failed={statuses['VALIDATOR_FAILED']}")
    print(f"attempts={sum(attempt_counts.values())} retried_cases="
          f"{sum(count > 1 for count in attempt_counts.values())}")


if __name__ == "__main__":
    main()
