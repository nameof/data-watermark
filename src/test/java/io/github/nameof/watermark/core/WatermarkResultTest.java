package io.github.nameof.watermark.core;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * WatermarkResult 与 WatermarkType 单元测试。
 * <p>
 * 覆盖 4 个工厂方法、链式 setter、派生属性（watermarkMode）与 toString。
 * </p>
 */
public class WatermarkResultTest {

    @Test
    public void testEmbedSuccessFactory() {
        List<Map<String, Object>> table = new ArrayList<>();
        List<String> columns = Arrays.asList("name");

        WatermarkResult<List<Map<String, Object>>> r =
                WatermarkResult.embedSuccess(table, columns, 7);

        assertTrue(r.isSuccess());
        assertSame(table, r.getData());
        assertEquals(columns, r.getWatermarkedColumns());
        assertEquals(7, r.getRepetition());
        assertNull("成功结果 message 应为 null", r.getMessage());
    }

    @Test
    public void testExtractSuccessFactory() {
        WatermarkResult<String> r = WatermarkResult.extractSuccess("payload", Arrays.asList("c"), 3);

        assertTrue(r.isSuccess());
        assertEquals("payload", r.getData());
        assertEquals(3, r.getRepetition());
        assertEquals(Collections.singletonList("c"), r.getWatermarkedColumns());
    }

    @Test
    public void testGenericSuccessFactory() {
        WatermarkResult<Integer> r = WatermarkResult.success(42, null, 1);

        assertTrue(r.isSuccess());
        assertEquals(Integer.valueOf(42), r.getData());
        assertEquals("null 列名列表应退化为空列表", Collections.emptyList(), r.getWatermarkedColumns());
    }

    @Test
    public void testFailureFactory() {
        WatermarkResult<String> r = WatermarkResult.failure("出错了");

        assertFalse(r.isSuccess());
        assertEquals("出错了", r.getMessage());
        assertNull(r.getData());
        assertEquals("失败结果列名应为空列表", Collections.emptyList(), r.getWatermarkedColumns());
        assertEquals(0, r.getRepetition());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testWatermarkedColumnsUnmodifiable() {
        WatermarkResult<String> r = WatermarkResult.extractSuccess("p", new ArrayList<>(Arrays.asList("a")), 1);
        r.getWatermarkedColumns().add("b");
    }

    @Test
    public void testFluentSetters() {
        WatermarkResult<String> r = WatermarkResult.extractSuccess("p", null, 1);

        // 链式 setter 应返回自身
        assertSame(r, r.setWatermarkType(WatermarkType.SIMPLE_INVISIBLE_PADDING));
        assertSame(r, r.setTotalCells(100));
        assertSame(r, r.setValidExtractions(80));
        assertSame(r, r.setConfidence(87.5));

        assertEquals(WatermarkType.SIMPLE_INVISIBLE_PADDING, r.getWatermarkType());
        assertEquals(100, r.getTotalCells());
        assertEquals(80, r.getValidExtractions());
        assertEquals(87.5, r.getConfidence(), 1e-9);
    }

    @Test
    public void testDefaultMetadataValues() {
        WatermarkResult<String> r = WatermarkResult.extractSuccess("p", null, 1);
        assertNull("默认 watermarkType 为 null", r.getWatermarkType());
        assertEquals(-1, r.getTotalCells());
        assertEquals(-1, r.getValidExtractions());
        assertEquals("未计算置信度应为 -1", -1.0, r.getConfidence(), 1e-9);
    }

    @Test
    public void testWatermarkModeDerivation() {
        // 未设置类型 → unknown
        WatermarkResult<String> r = WatermarkResult.extractSuccess("p", null, 1);
        assertEquals("unknown", r.getWatermarkMode());

        // bit-level 类型 → "bit-level"
        r.setWatermarkType(WatermarkType.BIT_CHINESE_ZERO_WIDTH);
        assertEquals("bit-level", r.getWatermarkMode());
        r.setWatermarkType(WatermarkType.BIT_NUMERIC_LSB);
        assertEquals("bit-level", r.getWatermarkMode());

        // simple 类型 → "simple"
        r.setWatermarkType(WatermarkType.SIMPLE_SUFFIX_MARKER);
        assertEquals("simple", r.getWatermarkMode());
    }

    @Test
    public void testToString() {
        WatermarkResult<String> ok = WatermarkResult.extractSuccess("p", Arrays.asList("c"), 2);
        ok.setWatermarkType(WatermarkType.BIT_LATIN_HOMOGLYPH);
        String s = ok.toString();
        assertTrue(s.contains("success=true"));
        assertTrue(s.contains("p"));
        assertTrue(s.contains("BIT_LATIN_HOMOGLYPH"));

        WatermarkResult<String> fail = WatermarkResult.failure("bad input");
        String fs = fail.toString();
        assertTrue(fs.contains("success=false"));
        assertTrue(fs.contains("bad input"));
    }

    // ==================== WatermarkType 枚举测试 ====================

    @Test
    public void testWatermarkTypeModes() {
        for (WatermarkType t : WatermarkType.values()) {
            assertNotNull("每个类型都应关联 Mode", t.getMode());
            assertEquals("isBitLevel 应与 Mode 一致: " + t,
                    t.getMode() == WatermarkType.Mode.BIT_LEVEL, t.isBitLevel());
        }
        assertEquals(WatermarkType.Mode.BIT_LEVEL, WatermarkType.BIT_CHINESE_ZERO_WIDTH.getMode());
        assertEquals(WatermarkType.Mode.BIT_LEVEL, WatermarkType.BIT_LATIN_HOMOGLYPH.getMode());
        assertEquals(WatermarkType.Mode.BIT_LEVEL, WatermarkType.BIT_NUMERIC_LSB.getMode());
        assertEquals(WatermarkType.Mode.SIMPLE, WatermarkType.SIMPLE_SUFFIX_MARKER.getMode());
        assertEquals(WatermarkType.Mode.SIMPLE, WatermarkType.SIMPLE_INVISIBLE_PADDING.getMode());
    }
}
