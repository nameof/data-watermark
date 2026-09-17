package io.github.nameof.watermark.core.bit;

import io.github.nameof.watermark.core.WatermarkType;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ChineseTextWatermarkStrategy（零宽字符）单元测试。
 * <p>
 * 覆盖 seed 化接口的核心契约：type() 标识、canWatermark 边界、
 * embed/extract 往返、确定性、seed 影响嵌入位置但不影响提取（扫描式）、
 * 重复嵌入覆盖旧水印。
 * </p>
 */
public class ChineseTextWatermarkStrategyTest {

    private static final String SECRET = "chinese-test-secret";
    private final ChineseTextWatermarkStrategy strategy = new ChineseTextWatermarkStrategy();

    @Test
    public void testType() {
        assertEquals(WatermarkType.BIT_CHINESE_ZERO_WIDTH, strategy.type());
    }

    @Test
    public void testCanWatermarkBoundaries() {
        assertFalse("null 不可嵌入", strategy.canWatermark(null));
        assertFalse("单字符太短", strategy.canWatermark("张"));
        assertFalse("去除零宽字符后太短", strategy.canWatermark("张\u200B"));
        assertFalse("非字符串（数值）不可嵌入", strategy.canWatermark(123));
        assertFalse("非字符串（浮点）不可嵌入", strategy.canWatermark(3.14));
        assertFalse("非字符串（布尔）不可嵌入", strategy.canWatermark(true));
        assertTrue("两个字符可嵌入", strategy.canWatermark("张三"));
        assertTrue("已含水印的文本仍可嵌入", strategy.canWatermark("张三丰\u200C"));
    }

    @Test
    public void testEmbedNonStringFails() {
        BitEmbedResult r = strategy.embed(123, 1, SECRET, 0L);
        assertFalse("非字符串应嵌入失败", r.isEmbedded());
        assertEquals("失败时应返回原值", 123, r.getValue());
    }

    @Test
    public void testEmbedExtractRoundtrip() {
        for (int bit = 0; bit <= 1; bit++) {
            BitEmbedResult r = strategy.embed("北京市海淀区中关村", bit, SECRET, 42L);
            assertTrue("嵌入 bit " + bit + " 应成功", r.isEmbedded());
            assertEquals("提取应还原嵌入的 bit " + bit, bit, strategy.extract(r.getValue(), SECRET, 42L));
        }
    }

    @Test
    public void testEmbedIsDeterministic() {
        Object a = strategy.embed("上海市浦东新区", 1, SECRET, 7L).getValue();
        Object b = strategy.embed("上海市浦东新区", 1, SECRET, 7L).getValue();
        assertEquals("相同 (value, bit, secret, seed) 应产生相同结果", a, b);
    }

    @Test
    public void testDifferentSeedsProduceDifferentPositions() {
        Set<Object> outputs = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            outputs.add(strategy.embed("广州市天河区体育西路", 1, SECRET, seed).getValue());
        }
        assertTrue("不同 seed 应产生不同的嵌入位置", outputs.size() > 1);
    }

    @Test
    public void testExtractIsSeedIndependent() {
        Object embedded = strategy.embed("深圳市南山区科技园", 1, SECRET, 100L).getValue();
        assertEquals("提取是扫描式的，不应依赖 seed", 1, strategy.extract(embedded, SECRET, 999L));
    }

    @Test
    public void testEmbedTooShortValueFails() {
        BitEmbedResult r = strategy.embed("张", 1, SECRET, 0L);
        assertFalse("过短的值应嵌入失败", r.isEmbedded());
        assertEquals("失败时应返回原值", "张", r.getValue());
    }

    @Test
    public void testReEmbedOverwritesPreviousWatermark() {
        Object first = strategy.embed("杭州市西湖区文三路", 1, SECRET, 5L).getValue();
        Object second = strategy.embed(first, 0, SECRET, 5L).getValue();
        assertEquals("重新嵌入应覆盖旧水印", 0, strategy.extract(second, SECRET, 5L));
        assertEquals("嵌入后长度应为原文 + 1 个零宽字符",
                "杭州市西湖区文三路".length() + 1, ((String) second).length());
    }

    @Test
    public void testExtractWithoutWatermark() {
        assertEquals("null 应返回 -1", -1, strategy.extract(null, SECRET, 0L));
        assertEquals("无水印文本应返回 -1", -1, strategy.extract("苏州市姑苏区平江路", SECRET, 0L));
    }

    @Test
    public void testVisibleTextUnchanged() {
        String original = "南京市玄武区中山陵";
        String embedded = (String) strategy.embed(original, 1, SECRET, 3L).getValue();
        // 去除零宽字符后应与原文完全一致（视觉不可区分）
        String cleaned = embedded.replaceAll("[\\u200B-\\u200F]", "");
        assertEquals("去除零宽字符后应与原文一致", original, cleaned);
    }
}
