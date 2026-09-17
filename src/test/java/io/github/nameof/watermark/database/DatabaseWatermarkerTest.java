package io.github.nameof.watermark.database;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.io.TableData;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DatabaseWatermarker} 端到端测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>单块 embed/extract 往返（in-memory TableData）</li>
 *   <li>多块分片嵌入 / 跨块聚合</li>
 *   <li>extract 多数投票语义</li>
 *   <li>canWatermark 返回策略集合</li>
 *   <li>空表 → 零计数结果，不抛异常</li>
 *   <li>{@link ChunkSource} 资源自动关闭（含异常路径）</li>
 * </ul>
 *
 * <p><b>策略选择</b>：测试使用 {@link WatermarkType#SIMPLE_INVISIBLE_PADDING}
 * 显式指定而非自动选择，原因有二：
 * <ol>
 *   <li>simple 策略不依赖"≥24 单元"门槛（bit-level 头部需要），小数据可用</li>
 *   <li>simple 路径在 core 层会填充 {@code totalCells} / {@code confidence}，
 *       便于测试断言</li>
 * </ol>
 */
public class DatabaseWatermarkerTest {

    private static final String PAYLOAD = "secret-payload-2026";
    private static final WatermarkConfig CONFIG = new WatermarkConfig("test-secret");
    /**
     * 简单策略组合：必须同时包含两种 simple 策略才能可靠提取。
     * 原因：core 内部注册顺序为 SuffixMarker → InvisiblePadding，
     * extract 使用 {@code findAnySimpleStrategy} 时返回第一个匹配的
     * （SuffixMarker）；如果 embed 只用 InvisiblePadding，extract
     * 会用 SuffixMarker 找不到标记而失败。
     */
    private static final List<WatermarkType> SIMPLE_STRATEGIES = Arrays.asList(
        WatermarkType.SIMPLE_SUFFIX_MARKER,
        WatermarkType.SIMPLE_INVISIBLE_PADDING
    );

    // ----------------------------------------------------------
    // 单块 API
    // ----------------------------------------------------------

    @Test
    public void singleChunk_embed_thenExtract_roundTripsPayload() {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData table = sampleStringTable(5);

        DatabaseEmbedResult embedResult = wm.embed(table, PAYLOAD, SIMPLE_STRATEGIES);
        // 关键：从结果中取 modifiedTable 用于后续提取（chunk 本身未被原地修改）
        TableData modified = embedResult.getModifiedTable();
        assertNotNull("modifiedTable should be set on success", modified);

        DatabaseExtractResult extractResult = wm.extract(modified);

        assertEquals(1, embedResult.getChunksProcessed());
        assertEquals(5, embedResult.getRowsModified());
        assertTrue("cells modified should be > 0, got " + embedResult.getCellsModified(),
            embedResult.getCellsModified() > 0);
        assertFalse(embedResult.getInvolvedColumns().isEmpty());

        assertTrue("expected payload recovered", extractResult.getPayload().isPresent());
        assertEquals(PAYLOAD, extractResult.getPayload().get());
        assertTrue("confidence should be > 0, got " + extractResult.getConfidence(),
            extractResult.getConfidence() > 0);
    }

    @Test
    public void singleChunk_chunkNotMutatedInPlace() {
        // 验证：embed 不会修改传入的 chunk（原 Map 引用保持原值）
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData table = sampleStringTable(3);
        String originalName = (String) table.getRows().get(0).get("name");

        wm.embed(table, PAYLOAD, SIMPLE_STRATEGIES);

        // 原 chunk 的 row map value 未变（不可见水印效果）
        assertEquals(originalName, table.getRows().get(0).get("name"));
        // modifiedTable 与原 chunk 是不同对象
        DatabaseEmbedResult r = wm.embed(table, PAYLOAD, SIMPLE_STRATEGIES);
        assertNotNull(r.getModifiedTable());
        assertNotSame(table, r.getModifiedTable());
    }

    @Test
    public void singleChunk_canWatermark_returnsApplicableStrategies() {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData table = sampleStringTable(3);

        java.util.Set<WatermarkType> applicable = wm.canWatermark(table, PAYLOAD);

        assertNotNull(applicable);
        assertFalse(applicable.isEmpty());
    }

    @Test
    public void singleChunk_canWatermark_doesNotModifyInput() {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData table = sampleStringTable(2);
        String originalValue = (String) table.getRows().get(0).get("name");

        wm.canWatermark(table, PAYLOAD);

        // 输入未被修改
        assertEquals(originalValue, table.getRows().get(0).get("name"));
    }

    @Test
    public void singleChunk_emptyTable_returnsZeroResult() {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData empty = new TableData("empty",
            Arrays.asList("name"), new ArrayList<Map<String, Object>>());

        DatabaseEmbedResult embedResult = wm.embed(empty, PAYLOAD, SIMPLE_STRATEGIES);

        assertEquals(0, embedResult.getRowsModified());
        assertEquals(0, embedResult.getCellsModified());
    }

    @Test
    public void singleChunk_extractFromUnmarkedTable_returnsEmptyPayload() {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData unmarked = sampleStringTable(5);  // 未嵌入水印

        DatabaseExtractResult extractResult = wm.extract(unmarked);

        assertFalse("unmarked table should not yield payload", extractResult.getPayload().isPresent());
    }

    // ----------------------------------------------------------
    // 全表 API（多块 ChunkSource）
    // ----------------------------------------------------------

    @Test
    public void multiChunk_embedAcrossThreeChunks_aggregatesCorrectly() throws Exception {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        // 3 块：5 行 + 5 行 + 3 行 = 13 行
        List<TableData> chunks = Arrays.asList(
            sampleStringTable(5),
            sampleStringTable(5),
            sampleStringTable(3)
        );
        InMemoryChunkSource source = new InMemoryChunkSource(chunks);

        DatabaseEmbedResult total = wm.embed(source, PAYLOAD, SIMPLE_STRATEGIES);

        assertEquals(3, total.getChunksProcessed());
        assertEquals(13, total.getRowsModified());
        assertTrue("cells modified should aggregate > 0, got " + total.getCellsModified(),
            total.getCellsModified() > 0);
    }

    @Test
    public void multiChunk_extractAcrossChunks_majorityVoteReturnsPayload() throws Exception {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        List<TableData> chunks = Arrays.asList(
            sampleStringTable(5),
            sampleStringTable(5),
            sampleStringTable(3)
        );
        // 先嵌入：每块返回 modifiedTable
        List<TableData> modifiedChunks = new ArrayList<>();
        for (TableData c : chunks) {
            DatabaseEmbedResult r = wm.embed(c, PAYLOAD, SIMPLE_STRATEGIES);
            modifiedChunks.add(r.getModifiedTable());
        }
        // 再分块提取
        InMemoryChunkSource source = new InMemoryChunkSource(modifiedChunks);

        DatabaseExtractResult total = wm.extract(source);

        assertEquals(3, total.getChunksProcessed());
        assertEquals(13, total.getRowsScanned());
        assertTrue(total.getPayload().isPresent());
        assertEquals(PAYLOAD, total.getPayload().get());
    }

    @Test
    public void multiChunk_extract_mixedMarkedAndUnmarked_picksMajority() throws Exception {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        List<TableData> chunks = Arrays.asList(
            sampleStringTable(4),  // 已嵌入
            sampleStringTable(4),  // 已嵌入
            sampleStringTable(3)   // 未嵌入
        );
        // 前两块嵌入，返回 modifiedTable；最后一块保持原样
        TableData m0 = wm.embed(chunks.get(0), PAYLOAD, SIMPLE_STRATEGIES).getModifiedTable();
        TableData m1 = wm.embed(chunks.get(1), PAYLOAD, SIMPLE_STRATEGIES).getModifiedTable();
        List<TableData> mixedChunks = Arrays.asList(m0, m1, chunks.get(2));

        InMemoryChunkSource source = new InMemoryChunkSource(mixedChunks);
        DatabaseExtractResult total = wm.extract(source);

        // 2/3 块带水印，多数投票应得 PAYLOAD
        assertTrue(total.getPayload().isPresent());
        assertEquals(PAYLOAD, total.getPayload().get());
    }

    @Test
    public void multiChunk_extract_noMarkedChunks_returnsEmptyPayload() throws Exception {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        List<TableData> chunks = Arrays.asList(
            sampleStringTable(3),
            sampleStringTable(3)
        );
        // 不嵌入，直接提取
        InMemoryChunkSource source = new InMemoryChunkSource(chunks);

        DatabaseExtractResult total = wm.extract(source);

        assertFalse(total.getPayload().isPresent());
        assertEquals(0.0, total.getConfidence(), 1e-9);
    }

    @Test
    public void multiChunk_emptySource_returnsZeroResult() throws Exception {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        InMemoryChunkSource source = new InMemoryChunkSource(Collections.<TableData>emptyList());

        DatabaseEmbedResult embedResult = wm.embed(source, PAYLOAD, SIMPLE_STRATEGIES);

        assertEquals(0, embedResult.getChunksProcessed());
        assertEquals(0, embedResult.getRowsModified());
    }

    // ----------------------------------------------------------
    // ChunkSource 资源管理
    // ----------------------------------------------------------

    @Test
    public void chunkSource_closeCalledOnSuccess() throws Exception {
        InMemoryChunkSource source = new InMemoryChunkSource(
            Collections.singletonList(sampleStringTable(2)));
        assertFalse(source.closed);

        new DatabaseWatermarker(CONFIG).embed(source, PAYLOAD, SIMPLE_STRATEGIES);

        assertTrue("close() should be called after embed", source.closed);
    }

    @Test
    public void chunkSource_closeCalledOnException() {
        ThrowingChunkSource source = new ThrowingChunkSource();
        try {
            new DatabaseWatermarker(CONFIG).embed(source, PAYLOAD, SIMPLE_STRATEGIES);
            fail("expected exception");
        } catch (Exception expected) {
            // 异常路径也必须 close
            assertTrue("close() should be called even on exception", source.closed);
        }
    }

    @Test
    public void chunkSource_closeCalledOnExtractException() {
        ThrowingChunkSource source = new ThrowingChunkSource();
        try {
            new DatabaseWatermarker(CONFIG).extract(source);
            fail("expected exception");
        } catch (Exception expected) {
            assertTrue(source.closed);
        }
    }

    // ----------------------------------------------------------
    // 静态聚合便捷方法
    // ----------------------------------------------------------

    @Test
    public void staticAggregateEmbed_delegatesToResultClass() {
        DatabaseWatermarker wm = new DatabaseWatermarker(CONFIG);
        TableData chunk = sampleStringTable(2);
        DatabaseEmbedResult partial = wm.embed(chunk, PAYLOAD, SIMPLE_STRATEGIES);

        DatabaseEmbedResult agg = DatabaseWatermarker.aggregateEmbed(
            Collections.singletonList(partial));

        assertEquals(partial.getChunksProcessed(), agg.getChunksProcessed());
        assertEquals(partial.getRowsModified(), agg.getRowsModified());
    }

    // ----------------------------------------------------------
    // helpers
    // ----------------------------------------------------------

    /**
     * 构造一个有可水印字符串数据的表（用于 SIMPLE_INVISIBLE_PADDING 策略）。
     * 每行 name/city 字段填入有重复因子的字符串，确保 simple 策略可承载载荷。
     */
    private static TableData sampleStringTable(int rowCount) {
        List<String> cols = Arrays.asList("name", "city");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 较长字符串以承载简单策略的载荷（simple 策略在每个值中嵌入完整载荷的副本）
            row.put("name", repeat("Person" + i + "_", 5));
            row.put("city", repeat("City" + i + "_", 5));
            rows.add(row);
        }
        return new TableData("test", cols, rows);
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    /**
     * 内存版 ChunkSource，按顺序返回固定列表中的块。
     * 每次调用 close() 后置 {@code closed = true}，便于测试验证。
     */
    static class InMemoryChunkSource implements ChunkSource {
        private final List<TableData> chunks;
        private int cursor = 0;
        boolean closed = false;

        InMemoryChunkSource(List<TableData> chunks) {
            this.chunks = chunks;
        }

        @Override
        public boolean hasNext() {
            return cursor < chunks.size();
        }

        @Override
        public TableData nextChunk() {
            if (!hasNext()) throw new IllegalStateException("no more chunks");
            return chunks.get(cursor++);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * 在 hasNext() 阶段抛异常的 ChunkSource，用于测试异常路径资源清理。
     */
    static class ThrowingChunkSource implements ChunkSource {
        boolean closed = false;

        @Override
        public boolean hasNext() throws Exception {
            throw new RuntimeException("simulated source failure");
        }

        @Override
        public TableData nextChunk() {
            throw new IllegalStateException("not reachable");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // 抑制未使用警告
    @SuppressWarnings("unused")
    private static final AtomicInteger UNUSED = new AtomicInteger();
}