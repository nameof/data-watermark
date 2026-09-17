package io.github.nameof.watermark;

import io.github.nameof.watermark.io.CsvDataSource;
import io.github.nameof.watermark.io.TableData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
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

    // ==================== 边界与错误路径 ====================

    @Test
    public void testReadEmptyFileReturnsEmptyTable() throws Exception {
        File empty = new File(TEST_DIR, "empty.csv");
        assertTrue("空文件应创建成功", empty.createNewFile());

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        TableData data = csvDs.readTable("empty");
        csvDs.close();

        assertEquals("空文件应无列", 0, data.getColumnCount());
        assertEquals("空文件应无行", 0, data.getRowCount());
        assertTrue(data.isEmpty());
    }

    @Test
    public void testReadHeaderOnlyFileReturnsNoRows() throws Exception {
        writeRawCsv("header_only.csv", "a,b,c");

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        TableData data = csvDs.readTable("header_only");
        csvDs.close();

        assertEquals("列名应正确解析", Arrays.asList("a", "b", "c"), data.getColumnNames());
        assertEquals("仅表头的文件应无数据行", 0, data.getRowCount());
        assertTrue(data.isEmpty());
    }

    @Test(expected = FileNotFoundException.class)
    public void testReadNonexistentFileThrows() throws Exception {
        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.readTable("no_such_file");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriteNullDataRejected() throws Exception {
        new CsvDataSource(TEST_DIR).writeTable(null, "t");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriteEmptyDataRejected() throws Exception {
        new CsvDataSource(TEST_DIR).writeTable(
                new TableData("t", Arrays.asList("a"), new ArrayList<Map<String, Object>>()), "t");
    }

    @Test
    public void testWhereClauseIgnoredWithAllRowsReturned() throws Exception {
        writeRawCsv("where_test.csv", "id,name\n1,张三\n2,李四\n");

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        // CSV 不支持 WHERE，应忽略条件返回全部数据
        TableData data = csvDs.readTable("where_test", "id > 1");
        csvDs.close();

        assertEquals("WHERE 应被忽略，返回全部 2 行", 2, data.getRowCount());
    }

    @Test
    public void testCsvSuffixNotDoubled() throws Exception {
        List<String> columns = Arrays.asList("v");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", "x");
        rows.add(row);

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(new TableData("t", columns, rows), "weird");

        // 传入已带 .csv 后缀的表名，不应生成 weird.csv.csv
        File expected = new File(TEST_DIR, "weird.csv");
        assertTrue("应生成 weird.csv", expected.exists());

        TableData back = csvDs.readTable("weird.csv");
        csvDs.close();
        assertEquals("带 .csv 后缀的表名应能读回", 1, back.getRowCount());
    }

    // ==================== 解析行为 ====================

    @Test
    public void testNumberParsing() throws Exception {
        writeRawCsv("numbers.csv", "a,b,c,d\n123,4.5,9999999999,007\n");

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        TableData data = csvDs.readTable("numbers");
        csvDs.close();

        Map<String, Object> row = data.getRows().get(0);
        assertTrue("整数应解析为 Integer", row.get("a") instanceof Integer);
        assertEquals(123, row.get("a"));
        assertTrue("小数应解析为 Double", row.get("b") instanceof Double);
        assertEquals(4.5, (Double) row.get("b"), 1e-9);
        assertTrue("超出 int 范围的应解析为 Long", row.get("c") instanceof Long);
        assertEquals(9999999999L, row.get("c"));
        // 已知行为：前导零数字串会丢失前导零（07 → 7），此处固化该行为
        assertEquals(7, row.get("d"));
    }

    @Test
    public void testNonNumericStaysString() throws Exception {
        writeRawCsv("mixed.csv", "v\n用户0\nabc\n2026-01-01\n");

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        TableData data = csvDs.readTable("mixed");
        csvDs.close();

        assertEquals("用户0", data.getRows().get(0).get("v"));
        assertEquals("abc", data.getRows().get(1).get("v"));
        assertEquals("日期串应保持字符串", "2026-01-01", data.getRows().get(2).get("v"));
    }

    @Test
    public void testBlankLinesAndMissingFields() throws Exception {
        // 空行跳过；字段数少于表头时缺失字段补空串
        writeRawCsv("gaps.csv", "a,b\n1,\n\n2\n");

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        TableData data = csvDs.readTable("gaps");
        csvDs.close();

        assertEquals("空行应被跳过", 2, data.getRowCount());
        assertEquals("", data.getRows().get(0).get("b"));
        assertEquals("缺失字段应补空串", "", data.getRows().get(1).get("b"));
        assertEquals(2, data.getRows().get(1).get("a"));
    }

    @Test
    public void testEscapedQuotesRoundTrip() throws Exception {
        List<String> columns = Arrays.asList("text");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("text", "say \"\"hi\"\" loudly");
        rows.add(row);

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(new TableData("q", columns, rows), "quotes");

        TableData back = csvDs.readTable("quotes");
        csvDs.close();

        assertEquals("转义双引号应正确往返",
                "say \"\"hi\"\" loudly", back.getRows().get(0).get("text"));
    }

    @Test
    public void testNullValuesWrittenAsEmpty() throws Exception {
        List<String> columns = Arrays.asList("a", "b");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "x");
        row.put("b", null);
        rows.add(row);

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        csvDs.writeTable(new TableData("n", columns, rows), "nulls");

        TableData back = csvDs.readTable("nulls");
        csvDs.close();

        assertEquals("null 应写为空串并读回空串", "", back.getRows().get(0).get("b"));
        assertEquals("x", back.getRows().get(0).get("a"));
    }

    @Test
    public void testBomSkippedOnRead() throws Exception {
        // 手工构造带 UTF-8 BOM 的文件（模拟 Excel 导出）
        writeRawCsvWithBom("bom.csv", "id,name\n1,张三\n");

        CsvDataSource csvDs = new CsvDataSource(TEST_DIR);
        TableData data = csvDs.readTable("bom");
        csvDs.close();

        assertEquals("BOM 不应混入首列列名",
                "id", data.getColumnNames().get(0));
        assertEquals(1, data.getRowCount());
    }

    // ==================== 辅助方法 ====================

    /** 直接以 UTF-8 写原始 CSV 文本（不带 BOM） */
    private void writeRawCsv(String fileName, String content) throws Exception {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(TEST_DIR, fileName)), "UTF-8")) {
            w.write(content);
        }
    }

    /** 以 UTF-8 + BOM 写原始 CSV 文本 */
    private void writeRawCsvWithBom(String fileName, String content) throws Exception {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(TEST_DIR, fileName)), "UTF-8")) {
            w.write('\uFEFF');
            w.write(content);
        }
    }
}
