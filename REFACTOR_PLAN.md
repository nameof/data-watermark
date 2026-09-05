# 数据水印系统架构重构

## 目标架构

```
io.github.nameof.watermark
├── core/                          # 核心层：纯数据水印工具（与数据库无关）
│   ├── Watermarker.java           # 统一核心 API（canWatermark/embed/extract）
│   ├── WatermarkConfig.java       # 配置（secret + minRepetition）
│   ├── WatermarkResult.java       # 操作结果（从根包迁入，精简）
│   ├── WatermarkType.java         # 水印类型枚举（内置 Mode 属性）
│   ├── bit/                       # bit 载体策略（BitCarrierStrategy 接口 + 3 实现）
│   └── simple/                    # simple 策略（SimpleWatermarkStrategy 接口 + 2 实现）
├── database/                      # 数据库层：表级水印，开箱即用
│   ├── DatabaseWatermarker.java   # 数据库表级 API 门面
│   ├── ColumnDefinition.java      # 列定义 DTO（由用户提供）
│   ├── TableAnalysisResult.java   # canWatermark 综合分析结果
│   ├── StrategySelector.java      # 策略自动选择器
│   ├── DatabaseWatermarkReport.java  # 多表提取报告
│   ├── TableExtractDetail.java    # 单表提取详情
│   ├── StrategyDetail.java        # 策略级详情
│   └── DatabaseReportGenerator.java  # 报告图片生成（原 WatermarkReportGenerator）
└── io/                            # I/O 层（保留）
    ├── DataSource.java / TableData.java
    ├── JdbcDataSource.java
    └── CsvDataSource.java
```

## 核心层 (core)

### WatermarkType 枚举
文件：`core/WatermarkType.java`

bit-level 策略加 `BIT_` 前缀，simple 策略加 `SIMPLE_` 前缀。不设独立 WatermarkMode 枚举，模式作为 WatermarkType 的内置属性：

```java
public enum WatermarkType {
    // --- bit-level 策略（每单元格承载 1 bit）---
    BIT_CHINESE_ZERO_WIDTH(Mode.BIT_LEVEL),    // 中文零宽字符
    BIT_LATIN_HOMOGLYPH(Mode.BIT_LEVEL),       // 拉丁同形字替换
    BIT_NUMERIC_LSB(Mode.BIT_LEVEL),           // 数值末位微扰
    // --- simple 策略（每单元格承载完整载荷）---
    SIMPLE_SUFFIX_MARKER(Mode.SIMPLE),         // 文本后缀标记
    SIMPLE_INVISIBLE_PADDING(Mode.SIMPLE);     // 文本零宽填充

    public enum Mode { BIT_LEVEL, SIMPLE }

    public Mode getMode();
    public boolean isBitLevel();
}
```

### Watermarker 核心统一 API
文件：`core/Watermarker.java`

合并当前 `DataWatermarker`（bit-level 两段式编码逻辑）和 `SimpleWatermarker`（简单策略逻辑）为统一门面，只针对输入数据：

- `canWatermark(List<Map<String, Object>> table, List<String> columns)` → `Set<WatermarkType>`：扫描数据返回支持的所有水印类型
- `embed(table, columns, payload, List<WatermarkType> strategies)`：策略列表可为 null，null 时自动选择最健壮策略（bit-level 优先，容量不足降级 simple）
- `extract(table, columns)`：自动尝试 bit-level 和 simple 两种模式，返回最可靠结果

### 数据量约定

Core 层只接收内存中的 `List<Map<String, Object>>` 数据集。约定：调用方应控制数据量在合理范围内（建议单次不超过几万行），避免内存溢出。Core 层不负责大数据分片逻辑，大数据场景由 Database 层负责切片处理。

### 其他迁移
- `WatermarkConfig` → `core/`，移除 `useBitLevel` 开关，保留 secret 和 minRepetition，新增 `chunkSize`（切片行数，默认 2000，供 Database 层使用）
- `WatermarkResult` → `core/`，新增 `watermarkType` 字段，移除 `getReportImage()`（报告归 database 层）
- 删除根包下旧文件：`DataWatermarker`、`SimpleWatermarker`、`WatermarkEngine`、`WatermarkReportGenerator`

## 策略接口修订（core 层重构后的架构修正）

> 本节为 core 层迁移完成后的接口修订记录。原计划"bit/*、simple/* 仅改包名迁入 core/，逻辑不变"，
> 实施 review 后发现接口签名把表格坐标（row/col）泄漏进了策略层，导致 core 层绑死表格结构，已修正。

### 分层定位

- **载体层（策略接口）**：单值粒度，只负责"如何在一个值里隐蔽藏信息"（1 bit 或完整载荷）。
  单值嵌 1 bit 是刻意设计（隐蔽性权衡：一个值塞多个零宽字符会显著增加统计检测风险）。
- **编码/扩频层（Watermarker）**：多值粒度，负责载荷 → bit 流 → 冗余分布（两段式头部、
  交叉分配、多数投票、CRC32）。一次完整水印的嵌入天然需要多个值，此职责不在策略接口内。

### 接口变更

| 原接口 | 新接口 | 变更 |
|---|---|---|
| `ColumnWatermarkStrategy` | `BitCarrierStrategy` | 改名（"Column" 有误导性，实为单值接口）；签名 `embed(value, bit, secret, int row, int col)` → `embed(value, bit, secret, long seed)`；新增 `type()` |
| `ColumnEmbedResult` | `BitEmbedResult` | 改名，内容不变 |
| `SimpleWatermarkStrategy` | （保留名称） | 签名 `embed(value, payload, secret, int rowIndex)` → `embed(value, payload, secret, long seed)`；`name()` → `type()` |

`seed` 由 `Watermarker` 从值的稳定标识计算（`cellSeed(row, col)` / `rowSeed(row)`，密钥参与置乱），
策略不感知其来源 —— 未来支持非表格数据集（文档、JSON 等）时可直接复用全部策略。
`type()` 返回策略对应的 `WatermarkType`，消除 Watermarker 中按注册顺序的索引映射。

安全性说明：5 个策略的 `extract` 实现均为扫描式提取（不依赖坐标），seed 仅影响嵌入位置
（防统一截断攻击），因此本变更不影响 embed→extract 往返正确性（测试已回归验证）。

### 接口修订后补测试时发现并修复的缺陷（2026-09-03）

为 seed 化接口补充单元测试（策略契约测试 + 引擎端到端测试，共 39 个新增用例，63/63 全绿）时，
暴露了 3 个此前因缺少直接策略测试而一直未发现的缺陷，均已修复：

1. **LatinTextWatermarkStrategy.embed 忽略 bit 参数**：无论嵌入 0 还是 1 都替换为同形字，
   而 extract 把"有同形字"读作 1 —— 即嵌入 bit 0 后提取得到 1。
   修复：bit 1 = 替换选定字符为同形字；bit 0 = 归一化（还原已有同形字为 ASCII，保证无同形字）。
2. **策略分发顺序错误**：ChineseText 的 `canWatermark` 对任意 ≥2 字符的值都返回 true 且注册在首位，
   `findBitStrategy`/`findAnyBitStrategy` 永远先命中它 —— Latin/Numeric 在引擎中是死代码
   （数值列实际被插入零宽字符而非末位微扰）。修复：注册顺序改为**最具体优先**
   （Numeric → Latin → Chinese），嵌入与提取共用此顺序；自定义策略构造器 javadoc 已注明该约定。
3. **NumericWatermarkStrategy 的 locale 依赖**：`String.format` 未指定 `Locale.ROOT`，
   在小数点为逗号的 locale 下会产出 `"123,45"`。修复：显式指定 `Locale.ROOT`。

## 数据库层 (database)

### ColumnDefinition（用户提供的列定义）
文件：`database/ColumnDefinition.java`

字段：`columnName`、`dataType`（如 VARCHAR(100)/INT/DECIMAL(10,2)）、`maxLength`、`nullable`。库级元数据解析不做编码实现，由用户通过此 DTO 传入。

### TableAnalysisResult（canWatermark 结果）
文件：`database/TableAnalysisResult.java`

不是简单 boolean，而是综合分析结果：
- `tableName`、`watermarkable`
- `supportedTypes: List<WatermarkType>`（支持的一种或多种类型）
- `columnCapabilities: Map<String, List<WatermarkType>>`（每列支持哪些类型）
- `totalRows`、`totalEmbeddableCells`、`maxPayloadBytes`（结合 payload 分析容量是否足够、字段长度是否够）
- `warnings`（如载荷超出容量、某列长度不足等具体原因）

### StrategySelector（策略自动选择器）
文件：`database/StrategySelector.java`

输入 TableAnalysisResult + payload，输出推荐的 `List<WatermarkType>`：
- 优先 bit-level（隐蔽性、鲁棒性强），容量不足降级 simple
- 按数据类型分布匹配：中文列→BIT_CHINESE_ZERO_WIDTH，拉丁列→BIT_LATIN_HOMOGLYPH，数值列→BIT_NUMERIC_LSB
- 多列时组合多种策略提升总容量

### DatabaseWatermarker（数据库层门面）
文件：`database/DatabaseWatermarker.java`

构造注入 `DataSource` + `WatermarkConfig`，API：

```java
// 综合分析：表数据 + 字段定义 + payload
TableAnalysisResult canWatermark(String tableName,
    List<ColumnDefinition> columnDefinitions, String payload);

// 嵌入（自动选策略）/ 嵌入（用户指定策略列表）
WatermarkResult<TableData> embed(String tableName,
    List<ColumnDefinition> columnDefinitions, String payload);
WatermarkResult<TableData> embed(String tableName,
    List<ColumnDefinition> columnDefinitions, String payload,
    List<WatermarkType> strategies);

// 提取：单表 / 批量多表
DatabaseWatermarkReport extract(String tableName,
    List<ColumnDefinition> columnDefinitions);
DatabaseWatermarkReport extract(List<String> tableNames,
    Map<String, List<ColumnDefinition>> columnDefinitionsMap);
```

流程：canWatermark 读表数据 + ColumnDefinition + payload 综合分析；embed 先分析再选策略再嵌入写回；extract 双模式尝试提取并汇总报告。

### 大数据切片策略

Database 层对 ResultSet 进行分批读取，每 N 行（默认 2000 行）作为一个切片调用 Core 层。每片独立嵌入相同载荷，提取时每片独立提取后多数投票汇总。

**容量验证**（以 2000 行为例）：
- 2000 行 × 3 个可嵌入列 = 6000 个单元格
- 20 字节载荷需 `24 + 20×8×5 = 824` 个单元格
- 实际重复因子 `R = (6000-24)/160 = 37`，远超最低要求
- 即使只有 1 个可嵌入列，`R = (2000-24)/160 = 12`，仍然充足

**切片嵌入 vs 全量嵌入对比**：

| 特性 | 全量嵌入（10万行一次） | 切片嵌入（50片×2000行） |
|---|---|---|
| 单片内重复因子 R | 很高（~500） | 足够（~12-37） |
| 跨片冗余 | 无 | 50 份独立副本 |
| 容忍连续数据删除 | 强 | 单片内稍弱，但跨片补偿 |
| 内存占用 | 全部在内存 | 仅 2000 行/次 |
| 提取方式 | 单次提取 | 每片独立提取，多数投票 |

切片嵌入实际上增加了跨切片的冗余度——即使某些切片被完全删除，其他切片仍能还原水印。鲁棒性不减反增。

**嵌入流程**（伪代码）：
```java
// 嵌入：分批读取，每片独立嵌入
List<Map<String, Object>> chunk;
while ((chunk = readNextChunk(resultSet, chunkSize)) != null) {
    coreWatermarker.embed(chunk, columns, payload, strategies);
    writeChunk(chunk);  // 写回数据库
}
```

**提取流程**（伪代码）：
```java
// 提取：每片独立提取，汇总投票
List<WatermarkResult<String>> chunkResults = new ArrayList<>();
while ((chunk = readNextChunk(resultSet, chunkSize)) != null) {
    chunkResults.add(coreWatermarker.extract(chunk, columns));
}
report = aggregateResults(chunkResults);  // 多数投票得出最终结果
```

切片大小可通过 `WatermarkConfig` 配置，默认 2000 行。

### 提取报告
文件：`database/DatabaseWatermarkReport.java`、`TableExtractDetail.java`、`StrategyDetail.java`、`DatabaseReportGenerator.java`

报告体现每个表：用了哪些水印策略、涉及哪些字段、提取到的水印信息：

```java
DatabaseWatermarkReport { extractTime, overallSuccess,
    List<TableExtractDetail> tableDetails, BufferedImage getReportImage() }
TableExtractDetail { tableName, success, extractedPayload,
    List<StrategyDetail> strategies, List<String> involvedColumns,
    totalCells, validExtractions, confidence, mode, repetition }
StrategyDetail { WatermarkType type, strategyName, List<String> columns, cellCount }
```

`DatabaseReportGenerator` 基于原 `WatermarkReportGenerator`（Java 2D）重构为多表报告。

## I/O 层与配置

- `io/` 包 4 个文件保留，更新 import 指向 core 包
- `pom.xml`：mysql-connector-j 设为 optional；移除未使用的 hutool-all

## 测试更新

- 更新现有测试 import 路径；`WatermarkEngineTest` 改写为核心层 `WatermarkerTest`（纯数据 API 验证）
- 新增 `DatabaseWatermarkerTest`：canWatermark 综合分析、自动策略选择、多表提取报告

## README.md 更新

添加两个章节表格：

### 水印策略对照表

| 原始数据 | 水印策略 | Java 类 | WatermarkType 枚举 | 水印后数据示例 | 原理说明 |
|---|---|---|---|---|---|
| `"张三"` (中文文本) | 中文零宽字符 | `ChineseTextWatermarkStrategy` | `BIT_CHINESE_ZERO_WIDTH` | `"张\u200B三"` 或 `"张\u200C三"` | 在文字间插入零宽字符，U+200B=bit0, U+200C=bit1，每个单元格只编码 1 bit |
| `"John"` (拉丁文本) | 拉丁同形字替换 | `LatinTextWatermarkStrategy` | `BIT_LATIN_HOMOGLYPH` | `"Jоhn"` (о是西里尔字母) | 把某个 ASCII 字母替换为视觉相同的西里尔字母，替换=bit1，原样=bit0 |
| `123.45` (数值) | 数值末位微扰 | `NumericWatermarkStrategy` | `BIT_NUMERIC_LSB` | `123.44` 或 `123.46` | 末位调为偶数=bit0，奇数=bit1，差异不超过 1 个最小精度单位 |
| `"张三"` (任意文本) | 后缀标记 | `SuffixMarkerStrategy` | `SIMPLE_SUFFIX_MARKER` | `"张[::operator:zs::]三"` | 在随机位置插入 `[::payload::]` 可见标记，每个单元格携带完整载荷 |
| `"张三"` (任意文本) | 零宽填充 | `InvisiblePaddingStrategy` | `SIMPLE_INVISIBLE_PADDING` | `"张三\u200B\u200C\u200B..."` | 把完整载荷编码为零宽字符序列追加到末尾，每个单元格携带完整载荷 |

### 两种模式对比

| | bit-level 模式 | simple 模式 |
|---|---|---|
| 每个单元格承载 | 1 bit | 完整载荷 |
| 编码机制 | 两段式头部 + 载荷 + CRC32，交叉分配，多数投票 | 每个单元格独立，提取时多数投票 |
| 容量需求 | 高（每字节载荷约需 20 个单元格） | 低（有足够非空单元格即可） |
| 鲁棒性 | 高（冗余分布，容忍 50%+ 数据删除） | 中（依赖多数投票） |
| 隐蔽性 | 高（肉眼完全不可见） | 后缀标记可见，零宽填充不可见 |

## 实施顺序

1. 创建 core/ 包，迁入 bit/、simple/（改包名）
2. 创建 WatermarkType 枚举
3. 迁入精简 WatermarkConfig、WatermarkResult
4. 实现核心 Watermarker（合并两个旧 Watermarker 逻辑）
5. 实现 database 包：ColumnDefinition、TableAnalysisResult、StrategySelector、DatabaseWatermarker
6. 实现报告体系：DatabaseWatermarkReport、TableExtractDetail、StrategyDetail、DatabaseReportGenerator
7. 更新 io 包 import；删除根包旧文件
8. 更新 pom.xml
9. 更新并新增测试，全量编译 + 测试验证
10. 更新 README.md
