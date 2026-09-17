package io.github.nameof.watermark.io;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * TableData DTO 单元测试。
 * <p>
 * 重点：防御性拷贝（构造后与源集合解耦）、不可变视图、null 容忍、计数与查询方法。
 * </p>
 */
public class TableDataTest {

    @Test
    public void testConstructorAndGetters() {
        List<String> columns = Arrays.asList("id", "name");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "张三");
        rows.add(row);

        TableData data = new TableData("users", columns, rows);

        assertEquals("users", data.getTableName());
        assertEquals(columns, data.getColumnNames());
        assertEquals(1, data.getRowCount());
        assertEquals(2, data.getColumnCount());
        assertFalse(data.isEmpty());
        assertEquals("张三", data.getRows().get(0).get("name"));
    }

    @Test
    public void testNullRowsToleratedAsEmpty() {
        TableData data = new TableData("t", Arrays.asList("a"), null);
        assertEquals("null rows 应被容忍为空表", 0, data.getRowCount());
        assertTrue(data.isEmpty());
        assertNotNull(data.getRows());
    }

    @Test
    public void testNullColumnNamesToleratedAsEmpty() {
        // 回归：columnNames=null 曾直接 NPE，与 rows=null 的容忍行为不一致
        TableData data = new TableData("t", null, null);
        assertEquals("null 列名应被容忍为空列表", 0, data.getColumnCount());
        assertTrue(data.getColumnNames().isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testColumnNamesAreDefensivelyCopied() {
        List<String> columns = new ArrayList<>(Arrays.asList("a", "b"));
        TableData data = new TableData("t", columns, null);

        // 修改源列表不应影响内部状态
        columns.add("c");
        assertEquals(2, data.getColumnCount());

        // 返回的视图不可变
        data.getColumnNames().add("d");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testRowsListIsDefensivelyCopiedAndUnmodifiable() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<String, Object>());
        TableData data = new TableData("t", Arrays.asList("a"), rows);

        // 修改源列表不应影响内部状态
        rows.clear();
        assertEquals("构造后源列表修改不应影响内部数据", 1, data.getRowCount());

        // 返回的列表视图不可变（回归：曾直接暴露可变内部列表）
        data.getRows().add(new LinkedHashMap<String, Object>());
    }

    @Test
    public void testEmptyRowsMeansEmpty() {
        TableData data = new TableData("t", Arrays.asList("a"), new ArrayList<Map<String, Object>>());
        assertTrue(data.isEmpty());
        assertEquals(0, data.getRowCount());
        assertEquals(1, data.getColumnCount());
    }

    @Test
    public void testToString() {
        TableData data = new TableData("users", Arrays.asList("id"), null);
        String s = data.toString();
        assertTrue("toString 应包含表名", s.contains("users"));
        assertTrue("toString 应包含行数", s.contains("rows=0"));
    }
}
