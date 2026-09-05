# Data Watermark

数据暗水印库 —— 在数据集中嵌入不可见的追溯水印，用于数据泄露溯源。

## 特性

- **统一核心 API（core 层）**：`Watermarker` 一个入口同时支持两种水印模式
  - **Bit-level**（默认）：两段式编码 + 交叉分配 + CRC32 校验，高隐蔽、高健壮性，容忍高达 60% 的数据删除
  - **Simple**：每个单元格独立承载完整载荷，实现简单，适合内部数据追溯
- **三种 Bit-level 嵌入策略**（自动按数据类型选择）
  - 中文文本 → 零宽字符嵌入（U+200B/U+200C）
  - 拉丁文本 → 西里尔同形字替换（肉眼不可区分）
  - 数值 → 末位微扰（奇偶编码，差异不超过 1 个最小精度单位）
- **两种 Simple 嵌入策略**
  - 后缀标记 → 可见标记 `[::payload::]`，最简单直观
  - 隐形填充 → 零宽字符编码完整载荷，肉眼不可见
- **数据源抽象（io 层）**：`DataSource` 接口 + CSV 实现，可与 `Watermarker` 自由组合
- **Java 8** 兼容，无第三方运行时依赖

> 🚧 **开发中**：数据库表级一键集成（`DatabaseWatermarker`，含大数据切片、多表提取报告、报告图片生成）。

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.nameof</groupId>
    <artifactId>data-watermark</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 架构概览

```
io.github.nameof.watermark/
├── core/                              # 核心层（与存储无关）
│   ├── Watermarker.java               # 统一核心 API（canWatermark/embed/extract）
│   ├── WatermarkConfig.java           # 配置（secret + minRepetition + chunkSize）
│   ├── WatermarkResult.java           # 操作结果
│   ├── WatermarkType.java             # 水印类型枚举（内置 Mode 属性）
│   │
│   ├── bit/                           # bit 载体策略（每个值承载 1 bit）
│   │   ├── BitCarrierStrategy.java    # 策略接口（单值 + seed）
│   │   ├── BitEmbedResult.java        # 单值嵌入结果
│   │   ├── ChineseTextWatermarkStrategy.java  # 中文 → 零宽字符
│   │   ├── LatinTextWatermarkStrategy.java    # 拉丁文 → 同形字替换
│   │   └── NumericWatermarkStrategy.java      # 数值 → 末位微扰
│   │
│   └── simple/                        # simple 策略（每个值承载完整载荷）
│       ├── SimpleWatermarkStrategy.java  # 策略接口（单值 + seed）
│       ├── SuffixMarkerStrategy.java     # 后缀标记（可见）
│       └── InvisiblePaddingStrategy.java # 隐形填充（零宽字符）
│
└── io/                                # 数据源抽象
    ├── DataSource.java                # 数据源接口
    ├── TableData.java                 # 表数据 DTO
    ├── JdbcDataSource.java            # JDBC 实现
    └── CsvDataSource.java             # CSV 实现
```

### 分层设计

| 层 | 位置 | 职责 | 粒度 |
|----|------|------|------|
| 载体层（策略） | `core.bit` / `core.simple` | 在**单个值**中隐蔽地嵌入/提取 1 bit 或完整载荷 | 单值 |
| 编码/扩频层 | `core.Watermarker` | 载荷 → bit 流 → 冗余分布到多个值（两段式头部、交叉分配、多数投票、CRC32） | 多值/数据集 |
| 数据源层 | `io` | 表数据的读取与写回（CSV/JDBC） | 表 |

> 一次完整水印的嵌入天然需要多个值：bit-level 模式下每个单元格只承载 1 bit，
> 由 `Watermarker` 负责把载荷编码为 bit 流并冗余分布到整个数据集。
> 策略接口通过 `seed`（由引擎从值的稳定标识计算）选择嵌入位置，
> 不感知数据集形态 —— 这使得策略可以被未来的非表格数据集（文档、JSON 等）直接复用。

## 水印策略对照表

| 原始数据 | 水印策略 | Java 类 | WatermarkType 枚举 | 水印后数据示例 | 原理说明 |
|---|---|---|---|---|---|
| `"张三"` (中文文本) | 中文零宽字符 | `ChineseTextWatermarkStrategy` | `BIT_CHINESE_ZERO_WIDTH` | `"张\u200B三"` 或 `"张\u200C三"` | 在文字间插入零宽字符，U+200B=bit0, U+200C=bit1，每个单元格只编码 1 bit |
| `"John"` (拉丁文本) | 拉丁同形字替换 | `LatinTextWatermarkStrategy` | `BIT_LATIN_HOMOGLYPH` | `"Jоhn"` (о是西里尔字母) | 把某个 ASCII 字母替换为视觉相同的西里尔字母，替换=bit1，原样=bit0 |
| `123.45` (数值) | 数值末位微扰 | `NumericWatermarkStrategy` | `BIT_NUMERIC_LSB` | `123.44` 或 `123.46` | 末位调为偶数=bit0，奇数=bit1，差异不超过 1 个最小精度单位 |
| `"张三"` (任意文本) | 后缀标记 | `SuffixMarkerStrategy` | `SIMPLE_SUFFIX_MARKER` | `"张[::operator:zs::]三"` | 在随机位置插入 `[::payload::]` 可见标记，每个单元格携带完整载荷 |
| `"张三"` (任意文本) | 零宽填充 | `InvisiblePaddingStrategy` | `SIMPLE_INVISIBLE_PADDING` | `"张三\u200B\u200C\u200B..."` | 把完整载荷编码为零宽字符序列追加到末尾，每个单元格携带完整载荷 |

## 前置条件（重要）

### 数据类型要求

**不是所有列都能嵌入水印。** 系统会根据单元格值的数据类型自动选择嵌入策略，不匹配的列会被跳过。

| 策略 | 适用的数据类型 | 最小数据要求 | 原理 |
|------|-------------|------------|------|
| 中文零宽字符 | `String`（含中文） | 去除零宽字符后 ≥ 2 个字符 | 在文本中插入 U+200B/U+200C |
| 拉丁同形字 | `String`（含 a/e/o/p/c/x/A/B/C/E/O/P） | ≥ 3 个字符，且至少含一个可替换字母 | 替换为视觉相同的西里尔字母 |
| 数值末位微扰 | `Number` 或可解析为数字的 `String` | 无特殊限制 | 末位 ±1 改变奇偶性 |
| 后缀标记（Simple） | 任意 `Object`（调用 `toString()`） | 无限制 | 追加 `[::payload::]` |
| 隐形填充（Simple） | `String` | 无限制 | 追加零宽字符序列 |

**关键限制：**

- **纯整数列**（如 `INT` 类型的 `1, 2, 3`，无小数点）：数值策略可以工作，但精度为 0 位小数，末位微扰会改变整数值 ±1，可能影响业务逻辑
- **日期/时间列**（`DATE`、`DATETIME`）：不支持，`toString()` 结果不匹配任何策略
- **布尔列**：不支持
- **二进制/BLOB 列**：不支持
- **纯数字字符串**（如 `"12345"`）：会被数值策略处理，等同于数值

### 数据库编码要求

| 使用的策略 | MySQL 列编码要求 | 原因 |
|-----------|----------------|------|
| 中文零宽字符（Bit-level） | **≥ `utf8`**（即 utf8mb3） | 零宽字符在 Unicode BMP 内，3 字节 |
| 拉丁同形字（Bit-level） | **≥ `utf8`** | 西里尔字母在 BMP 内 |
| 隐形填充（Simple） | **≥ `utf8`** | 使用同样的零宽字符 |
| 数值末位微扰（Bit-level） | 无要求 | 不改变字符集 |
| 后缀标记（Simple） | 无要求 | 纯 ASCII 追加 |

> **总结**：只要表中包含字符串列并计划使用零宽字符或同形字策略，列编码必须为 `utf8` 或 `utf8mb4`。`ascii` / `latin1` 编码会导致零宽字符丢失，水印无法提取。
>
> JDBC 连接串需包含 `characterEncoding=UTF-8`（Java 标准名，**不是** `utf8mb4`）。

### 数据完整性影响

| 策略 | 对原始数据的影响 | 下游系统是否可感知 |
|------|--------------|----------------|
| 中文零宽字符 | 字符串 `length()` 增加 1 | `equals()` 不等，`trim()` 不影响 |
| 拉丁同形字 | 字符编码改变，视觉不变 | `equals()` 不等，肉眼不可见 |
| 数值末位微扰 | 值变化 ±1 个最小精度单位 | 数值比较可发现 |
| 后缀标记 | 字符串变长，追加可见标记 | 肉眼可见 |
| 隐形填充 | 字符串 `length()` 显著增加 | `equals()` 不等，肉眼不可见 |

## Quick Start

### 1. 内存数据嵌入/提取（自动选择策略，默认 Bit-level）

最基础的用法：直接在内存数据上嵌入和提取水印。

```java
import io.github.nameof.watermark.core.*;
import java.util.*;

// 准备数据（需满足数据类型要求）
List<Map<String, Object>> table = new ArrayList<>();
for (int i = 0; i < 500; i++) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", "用户" + i);               // 中文 → 零宽字符策略
    row.put("address", "城市A街道" + i + "号");  // 中文 → 零宽字符策略
    row.put("salary", 10000.0 + i);            // Double → 数值末位微扰策略
    table.add(row);
}

// 配置与核心 API
WatermarkConfig config = new WatermarkConfig("my-secret-key");
Watermarker watermarker = new Watermarker(config);
List<String> columns = Arrays.asList("name", "address", "salary");
String payload = "operator:zhangsan|company:ACME|time:2024-01-01";

// 嵌入水印（strategies 传 null 时自动选择：bit-level 优先，容量不足降级 simple）
WatermarkResult<List<Map<String, Object>>> embedResult =
        watermarker.embed(table, columns, payload);

if (embedResult.isSuccess()) {
    System.out.println("嵌入成功，重复因子: " + embedResult.getRepetition());
    System.out.println("使用的水印类型: " + embedResult.getWatermarkType());

    // 提取水印（自动尝试 bit-level 和 simple 两种模式，返回最可靠结果）
    WatermarkResult<String> extractResult =
            watermarker.extract(embedResult.getData(), columns);
    System.out.println("还原载荷: " + extractResult.getData());
    // 输出: operator:zhangsan|company:ACME|time:2024-01-01
}
```

> **注意**：Bit-level 模式需要足够的可嵌入单元格。对于 30 字节的载荷，至少需要约 1500 个可嵌入单元格（约 500 行 × 3 列）。

### 2. 指定 Simple 模式

适合内部数据追溯，实现简单，对数据量要求低（几行即可）。

```java
import io.github.nameof.watermark.core.*;
import java.util.*;

// 准备数据（少量数据即可）
List<Map<String, Object>> table = new ArrayList<>();
for (int i = 0; i < 10; i++) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", "用户" + i);
    row.put("description", "这是第" + i + "个用户的描述");
    table.add(row);
}

WatermarkConfig config = new WatermarkConfig("my-secret");
Watermarker watermarker = new Watermarker(config);
List<String> columns = Arrays.asList("name", "description");

// 显式指定 simple 策略（也可只选其中一种）
List<WatermarkType> strategies = Arrays.asList(
        WatermarkType.SIMPLE_SUFFIX_MARKER,      // 后缀标记（可见）
        WatermarkType.SIMPLE_INVISIBLE_PADDING); // 隐形填充（不可见）

// 嵌入
WatermarkResult<List<Map<String, Object>>> embedResult =
        watermarker.embed(table, columns, "operator:zhangsan", strategies);

// 提取（每单元格独立提取 + 多数投票）
WatermarkResult<String> extractResult =
        watermarker.extract(embedResult.getData(), columns);
System.out.println("载荷: " + extractResult.getData());
```

### 3. 检测数据支持哪些水印类型

嵌入前可先扫描数据，查看支持的水印类型。

```java
import io.github.nameof.watermark.core.*;
import java.util.*;

Watermarker watermarker = new Watermarker(new WatermarkConfig("my-secret-key"));
Set<WatermarkType> types = watermarker.canWatermark(table, columns);
// 例如: [BIT_CHINESE_ZERO_WIDTH, BIT_NUMERIC_LSB, SIMPLE_SUFFIX_MARKER, ...]
```

### 4. 单独使用策略（无需 Watermarker）

策略是纯单值变换，可以直接使用。bit 策略在单个值中嵌 1 bit，`seed` 决定嵌入在值的哪个位置（相同 seed + 密钥 → 相同位置）。

```java
import io.github.nameof.watermark.core.bit.*;

// bit 载体策略：单值嵌 1 bit
ChineseTextWatermarkStrategy strategy = new ChineseTextWatermarkStrategy();
BitEmbedResult result = strategy.embed("张三", 1, "secret", 42L);
if (result.isEmbedded()) {
    String embedded = (String) result.getValue();
    // → "张\u200C三"（看起来还是"张三"）
    int bit = strategy.extract(embedded, "secret", 42L);
    // → 1
}

// simple 策略：单值嵌完整载荷
import io.github.nameof.watermark.core.simple.*;

SuffixMarkerStrategy suffix = new SuffixMarkerStrategy();
String embedded = suffix.embed("张三", "operator:zhangsan", "secret", 0L);
// → "张[::operator:zhangsan::]三"
String payload = suffix.extract(embedded, "secret", 0L);
// → "operator:zhangsan"
```

> 单独使用 bit 策略时没有冗余保护：值被修改（如零宽字符被清洗）该 bit 即丢失。
> 完整载荷的嵌入请使用 `Watermarker`，由它负责冗余分配和多数投票。

### 5. CSV 数据源组合

通过 `io` 层的 `DataSource` 读取表数据，用 `Watermarker` 嵌入后写回。

```java
import io.github.nameof.watermark.core.*;
import io.github.nameof.watermark.io.*;
import java.util.*;

try (CsvDataSource csvSource = new CsvDataSource("/data/csv")) {
    // 读取 CSV
    TableData tableData = csvSource.readTable("source_data");
    List<String> columns = Arrays.asList("name", "address");

    // 嵌入水印
    Watermarker watermarker = new Watermarker(new WatermarkConfig("my-secret-key"));
    WatermarkResult<List<Map<String, Object>>> embedResult =
            watermarker.embed(tableData.getRows(), columns, "operator:zhangsan");
    if (!embedResult.isSuccess()) {
        throw new IllegalStateException("嵌入失败: " + embedResult.getMessage());
    }

    // 写回新 CSV
    TableData watermarked = new TableData(
            "source_data_watermarked", tableData.getColumnNames(), embedResult.getData());
    csvSource.writeTable(watermarked, "source_data_watermarked");

    // 从新 CSV 提取验证
    TableData verification = csvSource.readTable("source_data_watermarked");
    WatermarkResult<String> result =
            watermarker.extract(verification.getRows(), columns);
    System.out.println("载荷: " + result.getData());
}
```

JDBC 场景同理：构造 `JdbcDataSource`（传入 `Connection`），按相同的 readTable → embed → writeTable 流程组合。

## 两种模式对比

| | bit-level 模式 | simple 模式 |
|---|---|---|
| 每个单元格承载 | 1 bit | 完整载荷 |
| 编码机制 | 两段式头部 + 载荷 + CRC32，交叉分配，多数投票 | 每个单元格独立，提取时多数投票 |
| 容量需求 | 高（每字节载荷约需 20 个单元格） | 低（有足够非空单元格即可） |
| 鲁棒性 | 高（冗余分布，容忍 50%+ 数据删除） | 中（依赖多数投票） |
| 隐蔽性 | 高（肉眼完全不可见） | 后缀标记可见，零宽填充不可见 |

## Bit-level 容量与鲁棒性

### 容量计算公式

```
编码后字节数 = 载荷UTF-8字节数 + 5（1字节长度头 + 4字节CRC32）
载荷 bit 数 N = 编码后字节数 × 8
需要单元格数 M = 24（头部） + N × minRepetition（默认5）
需要行数 = M ÷ 可嵌入列数
```

> **快速估算口诀**：每 1 字节载荷 ≈ 需要 20 个单元格（R=5 时），行数 ≈ (载荷字节数 × 20 + 24) ÷ 列数

### 容量需求表（minRepetition=5）

| 载荷内容 | UTF-8 字节 | 编码后字节 | 载荷 bits | 需要单元格 | 3 列需行数 | 5 列需行数 |
|---------|----------|----------|---------|----------|---------|--------|
| `A` | 1 | 6 | 48 | 264 | 88 | 53 |
| `ABC` | 3 | 8 | 64 | 344 | 115 | 69 |
| 3 个汉字 `操作员` | 9 | 14 | 112 | 584 | 195 | 117 |
| 10 个汉字 | 30 | 35 | 280 | 1424 | 475 | 285 |
| `operator:zhangsan\|company:ACME` | 31 | 36 | 288 | 1464 | 488 | 293 |
| 50 个汉字 | 150 | 155 | 1240 | 6224 | 2075 | 1245 |
| 极限（251 字节） | 251 | 256 | 2048 | 10264 | 3422 | 2053 |

> **注意**：不是所有单元格都能嵌入。中文需 ≥2 字符，拉丁文需 ≥3 字符且含可替换字母，整数列精度为 0。建议多预留 20-30% 行数。

### 鲁棒性（容错率）

鲁棒性由 **重复因子 R** 决定。R = (实际可嵌入单元格 - 24) ÷ 载荷 bits。

| 重复因子 R | 理论最大容忍比例 | 实际建议安全线 | 适用场景 |
|-----------|---------------|-------------|--------|
| R=3 | 66% | ~30% | 数据量紧张，最低保障 |
| R=5（默认） | 80% | ~50% | **常规使用推荐** |
| R=8 | 87.5% | ~60% | 高健壮性需求 |
| R=10 | 90% | ~70% | 极端场景 |

> **"容忍 50% 删除"** 的含义：即使随机删除一半的数据行，水印仍然可以被正确还原。

### 实际示例

一张 **800 行 × 3 列** 的表（姓名 + 地址 + 薪资），载荷 `operator:zhangsan|company:ACME`（31 字节）：

```
可嵌入单元格 ≈ 800 × 3 = 2400
编码后 = 36 字节 = 288 bits
R = (2400 - 24) / 288 ≈ 8
→ 可容忍约 60% 的数据删除（约 480 行）
```

## 配置参数

```java
// 基本配置（minRepetition=5, chunkSize=2000）
WatermarkConfig config = new WatermarkConfig("your-secret-key");

// 指定最小重复因子（默认 5，越大越健壮但载荷容量越小，最小 3）
WatermarkConfig config = new WatermarkConfig("your-secret-key", 8);

// 指定切片行数（默认 2000，供 Database 层分批处理使用）
WatermarkConfig config = new WatermarkConfig("your-secret-key", 5, 2000);
```

| 参数 | 默认值 | 说明 |
|------|-------|------|
| `secret` | 必填 | 密钥，决定嵌入位置，提取时必须一致 |
| `minRepetition` | 5 | 每个 bit 的最小重复数，越大越健壮、容量越小 |
| `chunkSize` | 2000 | 大数据切片行数（Database 层使用） |

## 数据库要求

- MySQL 5.7+ / 8.0+
- 字符串列编码为 `utf8` 或 `utf8mb4`（零宽字符和同形字策略需要）
- JDBC 连接参数需包含 `characterEncoding=UTF-8`（注意：是 Java 标准名 `UTF-8`，不是 MySQL 的 `utf8mb4`）
- 纯数值列（末位微扰策略）对编码无要求

## License

MIT
