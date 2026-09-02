package io.github.nameof.watermark.simple;

import io.github.nameof.watermark.WatermarkConfig;
import io.github.nameof.watermark.WatermarkResult;

import java.util.*;

/**
 * 简单水印引擎 —— 基于简单策略的嵌入/提取。
 * <p>
 * 与 {@link io.github.nameof.watermark.DataWatermarker} 平级，但逻辑大幅简化：
 * <ul>
 *   <li>每个单元格独立承载完整载荷（而非 1 bit）</li>
 *   <li>无需 header/payload 分离、交叉分配、CRC 校验</li>
 *   <li>提取时对多个单元格的提取结果做多数投票</li>
 * </ul>
 * </p>
 */
public class SimpleWatermarker {

    private final WatermarkConfig config;
    private final List<SimpleWatermarkStrategy> strategies;

    /**
     * 使用默认策略（SuffixMarker + InvisiblePadding）创建。
     *
     * @param config 水印配置
     */
    public SimpleWatermarker(WatermarkConfig config) {
        this.config = config;
        this.strategies = new ArrayList<>();
        strategies.add(new SuffixMarkerStrategy());
        strategies.add(new InvisiblePaddingStrategy());
    }

    /**
     * 使用自定义策略列表创建。
     *
     * @param config     水印配置
     * @param strategies 策略列表
     */
    public SimpleWatermarker(WatermarkConfig config, List<SimpleWatermarkStrategy> strategies) {
        this.config = config;
        this.strategies = new ArrayList<>(strategies);
    }

    /**
     * 嵌入水印。
     * <p>
     * 对每个可嵌入的单元格，使用匹配的策略嵌入完整载荷。
     * </p>
     *
     * @param table   表数据
     * @param columns 待嵌入的列名列表
     * @param payload 水印载荷
     * @return 嵌入结果
     */
    public WatermarkResult<List<Map<String, Object>>> embed(
            List<Map<String, Object>> table, List<String> columns, String payload) {

        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");
        if (payload == null || payload.isEmpty())
            return WatermarkResult.failure("水印载荷为空");

        // 深拷贝
        List<Map<String, Object>> result = new ArrayList<>(table.size());
        for (Map<String, Object> row : table) result.add(new LinkedHashMap<>(row));

        int embeddedCount = 0;
        Set<String> usedColumns = new LinkedHashSet<>();

        for (int r = 0; r < result.size(); r++) {
            Map<String, Object> row = result.get(r);
            for (String col : columns) {
                Object value = row.get(col);
                if (value == null) continue;

                SimpleWatermarkStrategy strategy = findStrategy(value);
                if (strategy != null) {
                    String embedded = strategy.embed(value, payload, config.getSecret(), r);
                    row.put(col, embedded);
                    embeddedCount++;
                    usedColumns.add(col);
                }
            }
        }

        if (embeddedCount == 0) {
            return WatermarkResult.failure("没有找到可嵌入水印的单元格");
        }

        // repetition 字段在此表示成功嵌入的单元格数
        return WatermarkResult.embedSuccess(result, new ArrayList<>(usedColumns), embeddedCount);
    }

    /**
     * 提取水印。
     * <p>
     * 对每个可提取的单元格调用策略的 extract 方法，
     * 对结果做多数投票得到最终载荷。
     * </p>
     *
     * @param table   表数据
     * @param columns 待提取的列名列表
     * @return 提取结果
     */
    public WatermarkResult<String> extract(List<Map<String, Object>> table, List<String> columns) {
        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");

        // 统计每个提取结果的票数
        Map<String, Integer> votes = new LinkedHashMap<>();
        int totalExtracted = 0;

        for (int r = 0; r < table.size(); r++) {
            Map<String, Object> row = table.get(r);
            for (String col : columns) {
                Object value = row.get(col);
                if (value == null) continue;

                SimpleWatermarkStrategy strategy = findStrategy(value);
                if (strategy != null) {
                    String extracted = strategy.extract(value, config.getSecret(), r);
                    if (extracted != null) {
                        votes.merge(extracted, 1, Integer::sum);
                        totalExtracted++;
                    }
                }
            }
        }

        if (votes.isEmpty()) {
            return WatermarkResult.failure("没有找到可提取水印的单元格");
        }

        // 多数投票：选择票数最多的结果
        String winner = null;
        int maxVotes = 0;
        for (Map.Entry<String, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                winner = entry.getKey();
            }
        }

        Set<String> usedColumns = new LinkedHashSet<>(columns);
        double conf = totalExtracted > 0 ? (maxVotes * 100.0 / totalExtracted) : -1;

        WatermarkResult<String> r = WatermarkResult.extractSuccess(winner, new ArrayList<>(usedColumns), totalExtracted);
        r.setWatermarkMode("simple");
        r.setTotalCells(totalExtracted);
        r.setValidExtractions(totalExtracted);
        r.setConfidence(conf);
        return r;
    }

    /**
     * 根据值类型查找匹配的策略。
     */
    private SimpleWatermarkStrategy findStrategy(Object value) {
        for (SimpleWatermarkStrategy s : strategies) {
            if (s.canWatermark(value)) return s;
        }
        return null;
    }
}
