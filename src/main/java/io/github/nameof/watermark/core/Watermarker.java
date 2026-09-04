package io.github.nameof.watermark.core;

import io.github.nameof.watermark.core.bit.BitCarrierStrategy;
import io.github.nameof.watermark.core.bit.BitEmbedResult;
import io.github.nameof.watermark.core.bit.ChineseTextWatermarkStrategy;
import io.github.nameof.watermark.core.bit.LatinTextWatermarkStrategy;
import io.github.nameof.watermark.core.bit.NumericWatermarkStrategy;
import io.github.nameof.watermark.core.simple.InvisiblePaddingStrategy;
import io.github.nameof.watermark.core.simple.SimpleWatermarkStrategy;
import io.github.nameof.watermark.core.simple.SuffixMarkerStrategy;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 核心统一水印 API —— 合并 bit-level 和 simple 两种水印模式。
 *
 * <h2>架构分层</h2>
 * <p>本类处于<b>编码/扩频层</b>，与策略层（载体层）职责分离：</p>
 * <ol>
 *   <li><b>载体层（策略，单值粒度）</b>：{@link BitCarrierStrategy} 负责在单个值中
 *       隐蔽地嵌入/提取 1 bit（零宽字符、同形字、末位微扰），
 *       {@link SimpleWatermarkStrategy} 负责在单个值中承载完整载荷。
 *       策略只做单值变换，通过 {@code seed}（由本类从值的稳定标识计算）
 *       选择嵌入位置，完全不感知数据集形态。</li>
 *   <li><b>编码/扩频层（本类，多值粒度）</b>：一次完整水印的嵌入天然需要多个值。
 *       本类负责把载荷编码为 bit 流，并冗余分布到整个数据集：
 *       <ul>
 *         <li><b>两段式编码</b>（bit-level 模式）：前 HEADER_BITS×3 = 24 个单元格编码
 *             8-bit 长度头部，剩余单元格编码载荷+CRC32。提取时先读头部确定精确长度。</li>
 *         <li><b>交叉分配</b>（bit-level 模式）：每个 bit 的 R 个副本均匀分布在整个
 *             数据范围内（cell j → bit j%N），删除任意连续区域只影响每个 bit 的少量副本，
 *             通过多数投票恢复，容忍高达 (R-1)/R 比例的数据丢失。</li>
 *         <li><b>独立编码</b>（simple 模式）：每个单元格独立承载完整载荷，提取时做多数投票。</li>
 *         <li><b>密钥流置乱</b>（bit-level 模式）：载荷 bit 在嵌入前与密钥派生的
 *             0/1 密钥流异或，提取时对称还原——不知道密钥则 CRC32 校验失败，
 *             无法读出载荷（长度头部不做置乱）。</li>
 *       </ul></li>
 * </ol>
 * <p>当前公开 API 面向表格数据（{@code List<Map<String,Object>>} + 列名）；
 * 由于策略层已与表格坐标解耦（seed 化），未来支持非表格数据集
 * （文档、JSON 等）时只需增加新的数据集展平入口，复用全部策略与编码逻辑。</p>
 *
 * <h2>容量需求（bit-level）</h2>
 * <p>
 * 总单元格 M = 头部(24) + 载荷区(M-24)。
 * 载荷 bit 数 N = (1 + payloadLen + 4) × 8，重复因子 R = (M-24) / N。
 * 需要 R ≥ config.minRepetition（默认 5）才能可靠提取。
 * </p>
 *
 * <h2>数据量约定</h2>
 * <p>
 * Core 层只接收内存中的 {@code List<Map<String, Object>>} 数据集。
 * 调用方应控制数据量在合理范围内（建议单次不超过几万行），避免内存溢出。
 * 大数据场景由 Database 层负责切片处理。
 * </p>
 */
public class Watermarker {

    /** 头部 bit 数：8 bit 可表示 0-255 字节载荷长度 */
    private static final int HEADER_BITS = 8;
    /** 头部重复因子：每个头部 bit 由 3 个单元格投票 */
    private static final int HEADER_REP = 3;
    /** 头部占用单元格数 */
    private static final int HEADER_CELLS = HEADER_BITS * HEADER_REP; // 24
    /** 载荷最大字节数（UTF-8），受 8-bit 头部长度字段约束并为编码开销留余量 */
    public static final int MAX_PAYLOAD_BYTES = 251;

    private final WatermarkConfig config;
    private final List<BitCarrierStrategy> bitStrategies;
    private final List<SimpleWatermarkStrategy> simpleStrategies;

    /**
     * 使用默认策略创建 Watermarker。
     * <p>
     * 默认 bit-level 策略（按<b>最具体优先</b>的顺序注册与匹配）：
     * Numeric（仅数值）、LatinText（含可替换拉丁字符）、ChineseText（任意 ≥2 字符文本，最泛化）；
     * 默认 simple 策略：SuffixMarker、InvisiblePadding。
     * </p>
     *
     * @param config 水印配置
     */
    public Watermarker(WatermarkConfig config) {
        this.config = config;
        this.bitStrategies = new ArrayList<>();
        // 注册顺序即匹配顺序，必须"最具体优先"：
        // ChineseText 对任意 ≥2 字符的值都返回 canWatermark=true，
        // 若排在前面会吞掉所有值，Numeric/Latin 永远无法被分发。
        // 嵌入与提取共用同一顺序，保证同一值在两端路由到同一策略。
        bitStrategies.add(new NumericWatermarkStrategy());
        bitStrategies.add(new LatinTextWatermarkStrategy());
        bitStrategies.add(new ChineseTextWatermarkStrategy());
        this.simpleStrategies = new ArrayList<>();
        simpleStrategies.add(new SuffixMarkerStrategy());
        simpleStrategies.add(new InvisiblePaddingStrategy());
    }

    /**
     * 使用自定义策略创建 Watermarker。
     * <p>
     * 注意：策略列表的顺序即值匹配顺序，应按"最具体优先"排列
     * （先匹配窄条件的策略，最后才是泛化策略），否则泛化策略会吞掉所有值。
     * 嵌入与提取共用此顺序。
     * </p>
     *
     * @param config           水印配置
     * @param bitStrategies    bit-level 策略列表，可为 null
     * @param simpleStrategies simple 策略列表，可为 null
     */
    public Watermarker(WatermarkConfig config,
                       List<BitCarrierStrategy> bitStrategies,
                       List<SimpleWatermarkStrategy> simpleStrategies) {
        this.config = config;
        this.bitStrategies = bitStrategies != null ? new ArrayList<>(bitStrategies) : new ArrayList<BitCarrierStrategy>();
        this.simpleStrategies = simpleStrategies != null ? new ArrayList<>(simpleStrategies) : new ArrayList<SimpleWatermarkStrategy>();
    }

    // ==================== 公开 API ====================

    /**
     * 扫描数据，返回支持的所有水印类型。
     *
     * @param table   表数据
     * @param columns 待检测的列名列表
     * @return 支持的水印类型集合
     */
    public Set<WatermarkType> canWatermark(List<Map<String, Object>> table, List<String> columns) {
        Set<WatermarkType> result = new LinkedHashSet<>();
        if (table == null || table.isEmpty() || columns == null || columns.isEmpty()) return result;

        for (String col : columns) {
            for (Map<String, Object> row : table) {
                Object value = row.get(col);
                if (value == null) continue;
                for (BitCarrierStrategy s : bitStrategies) {
                    if (s.canWatermark(value)) {
                        result.add(s.type());
                    }
                }
                for (SimpleWatermarkStrategy s : simpleStrategies) {
                    if (s.canWatermark(value)) {
                        result.add(s.type());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 嵌入水印。
     * <p>
     * strategies 为 null 时自动选择策略（bit-level 优先，容量不足降级 simple）。
     * </p>
     *
     * @param table      表数据
     * @param columns    待嵌入的列名列表
     * @param payload    水印载荷
     * @param strategies 水印类型列表，null 时自动选择
     * @return 嵌入结果
     */
    public WatermarkResult<List<Map<String, Object>>> embed(
            List<Map<String, Object>> table, List<String> columns,
            String payload, List<WatermarkType> strategies) {

        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");
        if (payload == null || payload.isEmpty())
            return WatermarkResult.failure("水印载荷为空");
        if (columns == null || columns.isEmpty())
            return WatermarkResult.failure("列名列表为空");

        // 载荷长度校验（UTF-8 字节数），统一以 failure 结果返回而非抛出异常
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > MAX_PAYLOAD_BYTES) {
            return WatermarkResult.failure("水印载荷过长: " + payloadBytes
                    + " 字节，最大支持 " + MAX_PAYLOAD_BYTES + " 字节");
        }

        // 确定要使用的策略
        boolean useBit = true;
        List<WatermarkType> effectiveTypes = strategies;
        if (effectiveTypes == null || effectiveTypes.isEmpty()) {
            effectiveTypes = autoSelectStrategies(table, columns, payload);
        }

        // 判断是否包含 simple 类型
        for (WatermarkType t : effectiveTypes) {
            if (!t.isBitLevel()) {
                useBit = false;
                break;
            }
        }

        if (useBit) {
            return embedBitLevel(table, columns, payload, effectiveTypes);
        } else {
            return embedSimple(table, columns, payload, effectiveTypes);
        }
    }

    /**
     * 嵌入水印（自动选择策略）。
     */
    public WatermarkResult<List<Map<String, Object>>> embed(
            List<Map<String, Object>> table, List<String> columns, String payload) {
        return embed(table, columns, payload, null);
    }

    /**
     * 提取水印。
     * <p>
     * 自动尝试 bit-level 和 simple 两种模式，返回最可靠的结果。
     * 优先尝试 bit-level（鲁棒性更强），失败后降级到 simple。
     * </p>
     *
     * @param table   表数据
     * @param columns 包含水印的列名列表
     * @return 提取结果
     */
    public WatermarkResult<String> extract(List<Map<String, Object>> table, List<String> columns) {
        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");
        if (columns == null || columns.isEmpty())
            return WatermarkResult.failure("列名列表为空");

        // 尝试两种模式的提取
        WatermarkResult<String> bitResult = extractBitLevel(table, columns);
        WatermarkResult<String> simpleResult = extractSimple(table, columns);

        boolean bitOk = bitResult.isSuccess();
        boolean simpleOk = simpleResult.isSuccess();

        if (!bitOk && !simpleOk) {
            return WatermarkResult.failure("无法还原有效的水印载荷。"
                    + "可能数据被大量删除或修改，或使用了错误的密钥。");
        }
        if (bitOk && !simpleOk) return bitResult;
        if (!bitOk && simpleOk) return simpleResult;

        // 两种模式都成功：优先选择置信度更高的
        if (bitResult.getConfidence() >= simpleResult.getConfidence()) {
            return bitResult;
        } else {
            return simpleResult;
        }
    }

    // ==================== Bit-level 嵌入/提取 ====================

    /**
     * Bit-level 嵌入（两段式编码 + 交叉分配）。
     */
    private WatermarkResult<List<Map<String, Object>>> embedBitLevel(
            List<Map<String, Object>> table, List<String> columns,
            String payload, List<WatermarkType> types) {

        // 1. 扫描可嵌入单元格（只扫描指定类型对应的策略）
        List<CellRef> cells = scanCellsForBit(table, columns, types);
        int M = cells.size();
        if (M == 0) return WatermarkResult.failure("没有找到可嵌入水印的单元格");
        if (M <= HEADER_CELLS)
            return WatermarkResult.failure("可嵌入单元格不足（" + M + "个），至少需要 " + (HEADER_CELLS + 1) + " 个");

        // 2. 编码载荷，并用密钥流置乱 bit —— 使提取依赖密钥（错误密钥 → CRC32 校验失败）
        byte[] encoded = encodePayload(payload);
        int[] payloadBits = bytesToBits(encoded);
        int[] keyStream = keyStream(payloadBits.length);
        for (int i = 0; i < payloadBits.length; i++) payloadBits[i] ^= keyStream[i];
        int N = payloadBits.length;

        // 3. 容量检查
        int payloadCells = M - HEADER_CELLS;
        int R = payloadCells / N;
        if (R < config.getMinRepetition()) {
            int maxBytes = (payloadCells / config.getMinRepetition()) / 8 - 5;
            return WatermarkResult.failure(
                "可嵌入单元格不足（" + M + "个），载荷需要 " + N + " bits（重复因子仅 " + R + "）。"
                + "建议：增加数据行数或缩短载荷（当前最多支持 " + Math.max(0, maxBytes) + " 字节）。");
        }

        // 4. 深拷贝
        List<Map<String, Object>> result = new ArrayList<>(table.size());
        for (Map<String, Object> row : table) result.add(new LinkedHashMap<>(row));

        // 5. 嵌入头部：载荷字节长度 → 8 bits，每 bit 重复 3 次
        int payloadByteLen = encoded.length;
        int[] headerBits = new int[HEADER_BITS];
        for (int i = 0; i < HEADER_BITS; i++) {
            headerBits[i] = (payloadByteLen >> (7 - i)) & 1;
        }
        for (int i = 0; i < HEADER_CELLS; i++) {
            int bitPos = i / HEADER_REP;
            CellRef cell = cells.get(i);
            Object value = result.get(cell.row).get(cell.column);
            BitCarrierStrategy strategy = findBitStrategy(value, types);
            if (strategy != null) {
                long seed = cellSeed(cell.row, cell.colIndex);
                BitEmbedResult er = strategy.embed(value, headerBits[bitPos], config.getSecret(), seed);
                if (er.isEmbedded()) {
                    result.get(cell.row).put(cell.column, er.getValue());
                }
            }
        }

        // 6. 嵌入载荷：交叉分配，每组包含全部 N 个 bit
        for (int i = 0; i < payloadCells; i++) {
            int bitPos = i % N;
            CellRef cell = cells.get(HEADER_CELLS + i);
            Object value = result.get(cell.row).get(cell.column);
            long seed = cellSeed(cell.row, cell.colIndex);
            BitCarrierStrategy strategy = findBitStrategy(value, types);
            if (strategy != null) {
                BitEmbedResult er = strategy.embed(value, payloadBits[bitPos], config.getSecret(), seed);
                if (er.isEmbedded()) {
                    result.get(cell.row).put(cell.column, er.getValue());
                }
            }
        }

        // 7. 收集修改过的列
        Set<String> usedColumns = new LinkedHashSet<>();
        for (int i = 0; i < M; i++) {
            CellRef cell = cells.get(i);
            if (!Objects.equals(table.get(cell.row).get(cell.column),
                                result.get(cell.row).get(cell.column)))
                usedColumns.add(cell.column);
        }

        // 确定主水印类型（取第一个 bit-level 类型）
        WatermarkType primaryType = null;
        for (WatermarkType t : types) {
            if (t.isBitLevel()) { primaryType = t; break; }
        }

        WatermarkResult<List<Map<String, Object>>> r =
                WatermarkResult.embedSuccess(result, new ArrayList<>(usedColumns), R);
        r.setWatermarkType(primaryType);
        return r;
    }

    /**
     * Bit-level 提取。
     */
    private WatermarkResult<String> extractBitLevel(List<Map<String, Object>> table, List<String> columns) {
        List<CellRef> cells = scanCellsForBit(table, columns, null);
        int M = cells.size();
        if (M <= HEADER_CELLS)
            return WatermarkResult.failure("可提取单元格不足，无法还原水印");

        // 1. 提取头部：前 24 个单元格 → 8 bits（每 3 个一组多数投票）
        int payloadByteLen = 0;
        for (int b = 0; b < HEADER_BITS; b++) {
            int ones = 0, total = 0;
            for (int j = b * HEADER_REP; j < (b + 1) * HEADER_REP && j < M; j++) {
                CellRef cell = cells.get(j);
                BitCarrierStrategy strategy = findAnyBitStrategy(cell.value);
                if (strategy != null) {
                    long seed = cellSeed(cell.row, cell.colIndex);
                    int bit = strategy.extract(cell.value, config.getSecret(), seed);
                    if (bit >= 0) {
                        total++;
                        if (bit == 1) ones++;
                    }
                }
            }
            int headerBit = (total > 0 && ones > total / 2) ? 1 : 0;
            payloadByteLen = (payloadByteLen << 1) | headerBit;
        }

        // 2. 验证载荷长度合理性
        if (payloadByteLen < 5 || payloadByteLen > 256) {
            return WatermarkResult.failure("头部长度异常: " + payloadByteLen);
        }

        // 3. 提取载荷 bits
        int N = payloadByteLen * 8;
        int payloadCells = M - HEADER_CELLS;
        int R = payloadCells / N;
        if (R < 1)
            return WatermarkResult.failure("可提取单元格不足，无法还原水印");

        int[] onesCount = new int[N];
        int[] totalCount = new int[N];

        for (int i = 0; i < payloadCells; i++) {
            int bitPos = i % N;
            CellRef cell = cells.get(HEADER_CELLS + i);
            BitCarrierStrategy strategy = findAnyBitStrategy(cell.value);
            if (strategy != null) {
                long seed = cellSeed(cell.row, cell.colIndex);
                int bit = strategy.extract(cell.value, config.getSecret(), seed);
                if (bit >= 0) {
                    totalCount[bitPos]++;
                    if (bit == 1) onesCount[bitPos]++;
                }
            }
        }

        // 4. 多数投票恢复 bits，再用同一密钥流还原（嵌入时做过对称置乱）
        int[] recovered = new int[N];
        for (int b = 0; b < N; b++) {
            recovered[b] = (totalCount[b] > 0 && onesCount[b] > totalCount[b] / 2) ? 1 : 0;
        }
        int[] keyStream = keyStream(N);
        for (int b = 0; b < N; b++) recovered[b] ^= keyStream[b];

        // 5. 解码并验证 CRC32
        byte[] bytes = bitsToBytes(recovered);
        String decoded = tryDecodePayload(bytes, payloadByteLen);
        if (decoded != null) {
            Set<String> usedColumns = new LinkedHashSet<>();
            for (CellRef cell : cells) usedColumns.add(cell.column);

            // 计算提取统计
            int validTotal = 0;
            int consistentBits = 0;
            for (int b = 0; b < N; b++) {
                if (totalCount[b] > 0) {
                    validTotal++;
                    int majority = onesCount[b] > totalCount[b] / 2 ? 1 : 0;
                    int majorityCount = majority == 1 ? onesCount[b] : (totalCount[b] - onesCount[b]);
                    if (totalCount[b] > 0 && majorityCount > totalCount[b] / 2) {
                        consistentBits++;
                    }
                }
            }
            double conf = validTotal > 0 ? (consistentBits * 100.0 / validTotal) : -1;

            // 检测实际使用的水印类型
            WatermarkType detectedType = detectBitWatermarkType(cells);

            WatermarkResult<String> r = WatermarkResult.extractSuccess(decoded, new ArrayList<>(usedColumns), R);
            r.setWatermarkType(detectedType);
            r.setTotalCells(M);
            r.setValidExtractions(validTotal);
            r.setConfidence(conf);
            return r;
        }

        return WatermarkResult.failure("CRC32 校验不匹配");
    }

    // ==================== Simple 嵌入/提取 ====================

    /**
     * Simple 嵌入（每个单元格独立承载完整载荷）。
     */
    private WatermarkResult<List<Map<String, Object>>> embedSimple(
            List<Map<String, Object>> table, List<String> columns,
            String payload, List<WatermarkType> types) {

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

                SimpleWatermarkStrategy strategy = findSimpleStrategy(value, types);
                if (strategy != null) {
                    long seed = rowSeed(r);
                    String embedded = strategy.embed(value, payload, config.getSecret(), seed);
                    row.put(col, embedded);
                    embeddedCount++;
                    usedColumns.add(col);
                }
            }
        }

        if (embeddedCount == 0) {
            return WatermarkResult.failure("没有找到可嵌入水印的单元格");
        }

        // 确定主水印类型
        WatermarkType primaryType = null;
        for (WatermarkType t : types) {
            if (!t.isBitLevel()) { primaryType = t; break; }
        }

        WatermarkResult<List<Map<String, Object>>> r =
                WatermarkResult.embedSuccess(result, new ArrayList<>(usedColumns), embeddedCount);
        r.setWatermarkType(primaryType);
        return r;
    }

    /**
     * Simple 提取（多数投票）。
     */
    private WatermarkResult<String> extractSimple(List<Map<String, Object>> table, List<String> columns) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        int totalExtracted = 0;

        for (int r = 0; r < table.size(); r++) {
            Map<String, Object> row = table.get(r);
            for (String col : columns) {
                Object value = row.get(col);
                if (value == null) continue;

                SimpleWatermarkStrategy strategy = findAnySimpleStrategy(value);
                if (strategy != null) {
                    long seed = rowSeed(r);
                    String extracted = strategy.extract(value, config.getSecret(), seed);
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

        // 多数投票
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

        // 检测实际使用的水印类型
        WatermarkType detectedType = detectSimpleWatermarkType(table, columns);

        WatermarkResult<String> r = WatermarkResult.extractSuccess(winner, new ArrayList<>(usedColumns), totalExtracted);
        r.setWatermarkType(detectedType);
        r.setTotalCells(totalExtracted);
        r.setValidExtractions(totalExtracted);
        r.setConfidence(conf);
        return r;
    }

    // ==================== 策略自动选择 ====================

    /**
     * 自动选择水印策略。
     * <p>
     * 优先 bit-level（隐蔽性、鲁棒性强），容量不足降级 simple。
     * </p>
     */
    private List<WatermarkType> autoSelectStrategies(
            List<Map<String, Object>> table, List<String> columns, String payload) {

        // 先尝试 bit-level
        List<CellRef> bitCells = scanCellsForBit(table, columns, null);
        int M = bitCells.size();
        if (M > HEADER_CELLS) {
            byte[] encoded = encodePayload(payload);
            int N = encoded.length * 8;
            int R = (M - HEADER_CELLS) / N;
            if (R >= config.getMinRepetition()) {
                // bit-level 容量足够，收集实际支持的 bit-level 类型
                List<WatermarkType> bitTypes = new ArrayList<>();
                for (BitCarrierStrategy s : bitStrategies) {
                    for (Map<String, Object> row : table) {
                        for (String col : columns) {
                            Object v = row.get(col);
                            if (v != null && s.canWatermark(v)) {
                                bitTypes.add(s.type());
                                break;
                            }
                        }
                        if (bitTypes.contains(s.type())) break;
                    }
                }
                if (!bitTypes.isEmpty()) return bitTypes;
            }
        }

        // 降级到 simple
        List<WatermarkType> simpleTypes = new ArrayList<>();
        for (SimpleWatermarkStrategy s : simpleStrategies) {
            for (Map<String, Object> row : table) {
                for (String col : columns) {
                    Object v = row.get(col);
                    if (v != null && s.canWatermark(v)) {
                        simpleTypes.add(s.type());
                        break;
                    }
                }
                if (simpleTypes.contains(s.type())) break;
            }
        }
        if (!simpleTypes.isEmpty()) return simpleTypes;

        // 兜底：返回所有 bit-level 类型
        List<WatermarkType> fallback = new ArrayList<>();
        for (BitCarrierStrategy s : bitStrategies) fallback.add(s.type());
        return fallback;
    }

    // ==================== 内部方法 ====================

    /**
     * 整数置乱函数（用于生成位置种子）。
     */
    private int scramble(int input, String secret) {
        long h = input * 31L + secret.hashCode();
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = h ^ (h >>> 16);
        return (int) Math.floorMod(h, Integer.MAX_VALUE);
    }

    /**
     * 计算单元格值的位置种子（bit-level 策略使用）。
     * <p>
     * 由密钥 + 行列坐标混合而成。嵌入与提取必须以相同方式计算，
     * 只要数据集的行序与列序保持稳定，同一单元格总能得到同一 seed。
     * </p>
     */
    private long cellSeed(int row, int col) {
        return ((long) scramble(row, config.getSecret()) << 32)
                | (scramble(col, config.getSecret()) & 0xFFFFFFFFL);
    }

    /**
     * 计算行的位置种子（simple 策略使用）。
     */
    private long rowSeed(int row) {
        return scramble(row, config.getSecret());
    }

    /**
     * 由密钥派生确定性 0/1 密钥流。
     * <p>
     * bit-level 载荷在嵌入前与密钥流异或，提取时对称还原，
     * 使"不知道密钥就无法读出载荷"（错误密钥 → CRC32 校验失败）。
     * 注意：长度头部（8 bit）不做置乱，错误密钥下表现为载荷区 CRC 不匹配。
     * </p>
     */
    private int[] keyStream(int length) {
        long seed = 1125899906842597L;
        String secret = config.getSecret();
        for (int i = 0; i < secret.length(); i++) {
            seed = 31 * seed + secret.charAt(i);
        }
        java.util.Random rnd = new java.util.Random(seed);
        int[] ks = new int[length];
        for (int i = 0; i < length; i++) {
            ks[i] = rnd.nextInt(2);
        }
        return ks;
    }

    /**
     * 扫描表中所有可嵌入 bit-level 水印的单元格。
     * 如果 types 为 null，扫描所有 bit-level 策略支持的单元格。
     */
    private List<CellRef> scanCellsForBit(List<Map<String, Object>> table,
                                           List<String> columns,
                                           List<WatermarkType> types) {
        List<CellRef> result = new ArrayList<>();
        for (int r = 0; r < table.size(); r++) {
            Map<String, Object> row = table.get(r);
            for (int c = 0; c < columns.size(); c++) {
                Object value = row.get(columns.get(c));
                if (value != null && findBitStrategy(value, types) != null)
                    result.add(new CellRef(r, c, columns.get(c), value));
            }
        }
        return result;
    }

    /**
     * 根据值查找匹配的 bit-level 策略。
     * types 为 null 时使用所有已注册的 bit-level 策略。
     */
    private BitCarrierStrategy findBitStrategy(Object value, List<WatermarkType> types) {
        for (BitCarrierStrategy s : bitStrategies) {
            if (types != null && !types.contains(s.type())) continue;
            if (s.canWatermark(value)) return s;
        }
        return null;
    }

    /**
     * 查找任意匹配的 bit-level 策略（提取时使用）。
     */
    private BitCarrierStrategy findAnyBitStrategy(Object value) {
        for (BitCarrierStrategy s : bitStrategies)
            if (s.canWatermark(value)) return s;
        return null;
    }

    /**
     * 根据值查找匹配的 simple 策略。
     */
    private SimpleWatermarkStrategy findSimpleStrategy(Object value, List<WatermarkType> types) {
        for (SimpleWatermarkStrategy s : simpleStrategies) {
            if (types != null && !types.contains(s.type())) continue;
            if (s.canWatermark(value)) return s;
        }
        return null;
    }

    /**
     * 查找任意匹配的 simple 策略（提取时使用）。
     */
    private SimpleWatermarkStrategy findAnySimpleStrategy(Object value) {
        for (SimpleWatermarkStrategy s : simpleStrategies)
            if (s.canWatermark(value)) return s;
        return null;
    }

    /**
     * 检测 bit-level 水印类型（通过检查第一个可嵌入单元格匹配的策略）。
     */
    private WatermarkType detectBitWatermarkType(List<CellRef> cells) {
        for (CellRef cell : cells) {
            for (BitCarrierStrategy s : bitStrategies) {
                if (s.canWatermark(cell.value)) {
                    return s.type();
                }
            }
        }
        return WatermarkType.BIT_CHINESE_ZERO_WIDTH;
    }

    /**
     * 检测 simple 水印类型（通过检查第一个可嵌入单元格匹配的策略）。
     */
    private WatermarkType detectSimpleWatermarkType(List<Map<String, Object>> table, List<String> columns) {
        for (Map<String, Object> row : table) {
            for (String col : columns) {
                Object value = row.get(col);
                if (value == null) continue;
                for (SimpleWatermarkStrategy s : simpleStrategies) {
                    if (s.canWatermark(value)) {
                        return s.type();
                    }
                }
            }
        }
        return WatermarkType.SIMPLE_SUFFIX_MARKER;
    }

    // ---------- 载荷编码/解码 ----------

    /**
     * 将载荷字符串编码为字节数组。
     * <p>
     * 编码格式：[length:1byte][payload:N bytes][crc32:4bytes]
     * </p>
     */
    private byte[] encodePayload(String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int len = data.length;
        if (len > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("载荷过长: " + len + " 字节，最大 " + MAX_PAYLOAD_BYTES);
        byte[] result = new byte[len + 5];
        result[0] = (byte) len;
        System.arraycopy(data, 0, result, 1, len);
        long crc = crc32(result, 0, len + 1);
        result[len + 1] = (byte) (crc >> 24);
        result[len + 2] = (byte) (crc >> 16);
        result[len + 3] = (byte) (crc >> 8);
        result[len + 4] = (byte) crc;
        return result;
    }

    /**
     * 尝试从字节数组中解码载荷。
     * 验证 length 字段一致性和 CRC32 校验和。
     */
    private String tryDecodePayload(byte[] bytes, int expectedLen) {
        if (bytes == null || bytes.length < expectedLen) return null;
        int len = bytes[0] & 0xFF;
        if (len != expectedLen - 5 || len + 5 > bytes.length) return null;
        long expectedCrc = crc32(bytes, 0, len + 1);
        long actualCrc = ((long)(bytes[len+1] & 0xFF) << 24) | ((long)(bytes[len+2] & 0xFF) << 16)
                       | ((long)(bytes[len+3] & 0xFF) << 8)  | (bytes[len+4] & 0xFF);
        if (expectedCrc != actualCrc) return null;
        byte[] payloadBytes = new byte[len];
        System.arraycopy(bytes, 1, payloadBytes, 0, len);
        return new String(payloadBytes, StandardCharsets.UTF_8);
    }

    /**
     * CRC32 校验和计算（ISO 3309 / ITU-T V.42 标准）。
     */
    private long crc32(byte[] data, int offset, int length) {
        long crc = 0xFFFFFFFFL;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++)
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xEDB88320L : crc >>> 1;
        }
        return crc ^ 0xFFFFFFFFL;
    }

    // ---------- 位/字节转换 ----------

    private int[] bytesToBits(byte[] bytes) {
        int[] bits = new int[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++)
            for (int j = 0; j < 8; j++)
                bits[i * 8 + j] = (bytes[i] >> (7 - j)) & 1;
        return bits;
    }

    private byte[] bitsToBytes(int[] bits) {
        int numBytes = bits.length / 8;
        byte[] bytes = new byte[numBytes];
        for (int i = 0; i < numBytes; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) b = (b << 1) | bits[i * 8 + j];
            bytes[i] = (byte) b;
        }
        return bytes;
    }

    private static class CellRef {
        final int row, colIndex;
        final String column;
        final Object value;
        CellRef(int row, int colIndex, String column, Object value) {
            this.row = row; this.colIndex = colIndex;
            this.column = column; this.value = value;
        }
    }
}
