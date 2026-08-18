# Query understanding（自然语言 → industry + concept）

`/api/chat/recommend` 的第一步：把用户 query 解析成结构化意图，再交给 embedding 检索和 ranking。

**默认厂商：OpenAI**（`gpt-4o-mini`，`https://api.openai.com/v1`）。

---

## 两种 provider

| provider | 说明 | 是否需要 API key |
|----------|------|------------------|
| `openai`（默认） | OpenAI Chat Completions，输出 JSON | ✅ `OPENAI_API_KEY` |
| `rule` | 关键词 / alias / filler 规则 | ❌ |

`llm` 仍可作为 `openai` 的别名。

配置：`recommendation/src/main/resources/application.yml`

```yaml
segment-rec:
  query-understanding:
    provider: openai
    api-key: ${OPENAI_API_KEY:}
    base-url: https://api.openai.com/v1
    model: gpt-4o-mini
    temperature: 0
    timeout-seconds: 30
    json-response: true
    fallback-to-rule: true
```

---

## 启动前

```bash
export OPENAI_API_KEY=sk-...

cd recommendation
mvn -s maven-settings.xml spring-boot:run
```

可选覆盖：

| 变量 | 默认 | 作用 |
|------|------|------|
| `OPENAI_API_KEY` | （必填） | OpenAI API key |
| `SEGMENT_QUERY_LLM_MODEL` | `gpt-4o-mini` | 可换成 `gpt-4o` |
| `SEGMENT_QUERY_LLM_BASE_URL` | `https://api.openai.com/v1` | 一般不用改 |
| `SEGMENT_QUERY_LLM_PROVIDER` | `openai` | 设为 `rule` 可关掉 LLM |

换更强模型：

```bash
export OPENAI_API_KEY=sk-...
export SEGMENT_QUERY_LLM_MODEL=gpt-4o
```

---

## LLM 输出格式

```json
{
  "industry": "CPG",
  "concept": "high value premium shoppers",
  "excludeConcepts": ["female"]
}
```

- `industry`：必须是 artifacts 里已知行业之一，或 `null`
- `concept`：给 `ConceptRetrievalService` 做 segment 名称 embedding 检索（**不要**把否定条件写进 concept）
- `excludeConcepts`：不想要的人群概念；空数组表示没有排除

例如：`推荐CPG高价值人群，不想要女性` → concept=`high value...`，excludeConcepts=`["female"]`。

排除是硬过滤：seed 检索和最终 ranking 都会丢掉匹配的 segment。  
`female` 会展开到 `women` / `miss` / `Ms.` / `Madam` / `lady` 等名称别名。

请求里若已带 `industry` 字段，会优先于 LLM 推断结果。

---

## 失败与回退

`fallback-to-rule: true`（默认）时，OpenAI 超时 / 鉴权失败 / JSON 解析失败 → 自动退回规则解析，`understandingStrategy` 为 `rule_fallback_after_llm_error`。

设为 `false` 则直接报错，便于排查 key / 网络。

---

## 与 query-embedding 的区别

| 配置块 | 用途 | 模型 |
|--------|------|------|
| `query-understanding` | 理解自然语言，抽 industry + concept + 排除项 | **OpenAI** `gpt-4o-mini` |
| `query-embedding` | concept 文本 → 向量，检索 seed segments | **本地** `sentence-transformers/all-MiniLM-L6-v2` |

OpenAI 只负责「听懂」；检索和打分不走 OpenAI。

---

## 相关代码

- `service/query/LlmQueryUnderstandingProvider.java`
- `service/query/RuleQueryUnderstandingProvider.java`
- `service/QueryUnderstandingService.java`（按 provider 路由 + 回退）
- `service/query/SegmentExclusionFilter.java`（不想要 / 排除）
