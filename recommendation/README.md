# Recommendation service

三层混合打分 + 全局名称近邻；默认只返回 client catalog 内 Segment。

## 启动

```bash
cd recommendation
mvn -s maven-settings.xml spring-boot:run
```

加载：`artifacts/global/name_neighbors.json` 与 `artifacts/clients/{client}/…`。  
热加载：`POST /api/admin/reload`。

## API

```bash
# 单选
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","selectedSegmentIds":["S1"],"topN":5}'

# 多选（S1+S2 可推出 S3：各已选邻居 max 融合）
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","selectedSegmentIds":["S1","S2"],"topN":5}'

# 可扩展到 catalog 外（inCatalog=false）
curl -s -X POST http://localhost:8080/api/recommend/segments \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"AcmeAuto","industry":"Auto","topN":5,"expandBeyondCatalog":true}'
```

## 文档

- [`docs/ARTIFACTS.md`](../docs/ARTIFACTS.md) — 产物字典（embeddings vs emb_neighbors 等）
- [`docs/TENANCY.md`](../docs/TENANCY.md) — 隔离与打分
- [`docs/NAME_SIMILARITY.md`](../docs/NAME_SIMILARITY.md) — 名称近邻
