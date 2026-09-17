package io.github.nameof.watermark.it;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 集成测试用：把水印修改后的 row 列表写回 MySQL。
 *
 * <p>策略：用 {@code UPDATE ... SET col=?, ... WHERE pk=?} 逐行更新；
 * 主键列不更新（自增 ID 也不应该被改）。
 */
public final class TableUpdater {

    private TableUpdater() {}

    /**
     * 按主键把每行的指定列更新到数据库。
     *
     * @param conn          同一连接（不负责关闭）
     * @param tableName     表名
     * @param pkColumn      主键列名
     * @param modifiedRows  水印修改后的行（含 {@code pkColumn} 字段）
     * @param dirtyColumns  本次被水印修改过的列名（来自 {@code DatabaseEmbedResult.getInvolvedColumns()}）
     * @return 受影响的行数
     */
    public static int updateById(Connection conn,
                                 String tableName,
                                 String pkColumn,
                                 List<Map<String, Object>> modifiedRows,
                                 List<String> dirtyColumns) throws SQLException {
        if (modifiedRows.isEmpty() || dirtyColumns.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("UPDATE `").append(tableName).append("` SET ");
        for (int i = 0; i < dirtyColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("`").append(dirtyColumns.get(i)).append("` = ?");
        }
        sql.append(" WHERE `").append(pkColumn).append("` = ?");
        String sqlText = sql.toString();

        int affected = 0;
        try (PreparedStatement ps = conn.prepareStatement(sqlText)) {
            for (Map<String, Object> row : modifiedRows) {
                Object pkValue = row.get(pkColumn);
                if (pkValue == null) continue;
                int idx = 1;
                for (String col : dirtyColumns) {
                    ps.setObject(idx++, row.get(col));
                }
                ps.setObject(idx, pkValue);
                affected += ps.executeUpdate();
            }
        }
        return affected;
    }
}