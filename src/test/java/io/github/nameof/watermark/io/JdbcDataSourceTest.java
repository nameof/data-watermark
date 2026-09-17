package io.github.nameof.watermark.io;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * JdbcDataSource 单元测试（基于 H2 内存数据库，MySQL 兼容模式）。
 * <p>
 * 覆盖读写往返、WHERE 过滤、建表复制、列类型推断、连接生命周期管理。
 * 使用 H2 验证 SQL 的可移植性：MySQL 专属子句（ENGINE/CHARSET/LIKE）
 * 只应在 MySQL 连接上使用。
 * </p>
 */
public class JdbcDataSourceTest {

    /** 每个测试方法使用独立的内存库，避免相互干扰 */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private Connection connection;
    private JdbcDataSource ds;

    @Before
    public void setUp() throws Exception {
        String url = "jdbc:h2:mem:wm_jdbc_test_" + DB_SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        ds = new JdbcDataSource(connection);
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // ==================== 读写往返 ====================

    @Test
    public void testWriteAndReadRoundTrip() throws Exception {
        List<String> columns = Arrays.asList("id", "name", "salary", "active", "bonus");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("name", "用户" + i);
            row.put("salary", 10000.0 + i);
            row.put("active", i % 2 == 0);
            row.put("bonus", new BigDecimal("12.5" + i));
            rows.add(row);
        }
        ds.writeTable(new TableData("emp", columns, rows), "emp_out");

        TableData back = ds.readTable("emp_out");

        assertEquals("emp_out", back.getTableName());
        assertEquals(columns, back.getColumnNames());
        assertEquals(5, back.getRowCount());

        Map<String, Object> first = back.getRows().get(0);
        assertEquals(1, first.get("id"));
        assertEquals("用户1", first.get("name"));
        assertEquals(10001.0, (Double) first.get("salary"), 1e-9);
        assertEquals(Boolean.FALSE, first.get("active"));
        assertEquals(new BigDecimal("12.51"), new BigDecimal(first.get("bonus").toString()));
    }

    @Test
    public void testZeroWidthCharsSurviveRoundTrip() throws Exception {
        // 零宽字符是中文水印的载体，必须原样存取
        String watermarked = "张三\u200B丰\u200C";
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", watermarked);
        rows.add(row);

        ds.writeTable(new TableData("zw", Arrays.asList("name"), rows), "zw_out");
        TableData back = ds.readTable("zw_out");

        assertEquals("零宽字符应原样往返",
                watermarked, back.getRows().get(0).get("name"));
    }

    @Test
    public void testTimestampRoundTrip() throws Exception {
        Timestamp ts = Timestamp.valueOf("2026-01-15 10:30:00");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("created", ts);
        rows.add(row);

        ds.writeTable(new TableData("ts", Arrays.asList("created"), rows), "ts_out");
        TableData back = ds.readTable("ts_out");

        Object value = back.getRows().get(0).get("created");
        assertNotNull("时间戳应能写入读出", value);
        assertEquals(Timestamp.class, value.getClass());
        assertEquals(ts, value);
    }

    // ==================== WHERE 过滤 ====================

    @Test
    public void testReadTableWithWhereClause() throws Exception {
        writeSimpleEmployeeTable("emp");

        TableData filtered = ds.readTable("emp", "id > 2");
        assertEquals("WHERE id > 2 应只返回 3 行", 3, filtered.getRowCount());
        for (Map<String, Object> row : filtered.getRows()) {
            assertTrue("过滤后的行 id 应大于 2", ((Number) row.get("id")).intValue() > 2);
        }
    }

    @Test
    public void testReadTableWithBlankWhereClauseReturnsAll() throws Exception {
        writeSimpleEmployeeTable("emp");

        // 空白 WHERE 子句应被忽略
        assertEquals(5, ds.readTable("emp", "   ").getRowCount());
        assertEquals(5, ds.readTable("emp", null).getRowCount());
    }

    // ==================== 建表复制 ====================

    @Test
    public void testCreateTableLike() throws Exception {
        writeSimpleEmployeeTable("emp_src");

        ds.createTableLike("emp_src", "emp_copy");

        TableData copy = ds.readTable("emp_copy");
        assertEquals("新表列结构应与源表一致",
                ds.readTable("emp_src").getColumnNames(), copy.getColumnNames());
        assertEquals("新表不应包含数据", 0, copy.getRowCount());
    }

    // ==================== 输入校验 ====================

    @Test(expected = IllegalArgumentException.class)
    public void testWriteTableRejectsNullData() throws Exception {
        ds.writeTable(null, "t");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriteTableRejectsEmptyData() throws Exception {
        ds.writeTable(new TableData("t", Arrays.asList("a"),
                new ArrayList<Map<String, Object>>()), "t");
    }

    // ==================== 列类型推断 ====================

    @Test
    public void testTypeInferenceSkipsNullFirstRow() throws Exception {
        // 回归：类型推断曾只看第一行，首行为 null 时整列退化为 VARCHAR
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("amount", null);
        rows.add(row1);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("amount", 12345);
        rows.add(row2);

        ds.writeTable(new TableData("t", Arrays.asList("amount"), rows), "inferred_int");

        TableData back = ds.readTable("inferred_int");
        // 第 0 行的 null 是原始数据；关键是第 1 行的数值没有被 VARCHAR 列转成字符串
        assertNull("null 值应保持为 null", back.getRows().get(0).get("amount"));
        Object value = back.getRows().get(1).get("amount");
        assertTrue("首行为 null 时应跳过并用后续行推断为数值列，实际: "
                        + (value == null ? "null" : value.getClass()),
                value instanceof Integer);
        assertEquals(12345, value);
    }

    @Test
    public void testInferredColumnTypes() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c_int", 42);
        row.put("c_long", 9999999999L);
        row.put("c_double", 3.14);
        row.put("c_bool", true);
        row.put("c_text", "hello");
        rows.add(row);

        ds.writeTable(new TableData("t", new ArrayList<>(row.keySet()), rows), "types_out");
        TableData back = ds.readTable("types_out");

        Map<String, Object> out = back.getRows().get(0);
        assertTrue("INT 列应读回 Integer", out.get("c_int") instanceof Integer);
        assertTrue("BIGINT 列应读回 Long: " + out.get("c_long").getClass(),
                out.get("c_long") instanceof Long);
        assertTrue("DOUBLE 列应读回 Double", out.get("c_double") instanceof Double);
        assertTrue("BOOLEAN 列应读回 Boolean: " + out.get("c_bool").getClass(),
                out.get("c_bool") instanceof Boolean);
        assertTrue("文本列应读回 String", out.get("c_text") instanceof String);
    }

    @Test
    public void testHighScaleDecimalColumnCreated() throws Exception {
        // 回归：scale > precision 的 BigDecimal 曾生成非法 DECIMAL(10,12)
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("micro", new BigDecimal("0.000000000001"));
        rows.add(row);

        ds.writeTable(new TableData("t", Arrays.asList("micro"), rows), "micro_out");

        TableData back = ds.readTable("micro_out");
        assertEquals(0, new BigDecimal("0.000000000001")
                .compareTo(new BigDecimal(back.getRows().get(0).get("micro").toString())));
    }

    @Test
    public void testAllNullColumnFallsBackToVarchar() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nothing", null);
        rows.add(row);

        ds.writeTable(new TableData("t", Arrays.asList("nothing"), rows), "nulls_out");

        TableData back = ds.readTable("nulls_out");
        assertEquals(1, back.getRowCount());
        assertNull(back.getRows().get(0).get("nothing"));
    }

    // ==================== 连接生命周期 ====================

    @Test
    public void testOwnConnectionClosedByClose() throws Exception {
        String url = "jdbc:h2:mem:wm_own_conn_" + DB_SEQ.incrementAndGet() + ";MODE=MySQL";
        JdbcDataSource own = new JdbcDataSource(url, "sa", "");
        Connection c = own.getConnection();
        assertFalse("创建后连接应可用", c.isClosed());

        own.close();
        assertTrue("URL 构造的连接应由 close() 关闭", c.isClosed());
    }

    @Test
    public void testExternalConnectionNotClosedByClose() throws Exception {
        ds.close();
        assertFalse("外部传入的连接不应被 close() 关闭", connection.isClosed());
    }

    // ==================== 辅助方法 ====================

    private void writeSimpleEmployeeTable(String tableName) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("name", "用户" + i);
            row.put("salary", 10000 + i);
            rows.add(row);
        }
        ds.writeTable(new TableData(tableName, Arrays.asList("id", "name", "salary"), rows), tableName);
    }
}
