package io.github.nameof.watermark;

import io.github.nameof.watermark.bit.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 数据暗水印核心类 —— 在 ETL 脱敏过程中嵌入/提取不可见的追溯水印。
 *
 * <h2>设计原理</h2>
 * <ol>
 *   <li><b>多策略嵌入</b>：根据列的数据类型自动选择嵌入策略
 *       （中文→零宽字符、拉丁文→同形字替换、数值→末位微扰）</li>
 *   <li><b>两段式编码</b>：前 HEADER_BITS×3 = 24 个单元格编码 8-bit 长度头部，
 *       剩余单元格编码载荷+CRC32。提取时先读头部确定精确长度，无需猜测。</li>
 *   <li><b>交叉分配</b>：每个 bit 的 R 个副本均匀分布在整个数据范围内
 *       （cell j → bit j%N），删除任意连续区域只影响每个 bit 的少量副本，
 *       通过多数投票恢复，容忍高达 (R-1)/R 比例的数据丢失。</li>
 *   <li><b>CRC32 校验</b>：载荷附带 CRC32 校验，提取时验证完整性。</li>
 * </ol>
 *
 * <h2>容量需求</h2>
 * <p>
 * 总单元格 M = 头部(24) + 载荷区(M-24)。
 * 载荷 bit 数 N = (1 + payloadLen + 4) × 8，重复因子 R = (M-24) / N。
 * 需要 R ≥ config.minRepetition（默认 5）才能可靠提取。
 * </p>
 */
public class DataWatermarker {

    /** 头部 bit 数：8 bit 可表示 0-255 字节载荷长度 */
    private static final int HEADER_BITS = 8;
    /** 头部重复因子：每个头部 bit 由 3 个单元格投票 */
    private static final int HEADER_REP = 3;
    /** 头部占用单元格数 */
    private static final int HEADER_CELLS = HEADER_BITS * HEADER_REP; // 24

    private final WatermarkConfig config;
    private final List<ColumnWatermarkStrategy> strategies;

    public DataWatermarker(WatermarkConfig config) {
        this.config = config;
        this.strategies = new ArrayList<>();
        strategies.add(new ChineseTextWatermarkStrategy());
        strategies.add(new LatinTextWatermarkStrategy());
        strategies.add(new NumericWatermarkStrategy());
    }

    public DataWatermarker(WatermarkConfig config, List<ColumnWatermarkStrategy> strategies) {
        this.config = config;
        this.strategies = new ArrayList<>(strategies);
    }

    // ==================== 公开 API ====================

    public boolean supportsWatermark(List<Map<String, Object>> table, List<String> columns) {
        if (table == null || table.isEmpty() || columns == null || columns.isEmpty()) return false;
        for (String col : columns)
            for (Map<String, Object> row : table)
                if (row.get(col) != null && findStrategy(row.get(col)) != null) return true;
        return false;
    }

    public Map<String, ColumnWatermarkStrategy> detectWatermarkColumns(
            List<Map<String, Object>> table, List<String> columns) {
        Map<String, ColumnWatermarkStrategy> result = new LinkedHashMap<>();
        if (table == null || table.isEmpty() || columns == null) return result;
        for (String col : columns)
            for (Map<String, Object> row : table) {
                Object v = row.get(col);
                if (v != null) {
                    ColumnWatermarkStrategy s = findStrategy(v);
                    if (s != null) { result.put(col, s); break; }
                }
            }
        return result;
    }

    /**
     * 嵌入暗水印。
     * <p>
     * 编码格式：[length:8bits × 3 rep][payload+CRC32: Nbits × R rep]
     * </p>
     */
    public WatermarkResult<List<Map<String, Object>>> embed(
            List<Map<String, Object>> table, List<String> columns, String payload) {

        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");
        if (payload == null || payload.isEmpty())
            return WatermarkResult.failure("水印载荷为空");

        // 1. 扫描可嵌入单元格
        List<CellRef> cells = scanCells(table, columns);
        int M = cells.size();
        if (M == 0) return WatermarkResult.failure("没有找到可嵌入水印的单元格");
        if (M <= HEADER_CELLS)
            return WatermarkResult.failure("可嵌入单元格不足（" + M + "个），至少需要 " + (HEADER_CELLS + 1) + " 个");

        // 2. 编码载荷
        byte[] encoded = encodePayload(payload);
        int[] payloadBits = bytesToBits(encoded);
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
        int payloadByteLen = encoded.length; // = payload.length() + 5
        int[] headerBits = new int[HEADER_BITS];
        for (int i = 0; i < HEADER_BITS; i++) {
            headerBits[i] = (payloadByteLen >> (7 - i)) & 1;
        }
        for (int i = 0; i < HEADER_CELLS; i++) {
            int bitPos = i / HEADER_REP;
            CellRef cell = cells.get(i);
            Object value = result.get(cell.row).get(cell.column);
            ColumnWatermarkStrategy strategy = findStrategy(value);
            if (strategy != null) {
                int sr = scramble(cell.row, config.getSecret());
                int sc = scramble(cell.colIndex, config.getSecret());
                ColumnEmbedResult er = strategy.embed(value, headerBits[bitPos], config.getSecret(), sr, sc);
                if (er.isEmbedded()) {
                    result.get(cell.row).put(cell.column, er.getValue());
                }
            }
        }

        // 6. 嵌入载荷：交叉分配，每组包含全部 N 个 bit
        //    cell j → bit (j % N)，确保每个 bit 的副本均匀分布在整个数据范围
        for (int i = 0; i < payloadCells; i++) {
            int bitPos = i % N;
            CellRef cell = cells.get(HEADER_CELLS + i);
            Object value = result.get(cell.row).get(cell.column);
            int sr = scramble(cell.row, config.getSecret());
            int sc = scramble(cell.colIndex, config.getSecret());
            ColumnWatermarkStrategy strategy = findStrategy(value);
            if (strategy != null) {
                ColumnEmbedResult er = strategy.embed(value, payloadBits[bitPos], config.getSecret(), sr, sc);
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

        return WatermarkResult.embedSuccess(result, new ArrayList<>(usedColumns), R);
    }

    /**
     * 提取暗水印。
     * <p>
     * 无需猜测载荷长度：先从头部 24 个单元格读取精确的载荷字节数，
     * 再从剩余单元格按正确的重复因子提取载荷 bits。
     * </p>
     */
    public WatermarkResult<String> extract(List<Map<String, Object>> table, List<String> columns) {
        if (table == null || table.isEmpty())
            return WatermarkResult.failure("表数据为空");

        List<CellRef> cells = scanCells(table, columns);
        int M = cells.size();
        if (M == 0) return WatermarkResult.failure("没有找到可提取水印的单元格");
        if (M <= HEADER_CELLS)
            return WatermarkResult.failure("可提取单元格不足，无法还原水印");

        // 1. 提取头部：前 24 个单元格 → 8 bits（每 3 个一组多数投票）
        int payloadByteLen = 0;
        for (int b = 0; b < HEADER_BITS; b++) {
            int ones = 0, total = 0;
            for (int j = b * HEADER_REP; j < (b + 1) * HEADER_REP && j < M; j++) {
                CellRef cell = cells.get(j);
                ColumnWatermarkStrategy strategy = findStrategy(cell.value);
                if (strategy != null) {
                    int sr = scramble(cell.row, config.getSecret());
                    int sc = scramble(cell.colIndex, config.getSecret());
                    int bit = strategy.extract(cell.value, config.getSecret(), sr, sc);
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
            return WatermarkResult.failure("无法还原有效的水印载荷，头部长度异常: " + payloadByteLen
                    + "。可能数据被大量删除或修改，或使用了错误的密钥。");
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
            ColumnWatermarkStrategy strategy = findStrategy(cell.value);
            if (strategy != null) {
                int sr = scramble(cell.row, config.getSecret());
                int sc = scramble(cell.colIndex, config.getSecret());
                int bit = strategy.extract(cell.value, config.getSecret(), sr, sc);
                if (bit >= 0) {
                    totalCount[bitPos]++;
                    if (bit == 1) onesCount[bitPos]++;
                }
            }
        }

        // 4. 多数投票恢复 bits
        int[] recovered = new int[N];
        for (int b = 0; b < N; b++) {
            recovered[b] = (totalCount[b] > 0 && onesCount[b] > totalCount[b] / 2) ? 1 : 0;
        }

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

            WatermarkResult<String> r = WatermarkResult.extractSuccess(decoded, new ArrayList<>(usedColumns), R);
            r.setWatermarkMode("bit-level");
            r.setTotalCells(M);
            r.setValidExtractions(validTotal);
            r.setConfidence(conf);
            return r;
        }

        return WatermarkResult.failure("无法还原有效的水印载荷，CRC32 校验不匹配。"
                + "可能数据被大量删除或修改，或使用了错误的密钥。");
    }

    // ==================== 内部方法 ====================

    /**
     * 整数置乱函数（用于隐藏嵌入位置）。
     * <p>
     * 将行号/列号与密钥混合，产生伪随机位置。
     * 嵌入和提取使用相同的置乱函数，确保双方定位到同一位置。
     * </p>
     *
     * @param input  原始位置值（行号或列索引）
     * @param secret 密钥
     * @return 置乱后的位置值
     */
    private int scramble(int input, String secret) {
        long h = input * 31L + secret.hashCode();
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = h ^ (h >>> 16);
        return (int) Math.floorMod(h, Integer.MAX_VALUE);
    }

    /**
     * 扫描表中所有可嵌入水印的单元格。
     * <p>
     * 按行优先顺序遍历，对每个单元格调用 findStrategy 判断是否可嵌入。
     * 返回的 CellRef 列表顺序决定了 bit 分配的位置。
     * </p>
     *
     * @param table   表数据
     * @param columns 待扫描的列名列表
     * @return 可嵌入单元格的引用列表
     */
    private List<CellRef> scanCells(List<Map<String, Object>> table, List<String> columns) {
        List<CellRef> result = new ArrayList<>();
        for (int r = 0; r < table.size(); r++) {
            Map<String, Object> row = table.get(r);
            for (int c = 0; c < columns.size(); c++) {
                Object value = row.get(columns.get(c));
                if (value != null && findStrategy(value) != null)
                    result.add(new CellRef(r, c, columns.get(c), value));
            }
        }
        return result;
    }

    /**
     * 根据单元格值的数据类型，自动选择匹配的嵌入策略。
     * 策略按注册顺序尝试，返回第一个支持该值的策略。
     *
     * @param value 单元格值
     * @return 匹配的策略，无匹配返回 null
     */
    private ColumnWatermarkStrategy findStrategy(Object value) {
        for (ColumnWatermarkStrategy s : strategies)
            if (s.canWatermark(value)) return s;
        return null;
    }

    // ---------- 载荷编码/解码 ----------

    /**
     * 将载荷字符串编码为字节数组。
     * <p>
     * 编码格式：[length:1byte][payload:N bytes][crc32:4bytes]
     * length 字段记录载荷的实际字节长度，用于提取时验证。
     * </p>
     *
     * @param payload 水印载荷字符串
     * @return 编码后的字节数组
     * @throws IllegalArgumentException 载荷过长（超过 251 字节）
     */
    private byte[] encodePayload(String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int len = data.length;
        if (len > 251) throw new IllegalArgumentException("载荷过长: " + len + " 字节，最大 251");
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
     * <p>
     * 验证 length 字段一致性和 CRC32 校验和。
     * 任一验证失败则返回 null。
     * </p>
     *
     * @param bytes       包含编码数据的字节数组
     * @param expectedLen 期望的总长度（= payloadLen + 5）
     * @return 解码后的载荷字符串，校验失败返回 null
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
     * 使用多项式 0xEDB88320（反转表示），初始值 0xFFFFFFFF，最终取反。
     *
     * @param data   数据数组
     * @param offset 起始偏移
     * @param length 计算长度
     * @return 32-bit CRC 校验值
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
