"""Item-based collaborative filtering: Segment → similar Segments (weighted)."""

from __future__ import annotations

from collections import defaultdict
from typing import Any

import pandas as pd

# (segment set, audience sample weight)
WeightedBasket = tuple[set[str], float]


def build_audiences(df: pd.DataFrame) -> list[WeightedBasket]:
    """
    Group rows into audience baskets with a single weight per audience.

    Weight = max(sample_weight) among rows of that audience (they should match).
    """
    baskets: list[WeightedBasket] = []
    has_w = "sample_weight" in df.columns
    for _, group in df.groupby("audience_id"):
        basket = {str(s) for s in group["segment_id"].tolist()}
        if not basket:
            continue
        weight = float(group["sample_weight"].max()) if has_w else 1.0
        weight = max(weight, 1e-9)
        baskets.append((basket, weight))
    return baskets


def build_similarity(
    baskets: list[WeightedBasket] | list[set[str]],
    top_k: int = 20,
) -> dict[str, list[dict[str, Any]]]:
    """
    Weighted cosine on co-occurrence:

      co[a][b] += w
      count[a] += w
      sim = co / sqrt(count[a] * count[b])

    Accepts legacy ``list[set]`` (implicit weight 1.0) or WeightedBasket list.
    """
    segment_weight: dict[str, float] = defaultdict(float)
    co_weight: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))

    for item in baskets:
        if isinstance(item, tuple):
            basket, w = item
        else:
            basket, w = item, 1.0
        w = float(w)
        segments = sorted(basket)
        for s in segments:
            segment_weight[s] += w
        for i, a in enumerate(segments):
            for b in segments[i + 1 :]:
                co_weight[a][b] += w
                co_weight[b][a] += w

    result: dict[str, list[dict[str, Any]]] = {}
    for a, neighbors in co_weight.items():
        scored: list[tuple[str, float, float]] = []
        ca = segment_weight[a]
        for b, co in neighbors.items():
            cb = segment_weight[b]
            denom = (ca * cb) ** 0.5
            if denom == 0:
                continue
            sim = co / denom
            scored.append((b, sim, co))

        scored.sort(key=lambda x: (-x[1], -x[2], x[0]))
        result[a] = [
            {
                "segmentId": b,
                "score": round(sim, 6),
                "coOccurrence": round(co, 6),
            }
            for b, sim, co in scored[:top_k]
        ]

    for s in segment_weight:
        result.setdefault(s, [])

    return result
