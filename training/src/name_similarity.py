"""Segment-name similarity without enumerating synonyms.

Preferred path: dense text embeddings → cosine neighbors
  (sentence-transformers locally, or OpenAI / HTTP embedding API).

Fallback: char TF-IDF (lexical only — will NOT link miss↔female).

Optional: concept_aliases.json as a small manual boost, NOT the main strategy.
"""

from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any, Literal

import numpy as np
import pandas as pd

_NON_ALNUM = re.compile(r"[^a-z0-9]+")

NameBackend = Literal["auto", "embedding", "tfidf"]


def normalize_name(name: str) -> str:
    text = (name or "").strip().lower()
    text = text.replace("&", " and ")
    text = _NON_ALNUM.sub(" ", text)
    return " ".join(text.split())


DEFAULT_CONCEPT_GROUPS: list[list[str]] = [
    # Tiny seed only — do NOT try to enumerate the world here.
    ["female", "miss", "ms", "mrs", "woman", "women"],
]


def load_concept_groups(path: Any | None = None) -> list[list[str]]:
    if path is None:
        return []
    p = Path(path)
    if not p.exists():
        return []
    with p.open(encoding="utf-8") as f:
        raw = json.load(f)
    groups = raw.get("groups", raw) if isinstance(raw, dict) else raw
    return [[normalize_name(x) for x in g] for g in groups]


def build_segment_name_map(df: pd.DataFrame) -> dict[str, str]:
    """segment_id → most common non-empty segment_name (fallback to id)."""
    if "segment_name" not in df.columns:
        return {str(s): str(s) for s in df["segment_id"].astype(str).unique()}

    counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for sid, name in zip(df["segment_id"].astype(str), df["segment_name"].astype(str)):
        name = name.strip()
        if not name or name.lower() == "nan":
            continue
        counts[sid][name] += 1
    names: dict[str, str] = {
        sid: max(hist.items(), key=lambda x: x[1])[0] for sid, hist in counts.items()
    }
    for sid in df["segment_id"].astype(str).unique():
        names.setdefault(str(sid), str(sid))
    return names


def _alias_boost(name_a: str, name_b: str, groups: list[list[str]]) -> float:
    if not groups:
        return 0.0
    na, nb = normalize_name(name_a), normalize_name(name_b)
    if not na or not nb:
        return 0.0
    tokens_a, tokens_b = set(na.split()), set(nb.split())
    for group in groups:
        gset = set(group)
        if (na in gset or tokens_a & gset) and (nb in gset or tokens_b & gset):
            return 1.0
    return 0.0


def _l2_normalize(mat: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    return mat / np.clip(norms, 1e-9, None)


def embed_texts_openai(texts: list[str], model: str | None = None) -> np.ndarray:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY not set")
    model = model or os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    payload = json.dumps({"model": model, "input": texts}).encode("utf-8")
    req = urllib.request.Request(
        "https://api.openai.com/v1/embeddings",
        data=payload,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    vectors = [None] * len(texts)
    for row in body["data"]:
        vectors[row["index"]] = row["embedding"]
    if any(v is None for v in vectors):
        raise RuntimeError("Incomplete OpenAI embedding response")
    return np.asarray(vectors, dtype=np.float32)


def embed_texts_http(texts: list[str], url: str | None = None) -> np.ndarray:
    """
    Generic embedding HTTP API.
    POST JSON: {"texts": ["...", "..."]}
    Response JSON: {"embeddings": [[...], ...]}
    """
    url = url or os.environ.get("EMBEDDING_API_URL")
    if not url:
        raise RuntimeError("EMBEDDING_API_URL not set")
    payload = json.dumps({"texts": texts}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    vectors = body.get("embeddings") or body.get("data")
    if not vectors or len(vectors) != len(texts):
        raise RuntimeError("Unexpected embedding API response shape")
    return np.asarray(vectors, dtype=np.float32)


def embed_texts_sentence_transformers(
    texts: list[str],
    model_name: str | None = None,
) -> np.ndarray:
    from sentence_transformers import SentenceTransformer

    model_name = model_name or os.environ.get(
        "SENTENCE_TRANSFORMER_MODEL",
        "sentence-transformers/all-MiniLM-L6-v2",
    )
    # Cache model on the function to avoid reloading per train call
    cache = getattr(embed_texts_sentence_transformers, "_cache", {})
    if model_name not in cache:
        cache[model_name] = SentenceTransformer(model_name)
        embed_texts_sentence_transformers._cache = cache  # type: ignore[attr-defined]
    model = cache[model_name]
    mat = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
    return np.asarray(mat, dtype=np.float32)


def embed_texts(texts: list[str]) -> tuple[np.ndarray, str]:
    """
    Prefer local sentence-transformers; optional overrides via env:
      EMBEDDING_API_URL, OPENAI_API_KEY
    Returns (matrix, backend_name).
    """
    errors: list[str] = []

    # Primary: sentence-transformers (project choice)
    try:
        return embed_texts_sentence_transformers(texts), "sentence_transformers"
    except Exception as exc:  # noqa: BLE001
        errors.append(f"sentence_transformers: {exc}")

    if os.environ.get("EMBEDDING_API_URL"):
        try:
            return embed_texts_http(texts), "http"
        except Exception as exc:  # noqa: BLE001
            errors.append(f"http: {exc}")

    if os.environ.get("OPENAI_API_KEY"):
        try:
            return embed_texts_openai(texts), "openai"
        except Exception as exc:  # noqa: BLE001
            errors.append(f"openai: {exc}")

    raise RuntimeError(
        "sentence-transformers unavailable. pip install -r requirements.txt "
        "(needs a supported Python, ideally 3.10–3.12). Details: "
        + " | ".join(errors)
    )


def _tfidf_sims(docs: list[str]) -> np.ndarray:
    try:
        from sklearn.feature_extraction.text import TfidfVectorizer

        vectorizer = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), min_df=1)
        mat = vectorizer.fit_transform(docs)
        return (mat @ mat.T).toarray()
    except Exception:
        def grams(s: str) -> set[str]:
            s = f" {s} "
            return {s[i : i + 3] for i in range(max(len(s) - 2, 1))}

        gsets = [grams(d) for d in docs]
        n = len(docs)
        sims = np.zeros((n, n), dtype=np.float64)
        for i in range(n):
            for j in range(i + 1, n):
                inter = len(gsets[i] & gsets[j])
                union = len(gsets[i] | gsets[j]) or 1
                sims[i, j] = sims[j, i] = inter / union
        return sims


def build_name_embeddings_map(
    segment_names: dict[str, str],
    *,
    backend: NameBackend = "embedding",
) -> tuple[dict[str, list[float]], dict[str, Any]]:
    """segment_id -> embedding vector for name-based retrieval."""
    ids = sorted(segment_names.keys())
    meta: dict[str, Any] = {"backendRequested": backend}
    if not ids:
        meta["backendUsed"] = "none"
        return {}, meta

    if backend == "tfidf":
        meta["backendUsed"] = "tfidf"
        meta["vectorDim"] = 0
        return {}, meta

    display = [segment_names[i] if segment_names[i].strip() else i for i in ids]
    mat, used = embed_texts(display)
    mat = _l2_normalize(mat)
    meta["backendUsed"] = used
    meta["vectorDim"] = int(mat.shape[1])
    return {
        sid: [round(float(x), 8) for x in mat[idx].tolist()]
        for idx, sid in enumerate(ids)
    }, meta


def build_name_neighbors(
    segment_names: dict[str, str],
    *,
    top_k: int = 20,
    backend: NameBackend = "auto",
    concept_groups: list[list[str]] | None = None,
    alias_boost: bool = False,
    min_score: float = 0.25,
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, Any]]:
    """
    Build Top-K name neighbors.

    backend:
      - embedding: semantic vectors (recommended; no synonym enumeration)
      - tfidf: lexical only
      - auto: try embedding, fall back to tfidf
    """
    ids = sorted(segment_names.keys())
    meta: dict[str, Any] = {
        "backendRequested": backend,
        "aliasBoost": alias_boost,
        "minScore": min_score,
        "topK": top_k,
    }
    if len(ids) < 2:
        meta["backendUsed"] = "none"
        return {i: [] for i in ids}, meta

    display = [segment_names[i] for i in ids]
    # Keep original casing for embedding quality; also pass a light normalized form.
    embed_inputs = [d if d.strip() else i for d, i in zip(display, ids)]

    used = backend
    sims: np.ndarray

    if backend in ("auto", "embedding"):
        try:
            mat, used_name = embed_texts(embed_inputs)
            mat = _l2_normalize(mat)
            sims = mat @ mat.T
            used = used_name  # type: ignore[assignment]
            meta["backendUsed"] = used_name
            meta["vectorDim"] = int(mat.shape[1])
        except Exception as exc:
            if backend == "embedding":
                raise
            meta["embeddingError"] = str(exc)
            docs = [normalize_name(d) or i.lower() for d, i in zip(display, ids)]
            sims = _tfidf_sims(docs)
            used = "tfidf_fallback"
            meta["backendUsed"] = used
            # Lexical scores are weaker; lower threshold a bit
            min_score = min(min_score, 0.15)
    else:
        docs = [normalize_name(d) or i.lower() for d, i in zip(display, ids)]
        sims = _tfidf_sims(docs)
        used = "tfidf"
        meta["backendUsed"] = used
        min_score = min(min_score, 0.15)

    groups = concept_groups if alias_boost else []
    result: dict[str, list[dict[str, Any]]] = {}
    for i, sid in enumerate(ids):
        scored: list[tuple[str, float, str]] = []
        for j, oid in enumerate(ids):
            if i == j:
                continue
            score = float(sims[i, j])
            if groups:
                score = max(
                    score,
                    _alias_boost(segment_names[sid], segment_names[oid], groups),
                )
            if score < min_score:
                continue
            scored.append((oid, score, segment_names[oid]))
        scored.sort(key=lambda x: (-x[1], x[0]))
        result[sid] = [
            {"segmentId": oid, "score": round(score, 6), "name": name}
            for oid, score, name in scored[:top_k]
        ]

    meta["pairCount"] = sum(len(v) for v in result.values())
    return result, meta
