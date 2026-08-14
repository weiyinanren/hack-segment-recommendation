# Training Algorithms Knowledge Base

本文档说明 `training/train.py` 用到的算法、公式与函数入口。

对应设计：[`segment_tranning.md`](../segment_tranning.md)  
产物用法：[`ARTIFACTS.md`](ARTIFACTS.md)

---

## 总览

```text
CSV (client_name, industry, audience_id, segment_id[, segment_name, ...])
        │
        ├── global/       segment_prior + segment_names + name_neighbors (MiniLM)
        ├── industries/   行业 popularity
        └── clients/      私有 popularity + similarity + embeddings/emb_neighbors + catalog
                │
                └── Recommendation：多路融合 + catalog 过滤
```

隔离与 Serving：[`TENANCY.md`](TENANCY.md)。名称近邻：[`NAME_SIMILARITY.md`](NAME_SIMILARITY.md)。

| 阶段 | 算法 | 入口 | 产物 |
|------|------|------|------|
| Popularity | 加权频次 | `build_popularity` | `popularity.json` / `segment_prior.json` |
| Item-CF | 加权共现余弦 | `build_similarity` | `clients/.../similarity.json` |
| 行为 Embedding | PPMI + SVD | `build_embeddings` | `embeddings.json` + **`emb_neighbors.json`（Serving 用）** |
| 名称 Embedding | MiniLM | `build_name_neighbors` | **`global/name_neighbors.json`（Serving 用，全局一份）** |

---

## 1. Popularity

**文件：** `src/popularity.py`

按 industry（或全局）对 segment 做加权求和，min-max 归一化到 `[0,1]`。  
样本权重见 [`SAMPLE_WEIGHTS.md`](SAMPLE_WEIGHTS.md)。

---

## 2. Item-Based CF（`similarity.json`）

**文件：** `src/similarity.py`

Audience = basket；加权共现后：

\[
\mathrm{sim}(a,b)=\frac{\mathrm{co}_w(a,b)}{\sqrt{\mathrm{count}_w(a)\cdot\mathrm{count}_w(b)}}
\]

**仅本 client 数据**；Serving 查 `similarity.json`。

---

## 3. 行为 Embedding（`embeddings.json` / `emb_neighbors.json`）

**文件：** `src/embeddings.py`

1. 本 client 共现矩阵 → PPMI → Truncated SVD（默认 128 维）→ L2 归一化 → `embeddings.json`  
2. 向量余弦 Top-K → `emb_neighbors.json`

| 文件 | Online |
|------|--------|
| `emb_neighbors.json` | ✅ Ranking 使用（`w_emb`） |
| `embeddings.json` | ❌ 当前不读；训练中间产物 / 将来多选向量池化可用 |

与 `global/name_neighbors.json` 无关：前者是**选购行为**像不像，后者是**名字**像不像。

---

## 4. 名称近邻（`global/name_neighbors.json`）

**文件：** `src/name_similarity.py`

- 输入：历史库里的 `segment_name`（与「和谁组合」无关）  
- 模型：`sentence-transformers/all-MiniLM-L6-v2`（预训练，**不**在你们数据上 fine-tune）  
- 输出：全局一份 `name_neighbors.json`  
- Serving：有已选时 `score += w_name * name_sim`

详见 [`NAME_SIMILARITY.md`](NAME_SIMILARITY.md)。

---

## 5. Recommendation 融合与多选

```text
score = w_g*global + w_i*industry + w_c*client_pop
      + w_sim*sim + w_emb*emb_nbr + w_name*name_nbr
```

**多选** `selectedSegmentIds: ["S1","S2"]`：对每个已选分别查三张邻居表，同一候选各通道取 **max** 再加权 → 可以推出 S3（只要 S3 是 S1 或 S2 的近邻等）。  
这是并集聚合，不是严格的「S1∧S2 组合规则」。

默认结果 ⊆ `segment_catalog`；`expandBeyondCatalog` 可放宽。

完整产物表：[`ARTIFACTS.md`](ARTIFACTS.md)。

---

## 6. 演进（未实现）

| 方向 | 说明 |
|------|------|
| 行业级共现先验 | 跨 client 聚合 pair，配合名称映射做同义迁移 |
| 多选向量平均 | 用 `embeddings.json`：`mean(v_S1,v_S2)` 再检索 |
| MiniLM fine-tune | 领域词很偏时再考虑 |

---

## 相关文件

| 路径 | 说明 |
|------|------|
| `training/train.py` | 训练入口 |
| `training/src/weights.py` | 样本权重 |
| `training/src/popularity.py` | Popularity |
| `training/src/similarity.py` | Item-CF |
| `training/src/embeddings.py` | 行为 PPMI+SVD |
| `training/src/name_similarity.py` | 名称 MiniLM |
| `docs/ARTIFACTS.md` | 产物字典 |
| `docs/SAMPLE_WEIGHTS.md` | created_at / distributed |
| `recommendation/` | 在线服务 |
