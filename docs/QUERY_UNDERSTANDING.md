# Query understanding（自然语言 → industry + concept）

`/api/audience/intelligence` 路由到 `chat_recommend` 后的第一步：把用户 query 解析成结构化意图，
再交给 embedding 检索和 ranking。

**默认厂商：Vertex AI 上的 Gemini**（`gemini-3.5-flash`），鉴权走 **ADC + IAM，不用 API key**。

---

## 三种 provider

| provider | 说明 | 鉴权方式 |
|----------|------|----------|
| `gemini`（默认） | Vertex AI `generateContent`，用 `responseSchema` 约束 JSON | ADC + IAM（`roles/aiplatform.user`） |
| `openai` | OpenAI Chat Completions，输出 JSON | `OPENAI_API_KEY` |
| `rule` | 关键词 / alias / filler 规则 | 无 |

`google` 是 `gemini` 的别名，`llm` 是 `openai` 的别名。

配置：`recommendation/src/main/resources/application.yml`

```yaml
segment-rec:
  query-understanding:
    provider: gemini          # gemini | openai | rule
    fallback-to-rule: true
    # 以下几项只在 provider=openai 时生效
    api-key: ${OPENAI_API_KEY:}
    base-url: https://api.openai.com/v1
    model: gpt-4o-mini
  gemini:
    project-id: ${GOOGLE_CLOUD_PROJECT:}   # 留空则用 ADC service account 自带的 project
    location: global                        # global 或具体 region，如 us-central1
    endpoint: ''                            # 只在 Private Service Connect 等场景覆盖
    model: gemini-3.5-flash
    timeout-seconds: 30
```

`segment-rec.gemini` 这一块同时服务于 query 理解和 `/api/audience/intelligence` 的工具路由。

> **不要给 Gemini 3 设 temperature。** 官方建议保持默认的 `1.0`；调到 0 反而可能出现循环或推理退化，
> 所以 `temperature` 默认不下发。只有把 `model` 换回 2.x 时才建议显式设置。

---

## 鉴权：ADC + IAM

调用地址（`location=global` 时）：

```
POST https://aiplatform.googleapis.com/v1/projects/{project}/locations/global/publishers/google/models/{model}:generateContent
Authorization: Bearer <ADC 签发的 access token>
```

region 模式下 host 变成 `{location}-aiplatform.googleapis.com`，路径里的 location 同步替换。

ADC 的查找顺序（Google 官方顺序，代码不自己实现）：

1. `GOOGLE_APPLICATION_CREDENTIALS` 指向的 service account key 文件
2. `gcloud auth application-default login` 生成的用户凭据
3. GCE / GKE / Cloud Run / Cloud Functions 上绑定的 service account（**推荐**，无需任何密钥文件）

需要的 IAM 权限：调用方 principal 在目标 project 上有 **`roles/aiplatform.user`**
（或至少 `aiplatform.endpoints.predict`）。token scope 为 `https://www.googleapis.com/auth/cloud-platform`，
由代码自动加上。

project id 解析顺序：`segment-rec.gemini.project-id` → `GOOGLE_CLOUD_PROJECT` / `GCLOUD_PROJECT`
→ service account 自带的 project → ADC 的 quota project。都取不到就判定为不可用并回退。

> 凭据只解析一次并缓存，access token 过期时自动刷新。**换凭据需要重启服务。**

---

## 启动前

```bash
# 本地开发
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=your-project

cd recommendation
mvn -s maven-settings.xml spring-boot:run
```

可选覆盖：

| 变量 | 默认 | 作用 |
|------|------|------|
| `GOOGLE_CLOUD_PROJECT` | （ADC 推断） | Vertex AI 调用所在 project |
| `GOOGLE_CLOUD_LOCATION` | `global` | 换成 `us-central1` 等 region |
| `GEMINI_MODEL` | `gemini-3.5-flash` | 可换成 `gemini-3.6-flash` 等 |
| `VERTEX_AI_ENDPOINT` | — | 覆盖 host，用于 PSC / 自定义接入点 |
| `SEGMENT_QUERY_LLM_PROVIDER` | `gemini` | 设为 `openai` 或 `rule` |
| `OPENAI_API_KEY` | — | 只在 provider=openai 时需要 |
| `SEGMENT_QUERY_LLM_MODEL` | `gpt-4o-mini` | 只在 provider=openai 时生效 |

常见报错：

| 现象 | 原因 |
|------|------|
| `could not determine the Google Cloud project` | ADC 有了但没 project，设 `GOOGLE_CLOUD_PROJECT` |
| `Vertex AI ADC unavailable` | 没跑 `application-default login`，也没有绑定 service account |
| HTTP 403 `PERMISSION_DENIED` | principal 缺 `roles/aiplatform.user`，或 project 写错 |

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

`fallback-to-rule: true`（默认）时，LLM 超时 / 鉴权失败 / JSON 解析失败 → 自动退回规则解析，`understandingStrategy` 为 `rule_fallback_after_llm_error`。

设为 `false` 则直接报错，便于排查 key / 网络。

成功时 `understandingStrategy` 会写明厂商和模型，例如 `gemini:gemini-3.5-flash`、`llm:gpt-4o-mini`。

---

## 与 query-embedding 的区别

| 配置块 | 用途 | 模型 |
|--------|------|------|
| `query-understanding` | 理解自然语言，抽 industry + concept + 排除项 | **Gemini** `gemini-3.5-flash` |
| `query-embedding` | concept 文本 → 向量，检索 seed segments | **Vertex AI** `gemini-embedding-001`（768 维） |

LLM 只负责「听懂」；检索和打分不走 LLM。两个配置块共用同一套 ADC 凭据，但调的是不同 API：
理解走 `:generateContent`，embedding 走 `:predict`。生成式模型产不出向量，两者不能互相替代。

`query-embedding` 的 `provider` 可设为 `local` 切回本机 sentence-transformers（查询不出网，但
服务端需要 `training/.venv`）。切换后必须用相同后端重训 artifacts，否则向量不可比。

---

## 相关代码

- `service/llm/VertexAiCredentials.java`（ADC 解析、token 刷新、project 推断）
- `service/llm/GeminiClient.java`（`generateContent`：结构化 JSON + function calling）
- `service/query/GeminiQueryUnderstandingProvider.java`
- `service/query/LlmQueryUnderstandingProvider.java`（OpenAI）
- `service/query/RuleQueryUnderstandingProvider.java`
- `service/query/QueryUnderstandingSupport.java`（各厂商共用的 prompt 与结果映射）
- `service/QueryUnderstandingService.java`（按 provider 路由 + 回退）
- `service/query/SegmentExclusionFilter.java`（不想要 / 排除）
- `service/agent/`（`/api/audience/intelligence` 的工具路由）
