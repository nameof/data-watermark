package io.github.nameof.watermark.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * WatermarkConfig 参数校验与默认值测试。
 */
public class WatermarkConfigTest {

    private static final String SECRET = "unit-test-secret";

    @Test
    public void testSingleArgConstructorDefaults() {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        assertEquals(SECRET, config.getSecret());
        assertEquals("默认 minRepetition=5", 5, config.getMinRepetition());
        assertEquals("默认 chunkSize=2000", 2000, config.getChunkSize());
    }

    @Test
    public void testTwoArgConstructor() {
        WatermarkConfig config = new WatermarkConfig(SECRET, 10);
        assertEquals(10, config.getMinRepetition());
        assertEquals("chunkSize 应保持默认", 2000, config.getChunkSize());
    }

    @Test
    public void testThreeArgConstructor() {
        WatermarkConfig config = new WatermarkConfig(SECRET, 7, 500);
        assertEquals(SECRET, config.getSecret());
        assertEquals(7, config.getMinRepetition());
        assertEquals(500, config.getChunkSize());
    }

    @Test
    public void testBoundaryValuesAccepted() {
        // 边界值应被接受：minRepetition=3, chunkSize=1
        WatermarkConfig config = new WatermarkConfig(SECRET, 3, 1);
        assertEquals(3, config.getMinRepetition());
        assertEquals(1, config.getChunkSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullSecretRejected() {
        new WatermarkConfig(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptySecretRejected() {
        new WatermarkConfig("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMinRepetitionBelowMinimumRejected() {
        new WatermarkConfig(SECRET, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroChunkSizeRejected() {
        new WatermarkConfig(SECRET, 5, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeChunkSizeRejected() {
        new WatermarkConfig(SECRET, 5, -100);
    }
}
