package io.github.nameof.watermark.it;

import io.github.nameof.watermark.database.ChunkSource;
import io.github.nameof.watermark.io.TableData;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 JDBC {@link ResultSet} 包成 {@link ChunkSource} 的测试用实现。
 *
 * <p><b>仅用于集成测试</b>，不属于主库 API。原因：
 * <ul>
 *   <li>本类的存在意义是让 {@link io.github.nameof.watermark.database.DatabaseWatermarker}
 *       在真实 MySQL 上能跑起来，验证端到端流程；通用适配层由调用方自行实现。</li>
 *   <li>放在 {@code it} 包下，明确"集成测试夹具"语义。</li>
 *   <li>只读实现（不持有写入逻辑），写回由测试类自己用 {@link TableUpdater#updateById} 完成。</li>
 * </ul>
 *
 * <p>行为约定：
 * <ul>
 *   <li>SELECT 在构造时立即执行（forward-only + fetchSize 提示）</li>
 *   <li>每次 {@link #nextChunk()} 返回至多 {@code chunkSize} 行；列名从 {@link ResultSetMetaData} 提取</li>
 *   <li>耗尽后再调 {@link #nextChunk()} 返回空 {@link TableData}（rows.size()==0）</li>
 *   <li>{@link #close()} 关闭 Statement 和 ResultSet；不关闭外部传入的 Connection</li>
 * </ul>
 */
public class JdbcChunkSource implements ChunkSource {

    private final Statement stmt;
    private final ResultSet rs;
    private final int chunkSize;
    private final List<String> columns;  // 缓存列名
    private final String tableName;
    private TableData pending;
    private boolean exhausted;
    private boolean closed;

    public JdbcChunkSource(Connection conn, String tableName, int chunkSize) throws SQLException {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        this.tableName = tableName;
        this.chunkSize = chunkSize;
        this.stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            // fetchSize 是 Statement hint；MySQL 驱动要求 ≥ Integer.MIN_VALUE
            int hint = Math.max(chunkSize, 1);
            if (hint > Integer.MIN_VALUE + 1024) {
                stmt.setFetchSize(hint);
            }
        } catch (SQLException ignored) {
            // 某些驱动对 fetchSize 限制严格；不致命
        }
        this.rs = stmt.executeQuery("SELECT * FROM `" + tableName + "` ORDER BY `id`");
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
        this.columns = cols;
    }

    @Override
    public boolean hasNext() throws Exception {
        ensureOpen();
        if (pending != null) return true;
        if (exhausted) return false;
        loadNext();
        return pending != null;
    }

    @Override
    public TableData nextChunk() throws Exception {
        ensureOpen();
        if (pending == null) loadNext();
        TableData r = pending;
        pending = null;
        if (r == null) {
            // 耗尽：返回空表（保持接口幂等，不抛异常）
            return new TableData(tableName, columns, new ArrayList<Map<String, Object>>());
        }
        return r;
    }

    private void loadNext() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>(chunkSize);
        for (int i = 0; i < chunkSize; i++) {
            if (!rs.next()) {
                exhausted = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>(columns.size() * 2);
            for (String col : columns) {
                row.put(col, rs.getObject(col));
            }
            rows.add(row);
        }
        pending = rows.isEmpty() ? null : new TableData(tableName, columns, rows);
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        try {
            if (rs != null) rs.close();
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("JdbcChunkSource already closed");
    }
}