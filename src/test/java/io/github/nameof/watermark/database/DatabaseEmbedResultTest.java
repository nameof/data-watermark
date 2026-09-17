package io.github.nameof.watermark.database;

import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.io.TableData;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DatabaseEmbedResult} 的单元测试，聚焦：
 * <ul>
 *   <li>单块 {@link WatermarkResult} → {@link DatabaseEmbedResult} 的字段映射</li>
 *   <li>{@link DatabaseEmbedResult#aggregate(List)} 的求和 / 并集 / 求和合并语义</li>
 *   <li>getter 的不可变视图 / 防御性拷贝</li>
 *   <li>空输入与异常输入</li>
 * </ul>
 */
public class DatabaseEmbedResultTest {

    private static final List<String> NO_COLS = Collections.emptyList();

    // ----------------------------------------------------------
    // fromCore 包装
    // ----------------------------------------------------------

    @Test
    public void fromCore_successEmbed_mapsAllFields() {
        WatermarkResult<List<Map<String, Object>>> core = WatermarkResult.embedSuccess(
            rows(), Arrays.asList("a", "b"), /* repetition */ 5);
        core.setWatermarkType(WatermarkType.BIT_LATIN_HOMOGLYPH);
        core.setTotalCells(120);
        core.setConfidence(0.95);

        TableData modified = new TableData("t", Arrays.asList("a", "b"), rows());
        // cellsModified=120（调用方传入的统计值），modifiedTable 用于写回
        DatabaseEmbedResult r = DatabaseEmbedResult.fromCore(core, /* rowsInChunk */ 10, 120, modified);

        assertEquals(1, r.getChunksProcessed());
        assertEquals(10, r.getRowsModified());
        assertEquals(120, r.getCellsModified());
        assertEquals(Arrays.asList("a", "b"), r.getInvolvedColumns());
        assertEquals(1, r.getStrategyUsage().size());
        assertEquals(Integer.valueOf(120), r.getStrategyUsage().get(WatermarkType.BIT_LATIN_HOMOGLYPH));
        assertEquals(modified, r.getModifiedTable());
    }

    @Test
    public void fromCore_noWatermarkType_yieldsEmptyStrategyMap() {
        WatermarkResult<List<Map<String, Object>>> core = WatermarkResult.embedSuccess(
            rows(), NO_COLS, 5);
        // 故意不设置 watermarkType；totalCells 保留 core 的 -1 哨兵值
        // 但 DatabaseEmbedResult 会归一化为 0

        DatabaseEmbedResult r = DatabaseEmbedResult.fromCore(core, 1, /* cellsModified */ 0, null);

        assertEquals(0, r.getStrategyUsage().size());
        // 归一化后 -1 → 0，避免污染跨块累加
        assertEquals(0, r.getCellsModified());
        assertEquals(null, r.getModifiedTable());
    }

    @Test
    public void fromCore_fallsBackToCoreTotalCellsWhenCallerZero() {
        // 调用方未统计（cellsModified=0）时，回退到 core 的 totalCells
        WatermarkResult<List<Map<String, Object>>> core = WatermarkResult.embedSuccess(
            rows(), NO_COLS, 5);
        core.setWatermarkType(WatermarkType.SIMPLE_SUFFIX_MARKER);
        core.setTotalCells(42);

        DatabaseEmbedResult r = DatabaseEmbedResult.fromCore(core, 3, /* cellsModified */ 0, null);

        assertEquals(42, r.getCellsModified());
        assertEquals(Integer.valueOf(42), r.getStrategyUsage().get(WatermarkType.SIMPLE_SUFFIX_MARKER));
    }

    @Test
    public void fromCore_modifiedTableIsNullForFailedEmbed() {
        // 嵌入失败时，modifiedTable 应为 null
        WatermarkResult<List<Map<String, Object>>> core = WatermarkResult.failure("no cells");

        DatabaseEmbedResult r = DatabaseEmbedResult.fromCore(core, 0, 0, /* modified */ null);

        assertEquals(null, r.getModifiedTable());
        // chunksProcessed 仍为 1（记录"尝试处理了 1 块"，不论成功失败）
        assertEquals(1, r.getChunksProcessed());
        assertEquals(0, r.getCellsModified());
    }

    // ----------------------------------------------------------
    // aggregate
    // ----------------------------------------------------------

    @Test
    public void aggregate_emptyList_returnsZeroResult() {
        DatabaseEmbedResult r = DatabaseEmbedResult.aggregate(Collections.<DatabaseEmbedResult>emptyList());

        assertEquals(0, r.getChunksProcessed());
        assertEquals(0, r.getRowsModified());
        assertEquals(0, r.getCellsModified());
        assertTrue(r.getInvolvedColumns().isEmpty());
        assertTrue(r.getStrategyUsage().isEmpty());
        assertEquals(null, r.getModifiedTable());
    }

    @Test
    public void aggregate_singleResult_returnsEqualFields() {
        DatabaseEmbedResult one = makeResult(1, 5, 50,
                Arrays.asList("a", "b"),
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 50));

        DatabaseEmbedResult agg = DatabaseEmbedResult.aggregate(Collections.singletonList(one));

        assertEquals(1, agg.getChunksProcessed());
        assertEquals(5, agg.getRowsModified());
        assertEquals(50, agg.getCellsModified());
        assertEquals(Arrays.asList("a", "b"), agg.getInvolvedColumns());
        assertEquals(Integer.valueOf(50), agg.getStrategyUsage().get(WatermarkType.BIT_LATIN_HOMOGLYPH));
    }

    @Test
    public void aggregate_multipleResults_sumsCountsAndColumns() {
        DatabaseEmbedResult p1 = makeResult(1, 10, 100,
                Arrays.asList("a", "b"),
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 100));
        DatabaseEmbedResult p2 = makeResult(1, 15, 150,
                Arrays.asList("b", "c"),  // 与 p1 有重叠列 b
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 150));
        DatabaseEmbedResult p3 = makeResult(1, 20, 200,
                Collections.singletonList("d"),
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 200));

        DatabaseEmbedResult agg = DatabaseEmbedResult.aggregate(Arrays.asList(p1, p2, p3));

        assertEquals(3, agg.getChunksProcessed());
        assertEquals(45, agg.getRowsModified());        // 10+15+20
        assertEquals(450, agg.getCellsModified());       // 100+150+200
        assertEquals(Arrays.asList("a", "b", "c", "d"), agg.getInvolvedColumns()); // 去重，保留首次出现顺序
        assertEquals(Integer.valueOf(450), agg.getStrategyUsage().get(WatermarkType.BIT_LATIN_HOMOGLYPH));
    }

    @Test
    public void aggregate_multipleStrategies_sumsPerStrategy() {
        DatabaseEmbedResult p1 = makeResult(1, 5, 50,
                NO_COLS,
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 50));
        DatabaseEmbedResult p2 = makeResult(1, 5, 50,
                NO_COLS,
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 30,
                            WatermarkType.SIMPLE_INVISIBLE_PADDING, 20));

        DatabaseEmbedResult agg = DatabaseEmbedResult.aggregate(Arrays.asList(p1, p2));

        assertEquals(Integer.valueOf(80), agg.getStrategyUsage().get(WatermarkType.BIT_LATIN_HOMOGLYPH));
        assertEquals(Integer.valueOf(20), agg.getStrategyUsage().get(WatermarkType.SIMPLE_INVISIBLE_PADDING));
        assertEquals(null, agg.getModifiedTable());  // 聚合后 modifiedTable 总是 null
    }

    @Test(expected = NullPointerException.class)
    public void aggregate_nullList_throws() {
        DatabaseEmbedResult.aggregate(null);
    }

    @Test(expected = NullPointerException.class)
    public void aggregate_listWithNull_throws() {
        DatabaseEmbedResult.aggregate(Arrays.asList((DatabaseEmbedResult) null));
    }

    // ----------------------------------------------------------
    // 防御性拷贝
    // ----------------------------------------------------------

    @Test
    public void involvedColumns_isUnmodifiable() {
        DatabaseEmbedResult r = makeResult(1, 1, 1,
                new ArrayList<>(Arrays.asList("a", "b")),
                new EnumMap<WatermarkType, Integer>(WatermarkType.class));

        try {
            r.getInvolvedColumns().add("evil");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void strategyUsage_returnsDefensiveCopy() {
        DatabaseEmbedResult r = makeResult(1, 1, 1,
                NO_COLS,
                strategyMap(WatermarkType.BIT_LATIN_HOMOGLYPH, 5));

        EnumMap<WatermarkType, Integer> copy1 = r.getStrategyUsage();
        EnumMap<WatermarkType, Integer> copy2 = r.getStrategyUsage();
        assertNotSame(copy1, copy2);

        // 修改副本不影响原始
        copy1.put(WatermarkType.SIMPLE_INVISIBLE_PADDING, 999);
        assertEquals(1, r.getStrategyUsage().size());
    }

    // ----------------------------------------------------------
    // helpers
    // ----------------------------------------------------------

    private static DatabaseEmbedResult makeResult(int chunks, int rows, int cells,
                                                  List<String> cols,
                                                  EnumMap<WatermarkType, Integer> usage) {
        return new DatabaseEmbedResult(chunks, rows, cells, cols, usage, null);
    }

    private static EnumMap<WatermarkType, Integer> strategyMap(WatermarkType k1, int v1) {
        EnumMap<WatermarkType, Integer> m = new EnumMap<>(WatermarkType.class);
        m.put(k1, v1);
        return m;
    }

    private static EnumMap<WatermarkType, Integer> strategyMap(WatermarkType k1, int v1,
                                                                WatermarkType k2, int v2) {
        EnumMap<WatermarkType, Integer> m = strategyMap(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "hello");
        row.put("b", "world");
        list.add(row);
        return list;
    }
}