# Data Watermark

数据暗水印库 —— 在数据集中嵌入不可见的追溯水印，用于数据泄露溯源。

## 特性

- **两种水印模式**
  - **Bit-level**（默认）：两段式编码 + 交叉分配 + CRC32 校验，高隐蔽、高健壮性，容忍高达 60% 的数据删除
  - **Simple**：每个单元格独立承载完整载荷，实现简单，适合内部数据追溯
- **三种 Bit-level 嵌入策略**（自动按数据类型选择）
  - 中文文本 → 零宽字符嵌入（U+200B/U+200C）
  - 拉丁文本 → 西里尔同形字替换（肉眼不可区分）
  - 数值 → 末位微扰（奇偶编码，差异不超过 1 个最小精度单位）
- **两种 Simple 嵌入策略**
  - 后缀标记 → 可见标记 `[::payload::]`，最简单直观
  - 隐形填充 → 零宽字符编码完整载荷，肉眼不可见
- **多种数据源**：MySQL 数据库、CSV 文件、内存数据
- **可视化提取报告**：Java 2D 生成报告图片，用于追责证据展示
- **Java 8** 兼容，无第三方运行时依赖（MySQL JDBC 除外）

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.nameof</groupId>
    <artifactId>data-watermark</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

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

### 1. 内存数据嵌入/提取（Bit-level 模式）

最基础的用法：直接在内存数据上嵌入和提取水印。

```java
import io.github.nameof.watermark.*;
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

// 配置
WatermarkConfig config = new WatermarkConfig("my-secret-key");

// 嵌入水印
DataWatermarker watermarker = new DataWatermarker(config);
List<String> columns = Arrays.asList("name", "address", "salary");
String payload = "operator:zhangsan|company:ACME|time:2024-01-01";

WatermarkResult<List<Map<String, Object>>> embedResult =
        watermarker.embed(table, columns, payload);

if (embedResult.isSuccess()) {
    System.out.println("嵌入成功，重复因子: " + embedResult.getRepetition());

    // 提取水印
    WatermarkResult<String> extractResult =
            watermarker.extract(embedResult.getData(), columns);
    System.out.println("还原载荷: " + extractResult.getData());
    // 输出: operator:zhangsan|company:ACME|time:2024-01-01
}
```

> **注意**：Bit-level 模式需要足够的可嵌入单元格。对于 30 字节的载荷，至少需要约 1500 个可嵌入单元格（约 500 行 × 3 列）。

### 2. 简单模式（Simple）

适合内部数据追溯，实现简单，对数据量要求低（几行即可）。

```java
import io.github.nameof.watermark.*;
import io.github.nameof.watermark.simple.*;
import java.util.*;

// 准备数据（少量数据即可）
List<Map<String, Object>> table = new ArrayList<>();
for (int i = 0; i < 10; i++) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", "用户" + i);
    row.put("description", "这是第" + i + "个用户的描述");
    table.add(row);
}

// 使用简单模式
WatermarkConfig config = new WatermarkConfig("my-secret").setUseBitLevel(false);
SimpleWatermarker watermarker = new SimpleWatermarker(config);
List<String> columns = Arrays.asList("name", "description");

// 嵌入
WatermarkResult<List<Map<String, Object>>> embedResult =
        watermarker.embed(table, columns, "operator:zhangsan");

// 提取（多数投票）
WatermarkResult<String> extractResult =
        watermarker.extract(embedResult.getData(), columns);
System.out.println("载荷: " + extractResult.getData());
```

### 3. MySQL 数据库集成

从数据库读取 → 嵌入水印 → 写入新表 → 提取验证。

```java
import io.github.nameof.watermark.*;
import java.util.*;

WatermarkConfig config = new WatermarkConfig("my-secret-key");

try (WatermarkEngine engine = new WatermarkEngine(
        "jdbc:mysql://localhost:3306/mydb?useSSL=false&characterEncoding=UTF-8",
        "root", "root", config)) {

    List<String> columns = Arrays.asList("name", "address", "salary");

    // 嵌入水印到新表
    WatermarkResult<TableData> embedResult = engine.embed(
            "citizen_info", columns,
            "operator:zhangsan|batch:20240101",
            "citizen_info_watermarked");

    if (embedResult.isSuccess()) {
        System.out.println("水印已写入新表 citizen_info_watermarked");
    }

    // 从新表提取水印
    WatermarkResult<String> extractResult = engine.extract(
            "citizen_info_watermarked", columns);
    System.out.println("还原载荷: " + extractResult.getData());
}
```

### 4. CSV 文件导出

从数据库读取 → 嵌入水印 → 导出为 CSV 文件。

```java
import io.github.nameof.watermark.*;
import java.util.*;

WatermarkConfig config = new WatermarkConfig("my-secret-key");

try (WatermarkEngine engine = new WatermarkEngine(
        "jdbc:mysql://localhost:3306/mydb?useSSL=false&characterEncoding=UTF-8",
        "root", "root", config)) {

    List<String> columns = Arrays.asList("name", "address", "salary");

    // 嵌入水印并导出到 CSV
    engine.embedToCsv("citizen_info", columns,
            "operator:zhangsan", "/tmp/watermarked_output.csv");

    // 从 CSV 提取水印
    WatermarkResult<String> result = engine.extractFromCsv(
            "/tmp/watermarked_output.csv", columns);
    System.out.println("载荷: " + result.getData());
}
```

### 5. 纯 CSV 数据源

不依赖数据库，直接操作 CSV 文件。

```java
import io.github.nameof.watermark.*;
import io.github.nameof.watermark.io.*;
import java.util.*;

WatermarkConfig config = new WatermarkConfig("my-secret-key");
CsvDataSource csvSource = new CsvDataSource("/data/csv");

try (WatermarkEngine engine = new WatermarkEngine(csvSource, config)) {
    List<String> columns = Arrays.asList("name", "address");

    // 读取 CSV → 嵌入水印 → 写入新 CSV
    engine.embedToCsv("source_data", columns,
            "operator:zhangsan", "/data/csv/output.csv");

    // 从新 CSV 提取
    WatermarkResult<String> result = engine.extractFromCsv(
            "/data/csv/output.csv", columns);
    System.out.println("载荷: " + result.getData());
}
```

### 6. 使用自定义数据源（JDBC Connection）

当你已有数据库连接时，可以直接传入 Connection。

```java
import io.github.nameof.watermark.*;
import io.github.nameof.watermark.io.*;
import java.sql.*;
import java.util.*;

Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
JdbcDataSource ds = new JdbcDataSource(conn); // 不自动关闭连接

WatermarkConfig config = new WatermarkConfig("my-secret-key");
try (WatermarkEngine engine = new WatermarkEngine(ds, config)) {
    engine.embed("users", Arrays.asList("name", "email"),
            "operator:zhangsan", "users_watermarked");
}
// conn 不会被关闭，需自行管理
```

### 7. 单独使用简单策略

直接使用策略类，无需引擎。

```java
import io.github.nameof.watermark.simple.*;

// 后缀标记策略（肉眼可见）
SuffixMarkerStrategy suffix = new SuffixMarkerStrategy();
String embedded = suffix.embed("张三", "operator:zhangsan", "secret");
// → "张三 [::operator:zhangsan::]"
String payload = suffix.extract(embedded, "secret");
// → "operator:zhangsan"

// 隐形填充策略（肉眼不可见）
InvisiblePaddingStrategy invisible = new InvisiblePaddingStrategy();
String hidden = invisible.embed("张三", "operator:zhangsan", "secret");
// → "张三" + 零宽字符序列（看起来还是"张三"，但 length() 显著增加）
String extracted = invisible.extract(hidden, "secret");
// → "operator:zhangsan"
```

### 8. 生成提取报告图片

提取水印后，生成可视化的报告图片用于追责证据展示。

```java
import io.github.nameof.watermark.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

// 提取水印
WatermarkResult<String> result = watermarker.extract(table, columns);

if (result.isSuccess()) {
    System.out.println("载荷: " + result.getData());

    // 生成报告图片
    BufferedImage reportImage = result.getReportImage();
    ImageIO.write(reportImage, "png", new File("watermark_report.png"));
    System.out.println("报告图片已生成");
}
```

报告图片包含：提取时间、水印模式、重复因子、CRC32 校验状态、还原载荷、提取统计（总单元格、有效提取数、投票置信度）、涉及列名。

## 架构概览

```
io.github.nameof.watermark/
├── WatermarkEngine.java              # 高层门面 API（推荐入口）
├── WatermarkConfig.java              # 配置（密钥、模式切换）
├── WatermarkResult.java              # 统一结果封装 + 报告图片生成
├── WatermarkReportGenerator.java     # Java 2D 报告图片生成器
├── DataWatermarker.java              # Bit-level 核心引擎
│
├── bit/                              # Bit-level 策略（每个单元格嵌入 1 bit）
│   ├── ColumnWatermarkStrategy.java  # 策略接口
│   ├── ColumnEmbedResult.java        # 嵌入结果
│   ├── ChineseTextWatermarkStrategy.java  # 中文 → 零宽字符
│   ├── LatinTextWatermarkStrategy.java    # 拉丁文 → 同形字替换
│   └── NumericWatermarkStrategy.java      # 数值 → 末位微扰
│
├── simple/                           # Simple 策略（每个单元格嵌入完整载荷）
│   ├── SimpleWatermarkStrategy.java  # 策略接口
│   ├── SimpleWatermarker.java        # 简单水印引擎
│   ├── SuffixMarkerStrategy.java     # 后缀标记（可见）
│   └── InvisiblePaddingStrategy.java # 隐形填充（零宽字符）
│
└── io/                               # 数据源抽象
    ├── TableData.java                # 表数据 DTO
    ├── DataSource.java               # 数据源接口
    ├── JdbcDataSource.java           # JDBC 实现
    └── CsvDataSource.java           # CSV 实现
```

## 两种模式对比

| 特性 | Bit-level | Simple |
|------|-----------|--------|
| 隐蔽性 | 极高（零宽字符/同形字/末位±1） | 中（后缀可见 / 零宽字符） |
| 健壮性 | 高（容忍 60% 数据删除） | 低（无冗余编码） |
| 数据量要求 | 高（需数百行以上） | 低（几行即可） |
| 实现复杂度 | 高（两段式编码+交叉分配+CRC32） | 低（每单元格独立） |
| 适用场景 | 对外数据泄露追溯 | 内部数据标记/调试 |

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
// 基本配置
WatermarkConfig config = new WatermarkConfig("your-secret-key");

// 指定最小重复因子（默认 5，越大越健壮但载荷容量越小）
WatermarkConfig config = new WatermarkConfig("your-secret-key", 8);

// 切换到简单模式
config.setUseBitLevel(false);
```

## 数据库要求

- MySQL 5.7+ / 8.0+
- 字符串列编码为 `utf8` 或 `utf8mb4`（零宽字符和同形字策略需要）
- JDBC 连接参数需包含 `characterEncoding=UTF-8`（注意：是 Java 标准名 `UTF-8`，不是 MySQL 的 `utf8mb4`）
- 纯数值列（末位微扰策略）对编码无要求

## License

MIT
