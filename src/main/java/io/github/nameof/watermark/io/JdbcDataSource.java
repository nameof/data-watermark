package io.github.nameof.watermark.io;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * JDBC 数据源实现。
 * <p>
 * 通过 JDBC 连接数据库，读取/写入表数据。
 * 支持传入 {@link Connection} 或 JDBC URL + 用户名 + 密码。
 * </p>
 */
public class JdbcDataSource implements DataSource {

    private final Connection connection;
    private final boolean ownConnection;

    /**
     * 使用已有连接创建数据源（不自动关闭连接）。
     *
     * @param connection 数据库连接
     */
    public JdbcDataSource(Connection connection) {
        this.connection = connection;
        this.ownConnection = false;
    }

    /**
     * 使用 JDBC URL 创建数据源（自动管理连接生命周期）。
     *
     * @param jdbcUrl  JDBC 连接 URL
     * @param username 用户名
     * @param password 密码
     * @throws SQLException 连接失败
     */
    public JdbcDataSource(String jdbcUrl, String username, String password) throws SQLException {
        this.connection = DriverManager.getConnection(jdbcUrl, username, password);
        this.ownConnection = true;
    }

    @Override
    public TableData readTable(String tableName) throws Exception {
        return readTable(tableName, null);
    }

    @Override
    public TableData readTable(String tableName, String whereClause) throws Exception {
        String sql = "SELECT * FROM `" + tableName + "`";
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql += " WHERE " + whereClause;
        }

        List<String> columnNames = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                columnNames.add(meta.getColumnLabel(i));
            }

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(columnNames.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }
        }

        return new TableData(tableName, columnNames, rows);
    }

    @Override
    public void writeTable(TableData data, String newTableName) throws Exception {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("表数据为空");
        }

        // 获取源表类型信息用于建表
        List<String> columns = data.getColumnNames();
        List<Map<String, Object>> rows = data.getRows();

        // 推断列类型并建表
        StringBuilder createSql = new StringBuilder();
        createSql.append("CREATE TABLE IF NOT EXISTS `").append(newTableName).append("` (");
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            String sqlType = inferSqlType(rows.get(0).get(col));
            createSql.append("`").append(col).append("` ").append(sqlType);
            if (i < columns.size() - 1) createSql.append(", ");
        }
        createSql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(createSql.toString());
        }

        // 插入数据
        StringBuilder insertSql = new StringBuilder();
        insertSql.append("INSERT INTO `").append(newTableName).append("` (");
        for (int i = 0; i < columns.size(); i++) {
            insertSql.append("`").append(columns.get(i)).append("`");
            if (i < columns.size() - 1) insertSql.append(", ");
        }
        insertSql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            insertSql.append("?");
            if (i < columns.size() - 1) insertSql.append(", ");
        }
        insertSql.append(")");

        try (PreparedStatement ps = connection.prepareStatement(insertSql.toString())) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    Object value = row.get(columns.get(i));
                    ps.setObject(i + 1, value);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public void createTableLike(String sourceTable, String newTable) throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS `" + newTable + "` LIKE `" + sourceTable + "`";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    @Override
    public void close() throws java.io.IOException {
        if (ownConnection && connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new java.io.IOException("关闭数据库连接失败", e);
            }
        }
    }

    /**
     * 获取底层 JDBC 连接。
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * 根据 Java 对象类型推断 SQL 列类型。
     */
    private String inferSqlType(Object value) {
        if (value == null) {
            return "VARCHAR(500)";
        }
        if (value instanceof Integer) {
            return "INT";
        }
        if (value instanceof Long) {
            return "BIGINT";
        }
        if (value instanceof BigDecimal) {
            BigDecimal bd = (BigDecimal) value;
            int precision = Math.max(bd.precision(), 10);
            int scale = Math.max(bd.scale(), 2);
            return "DECIMAL(" + precision + "," + scale + ")";
        }
        if (value instanceof Float || value instanceof Double) {
            return "DOUBLE";
        }
        if (value instanceof Boolean) {
            return "TINYINT(1)";
        }
        if (value instanceof java.sql.Date || value instanceof java.util.Date) {
            return "DATETIME";
        }
        // 默认使用 VARCHAR
        String str = value.toString();
        int len = Math.max(str.length() * 2, 100);
        return "VARCHAR(" + Math.min(len, 4000) + ")";
    }
}
