#!/usr/bin/env python3
"""Small laptop benchmark for Schemer's public ONNX extraction graphs.

This intentionally mirrors the public Schemer demo pipeline instead of treating
the model as a generative LLM. It uses only local model files and synthetic,
non-sensitive benchmark records by default.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


SEP = " ||| "
TODAY = "2026-08-12"
BIO_O, BIO_B, BIO_I = 0, 1, 2
CLASS_VALUES = (
    "driving licence", "passport", "invoice", "insurance policy",
    "movie or event ticket", "flight train bus journey",
    "coupon or voucher", "vehicle registration number plate",
    "text message", "other document or record",
)
CLASS_CANONICAL = (
    "driver_license", "passport", "invoice", "insurance", "ticket",
    "transport", "coupon", "vehicle_number_plate", "message", "other",
)


@dataclass
class Field:
    type: str
    describe: str = ""
    values: tuple[str, ...] = ()


@dataclass
class Example:
    name: str
    text: str
    schema: dict[str, Field]
    expected: dict[str, Any]


def label(*values: str, describe: str = "") -> Field:
    return Field("label", describe, values)


def string(describe: str) -> Field:
    return Field("string", describe)


def number(describe: str) -> Field:
    return Field("number", describe)


def datetime_field(describe: str) -> Field:
    return Field("datetime", describe)


def examples() -> list[Example]:
    classes = CLASS_VALUES
    return [
        Example(
            "driver_license",
            "Driving Licence Number DL-0420110123456. Name Ravi Sankar. Date of Issue 2020-01-10. Date of Expiry 2040-01-09.",
            {"record_class": label(*classes, describe="document category"), "driver_license_number": string("driving licence number"), "name": string("licence holder name"), "issue_date": datetime_field("issue date"), "expiry_date": datetime_field("expiry date")},
            {"record_class": "driver_license", "driver_license_number": "DL-0420110123456", "name": "Ravi Sankar", "issue_date": "2020-01-10T00:00", "expiry_date": "2040-01-09T00:00"},
        ),
        Example(
            "passport",
            "Passport No P1234567. Name Asha Rao. Nationality Indian. Date of Issue 2022-03-04. Date of Expiry 2032-03-03.",
            {"record_class": label(*classes, describe="document category"), "passport_number": string("passport number"), "name": string("passport holder name"), "nationality": string("nationality"), "issue_date": datetime_field("issue date"), "expiry_date": datetime_field("expiry date")},
            {"record_class": "passport", "passport_number": "P1234567", "name": "Asha Rao", "nationality": "Indian", "issue_date": "2022-03-04T00:00", "expiry_date": "2032-03-03T00:00"},
        ),
        Example(
            "invoice",
            "Invoice INV-77 from Bright Store. Total amount INR 1250. Invoice date 2025-06-12. Payment due 2025-06-30.",
            {"record_class": label(*classes, describe="document category"), "invoice_number": string("invoice number"), "seller": string("seller or merchant"), "total_amount": number("total amount"), "issue_date": datetime_field("invoice date"), "due_date": datetime_field("payment due date")},
            {"record_class": "invoice", "invoice_number": "INV-77", "seller": "Bright Store", "total_amount": 1250, "issue_date": "2025-06-12T00:00", "due_date": "2025-06-30T00:00"},
        ),
        Example(
            "insurance",
            "Tata AIG vehicle insurance policy POL-991. Policy period 2025-07-01 to 2026-06-30. Premium INR 8400.",
            {"record_class": label(*classes, describe="document category"), "policy_number": string("insurance policy number"), "insurer": string("insurance company"), "issue_date": datetime_field("policy start or issue date"), "expiry_date": datetime_field("policy expiry date"), "premium": number("insurance premium")},
            {"record_class": "insurance", "policy_number": "POL-991", "insurer": "Tata AIG", "issue_date": "2025-07-01T00:00", "expiry_date": "2026-06-30T00:00", "premium": 8400},
        ),
        Example(
            "ticket",
            "Movie Ticket: Odyssey. Venue Screen 3. Date 2025-08-20 at 18:30. Seat F12. Price INR 350.",
            {"record_class": label(*classes, describe="document category"), "title": string("movie or event title"), "venue": string("event venue"), "date": datetime_field("event date"), "seat_number": string("seat number"), "cost": number("ticket cost")},
            {"record_class": "ticket", "title": "Odyssey", "venue": "Screen 3", "date": "2025-08-20T18:30", "seat_number": "F12", "cost": 350},
        ),
        Example(
            "transport",
            "Flight AI-101 from Bengaluru to Delhi. Travel date 2025-09-14. Passenger Ravi Sankar. Seat 12A.",
            {"record_class": label(*classes, describe="document category"), "transport_type": string("type of transport"), "from": string("departure location"), "to": string("arrival location"), "date_of_travel": datetime_field("travel date"), "passenger": string("passenger name"), "seat_number": string("seat number")},
            {"record_class": "transport", "transport_type": "Flight", "from": "Bengaluru", "to": "Delhi", "date_of_travel": "2025-09-14T00:00", "passenger": "Ravi Sankar", "seat_number": "12A"},
        ),
        Example(
            "coupon",
            "Fresh Mart coupon SAVE20 gives 20 percent discount. Expires 2025-12-31.",
            {"record_class": label(*classes, describe="document category"), "title": string("coupon or promotion title"), "code": string("coupon code"), "discount_percent": number("discount percentage"), "expiry_date": datetime_field("coupon expiry date")},
            {"record_class": "coupon", "title": "Fresh Mart coupon", "code": "SAVE20", "discount_percent": 20, "expiry_date": "2025-12-31T00:00"},
        ),
        Example(
            "vehicle_number_plate",
            "Vehicle model Honda City. Registration number KA01AB1234.",
            {"record_class": label(*classes), "vehicle_model": string("vehicle model"), "vehicle_number": string("vehicle registration or number plate")},
            {"record_class": "vehicle_number_plate", "vehicle_model": "Honda City", "vehicle_number": "KA01AB1234"},
        ),
        Example(
            "message",
            "Message from Airtel: 2 missed calls received on 30-03-2025 while your number was unreachable.",
            {"record_class": label(*classes, describe="document category"), "sender": string("message sender"), "message_date": datetime_field("message date"), "description": string("one sentence describing the message")},
            {"record_class": "message", "sender": "Airtel", "message_date": "30-03-2025"},
        ),
        Example(
            "other",
            "School transfer certificate: student attended 21 days. Date of issue 28-Mar-2025. Character and Conduct Good.",
            {"record_class": label(*classes, describe="document category"), "document_type": string("document type"), "issue_date": datetime_field("issue date"), "conduct": string("character or conduct")},
            {"record_class": "other", "document_type": "School transfer certificate", "issue_date": "2025-03-28T00:00", "conduct": "Good"},
        ),
    ]


class Schemer:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.tok = Tokenizer.from_file(str(root / "tokenizer" / "tokenizer.json"))
        onnx = root / "onnx"
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        opts.inter_op_num_threads = 1
        self.encoder = ort.InferenceSession(str(onnx / "encoder_web4e4.onnx"), opts, providers=["CPUExecutionProvider"])
        self.reader = ort.InferenceSession(str(onnx / "reader.onnx"), opts, providers=["CPUExecutionProvider"])
        self.heads = ort.InferenceSession(str(onnx / "heads.onnx"), opts, providers=["CPUExecutionProvider"])
        self.label_head = ort.InferenceSession(str(onnx / "label_head.onnx"), opts, providers=["CPUExecutionProvider"])
        self.dt_head = ort.InferenceSession(str(onnx / "dt_head.onnx"), opts, providers=["CPUExecutionProvider"])

    def encode(self, text: str, special: bool = True) -> tuple[list[int], np.ndarray]:
        e = self.tok.encode(text, add_special_tokens=special)
        ids = np.asarray(e.ids, dtype=np.int64)
        hidden = self.encoder.run(["hidden"], {"input_ids": ids[None, :], "attention_mask": np.ones((1, len(ids)), dtype=np.int64)})[0][0]
        return e.ids, hidden

    @staticmethod
    def field_desc(name: str, spec: Field) -> str:
        return f"{name} ({spec.describe})" if spec.describe else f"{name} : {spec.type}"

    @staticmethod
    def schema_summary(name: str, spec: Field) -> str:
        suffix = f"({spec.describe})" if spec.describe else ""
        return f"{name}:{spec.type}{suffix}"

    def text_start(self, joint_ids: list[int], text: str) -> int:
        tids = self.tok.encode(text, add_special_tokens=False).ids
        needle = tids[1:min(6, len(tids))]
        if needle:
            for i in range(1, len(joint_ids) - len(needle) + 1):
                if joint_ids[i:i + len(needle)] == needle:
                    return i - 1
        return max(1, len(joint_ids) - len(tids) - 1)

    def query(self, query: str) -> tuple[list[int], np.ndarray]:
        return self.encode(query, True)

    def reader_run(self, hidden: np.ndarray, region: np.ndarray, query: str) -> tuple[np.ndarray, np.ndarray]:
        q_ids, q_hidden = self.query(query)
        q_states = np.zeros((32, 768), dtype=np.float32)
        q_len = min(32, len(q_ids))
        q_states[:q_len] = q_hidden[:q_len]
        out = self.reader.run(None, {
            "text_states": hidden.astype(np.float32),
            "query_states": q_states,
            "text_mask": region.astype(bool),
            "query_mask": np.array([i < q_len for i in range(32)], dtype=bool),
        })
        return out[0], out[1]

    def decode_span(self, ids: list[int], span: tuple[int, int]) -> str:
        a, b = max(0, span[0] - 1), span[1]
        return self.tok.decode(ids[a:b], skip_special_tokens=True).strip()

    @staticmethod
    def spans(logits: np.ndarray, mask: np.ndarray) -> list[tuple[int, int]]:
        result: list[tuple[int, int]] = []
        cur: list[int] | None = None
        for i, row in enumerate(logits):
            if not mask[i]:
                if cur: result.append((cur[0], cur[1])); cur = None
                continue
            tag = int(np.argmax(row))
            if tag == BIO_B:
                if cur: result.append((cur[0], cur[1]))
                cur = [i, i]
            elif tag == BIO_I and cur:
                cur[1] = i
            elif cur:
                result.append((cur[0], cur[1])); cur = None
        if cur: result.append((cur[0], cur[1]))
        return result

    def extract(self, text: str, schema: dict[str, Field]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for name, spec in schema.items():
            summary = self.schema_summary(name, spec)
            joint = f"today={TODAY}{SEP}{summary}{SEP}{text}"
            ids, hidden = self.encode(joint, True)
            start = self.text_start(ids, text)
            region = np.zeros(len(ids), dtype=np.uint8)
            region[start:] = 1
            states, rep = self.reader_run(hidden, region, self.field_desc(name, spec))
            if spec.type == "label":
                embs = []
                for value in spec.values:
                    _, h = self.query(value)
                    embs.append(h.mean(axis=0))
                scores = self.label_head.run(None, {"reader_rep": rep.astype(np.float32), "value_embs": np.asarray(embs, dtype=np.float32)})[0]
                result[name] = spec.values[int(np.argmax(scores))]
                continue
            if spec.type == "datetime":
                q_ids, q_hidden = self.query(f"{name}: datetime — {spec.describe} or null")
                q_states = np.zeros((32, 768), dtype=np.float32)
                q_len = min(32, len(q_ids))
                q_states[:q_len] = q_hidden[:q_len]
                dt = self.dt_head.run(None, {
                    "text_states": hidden.astype(np.float32),
                    "query_states": q_states,
                    "text_mask": region.astype(bool),
                    "query_mask": np.array([i < q_len for i in range(32)], dtype=bool),
                })
                null_prob = 1.0 / (1.0 + math.exp(-float(dt[6])))
                if null_prob > 0.5:
                    result[name] = None
                else:
                    year = 2026 + int(np.argmax(dt[9])) - 128
                    month = int(np.argmax(dt[5])) + 1
                    day = int(np.argmax(dt[0])) + 1
                    hour = int(np.argmax(dt[2]))
                    minute = int(np.argmax(dt[4]))
                    result[name] = f"{year:04d}-{month:02d}-{day:02d}T{hour:02d}:{minute:02d}"
                continue
            h = self.heads.run(None, {"states": states.astype(np.float32), "reader_rep": rep.astype(np.float32), "text_mask": region.astype(bool)})
            if spec.type == "string":
                if int(np.argmax(h[3])) == 0:
                    result[name] = None
                    continue
                candidates = self.spans(h[0], region)
                result[name] = self.decode_span(ids, max(candidates, key=lambda s: float(np.mean(np.max(h[0][s[0]:s[1] + 1], axis=1))))) if candidates else None
            elif spec.type == "number":
                starts, ends = h[5], h[6]
                p = int(np.argmax(np.where(region, starts, -1e30)))
                e = min(len(ids) - 1, p)
                if p < len(ends):
                    e = int(np.argmax(np.where(np.arange(len(ids)) >= p, ends, -1e30)))
                span = self.decode_span(ids, (p, e))
                m = re.search(r"-?\d[\d, .]*", span)
                if not m:
                    result[name] = None
                else:
                    raw = m.group(0).replace(",", "").replace(" ", "")
                    try: result[name] = float(raw) if "." in raw else int(raw)
                    except ValueError: result[name] = None
            else:
                result[name] = None
        return result


def norm(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip().lower()


def canonical_class(value: Any) -> Any:
    try:
        return CLASS_CANONICAL[CLASS_VALUES.index(str(value))]
    except ValueError:
        return value


def run_synthetic(model: Schemer) -> None:
    rows = []
    for ex in examples():
        started = time.perf_counter()
        got = model.extract(ex.text, ex.schema)
        got["record_class"] = canonical_class(got.get("record_class"))
        elapsed = time.perf_counter() - started
        checks = {}
        for key, expected in ex.expected.items():
            actual = got.get(key)
            if isinstance(expected, (int, float)):
                checks[key] = actual is not None and abs(float(actual) - expected) < 1e-6
            else:
                checks[key] = norm(actual) == norm(expected)
        rows.append((ex, got, checks, elapsed))
        print(json.dumps({"example": ex.name, "seconds": round(elapsed, 3), "expected": ex.expected, "actual": got, "checks": checks}, ensure_ascii=False))
    all_checks = [ok for _, _, checks, _ in rows for ok in checks.values()]
    class_checks = [checks["record_class"] for _, _, checks, _ in rows]
    print(json.dumps({
        "summary": "synthetic",
        "examples": len(rows),
        "field_exact": f"{sum(all_checks)}/{len(all_checks)}",
        "field_accuracy": round(sum(all_checks) / len(all_checks), 3),
        "class_exact": f"{sum(class_checks)}/{len(class_checks)}",
        "class_accuracy": round(sum(class_checks) / len(class_checks), 3),
        "mean_seconds": round(sum(x[3] for x in rows) / len(rows), 3),
    }))


def run_phone_samples(model: Schemer, gallery_db: Path, document_db: Path) -> None:
    samples = [
        ("message", "Message from Airtel: 2 missed call(s) received on 30-03-2025 while your number was unreachable."),
        ("invoice", "Dear Ravi, your 4w policy 6200407682 is due for renewal on 29-06-2025. Tata AIG General Insurance Company Limited."),
        ("transport", "Flight AI-101 from Bengaluru to Delhi. Travel date 2025-09-14. Passenger Ravi Sankar. Seat 12A."),
        ("driver_license", "Driving Licence Number DL-0420110123456. Name Ravi Sankar. Date of Issue 2020-01-10. Date of Expiry 2040-01-09."),
    ]
    for name, text in samples:
        schema = {"record_class": label(*CLASS_VALUES, describe="the category of this record"), "description": string("one sentence describing the record"), "identifier": string("main document or booking identifier"), "date": string("main date in the record")}
        started = time.perf_counter(); got = model.extract(text, schema); elapsed = time.perf_counter() - started
        got["record_class"] = canonical_class(got.get("record_class"))
        print(json.dumps({"sample": name, "seconds": round(elapsed, 3), "text": text, "actual": got}, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", type=Path, default=Path("/tmp/ask-galaxy-schemer-bench"))
    parser.add_argument("--phone-samples", action="store_true")
    parser.add_argument("--gallery-db", type=Path, default=Path("/tmp/gallery.db"))
    parser.add_argument("--document-db", type=Path, default=Path("/tmp/document_context.db"))
    args = parser.parse_args()
    model = Schemer(args.model_root)
    run_synthetic(model)
    if args.phone_samples:
        run_phone_samples(model, args.gallery_db, args.document_db)


if __name__ == "__main__":
    main()
