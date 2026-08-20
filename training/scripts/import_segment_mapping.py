#!/usr/bin/env python3
"""Import segment.mapping JSON into audiences.csv-compatible rows.

Rules for generated fields:
- client_name: top-level JSON key
- industry: CLI arg, defaults to CPG
- audience_id: deterministic synthetic id per attribute
- audience_name: attributeName
- segment_id: taxonomyId
- segment_name: business label from attributeName + nodeValue
- created_at: deterministic pseudo-random date
- distributed: deterministic pseudo-random boolean

The importer is idempotent for a client: existing rows for imported clients
are removed before new rows are written back.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import re
from datetime import date, timedelta
from pathlib import Path

HEADER = [
    "client_name",
    "industry",
    "audience_id",
    "audience_name",
    "segment_id",
    "segment_name",
    "created_at",
    "distributed",
]

VALUE_ALIASES = {
    "Y": "Yes",
    "N": "No",
    "F": "Female",
    "M": "Male",
    "O": "Other",
    "U": "Unknown",
}


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parent.parent / "data"
    p = argparse.ArgumentParser(description="Import segment.mapping into audiences.csv")
    p.add_argument("--mapping", type=Path, default=root / "segment.mapping")
    p.add_argument("--output", type=Path, default=root / "audiences.csv")
    p.add_argument("--industry", type=str, default="CPG")
    p.add_argument("--as-of", type=str, default="2026-08-19")
    p.add_argument("--seed", type=int, default=819)
    return p.parse_args()


def stable_rng(seed: int, *parts: str) -> random.Random:
    raw = "|".join([str(seed), *parts]).encode("utf-8")
    digest = hashlib.sha256(raw).hexdigest()
    return random.Random(int(digest[:16], 16))


def humanize_token(text: str) -> str:
    text = (text or "").strip()
    if not text:
        return ""
    if text in VALUE_ALIASES:
        return VALUE_ALIASES[text]
    if text.isdigit():
        return text
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    spaced = spaced.replace("_", " ").replace("-", " ")
    return " ".join(spaced.split()).lower()


def humanize_attribute(attribute_name: str) -> str:
    return humanize_token(attribute_name)


def humanize_node_value(attribute_name: str, node_value: str) -> str:
    raw = (node_value or "").strip()
    if not raw:
        return "unknown"
    if raw in VALUE_ALIASES:
        return VALUE_ALIASES[raw]
    if raw.isdigit():
        attr = humanize_attribute(attribute_name)
        if "frequency" in attr or "count" in attr or "size" in attr or "age" in attr:
            return raw
        return raw
    return humanize_token(raw)


def format_segment_name(attribute_name: str, node_value: str) -> str:
    attr = humanize_attribute(attribute_name)
    value = humanize_node_value(attribute_name, node_value)
    if not attr:
        return value
    if not value or value == "unknown":
        return attr
    if value.isdigit():
        if "frequency" in attr or "per year" in attr or "count" in attr:
            return f"{attr}: {value} per year"
        if "size" in attr or "household" in attr:
            return f"{attr}: {value}"
        if "age" in attr:
            return f"{attr}: {value}"
        return f"{attr}: {value}"
    return f"{attr}: {value}"


def slug_name(text: str) -> str:
    return text.replace("_", " ").strip() or "Mapped Attribute"


def random_created_at(seed: int, as_of: date, client: str, audience_name: str) -> str:
    rng = stable_rng(seed, client, audience_name, "created_at")
    days_back = rng.randint(0, 365)
    return (as_of - timedelta(days=days_back)).isoformat()


def random_distributed(seed: int, client: str, taxonomy_id: str) -> str:
    rng = stable_rng(seed, client, taxonomy_id, "distributed")
    return "true" if rng.random() < 0.65 else "false"


def build_rows(
    payload: dict[str, list[dict]],
    *,
    industry: str,
    as_of: date,
    seed: int,
) -> tuple[list[list[str]], set[str]]:
    rows: list[list[str]] = []
    imported_clients: set[str] = set()
    for client_name, attributes in payload.items():
        imported_clients.add(client_name)
        for idx, attribute in enumerate(attributes, start=1):
            audience_name = str(attribute.get("attributeName") or "").strip()
            if not audience_name:
                audience_name = f"mapped_attribute_{idx}"
            audience_id = f"MAP-{idx:06d}"
            created_at = random_created_at(seed, as_of, client_name, audience_name)
            node_list = attribute.get("nodeVOList") or []
            for node in node_list:
                taxonomy_id = str(node.get("taxonomyId") or "").strip()
                if not taxonomy_id:
                    continue
                node_value = str(node.get("nodeValue") or "").strip()
                segment_name = format_segment_name(audience_name, node_value)
                distributed = random_distributed(seed, client_name, taxonomy_id)
                rows.append(
                    [
                        client_name,
                        industry,
                        audience_id,
                        slug_name(audience_name),
                        taxonomy_id,
                        segment_name,
                        created_at,
                        distributed,
                    ]
                )
    return rows, imported_clients


def read_existing(path: Path) -> list[list[str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        data = list(reader)
    if not data:
        return []
    if data[0] != HEADER:
        raise SystemExit(f"Unexpected header in {path}")
    return data[1:]


def write_csv(path: Path, rows: list[list[str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)
        writer.writerows(rows)


def main() -> None:
    args = parse_args()
    payload = json.loads(args.mapping.read_text(encoding="utf-8"))
    as_of = date.fromisoformat(args.as_of)
    imported_rows, imported_clients = build_rows(
        payload,
        industry=args.industry,
        as_of=as_of,
        seed=args.seed,
    )
    existing_rows = read_existing(args.output)
    kept_rows = [row for row in existing_rows if row and row[0] not in imported_clients]
    final_rows = kept_rows + imported_rows
    write_csv(args.output, final_rows)
    print(
        f"Imported {len(imported_rows)} rows from {args.mapping.name} "
        f"for clients={sorted(imported_clients)} into {args.output}"
    )
    print(f"Final row count (excluding header): {len(final_rows)}")
    if imported_rows:
        print("sample segment_name:", imported_rows[0][5])
        print("sample gender segment_name:", next(r[5] for r in imported_rows if r[3] == "Gender"))


if __name__ == "__main__":
    main()
