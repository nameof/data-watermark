package io.github.nameof.watermark;

import io.github.nameof.watermark.io.JdbcDataSource;
import io.github.nameof.watermark.io.TableData;
import io.github.nameof.watermark.simple.SimpleWatermarker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.*;
import java.util.*;

/**
 * 全策略集成测试 —— 基于 MySQL 数据库的读写对比测试。
 * <p>
 * 在 hxl2 库中：
 * <ol>
 *   <li>创建源表 user_info_source（500 行模拟用户数据）</li>
 *   <li>对每种水印策略，从源表读取 → 嵌入水印 → 写入目标表</li>
 *   <li>从目标表提取水印并验证</li>
 * </ol>
 * 最终在数据库中生成多张表，可直接用 SQL 客户端对比效果。
 * </p>
 */
public class AllStrategiesIntegrationTest {

    private static final String JDBC_URL =
            "jdbc:mysql://localhost:3306/hxl2?characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    private static final String PAYLOAD = "operator:zhangsan|company:ACME";
    private static final String SECRET = "integration-test-secret-key";
    private static final int ROW_COUNT = 500;

    /** 源表名 */
    private static final String SOURCE_TABLE = "user_info_source";

    /** 各策略输出表名 */
    private static final String TBL_SIMPLE_SUFFIX_CN   = "wm_simple_suffix_cn";
    private static final String TBL_SIMPLE_SUFFIX_EMAIL = "wm_simple_suffix_email";
    private static final String TBL_SIMPLE_SUFFIX_ALL   = "wm_simple_suffix_all";
    private static final String TBL_SIMPLE_PADDING_CN   = "wm_simple_padding_cn";
    private static final String TBL_SIMPLE_PADDING_ALL  = "wm_simple_padding_all";
    private static final String TBL_BITLEVEL_CN         = "wm_bitlevel_cn";
    private static final String TBL_BITLEVEL_EMAIL      = "wm_bitlevel_email";
    private static final String TBL_BITLEVEL_NUMERIC    = "wm_bitlevel_numeric";
    private static final String TBL_BITLEVEL_ALL        = "wm_bitlevel_all";

    private Connection conn;

    // ==================== 生命周期 ====================

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        conn.setAutoCommit(true);

        // 清理旧表
        dropAllTables();

        // 创建源表并灌入 500 行数据
        createSourceTable();
        populateSourceData();

        System.out.println("=== 源表 " + SOURCE_TABLE + " 已创建，共 " + ROW_COUNT + " 行 ===");
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ==================== 测试入口 ====================

    @Test
    public void testAllStrategiesWithMySql() throws Exception {
        printSection("开始全策略 MySQL 集成测试");
        System.out.println("  载荷: " + PAYLOAD);
        System.out.println("  密钥: " + SECRET);
        System.out.println("  源表: " + SOURCE_TABLE + " (" + ROW_COUNT + " 行)");
        System.out.println();

        // 读取源表数据
        JdbcDataSource ds = new JdbcDataSource(conn);
        TableData sourceData = ds.readTable(SOURCE_TABLE);
        // 排除 id 列，只取业务列做水印
        List<String> allColumns = new ArrayList<>();
        for (String c : sourceData.getColumnNames()) {
            if (!"id".equalsIgnoreCase(c)) allColumns.add(c);
        }
        System.out.println("  源表列: " + allColumns);
        System.out.println();

        // ========== Simple 模式 ==========
        WatermarkConfig simpleConfig = new WatermarkConfig(SECRET);
        simpleConfig.setUseBitLevel(false);
        SimpleWatermarker simpleWatermarker = new SimpleWatermarker(simpleConfig);

        // 1. SuffixMarker - 中文列
        runSimpleTest(ds, simpleWatermarker, sourceData,
                Arrays.asList("name", "address"), TBL_SIMPLE_SUFFIX_CN,
                "Simple-SuffixMarker-中文列");

        // 2. SuffixMarker - 拉丁列
        runSimpleTest(ds, simpleWatermarker, sourceData,
                Collections.singletonList("email"), TBL_SIMPLE_SUFFIX_EMAIL,
                "Simple-SuffixMarker-拉丁列");

        // 3. SuffixMarker - 全字符串列
        runSimpleTest(ds, simpleWatermarker, sourceData,
                Arrays.asList("name", "address", "email"), TBL_SIMPLE_SUFFIX_ALL,
                "Simple-SuffixMarker-全字符串列");

        // 4. InvisiblePadding - 中文列
        runSimpleTestWithStrategy(ds, simpleConfig, sourceData,
                Arrays.asList("name", "address"), TBL_SIMPLE_PADDING_CN,
                "Simple-InvisiblePadding-中文列", "invisible-padding");

        // 5. InvisiblePadding - 全字符串列
        runSimpleTestWithStrategy(ds, simpleConfig, sourceData,
                Arrays.asList("name", "address", "email"), TBL_SIMPLE_PADDING_ALL,
                "Simple-InvisiblePadding-全字符串列", "invisible-padding");

        // ========== Bit-level 模式 ==========
        WatermarkConfig bitConfig = new WatermarkConfig(SECRET);
        bitConfig.setUseBitLevel(true);
        DataWatermarker bitWatermarker = new DataWatermarker(bitConfig);

        // 6. BitLevel - 中文列
        runBitLevelTest(ds, bitWatermarker, sourceData,
                Arrays.asList("name", "address"), TBL_BITLEVEL_CN,
                "BitLevel-中文列");

        // 7. BitLevel - 拉丁列
        runBitLevelTest(ds, bitWatermarker, sourceData,
                Collections.singletonList("email"), TBL_BITLEVEL_EMAIL,
                "BitLevel-拉丁列");

        // 8. BitLevel - 数值列
        runBitLevelTest(ds, bitWatermarker, sourceData,
                Arrays.asList("age", "salary", "score"), TBL_BITLEVEL_NUMERIC,
                "BitLevel-数值列");

        // 9. BitLevel - 全列
        runBitLevelTest(ds, bitWatermarker, sourceData,
                allColumns, TBL_BITLEVEL_ALL,
                "BitLevel-全列");

        // ========== 汇总 ==========
        printSection("测试完成，数据库表汇总");
        listAllTables();
    }

    // ==================== Simple 模式测试 ====================

    private void runSimpleTest(JdbcDataSource ds, SimpleWatermarker watermarker,
                               TableData sourceData, List<String> columns,
                               String targetTable, String label) throws Exception {
        printSection(label + " → " + targetTable);

        List<Map<String, Object>> rows = sourceData.getRows();
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(rows, columns, PAYLOAD);

        if (!embedResult.isSuccess()) {
            System.out.println("  [嵌入失败] " + embedResult.getMessage());
            return;
        }

        // 写入目标表
        TableData targetData = new TableData(targetTable, sourceData.getColumnNames(), embedResult.getData());
        writeTableToDb(ds, targetData, targetTable);

        System.out.println("  嵌入成功 | 写入表: " + targetTable
                + " | 涉及列: " + embedResult.getWatermarkedColumns()
                + " | 单元格数: " + embedResult.getRepetition());

        // 从目标表读回并提取
        TableData readBack = ds.readTable(targetTable);
        WatermarkResult<String> extractResult = watermarker.extract(readBack.getRows(), columns);
        printExtractResult(extractResult);
        System.out.println();
    }

    /**
     * 使用指定策略名的 Simple 测试（用于 InvisiblePadding 单独测试）。
     */
    private void runSimpleTestWithStrategy(JdbcDataSource ds, WatermarkConfig config,
                                           TableData sourceData, List<String> columns,
                                           String targetTable, String label,
                                           String strategyName) throws Exception {
        printSection(label + " → " + targetTable);

        // 构建只含指定策略的 SimpleWatermarker
        List<io.github.nameof.watermark.simple.SimpleWatermarkStrategy> strategies = new ArrayList<>();
        if ("invisible-padding".equals(strategyName)) {
            strategies.add(new io.github.nameof.watermark.simple.InvisiblePaddingStrategy());
        } else {
            strategies.add(new io.github.nameof.watermark.simple.SuffixMarkerStrategy());
        }
        SimpleWatermarker watermarker = new SimpleWatermarker(config, strategies);

        List<Map<String, Object>> rows = sourceData.getRows();
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(rows, columns, PAYLOAD);

        if (!embedResult.isSuccess()) {
            System.out.println("  [嵌入失败] " + embedResult.getMessage());
            return;
        }

        TableData targetData = new TableData(targetTable, sourceData.getColumnNames(), embedResult.getData());
        writeTableToDb(ds, targetData, targetTable);

        System.out.println("  嵌入成功 | 写入表: " + targetTable
                + " | 涉及列: " + embedResult.getWatermarkedColumns()
                + " | 单元格数: " + embedResult.getRepetition());

        TableData readBack = ds.readTable(targetTable);
        WatermarkResult<String> extractResult = watermarker.extract(readBack.getRows(), columns);
        printExtractResult(extractResult);
        System.out.println();
    }

    // ==================== Bit-level 模式测试 ====================

    private void runBitLevelTest(JdbcDataSource ds, DataWatermarker watermarker,
                                 TableData sourceData, List<String> columns,
                                 String targetTable, String label) throws Exception {
        printSection(label + " → " + targetTable);

        List<Map<String, Object>> rows = sourceData.getRows();
        WatermarkResult<List<Map<String, Object>>> embedResult =
                watermarker.embed(rows, columns, PAYLOAD);

        if (!embedResult.isSuccess()) {
            System.out.println("  [嵌入失败] " + embedResult.getMessage());
            return;
        }

        TableData targetData = new TableData(targetTable, sourceData.getColumnNames(), embedResult.getData());
        writeTableToDb(ds, targetData, targetTable);

        System.out.println("  嵌入成功 | 写入表: " + targetTable
                + " | 涉及列: " + embedResult.getWatermarkedColumns()
                + " | 重复因子: " + embedResult.getRepetition());

        // 从目标表读回并提取
        TableData readBack = ds.readTable(targetTable);
        WatermarkResult<String> extractResult = watermarker.extract(readBack.getRows(), columns);
        printExtractResult(extractResult);
        System.out.println();
    }

    // ==================== 数据库操作 ====================

    private void dropAllTables() throws SQLException {
        String[] tables = {
                SOURCE_TABLE,
                TBL_SIMPLE_SUFFIX_CN, TBL_SIMPLE_SUFFIX_EMAIL, TBL_SIMPLE_SUFFIX_ALL,
                TBL_SIMPLE_PADDING_CN, TBL_SIMPLE_PADDING_ALL,
                TBL_BITLEVEL_CN, TBL_BITLEVEL_EMAIL, TBL_BITLEVEL_NUMERIC, TBL_BITLEVEL_ALL
        };
        try (Statement stmt = conn.createStatement()) {
            for (String t : tables) {
                stmt.executeUpdate("DROP TABLE IF EXISTS `" + t + "`");
            }
        }
        System.out.println("  已清理旧表");
    }

    private void createSourceTable() throws SQLException {
        String sql = "CREATE TABLE `" + SOURCE_TABLE + "` ("
                + "`id` INT AUTO_INCREMENT PRIMARY KEY, "
                + "`name` VARCHAR(50) NOT NULL, "
                + "`address` VARCHAR(200) NOT NULL, "
                + "`email` VARCHAR(200) NOT NULL, "
                + "`age` INT NOT NULL, "
                + "`salary` DECIMAL(10,2) NOT NULL, "
                + "`score` DECIMAL(5,1) NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    private void populateSourceData() throws SQLException {
        Random rand = new Random(42);
        String sql = "INSERT INTO `" + SOURCE_TABLE + "` (name, address, email, age, salary, score) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ROW_COUNT; i++) {
                String surname = SURNAMES[rand.nextInt(SURNAMES.length)];
                String givenName = GIVEN_NAMES[rand.nextInt(GIVEN_NAMES.length)];
                ps.setString(1, surname + givenName);

                String city = CITIES[rand.nextInt(CITIES.length)];
                String district = DISTRICTS[rand.nextInt(DISTRICTS.length)];
                String street = STREETS[rand.nextInt(STREETS.length)];
                ps.setString(2, city + district + street + (rand.nextInt(200) + 1) + "号");

                String latinName = LATIN_NAMES[rand.nextInt(LATIN_NAMES.length)];
                String domain = DOMAINS[rand.nextInt(DOMAINS.length)];
                ps.setString(3, latinName + i + "@" + domain);

                ps.setInt(4, rand.nextInt(40) + 20);
                ps.setDouble(5, Math.round((rand.nextDouble() * 30000 + 5000) * 100.0) / 100.0);
                ps.setDouble(6, Math.round(rand.nextDouble() * 100 * 10.0) / 10.0);

                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 将水印后的数据写入 MySQL 目标表。
     * 先 DROP 再 CREATE，列类型根据水印特性调整：
     * - 字符串列加宽到 VARCHAR(1000)（水印会增加长度）
     * - 数值列保持原类型
     */
    private void writeTableToDb(JdbcDataSource ds, TableData data, String tableName) throws Exception {
        List<String> allColumns = data.getColumnNames();
        // 跳过源表的 id 列，目标表自建自增主键
        List<String> columns = new ArrayList<>();
        for (String c : allColumns) {
            if (!"id".equalsIgnoreCase(c)) columns.add(c);
        }
        List<Map<String, Object>> rows = data.getRows();

        if (rows.isEmpty()) return;

        // 建表：字符串列加宽
        StringBuilder createSql = new StringBuilder();
        createSql.append("CREATE TABLE `").append(tableName).append("` (");
        createSql.append("`id` INT AUTO_INCREMENT PRIMARY KEY, ");
        Map<String, String> colTypes = inferColumnTypes(rows.get(0), columns);
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            createSql.append("`").append(col).append("` ").append(colTypes.get(col));
            if (i < columns.size() - 1) createSql.append(", ");
        }
        createSql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createSql.toString());
        }

        // 批量插入
        StringBuilder insertSql = new StringBuilder();
        insertSql.append("INSERT INTO `").append(tableName).append("` (");
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

        try (PreparedStatement ps = conn.prepareStatement(insertSql.toString())) {
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

    /**
     * 推断列类型，字符串列加宽以容纳水印。
     */
    private Map<String, String> inferColumnTypes(Map<String, Object> sampleRow, List<String> columns) {
        Map<String, String> types = new LinkedHashMap<>();
        for (String col : columns) {
            Object val = sampleRow.get(col);
            if (val instanceof Integer) {
                types.put(col, "INT");
            } else if (val instanceof Long) {
                types.put(col, "BIGINT");
            } else if (val instanceof java.math.BigDecimal) {
                java.math.BigDecimal bd = (java.math.BigDecimal) val;
                int p = Math.max(bd.precision(), 10);
                int s = Math.max(bd.scale(), 2);
                types.put(col, "DECIMAL(" + p + "," + s + ")");
            } else if (val instanceof Double || val instanceof Float) {
                types.put(col, "DOUBLE");
            } else {
                // 字符串列加宽到 1000，容纳水印
                types.put(col, "VARCHAR(1000)");
            }
        }
        return types;
    }

    private void listAllTables() throws SQLException {
        String sql = "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = 'hxl2' AND (TABLE_NAME LIKE 'wm_%' OR TABLE_NAME = '"
                + SOURCE_TABLE + "') ORDER BY TABLE_NAME";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            System.out.println("  数据库 hxl2 中的水印表:");
            while (rs.next()) {
                String tblName = rs.getString(1);
                // 用精确 COUNT 替代估算
                long exactCount = 0;
                try (Statement cntStmt = conn.createStatement();
                     ResultSet cntRs = cntStmt.executeQuery("SELECT COUNT(*) FROM `" + tblName + "`")) {
                    if (cntRs.next()) exactCount = cntRs.getLong(1);
                }
                System.out.printf("    %-35s (%d rows)%n", tblName, exactCount);
            }
        }
    }

    // ==================== 输出工具 ====================

    private void printSection(String title) {
        System.out.println();
        System.out.println(repeatChar('-', 90));
        System.out.println("  " + title);
        System.out.println(repeatChar('-', 90));
    }

    private void printExtractResult(WatermarkResult<String> result) {
        System.out.println("  提取结果:");
        System.out.println("    成功: " + result.isSuccess());
        if (result.isSuccess()) {
            boolean match = PAYLOAD.equals(result.getData());
            System.out.println("    载荷: " + result.getData() + (match ? "  [MATCH]" : "  [MISMATCH]"));
            System.out.println("    模式: " + result.getWatermarkMode());
            System.out.println("    总单元格: " + result.getTotalCells());
            System.out.println("    有效提取: " + result.getValidExtractions());
            System.out.printf("    置信度: %.1f%%%n", result.getConfidence());
        } else {
            System.out.println("    错误: " + result.getMessage());
        }
    }

    private String repeatChar(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // ==================== 数据字典 ====================

    private static final String[] SURNAMES = {"张", "王", "李", "赵", "刘", "陈", "杨", "黄", "周", "吴"};
    private static final String[] GIVEN_NAMES = {"伟", "芳", "敏", "强", "磊", "静", "洋", "勇", "军", "丽",
            "建华", "秀英", "明辉", "婷婷", "志强", "雪梅", "浩然", "思琪", "子轩", "雨萱"};
    private static final String[] CITIES = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "重庆", "西安"};
    private static final String[] DISTRICTS = {"朝阳区", "海淀区", "浦东新区", "天河区", "南山区", "西湖区",
            "武侯区", "鼓楼区", "渝中区", "雁塔区"};
    private static final String[] STREETS = {"中山路", "解放路", "人民路", "建设路", "和平路",
            "光明路", "文化路", "科技路", "学院路", "长安街"};
    private static final String[] DOMAINS = {"gmail.com", "outlook.com", "qq.com", "163.com", "company.cn"};
    private static final String[] LATIN_NAMES = {"alice", "bob", "charlie", "david", "emma",
            "frank", "grace", "henry", "iris", "jack"};
}
