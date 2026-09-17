package io.github.nameof.watermark.database;

import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DatabaseExtractResult} 的单元测试，聚焦：
 * <ul>
 *   <li>单块 {@link WatermarkResult} → {@link DatabaseExtractResult} 的字段映射（含失败场景）</li>
 *   <li>{@link DatabaseExtractResult#aggregate(List)} 的多数投票 + 行数加权置信度语义</li>
 *   <li>全部失败时返回 {@code Optional.empty()}</li>
 *   <li>getter 的不可变视图 / 防御性拷贝</li>
 * </ul>
 */
public class DatabaseExtractResultTest {

    // ----------------------------------------------------------
    // fromCore 包装
    // ----------------------------------------------------------

    @Test
    public void fromCore_successfulExtract_mapsPayloadAndConfidence() {
        WatermarkResult<String> core = WatermarkResult.extractSuccess("PAYLOAD-1",
            Arrays.asList("a", "b"), /* repetition */ 5);
        core.setWatermarkType(WatermarkType.BIT_LATIN_HOMOGLYPH);
        core.setConfidence(0.92);
        core.setValidExtractions(8);

        DatabaseExtractResult r = DatabaseExtractResult.fromCore(core, /* rowsInChunk */ 10);

        assertEquals(1, r.getChunksProcessed());
        assertEquals(10, r.getRowsScanned());
        assertEquals(Optional.of("PAYLOAD-1"), r.getPayload());
        assertEquals(0.92, r.getConfidence(), 1e-9);
        assertEquals(Arrays.asList("a", "b"), r.getInvolvedColumns());
        assertEquals(Integer.valueOf(8), r.getStrategyHits().get(WatermarkType.BIT_LATIN_HOMOGLYPH));
    }

    @Test
    public void fromCore_failedExtract_returnsEmptyPayload() {
        WatermarkResult<String> core = WatermarkResult.failure("no watermark found");

        DatabaseExtractResult r = DatabaseExtractResult.fromCore(core, 10);

        assertFalse(r.getPayload().isPresent());
        assertEquals(0.0, r.getConfidence(), 1e-9);
        assertTrue(r.getInvolvedColumns().isEmpty());
        assertTrue(r.getStrategyHits().isEmpty());
    }

    @Test
    public void fromCore_successButNullPayload_returnsEmpty() {
        // 极少见：core 返回成功但 payload 为 null
        WatermarkResult<String> core = WatermarkResult.<String>success(null,
            Arrays.asList("a"), 3);
        core.setWatermarkType(WatermarkType.SIMPLE_SUFFIX_MARKER);

        DatabaseExtractResult r = DatabaseExtractResult.fromCore(core, 5);

        assertFalse(r.getPayload().isPresent());
    }

    // ----------------------------------------------------------
    // aggregate：基础
    // ----------------------------------------------------------

    @Test
    public void aggregate_emptyList_returnsZeroResult() {
        DatabaseExtractResult r = DatabaseExtractResult.aggregate(Collections.<DatabaseExtractResult>emptyList());

        assertEquals(0, r.getChunksProcessed());
        assertEquals(0, r.getRowsScanned());
        assertFalse(r.getPayload().isPresent());
        assertEquals(0.0, r.getConfidence(), 1e-9);
        assertTrue(r.getInvolvedColumns().isEmpty());
        assertTrue(r.getStrategyHits().isEmpty());
    }

    @Test
    public void aggregate_singleSuccessfulResult_preservesAllFields() {
        DatabaseExtractResult one = makeSuccessResult(1, 10, "P1", 0.9, "a");

        DatabaseExtractResult agg = DatabaseExtractResult.aggregate(Collections.singletonList(one));

        assertEquals(Optional.of("P1"), agg.getPayload());
        assertEquals(0.9, agg.getConfidence(), 1e-9);
    }

    @Test
    public void aggregate_allFailedResults_returnsEmptyPayload() {
        DatabaseExtractResult f1 = makeFailureResult(1, 5);
        DatabaseExtractResult f2 = makeFailureResult(1, 7);

        DatabaseExtractResult agg = DatabaseExtractResult.aggregate(Arrays.asList(f1, f2));

        assertFalse(agg.getPayload().isPresent());
        assertEquals(0.0, agg.getConfidence(), 1e-9);
        assertEquals(2, agg.getChunksProcessed());
        assertEquals(12, agg.getRowsScanned());
    }

    // ----------------------------------------------------------
    // aggregate：多数投票
    // ----------------------------------------------------------

    @Test
    public void aggregate_majorityVote_picksMostFrequentPayload() {
        // 5 块：3 块返回 "P1"，2 块返回 "P2"
        List<DatabaseExtractResult> partials = Arrays.asList(
            makeSuccessResult(1, 10, "P1", 0.8, "a"),
            makeSuccessResult(1, 10, "P1", 0.7, "a"),
            makeSuccessResult(1, 10, "P1", 0.6, "a"),
            makeSuccessResult(1, 10, "P2", 0.9, "a"),
            makeSuccessResult(1, 10, "P2", 0.5, "a")
        );

        DatabaseExtractResult agg = DatabaseExtractResult.aggregate(partials);

        assertEquals(Optional.of("P1"), agg.getPayload());
    }

    @Test
    public void aggregate_mixedSuccessAndFailure_onlySuccessfulVotesCount() {
        // 4 块：2 块成功返回 "P1"，1 块成功返回 "P2"，1 块失败
        List<DatabaseExtractResult> partials = Arrays.asList(
            makeSuccessResult(1, 10, "P1", 0.9, "a"),
            makeFailureResult(1, 10),
            makeSuccessResult(1, 10, "P1", 0.7, "a"),
            makeSuccessResult(1, 10, "P2", 0.95, "a")
        );

        DatabaseExtractResult agg = DatabaseExtractResult.aggregate(partials);

        assertEquals(Optional.of("P1"), agg.getPayload());  // 失败块不参与投票
        assertEquals(4, agg.getChunksProcessed());
        assertEquals(40, agg.getRowsScanned());
    }

    // ----------------------------------------------------------
    // aggregate：行数加权置信度
    // ----------------------------------------------------------

    @Test
    public void aggregate_confidence_isWeightedByRowsScanned() {
        // 块1: 100 行, conf=0.9
        // 块2: 300 行, conf=0.5
        // 加权 = (0.9*100 + 0.5*300) / 400 = (90 + 150) / 400 = 0.6
        List<DatabaseExtractResult> partials = Arrays.asList(
            makeSuccessResult(1, 100, "P1", 0.9, "a"),
            makeSuccessResult(1, 300, "P1", 0.5, "a")
        );

        DatabaseExtractResult agg = DatabaseExtractResult.aggregate(partials);

        assertEquals(0.6, agg.getConfidence(), 1e-9);
    }

    @Test
    public void aggregate_confidence_ignoresFailedChunks() {
        // 块1: 100 行, success conf=0.9
        // 块2: 1000 行, FAILED (不应参与加权)
        // 块3: 100 行, success conf=0.5
        // 加权 = (0.9*100 + 0.5*100) / 200 = 0.7
        List<DatabaseExtractResult> partials = Arrays.asList(
            makeSuccessResult(1, 100, "P1", 0.9, "a"),
            makeFailureResult(1, 1000),
            makeSuccessResult(1, 100, "P1", 0.5, "a")
        );

        DatabaseExtractResult agg = DatabaseExtractResult.aggregate(partials);

        assertEquals(0.7, agg.getConfidence(), 1e-9);
        assertEquals(1200, agg.getRowsScanned());
    }

    // ----------------------------------------------------------
    // 防御性拷贝
    // ----------------------------------------------------------

    @Test
    public void involvedColumns_isUnmodifiable() {
        DatabaseExtractResult r = makeSuccessResult(1, 1, "P", 1.0, "a");

        try {
            r.getInvolvedColumns().add("evil");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void strategyHits_returnsDefensiveCopy() {
        DatabaseExtractResult r = makeSuccessResult(1, 1, "P", 1.0, "a");

        EnumMap<WatermarkType, Integer> copy1 = r.getStrategyHits();
        EnumMap<WatermarkType, Integer> copy2 = r.getStrategyHits();
        assertNotSame(copy1, copy2);
    }

    @Test(expected = NullPointerException.class)
    public void aggregate_nullList_throws() {
        DatabaseExtractResult.aggregate(null);
    }

    @Test
    public void payloadOptional_isImmutable() {
        DatabaseExtractResult r = makeSuccessResult(1, 1, "P", 1.0, "a");

        // Optional<String> 不可变；不应提供 setter
        Optional<String> p1 = r.getPayload();
        assertEquals(Optional.of("P"), p1);
    }

    // ----------------------------------------------------------
    // helpers
    // ----------------------------------------------------------

    private static DatabaseExtractResult makeSuccessResult(int chunks, int rows,
                                                           String payload, double confidence,
                                                           String col) {
        EnumMap<WatermarkType, Integer> hits = new EnumMap<>(WatermarkType.class);
        hits.put(WatermarkType.BIT_LATIN_HOMOGLYPH, 5);
        return new DatabaseExtractResult(chunks, rows,
            Optional.of(payload), confidence,
            Collections.singletonList(col), hits);
    }

    private static DatabaseExtractResult makeFailureResult(int chunks, int rows) {
        return new DatabaseExtractResult(chunks, rows,
            Optional.<String>empty(), 0.0,
            Collections.<String>emptyList(), new EnumMap<WatermarkType, Integer>(WatermarkType.class));
    }
}