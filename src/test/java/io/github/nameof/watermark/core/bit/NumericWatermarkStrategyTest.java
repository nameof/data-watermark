package io.github.nameof.watermark.core.bit;

import io.github.nameof.watermark.core.WatermarkType;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * NumericWatermarkStrategy（末位微扰）单元测试。
 * <p>
 * 覆盖：type() 标识、canWatermark 边界、bit 0/1 往返（跨多种数值类型）、
 * 类型保持、扰动幅度上界（±1 个最小精度单位）、负数、确定性与提取的 seed 无关性。
 * </p>
 */
public class NumericWatermarkStrategyTest {

    private static final String SECRET = "numeric-test-secret";
    private final NumericWatermarkStrategy strategy = new NumericWatermarkStrategy();

    @Test
    public void testType() {
        assertEquals(WatermarkType.BIT_NUMERIC_LSB, strategy.type());
    }

    @Test
    public void testCanWatermarkBoundaries() {
        assertFalse("null 不可嵌入", strategy.canWatermark(null));
        assertFalse("非数值字符串不可嵌入", strategy.canWatermark("abc"));
        assertFalse("布尔不可嵌入", strategy.canWatermark(true));
        assertTrue("Integer 可嵌入", strategy.canWatermark(42));
        assertTrue("Double 可嵌入", strategy.canWatermark(3.14));
        assertTrue("BigDecimal 可嵌入", strategy.canWatermark(new BigDecimal("99.99")));
        assertTrue("数值字符串可嵌入", strategy.canWatermark("123.45"));
    }

    @Test
    public void testEmbedNonNumericFails() {
        BitEmbedResult r = strategy.embed("abc", 1, SECRET, 0L);
        assertFalse("非数值应嵌入失败", r.isEmbedded());
        assertEquals("失败时应返回原值", "abc", r.getValue());
    }

    @Test
    public void testRoundtripAcrossTypes() {
        Object[] values = {42, 123456789L, 3.14, "123.45", new BigDecimal("99.99")};
        for (Object v : values) {
            for (int bit = 0; bit <= 1; bit++) {
                BitEmbedResult r = strategy.embed(v, bit, SECRET, 9L);
                assertTrue(v + " 嵌入 bit " + bit + " 应成功", r.isEmbedded());
                assertEquals(v + " 提取应还原 bit " + bit, bit, strategy.extract(r.getValue(), SECRET, 9L));
            }
        }
    }

    @Test
    public void testParityInvariant() {
        // 编码规则：bit 0 → 放大后最低位为偶数；bit 1 → 奇数
        Object[][] cases = {{"123.45", 0}, {"123.45", 1}, {"10000.00", 0}, {"10000.00", 1}};
        for (Object[] c : cases) {
            Object out = strategy.embed(c[0], (Integer) c[1], SECRET, 1L).getValue();
            long scaled = Math.round(Double.parseDouble(out.toString()) * 100);
            assertEquals(c[0] + " 嵌入 bit " + c[1] + " 后末位奇偶性应匹配",
                    (int) (Integer) c[1], (int) (Math.abs(scaled) % 2));
        }
    }

    @Test
    public void testTypePreservation() {
        assertTrue("Integer 应保持 Integer",
                strategy.embed(42, 1, SECRET, 0L).getValue() instanceof Integer);
        assertTrue("Long 应保持 Long",
                strategy.embed(123456789L, 1, SECRET, 0L).getValue() instanceof Long);
        assertTrue("Double 应保持数值类型",
                strategy.embed(3.14, 1, SECRET, 0L).getValue() instanceof Number);
        Object s = strategy.embed("123.45", 1, SECRET, 0L).getValue();
        assertTrue("String 数值应保持 String，实际: " + s.getClass(), s instanceof String);
        assertTrue("小数位应保持 2 位，实际: " + s, ((String) s).matches("\\d+\\.\\d{2}"));
        assertTrue("BigDecimal 应保持 BigDecimal",
                strategy.embed(new BigDecimal("99.99"), 1, SECRET, 0L).getValue() instanceof BigDecimal);
    }

    @Test
    public void testPerturbationIsMinimal() {
        double original = 12345.67;
        for (int bit = 0; bit <= 1; bit++) {
            Object out = strategy.embed(original, bit, SECRET, 1L).getValue();
            // 在整数域比较（放大 100 倍），避免 double 表示误差
            long scaledOriginal = Math.round(original * 100);
            long scaledOut = Math.round(((Number) out).doubleValue() * 100);
            assertTrue("扰动不应超过 1 个最小精度单位，实际 " + Math.abs(scaledOut - scaledOriginal),
                    Math.abs(scaledOut - scaledOriginal) <= 1);
        }
    }

    @Test
    public void testNegativeNumbers() {
        for (int bit = 0; bit <= 1; bit++) {
            BitEmbedResult r = strategy.embed(-55, bit, SECRET, 2L);
            assertTrue("负数嵌入 bit " + bit + " 应成功", r.isEmbedded());
            assertEquals("负数提取应还原 bit " + bit, bit, strategy.extract(r.getValue(), SECRET, 2L));
        }
    }

    @Test
    public void testDeterministicAndSeedIndependentExtract() {
        Object a = strategy.embed(777, 1, SECRET, 5L).getValue();
        Object b = strategy.embed(777, 1, SECRET, 5L).getValue();
        assertEquals("相同参数应产生相同结果", a, b);
        // 数值策略的提取基于奇偶性，天然与 seed 无关
        assertEquals(1, strategy.extract(a, SECRET, 12345L));
    }
}
