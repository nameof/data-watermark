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
 * CSV 数据源读写测试。
 */
public class CsvDataSourceTest {

    private static final String TEST_DIR = System.getProperty("java.io.tmpdir")
            + File.separator + "watermark-csv-test";

    @Before
    public void setUp() {
        new File(TEST_DIR).mkdirs();
    }

    @After
    public void tearDown() {
        // 清理测试文件
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
    public void testWriteAndRead() throws Exception {
        // 准备数据
        List<String> columns = Arrays.asList("id", "name", "salary");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("name", "用户" + i);
            row.put("salary", 10000.0 + i * 1000);
            rows.add(row);
        }
        TableData data = new TableData("test_table", columns, rows);

        // 写入 CSV
        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(data, "test_output");
        csvDs.close();

        // 验证文件存在
        File csvFile = new File(TEST_DIR, "test_output.csv");
        assertTrue("CSV 文件应已创建", csvFile.exists());

        // 读回 CSV
        CsvDataSource readDs = new CsvDataSource(TEST_DIR);
        TableData readData = readDs.readTable("test_output");
        readDs.close();

        // 验证
        assertEquals("表名应一致", "test_output", readData.getTableName());
        assertEquals("列数应一致", 3, readData.getColumnCount());
        assertEquals("行数应一致", 5, readData.getRowCount());

        // 验证数据内容
        assertEquals("第1行 id", 1, readData.getRows().get(0).get("id"));
        assertEquals("第1行 name", "用户1", readData.getRows().get(0).get("name"));
        assertEquals("第3行 salary", 13000.0, readData.getRows().get(2).get("salary"));

        System.out.println("CSV 读写测试通过！");
    }

    @Test
    public void testCreateTableLike() throws Exception {
        // 先写入一个带数据的 CSV
        List<String> columns = Arrays.asList("col_a", "col_b");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("col_a", "value1");
        row.put("col_b", "value2");
        rows.add(row);
        TableData data = new TableData("source", columns, rows);

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(data, "source");

        // createTableLike 应只复制表头
        csvDs.createTableLike("source", "target");
        TableData target = csvDs.readTable("target");
        csvDs.close();

        assertEquals("列名应一致", columns, target.getColumnNames());
        assertEquals("目标表应为空", 0, target.getRowCount());

        System.out.println("CSV createTableLike 测试通过！");
    }

    @Test
    public void testSpecialCharacters() throws Exception {
        // 测试包含逗号、引号的特殊字符
        List<String> columns = Arrays.asList("text");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("text", "包含,逗号的内容");
        rows.add(row1);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("text", "包含\"引号的内容");
        rows.add(row2);
        TableData data = new TableData("special", columns, rows);

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(data, "special");

        // 读回验证
        TableData readData = csvDs.readTable("special");
        csvDs.close();

        assertEquals("行数应一致", 2, readData.getRowCount());
        assertEquals("逗号内容应正确还原", "包含,逗号的内容", readData.getRows().get(0).get("text"));
        assertEquals("引号内容应正确还原", "包含\"引号的内容", readData.getRows().get(1).get("text"));

        System.out.println("CSV 特殊字符测试通过！");
    }
}
