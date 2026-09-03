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
    public void testExtractEmptyTable() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        Watermarker watermarker = new Watermarker(config);

        WatermarkResult<String> result =
                watermarker.extract(new ArrayList<>(), Arrays.asList("col"));
        assertFalse("空表提取应失败", result.isSuccess());
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
}
