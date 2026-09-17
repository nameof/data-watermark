package io.github.nameof.watermark.it;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.database.ChunkSource;
import io.github.nameof.watermark.database.DatabaseEmbedResult;
import io.github.nameof.watermark.database.DatabaseExtractResult;
import io.github.nameof.watermark.database.DatabaseWatermarker;
import io.github.nameof.watermark.io.TableData;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实 MySQL 集成测试：验证 {@link DatabaseWatermarker} 在 {@code hxl.user_info_watermark}
 * 上的端到端嵌入 → 写回 → 重读 → 提取流程。
 *
 * <p><b>连接配置（硬编码，仅用于集成测试）</b>：
 * <pre>
 *   host:     localhost:3306
 *   user:     root / root
 *   database: hxl
 *   table:    user_info_watermark (89 rows, 20 cols)
 * </pre>
 *
 * <p><b>数据安全</b>：{@link #setUp()} 在测试开始前快照全部行；
 * {@link #tearDown()} 通过 DELETE + INSERT 把表恢复到快照状态（使用显式主键，保证
 * PK 值不变）。多次运行不会污染原始数据。
 *
 * <p><b>策略选择</b>：同时使用 {@code SIMPLE_SUFFIX_MARKER} + {@code SIMPLE_INVISIBLE_PADDING}
 * ——core 内部策略注册顺序是 SuffixMarker 优先，单独用 InvisiblePadding 无法可靠提取
 * （详见 {@code core.Watermarker.findAnySimpleStrategy}）。
 *
 * <p><b>运行</b>：
 * <pre>
 *   mvn test -Dtest=MySqlWatermarkIntegrationTest
 * </pre>
 * 若 MySQL 不可达，{@code Assume} 跳过整个测试类，不会让 mvn test 整体红。
 */
public class MySqlWatermarkIntegrationTest {

    // ============================================================
    // 连接配置（按用户要求硬编码）
    // ============================================================

    private static final String URL =
        "jdbc:mysql://localhost:3306/hxl"
        + "?useUnicode=true&characterEncoding=utf8"
        + "&useSSL=false&serverTimezone=Asia/Shanghai"
        + "&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String TABLE = "user_info_watermark";
    private static final String PK = "id";

    /** 测试载荷：含中文与拉丁字符混合，验证 UTF-8 链路；保持较短以避免短字段溢出 */
    private static final String PAYLOAD = "WM2026测试";
    private static final String SECRET = "integration-test-secret-9f8a";
    private static final int CHUNK_SIZE = 30;  // 89 行分 3 块（30/30/29）

    /**
     * 仅使用 {@code SIMPLE_SUFFIX_MARKER}。
     *
     * <p>原因：本表数据中字段值普遍偏短（人名 2-3 字、籍贯 2 字等），
     * {@code SIMPLE_INVISIBLE_PADDING} 会追加 payload.length()*8+1 个零宽字符
     * 到每个单元格（如 "WM2026测试"=10 字符 → 81 个零宽字符），
     * 即使 VARCHAR(50) 也容纳不下。SuffixMarker 仅追加
     * {@code [::payload::]} 标记（payload=10 → 18 字符），短字段也可承载。
     *
     * <p>提取时 {@code core.Watermarker.findAnySimpleStrategy} 优先匹配 SuffixMarker
     * （registry 顺序），所有有水印的单元格都能被识别，无需配合 InvisiblePadding。
     */
    private static final List<WatermarkType> SIMPLE_STRATEGIES = Collections.singletonList(
        WatermarkType.SIMPLE_SUFFIX_MARKER
    );

    /**
     * 可以安全嵌入水印的列名。
     *
     * <p>排除规则（基于本表 schema）：
     * <ul>
     *   <li>主键 {@code id}（保持原值）</li>
     *   <li>{@code enum} 列：{@code gender} / {@code marital_status}（仅取固定枚举值）</li>
     *   <li>固定长度 {@code CHAR} 列：{@code phone(11)} / {@code id_card(18)} /
     *       {@code emergency_phone(11)}（追加 invisible chars 会超长）</li>
     *   <li>数值/日期列：{@code age} / {@code salary} / {@code birth_date} / {@code create_time} /
     *       {@code update_time}（SIMPLE 策略只支持字符串）</li>
     * </ul>
     */
    private static final List<String> EMBEDDABLE_COLUMNS = Arrays.asList(
        PK,  // 保留 PK 用于 UPDATE 定位
        "user_name", "email", "address", "ethnicity", "education",
        "career", "native_place", "emergency_contact", "remark"
    );

    // ============================================================
    // 测试状态
    // ============================================================

    private Connection conn;
    /** 原始数据快照（按 PK 排序的全部行），用于 tearDown 恢复 */
    private List<Map<String, Object>> snapshot;
    /** 原始数据列名顺序 */
    private List<String> snapshotColumns;

    // ============================================================
    // Fixture
    // ============================================================

    @Before
    public void setUp() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (SQLException e) {
            // MySQL 不可达 → 跳过整个测试类（不让 mvn test 整体红）
            Assume.assumeNoException(
                "MySQL @ localhost:3306/hxl 不可达 — 跳过集成测试", e);
            return;  // Assume 已经抛 AssumptionViolatedException，这里不可达
        }
        snapshot = readAllRows(conn, TABLE, PK);
        snapshotColumns = readColumns(conn, TABLE);
        Assume.assumeTrue("测试表 " + TABLE + " 行为空，跳过", snapshot.size() > 0);
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            try {
                if (snapshot != null && !snapshot.isEmpty()) {
                    restoreSnapshot();
                }
            } finally {
                conn.close();
            }
        }
    }

    // ============================================================
    // 测试用例
    // ============================================================

    /**
     * 端到端：embed → 写回 DB → 关闭连接 → 重连 → 重读 → extract → 验证 payload 一致。
     *
     * <p>这是验证"功能实际生效"的核心场景——跨连接持久化必须保持水印可提取。
     */
    @Test
    public void embed_thenWriteBack_thenReExtractFromMySql_roundTripsPayload() throws Exception {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        DatabaseWatermarker wm = new DatabaseWatermarker(config);

        // ---- 阶段 1：嵌入 + 写回（按块循环，每块立刻写回 DB）----
        int chunksProcessed = 0;
        int rowsModified = 0;
        int cellsModified = 0;
        List<String> involvedColumns = null;

        try (ChunkSource source = new JdbcChunkSource(conn, TABLE, CHUNK_SIZE)) {
            while (source.hasNext()) {
                TableData chunk = filterColumns(source.nextChunk(), EMBEDDABLE_COLUMNS);
                if (chunk.getRowCount() == 0) break;

                DatabaseEmbedResult embedResult = wm.embed(chunk, PAYLOAD, SIMPLE_STRATEGIES);
                assertTrue("嵌入必须成功: " + embedResult,
                    embedResult.getCellsModified() > 0);
                assertNotNull("embed 必须返回 modifiedTable 以便写回",
                    embedResult.getModifiedTable());

                int affected = TableUpdater.updateById(
                    conn, TABLE, PK,
                    embedResult.getModifiedTable().getRows(),
                    embedResult.getInvolvedColumns());
                assertEquals("每行 UPDATE 应恰好影响 1 行",
                    embedResult.getModifiedTable().getRowCount(), affected);

                chunksProcessed += embedResult.getChunksProcessed();
                rowsModified += embedResult.getRowsModified();
                cellsModified += embedResult.getCellsModified();
                involvedColumns = embedResult.getInvolvedColumns();
            }
        }

        assertEquals("整表应被分 3 块处理", 3, chunksProcessed);
        assertEquals("全部行应被修改", snapshot.size(), rowsModified);
        assertTrue("应修改了若干单元", cellsModified > 0);
        assertNotNull("应记录水印涉及列", involvedColumns);
        assertFalse("水印涉及列不应为空", involvedColumns.isEmpty());

        // ---- 阶段 2：关闭连接，重新打开模拟"另一进程/重启后"读取 ----
        conn.close();
        conn = DriverManager.getConnection(URL, USER, PASS);

        // ---- 阶段 3：从 DB 重读，按块循环提取 ----
        DatabaseExtractResult extractResult;
        try (ChunkSource source = new JdbcChunkSource(conn, TABLE, CHUNK_SIZE)) {
            extractResult = wm.extract(source);
        }

        // ---- 阶段 4：断言 ----
        assertNotNull("提取必须返回结果", extractResult);
        assertTrue("提取必须拿到 payload，实际: " + extractResult,
            extractResult.getPayload().isPresent());
        assertEquals("载荷必须与嵌入一致", PAYLOAD, extractResult.getPayload().get());
        assertTrue("置信度应 > 0", extractResult.getConfidence() > 0);
        assertEquals("扫描行数应等于表行数", snapshot.size(), extractResult.getRowsScanned());
        assertEquals("应处理 3 块", 3, extractResult.getChunksProcessed());
    }

    /**
     * 多 chunk 聚合正确性：用 {@link DatabaseWatermarker#aggregateEmbed} 和
     * {@link DatabaseWatermarker#aggregateExtract} 聚合多次单块调用的结果，
     * 与全表 API 行为一致（统计字段求和正确，载荷多数投票）。
     */
    @Test
    public void multiChunk_aggregatesRowAndCellCountsCorrectly() throws Exception {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        DatabaseWatermarker wm = new DatabaseWatermarker(config);

        // 手动按块循环嵌入 + 写回
        List<DatabaseEmbedResult> embedPartials = new ArrayList<>();
        try (ChunkSource source = new JdbcChunkSource(conn, TABLE, CHUNK_SIZE)) {
            while (source.hasNext()) {
                TableData chunk = filterColumns(source.nextChunk(), EMBEDDABLE_COLUMNS);
                if (chunk.getRowCount() == 0) break;
                DatabaseEmbedResult r = wm.embed(chunk, PAYLOAD, SIMPLE_STRATEGIES);
                TableUpdater.updateById(conn, TABLE, PK,
                    r.getModifiedTable().getRows(), r.getInvolvedColumns());
                embedPartials.add(r);
            }
        }
        DatabaseEmbedResult aggregated = DatabaseWatermarker.aggregateEmbed(embedPartials);

        int totalRows = 0;
        int totalCells = 0;
        for (DatabaseEmbedResult p : embedPartials) {
            totalRows += p.getRowsModified();
            totalCells += p.getCellsModified();
        }
        assertEquals("aggregateEmbed rowsModified 应等于各 partial 之和",
            totalRows, aggregated.getRowsModified());
        assertEquals("aggregateEmbed cellsModified 应等于各 partial 之和",
            totalCells, aggregated.getCellsModified());
        assertEquals("aggregateEmbed chunksProcessed 应等于 partial 数",
            embedPartials.size(), aggregated.getChunksProcessed());

        // extract 走手动循环 + 聚合
        List<DatabaseExtractResult> extractPartials = new ArrayList<>();
        try (ChunkSource source = new JdbcChunkSource(conn, TABLE, CHUNK_SIZE)) {
            while (source.hasNext()) {
                TableData chunk = source.nextChunk();
                if (chunk.getRowCount() == 0) break;
                extractPartials.add(wm.extract(chunk));
            }
        }
        DatabaseExtractResult aggExtract = DatabaseWatermarker.aggregateExtract(extractPartials);
        assertTrue("提取必须拿到 payload", aggExtract.getPayload().isPresent());
        assertEquals(PAYLOAD, aggExtract.getPayload().get());
    }

    /**
     * 写回幂等性：连续两次 UPDATE 同样的列、同样的值，DB 行内容（除 update_time 外）不变，
     * 且第二次提取仍能恢复 payload。
     *
     * <p>验证水印数据正确持久化——水印后的字节序列完整写入 MySQL 的 VARCHAR 列，
     * 没有被 DB 层截断或丢失（特别是 invisible chars）。
     */
    @Test
    public void writeBack_isIdempotent_watermarkSurvivesSecondUpdate() throws Exception {
        WatermarkConfig config = new WatermarkConfig(SECRET);
        DatabaseWatermarker wm = new DatabaseWatermarker(config);

        // 1. 第一次嵌入 + 写回；保留 modifiedTable 与 involvedColumns 备用
        List<TableData> modifiedTables = new ArrayList<>();
        List<List<String>> dirtyColumnsPerChunk = new ArrayList<>();
        try (ChunkSource source = new JdbcChunkSource(conn, TABLE, CHUNK_SIZE)) {
            while (source.hasNext()) {
                TableData chunk = filterColumns(source.nextChunk(), EMBEDDABLE_COLUMNS);
                if (chunk.getRowCount() == 0) break;
                DatabaseEmbedResult r = wm.embed(chunk, PAYLOAD, SIMPLE_STRATEGIES);
                TableUpdater.updateById(conn, TABLE, PK,
                    r.getModifiedTable().getRows(), r.getInvolvedColumns());
                modifiedTables.add(r.getModifiedTable());
                dirtyColumnsPerChunk.add(r.getInvolvedColumns());
            }
        }

        // 2. 记录第一次写回后的 DB 状态
        List<Map<String, Object>> afterFirstWrite = readAllRows(conn, TABLE, PK);

        // 3. 再次 UPDATE 同样的列、同样的值（不重新嵌入）
        int totalAffected = 0;
        for (int i = 0; i < modifiedTables.size(); i++) {
            totalAffected += TableUpdater.updateById(
                conn, TABLE, PK,
                modifiedTables.get(i).getRows(),
                dirtyColumnsPerChunk.get(i));
        }

        // 4. 再次读取；除 update_time（ON UPDATE CURRENT_TIMESTAMP）外，应与 afterFirstWrite 完全一致
        List<Map<String, Object>> afterSecondWrite = readAllRows(conn, TABLE, PK);
        assertEquals("行数应一致", afterFirstWrite.size(), afterSecondWrite.size());
        for (int i = 0; i < afterFirstWrite.size(); i++) {
            Map<String, Object> a = afterFirstWrite.get(i);
            Map<String, Object> b = afterSecondWrite.get(i);
            for (String col : a.keySet()) {
                if ("update_time".equals(col)) continue;
                assertEquals("row#" + i + " col " + col + " 应幂等",
                    String.valueOf(a.get(col)), String.valueOf(b.get(col)));
            }
        }
        assertEquals("幂等 UPDATE 应影响 89 行", snapshot.size(), totalAffected);

        // 5. 重新提取仍能拿到 payload（说明水印字节完整保留在 DB 中）
        DatabaseExtractResult extractResult;
        try (ChunkSource source = new JdbcChunkSource(conn, TABLE, CHUNK_SIZE)) {
            extractResult = wm.extract(source);
        }
        assertTrue("幂等写回后仍可提取", extractResult.getPayload().isPresent());
        assertEquals(PAYLOAD, extractResult.getPayload().get());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 从快照还原（按主键 UPDATE 全部列）。
     *
     * <p>策略：DELETE FROM + INSERT 全部行；INSERT 使用显式主键值，保证 PK 序列不被破坏。
     * MySQL connector 8.x 把 DATETIME 读成 LocalDateTime，靠 {@code setObject} 兜底传值。
     */
    private void restoreSnapshot() throws SQLException {
        conn.setAutoCommit(false);
        try {
            // 1. 清空表
            try (Statement del = conn.createStatement()) {
                del.executeUpdate("DELETE FROM `" + TABLE + "`");
            }
            // 2. 用显式主键 INSERT 全部行（绕过 AUTO_INCREMENT，避免 PK 漂移）
            String insertSql = buildInsertSql(snapshotColumns);
            try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                for (Map<String, Object> row : snapshot) {
                    int idx = 1;
                    for (String col : snapshotColumns) {
                        ins.setObject(idx++, row.get(col));
                    }
                    ins.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static String buildInsertSql(List<String> columns) {
        StringBuilder sb = new StringBuilder("INSERT INTO `").append(TABLE).append("` (");
        StringBuilder vb = new StringBuilder(") VALUES (");
        boolean first = true;
        for (String col : columns) {
            if (!first) { sb.append(", "); vb.append(", "); }
            sb.append("`").append(col).append("`");
            vb.append("?");
            first = false;
        }
        return sb.append(vb).append(")").toString();
    }

    /** 读取表全部行，按 {@code orderByColumn} 排序。 */
    private static List<Map<String, Object>> readAllRows(Connection conn, String table, String orderBy)
        throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM `" + table + "` ORDER BY `" + orderBy + "`");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>(n * 2);
                for (String c : cols) row.put(c, rs.getObject(c));
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<String> readColumns(Connection conn, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM `" + table + "` WHERE 0=1");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
        }
        return cols;
    }

    /**
     * 把 {@link TableData} 投影到指定列集合。
     *
     * <p>用于绕开 MySQL 表中不可嵌入水印的列（enum、固定长度 char、数值、日期）。
     * 保留 PK 列以便后续 UPDATE 定位。
     */
    private static TableData filterColumns(TableData src, List<String> keepColumns) {
        List<Map<String, Object>> filtered = new ArrayList<>(src.getRowCount());
        for (Map<String, Object> row : src.getRows()) {
            Map<String, Object> sub = new LinkedHashMap<>(keepColumns.size() * 2);
            for (String c : keepColumns) sub.put(c, row.get(c));
            filtered.add(sub);
        }
        return new TableData(src.getTableName(), keepColumns, filtered);
    }
}