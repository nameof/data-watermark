# ADR-0002: bit-level 位槽分配缺陷 —— 「位置取模」改为「稳定键哈希分桶」

- **状态**：**已采纳并落地（Accepted & Implemented）** —— 2026-09-21 采纳方案 A 并完成实现
  - 落地内容：位槽改为稳定键哈希分桶；提取端"候选长度扫描 + CRC32 定夺"；
    头部槽位 24 → 8；容量判定改为按**独立投票者**计；`stableKey` 支持数值字符串。
  - 回归验证：`mvn test` 194/194 通过（含 6 个删除/乱序场景）；
    `tools/diag/MinDeleteDemo.java` 中"删中间第 100 行"由 FAIL 转为 OK。
  - 未决项：数值列有效冗余腰斩（§"遗留问题"）、legacy 位置取模读取通道是否保留。
- **日期**：2026-09-21（排查）/ 2026-09-24（落地）
- **作者**：排查结论（阿岩）
- **修订/关联**：
  - [`0001-core-api-list-values.md`](./0001-core-api-list-values.md) §"Seed 约定"（该节结论不完整，见 BUG-3）
  - `README.md` §"Bit-level 容量与鲁棒性"（失效承诺，见 §"需同步修正的文档"）
  - `core.Watermarker` bit-level 嵌入/提取路径
- **证据**：`tools/diag/SeedDiag.java` + `tools/diag/SeedDiag-report.txt`（可重跑，只用真实库类，无 mock）

---

## 结论（TL;DR）

**是 BUG，且是原理性缺陷，不是边界情况。**

ADR-0001 的「Seed 约定」（`seed = scramble(i, secret)`，要求 list 顺序一致）在文字上是诚实的，但它**掩盖了真实的实现缺陷**：库在 README 和 javadoc 中承诺的"删除任意连续区域仍可还原 / 容忍 (R-1)/R 数据丢失"**不成立**。

实测（500 个可嵌入值，R=6，仅删 1 行）：

| 场景 | 结果 |
|---|---|
| 删尾部任意行 | ✅ 100% 还原 |
| 删中间/头部 1 行 | ❌ 必然失败（`delete index 100 / 250 / 0 / 23` 全部 fail） |
| 随机散删 30% | ❌ 0/10 还原 |

**用户提出的场景（对数据库某一列打水印 → 回存 → 业务删行 → 重新 extract 传入新 list）在有非尾部删除时会失败**，除非走 Database 分块路径且**还有整块未被触碰**。

---

## 缺陷清单

### BUG-1（主因）：位槽分配与「位置」绑定，任何非尾部增删都使全体错位

`scanCellsForBit` 返回的是**紧凑**（compact）单元格序列 —— null 与不可嵌入的值被跳过：

```java
// Watermarker.java:778-791（节选）
for (int r = 0; r < table.size(); r++)
    for (int c = 0; c < columns.size(); c++) {
        Object value = row.get(columns.get(c));
        if (value != null && findBitStrategy(value, types) != null)
            result.add(new CellRef(r, c, columns.get(c), value));   // row 是真实行下标
    }
```

随后载荷区的 bit 槽**不使用真实行下标，而使用紧凑序列的下标 `i`**：

```java
// Watermarker.java:422-434（节选）
for (int i = 0; i < payloadCells; i++) {
    int bitPos = i % N;                          // ← 槽位由「紧凑下标」决定
    CellRef cell = cells.get(HEADER_CELLS + i);
    long seed = cellSeed(cell.row, cell.colIndex); // ← seed 由「真实行下标」决定
    ...
}
```

于是**同一份数据里混用了两套坐标**。只要序列中任意一个单元格"消失"（行被删、值变 null、值被改成不可嵌入类型），其后所有单元格的紧凑下标整体前移，**每个单元格携带的 bit 被投票到错误的槽位**。

- **为什么只删尾部能过**：尾部消失不改变前缀元素的紧凑下标 → 槽位不变。
- **为什么删 1 行还有约 45% 概率成功**（实测 9/20）：设删除点 `d`，则原始下标 `> d` 的单元格槽位前移 1。槽 `b` 同时收到「未受影响的正确票」（数量 ∝ `d-24`）与「来自原槽 `b+1` 的错误票」（数量 ∝ `M-d`），多数表决只有在未受影响部分占多数时才正确 → 需要 `d` 落在后半段。取 `d` 均匀落在 `[24,499]`，`P = (499-262)/(499-24) ≈ 50%`，实测 9/20 ≈ 45%，模型吻合。`k=2` 时预测 ≈25%，实测 6/20 = 30%，同样吻合。

### BUG-2：头部 24 个单元格是单点故障

头部固定占用**紧凑序列的前 24 个**单元格（`bitPos = i / HEADER_REP`，`HEADER_REP = 3`），即"数据集最前面的 24 个可嵌入值"，且只享受 3 倍重复、**没有任何空间冗余**。头部一坏，`payloadByteLen` 落到 `[5,256]` 之外即直接失败：

```java
// Watermarker.java:487-489
if (payloadByteLen < 5 || payloadByteLen > 256)
    return WatermarkResult.failure("头部长度异常: " + payloadByteLen);
```

实测对比（同一份 500 值数据，只洗掉水印、不删行）：

| 破坏范围 | 结果 |
|---|---|
| 洗掉前 24 个（全部头部单元） | ❌ 失败 |
| 洗掉载荷区 50 个 | ✅ 成功 |
| 洗掉载荷区 100 个 | ✅ 成功 |
| 洗掉载荷区 195 个（占载荷区 41%） | ✅ 成功 |

**载荷区能扛 41% 的破坏，头部却扛不住 24 个**。这是与 BUG-1 相互独立的第二个缺陷。

### BUG-3：「Seed 约定」的描述与实现不符 —— 约束被严重低估

四个策略的 `extract` **都不使用 `seed`，其中三个连 `secret` 都不用**：

| 策略 | extract 实际依据 | 用 seed？ | 用 secret？ |
|---|---|---|---|
| `ChineseTextWatermarkStrategy` | 扫描全文，返回**第一个**零宽字符 | ❌ | ❌ |
| `LatinTextWatermarkStrategy` | 值中是否存在西里尔同形字 | ❌ | ❌ |
| `NumericWatermarkStrategy` | 末位数字奇偶 | ❌ | ❌ |
| `SuffixMarkerStrategy` | 正则式模式匹配 `[::...::]` | ❌ | ❌ |
| `InvisiblePaddingStrategy` | 从第一个零宽字符顺序解码 | ❌ | ❌ |

实测：`embed(seed=111, secret=SECRET)` → `extract(seed=999999)` / `extract("other-secret")` 结果**完全相同**。

推论：

- 对 **simple 模式**，"list 顺序一致"这个约束是**多余的**（提取与顺序无关）；
- 对 **bit-level 模式**，"list 顺序一致"是**远远不够**的 —— 真正的约束是「数据集一行不多、一行不少」。ADR-0001 把它描述为"与行序稳定同构的、不增加新负担的约束"，实际上它等价于「embed 与 extract 之间数据必须完全冻结」，这在"打水印后回存、业务继续使用"的场景里不可能满足。

**结论：用户的直觉是对的 —— 这个约束在设计上是不可满足的。**

### BUG-4（次要）：显式指定策略时 embed / extract 路由不一致

`embedXxx` 用 `findBitStrategy(value, types)`（**按 `types` 过滤**），而 `extractBitLevel` / `extractSimple` 用 `findAnyBitStrategy` / `findAnySimpleStrategy`（**不按 types 过滤**，只按注册顺序）。

```java
// Watermarker.java:797-812
private BitCarrierStrategy findBitStrategy(Object value, List<WatermarkType> types) {
    for (BitCarrierStrategy s : bitStrategies) {
        if (types != null && !types.contains(s.type())) continue;  // 嵌入：过滤
        if (s.canWatermark(value)) return s;
    }
    return null;
}
private BitCarrierStrategy findAnyBitStrategy(Object value) {      // 提取：不过滤
    for (BitCarrierStrategy s : bitStrategies)
        if (s.canWatermark(value)) return s;
    return null;
}
```

实测：值形如 `user0_test_data`、显式 `types=[BIT_CHINESE_ZERO_WIDTH]` 时，**embed 走 Chinese、extract 走 Latin**（`Latin.extract=0`，`Chinese.extract=1`）→ 提取全部为 0 → 头部长度异常 → 失败。

（自动选策略路径是自洽的，所以这个坑只在显式指定策略时出现，但很隐蔽。）

---

## 为什么现有测试没发现

| 测试 | 为什么漏检 |
|---|---|
| `WatermarkerValuesApiTest.testValuesApiSurvivesPartialDeletion`（L299-318） | 删的是**尾部** 30%（`subList(0, keep)`），索引未变 → 通过 |
| `WatermarkerValuesApiTest.testEmbedExtractRoundTripBitLevel`（L115-126） | 名字含 BitLevel，但 500 值 + 31 字节载荷 → `R = (500-24)/288 ≈ 1.65 < minRepetition(5)` → `autoSelectStrategies` **自动降级到 simple**。实测输出的 `type=SIMPLE_SUFFIX_MARKER`，**根本没走 bit-level** |
| `WatermarkerValuesApiTest.testEmbedListExplicitStrategies`（L98-110） | 只断言 embed 成功，不做 extract |

**bit-level 路径上没有任何"非尾部删除"的测试** —— 这是缺陷能长期潜伏的直接原因。

---

## 修复方案

### 方案 A（推荐）：位槽分配改为「稳定键哈希分桶」

把"槽位 = 紧凑下标 % N"改成"槽位 = 稳定键的哈希 % 槽总数"：

```
SLOT_TOTAL = 24 + N                       // 头部槽 0..23，载荷槽 24..24+N-1
slot(cell) = floorMod( mixHash(secret, cellKey), SLOT_TOTAL )
  slot < 24        → 头部 bit = headerBits[ slot / 3 ]
  24 <= slot < 24+N → 载荷 bit = payloadBits[ slot - 24 ]
```

`cellKey`（稳定标识）的来源：

- **Values API（`List<?>`）**：默认用 `clean(value)` —— 即"去掉水印痕迹后的原值"（零宽字符剥离 / `[::...::]` 移除 / 拉丁同形字归一）。对 zero-width、suffix、latin 三种策略**天然可逆**，embed 与 extract 都能算出同一个 key，**且与行序完全无关**。
- **数值列（`BIT_NUMERIC_LSB`）**：末位 ±1 会改值，`clean()` 不可逆。改用"去掉最低位的量化值"作 key（`scaled >>> 1`），或对数值列强制要求调用方提供 key。
- **Table API / Database 层**：新增可选 `keyProvider`（`Map<String,Object> row -> String`），推荐传**主键列**。这是数据库水印文献里的标准稳定假设：PK 稳定 ≫ 行序稳定。

**收益**

- 任意位置删除/新增行 → 只损失该单元格自己的 1 票，冗余**真正**生效，达到 README 承诺的 `(R-1)/R` 容忍度；
- 头部从"前 24 格"变成"散布全数据集"，**BUG-2 一并消除**；
- 顺手把 ADR-0001 的"list 顺序必须一致"约束**放宽为"顺序无关"**，抽象更干净。

**代价 / 风险**

- 算法语义变更 → 已嵌入的旧水印读不出来（当前 `1.0.0-SNAPSHOT`，可接受）；
- 槽位分布变成随机量 → `embed` 的容量校验不能再用 `payloadCells / N >= minRepetition`，需要新增"槽位覆盖率"校验（如二项分布下界 / 对未覆盖槽位重排补槽），否则小数据集会出现空槽导致空票；
- `minRepetition` 语义从"确定值"变成"期望值"，javadoc 与 README 需同步。

**可行性已验证**（`SeedDiag` E9）：

| 方案 | 随机删 50% 后还原结果 |
|---|---|
| (a) 现行「位置取模」 | `"A)<@B,"` ❌ FAIL |
| (b) 「稳定键哈希分桶」 | `"WM2026"` ✅ OK |
| (c) 「位置取模」但只删尾部 | `"WM2026"` ✅ OK（说明现行实现只在尾删时正确） |

### 方案 B（不改 core，今天就能用）：数据库列水印改用 simple 模式

simple 每格独立承载完整载荷，提取不依赖 seed / 顺序。实测：**删 50%（含头部 25%）仍 100% 还原**。

代价：`SuffixMarker` 是**明文可见**标记（`[::payload::]`）；`InvisiblePadding` 不可见但会把字段撑长 `payload.length()*8+1` 个字符，短字段（`VARCHAR(50)` 之类）会溢出（本项目历史已踩过这个坑）。适合能接受明文标记、或字段足够长的过渡场景。

### 方案 C（补丁式，不推荐）

让 `extract` 支持"含 null 占位的稀疏序列"（`scanCellsForBit` 不跳过 null 而是占位），调用方按**原始 PK 全集**拼装（缺失行填 `null`）即可对齐。

缺点：只解决调用方拿得到"原始行全集"的情形；不解决 BUG-2；改变 `scanCellsForBit` 既有语义会牵连其他路径。**注意：单纯"按主键排序"是无效的** —— 排序不改变"删除后剩余元素下标整体前移"这一事实。

### 无论选哪个方案，都建议立刻做的

1. **修正 README 的失效承诺**（这是当前最危险的部分，下游会照着做容量规划）：
   - L335 `| 鲁棒性 | 高（冗余分布，容忍 50%+ 数据删除） |`
   - L369-376 的"理论最大容忍比例 / 实际建议安全线"整张表 + "即使随机删除一半的数据行，水印仍然可以被正确还原"
   - L386 `→ 可容忍约 60% 的数据删除（约 480 行）`
2. **修正 `Watermarker` 类 javadoc**：`删除任意连续区域只影响每个 bit 的少量副本，通过多数投票恢复，容忍高达 (R-1)/R 比例的数据丢失`（L31-33）
3. **明确写清**：bit-level 模式的鲁棒性**仅在"只在尾部截断"或"按方案 A 改造后"成立**。

---

## 复现方式（本机实测，Windows + JDK 8）

```powershell
$JDK  = "C:\Software\JDK\jdk\bin"
$OUT  = "E:\workspace\data-watermark\target\diag-classes"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

# 1) 编译主代码
$srcs = Get-ChildItem "E:\workspace\data-watermark\src\main\java" -Recurse -Filter *.java | % FullName
& "$JDK\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -d $OUT $srcs

# 2) 编译 + 运行诊断
& "$JDK\javac.exe" -encoding UTF-8 -cp $OUT -d $OUT "E:\workspace\data-watermark\tools\diag\SeedDiag.java"
& "$JDK\java.exe" "-Dfile.encoding=UTF-8" -cp $OUT SeedDiag "E:\workspace\data-watermark\tools\diag\SeedDiag-report.txt"
```

> 注意：`-Dfile.encoding=UTF-8` 在 PowerShell 里必须加引号，否则会被拆成 `-Dfile` + `.encoding=UTF-8`。

### 最小复现（二选一）

- **内存版**（不需要数据库）：`tools/diag/MinDeleteDemo.java`，4 个场景对照——bit 档不删/删中间/删尾部 + simple 档随机删 20%。
  结果文件：`tools/diag/MinDeleteDemo-result.txt`。
- **端到端 MySQL 版**（模拟真实业务链路：嵌入 → UPDATE 写回 → `DELETE FROM ... WHERE id BETWEEN 200 AND 209`（等价人工删行）→ 重新 SELECT → 提取）：
  `tools/diag/MinDeleteDemoDb.java`，只建临时表 `watermark_delete_demo`，结束自动 DROP。
  运行需加 MySQL 驱动到 classpath（`D:\jar\com\mysql\mysql-connector-j\8.3.0\mysql-connector-j-8.3.0.jar`）。
  结果文件：`tools/diag/MinDeleteDemoDb-result.txt` —— **bit 档删中间 10 行后提取 FAIL，simple 档同流程 OK**。

---

## 决策项与结论（2026-09-21 计划批准后执行）

| # | 待定项 | 结论 |
|---|---|---|
| 1 | 是否采纳**方案 A**（稳定键哈希分桶）？ | ✅ **采纳并实现**。`cellKey` 默认取 `stableKey(value)`（自动剥离水印痕迹），不强制调用方提供 keyProvider（Database 层可后续加可选项） |
| 2 | 数值列（`BIT_NUMERIC_LSB`）的分桶策略？ | ✅ **量化去 LSB**：`quantizedNumericKey` = `floor(v × 10^scale / 2)`。**并修正了一个连带缺陷**：原实现只对 `Number` 走量化，数据库 CHAR 列里的金额字符串（`"10000.00"`）走文本分支 → 被 Numeric 策略改末位后键漂移 → 槽位全变。现改为"剥掉水印痕迹后，长成数值的字符串同样量化" |
| 3 | 是否保留"位置取模"作为 legacy 读取通道？ | ❌ **不保留**。旧算法在"数据会增删"场景本就还原不了；且提取失败时 CRC32 会明确报错，不会静默返回错误载荷 |
| 4 | 是否立刻先修 README / javadoc 的失效承诺？ | ✅ **已修正**（README §容量公式/容量表/鲁棒性/示例，`Watermarker` 类注释容量章节） |

---

## 实施方案 A 的落地记录（2026-09-24）

### 代码改动（`core/Watermarker.java`）

| 项 | 改动前 | 改动后 |
|---|---|---|
| 位槽分配 | `bitPos = i % N`（`i` = 紧凑下标，随位置漂移） | `slot = floorMod(mixHash(secret, stableKey(value)), 8 + N)`（由内容决定） |
| 头部槽位 | 24 个（8 bit × `HEADER_REP=3`），固定占最前面 | **8 个**（1 槽/bit）。理由：`HEADER_REP=3` 是位置取模时代的补偿——那时头部固定占前 24 格，只能靠"一 bit 占 3 格"凑 3 票；哈希分桶下每槽天然有约 R 票，无需再 ×3，且省下的 16 个槽全部给载荷 |
| 长度/槽位循环依赖 | 不存在（槽位与长度无关） | 提取端按**候选长度扫描**（`enc = 5..255`），只接受"头部自洽（读出的长度 == 假设值）且 CRC32 通过"的候选 |
| 零票槽 | 不存在 | 数据删除后某位可能一个投票者都不剩：**穷举补全 + CRC32 定夺**，上限 `MAX_ERASURES=12`（2^12 次校验） |
| 容量判定 | `R = M / 槽位数`（M = 单元格数） | `R = 独立投票者数 / 槽位数`（独立投票者 = 内容互不相同的单元格）。原因：内容相同的单元格共享槽位、只贡献 1 票 |
| 提取策略路由 | `findAnyBitStrategy`（**不按 types 过滤**） | `findBitStrategy(value, types)`（与嵌入端同口径），修复 BUG-3 |
| 载荷上限 | `MAX_PAYLOAD_BYTES = 251` | **250**（251+5 = 256 会让 8-bit 长度字段溢出为 0） |
| 提取 API | `extract(table, columns)` | 新增 `extract(table, columns, types)`；值序列版因泛型擦除冲突改名 `extractValues(values, types)` |

### 验证

| 验证项 | 结果 |
|---|---|
| `mvn test` | **194/194 通过**（新增 6 个删除/乱序场景；3 个 MySQL 集成测试因容器未启动 skip） |
| `tools/diag/MinDeleteDemo.java` | `[2] 删中间第 100 行`：**FAIL → OK**；其余 3 场景保持 OK |
| 新增回归 `testBitLevelSurvivesHeadTailAndRandomDeletion` | 删头部 20% / 删尾部 20% / 随机删 ~50% / 整体乱序 / 追加 200 行无水印数据 —— **5/5 通过**，且类型确认为 `BIT_CHINESE_ZERO_WIDTH`（真走 bit-level） |
| `testBitLevelRobustnessAfterDataLoss`（改造） | 原测试用 500 行 × 2 列 → R < 5 → **静默降级 simple，从未测过 bit-level**；现改为 1000 行 + 真实删除中间 300 行，并断言类型必须是 bit-level |

### 连带修复的测试盲区

1. `testBitLevelRobustnessAfterDataLoss` 名不副实（实际跑 simple）—— 已改造并加类型断言。
2. `testEmbedOversizedPayloadReturnsFailure` 断言硬编码 `"251"` —— 改为引用 `Watermarker.MAX_PAYLOAD_BYTES`，避免上限调整后断言静默过期。
3. `testNumericOnlyDataEndToEnd` 用连续整数（`10000+i`）→ LSB 量化后两两碰撞、有效投票者腰斩 → 改为奇数步长，并在注释里写明原因。

### 遗留问题（新发现，待处理）

1. **数值列有效冗余腰斩**：任何"末位承载 1 bit"的数值水印，都无法区分"原值是 10001"与"原值 10000 被改成 10001"（信息论必然），因此相邻整数必然共享位槽。**连续自增数值列（流水号、ID）的有效冗余只有实际行数的一半**，容量规划需按"独立投票者"而非"行数"计算。
2. **`NumericWatermarkStrategy.canWatermark` 接受 `NaN`/`Infinity`**：`Double.parseDouble("NaN")` 成功 → 被当成可嵌入数值，嵌入后值变成 `"1"` 之类，破坏数据。属于策略层待修项。
3. **零票槽穷举上限 12**：随机删除比例很高（每槽期望票数 < 3）时，空槽可能超过上限 → 提取直接失败（报"CRC32 校验不匹配"）。这是保守设计（宁失败不返回错载荷），但错误信息可更明确。

---

## 附：完整实验记录（E0–E11）

见 `tools/diag/SeedDiag-report.txt`。摘要：

| # | 实验 | 关键结果 |
|---|---|---|
| E0 | 现有测试走哪条路径 | `testEmbedExtractRoundTripBitLevel` 实际降级为 `SIMPLE_SUFFIX_MARKER` |
| E1 | 基线（不删） | ✅ `test` conf=100% |
| E2 | 删 1 行 | 中间/头部 ❌；尾部 ✅ |
| E3 | 按比例删 | 只砍尾部 30% ✅；随机散删 30% → 0/10 ❌ |
| E4 | 顺序扰动 | 交换相邻 2 行 ✅（只错 1 票）；整体左旋 1 位 ❌ |
| E5 | 只改值不删行 | 载荷区洗掉 41% ✅；洗掉头部 24 格 ❌ |
| E6 | 删除行数扫描 | k=1 → 9/20；k≥4 → 0/20 |
| E7 | simple 模式 | 删 50%（含头部）✅ |
| E8 | 策略层 seed/secret 敏感性 | 4 个策略的 extract 均忽略 seed，3 个忽略 secret |
| E9 | 修复 PoC | 键哈希分桶删 50% ✅ / 位置取模 ❌ |
| E10 | Database 分块路径 | 只删 1 块中间 1 行 ✅（其他块兜底）；每块都删 1 行 ❌；换 chunkSize ❌（部分块） |
| E11 | 显式策略路由 | embed 走 Chinese / extract 走 Latin → ❌ |
