#!/usr/bin/env python3
"""Embed one or more texts with sentence-transformers and print JSON.

Input:
  {"texts": ["foo", "bar"], "model": "sentence-transformers/all-MiniLM-L6-v2"}

Output:
  {"embeddings": [[...], [...]], "model": "...", "dim": 384}
"""

from __future__ import annotations

import json
import os
import sys

from sentence_transformers import SentenceTransformer


def main() -> None:
    payload = json.load(sys.stdin)
    texts = payload.get("texts") or []
    if not isinstance(texts, list) or not texts:
        raise SystemExit("payload.texts must be a non-empty list")

    model_name = payload.get("model") or os.environ.get(
        "SENTENCE_TRANSFORMER_MODEL",
        "sentence-transformers/all-MiniLM-L6-v2",
    )
    model = SentenceTransformer(model_name)
    mat = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
    out = {
        "model": model_name,
        "dim": int(mat.shape[1]),
        "embeddings": [[round(float(x), 8) for x in row] for row in mat.tolist()],
    }
    json.dump(out, sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    main()
