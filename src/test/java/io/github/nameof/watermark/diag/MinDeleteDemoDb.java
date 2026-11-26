package io.github.nameof.watermark.diag;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;

import java.io.PrintWriter;
import java.math.BigDecimal;
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
 * 覆盖 **全部 5 个内置策略** + 自动选择路径，每个策略都跑同一套流程：
 *   BIT_CHINESE_ZERO_WIDTH   （中文零宽字符，数据：中文文本）
 *   BIT_LATIN_HOMOGLYPH      （拉丁同形字，  数据：含 a/e/o 等可替换字符的拉丁文本）
 *   BIT_NUMERIC_LSB          （数值末位微扰，数据：DECIMAL(12,2) 金额列）
 *   SIMPLE_SUFFIX_MARKER     （明文后缀标记，数据：中文文本）
 *   SIMPLE_INVISIBLE_PADDING （零宽填充，    数据：中文文本）
 *   自动选择（不指定 types）
 *
 * 另有「抗删梯度」：3 个 bit 策略 + 2 个 simple 策略各跑一次删中间 50%。
 *
 * 全程只使用临时表 watermark_delete_demo，结束自动 DROP，不影响任何业务表。
 * 运行：java io.github.nameof.watermark.diag.MinDeleteDemoDb <输出文件>
 */
public class MinDeleteDemoDb {

    static final String URL =
        "jdbc:mysql://localhost:3306/hxl"
        + "?useUnicode=true&characterEncoding=utf8"
        + "&useSSL=false&serverTimezone=Asia/Shanghai"
        + "&allowPublicKeyRetrieval=true";
    static final String USER = "root";
    static final String PASS = "root";
    /**
     * 临时表名。用独立名字（不复用旧 demo 的 watermark_delete_demo）：
     * 若 IDE 里另有一个同名 demo 进程残留，它的 finally 会 DROP 掉同名的表，
     * 导致本进程 CREATE 之后、INSERT 之前表被删（现象：DELETE 成功但 INSERT 报表不存在）。
     */
    static final String TABLE = "wm_diag_del_demo";
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

    /** 数据形态：决定建表数据与读写用的列 */
    static final String CN = "cn";
    static final String LATIN = "latin";
    static final String NUM = "num";

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

    /** 一个待验证场景：策略 + 数据形态。type == null 表示不指定策略（自动选择） */
    static class Case {
        final String name;
        final WatermarkType type;
        final String dataKind;

        Case(String name, WatermarkType type, String dataKind) {
            this.name = name;
            this.type = type;
            this.dataKind = dataKind;
        }

        List<WatermarkType> types() {
            return type == null ? null : Collections.singletonList(type);
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
                + " (id INT PRIMARY KEY, val VARCHAR(200), num DECIMAL(12,2)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.close();
            // 建表校验：排除"建成功但被别的进程删掉"的情况
            ResultSet chk = conn.createStatement().executeQuery("SHOW TABLES LIKE '" + TABLE + "'");
            boolean exists = chk.next();
            chk.close();
            p("已创建临时表 " + TABLE + "（" + ROWS + " 行）| 建表校验: "
                + (exists ? "存在" : "不存在（异常！）"));
            if (!exists) return;

            Watermarker wm = new Watermarker(new WatermarkConfig("demo-secret"));

            // ============ 全覆盖：每个策略都跑「嵌入 -> 写回 -> 删中间 10 行 -> 还原」 ============
            p("");
            p("========== 全覆盖：全部策略 + 自动选择（同一流程，删中间 10 行）==========");
            Case[] all = {
                new Case("BIT_CHINESE_ZERO_WIDTH", WatermarkType.BIT_CHINESE_ZERO_WIDTH, CN),
                new Case("BIT_LATIN_HOMOGLYPH", WatermarkType.BIT_LATIN_HOMOGLYPH, LATIN),
                new Case("BIT_NUMERIC_LSB", WatermarkType.BIT_NUMERIC_LSB, NUM),
                new Case("SIMPLE_SUFFIX_MARKER", WatermarkType.SIMPLE_SUFFIX_MARKER, CN),
                new Case("SIMPLE_INVISIBLE_PADDING", WatermarkType.SIMPLE_INVISIBLE_PADDING, CN),
                new Case("自动选择（不指定 types）", null, LATIN),
            };
            for (Case c : all) runCase(wm, conn, c);

            // ============ 抗删梯度：各策略删中间 50% ============
            p("");
            p("========== 抗删梯度：DELETE 中间 50%（id 300~899，剩 600 行）==========");
            Case[] bands = {
                new Case("BIT_CHINESE_ZERO_WIDTH", WatermarkType.BIT_CHINESE_ZERO_WIDTH, CN),
                new Case("BIT_LATIN_HOMOGLYPH", WatermarkType.BIT_LATIN_HOMOGLYPH, LATIN),
                new Case("BIT_NUMERIC_LSB", WatermarkType.BIT_NUMERIC_LSB, NUM),
                new Case("SIMPLE_SUFFIX_MARKER", WatermarkType.SIMPLE_SUFFIX_MARKER, CN),
                new Case("SIMPLE_INVISIBLE_PADDING", WatermarkType.SIMPLE_INVISIBLE_PADDING, CN),
            };
            for (Case c : bands) runHalfDeletion(wm, conn, c);
        } finally {
            try {
                conn.createStatement().execute("DROP TABLE IF EXISTS " + TABLE);
                p("");
                p("已清理临时表 " + TABLE);
            } catch (Exception ignore) { }
            conn.close();
        }
    }

    /** 跑一个场景的完整流程：嵌入 -> 写回 -> 删行前提取 -> 删中间 10 行 -> 再提取 */
    static void runCase(Watermarker wm, Connection conn, Case c) throws Exception {
        p("");
        p("---------- " + c.name + "（数据：" + kindDesc(c.dataKind) + "）----------");
        resetTable(conn, c.dataKind);
        List<WatermarkType> types = c.types();

        WatermarkResult<List<Object>> er = wm.embed(readVals(conn, c.dataKind), PAYLOAD, types);
        p("  1. 嵌入 success=" + er.isSuccess() + " repetition=" + er.getRepetition()
            + " 实际类型=" + er.getWatermarkType());
        if (!er.isSuccess()) {
            p("     失败原因: " + er.getMessage());
            p("     -> 跳过后续（未写入水印，删除测试无意义）");
            return;
        }

        writeBack(conn, er.getData(), c.dataKind);

        p("  2. 删行前提取（验证写回未丢水印）:");
        check(extract(wm, readVals(conn, c.dataKind), types));

        p("  3. 【人工删行】DELETE FROM " + TABLE + " WHERE id BETWEEN 200 AND 209;");
        int del = conn.createStatement().executeUpdate(
            "DELETE FROM " + TABLE + " WHERE id BETWEEN 200 AND 209");
        p("     实际删除 " + del + " 行（中间位置的 10 行）");

        p("  4. 重新 SELECT 并还原:");
        check(extract(wm, readVals(conn, c.dataKind), types));
    }

    /** 跑一个场景的抗删梯度：嵌入 -> 写回 -> 删中间 50% -> 还原 */
    static void runHalfDeletion(Watermarker wm, Connection conn, Case c) throws Exception {
        resetTable(conn, c.dataKind);
        List<WatermarkType> types = c.types();

        WatermarkResult<List<Object>> e = wm.embed(readVals(conn, c.dataKind), PAYLOAD, types);
        if (!e.isSuccess()) {
            p("  " + c.name + " : 嵌入失败 -> " + e.getMessage());
            return;
        }
        writeBack(conn, e.getData(), c.dataKind);

        int d = conn.createStatement().executeUpdate(
            "DELETE FROM " + TABLE + " WHERE id >= 300 AND id < 900");
        p("  " + c.name + "（删 " + d + " 行，剩 " + readVals(conn, c.dataKind).size() + " 行）:");
        check(extract(wm, readVals(conn, c.dataKind), types));
    }

    static WatermarkResult<String> extract(Watermarker wm, List<Object> vals, List<WatermarkType> types) {
        return types == null ? wm.extract(vals) : wm.extractValues(vals, types);
    }

    static String kindDesc(String kind) {
        if (NUM.equals(kind)) return "DECIMAL(12,2) 数值列";
        if (LATIN.equals(kind)) return "拉丁文本";
        return "中文文本";
    }

    /** 重建 ROWS 行数据（id 0 .. ROWS-1，与 writeBack 的下标约定一致） */
    static void resetTable(Connection conn, String kind) throws Exception {
        Statement st = conn.createStatement();
        st.execute("DELETE FROM " + TABLE);
        st.close();
        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO " + TABLE + " (id, val, num) VALUES (?, ?, ?)");
        for (int i = 0; i < ROWS; i++) {
            ps.setInt(1, i);
            ps.setString(2, textData(kind, i));
            // 步长 7：数值水印藏在末位（±1 微扰），末位量化后 floor(v/2) 仍要求互不相同，
            // 连续整数（步长 1）会两两碰撞 → 独立投票者腰斩。步长 7 可保证不碰撞。
            ps.setBigDecimal(3, new BigDecimal(10000L + i * 7L).setScale(2));
            ps.addBatch();
        }
        ps.executeBatch();
        ps.close();
    }

    /** 各场景的文本列数据：必须匹配目标策略的 canWatermark 约束 */
    static String textData(String kind, int i) {
        if (LATIN.equals(kind)) {
            // 含 a/e/o/x 等可替换字符，长度 >= 3，满足 Latin 策略
            return "user" + i + "_test_data";
        }
        // 中文：含 >= 2 个汉字，满足 Chinese / SuffixMarker / InvisiblePadding
        return "\u7528\u6237" + i + "\u6d4b\u8bd5"; // 用户i测试
    }

    /** 按场景读取目标列 -> List<Object>（与业务读库一致：数值列读 BigDecimal） */
    static List<Object> readVals(Connection conn, String kind) throws Exception {
        boolean numeric = NUM.equals(kind);
        String col = numeric ? "num" : "val";
        List<Object> vals = new ArrayList<Object>();
        ResultSet rs = conn.createStatement().executeQuery(
            "SELECT " + col + " FROM " + TABLE + " ORDER BY id");
        while (rs.next()) {
            vals.add(numeric ? (Object) rs.getBigDecimal(1) : (Object) rs.getString(1));
        }
        rs.close();
        return vals;
    }

    /**
     * UPDATE 目标列写回。
     * <p>下标 i 直接当 id：仅在「未删行」时调用（嵌入后立即写回），此时序列与 id 一一对应。</p>
     */
    static void writeBack(Connection conn, List<Object> marked, String kind) throws Exception {
        boolean numeric = NUM.equals(kind);
        String col = numeric ? "num" : "val";
        PreparedStatement ps = conn.prepareStatement(
            "UPDATE " + TABLE + " SET " + col + " = ? WHERE id = ?");
        for (int i = 0; i < marked.size(); i++) {
            Object v = marked.get(i);
            if (numeric) {
                ps.setBigDecimal(1, new BigDecimal(String.valueOf(v)));
            } else {
                ps.setString(1, String.valueOf(v));
            }
            ps.setInt(2, i);
            ps.addBatch();
        }
        ps.executeBatch();
        ps.close();
    }

    static void check(WatermarkResult<String> r) {
        if (r.isSuccess()) {
            p("     -> OK   还原 payload=\"" + r.getData()
                + "\"（置信度 " + String.format("%.1f", r.getConfidence()) + "%）");
        } else {
            p("     -> FAIL " + r.getMessage());
        }
    }

    static void p(String s) {
        out.println(s);
        System.out.println(s);
    }
}
