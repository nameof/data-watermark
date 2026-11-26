# TODO

> 最后更新：2026-11-25（P0 修复落地）

## 一、收尾类（补记录 / 路径同步）

- [ ] 把**端到端 MySQL 梯度验证**补进 `docs/adr/0002-*.md` 的落地记录。
      实测（2026-09-27，`MinDeleteDemoDb`，1200 行、payload `"这是隐蔽信息"` 18 字节、R=6）：
      | 场景 | 结果 |
      |---|---|
      | A 删中间 10 行（真实 DELETE） | OK，置信度 100% |
      | 删 10% / 30% / **50%** 中间段 | 全部 OK，置信度 100% |
      | B simple 档同一流程对照 | 全 OK |
      ADR 目前只记到 `MinDeleteDemo`（内存版，删中间第 100 行 FAIL→OK）。
- [ ] **2026-09-29 补测待记录**：`MinDeleteDemoDb` 已扩成覆盖**全部 5 个策略 + 自动选择**
      （结果文件 `src/test/java/io/github/nameof/watermark/diag/MinDeleteDemoDb-result.txt`）：
      | 策略 | 删中间 10 行 | 删中间 50% |
      |---|---|---|
      | `BIT_CHINESE_ZERO_WIDTH` | OK | OK |
      | `BIT_LATIN_HOMOGLYPH` | OK | OK |
      | `BIT_NUMERIC_LSB` | OK | OK |
      | `SIMPLE_SUFFIX_MARKER` | OK | OK |
      | `SIMPLE_INVISIBLE_PADDING` | **FAIL（删行前即 FAIL）** | FAIL |
      | 自动选择（实际选中 `BIT_LATIN_HOMOGLYPH`） | OK | — |

      ⚠️ 上表为 2026-09-29 修复前的历史记录。`SIMPLE_INVISIBLE_PADDING` 的 FAIL
      即 §二 的 P0，**已于 2026-11-25 修复**：复跑同流程三处全 OK（见 §二 验证记录）。
      结论：bit 档三个策略的删除鲁棒性修复**在全部 bit 策略上成立**；
      `SIMPLE_INVISIBLE_PADDING` 的失败是 §二 的 P0，与删除无关。
- [ ] 诊断程序已被移到 `src/test/java/io/github/nameof/watermark/diag/`（原 `tools/diag/`），
      ADR-0002 §复现方式 里的路径与运行命令需同步更新。

## 二、P0（正确性缺陷）—— ✅ 已修复（2026-11-25）

- [x] **`extractSimple` 无跨策略回退 + 完全忽略 types**（原 `Watermarker.java:740`）
      - 修复方式（痕迹探测 + 短路投票）：`extractSimple` 加 `types` 参数并由 `extract`（L274）透传；
        提取端不再用 `canWatermark` 做前置路由，改为对候选策略（`candidateSimpleStrategies`，
        按注册顺序）**逐个调用 `extract`、第一个非 null 即采纳**（每格短路，至多 1 票）；
        水印类型按实际命中策略的多数票（`typeVotes`）判定，根治恒误报 `SIMPLE_SUFFIX_MARKER`；
        附带 `isPlausiblePayload` 合法性粗筛（非空 / 无 U+FFFD / 无控制字符）抑制乱码假阳性票；
        删除死方法 `findAnySimpleStrategy`；`DatabaseWatermarker` 新增带 types 的
        `extract(TableData, types)` / `extract(ChunkSource, types)` 重载
      - 修复不影响嵌入端与数据格式：`embed` / `autoSelectStrategies` / bit 档一行未动，
        自动路径产出的数据（SuffixMarker 承载）行为不变，已落库的 InvisiblePadding 老水印**无需重嵌即可读出**
      - 新增回归测试 `core/simple/SimpleExtractRoutingTest`（8 用例，含 DB 层重载）
      - 验证（2026-11-25 实测）：`mvn test` **202/202 全绿**（原 194 + 新增 8，
        MySQL 可达，集成测试 3 用例真实执行）；`InvisiblePaddingCheck` 的 [A] 两种提取
        `success=false → true`；`MinDeleteDemoDb` 端到端 1200 行：
        `SIMPLE_INVISIBLE_PADDING` 删行前 / 删中间 10 行 / 删中间 50% **三处全 OK（置信度 100%）**，
        其余 5 组（bit 三策略 + SuffixMarker + 自动选择）不受影响全 OK

## 三、策略层缺陷（审查报告清单，均未动）

- [ ] `NumericWatermarkStrategy`：科学计数法文本（如 `"1E3"`）被静默改数
- [ ] `NumericWatermarkStrategy.canWatermark` 接受 `NaN`/`Infinity` → 嵌入后值被改坏
      （已在 ADR-0002 §遗留问题 2 记录）
- [ ] `ChineseTextWatermarkStrategy`：零宽字符清理会破坏 emoji ZWJ 组合序列
- [ ] `LatinTextWatermarkStrategy`：同形字归一化会把**真实**的西里尔文本改写掉
- [ ] `InvisiblePaddingStrategy`：缺长度预检 → 短字段 VARCHAR 溢出（`Data truncation`）
- [ ] `SuffixMarkerStrategy`：数据本身含 `[::` 时假阳性 / payload 含 `]` 解析错
- [ ] 全策略：`String.length` 与 code point 混用，UTF-16 代理对被从中间拆开

## 四、database / io 层缺陷（均未动）

- [ ] `DatabaseEmbedResult` 缺 `success` 字段 → **分块失败被静默聚合成"成功"**
- [ ] `chunkSize` 等参数无校验（0 / 负数）
- [ ] `CsvDataSource` 读写不对称（引号转义、字符集）
- [ ] `JdbcDataSource` 表名/列名直接拼接（SQL 注入面）

## 五、测试补强

- [ ] emoji / 代理对、数值量化边界、payload 250 字节边界
- [x] ~~`InvisiblePadding` 单独端到端~~ —— 已覆盖（2026-09-29），并据此**钓出 §二 的 P0**
- [ ] 把 `MinDeleteDemoDb` 的删除场景固化为 JUnit 集成测试
      （现在只有手工运行的 `main`，`mvn test` 覆盖不到）

## 六、清理

- [ ] `mixHash(String key, int nonce)` 的 `nonce` 形参已无实际调用方（恒传 0），可删

---

## 七、原始备忘（你之前提出，本次未动）

- 删除 io 下"傻逼代码"
- `InvisiblePaddingStrategy` 等类需要增加判断，只支持处理字符串数据
- `Watermarker.embed(table, columns, ...)` 接收 table 结构不够抽象：
  水印的数学需求是"有序、稳定、可微修改的值序列"，表格只是其一种投影，
  理论上 `List` 就够，table 是否把数据限制太死？
  （注：core 已有 values API，见 ADR-0001；Database 层仍用 Table API 以保留列名上下文）
