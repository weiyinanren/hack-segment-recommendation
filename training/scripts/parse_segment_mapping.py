#!/usr/bin/env python3
"""Flatten the tenant taxonomy export into one row per (attribute, value) segment.

Input is the raw mapping JSON: {tenant: [{attributeName, datasourceId,
datasourceName, nodeVOList: [{taxonomyId, nodeValue, ...}]}]}.

An attribute alone is not a targetable segment — "Household_Income_Range" only
becomes one once a value is attached. So each nodeVOList entry becomes its own
segment, keyed by its taxonomyId, named "AttributeName > nodeValue".
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent / "data"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Flatten taxonomy export to segment CSV")
    p.add_argument("--input", type=Path, default=DATA_DIR / "segment mapping.txt")
    p.add_argument("--output", type=Path, default=DATA_DIR / "segments_cpg_demo.csv")
    p.add_argument(
        "--tenant",
        type=str,
        default=None,
        help="Only export this top-level tenant key (default: all)",
    )
    return p.parse_args()


def flatten(payload: dict, tenant_filter: str | None) -> list[dict]:
    rows: list[dict] = []
    seen: set[str] = set()
    for tenant, attributes in payload.items():
        if tenant_filter and tenant != tenant_filter:
            continue
        for attr in attributes:
            attribute_name = str(attr.get("attributeName", "")).strip()
            if not attribute_name:
                continue
            for node in attr.get("nodeVOList") or []:
                taxonomy_id = str(node.get("taxonomyId", "")).strip()
                node_value = str(node.get("nodeValue", "")).strip()
                if not taxonomy_id or not node_value:
                    continue
                if node.get("disableFlag"):
                    continue
                if taxonomy_id in seen:
                    continue
                seen.add(taxonomy_id)
                rows.append(
                    {
                        "taxonomy_id": taxonomy_id,
                        "segment_name": f"{attribute_name} > {node_value}",
                        "attribute_name": attribute_name,
                        "node_value": node_value,
                        "node_type": str(attr.get("nodeType", "")).strip(),
                        "data_type": str(node.get("dataType", "")).strip(),
                        "datasource_name": str(attr.get("datasourceName", "")).strip(),
                        "datasource_id": str(attr.get("datasourceId", "")).strip(),
                        "tenant": tenant,
                    }
                )
    return rows


def main() -> None:
    args = parse_args()
    if not args.input.exists():
        raise SystemExit(f"Input not found: {args.input}")

    with args.input.open(encoding="utf-8") as f:
        payload = json.load(f)

    rows = flatten(payload, args.tenant)
    if not rows:
        raise SystemExit("No segments parsed — check --tenant or input shape")

    fields = [
        "taxonomy_id",
        "segment_name",
        "attribute_name",
        "node_value",
        "node_type",
        "data_type",
        "datasource_name",
        "datasource_id",
        "tenant",
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    attributes = {r["attribute_name"] for r in rows}
    print(f"Wrote {len(rows)} segments from {len(attributes)} attributes → {args.output}")
    print(f"  tenants: {sorted({r['tenant'] for r in rows})}")
    print(f"  example: {rows[0]['taxonomy_id']}, {rows[0]['segment_name']}, "
          f"{rows[0]['datasource_name']}, {rows[0]['datasource_id']}")


if __name__ == "__main__":
    main()
