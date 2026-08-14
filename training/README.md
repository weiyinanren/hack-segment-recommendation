# Training

三层产物：`global` + `industries` + `clients`。详见 [`docs/TENANCY.md`](../docs/TENANCY.md)。

## 启动

```bash
cd training
source .venv/bin/activate
python train.py --as-of 2026-08-12
```

只重训某个 client（不动 shared 层）：

```bash
python train.py --client AcmeAuto --skip-shared
```

样本权重：[`docs/SAMPLE_WEIGHTS.md`](../docs/SAMPLE_WEIGHTS.md)

名称语义（默认 sentence-transformers）：

```bash
pip install -r requirements.txt
python train.py --name-backend embedding --as-of 2026-08-12
```

详见 [`docs/NAME_SIMILARITY.md`](../docs/NAME_SIMILARITY.md)

## 产物

```text
artifacts/global/           # 含 name_neighbors.json（全局一份）
artifacts/industries/{slug}/
artifacts/clients/{slug}/   # similarity + emb_neighbors（Serving）；embeddings 仅训练产物
```

各文件用途：[`docs/ARTIFACTS.md`](../docs/ARTIFACTS.md)  
隔离策略：[`docs/TENANCY.md`](../docs/TENANCY.md)  
算法：[`docs/ALGORITHMS.md`](../docs/ALGORITHMS.md)