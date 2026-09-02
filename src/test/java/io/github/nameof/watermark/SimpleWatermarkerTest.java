package io.github.nameof.watermark;

import cn.hutool.core.img.ImgUtil;
import io.github.nameof.watermark.simple.*;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.*;

import static org.junit.Assert.*;

/**
 * 简单水印策略和 SimpleWatermarker 的单元测试。
 * <p>
 * 测试 SuffixMarker 和 InvisiblePadding 两种策略的嵌入/提取正确性，
 * 以及 SimpleWatermarker 的多数投票机制。
 * </p>
 */
public class SimpleWatermarkerTest {

    private static final String PAYLOAD = "operator:zhangsan|company:ACME";
    private static final String SECRET = "test-secret-key";

    // ==================== SuffixMarkerStrategy 测试 ====================

    @Test
    public void testSuffixMarkerEmbedAndExtract() {
        SuffixMarkerStrategy strategy = new SuffixMarkerStrategy();

        // 嵌入
        assertTrue(strategy.canWatermark("张三"));
        String embedded = strategy.embed("张三", PAYLOAD, SECRET, 0);
        System.out.println("SuffixMarker 嵌入结果: [" + embedded + "]");
        assertTrue("嵌入后应包含标记", embedded.contains("[::" + PAYLOAD + "::]"));

        // 提取
        String extracted = strategy.extract(embedded, SECRET, 0);
        assertEquals("提取的载荷应与原始一致", PAYLOAD, extracted);

        // 无水印时返回 null
        assertNull("原始文本应返回 null", strategy.extract("张三", SECRET, 0));
    }

    @Test
    public void testSuffixMarkerMultipleEmbed() {
        SuffixMarkerStrategy strategy = new SuffixMarkerStrategy();

        // 多次嵌入应覆盖而非叠加
        String first = strategy.embed("张三", "payload1", SECRET, 0);
        String second = strategy.embed(first, "payload2", SECRET, 0);
        System.out.println("多次嵌入结果: [" + second + "]");

        // 应只包含最后一次嵌入的载荷
        assertEquals("payload2", strategy.extract(second, SECRET, 0));
        assertFalse("不应包含第一次的载荷", second.contains("payload1"));
    }

    // ==================== InvisiblePaddingStrategy 测试 ====================

    @Test
    public void testInvisiblePaddingEmbedAndExtract() {
        InvisiblePaddingStrategy strategy = new InvisiblePaddingStrategy();

        // 嵌入
        assertTrue(strategy.canWatermark("张三"));
        String embedded = strategy.embed("张三", PAYLOAD, SECRET, 0);
        System.out.println("InvisiblePadding 嵌入结果长度: " + embedded.length()
                + " (原始: 2, 增加: " + (embedded.length() - 2) + " 零宽字符)");

        // 肉眼看起来一样
        assertTrue("嵌入后应以原始文本开头", embedded.startsWith("张三"));

        // 提取
        String extracted = strategy.extract(embedded, SECRET, 0);
        assertEquals("提取的载荷应与原始一致", PAYLOAD, extracted);

        // 无水印时返回 null
        assertNull("原始文本应返回 null", strategy.extract("张三", SECRET, 0));
    }

    @Test
    public void testInvisiblePaddingMultipleEmbed() {
        InvisiblePaddingStrategy strategy = new InvisiblePaddingStrategy();

        // 多次嵌入应覆盖而非叠加
        String first = strategy.embed("张三", "payload1", SECRET, 0);
        String second = strategy.embed(first, "payload2", SECRET, 0);

        assertEquals("payload2", strategy.extract(second, SECRET, 0));
    }

    @Test
    public void testInvisible() {
        InvisiblePaddingStrategy strategy = new InvisiblePaddingStrategy();

        // 多次嵌入应覆盖而非叠加
        String first = strategy.embed("张三", "我去", SECRET, 0);
        System.out.println(first);
        System.out.println(strategy.extract(first, SECRET, 0));
    }

    // ==================== SimpleWatermarker 多数投票测试 ====================

    @Test
    public void testSimpleWatermarkerEmbedAndExtract() throws FileNotFoundException {
        // 构造测试数据
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            row.put("address", "地址" + i + "号");
            table.add(row);
        }

        WatermarkConfig config = new WatermarkConfig(SECRET);
        SimpleWatermarker watermarker = new SimpleWatermarker(config);
        List<String> columns = Arrays.asList("name", "address");

        // 嵌入
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());
        System.out.println("SimpleWatermarker 嵌入成功，涉及单元格数: " + embedResult.getRepetition());

        // 提取
        WatermarkResult<String> extractResult =
                watermarker.extract(embedResult.getData(), columns);
        assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals("提取的载荷应与原始一致", PAYLOAD, extractResult.getData());
        System.out.println("SimpleWatermarker 提取成功: " + extractResult.getData());

        BufferedImage reportImage = embedResult.getReportImage();
        ImgUtil.write(reportImage, "png", new FileOutputStream("C:\\Users\\chengpan\\Desktop\\report.png"));
    }

    @Test
    public void testSimpleWatermarkerWithPartialDataLoss() {
        // 构造测试数据
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            table.add(row);
        }

        WatermarkConfig config = new WatermarkConfig(SECRET);
        SimpleWatermarker watermarker = new SimpleWatermarker(config);
        List<String> columns = Arrays.asList("name");

        // 嵌入
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(table, columns, PAYLOAD);
        assertTrue("嵌入应成功", embedResult.isSuccess());

        // 模拟数据丢失：删除前 50% 的行
        List<Map<String, Object>> partial = embedResult.getData().subList(10, 20);

        // 提取（多数投票应仍能恢复）
        WatermarkResult<String> extractResult =
                watermarker.extract(partial, columns);
        assertTrue("部分数据丢失后仍应能提取: " + extractResult.getMessage(), extractResult.isSuccess());
        assertEquals(PAYLOAD, extractResult.getData());
        System.out.println("部分数据丢失后提取成功: " + extractResult.getData());
    }
}
