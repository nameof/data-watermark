package io.github.nameof.watermark.core.bit;

import io.github.nameof.watermark.core.WatermarkType;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * LatinTextWatermarkStrategy（同形字替换）单元测试。
 * <p>
 * 重点回归：embed 必须区分 bit 0 / bit 1 ——
 * bit 1 = 引入同形字，bit 0 = 保证无同形字（还原已有同形字）。
 * （历史上 embed 曾忽略 bit 参数，无论嵌入什么都替换为同形字，
 * 导致嵌入 bit 0 后提取得到 1。）
 * </p>
 */
public class LatinTextWatermarkStrategyTest {

    private static final String SECRET = "latin-test-secret";
    private final LatinTextWatermarkStrategy strategy = new LatinTextWatermarkStrategy();

    @Test
    public void testType() {
        assertEquals(WatermarkType.BIT_LATIN_HOMOGLYPH, strategy.type());
    }

    @Test
    public void testCanWatermarkBoundaries() {
        assertFalse("null 不可嵌入", strategy.canWatermark(null));
        assertFalse("长度不足 3", strategy.canWatermark("ab"));
        assertFalse("无可替换字符", strategy.canWatermark("123"));
        assertFalse("纯中文", strategy.canWatermark("张三丰"));
        assertFalse("非字符串（数值）不可嵌入", strategy.canWatermark(123));
        assertTrue("含可替换拉丁字符", strategy.canWatermark("abc"));
        assertTrue(strategy.canWatermark("John Smith"));
    }

    @Test
    public void testEmbedNonStringFails() {
        BitEmbedResult r = strategy.embed(123, 1, SECRET, 0L);
        assertFalse("非字符串应嵌入失败", r.isEmbedded());
        assertEquals("失败时应返回原值", 123, r.getValue());
    }

    @Test
    public void testEmbedBitOneAndExtract() {
        BitEmbedResult r = strategy.embed("coffee shop", 1, SECRET, 11L);
        assertTrue("嵌入 bit 1 应成功", r.isEmbedded());
        assertNotEquals("bit 1 嵌入应修改值", "coffee shop", r.getValue());
        assertEquals("提取应还原 bit 1", 1, strategy.extract(r.getValue(), SECRET, 11L));
        assertEquals("同形字是替换而非插入，长度不应变化",
                "coffee shop".length(), ((String) r.getValue()).length());
    }

    @Test
    public void testEmbedBitZeroKeepsAscii() {
        BitEmbedResult r = strategy.embed("coffee shop", 0, SECRET, 11L);
        assertTrue("嵌入 bit 0 应成功", r.isEmbedded());
        assertEquals("bit 0 不应引入同形字，提取应为 0", 0, strategy.extract(r.getValue(), SECRET, 11L));
        assertEquals("干净文本嵌入 bit 0 应保持原值", "coffee shop", r.getValue());
    }

    @Test
    public void testEmbedBitZeroNormalizesExistingHomoglyph() {
        Object one = strategy.embed("coffee shop", 1, SECRET, 11L).getValue();
        Object zero = strategy.embed(one, 0, SECRET, 11L).getValue();
        assertEquals("bit 0 应还原已有同形字", 0, strategy.extract(zero, SECRET, 11L));
    }

    @Test
    public void testEmbedIsDeterministic() {
        Object a = strategy.embed("example content", 1, SECRET, 5L).getValue();
        Object b = strategy.embed("example content", 1, SECRET, 5L).getValue();
        assertEquals("相同 (value, bit, secret, seed) 应产生相同结果", a, b);
    }

    @Test
    public void testDifferentSeedsSelectDifferentPositions() {
        Set<Object> outputs = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            outputs.add(strategy.embed("peaceful capital", 1, SECRET, seed).getValue());
        }
        assertTrue("不同 seed 应选择不同的替换位置", outputs.size() > 1);
    }

    @Test
    public void testExtractIsSeedIndependent() {
        Object embedded = strategy.embed("example content", 1, SECRET, 1L).getValue();
        assertEquals("提取是扫描式的，不应依赖 seed", 1, strategy.extract(embedded, SECRET, 88L));
    }

    @Test
    public void testExtractUnmodifiedValues() {
        assertEquals("无同形字的可嵌入文本 → bit 0", 0, strategy.extract("plain text", SECRET, 0L));
        assertEquals("不可嵌入的文本 → -1", -1, strategy.extract("1234", SECRET, 0L));
        assertEquals("null → -1", -1, strategy.extract(null, SECRET, 0L));
    }

    @Test
    public void testEmbedNoReplaceableCharFails() {
        BitEmbedResult r = strategy.embed("1234", 1, SECRET, 0L);
        assertFalse("无可替换字符应嵌入失败", r.isEmbedded());
        assertEquals("失败时应返回原值", "1234", r.getValue());
    }
}
