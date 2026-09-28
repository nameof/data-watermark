package io.github.nameof.watermark.diag;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;
import io.github.nameof.watermark.core.bit.BitEmbedResult;
import io.github.nameof.watermark.core.bit.ChineseTextWatermarkStrategy;
import io.github.nameof.watermark.core.bit.LatinTextWatermarkStrategy;
import io.github.nameof.watermark.core.bit.NumericWatermarkStrategy;
import io.github.nameof.watermark.core.simple.InvisiblePaddingStrategy;
import io.github.nameof.watermark.core.simple.SuffixMarkerStrategy;
import io.github.nameof.watermark.database.DatabaseEmbedResult;
import io.github.nameof.watermark.database.DatabaseExtractResult;
import io.github.nameof.watermark.database.DatabaseWatermarker;
import io.github.nameof.watermark.io.TableData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ADR-0001 "Seed 约定" 删除鲁棒性诊断。只用真实库类 + 真实策略，不 mock 任何东西。
 * 输出 UTF-8 文本到 args[0]。
 */
public class SeedDiag {

    static final String SECRET = "diag-secret";
    static final String PAYLOAD = "test"; // 4 字节 -> encode 后 9 字节 -> N = 72 bits
    static final StringBuilder LOG = new StringBuilder();

    static void p(String s) {
        LOG.append(s).append('\n');
    }

    /** 纯中文值，确保 bit-level 路由到 ChineseText 策略（不含拉丁同形字映射表中的字符） */
    static List<Object> values(int n) {
        List<Object> v = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) {
            v.add("\u7528\u6237" + i + "\u6d4b\u8bd5"); // 用户<i>测试
        }
        return v;
    }

    static List<Object> copy(List<?> in) {
        return new ArrayList<Object>(in);
    }

    static List<Object> deleteAt(List<?> in, int idx) {
        List<Object> r = copy(in);
        r.remove(idx);
        return r;
    }

    static List<Object> deleteRandom(List<?> in, double fraction, long seed) {
        List<Object> r = copy(in);
        Random rnd = new Random(seed);
        int toDelete = (int) (r.size() * fraction);
        for (int k = 0; k < toDelete; k++) {
            r.remove(rnd.nextInt(r.size()));
        }
        return r;
    }

    static void show(String label, WatermarkResult<String> r) {
        p(String.format("  %-42s success=%-5s payload=%-8s conf=%.1f%%  msg=%s",
                label, r.isSuccess(), r.isSuccess() ? "\"" + r.getData() + "\"" : "-",
                r.getConfidence(), r.isSuccess() ? "-" : r.getMessage()));
    }

    public static void main(String[] args) throws Exception {
        Watermarker wm = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> zh = Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH);
        List<WatermarkType> suffix = Collections.singletonList(WatermarkType.SIMPLE_SUFFIX_MARKER);

        // ---------- 准备：bit-level 嵌入 ----------
        List<Object> orig = values(500);
        WatermarkResult<List<Object>> er = wm.embed(orig, PAYLOAD, zh);
        p("=== SETUP ===");
        p("bit-level embed : success=" + er.isSuccess() + " repetition=" + er.getRepetition()
                + " type=" + er.getWatermarkType() + " msg=" + er.getMessage());
        List<Object> marked = copy(er.getData());
        p("原序列 500 个值，显式策略 = BIT_CHINESE_ZERO_WIDTH，payload = \"" + PAYLOAD + "\"");
        p("");

        p("=== E0 现有测试走的到底是哪条路径？ ===");
        WatermarkResult<List<Object>> auto = wm.embed(values(500), "operator:zhangsan|company:ACME");
        p("  testEmbedExtractRoundTripBitLevel: 500 值 + 31 字节载荷 + 自动选策略 -> type="
                + auto.getWatermarkType() + " repetition=" + auto.getRepetition()
                + "   (SIMPLE 说明自动降级，并未走 bit-level)");
        p("  testValuesApiSurvivesPartialDeletion 删的是【尾部】30%，索引未变，所以通过");
        p("");

        p("=== E1 基线（不删任何行）===");
        show("no deletion", wm.extract(marked));
        p("");

        p("=== E2 删除【中间】1 行（模拟业务删行，与尾删对照）===");
        show("delete index 100 (middle)", wm.extract(deleteAt(marked, 100)));
        show("delete index 250 (middle)", wm.extract(deleteAt(marked, 250)));
        show("delete index 0   (head)", wm.extract(deleteAt(marked, 0)));
        show("delete index 23  (last header cell)", wm.extract(deleteAt(marked, 23)));
        show("delete index 499 (tail, 索引不变)", wm.extract(deleteAt(marked, 499)));
        show("delete tail 3 rows (index 497..499)", wm.extract(marked.subList(0, 497)));
        p("");

        p("=== E3 按比例删除 ===");
        show("keep first 70%  (只砍尾部 30%)", wm.extract(copy(marked.subList(0, 350))));
        int successRandom = 0;
        for (long s = 0; s < 10; s++) {
            WatermarkResult<String> r = wm.extract(deleteRandom(marked, 0.30, s));
            if (r.isSuccess()) successRandom++;
        }
        p("  keep 70% but randomly scattered (10 trials) : success " + successRandom + "/10");
        p("");

        p("=== E4 顺序扰动（不删除，只换序）===");
        List<Object> swapped = copy(marked);
        Object t = swapped.get(100);
        swapped.set(100, swapped.get(101));
        swapped.set(101, t);
        show("swap index 100 <-> 101", wm.extract(swapped));
        List<Object> rotated = copy(marked);
        rotated.add(rotated.remove(0));
        show("rotate left by 1 (row moved head->tail)", wm.extract(rotated));
        p("");

        p("=== E5 只改值不删行（索引/行数都不变，把水印洗掉）===");
        show("overwrite index 0        (毁 1 个头部单元)", wm.extract(overwrite(marked, 0, 1)));
        show("overwrite index 0..23    (全部 24 个头部单元)", wm.extract(overwrite(marked, 0, 24)));
        show("overwrite index 24..73   (载荷区 50 个)", wm.extract(overwrite(marked, 24, 50)));
        show("overwrite index 100..199 (载荷区 100 个)", wm.extract(overwrite(marked, 100, 100)));
        show("overwrite index 100..294 (载荷区 195 个)", wm.extract(overwrite(marked, 100, 195)));
        p("");

        p("=== E6 删除行数的临界扫描：随机删 k 行，20 次试验的成功次数 ===");
        for (int k = 1; k <= 10; k++) {
            int ok = 0;
            for (long s = 0; s < 20; s++) {
                List<Object> r3 = copy(marked);
                Random rnd = new Random(s * 100 + k);
                for (int j = 0; j < k; j++) r3.remove(rnd.nextInt(r3.size()));
                if (wm.extract(r3).isSuccess()) ok++;
            }
            p("  delete k=" + k + " random rows -> success " + ok + "/20");
        }
        p("");

        // ---------- E7 simple 模式对照 ----------
        p("=== E7 simple 模式（SuffixMarker）同样删行 ===");
        List<Object> s20 = values(20);
        WatermarkResult<List<Object>> ser = wm.embed(s20, PAYLOAD, suffix);
        p("simple embed: success=" + ser.isSuccess() + " type=" + ser.getWatermarkType());
        List<Object> smarked = copy(ser.getData());
        show("no deletion", wm.extract(smarked));
        show("delete 30% random", wm.extract(deleteRandom(smarked, 0.30, 7)));
        show("delete first 25% (head)", wm.extract(copy(smarked.subList(5, 20))));
        show("delete 50% random", wm.extract(deleteRandom(smarked, 0.50, 7)));
        p("");

        // ---------- E8 证明 extract 根本不看 seed/secret ----------
        p("=== E8 单策略层面：embed 用 seed=S1，extract 换成 S2 / 换 secret 会怎样？ ===");
        ChineseTextWatermarkStrategy zs = new ChineseTextWatermarkStrategy();
        SuffixMarkerStrategy ss = new SuffixMarkerStrategy();
        NumericWatermarkStrategy ns = new NumericWatermarkStrategy();
        InvisiblePaddingStrategy ip = new InvisiblePaddingStrategy();
        String txt = "\u5f20\u4e09\u6d4b\u8bd5\u6570\u636e"; // 张三测试数据

        BitEmbedResult z1 = zs.embed(txt, 1, SECRET, 111L);
        BitEmbedResult z0 = zs.embed(txt, 0, SECRET, 111L);
        p("  ChineseText   bit=1 embed(seed=111) -> extract(seed=111)=" + zs.extract(z1.getValue(), SECRET, 111L)
                + "  extract(seed=999999)=" + zs.extract(z1.getValue(), SECRET, 999999L)
                + "  extract(other-secret)=" + zs.extract(z1.getValue(), "other-secret", 111L));
        p("  ChineseText   bit=0 embed(seed=111) -> extract(seed=111)=" + zs.extract(z0.getValue(), SECRET, 111L)
                + "  extract(seed=999999)=" + zs.extract(z0.getValue(), SECRET, 999999L)
                + "  extract(other-secret)=" + zs.extract(z0.getValue(), "other-secret", 111L));
        p("  -> 插入位置确实随 seed 变，但 extract 扫描全文找第一个零宽字符，不看 seed/secret");

        String s1 = ss.embed(txt, PAYLOAD, SECRET, 111L);
        p("  SuffixMarker  extract(seed=111)=" + ss.extract(s1, SECRET, 111L)
                + "  extract(seed=999999)=" + ss.extract(s1, SECRET, 999999L)
                + "  extract(other-secret)=" + ss.extract(s1, "other-secret", 111L));

        BitEmbedResult n1 = ns.embed(12345.6, 1, SECRET, 111L);
        p("  Numeric       extract(seed=111)=" + ns.extract(n1.getValue(), SECRET, 111L)
                + "  extract(seed=999999)=" + ns.extract(n1.getValue(), SECRET, 999999L)
                + "  embed 端也完全不用 seed（只看奇偶）");

        String p1 = ip.embed(txt, PAYLOAD, SECRET, 111L);
        p("  InvisiblePad  extract(seed=111)=" + ip.extract(p1, SECRET, 111L)
                + "  extract(seed=999999)=" + ip.extract(p1, SECRET, 999999L));
        p("");

        // ---------- E11 显式策略与 extract 路由不一致？ ----------
        p("=== E11 显式指定中文策略、但值里含拉丁字母（真实业务列常见）===");
        List<Object> mixed = new ArrayList<Object>();
        for (int i = 0; i < 500; i++) mixed.add("user" + i + "_test_data");
        WatermarkResult<List<Object>> me = wm.embed(mixed, PAYLOAD, zh);
        p("  embed (types=[BIT_CHINESE_ZERO_WIDTH]) : success=" + me.isSuccess()
                + " type=" + me.getWatermarkType() + " R=" + me.getRepetition());
        show("  extract（无策略参数）", wm.extract(copy(me.getData())));
        String probe = (String) me.getData().get(50);
        LatinTextWatermarkStrategy ls = new LatinTextWatermarkStrategy();
        p("  第 50 个值的路由探测: Latin.canWatermark=" + ls.canWatermark(probe)
                + " -> Latin.extract=" + ls.extract(probe, SECRET, 0)
                + " | Chinese.canWatermark=" + zs.canWatermark(probe)
                + " -> Chinese.extract=" + zs.extract(probe, SECRET, 0));
        p("  -> embed 按 types 过滤（走了 Chinese），extract 用 findAnyBitStrategy 不按 types 过滤（走了 Latin）");
        p("");

        p("=== E9 PoC：交叉分配的位槽从「位置取模」改成「稳定键哈希」后，删 50% 是否还能还原 ===");
        String pl = "WM2026";
        byte[] pbytes = pl.getBytes(StandardCharsets.UTF_8);
        int N = pbytes.length * 8;
        int[] bits = new int[N];
        for (int i = 0; i < N; i++) {
            bits[i] = (pbytes[i / 8] >> (7 - (i % 8))) & 1;
        }
        p("  payload=\"" + pl + "\" -> N=" + N + " bits, 500 个单元格");

        // (a) 位置取模（当前实现的做法）
        List<Object> cellVals = values(500);
        List<Object> posMarked = new ArrayList<Object>(500);
        for (int i = 0; i < 500; i++) {
            posMarked.add(zs.embed(cellVals.get(i), bits[i % N], SECRET, i).getValue());
        }
        List<Object> posDeleted = deleteRandom(posMarked, 0.50, 42);
        int[] recPos = vote(posDeleted, N, false);
        p("  (a) 位置取模  删 50% -> 还原 = \"" + new String(bitsToBytes(recPos), StandardCharsets.UTF_8)
                + "\"  " + (pl.equals(new String(bitsToBytes(recPos), StandardCharsets.UTF_8)) ? "OK" : "FAIL"));

        // (b) 稳定键哈希分桶
        List<Object> keyMarked = new ArrayList<Object>(500);
        for (int i = 0; i < 500; i++) {
            String key = (String) cellVals.get(i);
            int bucket = bucketOf(key, SECRET, N);
            keyMarked.add(zs.embed(key, bits[bucket], SECRET, stableHash(key)).getValue());
        }
        List<Object> keyDeleted = deleteRandom(keyMarked, 0.50, 42);
        int[] recKey = vote(keyDeleted, N, true);
        String got = new String(bitsToBytes(recKey), StandardCharsets.UTF_8);
        p("  (b) 键哈希分桶 删 50% -> 还原 = \"" + got + "\"  " + (pl.equals(got) ? "OK" : "FAIL"));

        // (c) 位置取模 + 只删尾部（当前测试覆盖的场景）
        List<Object> posTail = copy(posMarked.subList(0, 250));
        int[] recTail = vote(posTail, N, false);
        String gotTail = new String(bitsToBytes(recTail), StandardCharsets.UTF_8);
        p("  (c) 位置取模  只删尾部 50% -> 还原 = \"" + gotTail + "\"  " + (pl.equals(gotTail) ? "OK" : "FAIL"));
        p("");

        // ---------- E10 用户真实场景：DatabaseWatermarker 分块路径 ----------
        p("=== E10 Database 分块路径（5 块 x 500 行，每块独立嵌完整载荷，跨块多数投票）===");
        WatermarkConfig dbCfg = new WatermarkConfig(SECRET, 5, 500);
        DatabaseWatermarker dbw = new DatabaseWatermarker(dbCfg);

        List<List<Map<String, Object>>> chunks = new ArrayList<List<Map<String, Object>>>();
        for (int c = 0; c < 5; c++) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object v : values(500)) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("name", v);
                rows.add(row);
            }
            DatabaseEmbedResult der =
                    dbw.embed(new TableData("t", Collections.singletonList("name"), rows), PAYLOAD);
            p("  chunk " + c + " embed: rowsModified=" + der.getRowsModified()
                    + " cells=" + der.getCellsModified() + " cols=" + der.getInvolvedColumns()
                    + " usage=" + der.getStrategyUsage());
            chunks.add(der.getModifiedTable().getRows());
        }
        p("");
        p("  (a) 不动任何行                 -> " + dbExtract(chunks));
        p("  (b) 只删第 3 块中间 1 行        -> " + dbExtract(deleteInChunks(chunks, new int[] {2}, 100, 1)));
        p("  (c) 每块都删中间 1 行           -> " + dbExtract(deleteInChunks(chunks, new int[] {0, 1, 2, 3, 4}, 100, 1)));
        p("  (d) 每块各删 5 行（分散）        -> " + dbExtract(deleteInChunks(chunks, new int[] {0, 1, 2, 3, 4}, 100, 5)));
        p("  (e) 每块只删最后 1 行           -> " + dbExtract(tailDeleteChunks(chunks, 1)));
        p("  (f) 提取时改成每块 400 行       -> " + dbExtract(rechunk(chunks, 400)));
        p("  (g) 提取时改成每块 600 行       -> " + dbExtract(rechunk(chunks, 600)));
        p("");

        Files.write(Paths.get(args[0]), LOG.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 把 [start, start+count) 区间的值替换成无水印值，行数/索引不变 */
    static List<Object> overwrite(List<?> in, int start, int count) {
        List<Object> r = copy(in);
        for (int i = start; i < start + count && i < r.size(); i++) {
            r.set(i, "\u7834\u574f\u503c" + i); // 破坏值<i>
        }
        return r;
    }

    // ---------- E10 辅助 ----------

    /** 逐块走真实 DatabaseWatermarker.extract(TableData)，再跨块聚合 */
    static String dbExtract(List<List<Map<String, Object>>> chunks) {
        DatabaseWatermarker dbw = new DatabaseWatermarker(new WatermarkConfig(SECRET, 5, 500));
        List<DatabaseExtractResult> parts = new ArrayList<DatabaseExtractResult>();
        StringBuilder per = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DatabaseExtractResult r = dbw.extract(
                    new TableData("t", Collections.singletonList("name"), chunks.get(i)));
            parts.add(r);
            per.append(i).append(':')
               .append(r.getPayload().isPresent() ? r.getPayload().get() : "未还原")
               .append(' ');
        }
        DatabaseExtractResult agg = DatabaseWatermarker.aggregateExtract(parts);
        return String.format("perChunk[%s] -> aggregated=%s conf=%.0f%%",
                per.toString().trim(),
                agg.getPayload().isPresent() ? "\"" + agg.getPayload().get() + "\"" : "<none>",
                agg.getConfidence());
    }

    static List<List<Map<String, Object>>> copyChunks(List<List<Map<String, Object>>> src) {
        List<List<Map<String, Object>>> out = new ArrayList<List<Map<String, Object>>>();
        for (List<Map<String, Object>> r : src) {
            out.add(new ArrayList<Map<String, Object>>(r));
        }
        return out;
    }

    /** 在指定块内从 startIdx 起每隔 30 行删 1 行，共 k 行 */
    static List<List<Map<String, Object>>> deleteInChunks(List<List<Map<String, Object>>> src,
                                                          int[] which, int startIdx, int k) {
        List<List<Map<String, Object>>> out = copyChunks(src);
        for (int wi : which) {
            List<Map<String, Object>> rows = out.get(wi);
            for (int j = 0; j < k; j++) {
                int idx = startIdx + j * 30;
                if (idx < rows.size()) rows.remove(idx);
            }
        }
        return out;
    }

    static List<List<Map<String, Object>>> tailDeleteChunks(List<List<Map<String, Object>>> src, int k) {
        List<List<Map<String, Object>>> out = copyChunks(src);
        for (List<Map<String, Object>> rows : out) {
            for (int j = 0; j < k && !rows.isEmpty(); j++) rows.remove(rows.size() - 1);
        }
        return out;
    }

    /** 把全部行按新块大小重新分组（模拟调用方换了 chunkSize / 换了 SELECT 分页） */
    static List<List<Map<String, Object>>> rechunk(List<List<Map<String, Object>>> src, int size) {
        List<Map<String, Object>> flat = new ArrayList<Map<String, Object>>();
        for (List<Map<String, Object>> r : src) flat.addAll(r);
        List<List<Map<String, Object>>> out = new ArrayList<List<Map<String, Object>>>();
        for (int i = 0; i < flat.size(); i += size) {
            out.add(new ArrayList<Map<String, Object>>(flat.subList(i, Math.min(flat.size(), i + size))));
        }
        return out;
    }

    /** 按位槽投票还原 N 个 bit；byKey=true 时用「去掉水印字符后的稳定键」算槽位 */    static int[] vote(List<Object> vals, int n, boolean byKey) {
        int[] ones = new int[n];
        int[] total = new int[n];
        ChineseTextWatermarkStrategy zs = new ChineseTextWatermarkStrategy();
        for (int i = 0; i < vals.size(); i++) {
            String v = (String) vals.get(i);
            int bucket = byKey ? bucketOf(stripZeroWidth(v), SECRET, n) : i % n;
            int bit = zs.extract(v, SECRET, 12345L);
            if (bit >= 0) {
                total[bucket]++;
                if (bit == 1) ones[bucket]++;
            }
        }
        int[] out = new int[n];
        for (int b = 0; b < n; b++) {
            out[b] = (total[b] > 0 && ones[b] > total[b] / 2) ? 1 : 0;
        }
        return out;
    }

    static String stripZeroWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '\u200B' || c > '\u200F') sb.append(c);
        }
        return sb.toString();
    }

    static int bucketOf(String key, String secret, int n) {
        long h = secret.hashCode();
        h = h * 31 + stableHash(key);
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = h ^ (h >>> 16);
        return (int) Math.floorMod(h, n);
    }

    static long stableHash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    static byte[] bitsToBytes(int[] bits) {
        byte[] out = new byte[bits.length / 8];
        for (int i = 0; i < out.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) b = (b << 1) | bits[i * 8 + j];
            out[i] = (byte) b;
        }
        return out;
    }
}
