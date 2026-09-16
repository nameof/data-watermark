package io.github.nameof.watermark.database;

import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一次提取（{@code DatabaseWatermarker.extract(...)}）的聚合结果。
 *
 * <p>包含跨块的统计信息与多数投票结果：
 * <ul>
 *   <li>处理了多少块（{@link #getChunksProcessed()}）</li>
 *   <li>累计扫描了多少行（{@link #getRowsScanned()}）</li>
 *   <li>最终载荷（{@link #getPayload()}，多数投票得出；可为空）</li>
 *   <li>加权置信度（{@link #getConfidence()}，按成功块的行数加权）</li>
 *   <li>水印涉及的列名（{@link #getInvolvedColumns()}，跨块取并集去重）</li>
 *   <li>每种 {@link WatermarkType} 的命中次数（{@link #getStrategyHits()}）</li>
 * </ul>
 *
 * <p>聚合规则（{@link #aggregate(List)}）：
 * <ul>
 *   <li>{@code payload}：取出现次数最多的非空载荷；若全部块都失败，{@link Optional#empty()}</li>
 *   <li>{@code confidence}：成功块的 {@code confidence * rowsScanned} 之和 / 成功块的行数之和
 *       （按行数加权的平均值，避免小块拉偏）</li>
 *   <li>其余计数项跨块累加；{@code involvedColumns} 取并集去重</li>
 * </ul>
 *
 * @author chengpan
 */
public final class DatabaseExtractResult {

    private final int chunksProcessed;
    private final int rowsScanned;
    private final Optional<String> payload;
    private final double confidence;
    private final List<String> involvedColumns;
    private final EnumMap<WatermarkType, Integer> strategyHits;

    DatabaseExtractResult(int chunksProcessed,
                          int rowsScanned,
                          Optional<String> payload,
                          double confidence,
                          List<String> involvedColumns,
                          EnumMap<WatermarkType, Integer> strategyHits) {
        this.chunksProcessed = chunksProcessed;
        this.rowsScanned = rowsScanned;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.confidence = confidence;
        this.involvedColumns = Collections.unmodifiableList(new ArrayList<>(involvedColumns));
        this.strategyHits = copyStrategyMap(strategyHits);
    }

    public int getChunksProcessed() {
        return chunksProcessed;
    }

    public int getRowsScanned() {
        return rowsScanned;
    }

    /**
     * 多数投票得出的载荷。若所有块都未提取到水印，返回 {@link Optional#empty()}。
     */
    public Optional<String> getPayload() {
        return payload;
    }

    /**
     * 按行数加权的平均置信度（仅成功块参与加权）。
     * 若无任何成功块，返回 0.0。
     */
    public double getConfidence() {
        return confidence;
    }

    public List<String> getInvolvedColumns() {
        return involvedColumns;
    }

    /**
     * 每种 {@link WatermarkType} 的命中次数（仅成功块计数）。
     */
    public EnumMap<WatermarkType, Integer> getStrategyHits() {
        return copyStrategyMap(strategyHits);
    }

    /**
     * 聚合多次单块提取的结果。
     *
     * <p>多数投票：相同 {@code payload} 字符串视为同一票，出现次数最多的载荷胜出。
     * 平票时取 {@link HashMap} 迭代顺序首次遇到的（不保证确定性，可视为随机）。
     *
     * @param partials 单块提取结果列表，可为空
     * @return 聚合后的结果；若 partials 为空或全部失败，返回 payload 为空的零计数结果
     */
    public static DatabaseExtractResult aggregate(List<DatabaseExtractResult> partials) {
        Objects.requireNonNull(partials, "partials");
        if (partials.isEmpty()) {
            return new DatabaseExtractResult(0, 0, Optional.empty(), 0.0,
                Collections.emptyList(), new EnumMap<>(WatermarkType.class));
        }

        int chunks = 0;
        int rows = 0;
        Set<String> cols = new LinkedHashSet<>();
        EnumMap<WatermarkType, Integer> hits = new EnumMap<>(WatermarkType.class);

        Map<String, Integer> payloadVote = new HashMap<>();
        double confidenceSum = 0.0;
        long rowsSumSuccessful = 0;

        for (DatabaseExtractResult p : partials) {
            Objects.requireNonNull(p, "partial contains null");
            chunks += p.chunksProcessed;
            rows += p.rowsScanned;
            cols.addAll(p.involvedColumns);
            mergeStrategyMap(hits, p.strategyHits);

            if (p.payload.isPresent()) {
                payloadVote.merge(p.payload.get(), 1, Integer::sum);
                confidenceSum += p.confidence * p.rowsScanned;
                rowsSumSuccessful += p.rowsScanned;
            }
        }

        Optional<String> finalPayload;
        double finalConfidence;
        if (payloadVote.isEmpty()) {
            finalPayload = Optional.empty();
            finalConfidence = 0.0;
        } else {
            // 多数投票：取出现次数最多的 payload
            String winner = null;
            int maxVotes = -1;
            for (Map.Entry<String, Integer> e : payloadVote.entrySet()) {
                if (e.getValue() > maxVotes) {
                    winner = e.getKey();
                    maxVotes = e.getValue();
                }
            }
            finalPayload = Optional.of(winner);
            finalConfidence = rowsSumSuccessful > 0
                ? confidenceSum / rowsSumSuccessful
                : 0.0;
        }

        return new DatabaseExtractResult(chunks, rows, finalPayload, finalConfidence,
            new ArrayList<>(cols), hits);
    }

    /**
     * 从 core 层 {@link WatermarkResult} 构造单块结果。
     * 仅包内可见，供 {@link DatabaseWatermarker} 调用。
     */
    static DatabaseExtractResult fromCore(WatermarkResult<String> core, int rowsInChunk) {
        if (core.isSuccess() && core.getData() != null) {
            EnumMap<WatermarkType, Integer> hits = new EnumMap<>(WatermarkType.class);
            WatermarkType type = core.getWatermarkType();
            if (type != null && core.getValidExtractions() > 0) {
                hits.put(type, core.getValidExtractions());
            }
            List<String> cols = core.getWatermarkedColumns();
            return new DatabaseExtractResult(
                1,
                rowsInChunk,
                Optional.of(core.getData()),
                core.getConfidence(),
                cols != null ? cols : Collections.<String>emptyList(),
                hits
            );
        }
        return new DatabaseExtractResult(
            1,
            rowsInChunk,
            Optional.<String>empty(),
            0.0,
            Collections.emptyList(),
            new EnumMap<>(WatermarkType.class)
        );
    }

    private static EnumMap<WatermarkType, Integer> copyStrategyMap(EnumMap<WatermarkType, Integer> src) {
        EnumMap<WatermarkType, Integer> copy = new EnumMap<>(WatermarkType.class);
        copy.putAll(src);
        return copy;
    }

    private static void mergeStrategyMap(EnumMap<WatermarkType, Integer> target,
                                         EnumMap<WatermarkType, Integer> source) {
        for (Map.Entry<WatermarkType, Integer> e : source.entrySet()) {
            target.merge(e.getKey(), e.getValue(), Integer::sum);
        }
    }

    @Override
    public String toString() {
        return "DatabaseExtractResult{" +
            "chunks=" + chunksProcessed +
            ", rows=" + rowsScanned +
            ", payload=" + payload.orElse("<none>") +
            ", confidence=" + confidence +
            ", cols=" + involvedColumns +
            ", strategies=" + strategyHits +
            '}';
    }
}