package io.github.nameof.watermark.database;

import io.github.nameof.watermark.io.TableData;

/**
 * 调用方实现的分块数据源。{@link DatabaseWatermarker} 通过它按块读取数据，
 * 完全不关心底层是 JDBC ResultSet、CSV 流、内存列表还是其它任何形式。
 *
 * <p>实现要点：
 * <ul>
 *   <li>每次 {@link #nextChunk()} 返回的 {@link TableData} 必须包含列名
 *       （{@link TableData#getColumnNames()}），以便水印核心能识别字段。
 *   <li>块大小由实现自行决定，通常等于 {@code WatermarkConfig.getChunkSize()}。
 *   <li>实现需保证线程安全：{@link DatabaseWatermarker} 默认在单线程中顺序调用
 *       {@link #hasNext()} 与 {@link #nextChunk()}。
 *   <li>资源通过 {@link #close()} 释放；CSV / 内存等无需清理的实现可保留默认空实现。
 * </ul>
 *
 * <p>典型 JDBC 实现（仅供参考，非本库代码）：
 * <pre>{@code
 * public class JdbcChunkSource implements ChunkSource {
 *     private final Statement stmt;
 *     private final ResultSet rs;
 *     private final int chunkSize;
 *     private TableData pending;
 *
 *     public JdbcChunkSource(Connection conn, String table, int chunkSize) throws SQLException {
 *         this.chunkSize = chunkSize;
 *         this.stmt = conn.createStatement();
 *         this.rs = stmt.executeQuery("SELECT * FROM " + table);
 *     }
 *
 *     public boolean hasNext() throws SQLException {
 *         if (pending == null) loadNext();
 *         return pending != null;
 *     }
 *
 *     public TableData nextChunk() throws SQLException {
 *         if (pending == null) loadNext();
 *         TableData r = pending; pending = null; return r;
 *     }
 *
 *     private void loadNext() throws SQLException {
 *         List<String> cols = ...; // from ResultSetMetaData
 *         List<Map<String,Object>> rows = new ArrayList<>(chunkSize);
 *         for (int i = 0; i < chunkSize && rs.next(); i++) {
 *             Map<String,Object> row = new LinkedHashMap<>();
 *             for (String c : cols) row.put(c, rs.getObject(c));
 *             rows.add(row);
 *         }
 *         pending = rows.isEmpty() ? null : new TableData(null, cols, rows);
 *     }
 *
 *     public void close() throws Exception { rs.close(); stmt.close(); }
 * }
 * }</pre>
 *
 * @author chengpan
 */
public interface ChunkSource extends AutoCloseable {

    /**
     * 是否还有下一个数据块。
     *
     * <p>实现应在调用本方法前保证数据源已就绪（如 JDBC 已执行 SELECT、CSV 已打开），
     * 避免 {@link #nextChunk()} 调用时再阻塞执行查询。
     *
     * @return 若仍有数据块可读返回 {@code true}
     * @throws Exception 实现可抛出任意异常（典型如 {@code SQLException}、
     *         {@code IOException}）。{@link DatabaseWatermarker} 会原样向上抛出。
     */
    boolean hasNext() throws Exception;

    /**
     * 取出下一个数据块。
     *
     * <p>返回的 {@link TableData} 必须包含列名。块内行数由实现决定，
     * 通常等于 {@code WatermarkConfig.chunkSize}。
     *
     * <p>调用前应先用 {@link #hasNext()} 判断是否还有数据；
     * 数据耗尽后再次调用的行为未定义（可能返回 {@code null}、空表或抛异常，
     * 由实现决定）。
     *
     * @return 下一个数据块，永不为 {@code null}（耗尽前）
     * @throws Exception 同 {@link #hasNext()}
     */
    TableData nextChunk() throws Exception;

    /**
     * 资源清理。{@link DatabaseWatermarker} 在完成全部读写后会调用本方法，
     * 无论中途是否抛异常（通常通过 try-with-resources）。
     *
     * <p>CSV / 内存等无需清理的实现可保留默认空实现。
     */
    @Override
    default void close() throws Exception {
        // 默认空实现
    }
}