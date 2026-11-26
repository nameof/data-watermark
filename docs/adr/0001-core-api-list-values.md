# ADR-0001: Core API 接受 `List<?>` 而非表格

- **状态**：**接受（Accepted，渐进式实施）**
- **日期**：2026-09-05
- **作者**：core 层设计讨论
- **取代**：[`REFACTOR_PLAN.md`](../REFACTOR_PLAN.md) §"Watermarker 核心统一 API"中关于 `embed(table, columns, ...)` 的设计；其他章节（策略层 seed 化、Database 层切片、报告体系）保持不变。

---

## 实施状态

- ✅ **第一阶段（已落地）**：在 `Watermarker` 中新增 `canWatermark(List<?>)` / `embed(List<?>)` / `embed(List<?>, strategies)` / `extract(List<?>)` 四个并列 API。**完全不动现有 Table API 及其内部实现**，新增 API 内部把 `List<?>` 包装为单虚拟列 `"_value"` 的 table 后复用 Table API，再展平回 `List<Object>`。
- ⏳ **第二阶段（待观察）**：根据实际运行情况（用户偏好、Database 层整合、非表格场景使用频率）再决定是否：
  - (a) 保持双 API 并列长期共存
  - (b) 把 Table API 收敛为 thin static wrapper，core 主路径完全走 values API
  - (c) 维持现状但用 Adapter 模式重整内部

---

## 背景与问题

水印的数学需求是「**有序、稳定、可微修改的值序列**」。表格（`List<Map<String, Object>>` + 列名）只是这一需求的一种便利投影。

审视 `REFACTOR_PLAN.md` 计划中的 `Watermarker.embed` 签名：

```java
public WatermarkResult<List<Map<String, Object>>> embed(
    List<Map<String, Object>> table, List<String> columns, String payload, ...)
```

我们对「列名」这个要素做了严格盘点，它在 core 算法里只承担两个实质工作：

1. 取值路径：`row.get(col)`
2. seed 来源：`cellSeed(row, col)`（行列坐标混合）

这两者都有等价替代（list 索引取值、`scramble(i, secret)` 生成 seed），且策略层接口早已在 2026-09-03 完成 seed 化、与数据集形态完全解耦（见 `BitCarrierStrategy` / `SimpleWatermarkStrategy`），不依赖 (row, col) 二维结构。

**列名对业务用户的可读性是真的，对算法的必要性是假的**。把 core 公开 API 限制在 table 形态上，让以下场景付出了不必要的转换代价：

- 文档水印（字符序列无列名）
- JSON 数组水印（数组元素无列名）
- `List<String>` / `List<BigDecimal>` 等纯值序列
- 任何非 SQL/CSV 形态的数据源

## 决策

将 `Watermarker.embed` / `extract` 的**核心签名**从 `table + columns` 改为 **`List<?>`**：

```java
// 核心 API —— 真正的数学抽象
WatermarkResult<List<Object>> embed(List<?> values, String payload);
WatermarkResult<List<Object>> embed(List<?> values, String payload, List<WatermarkType> strategies);
WatermarkResult<String> extract(List<?> values);
WatermarkResult<String> extract(List<?> values, List<WatermarkType> strategies);

// 便利 API —— table 用户的薄包装（保留）
static WatermarkResult<List<Map<String, Object>>> embedTable(
    List<Map<String, Object>> table, List<String> columns, String payload);
static WatermarkResult<String> extractTable(
    List<Map<String, Object>> table, List<String> columns);
```

### Seed 约定

seed 改为 `scramble(i, secret)`，其中 `i` 为值在 list 中的索引。**调用方必须保证嵌入与提取时传入的 list 顺序一致**，这与当前"行序稳定"是同构的约束。

> ⚠️ **2026-09-21 修订注记**：本节对约束的评估**不完整**。实测表明该约束的真实含义是「embed 与 extract 之间数据集必须一行不多、一行不少」（`i` 是**紧凑序列下标**，任何非尾部删除都会使后续全部单元格错位），并非"不增加新负担"。bit-level 模式因此**不具备** README/javadoc 承诺的删除鲁棒性。详见 **[`0002-bit-slot-alignment-deletion-robustness.md`](./0002-bit-slot-alignment-deletion-robustness.md)**（含实验证据与修复方案）。

> ✅ **2026-09-24 落地更新**：ADR-0002 方案 A 已实现，本节约束**已大幅放宽**：
> - bit-level 的位槽改由每个值自身的**稳定键**（剥离水印痕迹后的原值哈希）决定，**与 `i` 无关**；
>   simple 模式本就每格独立承载完整载荷。因此**嵌入与提取之间允许增删值、允许乱序**。
> - 仍保留 `seed`（`cellSeed(row, col)`）：但它现在**只影响水印在单个值内的落点**，
>   且四个 bit 策略的 `extract` **都不使用 seed**，所以行序变化不再影响还原。
> - 新约束是**容量**而非顺序：位槽按内容认领，**内容重复的值会共享槽位、只贡献 1 票**，
>   容量规划须按"独立投票者数"计（见 README §容量计算公式）。


### 列名信息去向

列名不再出现在 core 层。"涉及字段 X/Y/Z"的人类可读报告由 `DatabaseWatermarker`（Database 层）负责 —— 它本来就知道列名，因为列名是 SQL/CSV 的元数据，跟 core 抽象无关。

### 内部 CellRef

`Watermarker` 内部的 `CellRef` 私有类可以移除 —— 它的存在就是为了在 table 上构造 `(value, seed, colName)` 三元组，list 形态下不需要。

### 策略层

**零改动**。`BitCarrierStrategy` / `SimpleWatermarkStrategy` 已经接受 `Object value + long seed`，完全无感。

## 后果

### 正面

- ✅ core 公开 API 直接对应水印的数学抽象（值序列）
- ✅ 非表格场景零成本接入（文档、JSON 数组、纯 List）
- ✅ `CellRef` 内部类可移除（代码瘦身）
- ✅ 策略层零改动（已 seed 化）
- ✅ **当前为 `1.0.0-SNAPSHOT`，重构 API 的下游迁移成本为零** —— 这是最有利的窗口期

### 负面

- ⚠️ 破坏性 API 变更：所有 `embed(table, columns, ...)` 调用要改为 `embed(values, ...)`。在快照版期间可以零负担完成；错过窗口期后需走 deprecation。
- ⚠️ `DatabaseWatermarker` 需要相应调整（内部把 table + 列定义展平为 `List<Object>` 给 core）
- ⚠️ README 中所有代码示例需要重写

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| seed 稳定性约束从"行列序"退化为"list 顺序" | 在 javadoc 第一段就强调；与现有约束同构，不增加新负担 |
| "涉及列名"的语义信息从 core 移到 Database 层 | `DatabaseWatermarkReport` 本来就记录 `involvedColumns`，无需新增逻辑 |
| 用户误用 `List<Map<...>>` 当 List<Object> | 在 `embed(List<?>)` javadoc 中明确「这是值序列，不是行序列」；table 便利方法有独立签名 |

## 备选方案（已评估，未采纳）

### 方案 A：保持 table API，内部 CellRef 序列

- 优点：0 改动
- **不采纳原因**：core 公开 API 仍是投影而非抽象；非表格场景需"伪造列名"

### 方案 B：table API + 内部 package-private `List<?>` API

- 优点：源码层面体现抽象
- **不采纳原因**：公共 API 仍是 table；非表格场景仍需"伪造列名"

### 方案 C：双 API —— `embedValues` + `embedTable` 并列

- 优点：两种用户各取所需
- **不采纳原因**：列名信息在 `embedValues` 路径下丢失；seed 来源定义模糊（位置？值 hash？调用方传？）；维护成本翻倍

## 实施顺序（待本 ADR 接受后启动）

1. 在 `core` 包内重构 `Watermarker.embed` / `extract` 签名为 `List<?>`
2. 移除 `CellRef` 私有类
3. 保留 `embedTable` / `extractTable` 作为 thin static wrapper
4. 调整 `DatabaseWatermarker` 内部展平逻辑（先展平为 `List<Object>` 再调 core）
5. 更新所有现有测试（应全部等价通过 —— 语义不变）
6. 更新 `README.md` 示例
7. 在 `REFACTOR_PLAN.md` 中标注本 ADR 已取代相关小节