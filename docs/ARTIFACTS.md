# Artifacts 说明

训练产物目录与 Serving 用法。路径均相对 `artifacts/`。

---

## 目录结构

```text
artifacts/
  index.json
  global/
    segment_prior.json      # 平台级 Segment 加权热门
    segment_names.json      # segmentId → segment_name
    segment_name_embeddings.json # segmentId → 384D 名称向量 ★ Chat concept retrieval
    name_neighbors.json     # 名称语义近邻（MiniLM）★ Serving 使用
    meta.json
  industries/{Industry}/
    popularity.json         # 行业热门
    meta.json
  clients/{Client}/
    popularity.json         # 本 client 热门
    similarity.json         # 本 client Item-CF 共现近邻 ★ Serving
    emb_neighbors.json      # 本 client 行为向量近邻 ★ Serving
    embeddings.json         # 本 client 128D 行为向量（训练中间产物）
    segment_catalog.json    # 本 client 可用 Segment 白名单 ★ Serving
    meta.json
```

---

## 三张「邻居表」对比

| 文件 | 范围 | 依据 | Serving？ | 回答的问题 |
|------|------|------|-----------|------------|
| `global/name_neighbors.json` | **全局一份即可** | embedding 模型对 `segment_name` 的向量余弦 | ✅ | 名字语义像谁？与共现无关 |
| `clients/.../similarity.json` | 本 client | Audience 直接共现 | ✅ | 常和谁一起被选？ |
| `clients/.../emb_neighbors.json` | 本 client | PPMI+SVD 行为向量近邻 | ✅ | 选购行为模式像谁？（可含间接关系） |

`name_neighbors` **不按 client / industry 拆分**；Serving 再用 `segment_catalog` 过滤。

`segment_name_embeddings.json` 也是 **global 一份**，给 `/api/audience/intelligence` 的 `chat_recommend` 链路做 `concept -> seed segments` 的实时余弦检索。

### embedding 模型必须两侧一致

用户的查询是训练时不存在的新文本，只能在线编码，才能和这里存好的向量比余弦。不同模型的向量
空间互不相通，即便维度相同也不可比，所以换模型必须重训。`meta.json` 里记录了判据：

```json
"segmentNameEmbeddings": { "backendUsed": "vertex", "model": "gemini-embedding-001", "vectorDim": 768 }
```

服务启动时读出 `model` / `vectorDim`，与在线 provider 比对，不一致就退回名称匹配并告警。老产物
没有 `model` 字段时只校验维度，重训后保护才完整。

---

## `embeddings.json` vs `emb_neighbors.json`

| | `embeddings.json` | `emb_neighbors.json` |
|--|-------------------|----------------------|
| 内容 | segmentId → 128 维向量 | segmentId → Top-K 近邻 + score |
| Online | **当前不读** | **Ranking 使用**（`w_emb`） |
| 用途 | 训练中间结果；调试；将来若做「多选向量平均再检索」 | 预计算查表，低延迟 |

结论：线上发布 **可以只带 `emb_neighbors.json`**；训练机保留 `embeddings.json` 即可。

生成关系：

```text
baskets → PPMI+SVD → embeddings.json
                    → cosine Top-K → emb_neighbors.json
```

---

## Serving 打分（有已选 Segment 时）

```text
score =
  w_global   * global_prior
+ w_industry * industry_pop
+ w_client   * client_pop
+ w_sim      * similarity(selected → cand)      # clients/.../similarity.json
+ w_emb      * emb_neighbors(selected → cand)   # clients/.../emb_neighbors.json
+ w_name     * name_neighbors(selected → cand)  # global/name_neighbors.json
```

冷启动（`selectedSegmentIds` 为空）：只用 global / industry / client popularity。

默认权重见 `recommendation/src/main/resources/application.yml`。

---

## industry 缺省时的推断

请求里不传 `industry` 时，服务端会从该 client 的 `popularity.json` 推断「主行业」——
取各行业下 `count` 之和最大的那个，同分按名称字典序。

**为什么用 `count` 而不是 `score`：** `score` 是在**每个行业内部**做 min-max 归一的
（见 `training/src/popularity.py`），所以每个行业的头部 segment 都是 `1.0`，跨行业比较没有意义。
`count` 是绝对选择次数，才能反映租户的业务重心。

响应体回传实际使用的行业和来源：

| `industrySource` | 含义 |
|------------------|------|
| `request` | 调用方显式传了 `industry` |
| `client_primary` | 由该 client 的 popularity 推断 |
| `none` | 该 client 没有任何 popularity 数据，行业通道为空 |

一个 client 可以跨多个行业（`popularity.json` 就是按行业分组的 map），
所以调用方明确知道行业时仍应显式传，推断只是缺省兜底。

---

## 多选 Segment（S1 + S2 → 能否推 S3？）

**可以，现有逻辑已支持。**

```json
{
  "clientName": "AcmeAuto",
  "industry": "Auto",
  "selectedSegmentIds": ["S1", "S2"],
  "topN": 10
}
```

对每个已选 id 分别查 `similarity` / `emb_neighbors` / `name_neighbors`，同一候选上各通道分数取 **max** 再加权。

因此：只要 S3 是 S1 **或** S2 的近邻（或热门通道够强），且通过 catalog 过滤，就会进入推荐。

这是「并集 + max」，**不是**「必须同时匹配 S1∧S2 组合模式」。若以后要组合级模式，可再做 basket 共现或 `mean(emb(S1),emb(S2))` 检索（那时会用到 `embeddings.json`）。

---

## Catalog 与 expand

- 默认：`expandBeyondCatalog=false` → 结果 ⊆ 本 client `segment_catalog.json`
- `expandBeyondCatalog=true` → 可出目录外候选，响应带 `inCatalog`

详见 [`TENANCY.md`](TENANCY.md)。

---

## 相关文档

- [`ALGORITHMS.md`](ALGORITHMS.md) — 训练算法
- [`NAME_SIMILARITY.md`](NAME_SIMILARITY.md) — 名称近邻 / MiniLM
- [`SAMPLE_WEIGHTS.md`](SAMPLE_WEIGHTS.md) — 样本权重
- [`TENANCY.md`](TENANCY.md) — 三层隔离与 Serving 策略
