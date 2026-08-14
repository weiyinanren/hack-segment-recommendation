"""Industry → Top-N popular segments (optionally weighted)."""

from __future__ import annotations

from collections import defaultdict
from typing import Any

import pandas as pd


def build_popularity(
    df: pd.DataFrame,
    top_n: int = 50,
) -> dict[str, list[dict[str, Any]]]:
    """
    Aggregate segment selections per industry and keep Top-N.

    If ``sample_weight`` column exists, uses weighted sum; otherwise count=+1.
    Returns:
        { industry: [ {segmentId, count, weightSum, score}, ... ] }
        score is min-max normalized on weightSum within the industry (0..1).
    """
    work = df.copy()
    if "sample_weight" not in work.columns:
        work["sample_weight"] = 1.0

    grouped = (
        work.groupby(["industry", "segment_id"], as_index=False)
        .agg(
            count=("segment_id", "size"),
            weightSum=("sample_weight", "sum"),
        )
        .rename(columns={"segment_id": "segmentId"})
    )

    result: dict[str, list[dict[str, Any]]] = {}
    for industry, group in grouped.groupby("industry"):
        ranked = group.sort_values(
            ["weightSum", "count"], ascending=[False, False]
        ).head(top_n)
        max_w = float(ranked["weightSum"].max()) if len(ranked) else 1.0
        min_w = float(ranked["weightSum"].min()) if len(ranked) else 0.0
        denom = max(max_w - min_w, 1e-9)

        items = []
        for _, row in ranked.iterrows():
            score = (float(row["weightSum"]) - min_w) / denom
            items.append(
                {
                    "segmentId": str(row["segmentId"]),
                    "count": int(row["count"]),
                    "weightSum": round(float(row["weightSum"]), 6),
                    "score": round(score, 6),
                }
            )
        result[str(industry)] = items

    return result


def global_segment_weights(df: pd.DataFrame) -> dict[str, float]:
    """segment_id → weighted sum (falls back to raw count)."""
    work = df.copy()
    if "sample_weight" not in work.columns:
        work["sample_weight"] = 1.0
    weights: dict[str, float] = defaultdict(float)
    for sid, w in work.groupby("segment_id")["sample_weight"].sum().items():
        weights[str(sid)] = float(w)
    return dict(weights)


def global_segment_counts(df: pd.DataFrame) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for sid, n in df.groupby("segment_id").size().items():
        counts[str(sid)] = int(n)
    return dict(counts)
