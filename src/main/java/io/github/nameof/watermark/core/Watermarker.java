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
 *         <li><b>两段式编码</b>（bit-level 模式）：头部 8 个槽位编码 8-bit 长度头部，
 *             其余槽位编码载荷+CRC32。提取时先读头部确定精确长度
 *             （长度反过来决定槽位总数，故提取端按候选长度扫描，用 CRC32 定夺）。</li>
 *         <li><b>稳定键哈希分桶</b>（bit-level 模式）：每个单元格按**自身内容的稳定键**
 *             认领槽位（{@code slot = mixHash(secret, cellKey) % (24 + N)}），与行序无关。
 *             因此任意位置的增删行只会损失该单元格自己的 1 票，其余副本不受影响，
 *             通过多数投票恢复，真正达到 (R-1)/R 比例的删除容忍度。</li>
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
 * 载荷 bit 数 N = (1 + payloadLen + 4) × 8，槽位总数 = 8（头部）+ N。
 * 重复因子 R = <b>独立投票者数</b> ÷ 槽位总数，需要 R ≥ config.minRepetition（默认 5）。
 * </p>
 * <p>
 * “独立投票者”指内容互不相同的单元格：位槽由内容的稳定键决定，
 * 内容相同（或数值列经末位量化后相撞）的单元格会共享同一槽位、只贡献 1 票。
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

    /** 长度字段 bit 数：8 bit 表示 (载荷字节数 + 5) */
    private static final int HEADER_BITS = 8;
    /**
     * 头部占用槽位数：每个头部 bit 一个槽。
     * <p>
     * <b>为什么不是 24</b>：旧实现（位置取模）把头部固定写在前 24 个单元格里，
     * 只能靠"1 个 bit 占 3 格"凑出 3 票冗余（{@code HEADER_REP=3}）。
     * 改用稳定键哈希分桶后，每个槽天然获得约 R 个投票者，
     * 头部槽与载荷槽享受同样的冗余 —— 因此 1 槽/bit 即可，
     * 头部开销从 24 槽降为 8 槽（省下的槽位全部给载荷，R 相应提高）。
     * </p>
     */
    private static final int HEADER_CELLS = HEADER_BITS; // 8
    /**
     * 提取时允许的"零票槽"上限。
     * <p>
     * 数据被删除后，某些位可能一个投票者都不剩（取值未知）。这些位用穷举补全 + CRC32
     * 校验找回；上限用来避免候选数爆炸（2^k 次 CRC 校验）。
     * </p>
     */
    private static final int MAX_ERASURES = 12;
    /**
     * 载荷最大字节数（UTF-8）。
     * <p>
     * 头部用 8 bit 表示 (载荷字节数 + 5)，可表示 0..255，故载荷上限为 250 字节。
     * （旧值 251 会让 256 溢出为 0，头部读回长度为 0 → 提取必然失败。）
     * </p>
     */
    public static final int MAX_PAYLOAD_BYTES = 250;

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
        return extract(table, columns, null);
    }

    /**
     * 提取水印（可指定策略白名单）。
     * <p>
     * <b>types 必须与嵌入端传入的一致</b>：否则同一个值在两端可能路由到不同策略
     * （例如值里既有中文又有拉丁字母、而显式只指定了 Chinese 策略时，
     * extract 若不过滤就会走 Latin，提取结果恒为 0 → 失败）。
     * 传 null 表示不限，对应"自动选择策略"的嵌入路径。
     * </p>
     *
     * @param table   表数据
     * @param columns 包含水印的列名列表
     * @param types   策略白名单，null 表示不限
     * @return 提取结果
     */
    public WatermarkResult<String> extract(List<Map<String, Object>> table, List<String> columns,
                                           List<WatermarkType> types) {
        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");
        if (columns == null || columns.isEmpty())
            return WatermarkResult.failure("列名列表为空");

        // 尝试两种模式的提取
        WatermarkResult<String> bitResult = extractBitLevel(table, columns, types);
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

    // ==================== Values API（新增，ADR-0001 渐进式）====================

    /**
     * Values API 包装用虚拟列名（仅在内部把 List<?> 包装为 List<Map> 时使用，对外不可见）。
     */
    private static final String VALUES_API_VIRTUAL_COLUMN = "_value";
    private static final List<String> VALUES_API_VIRTUAL_COLUMN_LIST =
            Collections.singletonList(VALUES_API_VIRTUAL_COLUMN);

    /**
     * 扫描值序列，返回支持的所有水印类型（值序列 API）。
     *
     * @param values 值序列
     * @return 支持的水印类型集合
     */
    public Set<WatermarkType> canWatermark(List<?> values) {
        Set<WatermarkType> result = new LinkedHashSet<>();
        if (values == null || values.isEmpty()) return result;
        for (Object value : values) {
            if (value == null) continue;
            for (BitCarrierStrategy s : bitStrategies) {
                if (s.canWatermark(value)) result.add(s.type());
            }
            for (SimpleWatermarkStrategy s : simpleStrategies) {
                if (s.canWatermark(value)) result.add(s.type());
            }
        }
        return result;
    }

    /**
     * 嵌入水印（值序列 API，自动选择策略）。
     * <p>
     * 与 {@link #embed(List, List, String, List)}（Table API）的区别：
     * 本方法接受任意类型的值序列，无需指定列名，更接近水印的数学抽象
     * （有序、稳定、可微修改的值序列）。适合非表格场景
     * （JSON 数组、文档字符序列、纯 {@code List<String>} 等）。
     * </p>
     * <p>
     * <b>Seed / 顺序约定</b>（ADR-0002 方案 A 起）：内部将值序列包装为单虚拟列的 table 后复用
     * Table API；bit-level 的位槽由每个值自身的**稳定键**（剥离水印痕迹后的原值哈希）决定，
     * 与序列顺序无关。simple 模式本就每个值独立承载完整载荷，也与顺序无关。
     * 因此嵌入与提取之间**允许增删值、允许乱序**。
     * </p>
     *
     * @param values  值序列
     * @param payload 水印载荷
     * @return 嵌入结果
     */
    public WatermarkResult<List<Object>> embed(List<?> values, String payload) {
        return embed(values, payload, null);
    }

    /**
     * 嵌入水印（值序列 API，指定策略）。
     *
     * @param values     值序列
     * @param payload    水印载荷
     * @param strategies 水印类型列表，null 时自动选择（bit-level 优先，容量不足降级 simple）
     * @return 嵌入结果
     */
    public WatermarkResult<List<Object>> embed(List<?> values, String payload,
                                               List<WatermarkType> strategies) {
        if (values == null || values.isEmpty())
            return WatermarkResult.failure("值序列为空");
        if (payload == null || payload.isEmpty())
            return WatermarkResult.failure("水印载荷为空");

        // 包装为单虚拟列的 List<Map<String, Object>> 后复用 Table API
        List<Map<String, Object>> table = new ArrayList<>(values.size());
        for (Object v : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(VALUES_API_VIRTUAL_COLUMN, v);
            table.add(row);
        }

        WatermarkResult<List<Map<String, Object>>> tableResult =
                embed(table, VALUES_API_VIRTUAL_COLUMN_LIST, payload, strategies);

        if (!tableResult.isSuccess()) {
            return WatermarkResult.failure(tableResult.getMessage());
        }

        // 展平回 List<Object>
        List<Object> result = new ArrayList<>(tableResult.getData().size());
        for (Map<String, Object> row : tableResult.getData()) {
            result.add(row.get(VALUES_API_VIRTUAL_COLUMN));
        }

        WatermarkResult<List<Object>> r =
                WatermarkResult.success(result, null, tableResult.getRepetition());
        r.setWatermarkType(tableResult.getWatermarkType());
        return r;
    }

    /**
     * 提取水印（值序列 API）。
     * <p>
     * 自动尝试 bit-level 和 simple 两种模式，返回最可靠结果。
     * </p>
     * <p>
     * <b>顺序无关</b>（ADR-0002 方案 A 起）：bit-level 的位槽由每个值自身的稳定键决定，
     * 不再依赖它在序列中的位置，因此删除值、新增值、乱序都不会导致位槽错位。
     * </p>
     */
    public WatermarkResult<String> extract(List<?> values) {
        return extractValues(values, null);
    }

    /**
     * 提取水印（值序列 API，指定策略白名单）。
     *
     * <p>方法名带 {@code Values} 后缀是为了避开与 Table API
     * {@code extract(List, List)} 的泛型擦除冲突。</p>
     *
     * @param values 值序列
     * @param types  策略白名单，须与嵌入端一致；null 表示不限
     * @return 提取结果
     */
    public WatermarkResult<String> extractValues(List<?> values, List<WatermarkType> types) {
        if (values == null || values.isEmpty())
            return WatermarkResult.failure("值序列为空");

        List<Map<String, Object>> table = new ArrayList<>(values.size());
        for (Object v : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(VALUES_API_VIRTUAL_COLUMN, v);
            table.add(row);
        }

        return extract(table, VALUES_API_VIRTUAL_COLUMN_LIST, types);
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

        // 3. 容量检查：槽总数 = 头部槽(8) + 载荷槽(N)，每个槽由落在其中的单元格投票。
        //    冗余必须按**独立投票者**衡量：哈希分桶是"按内容认领槽位"，
        //    内容相同的单元格（含数值列经 LSB 量化后相撞的情形）会落进同一个槽、
        //    投同一个 bit，只贡献 1 票独立信息，不能按单元格数虚高计算。
        int slotTotal = HEADER_CELLS + N;
        String[] keys = new String[M];
        Set<String> distinctKeys = new HashSet<String>();
        for (int i = 0; i < M; i++) {
            keys[i] = stableKey(cells.get(i).value);
            distinctKeys.add(keys[i]);
        }
        int voters = distinctKeys.size();
        int R = voters / slotTotal;
        if (R < config.getMinRepetition()) {
            int maxEncoded = voters / config.getMinRepetition() - HEADER_CELLS;
            int maxBytes = Math.max(0, maxEncoded / 8 - 5);
            return WatermarkResult.failure(
                "可嵌入单元格不足（共 " + M + " 个，其中内容互不相同的仅 " + voters + " 个），载荷需要 "
                + N + " bits（重复因子仅 " + R + "）。"
                + "建议：增加数据行数、改用内容更多样的列、或缩短载荷（当前最多支持 "
                + maxBytes + " 字节）；数据会被增删的场景也可直接用 simple 模式。");
        }

        // 3b. 分桶：单元格的槽位由自身内容的稳定键决定，与行序无关。
        //     哈希分桶是随机的，可能出现"空槽"（该位一个投票者都没有）；
        //     提取端会用穷举补全处理，但空洞过多就补不过来了，这里先卡上限。
        int[] cellSlots = new int[M];
        int[] hits = new int[slotTotal];
        for (int i = 0; i < M; i++) {
            int slot = slotOf(mixHash(keys[i], 0), slotTotal);
            cellSlots[i] = slot;
            hits[slot]++;
        }
        int emptySlots = 0;
        for (int s = 0; s < slotTotal; s++) {
            if (hits[s] == 0) emptySlots++;
        }
        if (emptySlots > MAX_ERASURES) {
            return WatermarkResult.failure(
                "位槽分布过于稀疏：共 " + slotTotal + " 个槽，其中 " + emptySlots
                + " 个没有任何单元格命中（上限 " + MAX_ERASURES + "）。"
                + "建议：增加数据行数或缩短载荷，或改用 simple 模式。");
        }

        // 4. 深拷贝
        List<Map<String, Object>> result = new ArrayList<>(table.size());
        for (Map<String, Object> row : table) result.add(new LinkedHashMap<>(row));

        // 5. 头部 bits：载荷字节长度 → 8 bit，每个 bit 占 1 个槽（冗余来自哈希落点）
        int payloadByteLen = encoded.length;
        int[] headerBits = new int[HEADER_BITS];
        for (int i = 0; i < HEADER_BITS; i++) {
            headerBits[i] = (payloadByteLen >> (7 - i)) & 1;
        }

        // 6. 按稳定键分桶嵌入：单元格的槽位由它自己的内容决定，与行序无关
        for (int i = 0; i < M; i++) {
            CellRef cell = cells.get(i);
            Object value = result.get(cell.row).get(cell.column);
            BitCarrierStrategy strategy = findBitStrategy(value, types);
            if (strategy == null) continue;

            int slot = cellSlots[i];
            int bit = (slot < HEADER_CELLS)
                    ? headerBits[slot]
                    : payloadBits[slot - HEADER_CELLS];

            long seed = cellSeed(cell.row, cell.colIndex);
            BitEmbedResult er = strategy.embed(value, bit, config.getSecret(), seed);
            if (er.isEmbedded()) {
                result.get(cell.row).put(cell.column, er.getValue());
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
    /**
     * Bit-level 提取（稳定键哈希分桶 + 候选扫描）。
     *
     * @param types 与嵌入端一致的策略白名单；null 表示不限（自动选择路径）
     */
    private WatermarkResult<String> extractBitLevel(List<Map<String, Object>> table, List<String> columns,
                                                    List<WatermarkType> types) {
        List<CellRef> cells = scanCellsForBit(table, columns, types);
        int M = cells.size();
        if (M <= HEADER_CELLS)
            return WatermarkResult.failure("可提取单元格不足，无法还原水印");

        // 稳定键哈希与 bit 值都与载荷长度无关，先各算一次
        long[] hashes = new long[M];
        int[] bits = new int[M];
        for (int i = 0; i < M; i++) {
            CellRef cell = cells.get(i);
            hashes[i] = mixHash(stableKey(cell.value), 0);
            BitCarrierStrategy strategy = findBitStrategy(cell.value, types);
            bits[i] = strategy == null ? -1
                    : strategy.extract(cell.value, config.getSecret(), cellSeed(cell.row, cell.colIndex));
        }

        // 槽位数依赖载荷长度、载荷长度又写在头部里 —— 用候选扫描破环：
        // 只接受"头部自洽（读出的长度 == 假设值）且 CRC32 通过"的那一个长度。
        for (int enc = 5; enc <= MAX_PAYLOAD_BYTES + 5; enc++) {
            WatermarkResult<String> r = tryExtractWithLength(cells, hashes, bits, enc);
            if (r != null) return r;
        }
        return WatermarkResult.failure("CRC32 校验不匹配（可能数据变动过大或密钥不匹配）");
    }

    /**
     * 按指定的编码长度尝试解码；头部不自洽或 CRC 不通过时返回 null。
     */
    private WatermarkResult<String> tryExtractWithLength(
            List<CellRef> cells, long[] hashes, int[] bits, int enc) {
        int M = cells.size();
        int N = enc * 8;
        int slotTotal = HEADER_CELLS + N;

        // 1. 头部：每个 bit 由落在它那个槽里的单元格多数投票（1 槽/bit）
        int[] headerBits = new int[HEADER_BITS];
        for (int b = 0; b < HEADER_BITS; b++) {
            int ones = 0, total = 0;
            for (int i = 0; i < M; i++) {
                if (bits[i] < 0) continue;
                int slot = (int) Math.floorMod(hashes[i], (long) slotTotal);
                if (slot != b) continue;
                total++;
                if (bits[i] == 1) ones++;
            }
            headerBits[b] = (total > 0 && ones > total / 2) ? 1 : 0;
        }
        int payloadByteLen = 0;
        for (int b = 0; b < HEADER_BITS; b++) payloadByteLen = (payloadByteLen << 1) | headerBits[b];

        // 自洽性检查：头部读出的长度必须等于本次假设的长度
        if (payloadByteLen != enc) return null;

        // 2. 载荷：按槽位多数投票
        int[] onesCount = new int[N];
        int[] totalCount = new int[N];
        for (int i = 0; i < M; i++) {
            if (bits[i] < 0) continue;
            int slot = (int) Math.floorMod(hashes[i], (long) slotTotal);
            if (slot < HEADER_CELLS) continue;
            int bitPos = slot - HEADER_CELLS;
            totalCount[bitPos]++;
            if (bits[i] == 1) onesCount[bitPos]++;
        }

        // 3. 多数投票恢复 bits，再用同一密钥流还原（嵌入时做过对称置乱）
        int[] recovered = new int[N];
        int minVotes = Integer.MAX_VALUE;
        for (int b = 0; b < N; b++) {
            recovered[b] = (totalCount[b] > 0 && onesCount[b] > totalCount[b] / 2) ? 1 : 0;
            minVotes = Math.min(minVotes, totalCount[b]);
        }
        int[] keyStream = keyStream(N);
        for (int b = 0; b < N; b++) recovered[b] ^= keyStream[b];

        // 4. 零票槽（数据被删除后可能出现"某位一个投票者都不剩"）：该位取值未知，
        //    穷举补全（2^k 种）后用 CRC32 挑出唯一正确的那一种。
        //    注意：有票的槽一定是对的——投票者携带来的都是真实 bit，空槽只是"弃权"。
        int erasures = 0;
        int[] erasedPos = new int[N];
        for (int b = 0; b < N; b++) {
            if (totalCount[b] == 0) erasedPos[erasures++] = b;
        }
        if (erasures > MAX_ERASURES) return null; // 空洞太多，放弃该候选

        String decoded = null;
        if (erasures == 0) {
            decoded = tryDecodePayload(bitsToBytes(recovered), payloadByteLen);
        } else {
            int[] guess = recovered.clone();
            int combos = 1 << erasures;
            for (int mask = 0; mask < combos && decoded == null; mask++) {
                for (int e = 0; e < erasures; e++) {
                    guess[erasedPos[e]] = (mask >> e) & 1;
                }
                decoded = tryDecodePayload(bitsToBytes(guess), payloadByteLen);
            }
        }
        if (decoded == null) return null;

        Set<String> usedColumns = new LinkedHashSet<>();
        for (CellRef cell : cells) usedColumns.add(cell.column);

        int validTotal = 0, consistentBits = 0;
        for (int b = 0; b < N; b++) {
            if (totalCount[b] > 0) {
                validTotal++;
                int majority = onesCount[b] > totalCount[b] / 2 ? 1 : 0;
                int majorityCount = majority == 1 ? onesCount[b] : (totalCount[b] - onesCount[b]);
                if (majorityCount > totalCount[b] / 2) consistentBits++;
            }
        }
        double conf = validTotal > 0 ? (consistentBits * 100.0 / validTotal) : -1;

        WatermarkResult<String> r = WatermarkResult.extractSuccess(
                decoded, new ArrayList<>(usedColumns), Math.max(0, minVotes));
        r.setWatermarkType(detectBitWatermarkType(cells));
        r.setTotalCells(M);
        r.setValidExtractions(validTotal);
        r.setConfidence(conf);
        return r;
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
            // 冗余按"独立投票者"计（内容互不相同的单元格），与 embedBitLevel 的判定口径一致，
            // 否则会出现"预估容量够 → 实际嵌入失败 → 静默降级 simple"。
            Set<String> distinctKeys = new HashSet<String>();
            for (CellRef c : bitCells) distinctKeys.add(stableKey(c.value));
            int R = distinctKeys.size() / (HEADER_CELLS + N);
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

    // ---------- 稳定键与位槽分配（ADR-0002 方案 A）----------

    /**
     * 计算单元格的稳定键：把值上的水印痕迹剥掉后得到的“原值”。
     * <p>
     * 位槽分配依赖它——只要单元格内容不变，键就不变，
     * **与该单元格在序列中的位置无关**，因此任意位置的增删行都不会影响其他单元格的槽位。
     * </p>
     */
    private String stableKey(Object value) {
        if (value == null) return "";
        // 先剥掉三类水印痕迹，还原出"原始值"
        String original;
        if (value instanceof Number) {
            original = value.toString();
        } else {
            String s = value.toString();
            s = stripZeroWidth(s);
            s = stripSuffixMarker(s);
            original = LatinTextWatermarkStrategy.stripHomoglyphs(s);
        }
        // 数值（含"长成数值的字符串"，例如数据库 CHAR/VARCHAR 列里存的金额）
        // 必须抹掉最低位再量化：数值水印藏在末位（±1 微扰），
        // 不量化的话嵌入前后的键会漂移（"10000.00" → "10000.01"），槽位随之改变。
        String trimmed = original.trim();
        if (looksNumeric(trimmed)) return quantizedNumericKey(trimmed);
        return original;
    }

    /**
     * 判断字符串是否可解析为数值。
     * <p>口径与 {@link io.github.nameof.watermark.core.bit.NumericWatermarkStrategy#canWatermark(Object)}
     * 保持一致，避免"策略认它是数值改了末位、而稳定键却按文本算"的错配。</p>
     */
    private boolean looksNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 数值列的稳定键：去掉最低位后量化。
     * <p>
     * 数值水印藏在末位（±1 微扰），除以 2 取整可抹掉该差异，
     * 使嵌入前后算出的键一致。
     * </p>
     */
    private String quantizedNumericKey(Object value) {
        String text = value.toString().trim();
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(text);
            int scale = Math.max(0, bd.scale());
            return bd.movePointRight(scale).toBigInteger().shiftRight(1).toString();
        } catch (NumberFormatException e) {
            // 极端数值文本（"NaN"/"Infinity" 等能通过 parseDouble 但 BigDecimal 不认）：
            // 退化为原文本，至少保证嵌入端与提取端算出同一个键
            return text;
        }
    }

    /** 剥离零宽字符（U+200B..U+200F）。 */
    private String stripZeroWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '\u200B' || c > '\u200F') sb.append(c);
        }
        return sb.toString();
    }

    /** 剥离后缀标记 {@code [::...::]}（mixed 模式下可能出现在值里）。 */
    private String stripSuffixMarker(String s) {
        int start = s.indexOf("[::");
        while (start >= 0) {
            int end = s.indexOf("::]", start);
            if (end < 0) break;
            s = s.substring(0, start) + s.substring(end + 3);
            start = s.indexOf("[::");
        }
        return s;
    }

    /**
     * 稳定键哈希：把密钥与键混合成稳定的数值。
     * <p>
     * 只依赖 {@code key} 与 {@code secret}，不依赖 JVM、不依赖调用顺序，
     * 保证嵌入端与提取端算出同一槽位。
     * </p>
     */
    private long mixHash(String key, int nonce) {
        long h = 1125899906842597L ^ config.getSecret().hashCode()
                ^ (0x9E3779B97F4A7C15L * (nonce + 1));
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return h ^ (h >>> 33);
    }

    /** 计算单元格的位槽：[0, slotTotal)。 */
    private int slotOf(long hash, int slotTotal) {
        return (int) Math.floorMod(hash, (long) slotTotal);
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
