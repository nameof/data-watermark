package io.github.nameof.watermark.core.simple;

import io.github.nameof.watermark.core.WatermarkType;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Simple 策略接口（SimpleWatermarkStrategy）的通用契约测试。
 * <p>
 * 覆盖两个实现（SuffixMarker、InvisiblePadding）的共同契约：
 * type() 标识、canWatermark 边界、seed 确定性、
 * seed 影响插入位置但不影响提取（扫描式定位，文档化的设计点）、往返正确性。
 * </p>
 */
public class SimpleWatermarkStrategyContractTest {

    private static final String PAYLOAD = "op:zhangsan";
    private static final String SECRET = "simple-contract-secret";

    private final SuffixMarkerStrategy suffix = new SuffixMarkerStrategy();
    private final InvisiblePaddingStrategy padding = new InvisiblePaddingStrategy();

    @Test
    public void testTypes() {
        assertEquals(WatermarkType.SIMPLE_SUFFIX_MARKER, suffix.type());
        assertEquals(WatermarkType.SIMPLE_INVISIBLE_PADDING, padding.type());
    }

    @Test
    public void testCanWatermarkRejectsNullAndBlank() {
        for (SimpleWatermarkStrategy s : Arrays.asList(suffix, padding)) {
            assertFalse(s.getClass().getSimpleName() + " null 不可嵌入", s.canWatermark(null));
            assertFalse(s.getClass().getSimpleName() + " 空串不可嵌入", s.canWatermark(""));
            assertFalse(s.getClass().getSimpleName() + " 空白串不可嵌入", s.canWatermark("   "));
        }
    }

    @Test
    public void testCanWatermarkRejectsNonString() {
        for (SimpleWatermarkStrategy s : Arrays.asList(suffix, padding)) {
            assertFalse(s.getClass().getSimpleName() + " 数值不可嵌入", s.canWatermark(123));
            assertFalse(s.getClass().getSimpleName() + " 浮点不可嵌入", s.canWatermark(3.14));
            assertFalse(s.getClass().getSimpleName() + " 布尔不可嵌入", s.canWatermark(true));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmbedRejectsNonString() {
        suffix.embed(123, PAYLOAD, SECRET, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPaddingEmbedRejectsNonString() {
        padding.embed(123, PAYLOAD, SECRET, 0L);
    }

    @Test
    public void testEmbedIsDeterministic() {
        for (SimpleWatermarkStrategy s : Arrays.asList(suffix, padding)) {
            String a = s.embed("北京市朝阳区", PAYLOAD, SECRET, 42L);
            String b = s.embed("北京市朝阳区", PAYLOAD, SECRET, 42L);
            assertEquals(s.getClass().getSimpleName() + " 相同 seed 应产生相同结果", a, b);
        }
    }

    @Test
    public void testSuffixMarkerSeedChangesPosition() {
        Set<String> outputs = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            outputs.add(suffix.embed("上海市黄浦区南京东路", PAYLOAD, SECRET, seed));
        }
        assertTrue("不同 seed 应产生不同的插入位置", outputs.size() > 1);
    }

    @Test
    public void testExtractIsSeedIndependent() {
        for (SimpleWatermarkStrategy s : Arrays.asList(suffix, padding)) {
            String embedded = s.embed("广州市越秀区中山五路", PAYLOAD, SECRET, 7L);
            assertEquals(s.getClass().getSimpleName() + " 提取不依赖 seed（扫描式定位）",
                    PAYLOAD, s.extract(embedded, SECRET, 4242L));
        }
    }

    @Test
    public void testRoundtrip() {
        for (SimpleWatermarkStrategy s : Arrays.asList(suffix, padding)) {
            String embedded = s.embed("杭州市滨江区江南大道", PAYLOAD, SECRET, 3L);
            assertEquals(s.getClass().getSimpleName() + " 往返应还原载荷",
                    PAYLOAD, s.extract(embedded, SECRET, 3L));
        }
    }
}
