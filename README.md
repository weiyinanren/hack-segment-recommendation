# Segment Recommendation

`training`（Python）产出 artifacts → `recommendation`（Java）做 Top-N 推荐。

```
Hack/
├── segment_tranning.md          # design spec
├── artifacts/                   # shared model outputs (generated)
├── training/                    # Python：L0/L1 训练
│   ├── train.py
│   ├── data/audiences.csv
│   └── src/
├── recommendation/              # Java Spring Boot：推荐 API
│   └── src/main/java/...
└── docs/
```

## Quick start

### 1. Training

首次：

```bash
cd training
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python train.py
```

之后重新训练：

```bash
cd training
source .venv/bin/activate
python train.py
```

只训某个 client：

```bash
python train.py --client AcmeAuto
```

常用参数：

```bash
python train.py --input data/audiences.csv --output ../artifacts
python train.py --skip-embeddings
python train.py --vector-size 128 --top-k-similarity 20 --top-n-popularity 50
```

CSV 列：`client_name,industry,audience_id,segment_id`。

模式：**L0 全局先验（可共享聚合）+ L1 client 私有行为**，Serving 按 catalog 过滤。

产物：

```text
artifacts/
  index.json
  global/                      # 平台先验
  industries/{IndustrySlug}/   # 行业热门
  clients/{ClientSlug}/        # 私有 CF + catalog
```

Serving：默认只返回 client catalog 内 Segment；`expandBeyondCatalog=true` 可扩展发现。  
说明见 [`docs/TENANCY.md`](docs/TENANCY.md)。

### 2. Recommendation service

先打包（可选，`spring-boot:run` 会自动编译）：

```bash
cd recommendation
mvn -s maven-settings.xml -DskipTests package
```

> 使用 `-s maven-settings.xml`，避免本机 `~/.m2/settings.xml` 把 Central 指到本地 `file://` 导致依赖拉不下来。

方式 A — Maven 直接跑：

```bash
export GOOGLE_CLOUD_PROJECT=eng-genai-pilot   # 需先 gcloud auth application-default login
cd recommendation
mvn -s maven-settings.xml spring-boot:run
```

自然语言入口是 `/api/audience/intelligence`，由 Gemini 经 Vertex AI（ADC，无 API key）做路由和语义解析。
未配置凭据时它不会失败，而是降级为关键词路由 + 名称匹配检索。详见
[`docs/QUERY_UNDERSTANDING.md`](docs/QUERY_UNDERSTANDING.md)。

方式 B — 跑已打好的 jar：

```bash
cd recommendation
java -jar target/segment-rec-recommendation-0.1.0-SNAPSHOT.jar
```

自定义 artifacts 路径（默认 `../artifacts`）：

```bash
SEGMENT_REC_ARTIFACTS=/Users/stevlu/Desktop/Hack/artifacts \
  java -jar target/segment-rec-recommendation-0.1.0-SNAPSHOT.jar
```

服务地址：`http://localhost:8080`

### 3. 试 API

健康检查：

```bash
curl -s http://localhost:8080/api/health
```

冷启动（行业热门）：

```bash
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","topN":5}'
```

已选 Segment 后融合排序：

```bash
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","selectedSegmentIds":["S1","S3"],"topN":5}'
```

扩展到 catalog 外（行业/全局发现）：

```bash
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","topN":5,"expandBeyondCatalog":true}'
```

重新训练后热加载产物：

```bash
curl -s -X POST http://localhost:8080/api/admin/reload
```

## Ranking

```
score = w_g*global + w_i*industry + w_c*client + w_sim*sim + w_emb*emb_nbr + w_name*name_nbr
```

多选 Segment：对每个已选查邻居后取 max 再融合（可选中 S1+S2 推出 S3）。  
默认结果 ∈ client catalog；`expandBeyondCatalog` 可放宽。

产物说明见 [`docs/ARTIFACTS.md`](docs/ARTIFACTS.md)。

## Knowledge base

- [`docs/ARTIFACTS.md`](docs/ARTIFACTS.md) — 产物字典（含 embeddings vs emb_neighbors、多选）
- [`docs/NAME_SIMILARITY.md`](docs/NAME_SIMILARITY.md) — 全局名称近邻（MiniLM）
- [`docs/ALGORITHMS.md`](docs/ALGORITHMS.md) — 训练算法
- [`docs/TENANCY.md`](docs/TENANCY.md) — 三层隔离与 Serving
- [`docs/QUERY_UNDERSTANDING.md`](docs/QUERY_UNDERSTANDING.md) — OpenAI query 解析（industry / concept / 排除项）
- [`segment_tranning.md`](segment_tranning.md) — 原始设计 Spec
- [`training/README.md`](training/README.md) — 训练启动与参数
- [`recommendation/README.md`](recommendation/README.md) — 推荐 API 与启动
