# Recommendation service

三层混合打分 + 全局名称近邻；默认只返回 client catalog 内 Segment。

## 启动

```bash
cd recommendation
mvn -s maven-settings.xml spring-boot:run
```

加载：`artifacts/global/name_neighbors.json`、`artifacts/global/segment_name_embeddings.json` 与 `artifacts/clients/{client}/…`。  
热加载：`POST /api/admin/reload`。

### Query 理解（Gemini / OpenAI / 规则）

默认走 Vertex AI 上的 Gemini `gemini-3.5-flash`，**不用 API key**，走 ADC + IAM。启动前：

```bash
# 本地开发：用自己的身份签发 ADC
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=your-project

# 可选：换模型 / 换区域
# export GEMINI_MODEL=gemini-3.6-flash
# export GOOGLE_CLOUD_LOCATION=us-central1
```

部署到 GCE / GKE / Cloud Run 时不用配任何凭据，ADC 自动取绑定的 service account；
只需给该 principal 授 `roles/aiplatform.user`。

切换 provider：`SEGMENT_QUERY_LLM_PROVIDER=gemini|openai|rule`。  
用 OpenAI 时另外设置 `OPENAI_API_KEY`（默认 `gpt-4o-mini`）。  
LLM 失败且 `fallback-to-rule: true` 时自动退回规则解析。

详见 [`docs/QUERY_UNDERSTANDING.md`](../docs/QUERY_UNDERSTANDING.md)。

> `segment-rec.gemini.temperature` 默认不下发。Gemini 3 系列按默认 1.0 调优，
> 强行调到 0 可能出现循环或推理退化；只有切回 2.x 模型时才建议显式设置。

### Gemini 工具路由（`/api/agent/ask`）

同一个 Gemini 配置还驱动一层工具路由：把用户的自然语言交给 Gemini，由它决定调用本服务的哪个能力，
再由服务执行并让 Gemini 用自然语言总结结果。

当前注册的工具：

| tool | 对应能力 | 何时选中 |
| --- | --- | --- |
| `chat_recommend` | `ConversationalRecommendationService` | 任何人群描述类请求（默认兜底） |
| `recommend_segments` | `RankingService` | 已经给出 segment ID 找相似，或只要某行业热门 |
| `service_health` | `ArtifactStore` 概览 | 问「有哪些行业/客户」「服务正常吗」 |
| `reload_artifacts` | `ArtifactStore.reload()` | 显式要求重载，默认不暴露给模型 |

安全边界：

- `clientName` 只来自请求体，模型无法选择或伪造租户；未知租户在调用 LLM 之前就返回 400。
- `selectedSegmentIds` 同样只来自请求体：模型无从得知 segment ID，由 UI 把用户已选中的 segment 传进来。
- `reload_artifacts` 会改服务状态，默认隐藏；需要时设 `SEGMENT_AGENT_ALLOW_ADMIN=true`。
- ADC 不可用或路由失败时退回固定工具 `chat_recommend`，接口仍可用，
  `routingStrategy` 会写明原因（`heuristic:gemini_unavailable` / `heuristic:gemini_error`）。
  带 `selectedSegmentIds` 时改为退回 `recommend_segments`（后缀 `+seeds`），
  所以 lookalike 场景不依赖 Gemini 也能走通。

关掉整层路由：`SEGMENT_AGENT_ENABLED=false`；关掉结果总结：`SEGMENT_AGENT_SUMMARIZE=false`。

## API

```bash
# Gemini 决定调用哪个能力，再执行并总结
curl -s -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{
    "clientName":"UnileverDemo",
    "query":"推荐一些CPG行业的高价值人群，不想要女性",
    "topN":5
  }'

# 同一入口的 lookalike：UI 把用户已选中的 segment 一并传入，路由改走 recommend_segments
curl -s -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{
    "clientName":"Hack CPG Demo",
    "query":"帮我找和这个人群类似的",
    "selectedSegmentIds":["626558140458404040"],
    "topN":5
  }'

# 自然语言 query -> industry/concept -> seed segments -> final recommendations
curl -s -X POST http://localhost:8080/api/chat/recommend \
  -H 'Content-Type: application/json' \
  -d '{
    "clientName":"UnileverDemo",
    "query":"你帮我推荐一些CPG行业的高价值人群，不想要女性",
    "topN":5
  }'

# 建 audience 场景一：冷启动，出该租户所在行业的 Top-N 热门
# 不传 industry 时由 client 的 popularity 推断主行业，响应回传 industry / industrySource
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"UnileverDemo","topN":10}'

# 建 audience 场景二：已选一个 segment，找关联度最高的
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"UnileverDemo","selectedSegmentIds":["SG_INCOME_50_75K"],"topN":10}'

# 单选（CPG）
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"UnileverDemo","industry":"CPG","selectedSegmentIds":["SG_MISS"],"topN":5}'

# 多选（Retail）
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"FashionHub","industry":"Retail","selectedSegmentIds":["RTL_FASH","RTL_BEAUTY"],"topN":5}'

# 可扩展到 catalog 外（inCatalog=false）
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"HealthCheckA","industry":"HealthCheck","topN":5,"expandBeyondCatalog":true}'
```

`/api/agent/ask` 返回：`tool`（选中的能力）、`toolArguments`（模型填的参数）、
`routingStrategy`（`gemini:<model>` 或 `heuristic:*`）、`reply`（自然语言总结）、`result`（原始返回）。

`/api/recommend/segments` 的 `items` 带 `segmentId` / `segmentName` / `score` 及六个通道分；
响应顶层的 `industry` 是本次实际使用的行业，`industrySource` 为 `request`（调用方传的）、
`client_primary`（由租户 popularity 推断）或 `none`（该租户无 popularity 数据）。

`/api/chat/recommend` 返回三块：

- `parsedQuery`: LLM（或规则）抽取的 `industry` / `concept` / `excludeConcepts`
- `seedSegments`: 概念检索得到的种子 segments
- `recommendations`: 复用现有 ranking 的最终结果

当前 `ConceptRetrievalService` 是独立模块；默认优先走 query embedding 检索：

- query/concept：本机 `sentence-transformers/all-MiniLM-L6-v2`
- candidate 向量：`artifacts/global/segment_name_embeddings.json`
- fallback：规则 + 名称 lexical 检索

## 文档

- [`docs/ARTIFACTS.md`](../docs/ARTIFACTS.md) — 产物字典（embeddings vs emb_neighbors 等）
- [`docs/TENANCY.md`](../docs/TENANCY.md) — 隔离与打分
- [`docs/QUERY_UNDERSTANDING.md`](../docs/QUERY_UNDERSTANDING.md) — LLM query 解析配置
- [`docs/NAME_SIMILARITY.md`](../docs/NAME_SIMILARITY.md) — 名称近邻
