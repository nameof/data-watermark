package io.github.nameof.watermark;

import io.github.nameof.watermark.core.*;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.Assert.*;

/**
 * 核心 Watermarker 统一 API 测试。
 * <p>
 * 测试 canWatermark / embed / extract 在纯数据场景下的正确性，
 * 覆盖 bit-level 和 simple 两种模式。
 * </p>
 */
public class WatermarkerTest {

    private static final String PAYLOAD = "operator:zhangsan|company:ACME";
    private static final String SECRET = "watermarker-test-secret";

    // ==================== canWatermark 测试 ====================

    @Test
    public void testCanWatermarkMixedData() {
        List<Map<String, Object>> table = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "张三");
        row.put("email", "john@example.com");
        row.put("salary", new BigDecimal("12345.67"));
        table.add(row);

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);
        List<String> columns = Arrays.asList("name", "email", "salary");

        Set<WatermarkType> types = watermarker.canWatermark(table, columns);
        assertFalse("应检测到支持的水印类型", types.isEmpty());
        assertTrue("应支持中文零宽字符", types.contains(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
        assertTrue("应支持拉丁同形字", types.contains(WatermarkType.BIT_LATIN_HOMOGLYPH));
        assertTrue("应支持数值末位微扰", types.contains(WatermarkType.BIT_NUMERIC_LSB));
        assertTrue("应支持后缀标记", types.contains(WatermarkType.SIMPLE_SUFFIX_MARKER));
        assertTrue("应支持零宽填充", types.contains(WatermarkType.SIMPLE_INVISIBLE_PADDING));
        System.out.println("canWatermark 检测到类型: " + types);
    }

    @Test
    public void testCanWatermarkEmptyData() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        Set<WatermarkType> types = watermarker.canWatermark(new ArrayList<>(), Arrays.asList("col"));
        assertTrue("空数据应返回空集合", types.isEmpty());

        types = watermarker.canWatermark(null, null);
        assertTrue("null 输入应返回空集合", types.isEmpty());
    }

    // ==================== Bit-level 嵌入/提取测试 ====================

    @Test
    public void testBitLevelEmbedAndExtract() {
        List<Map<String, Object>> table = createTestTable(500);
        List<String> columns = Arrays.asList("name", "address", "salary");

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        // 嵌入（自动选择 bit-level 策略）
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue("嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());
        assertNotNull("嵌入结果数据不应为空", embedResult.getData());
        assertTrue("重复因子应 >= 5", embedResult.getRepetition() >= 5);
        System.out.println("Bit-level 嵌入成功 | 重复因子: " + embedResult.getRepetition()
                + " | 涉及列: " + embedResult.getWatermarkedColumns());

        // 提取
        WatermarkResult<String> extractResult =
                watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
        assertNotNull("水印类型应不为空", extractResult.getWatermarkType());
        assertTrue("水印类型应为 bit-level", extractResult.getWatermarkType().isBitLevel());
        System.out.println("Bit-level 提取成功 | 类型: " + extractResult.getWatermarkType()
                + " | 载荷: " + extractResult.getData());
    }

    @Test
    public void testBitLevelRobustnessAfterDataLoss() {
        List<Map<String, Object>> table = createTestTable(500);
        // 只使用中文列，避免数值列干扰
        List<String> columns = Arrays.asList("name", "address");

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        // 嵌入
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        // 模拟数据丢失：清除前 40% 的行（保留行索引不变）
        List<Map<String, Object>> damaged = new ArrayList<>(embedResult.getData());
        for (int i = 0; i < 200; i++) {
            damaged.set(i, new LinkedHashMap<>(damaged.get(i)));
            for (String col : columns) damaged.get(i).put(col, null);
        }

        // 提取（多数投票应仍能恢复）
        WatermarkResult<String> extractResult = watermarker.extract(damaged, columns);
        assertTrue("部分数据丢失后仍应能提取: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
        System.out.println("Bit-level 健壮性测试通过 | 清除 40% 数据后仍成功提取");
    }

    @Test
    public void testBitLevelWithSpecifiedTypes() {
        List<Map<String, Object>> table = createTestTable(500);
        List<String> columns = Arrays.asList("name", "address", "salary");

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        // 指定使用中文和数值策略
        List<WatermarkType> types = Arrays.asList(
                WatermarkType.BIT_CHINESE_ZERO_WIDTH, WatermarkType.BIT_NUMERIC_LSB);

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD, types);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        WatermarkResult<String> extractResult =
                watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功", extractResult.isSuccess());
        assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
        System.out.println("指定策略嵌入/提取成功");
    }

    // ==================== Simple 嵌入/提取测试 ====================

    @Test
    public void testSimpleEmbedAndExtract() {
        List<Map<String, Object>> table = createTestTable(20);
        List<String> columns = Arrays.asList("name", "address");

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        // 指定使用 simple 策略
        List<WatermarkType> types = Arrays.asList(
                WatermarkType.SIMPLE_SUFFIX_MARKER, WatermarkType.SIMPLE_INVISIBLE_PADDING);

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD, types);
        assertTrue("嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());
        System.out.println("Simple 嵌入成功 | 涉及单元格数: " + embedResult.getRepetition());

        // 提取
        WatermarkResult<String> extractResult =
                watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
        assertNotNull("水印类型应不为空", extractResult.getWatermarkType());
        assertFalse("水印类型应为 simple", extractResult.getWatermarkType().isBitLevel());
        System.out.println("Simple 提取成功 | 类型: " + extractResult.getWatermarkType()
                + " | 载荷: " + extractResult.getData());
    }

    @Test
    public void testSimpleRobustnessAfterDataLoss() {
        // 使用 SuffixMarker 策略（不依赖零宽字符，避免与 bit-level 策略冲突）
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            table.add(row);
        }

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);
        List<String> columns = Arrays.asList("name");

        List<WatermarkType> types = Arrays.asList(WatermarkType.SIMPLE_SUFFIX_MARKER);

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD, types);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        // 提取验证（SuffixMarker 不与 bit-level 策略冲突）
        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
        System.out.println("Simple 模式提取测试通过");
    }

    // ==================== 自动降级测试 ====================

    @Test
    public void testAutoFallbackToSimple() {
        // 构造少量数据，bit-level 容量不足
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            table.add(row);
        }
        List<String> columns = Arrays.asList("name");

        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        // 不指定策略，应自动降级到 simple
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        WatermarkResult<String> extractResult =
                watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功", extractResult.isSuccess());
        assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
        System.out.println("自动降级测试通过 | 类型: " + extractResult.getWatermarkType());
    }

    // ==================== 错误处理测试 ====================

    @Test
    public void testEmbedEmptyTable() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        WatermarkResult<List<Map<String, Object>>> result =
                watermarker.embed(new ArrayList<>(), Arrays.asList("col"), PAYLOAD);
        assertFalse("空表嵌入应失败", result.isSuccess());
    }

    @Test
    public void testEmbedNullPayload() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        WatermarkResult<List<Map<String, Object>>> result =
                watermarker.embed(createTestTable(10), Arrays.asList("name"), null);
        assertFalse("空载荷嵌入应失败", result.isSuccess());
    }

    @Test
    public void testEmbedOversizedPayloadReturnsFailure() {
        // 回归：载荷超长曾直接抛 IllegalArgumentException，而非统一返回 failure
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('x');

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Map<String, Object>>> result =
                watermarker.embed(createTestTable(500), Arrays.asList("name", "address"), sb.toString());

        assertFalse("超长载荷嵌入应失败", result.isSuccess());
        assertTrue("失败信息应说明长度限制: " + result.getMessage(),
                result.getMessage().contains("251"));
    }

    @Test
    public void testEmbedEmptyColumnsRejected() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));

        WatermarkResult<List<Map<String, Object>>> r1 =
                watermarker.embed(createTestTable(10), null, PAYLOAD);
        assertFalse("null 列名应失败", r1.isSuccess());

        WatermarkResult<List<Map<String, Object>>> r2 =
                watermarker.embed(createTestTable(10), new ArrayList<String>(), PAYLOAD);
        assertFalse("空列名应失败", r2.isSuccess());
    }

    @Test
    public void testExtractEmptyColumnsRejected() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));

        WatermarkResult<String> r = watermarker.extract(createTestTable(10), null);
        assertFalse("null 列名提取应失败", r.isSuccess());
    }

    @Test
    public void testExtractEmptyTable() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        WatermarkResult<String> result =
                watermarker.extract(new ArrayList<>(), Arrays.asList("col"));
        assertFalse("空表提取应失败", result.isSuccess());
    }

    @Test
    public void testEmbedBitLevelInsufficientCells() {
        // 指定 bit-level 类型绕过自动降级，验证容量不足的错误路径
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> bitTypes = Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH);

        // 场景 1：单元格数少于头部需求（24 个）
        List<Map<String, Object>> tiny = createTestTable(3);
        WatermarkResult<List<Map<String, Object>>> r1 =
                watermarker.embed(tiny, Arrays.asList("name"), PAYLOAD, bitTypes);
        assertFalse("单元格过少应失败", r1.isSuccess());

        // 场景 2：单元格超过 24 个但重复因子不足
        List<Map<String, Object>> small = createTestTable(30);
        WatermarkResult<List<Map<String, Object>>> r2 =
                watermarker.embed(small, Arrays.asList("name"), PAYLOAD, bitTypes);
        assertFalse("重复因子不足应失败", r2.isSuccess());
        assertTrue("失败信息应包含容量提示: " + r2.getMessage(),
                r2.getMessage().contains("重复因子"));
    }

    // ==================== WatermarkConfig 测试 ====================

    @Test
    public void testConfigDefaults() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        assertEquals("默认 minRepetition 应为 5", 5, config.getMinRepetition());
        assertEquals("默认 chunkSize 应为 2000", 2000, config.getChunkSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigEmptySecret() {
        new WatermarkConfig("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigLowRepetition() {
        new WatermarkConfig(SECRET, 2);
    }

    // ==================== WatermarkType 测试 ====================

    @Test
    public void testWatermarkTypeMode() {
        assertTrue(WatermarkType.BIT_CHINESE_ZERO_WIDTH.isBitLevel());
        assertTrue(WatermarkType.BIT_LATIN_HOMOGLYPH.isBitLevel());
        assertTrue(WatermarkType.BIT_NUMERIC_LSB.isBitLevel());
        assertFalse(WatermarkType.SIMPLE_SUFFIX_MARKER.isBitLevel());
        assertFalse(WatermarkType.SIMPLE_INVISIBLE_PADDING.isBitLevel());
    }

    // ==================== 确定性与副作用测试 ====================

    @Test
    public void testEmbedIsDeterministicAndDoesNotMutateInput() {
        List<Map<String, Object>> table = createTestTable(100);
        List<String> columns = Arrays.asList("name", "address", "salary");
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));

        // 深拷贝一份用于校验原表不被修改
        List<Map<String, Object>> snapshot = deepCopy(table);

        WatermarkResult<List<Map<String, Object>>> r1 = watermarker.embed(table, columns, PAYLOAD);
        WatermarkResult<List<Map<String, Object>>> r2 = watermarker.embed(table, columns, PAYLOAD);
        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertEquals("相同输入与密钥应产生完全相同的输出（seed 确定性）", r1.getData(), r2.getData());
        assertEquals("嵌入不应修改原表（深拷贝语义）", snapshot, table);
    }

    @Test
    public void testDifferentSecretsProduceDifferentEmbeddings() {
        List<Map<String, Object>> table = createTestTable(200);
        List<String> columns = Arrays.asList("name", "address", "salary");

        WatermarkResult<List<Map<String, Object>>> rA =
                new Watermarker(new WatermarkConfig("secret-A")).embed(table, columns, PAYLOAD);
        WatermarkResult<List<Map<String, Object>>> rB =
                new Watermarker(new WatermarkConfig("secret-B")).embed(table, columns, PAYLOAD);
        assertTrue(rA.isSuccess());
        assertTrue(rB.isSuccess());
        assertNotEquals("不同密钥应产生不同的嵌入位置", rA.getData(), rB.getData());
    }

    @Test
    public void testExtractWithoutWatermarkFails() {
        List<Map<String, Object>> table = createTestTable(100);
        List<String> columns = Arrays.asList("name", "address", "salary");
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));

        WatermarkResult<String> result = watermarker.extract(table, columns);
        assertFalse("未嵌入水印的数据提取应失败", result.isSuccess());
    }

    @Test
    public void testExtractWithWrongSecretFails() {
        // 回归：bit 策略的提取是扫描式的，密钥原本只影响嵌入位置；
        // 修复后载荷 bit 经密钥流置乱，错误密钥应导致 CRC32 校验失败
        List<Map<String, Object>> table = createTestTable(1000);
        List<String> columns = Arrays.asList("name", "address");

        WatermarkResult<List<Map<String, Object>>> embedded =
                new Watermarker(new WatermarkConfig("correct-secret")).embed(
                        table, columns, PAYLOAD,
                        Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
        assertTrue(embedded.isSuccess());

        // 用错误密钥提取：密钥流不同 → 载荷 bit 无法还原
        Watermarker wrongKeyMarker = new Watermarker(new WatermarkConfig("wrong-secret"));
        WatermarkResult<String> result = wrongKeyMarker.extract(embedded.getData(), columns);
        assertFalse("错误密钥提取应失败", result.isSuccess());
    }

    // ==================== 混合类型与自定义构造器 ====================

    @Test
    public void testMixedTypeListFallsBackToSimple() {
        // bit + simple 混合列表：任一 simple 类型存在时整体走 simple 模式
        List<Map<String, Object>> table = createTestTable(30);
        List<String> columns = Arrays.asList("name", "address");

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> mixed = Arrays.asList(
                WatermarkType.BIT_CHINESE_ZERO_WIDTH, WatermarkType.SIMPLE_SUFFIX_MARKER);

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD, mixed);
        assertTrue("混合类型应成功嵌入（走 simple 模式）: " + embedResult.getMessage(),
                embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData(), columns);
        assertTrue(extractResult.isSuccess());
        assertEquals(PAYLOAD, extractResult.getData());
        assertNotNull(extractResult.getWatermarkType());
        assertFalse("混合列表的主类型应为 simple", extractResult.getWatermarkType().isBitLevel());
    }

    @Test
    public void testCustomConstructorWithNullStrategyLists() {
        // null 策略列表 = 无可用策略，嵌入应失败而非 NPE
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET), null, null);

        WatermarkResult<List<Map<String, Object>>> result =
                watermarker.embed(createTestTable(100), Arrays.asList("name"), PAYLOAD);
        assertFalse("无策略时嵌入应失败", result.isSuccess());

        WatermarkResult<String> extractResult =
                watermarker.extract(createTestTable(100), Arrays.asList("name"));
        assertFalse("无策略时提取应失败", extractResult.isSuccess());
    }

    // ==================== 载荷编码与统计信息 ====================

    @Test
    public void testMultiByteUtf8PayloadRoundTrip() {
        // 中文载荷：UTF-8 多字节编码 + CRC32 校验（34 字节载荷需要 R>=5 的行数）
        String chinesePayload = "操作员:张三|部门:数据安全部";
        List<Map<String, Object>> table = createTestTable(1000);
        List<String> columns = Arrays.asList("name", "address");

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, chinesePayload);
        assertTrue("中文载荷嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData(), columns);
        assertTrue("中文载荷提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals(chinesePayload, extractResult.getData());
    }

    @Test
    public void testExtractStatisticsAndConfidence() {
        // 2000 个单元格保证 auto-select 选择 bit-level（R = 1976/288 = 6 >= 5）
        List<Map<String, Object>> table = createTestTable(1000);
        List<String> columns = Arrays.asList("name", "address");
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue(embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData(), columns);
        assertTrue(extractResult.isSuccess());

        // 统计元数据应完整填充
        assertTrue("总单元格数应 > 0", extractResult.getTotalCells() > 0);
        assertTrue("有效提取数应 > 0", extractResult.getValidExtractions() > 0);
        assertTrue("置信度应在 [0,100]: " + extractResult.getConfidence(),
                extractResult.getConfidence() >= 0 && extractResult.getConfidence() <= 100);
        assertEquals("bit-level 模式名", "bit-level", extractResult.getWatermarkMode());
        assertEquals("watermarkedColumns 应为涉及列",
                new LinkedHashSet<>(Arrays.asList("name", "address")),
                new LinkedHashSet<>(extractResult.getWatermarkedColumns()));
    }

    // ==================== 策略分发端到端测试 ====================
    // 回归：ChineseText 对任意 ≥2 字符的值 canWatermark=true，
    // 若注册在首位会吞掉所有值，Latin/Numeric 策略成为死代码。
    // 分发顺序必须"最具体优先"（Numeric → Latin → Chinese）。

    @Test
    public void testLatinOnlyDataEndToEnd() {
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("first_name", "employee_" + i);
            row.put("city", "office park " + i);
            table.add(row);
        }
        List<String> columns = Arrays.asList("first_name", "city");
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> types = Collections.singletonList(WatermarkType.BIT_LATIN_HOMOGLYPH);

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, "op:zhangsan", types);
        assertTrue("纯拉丁数据嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData(), columns);
        assertTrue("纯拉丁数据提取应成功（Latin 策略必须被分发到，而非被 Chinese 吞掉）: "
                + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("op:zhangsan", extractResult.getData());
        assertTrue(extractResult.getWatermarkType().isBitLevel());
    }

    @Test
    public void testNumericOnlyDataEndToEnd() {
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("salary", 10000 + i);
            row.put("bonus", 500 + i);
            table.add(row);
        }
        List<String> columns = Arrays.asList("salary", "bonus");
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> types = Collections.singletonList(WatermarkType.BIT_NUMERIC_LSB);

        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, "op:zhangsan", types);
        assertTrue("纯数值数据嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData(), columns);
        assertTrue("纯数值数据提取应成功（Numeric 策略必须被分发到）: "
                + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("op:zhangsan", extractResult.getData());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建包含中文文本和数值的测试数据。
     */
    private List<Map<String, Object>> createTestTable(int rows) {
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            row.put("address", "城市" + (i % 10) + "区街道" + i + "号");
            row.put("salary", String.format("%d.00", 10000 + i * 100));
            table.add(row);
        }
        return table;
    }

    /**
     * 深拷贝表数据。
     */
    private List<Map<String, Object>> deepCopy(List<Map<String, Object>> table) {
        List<Map<String, Object>> copy = new ArrayList<>(table.size());
        for (Map<String, Object> row : table) copy.add(new LinkedHashMap<>(row));
        return copy;
    }
}
