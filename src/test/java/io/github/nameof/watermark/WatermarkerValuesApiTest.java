package io.github.nameof.watermark;

import io.github.nameof.watermark.core.*;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Values API（{@code embed(List<?>) / extract(List<?>) / canWatermark(List<?>)}）测试。
 * <p>
 * Values API 与 Table API（{@code embed(List<Map>, List<String>)}）共用同一套内部算法，
 * 仅在外部数据形态上有差异。本测试验证：
 * <ul>
 *   <li>基本功能：canWatermark / embed / extract 三件套在 List<?> 形态下正确工作</li>
 *   <li>往返一致性：embed 后 extract 能还原原始载荷</li>
 *   <li>等价性：在等价输入下，Values API 与 Table API 产生等价的输出</li>
 *   <li>边界条件：空列表、null 输入</li>
 *   <li>不同值类型：String、Number、混合类型</li>
 * </ul>
 */
public class WatermarkerValuesApiTest {

    private static final String PAYLOAD = "operator:zhangsan|company:ACME";
    private static final String SECRET = "watermarker-values-api-test-secret";
    /** 短载荷，用于单策略测试（默认载荷 31 字节需要 ~1464 cells，500 cells 容量不足） */
    private static final String SHORT_PAYLOAD = "test";

    // ==================== canWatermark(List<?>) ====================

    @Test
    public void testCanWatermarkListMixedTypes() {
        List<Object> values = Arrays.asList(
                "张三", "john@example.com", new BigDecimal("12345.67"), 100L);

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        Set<WatermarkType> types = watermarker.canWatermark(values);

        assertFalse("应检测到支持的水印类型", types.isEmpty());
        assertTrue("应支持中文零宽字符", types.contains(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
        assertTrue("应支持拉丁同形字", types.contains(WatermarkType.BIT_LATIN_HOMOGLYPH));
        assertTrue("应支持数值末位微扰", types.contains(WatermarkType.BIT_NUMERIC_LSB));
    }

    @Test
    public void testCanWatermarkListEmpty() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        assertTrue("空列表应返回空集合",
                watermarker.canWatermark(new ArrayList<Object>()).isEmpty());
        assertTrue("null 应返回空集合", watermarker.canWatermark(null).isEmpty());
    }

    @Test
    public void testCanWatermarkListSkipsNullValues() {
        List<Object> values = Arrays.asList("张三", null, "李四");
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        Set<WatermarkType> types = watermarker.canWatermark(values);
        assertTrue("应支持中文零宽字符", types.contains(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
    }

    // ==================== embed(List<?>) 基本功能 ====================

    @Test
    public void testEmbedListBitLevel() {
        // 准备足够的数据以满足 bit-level 容量需求
        List<Object> values = createTestValues(500);

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> result = watermarker.embed(values, PAYLOAD);

        assertTrue("嵌入应成功: " + result.getMessage(), result.isSuccess());
        assertNotNull("嵌入结果数据不应为空", result.getData());
        assertEquals("返回序列大小应等于原序列大小", values.size(), result.getData().size());
        assertTrue("重复因子应 >= 5", result.getRepetition() >= 5);
        System.out.println("Values API Bit-level 嵌入成功 | 重复因子: " + result.getRepetition()
                + " | 类型: " + result.getWatermarkType());
    }

    @Test
    public void testEmbedListSimple() {
        // simple 模式只需少量数据
        List<Object> values = Arrays.asList(
                "张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十",
                "陈十一", "褚十二", "卫十三", "蒋十四", "沈十五", "韩十六");

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> strategies = Arrays.asList(WatermarkType.SIMPLE_SUFFIX_MARKER);
        WatermarkResult<List<Object>> result = watermarker.embed(values, PAYLOAD, strategies);

        assertTrue("嵌入应成功: " + result.getMessage(), result.isSuccess());
        assertEquals("返回序列大小应等于原序列大小", values.size(), result.getData().size());
        System.out.println("Values API Simple 嵌入成功 | 涉及单元格: " + result.getRepetition());
    }

    @Test
    public void testEmbedListExplicitStrategies() {
        List<Object> values = createTestValues(500);
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));

        // 使用短载荷以满足单策略下的容量要求
        List<WatermarkType> bitTypes = Arrays.asList(WatermarkType.BIT_CHINESE_ZERO_WIDTH);
        WatermarkResult<List<Object>> bitResult = watermarker.embed(values, SHORT_PAYLOAD, bitTypes);
        assertTrue("Bit-level 嵌入应成功: " + bitResult.getMessage(), bitResult.isSuccess());

        List<WatermarkType> simpleTypes = Arrays.asList(WatermarkType.SIMPLE_SUFFIX_MARKER);
        WatermarkResult<List<Object>> simpleResult = watermarker.embed(values, SHORT_PAYLOAD, simpleTypes);
        assertTrue("Simple 嵌入应成功: " + simpleResult.getMessage(), simpleResult.isSuccess());
    }

    // ==================== embed → extract 往返 ====================

    @Test
    public void testEmbedExtractRoundTripBitLevel() {
        List<Object> values = createTestValues(500);

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData());
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", PAYLOAD, extractResult.getData());
        System.out.println("Values API 往返测试通过 | 置信度: " + extractResult.getConfidence() + "%");
    }

    @Test
    public void testEmbedExtractRoundTripSimple() {
        List<Object> values = Arrays.asList(
                "张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十",
                "陈十一", "褚十二", "卫十三", "蒋十四", "沈十五", "韩十六");

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> strategies = Arrays.asList(WatermarkType.SIMPLE_SUFFIX_MARKER);

        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, PAYLOAD, strategies);
        assertTrue("Simple 嵌入应成功", embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData());
        assertTrue("Simple 提取应成功", extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", PAYLOAD, extractResult.getData());
    }

    // ==================== 与 Table API 的等价性 ====================

    /**
     * 核心断言：把同样的值用 Values API 和 Table API 分别嵌入，
     * 嵌入后的值应该一致（因为内部共用同一套算法）。
     */
    @Test
    public void testValuesApiEquivalentToTableApi() {
        List<Object> values = createTestValues(500);

        // Values API 嵌入
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> valuesResult = watermarker.embed(values, PAYLOAD);
        assertTrue("Values API 嵌入应成功", valuesResult.isSuccess());

        // Table API 嵌入：构造等价的 table（每个值单列）
        List<Map<String, Object>> table = new ArrayList<>();
        for (Object v : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("v", v);
            table.add(row);
        }
        WatermarkResult<List<Map<String, Object>>> tableResult =
                watermarker.embed(table, Arrays.asList("v"), PAYLOAD);
        assertTrue("Table API 嵌入应成功", tableResult.isSuccess());

        // 比较嵌入结果
        assertEquals("两种 API 的嵌入后序列大小应相同",
                valuesResult.getData().size(), tableResult.getData().size());
        for (int i = 0; i < values.size(); i++) {
            Object fromValues = valuesResult.getData().get(i);
            Object fromTable = tableResult.getData().get(i).get("v");
            assertEquals("索引 " + i + " 的嵌入结果应一致", fromTable, fromValues);
        }
        System.out.println("Values API 与 Table API 嵌入结果完全一致");
    }

    /**
     * 验证：嵌入后用另一种 API 也能正确提取（API 间互通）。
     */
    @Test
    public void testValuesEmbedThenTableExtract() {
        List<Object> values = createTestValues(500);

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        // 把嵌入后的 List<Object> 包装为 List<Map> 用 Table API 提取
        List<Map<String, Object>> table = new ArrayList<>();
        for (Object v : embedResult.getData()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("v", v);
            table.add(row);
        }
        WatermarkResult<String> extractResult = watermarker.extract(table, Arrays.asList("v"));
        assertTrue("Table API 提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", PAYLOAD, extractResult.getData());
    }

    // ==================== 边界条件 ====================

    @Test
    public void testEmbedListNullInput() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> result = watermarker.embed((List<?>) null, PAYLOAD);
        assertFalse("null 输入应失败", result.isSuccess());
        assertTrue("失败信息应包含'空'", result.getMessage().contains("空"));
    }

    @Test
    public void testEmbedListEmptyInput() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> result = watermarker.embed(new ArrayList<Object>(), PAYLOAD);
        assertFalse("空列表应失败", result.isSuccess());
    }

    @Test
    public void testEmbedListNullPayload() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> result = watermarker.embed(createTestValues(100), null);
        assertFalse("null 载荷应失败", result.isSuccess());
    }

    @Test
    public void testExtractListNullInput() {
        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<String> result = watermarker.extract((List<?>) null);
        assertFalse("null 输入应失败", result.isSuccess());
    }

    // ==================== 不同值类型 ====================

    @Test
    public void testEmbedListOfStringsOnly() {
        // 纯字符串场景（非表格常见用法：JSON 数组）
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            values.add("user" + i + "_" + "测试内容");
        }

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, PAYLOAD);
        assertTrue("List<String> 嵌入应成功", embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData());
        assertTrue("List<String> 提取应成功", extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", PAYLOAD, extractResult.getData());
    }

    @Test
    public void testEmbedListOfNumericsOnly() {
        // 纯数值场景
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            values.add(10000.0 + i);
        }

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        // 显式指定 bit-numeric，避免 ChineseText 吞掉所有值；用短载荷以满足容量
        List<WatermarkType> strategies = Arrays.asList(WatermarkType.BIT_NUMERIC_LSB);
        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, SHORT_PAYLOAD, strategies);
        assertTrue("List<Double> 嵌入应成功: " + embedResult.getMessage(), embedResult.isSuccess());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData());
        assertTrue("List<Double> 提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", SHORT_PAYLOAD, extractResult.getData());
    }

    @Test
    public void testEmbedListWithNullValues() {
        // 包含 null 的值序列（跳过 null 是期望行为）
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            if (i % 3 == 0) {
                values.add(null);
            } else {
                values.add("用户" + i);
            }
        }

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, PAYLOAD);
        assertTrue("含 null 的值序列嵌入应成功", embedResult.isSuccess());
        assertEquals("返回序列大小应等于原序列大小", values.size(), embedResult.getData().size());

        WatermarkResult<String> extractResult = watermarker.extract(embedResult.getData());
        assertTrue("提取应成功", extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", PAYLOAD, extractResult.getData());
    }

    // ==================== 鲁棒性 ====================

    @Test
    public void testValuesApiSurvivesPartialDeletion() {
        // 删除 30% 数据后仍能提取（与 Table API 行为一致）
        List<Object> values = createTestValues(500);

        Watermarker watermarker = new Watermarker(new WatermarkConfig(SECRET));
        WatermarkResult<List<Object>> embedResult = watermarker.embed(values, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        List<Object> watermarked = embedResult.getData();
        // 删除后 30% 数据（保留前 70%）
        int keep = (int) (watermarked.size() * 0.7);
        List<Object> truncated = new ArrayList<>(watermarked.subList(0, keep));

        WatermarkResult<String> extractResult = watermarker.extract(truncated);
        assertTrue("删除 30% 数据后提取应成功: " + extractResult.getMessage(),
                extractResult.isSuccess());
        assertEquals("提取载荷应等于原始载荷", PAYLOAD, extractResult.getData());
        System.out.println("Values API 鲁棒性测试通过 | 保留 " + keep + "/" + watermarked.size()
                + " | 置信度: " + extractResult.getConfidence() + "%");
    }

    // ==================== 工具方法 ====================

    /**
     * 构造测试值序列：500 个值，中文 + 拉丁 + 数值混合。
     */
    private static List<Object> createTestValues(int count) {
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 每个值是字符串，包含中文便于走 ChineseText 策略
            values.add("用户" + i + "_test_data");
        }
        return values;
    }
}