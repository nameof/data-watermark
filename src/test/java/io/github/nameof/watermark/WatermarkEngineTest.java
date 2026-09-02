package io.github.nameof.watermark;

import io.github.nameof.watermark.io.CsvDataSource;
import io.github.nameof.watermark.io.TableData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;

/**
 * WatermarkEngine 端到端测试。
 * <p>
 * 测试 Engine 层级的完整水印流程：
 * 读取数据 → 嵌入水印 → 写入 CSV → 读回 → 提取水印。
 * </p>
 */
public class WatermarkEngineTest {

    private static final String PAYLOAD = "operator:zhangsan|company:ACME";
    private static final String SECRET = "engine-test-secret";
    private static final String TEST_DIR = System.getProperty("java.io.tmpdir")
            + File.separator + "watermark-engine-test";

    @Before
    public void setUp() {
        new File(TEST_DIR).mkdirs();
    }

    @After
    public void tearDown() {
        File dir = new File(TEST_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void testBitLevelWithCsv() throws Exception {
        // 准备 CSV 数据源
        List<String> columns = Arrays.asList("name", "address", "salary");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            row.put("address", "城市" + (i % 10) + "区街道" + i + "号");
            row.put("salary", String.format("%d.00", 10000 + i * 100));
            rows.add(row);
        }
        TableData data = new TableData("source", columns, rows);
        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(data, "source");
        csvDs.close();

        // 使用 Engine 进行水印操作
        WatermarkConfig config = new WatermarkConfig(SECRET);
        CsvDataSource sourceDs = new CsvDataSource(TEST_DIR);

        try (WatermarkEngine engine = new WatermarkEngine(sourceDs, config)) {
            // 嵌入到 CSV
            WatermarkResult<TableData> embedResult = engine.embedToCsv(
                    "source", Arrays.asList("name", "address", "salary"),
                    PAYLOAD, TEST_DIR + File.separator + "watermarked.csv");

            assertTrue("嵌入应成功", embedResult.isSuccess());
            System.out.println("Engine bit-level 嵌入成功，涉及列: " + embedResult.getWatermarkedColumns());

            // 从 CSV 提取
            WatermarkResult<String> extractResult = engine.extractFromCsv(
                    TEST_DIR + File.separator + "watermarked.csv",
                    Arrays.asList("name", "address", "salary"));

            assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
            assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
            System.out.println("Engine bit-level 提取成功: " + extractResult.getData());
        }
    }

    @Test
    public void testSimpleModeWithCsv() throws Exception {
        // 准备 CSV 数据源
        List<String> columns = Arrays.asList("name", "description");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "商品" + i);
            row.put("description", "这是第" + i + "个商品的描述信息");
            rows.add(row);
        }
        TableData data = new TableData("products", columns, rows);
        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(data, "products");
        csvDs.close();

        // 使用简单模式
        WatermarkConfig config = new WatermarkConfig(SECRET);
        config.setUseBitLevel(false);
        CsvDataSource sourceDs = new CsvDataSource(TEST_DIR);

        try (WatermarkEngine engine = new WatermarkEngine(sourceDs, config)) {
            // 嵌入到 CSV
            WatermarkResult<TableData> embedResult = engine.embedToCsv(
                    "products", Arrays.asList("name", "description"),
                    PAYLOAD, TEST_DIR + File.separator + "products_wm.csv");

            assertTrue("嵌入应成功", embedResult.isSuccess());
            System.out.println("Engine simple 嵌入成功");

            // 从 CSV 提取
            WatermarkResult<String> extractResult = engine.extractFromCsv(
                    TEST_DIR + File.separator + "products_wm.csv",
                    Arrays.asList("name", "description"));

            assertTrue("提取应成功: " + extractResult.getMessage(), extractResult.isSuccess());
            assertEquals("载荷应一致", PAYLOAD, extractResult.getData());
            System.out.println("Engine simple 提取成功: " + extractResult.getData());
        }
    }

    @Test
    public void testSupportsWatermark() throws Exception {
        // 准备包含可嵌入数据的 CSV
        List<String> columns = Arrays.asList("name", "value");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("name", "张三");
        row1.put("value", "100.50");
        rows.add(row1);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("name", "John");
        row2.put("value", "200.75");
        rows.add(row2);

        TableData data = new TableData("test", columns, rows);
        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(data, "test");
        csvDs.close();

        WatermarkConfig config = new WatermarkConfig(SECRET);
        CsvDataSource sourceDs = new CsvDataSource(TEST_DIR);

        try (WatermarkEngine engine = new WatermarkEngine(sourceDs, config)) {
            boolean supported = engine.supportsWatermark("test", Arrays.asList("name", "value"));
            assertTrue("应该支持水印嵌入", supported);
            System.out.println("supportsWatermark 测试通过");
        }
    }
}
