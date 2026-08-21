# Training

三层产物：`global` + `industries` + `clients`。详见 [`docs/TENANCY.md`](../docs/TENANCY.md)。

## 生成 10 万行测试数据

```bash
cd training
source .venv/bin/activate
python scripts/generate_audiences.py --rows 100000 --output data/audiences.csv
```

行业：CPG / OEM / Retail / HealthCheck / Dining。可复现（`--seed 42`）。

## 从真实租户 taxonomy 造 CPG demo 数据

两步。第一步把 taxonomy 导出摊平成 segment 目录——一个 attribute 本身不是可投放的
segment，必须带上 value，所以 `nodeVOList` 的每个节点各成一行，以 `taxonomyId` 为主键，
命名为 `AttributeName > nodeValue`：

```bash
python scripts/parse_segment_mapping.py \
  --input "data/segment mapping.txt" \
  --output data/segments_cpg_demo.csv
```

第二步按美妆营销主题合成 audience。**共现结构来自主题而非随机采样**——
audience 内部的共同出现是 `clients/*/similarity.json` 的唯一信号来源，
随机组篮会让推荐模型学到噪声。人口属性刻意跨主题复用，制造主题之间的真实桥接：

```bash
python scripts/generate_cpg_audiences.py \
  --audiences 2000 \
  --client-name "Hack CPG Demo" \
  --output data/audience_hack_cpg_demo.csv
```

主题写在 `THEMES` 里（Anti-Aging Prestige / Gen Z Social Beauty Explorer /
Sensitive Skin Gentle Care / Deal Seeker / Mens Grooming 等 24 个）。
脚本会拿 taxonomy 校验每一个 `(attribute, value)`，写错的取值会打印出来而不是静默丢弃。

`audience_id` 是从 1 开始的连续整数，可用 `--audience-id-base` 加偏移（会检查不溢出 int64）。

`audience_name` 由主题名加**非核心** segment 的标签拼成，例如
`Curly and Coily Hair Care - Scalp Care Routine User and Frizz Concern`。
核心 segment 不进名字——它们正是主题名已经表达的内容，写进去只是重复。

segment 组合和名称都通过拒绝重采样保证**全局唯一**，生成结束前有断言兜底。
名称默认取 1 个限定词，只在撞名时才追加，最多 `--max-name-parts` 个。

拿它训练：

```bash
python train.py --input data/audience_hack_cpg_demo.csv --as-of 2026-08-19
```

## 环境

Python 3.10–3.12（sentence-transformers 的限制）：

```bash
cd training
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
source .venv/bin/activate
```

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

名称语义（默认 Vertex AI，服务端因此不需要 Python）：

```bash
gcloud auth application-default login
GOOGLE_CLOUD_PROJECT=eng-genai-pilot \
  python train.py --input data/audience_hack_cpg_demo.csv
```

`EMBEDDING_BACKEND` 控制后端，默认 `vertex`，失败时退回本机 sentence-transformers。相关变量：
`VERTEX_EMBEDDING_MODEL`（默认 `gemini-embedding-001`）、`VERTEX_EMBEDDING_LOCATION`（默认
`us-central1`）、`VERTEX_EMBEDDING_DIM`（默认 `768`）。

维度和模型 id 会写进 `artifacts/global/meta.json`，服务启动时比对；改了这里就必须重训，
否则查询向量和存量向量不在同一空间，检索会退回名称匹配。

```bash
# 不出网的老路径：本机 MiniLM（服务端需相应设 SEGMENT_QUERY_EMBED_PROVIDER=local）
EMBEDDING_BACKEND=sentence_transformers python train.py --name-backend embedding
```

详见 [`docs/NAME_SIMILARITY.md`](../docs/NAME_SIMILARITY.md)

## 产物

```text
artifacts/global/           # 含 name_neighbors.json + segment_name_embeddings.json（全局一份）
artifacts/industries/{slug}/
artifacts/clients/{slug}/   # similarity + emb_neighbors（Serving）；embeddings 仅训练产物
```

各文件用途：[`docs/ARTIFACTS.md`](../docs/ARTIFACTS.md)  
隔离策略：[`docs/TENANCY.md`](../docs/TENANCY.md)  
算法：[`docs/ALGORITHMS.md`](../docs/ALGORITHMS.md)