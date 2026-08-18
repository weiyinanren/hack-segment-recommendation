# Segment Recommendation System Design

## Background

我们的产品允许用户创建 Audience，并选择他们感兴趣的 Segments。

例如：

| User | Industry | Audience | Selected Segments |
|------|----------|----------|-------------------|
| U1 | Auto | A1 | S1, S3, S5 |
| U2 | Auto | A2 | S1, S7 |
| U3 | Retail | A3 | S2, S8 |

一个 Audience 本质上就是一个 **Segment 集合（Basket）**。

我们的目标是利用历史数据，训练一个推荐系统，实现：

- 新用户推荐（Cold Start）
- 用户选中部分 Segment 后，推荐更多相关 Segment
- 根据行业（Industry）推荐更加符合业务特点的 Segment

---

# 数据准备

首先整理训练数据。

## 原始数据

| user_id | industry | audience_id | segment_id |
|----------|----------|-------------|------------|
| U1 | Auto | A1 | S1 |
| U1 | Auto | A1 | S3 |
| U1 | Auto | A1 | S5 |
| U2 | Auto | A2 | S1 |
| U2 | Auto | A2 | S7 |
| U3 | Retail | A3 | S2 |
| U3 | Retail | A3 | S8 |

转换以后：

Audience A1

```
S1
S3
S5
```

Audience A2

```
S1
S7
```

Audience A3

```
S2
S8
```

实际上训练的数据就是：

```
Audience -> Segment List
```

---

# 推荐方案

## 方案一：Popularity（行业热门推荐）

### 思路

统计每个行业最常被选择的 Segment。

例如：

### Auto

| Segment | Count |
|----------|------:|
| S1 | 5000 |
| S3 | 4200 |
| S7 | 3900 |
| S5 | 3700 |

### Retail

| Segment | Count |
|----------|------:|
| S2 | 6200 |
| S8 | 5100 |
| S4 | 4300 |

对于新用户：

```
Industry = Auto

↓

推荐

S1
S3
S7
S5
```

### 优点

- 实现简单
- 可解释
- 冷启动效果很好

### 缺点

无法根据用户当前选择动态推荐。

---

# 方案二：Association Rule（购物篮分析）

Audience 可以看成购物篮（Basket）。

例如：

```
Audience1

S1
S3
S5
```

```
Audience2

S1
S3
S6
```

```
Audience3

S1
S3
S5
```

训练以后得到：

```
S1 + S3

↓

S5

Confidence = 83%
```

即：

```
P(S5 | S1,S3)=0.83
```

如果用户已经选择：

```
S1
S3
```

推荐：

```
S5
```

### 常见算法

- Apriori
- FP-Growth

### 优点

- 非常容易解释
- 推荐准确率高

### 缺点

无法发现隐藏关系。

---

# 方案三：Collaborative Filtering（协同过滤）

建立 User × Segment 矩阵。

| User | S1 | S2 | S3 | S4 | S5 |
|------|----|----|----|----|----|
| U1 |1|0|1|0|1|
| U2 |1|0|1|1|0|
| U3 |0|1|0|1|1|

使用：

- Matrix Factorization
- ALS

训练：

```
User Embedding

Segment Embedding
```

推荐：

```
User

↓

Nearest Segments
```

### 优点

能够发现潜在兴趣。

### 缺点

需要较多数据。

---

# 方案四：Item-Based Collaborative Filtering（推荐）

由于：

```
Segment 数量

<<

User 数量
```

更加适合计算：

Segment 与 Segment 的相似度。

例如：

```
S1

和

S3

一起出现

90%
```

```
S1

和

S7

一起出现

82%
```

得到：

```
S1

↓

Most Similar

S3
S7
S8
```

如果用户选择：

```
S1
```

推荐：

```
S3
S7
S8
```

### 优点

- 工程实现简单
- 推荐效果稳定
- Amazon 大规模使用

推荐指数：★★★★★

---

# 方案五：Embedding（Word2Vec）

把：

Audience

看成一句话。

例如：

```
S1 S3 S5 S7
```

```
S1 S3 S6
```

```
S2 S8 S9
```

训练 Word2Vec：

```
Segment

↓

Embedding
```

例如：

```
S1

↓

[0.12
0.91
0.45
...]
```

```
S3

↓

[0.13
0.89
0.47
...]
```

由于：

```
S1

经常和

S3

一起出现
```

因此：

```
Embedding(S1)

≈

Embedding(S3)
```

推荐流程：

```
用户选择

S1

↓

查找最近邻

↓

S3
S5
S7
```

### 优点

可以发现隐藏关系。

例如：

```
S1

和

S9

虽然没有一起出现

但是都经常和

S3

一起出现

↓

Embedding 很接近
```

推荐指数：★★★★★

---

# 如何结合 Industry

Industry 可以作为一个 Feature。

例如：

```
Industry = Auto
```

推荐有两种方式：

## 方法一

每个行业训练一个模型。

例如：

```
Auto

↓

Word2Vec Model
```

```
Retail

↓

Word2Vec Model
```

```
Finance

↓

Word2Vec Model
```

优点：

推荐最准。

---

## 方法二

Industry 加入模型。

例如：

```
User

↓

Industry

↓

Selected Segments

↓

Model
```

模型自动学习：

```
Auto 用户

更喜欢哪些 Segment
```

推荐效果更好。

---

# 更高级方案（未来）

当数据达到：

- 百万用户
- 数千万 Audience

可以升级为：

Two-Tower 推荐模型。

```
              User Tower

Industry
Company
Country
History

        ↓

 User Embedding
                 \
                  Dot Product
                 /
 Segment Tower

Segment Name
Category
Description

        ↓

Segment Embedding
```

在线：

```
User Embedding

×

Segment Embedding

↓

Top K
```

通常配合：

- FAISS
- ScaNN
- Vertex AI Matching Engine

进行毫秒级推荐。

---

# 推荐的演进路线

## Phase 1

行业热门推荐（Popularity）

用途：

- Cold Start
- 首页推荐

---

## Phase 2（推荐）

Item-Based Collaborative Filtering

用途：

用户选择 Segment 后实时推荐。

例如：

```
S1

↓

S3
S5
S7
```

---

## Phase 3（推荐）

Word2Vec Embedding

用途：

学习 Segment 之间更深层的关系。

例如：

```
S1

↓

Embedding

↓

Nearest Neighbor

↓

S3
S9
S12
```

---

## Phase 4

融合排序。

最终分数：

```
Final Score

=

Popularity Score
+
Co-occurrence Score
+
Embedding Similarity
```

最终输出：

Top N Segments。

---

# 推荐系统整体架构

```
                Raw Audience Data
                       │
                       ▼
              Data Preprocessing
                       │
        ┌──────────────┴──────────────┐
        │                             │
        ▼                             ▼
 Industry Popularity         Co-occurrence Matrix
        │                             │
        ▼                             ▼
 Cold Start                 Real-time Recommendation
                                      │
                                      ▼
                           Word2Vec Training
                                      │
                                      ▼
                             Segment Embedding
                                      │
                                      ▼
                         Nearest Neighbor Search
                                      │
                                      ▼
                              Ranking & Merge
                                      │
                                      ▼
                          Final Segment Recommendation
```

---

# 推荐方案总结

| 方法 | 难度 | 数据需求 | 冷启动 | 实时推荐 | 推荐效果 |
|------|------|----------|--------|----------|----------|
| Popularity | ⭐ | 少 | ⭐⭐⭐⭐⭐ | ⭐ | ⭐⭐ |
| Association Rule | ⭐⭐ | 中 | ⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Item-Based CF | ⭐⭐⭐ | 中 | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Matrix Factorization | ⭐⭐⭐⭐ | 多 | ⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Word2Vec | ⭐⭐⭐ | 中 | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Two-Tower | ⭐⭐⭐⭐⭐ | 很多 | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

---

# 推荐实施路线

**短期（MVP）**

- 行业热门推荐
- Item-Based Collaborative Filtering

**中期**

- Word2Vec Segment Embedding
- 相似 Segment 推荐

**长期**

- Two-Tower
- Deep Retrieval
- Learning To Rank
- A/B Testing