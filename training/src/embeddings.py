"""Segment embeddings via weighted co-occurrence PPMI + Truncated SVD."""

from __future__ import annotations

from typing import Any

import numpy as np


def build_embeddings(
    baskets: list,
    vector_size: int = 128,
    seed: int = 42,
    **_ignored: Any,
) -> dict[str, list[float]]:
    """
    Build L2-normalized segment vectors from weighted co-occurrence PPMI + SVD.

    ``baskets`` may be ``list[set]`` or ``list[tuple[set, weight]]``.
    """
    if not baskets:
        return {}

    normalized: list[tuple[set[str], float]] = []
    for item in baskets:
        if isinstance(item, tuple):
            basket, w = item
        else:
            basket, w = item, 1.0
        if basket:
            normalized.append((set(basket), float(w)))

    if not normalized:
        return {}

    vocab: list[str] = sorted({s for b, _ in normalized for s in b})
    index = {s: i for i, s in enumerate(vocab)}
    n = len(vocab)
    if n == 0:
        return {}

    co = np.zeros((n, n), dtype=np.float64)
    for basket, w in normalized:
        ids = [index[s] for s in basket]
        for i, a in enumerate(ids):
            for b in ids[i + 1 :]:
                co[a, b] += w
                co[b, a] += w

    row_sum = co.sum(axis=1, keepdims=True)
    total = co.sum()
    if total <= 0:
        rng = np.random.default_rng(seed)
        mat = rng.normal(size=(n, min(vector_size, max(n, 1))))
        mat /= np.linalg.norm(mat, axis=1, keepdims=True).clip(min=1e-9)
        return {
            sid: [round(float(x), 8) for x in mat[i].tolist()]
            for i, sid in enumerate(vocab)
        }

    p_i = row_sum / total
    p_ij = co / total
    with np.errstate(divide="ignore", invalid="ignore"):
        pmi = np.log((p_ij + 1e-12) / (p_i @ p_i.T + 1e-12))
    ppmi = np.maximum(pmi, 0.0)
    np.fill_diagonal(ppmi, 0.0)

    dim = min(vector_size, max(n - 1, 1), max(int(np.linalg.matrix_rank(ppmi)), 1))
    try:
        from sklearn.decomposition import TruncatedSVD

        svd = TruncatedSVD(n_components=dim, random_state=seed)
        mat = svd.fit_transform(ppmi)
    except Exception:
        u, s, _ = np.linalg.svd(ppmi, full_matrices=False)
        mat = u[:, :dim] * s[:dim]

    if mat.shape[1] < vector_size:
        pad = np.zeros((n, vector_size - mat.shape[1]), dtype=np.float64)
        mat = np.hstack([mat, pad])

    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    mat = mat / np.clip(norms, 1e-9, None)

    return {
        sid: [round(float(x), 8) for x in mat[i].tolist()]
        for i, sid in enumerate(vocab)
    }


def nearest_neighbors_from_embeddings(
    embeddings: dict[str, list[float]],
    top_k: int = 20,
) -> dict[str, list[dict[str, Any]]]:
    """Top-K neighbors via cosine similarity (vectors are L2-normalized)."""
    if not embeddings:
        return {}

    ids = list(embeddings.keys())
    mat = np.array([embeddings[i] for i in ids], dtype=np.float32)
    sims = mat @ mat.T

    result: dict[str, list[dict[str, Any]]] = {}
    for i, sid in enumerate(ids):
        row = sims[i]
        order = np.argsort(-row)
        neighbors = []
        for j in order:
            if j == i:
                continue
            neighbors.append(
                {
                    "segmentId": ids[j],
                    "score": round(float(row[j]), 6),
                }
            )
            if len(neighbors) >= top_k:
                break
        result[sid] = neighbors

    return result
