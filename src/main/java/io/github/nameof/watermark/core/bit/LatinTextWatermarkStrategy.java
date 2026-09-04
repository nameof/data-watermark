package io.github.nameof.watermark.core.bit;

import io.github.nameof.watermark.core.WatermarkType;

/**
 * 拉丁文本水印策略 —— Homoglyph（同形字）替换。
 * <p>
 * 原理：将文本中的某个 ASCII 字符替换为视觉上几乎相同的 Unicode 字符，
 * 肉眼无法区分，但编码不同，可用于承载水印信息。
 * </p>
 * <p>
 * 替换映射表（部分示例）：
 * <ul>
 *   <li>a ↔ а (U+0430, 西里尔小写 а)</li>
 *   <li>e ↔ е (U+0435)</li>
 *   <li>o ↔ о (U+043E)</li>
 *   <li>p ↔ р (U+0440)</li>
 *   <li>c ↔ с (U+0441)</li>
 *   <li>x ↔ х (U+0445)</li>
 *   <li>A ↔ А (U+0410)</li>
 *   <li>B ↔ В (U+0412)</li>
 *   <li>C ↔ С (U+0421)</li>
 *   <li>E ↔ Е (U+0415)</li>
 *   <li>O ↔ О (U+041E)</li>
 *   <li>P ↔ Р (U+0420)</li>
 * </ul>
 * 嵌入规则（与提取语义严格对应）：
 * <ul>
 *   <li>bit 1 = 将密钥+种子选定的字符替换为同形字</li>
 *   <li>bit 0 = 保证值中不存在同形字（已有同形字还原为 ASCII）</li>
 * </ul>
 * 提取时检测是否存在同形字：有 → 1；无（且值可嵌入）→ 0；不可嵌入 → -1。
 * </p>
 */
public class LatinTextWatermarkStrategy implements BitCarrierStrategy {

    /** 最小文本长度要求 */
    private static final int MIN_LENGTH = 3;

    /**
     * ASCII → 西里尔同形字映射（小写）
     */
    private static final char[][] HOMOGlyph_MAP = {
            {'a', '\u0430'}, // а
            {'e', '\u0435'}, // е
            {'o', '\u043E'}, // о
            {'p', '\u0440'}, // р
            {'c', '\u0441'}, // с
            {'x', '\u0445'}, // х
            {'A', '\u0410'}, // А
            {'B', '\u0412'}, // В
            {'C', '\u0421'}, // С
            {'E', '\u0415'}, // Е
            {'O', '\u041E'}, // О
            {'P', '\u0420'}, // Р
    };

    @Override
    public WatermarkType type() {
        return WatermarkType.BIT_LATIN_HOMOGLYPH;
    }

    @Override
    public boolean canWatermark(Object value) {
        if (value == null) {
            return false;
        }
        String str = value.toString();
        if (str.length() < MIN_LENGTH) {
            return false;
        }
        // 至少包含一个可替换的字符
        for (int i = 0; i < str.length(); i++) {
            if (findHomoglyph(str.charAt(i)) != 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BitEmbedResult embed(Object value, int bit, String secret, long seed) {
        String str = value.toString();

        // 收集所有可替换字符的位置
        int[] replaceablePositions = new int[str.length()];
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (findHomoglyph(str.charAt(i)) != 0) {
                replaceablePositions[count++] = i;
            }
        }

        if (count == 0) {
            return new BitEmbedResult(value, false);
        }

        // bit 0：保证值中不存在同形字（还原已有同形字），"无同形字"即代表 bit 0
        if (bit == 0) {
            return new BitEmbedResult(normalizeHomoglyphs(str), true);
        }

        // bit 1：用密钥 + 种子选择要替换的位置
        int hashPos = Math.abs(hashPosition(secret, seed));
        int selectedIdx = replaceablePositions[hashPos % count];
        char replacement = findHomoglyph(str.charAt(selectedIdx));

        StringBuilder sb = new StringBuilder(str);
        sb.setCharAt(selectedIdx, replacement);

        return new BitEmbedResult(sb.toString(), true);
    }

    @Override
    public int extract(Object value, String secret, long seed) {
        if (value == null) {
            return -1;
        }
        String str = value.toString();

        // 检查是否存在西里尔同形字
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // 检查是否是映射表中的西里尔字符
            for (char[] pair : HOMOGlyph_MAP) {
                if (c == pair[1]) {
                    return 1; // 检测到同形字 → bit 1
                }
            }
        }

        // 未检测到同形字 → bit 0（原始值未被修改）
        // 但需要确认该值确实是可以嵌入水印的（否则 -1 表示无水印）
        if (canWatermark(value)) {
            return 0;
        }

        return -1;
    }

    /**
     * 查找字符的同形字替换。
     *
     * @return 同形字字符，如果不存在则返回 0
     */
    private char findHomoglyph(char c) {
        for (char[] pair : HOMOGlyph_MAP) {
            if (pair[0] == c) {
                return pair[1];
            }
        }
        return 0;
    }

    /**
     * 将文本中已有的西里尔同形字还原为对应的 ASCII 字符。
     * 用于嵌入 bit 0（"无同形字"即代表 bit 0）以及覆盖旧水印。
     */
    private String normalizeHomoglyphs(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            char ascii = findAscii(c);
            sb.append(ascii != 0 ? ascii : c);
        }
        return sb.toString();
    }

    /**
     * 查找西里尔同形字对应的原始 ASCII 字符。
     *
     * @return ASCII 字符，如果不是映射表中的同形字则返回 0
     */
    private char findAscii(char homoglyph) {
        for (char[] pair : HOMOGlyph_MAP) {
            if (pair[1] == homoglyph) {
                return pair[0];
            }
        }
        return 0;
    }

    /**
     * 基于密钥和位置种子计算哈希位置。
     */
    private int hashPosition(String secret, long seed) {
        long hash = secret.hashCode();
        hash = hash * 31 + seed;
        hash = (hash ^ (hash >>> 16)) * 0x45D9F3B;
        hash = (hash ^ (hash >>> 16));
        return (int) hash;
    }
}
