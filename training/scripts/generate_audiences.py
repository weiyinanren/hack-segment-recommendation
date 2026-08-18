#!/usr/bin/env python3
"""Generate ~100k-row synthetic audience training CSV.

Industries: CPG, OEM, Retail, HealthCheck, Dining
Seeded so reruns are reproducible.
"""

from __future__ import annotations

import argparse
import csv
import random
from datetime import date, timedelta
from pathlib import Path

# Shared gender-adjacent names so MiniLM / name_neighbors can link across clients.
GENDER_NAMES = [
    ("SG_FEMALE", "female"),
    ("SG_MISS", "miss"),
    ("SG_MS", "Ms."),
    ("SG_MADAM", "Madam"),
    ("SG_WOMEN", "women"),
    ("SG_LADY", "lady"),
]

INDUSTRIES: dict[str, dict] = {
    "CPG": {
        "clients": ["UnileverDemo", "PAndGRetail", "NestleBrand", "CPGStart"],
        "segments": [
            ("CPG_MOM", "moms with kids"),
            ("CPG_BABY", "baby care buyers"),
            ("CPG_SNACK", "snack lovers"),
            ("CPG_ORG", "organic shoppers"),
            ("CPG_VALUE", "value seekers"),
            ("CPG_PREM", "premium grocery"),
            ("CPG_CLEAN", "household cleaners"),
            ("CPG_BEAUTY", "beauty shoppers"),
            ("CPG_SKIN", "skincare"),
            ("CPG_PET", "pet owners"),
            ("CPG_HEALTH", "health conscious"),
            ("CPG_BRAND", "brand loyalists"),
        ],
        "bundles": [
            ["CPG_MOM", "CPG_BABY", "CPG_ORG"],
            ["CPG_SNACK", "CPG_VALUE", "CPG_BRAND"],
            ["CPG_BEAUTY", "CPG_SKIN", "SG_FEMALE"],
            ["CPG_PREM", "CPG_ORG", "CPG_HEALTH"],
            ["CPG_CLEAN", "CPG_VALUE", "CPG_MOM"],
        ],
        "audience_prefixes": ["Mom Shop", "Snack Promo", "Beauty CPG", "Organic Wave", "Household Core"],
    },
    "OEM": {
        "clients": ["AutoOEM1", "PartsMaker", "VehicleOEM", "TierOneSup"],
        "segments": [
            ("OEM_SUV", "SUV buyers"),
            ("OEM_EV", "EV intenders"),
            ("OEM_LUX", "luxury"),
            ("OEM_NEW", "new car intenders"),
            ("OEM_FLEET", "fleet managers"),
            ("OEM_PARTS", "aftermarket parts"),
            ("OEM_TRUCK", "light truck owners"),
            ("OEM_USED", "used car shoppers"),
            ("OEM_SAFE", "safety focused"),
            ("OEM_TECH", "connected car fans"),
            ("OEM_DEAL", "dealership visitors"),
            ("OEM_LEASE", "lease shoppers"),
        ],
        "bundles": [
            ["OEM_SUV", "OEM_LUX", "SG_MISS"],
            ["OEM_EV", "OEM_NEW", "OEM_TECH"],
            ["OEM_FLEET", "OEM_TRUCK", "OEM_PARTS"],
            ["OEM_USED", "OEM_DEAL", "OEM_LEASE"],
            ["OEM_SAFE", "OEM_SUV", "OEM_NEW"],
        ],
        "audience_prefixes": ["Launch SUV", "EV Campaign", "Fleet Q3", "Dealer Traffic", "Luxury OEM"],
    },
    "Retail": {
        "clients": ["RetailCo", "SuperMart", "FashionHub", "ConveniencePlus"],
        "segments": [
            ("RTL_BEAUTY", "beauty shoppers"),
            ("RTL_SKIN", "skincare"),
            ("RTL_COSM", "cosmetics"),
            ("RTL_FASH", "women fashion"),
            ("RTL_MALL", "mall shoppers"),
            ("RTL_ONLINE", "online retail buyers"),
            ("RTL_VIP", "loyalty VIP"),
            ("RTL_PROMO", "promo hunters"),
            ("RTL_GROC", "grocery frequent"),
            ("RTL_HOME", "home decor"),
            ("RTL_SPORT", "sports apparel"),
            ("RTL_KIDS", "kids apparel"),
        ],
        "bundles": [
            ["RTL_BEAUTY", "RTL_SKIN", "RTL_COSM"],
            ["RTL_FASH", "SG_MADAM", "RTL_VIP"],
            ["RTL_MALL", "RTL_PROMO", "RTL_ONLINE"],
            ["RTL_GROC", "RTL_PROMO", "RTL_KIDS"],
            ["RTL_HOME", "RTL_VIP", "RTL_MALL"],
        ],
        "audience_prefixes": ["Beauty Week", "Fashion Drop", "Mall Traffic", "VIP Loyalty", "Promo Blast"],
    },
    "HealthCheck": {
        "clients": ["HealthCheckA", "MedExamB", "WellnessLab", "ClinicNet"],
        "segments": [
            ("HC_ANNUAL", "annual checkup"),
            ("HC_PREM", "premium physical"),
            ("HC_CORP", "corporate wellness"),
            ("HC_ELDER", "senior health"),
            ("HC_MATERN", "maternity checkup"),
            ("HC_CANCER", "cancer screening"),
            ("HC_CARDIO", "cardio screening"),
            ("HC_DIAB", "diabetes risk"),
            ("HC_EXEC", "executive physical"),
            ("HC_FAMILY", "family package"),
            ("HC_REPEAT", "repeat patients"),
            ("HC_ONLINE", "online booking"),
        ],
        "bundles": [
            ["HC_ANNUAL", "HC_FAMILY", "HC_ONLINE"],
            ["HC_PREM", "HC_EXEC", "HC_CARDIO"],
            ["HC_MATERN", "SG_WOMEN", "HC_FAMILY"],
            ["HC_ELDER", "HC_CANCER", "HC_CARDIO"],
            ["HC_CORP", "HC_ANNUAL", "HC_DIAB"],
        ],
        "audience_prefixes": ["Annual Exam", "Corp Wellness", "Senior Screen", "Maternity Pack", "Exec Physical"],
    },
    "Dining": {
        "clients": ["FoodChainA", "CafeGroup", "QSRBrand", "FineDineCo"],
        "segments": [
            ("FD_QSR", "fast food fans"),
            ("FD_CAFE", "coffee drinkers"),
            ("FD_DELIV", "food delivery"),
            ("FD_FINE", "fine dining"),
            ("FD_FAMILY", "family dining"),
            ("FD_LUNCH", "weekday lunch"),
            ("FD_NIGHT", "late night eaters"),
            ("FD_VEG", "vegetarian"),
            ("FD_SPICY", "spicy food lovers"),
            ("FD_TEA", "tea shop visitors"),
            ("FD_BUFFET", "buffet diners"),
            ("FD_LOYAL", "restaurant loyalty"),
        ],
        "bundles": [
            ["FD_QSR", "FD_DELIV", "FD_LUNCH"],
            ["FD_CAFE", "FD_TEA", "FD_LOYAL"],
            ["FD_FINE", "FD_FAMILY", "SG_LADY"],
            ["FD_NIGHT", "FD_QSR", "FD_DELIV"],
            ["FD_VEG", "FD_FINE", "FD_FAMILY"],
        ],
        "audience_prefixes": ["Lunch Rush", "Delivery Peak", "Cafe Regulars", "Family Dinner", "Late Night"],
    },
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Generate synthetic audience CSV")
    p.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "audiences.csv",
    )
    p.add_argument("--rows", type=int, default=100_000, help="Target CSV row count")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument(
        "--as-of",
        type=str,
        default="2026-08-12",
        help="Latest created_at date (YYYY-MM-DD)",
    )
    return p.parse_args()


def pick_basket(rng: random.Random, spec: dict, client: str, client_idx: int) -> list[tuple[str, str]]:
    """Build a 2–5 segment basket with industry bundles + occasional gender names."""
    id_to_name = dict(spec["segments"])
    for sid, name in GENDER_NAMES:
        id_to_name[sid] = name

    bundle = list(rng.choice(spec["bundles"]))
    # Each client privately "owns" a slightly different gender-adjacent name.
    gender = GENDER_NAMES[client_idx % len(GENDER_NAMES)]
    if rng.random() < 0.35:
        bundle.append(gender[0])

    extra = rng.sample(spec["segments"], k=min(2, len(spec["segments"])))
    ids = []
    seen = set()
    for sid in bundle + [s[0] for s in extra]:
        if sid in id_to_name and sid not in seen:
            seen.add(sid)
            ids.append(sid)
        if len(ids) >= rng.randint(2, 5):
            break
    if len(ids) < 2:
        ids = [spec["segments"][0][0], spec["segments"][1][0]]
    return [(sid, id_to_name[sid]) for sid in ids]


def main() -> None:
    args = parse_args()
    rng = random.Random(args.seed)
    as_of = date.fromisoformat(args.as_of)
    start = as_of - timedelta(days=400)

    industries = list(INDUSTRIES.keys())
    # Slightly uneven industry mix
    weights = [0.22, 0.18, 0.24, 0.18, 0.18]

    rows: list[list[str]] = []
    audience_seq = 0

    # Overshoot audiences until we hit target rows
    while len(rows) < args.rows:
        industry = rng.choices(industries, weights=weights, k=1)[0]
        spec = INDUSTRIES[industry]
        client_idx = rng.randrange(len(spec["clients"]))
        client = spec["clients"][client_idx]
        audience_seq += 1
        audience_id = f"{industry[:2]}-{audience_seq:06d}"
        prefix = rng.choice(spec["audience_prefixes"])
        audience_name = f"{prefix} {audience_seq % 9000 + 100}"

        created = start + timedelta(days=rng.randint(0, (as_of - start).days))
        # Newer audiences more likely distributed
        age_ratio = (as_of - created).days / 400
        dist_p = 0.75 if age_ratio < 0.3 else 0.45
        distributed = "true" if rng.random() < dist_p else "false"

        basket = pick_basket(rng, spec, client, client_idx)
        for sid, sname in basket:
            rows.append(
                [
                    client,
                    industry,
                    audience_id,
                    audience_name,
                    sid,
                    sname,
                    created.isoformat(),
                    distributed,
                ]
            )
            if len(rows) >= args.rows:
                break

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(
            [
                "client_name",
                "industry",
                "audience_id",
                "audience_name",
                "segment_id",
                "segment_name",
                "created_at",
                "distributed",
            ]
        )
        writer.writerows(rows)

    # Summary
    from collections import Counter

    ind_c = Counter(r[1] for r in rows)
    cli_c = Counter(r[0] for r in rows)
    print(f"Wrote {len(rows)} rows → {args.output}")
    print("industries:", dict(ind_c))
    print("clients:", len(cli_c), "audiences≈", audience_seq)


if __name__ == "__main__":
    main()
