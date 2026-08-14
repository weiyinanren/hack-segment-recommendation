# Three-layer artifacts + catalog policy

```text
artifacts/
  global/
    segment_prior.json
    segment_names.json
    name_neighbors.json     # MiniLM 名称近邻（全局一份）★
    meta.json
  industries/{IndustrySlug}/
    popularity.json
    meta.json
  clients/{ClientSlug}/
    popularity.json
    similarity.json          # Item-CF ★
    emb_neighbors.json       # 行为向量近邻 ★
    embeddings.json          # 向量原文（Online 当前不用）
    segment_catalog.json     # ★
    meta.json
```

各文件职责详见 [`ARTIFACTS.md`](ARTIFACTS.md)。

---

## Serving 策略（Client D 举例）

**默认** `expandBeyondCatalog=false`：

```text
推荐结果 ⊆ ClientD.segment_catalog
score = mix(global, industry, clientPop, sim, emb_nbr, name_nbr)
```

**扩展** `expandBeyondCatalog=true`：可出 catalog 外，响应带 `inCatalog`。

```bash
# 单选
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","selectedSegmentIds":["S1"],"topN":5}'

# 多选（S1+S2 → 可推 S3：对每个已选查邻居后 max 融合）
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","selectedSegmentIds":["S1","S2"],"topN":5}'

# 扩展目录外
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","topN":5,"expandBeyondCatalog":true}'
```

---

## 打分

```text
score =
  w_global   * global_prior
+ w_industry * industry_pop
+ w_client   * client_pop
+ w_sim      * client_similarity       # similarity.json
+ w_emb      * client_emb_neighbors    # emb_neighbors.json
+ w_name     * global_name_neighbors   # name_neighbors.json
```

默认权重见 `recommendation/.../application.yml`（含 `name-similarity`）。

- 共现 / 行为 embedding：**仅 client 层**  
- 名称近邻：**仅 global 一份**，与组合无关  

### 多选聚合

对 `selectedSegmentIds` 中每个 id 查邻居，同一候选同一通道取 **max**，再加权。  
支持「选了 S1 和 S2 推出 S3」；不是严格组合规则匹配。

---

## 隔离边界

| 层 | 跨 client？ | 内容 |
|----|-------------|------|
| global | ✅ | 热门先验 + **名称近邻** |
| industry | ✅ 聚合 | 行业热门 |
| client | ❌ | 共现、行为 embedding、catalog |

生产建议：`segment_catalog` 对接权限系统完整授权列表。

---

## 演进备忘（未实现）

行业级共现先验 + 名称映射：可用于「别家 rich+miss → 本家选 rich person、目录只有 female → 推 female」。  
见讨论结论；落地需新增 `industries/.../cooccurrence.json` 与 Serving 多跳。
