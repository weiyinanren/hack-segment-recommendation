# Name similarity — sentence-transformers（默认）

目标：用语义向量连接 `miss` / `Ms.` / `female`，**不必穷举同义词**。

产物：**全局一份** `artifacts/global/name_neighbors.json`（不要按 client 复制）。

---

## 它依赖什么 / 不依赖什么

| 依赖 | 不依赖 |
|------|--------|
| 历史库里的 `segment_name` | Audience 里和谁组合出现 |
| 预训练 MiniLM（固定权重） | 在你们数据上 fine-tune |
| 训练时词表里出现过的 segment | 训练后新名字（需重训或实时 encode） |

MiniLM **不会**因为你们的 rich+miss 组合而更新向量；组合关系走 `similarity` / `emb_neighbors`。

---

## 流水线

```text
segment_name（历史库）
    → SentenceTransformer("all-MiniLM-L6-v2")
    → 384D 向量（L2 归一化）
    → 两两余弦，保留 score ≥ minScore 的 Top-K
    → artifacts/global/name_neighbors.json
```

`name_neighbors.json` 结构示例：

```json
"S1": [
  { "segmentId": "S14", "score": 0.45, "name": "female" },
  { "segmentId": "S15", "score": 0.41, "name": "Ms." }
]
```

含义：源 segment `S1`（名 miss）的名称近邻列表；`score` 为余弦相似度。

Recommendation 启动时加载该文件；有已选时：

```text
score += w_name * name_neighbors(selected → candidate)
```

再与 catalog 求交（除非 `expandBeyondCatalog`）。

---

## 安装 & 训练

```bash
cd training
source .venv/bin/activate
pip install -r requirements.txt

python train.py --name-backend embedding --as-of 2026-08-12
# 可选模型:
# --sentence-transformer-model sentence-transformers/all-MiniLM-L6-v2
```

| `--name-backend` | 行为 |
|------------------|------|
| `embedding`（默认） | 强制 sentence-transformers |
| `auto` | 失败则退回 tfidf |
| `tfidf` | 仅字面相似 |

`--name-alias-boost` 为可选补丁，不是主路径。

---

## 新名字（如 Madam）怎么办？

预计算表里没有 → 查不到。优先：**重训**把新 `segment_name` 编进 `name_neighbors`。  
Realtime LLM 不推荐做主路径；若要实时，用本机 MiniLM 对 catalog 内名称算余弦更合适。

---

## 相关

- [`ARTIFACTS.md`](ARTIFACTS.md) — 与 similarity / emb_neighbors 的区别  
- [`ALGORITHMS.md`](ALGORITHMS.md) — 全算法索引  
