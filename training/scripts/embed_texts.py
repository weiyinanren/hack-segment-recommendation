#!/usr/bin/env python3
"""Embed texts with sentence-transformers.

Runs as a long-lived worker: the model is loaded once, then each line on stdin is
answered with one line on stdout. Loading the model costs ~3.4s while encoding a
single query costs ~6ms, so the caller keeps one process alive instead of paying
startup on every request.

Request (one JSON object per line):
  {"texts": ["foo", "bar"], "model": "sentence-transformers/all-MiniLM-L6-v2"}

Response (one JSON object per line):
  {"embeddings": [[...], [...]], "model": "...", "dim": 384}
  {"error": "..."}

A single request with no trailing newline also works, which keeps one-shot
invocations valid.
"""

from __future__ import annotations

import json
import os
import sys

from sentence_transformers import SentenceTransformer

DEFAULT_MODEL = "sentence-transformers/all-MiniLM-L6-v2"


def resolve_model_name(requested: str | None) -> str:
    if requested:
        return requested
    return os.environ.get("SENTENCE_TRANSFORMER_MODEL", DEFAULT_MODEL)


def respond(payload: dict) -> None:
    """One JSON object per line, flushed so the caller is never left waiting."""
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
    sys.stdout.write("\n")
    sys.stdout.flush()


def main() -> None:
    # Loaded lazily on the first request so the model name can come from the payload,
    # then reused for the lifetime of the process.
    model = None
    loaded_name = None

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            payload = json.loads(line)
            texts = payload.get("texts") or []
            if not isinstance(texts, list) or not texts:
                respond({"error": "payload.texts must be a non-empty list"})
                continue

            model_name = resolve_model_name(payload.get("model"))
            if model is None or model_name != loaded_name:
                model = SentenceTransformer(model_name)
                loaded_name = model_name

            mat = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
            respond(
                {
                    "model": model_name,
                    "dim": int(mat.shape[1]),
                    "embeddings": [[round(float(x), 8) for x in row] for row in mat.tolist()],
                }
            )
        except Exception as exc:  # keep serving after a bad request
            respond({"error": f"{type(exc).__name__}: {exc}"})


if __name__ == "__main__":
    main()
