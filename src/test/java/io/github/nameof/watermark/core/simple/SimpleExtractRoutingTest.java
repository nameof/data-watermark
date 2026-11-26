package io.github.nameof.watermark.core.simple;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;
import io.github.nameof.watermark.database.ChunkSource;
import io.github.nameof.watermark.database.DatabaseEmbedResult;
import io.github.nameof.watermark.database.DatabaseExtractResult;
import io.github.nameof.watermark.database.DatabaseWatermarker;
import io.github.nameof.watermark.io.TableData;
import org.junit.Test;

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
 * P0 回归：simple 档提取路由（痕迹探测 + 短路投票）。
 *
 * <p>修复前的缺陷链：顶层 {@code extract(table, columns, types)} 把 {@code types}
 * 丢弃（只透传给 bit 分支）；{@code extractSimple} 用 {@code findAnySimpleStrategy}
 * 按 {@code canWatermark} 取第一个策略——而 {@code SuffixMarkerStrategy} 注册在前
 * 且对任意非空字符串返回 true，导致显式用 {@code SIMPLE_INVISIBLE_PADDING} 嵌入的
 * 数据在提取时被误判给 SuffixMarker（找不到 {@code [::} 标记 → 0 票 → 失败）。
 * 历史佐证：{@code diag/InvisiblePaddingCheck-result.txt} 两种提取均 success=false。</p>
 *
 * <p>修复后：提取端不再用 {@code canWatermark} 做前置路由，而是逐个调用候选策略的
 * {@code extract}，由策略自身的水印痕迹判断消歧（第一个非 null 即采纳，每格至多 1 票）；
 * 水印类型按实际命中的策略多数票判定。</p>
 */
public class SimpleExtractRoutingTest {

    private static final String PAYLOAD = "wm-routing-2026";
    private static final String SECRET = "routing-test-secret";
    private static final List<String> COLS = Arrays.asList("name", "remark");

    private static List<Map<String, Object>> stringTable(int rows) {
        List<Map<String, Object>> table = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "用户" + i);
            row.put("remark", "备注内容" + i + "号");
            table.add(row);
        }
        return table;
    }

    // ==================== 用例 1/2/5：显式 InvisiblePadding 的提取路由 ====================

    @Test
    public void explicitInvisiblePadding_roundTripsWithSameTypes() {
        Watermarker wm = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> ip = Collections.singletonList(WatermarkType.SIMPLE_INVISIBLE_PADDING);

        WatermarkResult<List<Map<String, Object>>> embed = wm.embed(stringTable(50), COLS, PAYLOAD, ip);
        assertTrue("嵌入应成功: " + embed.getMessage(), embed.isSuccess());

        // 三参提取：types 与嵌入端一致（修复前：types 被 extractSimple 丢弃 → 必失败）
        WatermarkResult<String> extract = wm.extract(embed.getData(), COLS, ip);
        assertTrue("显式同 types 提取应成功: " + extract.getMessage(), extract.isSuccess());
        assertEquals(PAYLOAD, extract.getData());
    }

    @Test
    public void explicitInvisiblePadding_roundTripsWithoutTypes() {
        Watermarker wm = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> ip = Collections.singletonList(WatermarkType.SIMPLE_INVISIBLE_PADDING);

        WatermarkResult<List<Map<String, Object>>> embed = wm.embed(stringTable(50), COLS, PAYLOAD, ip);
        assertTrue(embed.isSuccess());

        // 二参提取（types=null → 候选全集）：已落库的老数据无需重嵌即可读出
        // （修复前：按 canWatermark 先命中 SuffixMarker，对零宽填充值找不到 [:: → 失败）
        WatermarkResult<String> extract = wm.extract(embed.getData(), COLS);
        assertTrue("不指定 types 提取也应成功: " + extract.getMessage(), extract.isSuccess());
        assertEquals(PAYLOAD, extract.getData());
    }

    @Test
    public void detectType_matchesActualStrategy() {
        Watermarker wm = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> ip = Collections.singletonList(WatermarkType.SIMPLE_INVISIBLE_PADDING);

        WatermarkResult<List<Map<String, Object>>> embed = wm.embed(stringTable(50), COLS, PAYLOAD, ip);
        assertTrue(embed.isSuccess());

        WatermarkResult<String> extract = wm.extract(embed.getData(), COLS);
        assertTrue(extract.isSuccess());
        // 修复前 detectSimpleWatermarkType 按 canWatermark 取第一个 → 恒误报 SUFFIX_MARKER
        assertEquals("水印类型应与实际命中策略一致",
                WatermarkType.SIMPLE_INVISIBLE_PADDING, extract.getWatermarkType());
    }

    // ==================== 用例 3/4：自动路径与混合 types ====================

    @Test
    public void autoPath_stillRoundTrips() {
        Watermarker wm = new Watermarker(new WatermarkConfig(SECRET));

        // 20 行 × 2 列 = 40 个独立投票者，bit 档容量不足 → 自动降级 simple（SuffixMarker 先命中）
        WatermarkResult<List<Map<String, Object>>> embed = wm.embed(stringTable(20), COLS, PAYLOAD);
        assertTrue("自动路径嵌入应成功: " + embed.getMessage(), embed.isSuccess());

        WatermarkResult<String> extract = wm.extract(embed.getData(), COLS);
        assertTrue("自动路径提取应成功: " + extract.getMessage(), extract.isSuccess());
        assertEquals(PAYLOAD, extract.getData());
        assertEquals("自动降级 simple 的数据应由先注册的 SuffixMarker 承载",
                WatermarkType.SIMPLE_SUFFIX_MARKER, extract.getWatermarkType());
    }

    @Test
    public void mixedSimpleTypes_firstRegisteredWins() {
        Watermarker wm = new Watermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> both = Arrays.asList(
                WatermarkType.SIMPLE_SUFFIX_MARKER, WatermarkType.SIMPLE_INVISIBLE_PADDING);

        WatermarkResult<List<Map<String, Object>>> embed = wm.embed(stringTable(30), COLS, PAYLOAD, both);
        assertTrue(embed.isSuccess());

        WatermarkResult<String> extract = wm.extract(embed.getData(), COLS);
        assertTrue(extract.isSuccess());
        assertEquals(PAYLOAD, extract.getData());
        assertEquals("混合 types 下嵌入端按注册顺序先命中 SuffixMarker，提取类型应一致",
                WatermarkType.SIMPLE_SUFFIX_MARKER, extract.getWatermarkType());
    }

    // ==================== 用例 6：bit 档数据不被误判为 simple ====================

    @Test
    public void bitLevelZeroWidthData_notMisdetectedAsSimple() {
        // bit 档每格只插 1 个零宽字符：SuffixMarker 找不到 [:: → null；
        // InvisiblePadding 因 1 % 8 != 0 → null → simple 分支 0 票；
        // 错误密钥下 bit 分支 CRC32 失败 → 整体提取失败
        List<Map<String, Object>> table = stringTable(1000);

        WatermarkResult<List<Map<String, Object>>> embedded =
                new Watermarker(new WatermarkConfig("correct-secret")).embed(
                        table, COLS, PAYLOAD,
                        Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH));
        assertTrue(embedded.isSuccess());

        Watermarker wrongKeyMarker = new Watermarker(new WatermarkConfig("wrong-secret"));
        WatermarkResult<String> result = wrongKeyMarker.extract(embedded.getData(), COLS);
        assertFalse("bit 档数据 + 错误密钥：simple 分支不应误投出载荷，整体应失败",
                result.isSuccess());
    }

    // ==================== 用例 7：Database 层新增的 types 重载 ====================

    @Test
    public void databaseLayer_explicitInvisiblePadding_roundTrips() {
        DatabaseWatermarker wm = new DatabaseWatermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> ip = Collections.singletonList(WatermarkType.SIMPLE_INVISIBLE_PADDING);
        TableData table = new TableData("routing_test", new ArrayList<>(COLS), stringTable(20));

        DatabaseEmbedResult embed = wm.embed(table, PAYLOAD, ip);
        assertNotNull("modifiedTable should be set on success", embed.getModifiedTable());

        // 带 types 重载（修复前 Database 层只有 2 参 extract，无法补救 P0）
        DatabaseExtractResult extract = wm.extract(embed.getModifiedTable(), ip);
        assertTrue("DB 层带 types 提取应恢复载荷", extract.getPayload().isPresent());
        assertEquals(PAYLOAD, extract.getPayload().get());

        // 2 参提取（types=null）也应成功 —— 老数据无需重嵌
        DatabaseExtractResult extractNoTypes = wm.extract(embed.getModifiedTable());
        assertTrue("DB 层 2 参提取也应恢复载荷", extractNoTypes.getPayload().isPresent());
        assertEquals(PAYLOAD, extractNoTypes.getPayload().get());
    }

    @Test
    public void databaseLayer_chunkSourceWithTypes_roundTrips() throws Exception {
        DatabaseWatermarker wm = new DatabaseWatermarker(new WatermarkConfig(SECRET));
        List<WatermarkType> ip = Collections.singletonList(WatermarkType.SIMPLE_INVISIBLE_PADDING);

        // 先逐块嵌入，再对嵌入后的数据做分块提取
        List<TableData> chunks = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            TableData chunk = new TableData("routing_test", new ArrayList<>(COLS), stringTable(20));
            DatabaseEmbedResult embed = wm.embed(chunk, PAYLOAD, ip);
            assertNotNull("block " + c + " embed should produce modifiedTable", embed.getModifiedTable());
            chunks.add(embed.getModifiedTable());
        }

        DatabaseExtractResult result = wm.extract(new ListChunkSource(chunks), ip);
        assertTrue("ChunkSource + types 提取应恢复载荷", result.getPayload().isPresent());
        assertEquals(PAYLOAD, result.getPayload().get());
        assertEquals("应处理全部 3 块", 3, result.getChunksProcessed());
    }

    /** 内存版分块数据源（仅测试用）。 */
    private static class ListChunkSource implements ChunkSource {
        private final List<TableData> chunks;
        private int idx = 0;

        ListChunkSource(List<TableData> chunks) {
            this.chunks = chunks;
        }

        @Override
        public boolean hasNext() {
            return idx < chunks.size();
        }

        @Override
        public TableData nextChunk() {
            return chunks.get(idx++);
        }
    }
}
