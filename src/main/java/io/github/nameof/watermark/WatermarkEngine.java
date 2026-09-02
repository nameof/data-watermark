package io.github.nameof.watermark;

import io.github.nameof.watermark.bit.ColumnWatermarkStrategy;
import io.github.nameof.watermark.io.*;
import io.github.nameof.watermark.simple.SimpleWatermarker;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.util.*;

/**
 * 水印系统高层门面 API。
 * <p>
 * 封装了 bit-level 和 simple 两种水印引擎，提供统一的数据库/文件操作接口。
 * 用户无需关心底层细节，通过 Engine 即可完成：
 * <ul>
 *   <li>从数据库读取数据 → 嵌入水印 → 写入新表/CSV</li>
 *   <li>从数据库/CSV 读取数据 → 提取水印</li>
 *   <li>检测表是否支持水印</li>
 * </ul>
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * WatermarkConfig config = new WatermarkConfig("my-secret");
 * try (WatermarkEngine engine = new WatermarkEngine(
 *         "jdbc:mysql://localhost:3306/mydb", "root", "root", config)) {
 *     // 嵌入水印到新表
 *     engine.embed("users", Arrays.asList("name", "salary"),
 *                  "operator:zhangsan", "users_watermarked");
 *     // 提取水印
 *     WatermarkResult<String> result = engine.extract("users_watermarked",
 *                                                      Arrays.asList("name", "salary"));
 *     System.out.println("载荷: " + result.getData());
 * }
 * }</pre>
 */
public class WatermarkEngine implements Closeable {

    private final WatermarkConfig config;
    private final DataWatermarker bitLevelWatermarker;
    private final SimpleWatermarker simpleWatermarker;
    private DataSource dataSource;

    /**
     * 使用配置创建引擎（不提供数据源，需后续手动设置）。
     *
     * @param config 水印配置
     */
    public WatermarkEngine(WatermarkConfig config) {
        this.config = config;
        this.bitLevelWatermarker = new DataWatermarker(config);
        this.simpleWatermarker = new SimpleWatermarker(config);
    }

    /**
     * 使用 JDBC 连接信息创建引擎。
     *
     * @param jdbcUrl  JDBC URL
     * @param user     用户名
     * @param password 密码
     * @param config   水印配置
     * @throws Exception 连接失败
     */
    public WatermarkEngine(String jdbcUrl, String user, String password, WatermarkConfig config)
            throws Exception {
        this(config);
        this.dataSource = new JdbcDataSource(jdbcUrl, user, password);
    }

    /**
     * 使用已有数据源创建引擎。
     *
     * @param dataSource 数据源
     * @param config     水印配置
     */
    public WatermarkEngine(DataSource dataSource, WatermarkConfig config) {
        this(config);
        this.dataSource = dataSource;
    }

    // ==================== 数据库操作 ====================

    /**
     * 从数据库表读取数据、嵌入水印、写入新表。
     *
     * @param sourceTable 源表名
     * @param columns     待嵌入的列名列表
     * @param payload     水印载荷
     * @param outputTable 输出表名
     * @return 嵌入结果
     * @throws Exception 操作失败
     */
    public WatermarkResult<TableData> embed(String sourceTable, List<String> columns,
                                            String payload, String outputTable) throws Exception {
        requireDataSource();

        // 读取源表数据
        TableData source = dataSource.readTable(sourceTable);
        List<Map<String, Object>> rows = source.getRows();

        // 嵌入水印
        WatermarkResult<List<Map<String, Object>>> embedResult;
        if (config.isUseBitLevel()) {
            embedResult = bitLevelWatermarker.embed(rows, columns, payload);
        } else {
            embedResult = simpleWatermarker.embed(rows, columns, payload);
        }

        if (!embedResult.isSuccess()) {
            return WatermarkResult.failure(embedResult.getMessage());
        }

        // 写入新表
        TableData watermarked = new TableData(outputTable, source.getColumnNames(), embedResult.getData());
        dataSource.writeTable(watermarked, outputTable);

        return WatermarkResult.success(watermarked, embedResult.getWatermarkedColumns(),
                embedResult.getRepetition());
    }

    /**
     * 从数据库表读取数据、嵌入水印、原地更新（UPDATE 原始表）。
     *
     * @param sourceTable 源表名
     * @param columns     待嵌入的列名列表
     * @param payload     水印载荷
     * @return 嵌入结果
     * @throws Exception 操作失败
     */
    public WatermarkResult<TableData> embedInPlace(String sourceTable, List<String> columns,
                                                   String payload) throws Exception {
        requireDataSource();

        TableData source = dataSource.readTable(sourceTable);
        List<Map<String, Object>> rows = source.getRows();

        WatermarkResult<List<Map<String, Object>>> embedResult;
        if (config.isUseBitLevel()) {
            embedResult = bitLevelWatermarker.embed(rows, columns, payload);
        } else {
            embedResult = simpleWatermarker.embed(rows, columns, payload);
        }

        if (!embedResult.isSuccess()) {
            return WatermarkResult.failure(embedResult.getMessage());
        }

        // 写回原表（覆盖）
        TableData watermarked = new TableData(sourceTable, source.getColumnNames(), embedResult.getData());
        dataSource.writeTable(watermarked, sourceTable + "_tmp");

        return WatermarkResult.success(watermarked, embedResult.getWatermarkedColumns(),
                embedResult.getRepetition());
    }

    /**
     * 从数据库表提取水印。
     *
     * @param table   表名
     * @param columns 包含水印的列名列表
     * @return 提取结果
     * @throws Exception 操作失败
     */
    public WatermarkResult<String> extract(String table, List<String> columns) throws Exception {
        requireDataSource();

        TableData data = dataSource.readTable(table);

        if (config.isUseBitLevel()) {
            return bitLevelWatermarker.extract(data.getRows(), columns);
        } else {
            return simpleWatermarker.extract(data.getRows(), columns);
        }
    }

    // ==================== 文件操作 ====================

    /**
     * 从数据库读取数据、嵌入水印、导出到 CSV 文件。
     *
     * @param sourceTable 源表名
     * @param columns     待嵌入的列名列表
     * @param payload     水印载荷
     * @param csvPath     CSV 文件路径
     * @return 嵌入结果
     * @throws Exception 操作失败
     */
    public WatermarkResult<TableData> embedToCsv(String sourceTable, List<String> columns,
                                                  String payload, String csvPath) throws Exception {
        requireDataSource();

        TableData source = dataSource.readTable(sourceTable);
        List<Map<String, Object>> rows = source.getRows();

        WatermarkResult<List<Map<String, Object>>> embedResult;
        if (config.isUseBitLevel()) {
            embedResult = bitLevelWatermarker.embed(rows, columns, payload);
        } else {
            embedResult = simpleWatermarker.embed(rows, columns, payload);
        }

        if (!embedResult.isSuccess()) {
            return WatermarkResult.failure(embedResult.getMessage());
        }

        // 导出到 CSV
        TableData watermarked = new TableData(sourceTable, source.getColumnNames(), embedResult.getData());
        CsvDataSource csvDs = new CsvDataSource(getParentDir(csvPath));
        csvDs.writeTable(watermarked, getFileName(csvPath));
        csvDs.close();

        return WatermarkResult.success(watermarked, embedResult.getWatermarkedColumns(),
                embedResult.getRepetition());
    }

    /**
     * 从 CSV 文件读取数据、提取水印。
     *
     * @param csvPath CSV 文件路径
     * @param columns 包含水印的列名列表
     * @return 提取结果
     * @throws Exception 操作失败
     */
    public WatermarkResult<String> extractFromCsv(String csvPath, List<String> columns) throws Exception {
        CsvDataSource csvDs = new CsvDataSource(getParentDir(csvPath));
        TableData data = csvDs.readTable(getFileName(csvPath));
        csvDs.close();

        if (config.isUseBitLevel()) {
            return bitLevelWatermarker.extract(data.getRows(), columns);
        } else {
            return simpleWatermarker.extract(data.getRows(), columns);
        }
    }

    // ==================== 便捷方法 ====================

    /**
     * 检测指定表的指定列是否支持水印嵌入。
     *
     * @param table   表名
     * @param columns 待检测的列名列表
     * @return 是否支持
     * @throws Exception 操作失败
     */
    public boolean supportsWatermark(String table, List<String> columns) throws Exception {
        requireDataSource();
        TableData data = dataSource.readTable(table);

        if (config.isUseBitLevel()) {
            return bitLevelWatermarker.supportsWatermark(data.getRows(), columns);
        }
        // 简单模式：只要有非空字符串列就支持
        for (Map<String, Object> row : data.getRows()) {
            for (String col : columns) {
                if (row.get(col) != null) return true;
            }
        }
        return false;
    }

    /**
     * 设置数据源。
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 获取 bit-level 水印引擎。
     */
    public DataWatermarker getBitLevelWatermarker() {
        return bitLevelWatermarker;
    }

    /**
     * 获取简单水印引擎。
     */
    public SimpleWatermarker getSimpleWatermarker() {
        return simpleWatermarker;
    }

    /**
     * 获取当前数据源。
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public void close() throws IOException {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ==================== 内部方法 ====================

    private void requireDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("未设置数据源，请先调用 setDataSource() 或使用带 JDBC 参数的构造器");
        }
    }

    private String getParentDir(String path) {
        int idx = path.lastIndexOf(java.io.File.separatorChar);
        if (idx < 0) idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(0, idx) : ".";
    }

    private String getFileName(String path) {
        int idx = path.lastIndexOf(java.io.File.separatorChar);
        if (idx < 0) idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
