package io.github.nameof.watermark.database;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;
import io.github.nameof.watermark.io.TableData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据库水印的薄适配器。本类对底层数据源（JDBC / CSV / 内存 / 任意）零知识，
 * 仅负责把 {@link TableData} 或 {@link ChunkSource} 提供的数据分块委托给
 * {@link Watermarker} 水印核心，并聚合跨块统计结果。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li><b>单块 API</b>：把单块 {@link TableData} 委托给核心，记录本次统计</li>
 *   <li><b>全表 API</b>：按 {@link ChunkSource} 顺序读取多块，循环嵌入/提取，跨块聚合</li>
 *   <li><b>写回</b>：<b>不负责</b>。调用方拿到本类的结果对象时，输入数据已被原地修改，
 *       写回由调用方自行负责（UPDATE / 文件重写 / 不写回等）</li>
 *   <li><b>数据库连接</b>：<b>不管理</b>。连接生命周期由调用方控制</li>
 * </ul>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 1. 单块嵌入
 * TableData chunk = ...; // 从 JDBC / CSV / 内存获取
 * DatabaseWatermarker wm = new DatabaseWatermarker(new WatermarkConfig("secret"));
 * DatabaseEmbedResult result = wm.embed(chunk, "my-payload");
 * // chunk 的行已被原地修改；调用方自己 UPDATE 回 DB
 *
 * // 2. 全表嵌入（调用方实现 ChunkSource）
 * try (ChunkSource src = new MyJdbcChunkSource(conn, "orders", 2000)) {
 *     DatabaseEmbedResult total = wm.embed(src, "my-payload");
 *     log.info("修改 {} 行 / {} 单元", total.getRowsModified(), total.getCellsModified());
 * }
 *
 * // 3. 全表提取（多数投票）
 * try (ChunkSource src = new MyJdbcChunkSource(conn, "orders", 2000)) {
 *     DatabaseExtractResult r = wm.extract(src);
 *     r.getPayload().ifPresent(p -> log.info("载荷: {}", p));
 * }
 * }</pre>
 *
 * @author chengpan
 */
public final class DatabaseWatermarker {

    private final Watermarker core;

    /**
     * 用 {@link WatermarkConfig} 构造，内部创建一个默认策略配置的 {@link Watermarker}。
     */
    public DatabaseWatermarker(WatermarkConfig config) {
        this(new Watermarker(Objects.requireNonNull(config, "config")));
    }

    /**
     * 直接复用调用方提供的 {@link Watermarker} 实例。
     * 适用于调用方已有自定义策略配置的复杂场景。
     */
    public DatabaseWatermarker(Watermarker core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    // ============================================================
    // 单块 API（直接委托）
    // ============================================================

    /**
     * 对单块 {@link TableData} 进行水印嵌入。自动选择策略。
     *
     * <p><b>输入 {@code chunk} 会被原地修改</b>（{@link TableData#getRows()} 中的 Map
     * 内字段值被替换为水印后的版本）。调用方负责在调用结束后把改动写回持久层。
     *
     * @param chunk   待嵌入的数据块；列名与行内容必须齐全
     * @param payload 水印载荷字符串
     * @return 本次嵌入的统计结果
     */
    public DatabaseEmbedResult embed(TableData chunk, String payload) {
        return embed(chunk, payload, null);
    }

    /**
     * 对单块 {@link TableData} 进行水印嵌入，使用指定策略。
     *
     * <p>传入 {@code null} 或空列表等同于 {@link #embed(TableData, String)} 的自动策略。
     *
     * <p><b>输入 {@code chunk} 不会被原地修改</b>（core 层 embed 内部深拷贝 Map）。
     * 调用方应使用返回结果中的 {@link DatabaseEmbedResult#getModifiedTable()} 获取
     * 水印修改后的数据，再走自己的写回逻辑（UPDATE / 文件重写 / 任意）。
     *
     * @param chunk     待嵌入的数据块
     * @param payload   水印载荷字符串
     * @param strategies 水印策略列表；{@code null}/空表示自动选择
     * @return 本次嵌入的统计结果（含 {@code modifiedTable} 用于写回）
     */
    public DatabaseEmbedResult embed(TableData chunk, String payload, List<WatermarkType> strategies) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(payload, "payload");

        List<String> columns = chunk.getColumnNames();
        List<Map<String, Object>> originalRows = chunk.getRows();
        List<Map<String, Object>> rows = new ArrayList<>(originalRows);

        WatermarkResult<List<Map<String, Object>>> coreResult;
        if (strategies == null || strategies.isEmpty()) {
            coreResult = core.embed(rows, columns, payload);
        } else {
            coreResult = core.embed(rows, columns, payload, strategies);
        }

        int cellsModified = countModifiedCells(originalRows, coreResult.getData(), columns);

        // 构造修改后的 TableData：core 返回的 rows 是新的 List（内部 Map 也是新的），与原 chunk 隔离
        TableData modified = null;
        if (coreResult.isSuccess() && coreResult.getData() != null) {
            modified = new TableData(chunk.getTableName(), columns, coreResult.getData());
        }

        return DatabaseEmbedResult.fromCore(coreResult, chunk.getRowCount(), cellsModified, modified);
    }

    /**
     * 从单块 {@link TableData} 提取水印。
     *
     * <p>输入 {@code chunk} 不会被修改（提取是只读操作）。
     *
     * @param chunk 待提取的数据块
     * @return 本次提取的结果（含载荷、置信度、策略命中）
     */
    public DatabaseExtractResult extract(TableData chunk) {
        Objects.requireNonNull(chunk, "chunk");

        List<String> columns = chunk.getColumnNames();
        // 防御性拷贝：core.extract 不应该改输入，但仍拷贝以隔离外部副作用
        List<Map<String, Object>> rows = new ArrayList<>(chunk.getRows());

        WatermarkResult<String> coreResult = core.extract(rows, columns);
        return DatabaseExtractResult.fromCore(coreResult, chunk.getRowCount());
    }

    /**
     * 判断单块 {@link TableData} 是否可被水印（哪些策略可用）。
     * 不修改输入数据。
     *
     * @param chunk   待分析的数据块
     * @param payload 载荷字符串（仅用于校验）
     * @return 可用策略集合
     */
    public Set<WatermarkType> canWatermark(TableData chunk, String payload) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(payload, "payload");
        List<Map<String, Object>> rows = new ArrayList<>(chunk.getRows());
        return core.canWatermark(rows, chunk.getColumnNames());
    }

    // ============================================================
    // 全表 API（按 ChunkSource 分块循环 + 跨块聚合）
    // ============================================================

    /**
     * 对 {@link ChunkSource} 提供的全部数据块顺序进行水印嵌入，自动选择策略。
     *
     * <p>每块独立嵌入相同 {@code payload}；每块的 {@link TableData} 行会被原地修改，
     * 调用方可在循环结束后（甚至在循环内）把每块改动写回持久层。
     *
     * <p>{@link ChunkSource#close()} 在本方法返回前被调用（无论成功还是异常）。
     * 建议调用方用 try-with-resources 包住 ChunkSource 以保证异常路径也能清理。
     *
     * @param source  数据源，按块顺序提供数据
     * @param payload 水印载荷字符串
     * @return 跨块聚合后的统计结果
     * @throws Exception 透传 {@link ChunkSource#hasNext()} / {@link ChunkSource#nextChunk()} 抛出的异常
     */
    public DatabaseEmbedResult embed(ChunkSource source, String payload) throws Exception {
        return embed(source, payload, null);
    }

    /**
     * 对 {@link ChunkSource} 提供的全部数据块顺序进行水印嵌入，使用指定策略。
     *
     * <p>{@code strategies} 为 {@code null} 或空列表时等同于自动选择。
     */
    public DatabaseEmbedResult embed(ChunkSource source, String payload,
                                     List<WatermarkType> strategies) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(payload, "payload");

        List<DatabaseEmbedResult> partials = new ArrayList<>();
        try {
            while (source.hasNext()) {
                TableData chunk = source.nextChunk();
                partials.add(embed(chunk, payload, strategies));
            }
        } finally {
            try {
                source.close();
            } catch (Exception closeEx) {
                // 关闭异常不掩盖主异常；调用方通过外层 try 可观察到 closeEx
                // 若 partials 非空说明中途出了异常，这里静默吞掉 close 异常
                if (partials.isEmpty()) throw closeEx;
            }
        }
        return aggregateEmbed(partials);
    }

    /**
     * 从 {@link ChunkSource} 提供的全部数据块顺序提取水印，跨块多数投票得出载荷。
     *
     * <p>输入数据不会被修改（提取是只读操作）。
     */
    public DatabaseExtractResult extract(ChunkSource source) throws Exception {
        Objects.requireNonNull(source, "source");

        List<DatabaseExtractResult> partials = new ArrayList<>();
        try {
            while (source.hasNext()) {
                TableData chunk = source.nextChunk();
                partials.add(extract(chunk));
            }
        } finally {
            try {
                source.close();
            } catch (Exception closeEx) {
                if (partials.isEmpty()) throw closeEx;
            }
        }
        return aggregateExtract(partials);
    }

    /**
     * 聚合多次单块嵌入的结果（手动分块场景下调用方自己循环时使用）。
     * 直接委托给 {@link DatabaseEmbedResult#aggregate(List)}。
     */
    public static DatabaseEmbedResult aggregateEmbed(List<DatabaseEmbedResult> partials) {
        return DatabaseEmbedResult.aggregate(partials);
    }

    /**
     * 聚合多次单块提取的结果（多数投票 + 行数加权置信度）。
     * 直接委托给 {@link DatabaseExtractResult#aggregate(List)}。
     */
    public static DatabaseExtractResult aggregateExtract(List<DatabaseExtractResult> partials) {
        return DatabaseExtractResult.aggregate(partials);
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    /**
     * 统计被 core 修改的单元格数。
     * 对比输入 rows 与 core 返回的 watermarked rows（Map 深拷贝），
     * 数出哪些 (row, col) 的 value 发生了变化。
     *
     * <p>此方法独立于 core 层的 totalCells 字段——core 对 embed 路径不设置
     * totalCells（仅 extract 路径设置），Database 层自行统计更可靠。
     */
    private static int countModifiedCells(List<Map<String, Object>> original,
                                          List<Map<String, Object>> watermarked,
                                          List<String> columns) {
        if (watermarked == null) return 0;
        int count = 0;
        int maxRows = Math.min(original.size(), watermarked.size());
        for (int r = 0; r < maxRows; r++) {
            Map<String, Object> origRow = original.get(r);
            Map<String, Object> wmRow = watermarked.get(r);
            if (wmRow == null) continue;
            for (String col : columns) {
                if (!Objects.equals(origRow.get(col), wmRow.get(col))) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 包内访问：暴露核心实例，供全表 API 使用。
     */
    Watermarker getCore() {
        return core;
    }
}