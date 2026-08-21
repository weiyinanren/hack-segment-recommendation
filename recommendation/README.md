# Recommendation service

三层混合打分 + 全局名称近邻；默认只返回 client catalog 内 Segment。

## 启动

```bash
cd recommendation
mvn -s maven-settings.xml spring-boot:run
```

加载：`artifacts/global/name_neighbors.json`、`artifacts/global/segment_name_embeddings.json` 与 `artifacts/clients/{client}/…`。  
热加载：`POST /api/admin/reload`。

### Query 理解（OpenAI）

默认调用 OpenAI `gpt-4o-mini`。启动前设置：

```bash
export OPENAI_API_KEY=sk-...
# 可选：换成更强模型
# export SEGMENT_QUERY_LLM_MODEL=gpt-4o
```

详见 [`docs/QUERY_UNDERSTANDING.md`](../docs/QUERY_UNDERSTANDING.md)。

关掉 LLM、只用规则：`SEGMENT_QUERY_LLM_PROVIDER=rule`。  
OpenAI 失败且 `fallback-to-rule: true` 时也会自动退回规则。

## API

```bash
# 自然语言 query -> industry/concept -> seed segments -> final recommendations
curl -s -X POST http://localhost:8080/api/chat/recommend \
  -H 'Content-Type: application/json' \
  -d '{
    "clientName":"UnileverDemo",
    "query":"你帮我推荐一些CPG行业的高价值人群，不想要女性",
    "topN":5
  }'

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

`/api/chat/recommend` 返回三块：

- `parsedQuery`: LLM（或规则）抽取的 `industry` / `concept` / `excludeConcepts`
- `seedSegments`: 概念检索得到的种子 segments
- `recommendations`: 复用现有 ranking 的最终结果

当前 `ConceptRetrievalService` 是独立模块；默认优先走 query embedding 检索：

- query/concept：本机 `sentence-transformers/all-MiniLM-L6-v2`
- candidate 向量：`artifacts/global/segment_name_embeddings.json`
- fallback：规则 + 名称 lexical 检索

## 文档

- [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) — Offline + online overview (English)
- [`docs/ARTIFACTS.md`](../docs/ARTIFACTS.md) — 产物字典（embeddings vs emb_neighbors 等）
- [`docs/TENANCY.md`](../docs/TENANCY.md) — 隔离与打分
- [`docs/QUERY_UNDERSTANDING.md`](../docs/QUERY_UNDERSTANDING.md) — LLM query 解析配置
- [`docs/NAME_SIMILARITY.md`](../docs/NAME_SIMILARITY.md) — 名称近邻
