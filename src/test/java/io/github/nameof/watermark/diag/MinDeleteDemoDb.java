package io.github.nameof.watermark.diag;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 端到端最小删除测试（真实 MySQL 链路）：
 *
 *   嵌入 -> UPDATE 写回库 -> 【人工删行：DELETE FROM ... WHERE id BETWEEN 200 AND 209】
 *        -> 重新 SELECT -> 提取还原
 *
 * 两个场景跑同一套流程，唯一区别是水印档位：
 *   场景 A：bit 档   （BIT_CHINESE_ZERO_WIDTH，隐蔽水印）
 *   场景 B：simple 档（SIMPLE_SUFFIX_MARKER，明文标记）
 *
 * 全程只使用临时表 watermark_delete_demo，结束自动 DROP，不影响任何业务表。
 * 运行：java MinDeleteDemoDb <输出文件>
 */
public class MinDeleteDemoDb {

    static final String URL =
        "jdbc:mysql://localhost:3306/hxl"
        + "?useUnicode=true&characterEncoding=utf8"
        + "&useSSL=false&serverTimezone=Asia/Shanghai"
        + "&allowPublicKeyRetrieval=true";
    static final String USER = "root";
    static final String PASS = "root";
    static final String TABLE = "watermark_delete_demo";
    /**
     * 行数：需满足 bit 档容量要求。
     * <p>
     * 容量公式：槽位总数 = 头部 8 + 载荷 bits N，其中 N = (1 + payload字节数 + 4) × 8。
     * 本 demo 的 payload 是 6 个汉字 = 18 字节 → N = 184 → 槽位 192 个，
     * 要求 行数 / 192 ≥ minRepetition(5) → 行数 ≥ 960。取 1200 留余量（R ≈ 6）。
     * </p>
     */
    static final int ROWS = 1200;
    static final String PAYLOAD = "这是隐蔽信息";

    static PrintWriter out;

    public static void main(String[] args) throws Exception {
        out = new PrintWriter(args.length > 0 ? args[0] : "MinDeleteDemoDb-result.txt",
                StandardCharsets.UTF_8.name());
        try {
            run();
        } finally {
            out.close();
        }
    }

    static void run() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn;
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (Exception e) {
            p("MySQL 不可达，跳过: " + e.getMessage());
            return;
        }
        try {
            Statement st = conn.createStatement();
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE
                + " (id INT PRIMARY KEY, val VARCHAR(200)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.close();
            p("已创建临时表 " + TABLE + "（" + ROWS + " 行中文数据）");

            Watermarker wm = new Watermarker(new WatermarkConfig("demo-secret"));

            // ================= 场景 A：bit 档 =================
            p("");
            p("========== 场景 A：bit 档（隐蔽水印）==========");
            resetTable(conn);
            p("A1. 嵌入并 UPDATE 写回库 ...");
            WatermarkResult<List<Object>> er = wm.embed(
                readVals(conn), PAYLOAD,
                Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
            p("    嵌入 success=" + er.isSuccess() + " repetition=" + er.getRepetition());
            if (!er.isSuccess()) {
                p("    嵌入失败原因: " + er.getMessage());
                p("    -> 跳过 A2~A4（未写入水印，删除测试无意义）");
            } else {
                writeBack(conn, er.getData());

                p("A2. 删行前先提取一次（验证写回没丢水印）:");
                check(wm.extract(readVals(conn)));

                p("A3. 【人工删行】执行 SQL: DELETE FROM " + TABLE + " WHERE id BETWEEN 200 AND 209;");
                int del = conn.createStatement().executeUpdate(
                    "DELETE FROM " + TABLE + " WHERE id BETWEEN 200 AND 209");
                p("    实际删除 " + del + " 行（中间位置的 10 行）");

                p("A4. 重新 SELECT 全表并提取还原:");
                check(wm.extract(readVals(conn)));
            }

            // ================= 场景 A2：删除比例梯度 =================
            // 哈希分桶下"删中间连续段"与"随机删"对槽位的影响等价（槽位由内容决定、与序号无关），
            // 故用纯 SQL 删连续段来量化抗删边界；每个比例都重新嵌入一份干净数据。
            p("");
            p("========== 场景 A2：bit 档抗删比例梯度（每次重新嵌入，SQL 删中间段）==========");
            int[][] bands = {{540, 660, 10}, {420, 780, 30}, {300, 900, 50}};
            for (int[] band : bands) {
                resetTable(conn);
                WatermarkResult<List<Object>> e = wm.embed(
                    readVals(conn), PAYLOAD,
                    Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
                if (!e.isSuccess()) {
                    p("  删 " + band[2] + "% : 嵌入失败 -> " + e.getMessage());
                    continue;
                }
                writeBack(conn, e.getData());
                int d = conn.createStatement().executeUpdate(
                    "DELETE FROM " + TABLE + " WHERE id >= " + band[0] + " AND id < " + band[1]);
                p("  删 " + band[2] + "%（SQL 删 id " + band[0] + "~" + (band[1] - 1)
                    + "，实际 " + d + " 行，剩 " + readVals(conn).size() + " 行）:");
                check(wm.extract(readVals(conn)));
            }

            // ================= 场景 B：simple 档 =================
            p("");
            p("========== 场景 B：simple 档（同一流程对照）==========");
            resetTable(conn);
            p("B1. 嵌入并 UPDATE 写回库 ...");
            WatermarkResult<List<Object>> er2 = wm.embed(
                readVals(conn), PAYLOAD,
                Collections.singletonList(WatermarkType.SIMPLE_SUFFIX_MARKER));
            p("    嵌入 success=" + er2.isSuccess());
            if (!er2.isSuccess()) {
                p("    嵌入失败原因: " + er2.getMessage());
                p("    -> 跳过 B2~B4");
            } else {
                writeBack(conn, er2.getData());

                p("B2. 删行前先提取一次:");
                check(wm.extract(readVals(conn)));

                p("B3. 【人工删行】执行同样的 SQL（中间 10 行）...");
                int del2 = conn.createStatement().executeUpdate(
                    "DELETE FROM " + TABLE + " WHERE id BETWEEN 200 AND 209");
                p("    实际删除 " + del2 + " 行");

                p("B4. 重新 SELECT 全表并提取还原:");
                check(wm.extract(readVals(conn)));
            }
        } finally {
            try {
                conn.createStatement().execute("DROP TABLE IF EXISTS " + TABLE);
                p("");
                p("已清理临时表 " + TABLE);
            } catch (Exception ignore) { }
            conn.close();
        }
    }

    /** 重建 ROWS 行纯中文数据（id 0 .. ROWS-1，与 writeBack 的下标约定一致） */
    static void resetTable(Connection conn) throws Exception {
        Statement st = conn.createStatement();
        st.execute("DELETE FROM " + TABLE);
        st.close();
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)");
        for (int i = 0; i < ROWS; i++) {
            ps.setInt(1, i);
            ps.setString(2, "\u7528\u6237" + i + "\u6d4b\u8bd5"); // 用户i测试
            ps.addBatch();
        }
        ps.executeBatch();
        ps.close();
    }

    /** SELECT val ... ORDER BY id -> List<Object>（与业务读库完全一致） */
    static List<Object> readVals(Connection conn) throws Exception {
        List<Object> vals = new ArrayList<Object>();
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT val FROM " + TABLE + " ORDER BY id");
        while (rs.next()) vals.add(rs.getString(1));
        rs.close();
        return vals;
    }

    /** UPDATE val=? WHERE id=? 逐行写回 */
    static void writeBack(Connection conn, List<Object> marked) throws Exception {
        PreparedStatement ps = conn.prepareStatement(
            "UPDATE " + TABLE + " SET val = ? WHERE id = ?");
        for (int i = 0; i < marked.size(); i++) {
            ps.setString(1, String.valueOf(marked.get(i)));
            ps.setInt(2, i);
            ps.addBatch();
        }
        ps.executeBatch();
        ps.close();
    }

    static void check(WatermarkResult<String> r) {
        if (r.isSuccess()) {
            p("    -> OK   还原 payload=\"" + r.getData()
                + "\"（置信度 " + String.format("%.1f", r.getConfidence()) + "%）");
        } else {
            p("    -> FAIL " + r.getMessage());
        }
    }

    static void p(String s) {
        out.println(s);
        System.out.println(s);
    }
}
