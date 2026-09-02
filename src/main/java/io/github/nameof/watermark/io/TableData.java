package io.github.nameof.watermark.io;

import java.util.*;

/**
 * 表数据 DTO，封装数据库表的结构和内容。
 * <p>
 * 包含表名、列名列表和行数据列表。
 * 每行数据用 {@code Map<String, Object>} 表示，key 为列名。
 * </p>
 */
public class TableData {

    /** 表名 */
    private final String tableName;

    /** 列名列表（有序） */
    private final List<String> columnNames;

    /** 行数据列表，每行为列名→值的映射 */
    private final List<Map<String, Object>> rows;

    /**
     * 创建表数据。
     *
     * @param tableName  表名
     * @param columnNames 列名列表（有序）
     * @param rows       行数据列表
     */
    public TableData(String tableName, List<String> columnNames, List<Map<String, Object>> rows) {
        this.tableName = tableName;
        this.columnNames = Collections.unmodifiableList(new ArrayList<>(columnNames));
        this.rows = rows != null ? new ArrayList<>(rows) : new ArrayList<Map<String, Object>>();
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    /** 获取行数 */
    public int getRowCount() {
        return rows.size();
    }

    /** 获取列数 */
    public int getColumnCount() {
        return columnNames.size();
    }

    /** 检查表是否为空（无数据行） */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public String toString() {
        return "TableData{table='" + tableName + "', columns=" + columnNames
                + ", rows=" + rows.size() + "}";
    }
}
