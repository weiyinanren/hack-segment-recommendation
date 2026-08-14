# Sample Weights（创建时间 / 是否分发）

训练时给每个 Audience 一个样本权重，再作用到 Popularity / 共现 / Embedding。

**不需要**一开始就靠在线反馈学权重；先用启发式，有日志后再离线调参。

---

## 数据字段

在 `training/data/audiences.csv`（Audience 粒度，同一 `audience_id` 多行应一致）：

| 列 | 说明 | 示例 |
|----|------|------|
| `created_at` | 创建时间（ISO 日期/时间） | `2026-07-20` |
| `distributed` | 是否分发过 | `true` / `false` / `1` / `0` |

缺省这两列时，所有样本权重 = `1.0`（向后兼容）。

---

## 公式

\[
w_{\text{recency}} = \exp\left(-\frac{\ln 2}{T_{1/2}} \cdot \text{age\_days}\right)
\]

\[
w_{\text{distributed}} =
\begin{cases}
W_{\text{yes}} & \text{if distributed} \\
W_{\text{no}} & \text{otherwise}
\end{cases}
\]

\[
w = \max(w_{\min},\; w_{\text{recency}} \times w_{\text{distributed}})
\]

默认超参（`train.py` CLI）：

| 参数 | 默认 | 含义 |
|------|------|------|
| `--half-life-days` | `90` | 半衰期：90 天前 ≈ 一半权重 |
| `--weight-distributed` | `1.0` | 已分发倍率 |
| `--weight-undistributed` | `0.4` | 未分发倍率 |
| `--min-weight` | `0.05` | 下限，避免老数据归零 |
| `--as-of` | now | 计算 age 的参考时间 |

代码：`training/src/weights.py` → `attach_sample_weights()`。

---

## 权重用在哪

| 模块 | 用法 |
|------|------|
| Popularity / L0 prior | `weightSum += w`（替代单纯 +1） |
| Item-CF 共现 | `co(a,b) += w`，`count(a) += w` |
| Embedding PPMI | 加权共现矩阵再 SVD |

产物里 popularity 会带 `count`（原始次数）和 `weightSum`（加权和）；`score` 按 `weightSum` 归一化。

---

## 要不要持续反馈？

| 阶段 | 做法 |
|------|------|
| MVP（当前） | 启发式权重，配置可调 |
| 有采纳/点击日志 | 离线扫 `half-life` / 分发倍率 |
| 反馈稳定且量大 | 再把 recency、distributed 当特征进 LTR / 学权重 |

**结论：** 推断权重 ≠ 必须在线学习；先旋钮化，再用反馈校准旋钮。

---

## 训练示例

```bash
cd training
source .venv/bin/activate

python train.py \
  --half-life-days 90 \
  --weight-distributed 1.0 \
  --weight-undistributed 0.4 \
  --as-of 2026-08-12
```

`meta.json` / `index.json` 会写入所用的 `sampleWeights` 配置，便于复现。
