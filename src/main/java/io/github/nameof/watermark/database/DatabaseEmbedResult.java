package io.github.nameof.watermark.database;

import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.io.TableData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次嵌入（{@code DatabaseWatermarker.embed(...)}）的聚合结果。
 *
 * <p>包含跨块的统计信息：
 * <ul>
 *   <li>处理了多少块（{@link #getChunksProcessed()}）</li>
 *   <li>累计修改了多少行（{@link #getRowsModified()}）</li>
 *   <li>累计修改了多少单元（{@link #getCellsModified()}）</li>
 *   <li>水印涉及的列名（{@link #getInvolvedColumns()}，跨块取并集去重）</li>
 *   <li>每种 {@link WatermarkType} 的单元使用数（{@link #getStrategyUsage()}）</li>
 * </ul>
 *
 * <p>本对象不持有修改后的 {@code TableData}——调用方在调用
 * {@code DatabaseWatermarker.embed(...)} 后，其输入 {@code TableData}（或每块
 * {@code TableData}）已被原地修改，可直接走调用方自己的写回逻辑（UPDATE / 文件重写等）。
 *
 * @author chengpan
 */
public final class DatabaseEmbedResult {

    private final int chunksProcessed;
    private final int rowsModified;
    private final int cellsModified;
    private final List<String> involvedColumns;
    private final EnumMap<WatermarkType, Integer> strategyUsage;
    /** 水印修改后的 {@link TableData}；多块聚合时为 {@code null}（无法表示合并表）*/
    private final TableData modifiedTable;

    DatabaseEmbedResult(int chunksProcessed,
                        int rowsModified,
                        int cellsModified,
                        List<String> involvedColumns,
                        EnumMap<WatermarkType, Integer> strategyUsage,
                        TableData modifiedTable) {
        this.chunksProcessed = chunksProcessed;
        this.rowsModified = rowsModified;
        this.cellsModified = cellsModified;
        this.involvedColumns = Collections.unmodifiableList(new ArrayList<>(involvedColumns));
        this.strategyUsage = copyStrategyMap(strategyUsage);
        this.modifiedTable = modifiedTable;
    }

    /**
     * 处理了多少块数据。
     */
    public int getChunksProcessed() {
        return chunksProcessed;
    }

    /**
     * 累计修改的行数（所有块之和）。
     */
    public int getRowsModified() {
        return rowsModified;
    }

    /**
     * 累计修改的单元数（来自 core 层 {@link WatermarkResult#getTotalCells()} 求和）。
     */
    public int getCellsModified() {
        return cellsModified;
    }

    /**
     * 水印涉及的列名（跨块并集去重），不可变列表。
     */
    public List<String> getInvolvedColumns() {
        return involvedColumns;
    }

    /**
     * 每种 {@link WatermarkType} 修改的单元数。
     * 若本次嵌入调用方未指定策略（自动选择），本字段通常只有一个 key。
     */
    public EnumMap<WatermarkType, Integer> getStrategyUsage() {
        return copyStrategyMap(strategyUsage);
    }

    /**
     * 水印修改后的 {@link TableData}（仅单块嵌入时有值）。
     *
     * <p>调用方拿到此对象后可将其写回持久层（UPDATE 语句 / 文件重写 / 任意）。
     *
     * <p>由 {@link DatabaseWatermarker#embed(TableData, String, List)} 或
     * {@link DatabaseWatermarker#embed(TableData, String)} 返回时非空。
     * 由 {@link #aggregate(List)} 聚合得到时为 {@code null}（无法表示合并的多块表）。
     *
     * @return 修改后的 TableData，或 {@code null}
     */
    public TableData getModifiedTable() {
        return modifiedTable;
    }

    /**
     * 聚合多次单块嵌入的结果。
     *
     * <p>聚合规则：
     * <ul>
     *   <li>{@code chunksProcessed}：求和</li>
     *   <li>{@code rowsModified}：求和</li>
     *   <li>{@code cellsModified}：求和</li>
     *   <li>{@code involvedColumns}：并集去重，保留首次出现顺序</li>
     *   <li>{@code strategyUsage}：每种 {@link WatermarkType} 的 value 求和</li>
     *   <li>{@code modifiedTable}：聚合后置 {@code null}（无法表示合并的多块表）</li>
     * </ul>
     *
     * <p>若调用方需要逐块写回，应在循环中逐个处理每个 partial 的
     * {@link #getModifiedTable()}，而不是用 aggregate 结果。
     *
     * @param partials 单块嵌入结果列表，可为空
     * @return 聚合后的结果；若 partials 为空，返回全零结果
     */
    public static DatabaseEmbedResult aggregate(List<DatabaseEmbedResult> partials) {
        Objects.requireNonNull(partials, "partials");
        if (partials.isEmpty()) {
            return new DatabaseEmbedResult(0, 0, 0,
                Collections.emptyList(), new EnumMap<>(WatermarkType.class), null);
        }

        int chunks = 0;
        int rows = 0;
        int cells = 0;
        Set<String> cols = new LinkedHashSet<>();
        EnumMap<WatermarkType, Integer> usage = new EnumMap<>(WatermarkType.class);

        for (DatabaseEmbedResult p : partials) {
            Objects.requireNonNull(p, "partial contains null");
            chunks += p.chunksProcessed;
            rows += p.rowsModified;
            cells += p.cellsModified;
            cols.addAll(p.involvedColumns);
            mergeStrategyMap(usage, p.strategyUsage);
        }

        return new DatabaseEmbedResult(chunks, rows, cells, new ArrayList<>(cols), usage, null);
    }

    /**
     * 从 core 层 {@link WatermarkResult} 构造单块结果。
     * 仅包内可见，供 {@link DatabaseWatermarker} 调用。
     *
     * <p><b>cellsModified 参数</b>由调用方独立统计（core 的 embed 路径不设置 totalCells，
     * 仅 extract 设置），传入 0 表示未统计。
     *
     * <p><b>归一化</b>：core 层 {@code totalCells} 字段未计算时为 -1（哨兵值），
     * 这里统一归一化为 0，避免污染跨块累加结果。
     *
     * @param modifiedTable 水印后的 TableData（用于写回），{@code null} 表示失败
     */
    static DatabaseEmbedResult fromCore(WatermarkResult<List<Map<String, Object>>> core,
                                        int rowsInChunk,
                                        int cellsModified,
                                        TableData modifiedTable) {
        EnumMap<WatermarkType, Integer> usage = new EnumMap<>(WatermarkType.class);
        WatermarkType type = core.getWatermarkType();
        int coreCells = core.getTotalCells();
        if (coreCells < 0) coreCells = 0;  // 归一化 -1 → 0
        // 优先使用调用方统计的 cellsModified，回退到 core 的 totalCells
        int effectiveCells = cellsModified > 0 ? cellsModified : coreCells;
        if (type != null && core.isSuccess()) {
            usage.put(type, effectiveCells);
        }
        List<String> cols = core.getWatermarkedColumns();
        return new DatabaseEmbedResult(
            1,
            rowsInChunk,
            effectiveCells,
            cols != null ? cols : Collections.<String>emptyList(),
            usage,
            modifiedTable
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
        return "DatabaseEmbedResult{" +
            "chunks=" + chunksProcessed +
            ", rows=" + rowsModified +
            ", cells=" + cellsModified +
            ", cols=" + involvedColumns +
            ", strategies=" + strategyUsage +
            '}';
    }
}