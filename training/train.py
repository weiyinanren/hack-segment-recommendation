#!/usr/bin/env python3
"""
Training artifacts — three layers:

  artifacts/global/                 # platform-wide segment prior (aggregates only)
  artifacts/industries/{slug}/      # industry popularity (aggregates only)
  artifacts/clients/{slug}/         # private CF / embeddings / catalog

Sample weights: created_at × distributed (see src/weights.py).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))

from src.embeddings import build_embeddings, nearest_neighbors_from_embeddings
from src.name_similarity import (
    build_name_embeddings_map,
    build_name_neighbors,
    build_segment_name_map,
    load_concept_groups,
)
from src.popularity import build_popularity, global_segment_counts, global_segment_weights
from src.similarity import build_audiences, build_similarity
from src.weights import WeightConfig, attach_sample_weights

SAFE_SLUG_RE = re.compile(r"[^\w._-]+", re.UNICODE)


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")


def safe_slug(name: str, *, kind: str = "name") -> str:
    raw = (name or "").strip()
    if not raw or raw in {".", ".."} or "/" in raw or "\\" in raw:
        raise ValueError(f"Invalid {kind}: {name!r}")
    slug = SAFE_SLUG_RE.sub("_", raw).strip("._-")
    if not slug:
        raise ValueError(f"Invalid {kind} after sanitize: {name!r}")
    return slug


def weights_to_scored_list(weights: dict[str, float], top_n: int) -> list[dict]:
    ranked = sorted(weights.items(), key=lambda x: -x[1])[:top_n]
    if not ranked:
        return []
    max_w = float(ranked[0][1])
    min_w = float(ranked[-1][1])
    denom = max(max_w - min_w, 1e-9)
    return [
        {
            "segmentId": sid,
            "weightSum": round(w, 6),
            "score": round((w - min_w) / denom, 6),
        }
        for sid, w in ranked
    ]


def train_global(
    df: pd.DataFrame,
    out_dir: Path,
    *,
    top_n: int,
    top_k_name: int,
    version: str,
    weight_cfg: WeightConfig,
    concept_aliases_path: Path | None,
    name_backend: str,
    name_alias_boost: bool,
    name_min_score: float,
) -> dict:
    """Platform-wide prior + segment name graph (no co-occurrence)."""
    segment_prior = weights_to_scored_list(global_segment_weights(df), top_n)
    write_json(out_dir / "segment_prior.json", segment_prior)

    segment_names = build_segment_name_map(df)
    write_json(out_dir / "segment_names.json", segment_names)
    segment_name_embeddings, embedding_meta = build_name_embeddings_map(
        segment_names,
        backend=name_backend,  # type: ignore[arg-type]
    )
    if segment_name_embeddings:
        write_json(out_dir / "segment_name_embeddings.json", segment_name_embeddings)
    groups = load_concept_groups(concept_aliases_path) if name_alias_boost else []
    name_neighbors, name_meta = build_name_neighbors(
        segment_names,
        top_k=top_k_name,
        backend=name_backend,  # type: ignore[arg-type]
        concept_groups=groups,
        alias_boost=name_alias_boost,
        min_score=name_min_score,
    )
    write_json(out_dir / "name_neighbors.json", name_neighbors)
    print(f"  name backend={name_meta.get('backendUsed')} pairs={name_meta.get('pairCount')}")

    meta = {
        "layer": "global",
        "version": version,
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "inputRows": len(df),
        "clientCount": int(df["client_name"].nunique()),
        "segmentCount": len(global_segment_counts(df)),
        "namedSegmentCount": sum(
            1 for sid, n in segment_names.items() if n and n != sid
        ),
        "topN": top_n,
        "topKName": top_k_name,
        "segmentNameEmbeddings": embedding_meta,
        "nameSimilarity": name_meta,
        "sampleWeights": weight_cfg.to_meta(),
        "contents": "segment_prior + segment_names + segment_name_embeddings + name_neighbors",
        "excludes": "industry split, co-occurrence, embeddings",
        "artifacts": [
            "segment_prior.json",
            "segment_names.json",
            *(
                ["segment_name_embeddings.json"]
                if segment_name_embeddings
                else []
            ),
            "name_neighbors.json",
            "meta.json",
        ],
    }
    write_json(out_dir / "meta.json", meta)
    return meta


def train_industries(
    df: pd.DataFrame,
    out_root: Path,
    *,
    top_n: int,
    version: str,
    weight_cfg: WeightConfig,
) -> list[dict]:
    """One folder per industry with popularity list."""
    by_industry = build_popularity(df, top_n=top_n)
    out_root.mkdir(parents=True, exist_ok=True)
    index = []
    for industry, items in sorted(by_industry.items()):
        slug = safe_slug(industry, kind="industry")
        out_dir = out_root / slug
        write_json(out_dir / "popularity.json", items)
        meta = {
            "layer": "industry",
            "industry": industry,
            "industrySlug": slug,
            "version": version,
            "trainedAt": datetime.now(timezone.utc).isoformat(),
            "segmentCount": len(items),
            "topN": top_n,
            "sampleWeights": weight_cfg.to_meta(),
            "contents": "industry popularity (aggregates only)",
            "excludes": "co-occurrence, embeddings, audience baskets",
            "artifacts": ["popularity.json", "meta.json"],
        }
        write_json(out_dir / "meta.json", meta)
        index.append(
            {
                "industry": industry,
                "industrySlug": slug,
                "path": f"industries/{slug}",
                "segmentCount": len(items),
            }
        )
        print(f"  → industry {industry} (slug={slug}, segments={len(items)})")
    write_json(out_root / "index.json", {"version": version, "industries": index})
    return index


def train_client(
    client_name: str,
    df: pd.DataFrame,
    out_dir: Path,
    *,
    top_n_popularity: int,
    top_k_similarity: int,
    vector_size: int,
    skip_embeddings: bool,
    version: str,
    weight_cfg: WeightConfig,
) -> dict:
    popularity = build_popularity(df, top_n=top_n_popularity)
    counts = global_segment_counts(df)
    baskets = build_audiences(df)
    similarity = build_similarity(baskets, top_k=top_k_similarity)
    catalog = sorted(counts.keys())

    write_json(out_dir / "popularity.json", popularity)
    write_json(out_dir / "similarity.json", similarity)
    write_json(
        out_dir / "segment_catalog.json",
        {"clientName": client_name, "segmentIds": catalog},
    )

    embeddings: dict = {}
    emb_neighbors: dict = {}
    if not skip_embeddings:
        embeddings = build_embeddings(baskets, vector_size=vector_size)
        emb_neighbors = nearest_neighbors_from_embeddings(
            embeddings, top_k=top_k_similarity
        )
        write_json(out_dir / "embeddings.json", embeddings)
        write_json(out_dir / "emb_neighbors.json", emb_neighbors)

    meta = {
        "layer": "client",
        "version": version,
        "clientName": client_name,
        "clientSlug": out_dir.name,
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "inputRows": len(df),
        "audienceCount": len(baskets),
        "segmentCount": len(counts),
        "industries": sorted(popularity.keys()),
        "topNPopularity": top_n_popularity,
        "topKSimilarity": top_k_similarity,
        "vectorSize": vector_size if embeddings else 0,
        "hasEmbeddings": bool(embeddings),
        "sampleWeights": weight_cfg.to_meta(),
        "isolation": "client_private_behavior",
        "artifacts": [
            "popularity.json",
            "similarity.json",
            "segment_catalog.json",
            *(["embeddings.json", "emb_neighbors.json"] if embeddings else []),
            "meta.json",
        ],
    }
    write_json(out_dir / "meta.json", meta)
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train global + industry + client artifacts"
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=Path(__file__).parent / "data" / "audiences.csv",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).parent.parent / "artifacts",
    )
    parser.add_argument("--client", action="append", default=None)
    parser.add_argument(
        "--skip-shared",
        action="store_true",
        help="Skip refreshing global/ and industries/",
    )
    parser.add_argument("--skip-l0", action="store_true", help=argparse.SUPPRESS)  # alias
    parser.add_argument("--top-n-popularity", type=int, default=50)
    parser.add_argument("--top-k-similarity", type=int, default=20)
    parser.add_argument("--top-k-name", type=int, default=20)
    parser.add_argument(
        "--name-backend",
        choices=["auto", "embedding", "tfidf"],
        default="embedding",
        help="Name similarity backend (default: embedding via sentence-transformers)",
    )
    parser.add_argument(
        "--sentence-transformer-model",
        type=str,
        default="sentence-transformers/all-MiniLM-L6-v2",
        help="HuggingFace model id for sentence-transformers",
    )
    parser.add_argument(
        "--name-alias-boost",
        action="store_true",
        help="Optional: also boost pairs from concept_aliases.json (not required)",
    )
    parser.add_argument(
        "--name-min-score",
        type=float,
        default=0.25,
        help="Min cosine score to keep a name neighbor (embedding scale)",
    )
    parser.add_argument(
        "--concept-aliases",
        type=Path,
        default=Path(__file__).parent / "data" / "concept_aliases.json",
        help="Optional synonym groups (only used with --name-alias-boost)",
    )
    parser.add_argument("--vector-size", type=int, default=128)
    parser.add_argument("--skip-embeddings", action="store_true")
    parser.add_argument("--half-life-days", type=float, default=90.0)
    parser.add_argument("--weight-distributed", type=float, default=1.0)
    parser.add_argument("--weight-undistributed", type=float, default=0.4)
    parser.add_argument("--min-weight", type=float, default=0.05)
    parser.add_argument("--as-of", type=str, default=None)
    args = parser.parse_args()
    skip_shared = args.skip_shared or args.skip_l0

    if not args.input.exists():
        raise SystemExit(f"Input not found: {args.input}")

    df = pd.read_csv(args.input)
    if "client_name" not in df.columns and "user_id" in df.columns:
        df = df.rename(columns={"user_id": "client_name"})
        print("Note: renamed legacy column user_id → client_name")

    required = {"client_name", "industry", "audience_id", "segment_id"}
    missing = required - set(df.columns)
    if missing:
        raise SystemExit(f"Missing columns: {sorted(missing)}")

    df["client_name"] = df["client_name"].astype(str).str.strip()
    df["industry"] = df["industry"].astype(str).str.strip()
    if df["client_name"].eq("").any():
        raise SystemExit("Found empty client_name rows")

    as_of = None
    if args.as_of:
        as_of = datetime.fromisoformat(args.as_of.replace("Z", "+00:00"))
        if as_of.tzinfo is None:
            as_of = as_of.replace(tzinfo=timezone.utc)

    weight_cfg = WeightConfig(
        half_life_days=args.half_life_days,
        weight_distributed=args.weight_distributed,
        weight_undistributed=args.weight_undistributed,
        min_weight=args.min_weight,
        as_of=as_of,
    )
    df = attach_sample_weights(df, weight_cfg)

    cols = set(pd.read_csv(args.input, nrows=0).columns)
    if {"created_at", "distributed"} & cols:
        print(
            f"Sample weights enabled: half_life={weight_cfg.half_life_days}d "
            f"dist={weight_cfg.weight_distributed}/{weight_cfg.weight_undistributed} "
            f"mean_w={df['sample_weight'].mean():.4f}"
        )
    else:
        print("No created_at/distributed columns — using uniform weight 1.0")

    all_clients = sorted(df["client_name"].unique())
    l1_clients = all_clients
    if args.client:
        wanted = set(args.client)
        unknown = wanted - set(all_clients)
        if unknown:
            raise SystemExit(f"Unknown --client values: {sorted(unknown)}")
        l1_clients = sorted(wanted)

    print(f"Loaded {len(df)} rows from {args.input}")
    version = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    args.output.mkdir(parents=True, exist_ok=True)

    # Make sentence-transformers model configurable for this process
    if args.sentence_transformer_model:
        os.environ.setdefault(
            "SENTENCE_TRANSFORMER_MODEL", args.sentence_transformer_model
        )

    industry_index: list[dict] = []
    if not skip_shared:
        print("Layer global/ ...")
        train_global(
            df,
            args.output / "global",
            top_n=args.top_n_popularity,
            top_k_name=args.top_k_name,
            version=version,
            weight_cfg=weight_cfg,
            concept_aliases_path=args.concept_aliases,
            name_backend=args.name_backend,
            name_alias_boost=args.name_alias_boost,
            name_min_score=args.name_min_score,
        )
        print("Layer industries/ ...")
        industry_index = train_industries(
            df,
            args.output / "industries",
            top_n=args.top_n_popularity,
            version=version,
            weight_cfg=weight_cfg,
        )
    else:
        print("Skipping global/ + industries/ (--skip-shared)")
        idx_path = args.output / "industries" / "index.json"
        if idx_path.exists():
            with idx_path.open(encoding="utf-8") as f:
                industry_index = json.load(f).get("industries", [])

    print(f"Layer clients/ for {len(l1_clients)} client(s): {l1_clients}")
    clients_root = args.output / "clients"
    clients_root.mkdir(parents=True, exist_ok=True)

    index_clients = []
    for name in l1_clients:
        slug = safe_slug(name, kind="client_name")
        client_df = df[df["client_name"] == name].copy()
        out_dir = clients_root / slug
        print(f"  → client {name} (slug={slug}, rows={len(client_df)})")
        meta = train_client(
            name,
            client_df,
            out_dir,
            top_n_popularity=args.top_n_popularity,
            top_k_similarity=args.top_k_similarity,
            vector_size=args.vector_size,
            skip_embeddings=args.skip_embeddings,
            version=version,
            weight_cfg=weight_cfg,
        )
        index_clients.append(
            {
                "clientName": name,
                "clientSlug": slug,
                "path": f"clients/{slug}",
                "segmentCount": meta["segmentCount"],
                "audienceCount": meta["audienceCount"],
            }
        )

    if args.client:
        for d in sorted(clients_root.iterdir()) if clients_root.exists() else []:
            if not d.is_dir():
                continue
            meta_path = d / "meta.json"
            if not meta_path.exists():
                continue
            with meta_path.open(encoding="utf-8") as f:
                m = json.load(f)
            name = m.get("clientName", d.name)
            if any(c["clientName"] == name for c in index_clients):
                continue
            index_clients.append(
                {
                    "clientName": name,
                    "clientSlug": d.name,
                    "path": f"clients/{d.name}",
                    "segmentCount": m.get("segmentCount"),
                    "audienceCount": m.get("audienceCount"),
                }
            )
        index_clients.sort(key=lambda c: c["clientName"])

    index = {
        "version": version,
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "mode": "three_layer_global_industry_client",
        "sampleWeights": weight_cfg.to_meta(),
        "layers": {
            "global": "global/ (segment_prior + name_neighbors + segment_names)",
            "industry": "industries/{slug}/popularity.json",
            "client": "clients/{slug}/ (private CF + embeddings + catalog)",
        },
        "serving": {
            "default": "mix(global, industry, client, name) ∩ client.segment_catalog",
            "expandBeyondCatalog": "optional; allow industry/global/name candidates outside catalog",
            "nameSimilarity": "global/name_neighbors.json via sentence-transformers (MiniLM)",
        },
        "industries": industry_index,
        "clientCount": len(index_clients),
        "clients": index_clients,
    }
    write_json(args.output / "index.json", index)

    print(f"Wrote artifacts to {args.output.resolve()}")
    print(
        f"  layers=global+industry+client "
        f"industries={len(industry_index)} clients={len(index_clients)} version={version}"
    )


if __name__ == "__main__":
    main()
