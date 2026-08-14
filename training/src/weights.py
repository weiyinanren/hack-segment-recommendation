"""Audience-level sample weights: recency × distributed.

w = max(min_weight, w_recency(created_at) * w_distributed(distributed))

w_recency = exp(-ln(2) / half_life_days * age_days)   # newer → closer to 1
w_distributed = weight_distributed | weight_undistributed
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any

import pandas as pd

TRUE_VALUES = {"1", "true", "t", "yes", "y", "distributed", "published"}


@dataclass(frozen=True)
class WeightConfig:
    half_life_days: float = 90.0
    weight_distributed: float = 1.0
    weight_undistributed: float = 0.4
    min_weight: float = 0.05
    # If None, use "now" when applying weights
    as_of: datetime | None = None

    def to_meta(self) -> dict[str, Any]:
        d = asdict(self)
        d["as_of"] = self.as_of.isoformat() if self.as_of else None
        d["formula"] = (
            "w = max(min_weight, exp(-ln2/half_life * age_days) * w_distributed)"
        )
        return d


def _parse_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return False
    if isinstance(value, (int, float)):
        return value != 0
    return str(value).strip().lower() in TRUE_VALUES


def _parse_datetime(value: Any) -> datetime | None:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return None
    if isinstance(value, datetime):
        dt = value
    else:
        text = str(value).strip()
        if not text:
            return None
        # Support "2024-01-15" and ISO datetimes
        try:
            dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def recency_weight(age_days: float, half_life_days: float) -> float:
    if half_life_days <= 0:
        return 1.0
    age = max(0.0, float(age_days))
    return math.exp(-math.log(2.0) / half_life_days * age)


def compute_row_weight(
    created_at: Any,
    distributed: Any,
    cfg: WeightConfig,
    *,
    now: datetime,
) -> dict[str, float | bool]:
    created = _parse_datetime(created_at)
    is_dist = _parse_bool(distributed)

    if created is None:
        w_rec = 1.0
        age_days = 0.0
    else:
        age_days = max(0.0, (now - created).total_seconds() / 86400.0)
        w_rec = recency_weight(age_days, cfg.half_life_days)

    w_dist = cfg.weight_distributed if is_dist else cfg.weight_undistributed
    w = max(cfg.min_weight, w_rec * w_dist)
    return {
        "age_days": round(age_days, 4),
        "w_recency": round(w_rec, 6),
        "w_distributed": w_dist,
        "distributed": is_dist,
        "sample_weight": round(w, 6),
    }


def attach_sample_weights(
    df: pd.DataFrame,
    cfg: WeightConfig,
) -> pd.DataFrame:
    """
    Add sample_weight (and debug columns) to a copy of df.

    Weight is audience-level: if multiple rows share audience_id, they should
    share the same created_at/distributed; we still compute per-row then
    optionally reconcile in build_audiences by taking the first/max.
    """
    out = df.copy()
    now = cfg.as_of or datetime.now(timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)

    has_created = "created_at" in out.columns
    has_dist = "distributed" in out.columns

    if not has_created and not has_dist:
        out["sample_weight"] = 1.0
        out["w_recency"] = 1.0
        out["w_distributed"] = 1.0
        out["age_days"] = 0.0
        return out

    if not has_created:
        out["created_at"] = pd.NA
    if not has_dist:
        out["distributed"] = False

    rows = [
        compute_row_weight(c, d, cfg, now=now)
        for c, d in zip(out["created_at"].tolist(), out["distributed"].tolist())
    ]
    out["sample_weight"] = [r["sample_weight"] for r in rows]
    out["w_recency"] = [r["w_recency"] for r in rows]
    out["w_distributed"] = [r["w_distributed"] for r in rows]
    out["age_days"] = [r["age_days"] for r in rows]
    return out
