package io.github.nameof.watermark.bit;

/**
 * 中文文本水印策略 —— 零宽字符嵌入。
 * <p>
 * 原理：在文本的特定位置插入零宽字符（肉眼不可见），用不同零宽字符编码 bit 0/1。
 * <ul>
 *   <li>U+200B (Zero Width Space) → bit 0</li>
 *   <li>U+200C (Zero Width Non-Joiner) → bit 1</li>
 * </ul>
 * 提取时扫描文本中的零宽字符即可还原 bit。
 * </p>
 * <p>
 * 健壮性：零宽字符不影响文本显示和大多数文本处理操作，
 * 即使文本被复制粘贴、数据库导出导入，零宽字符通常也能保留。
 * 需要 MySQL 表/列使用 utf8mb4 编码。
 * </p>
 * <p>
 * 适用场景：中文文本列（姓名、地址等），要求肉眼不可见。
 * </p>
 */
public class ChineseTextWatermarkStrategy implements ColumnWatermarkStrategy {

    /** 零宽空格 - 编码 bit 0 */
    private static final char ZWSP = '\u200B';

    /** 零宽非连接符 - 编码 bit 1 */
    private static final char ZWNJ = '\u200C';

    /** 文本最小长度要求（至少需要几个字符才能嵌入水印） */
    private static final int MIN_LENGTH = 2;

    @Override
    public boolean canWatermark(Object value) {
        if (value == null) {
            return false;
        }
        String str = value.toString();
        // 去除已有的零宽字符后检查有效长度
        String cleaned = removeZeroWidthChars(str);
        return cleaned.length() >= MIN_LENGTH;
    }

    @Override
    public ColumnEmbedResult embed(Object value, int bit, String secret, int row, int col) {
        String str = value.toString();
        // 先清除已有零宽字符，避免多次嵌入叠加
        String cleaned = removeZeroWidthChars(str);

        if (cleaned.length() < MIN_LENGTH) {
            return new ColumnEmbedResult(value, false);
        }

        // 用哈希确定插入位置，使不同单元格在不同位置嵌入（增加攻击者定位难度）
        int pos = Math.abs(hashPosition(secret, row, col)) % cleaned.length();

        // 根据 bit 值选择零宽字符
        char zwChar = (bit == 1) ? ZWNJ : ZWSP;

        // 在 pos 位置后插入零宽字符
        StringBuilder sb = new StringBuilder();
        sb.append(cleaned, 0, pos + 1);
        sb.append(zwChar);
        if (pos + 1 < cleaned.length()) {
            sb.append(cleaned.substring(pos + 1));
        }

        return new ColumnEmbedResult(sb.toString(), true);
    }

    @Override
    public int extract(Object value, String secret, int row, int col) {
        if (value == null) {
            return -1;
        }
        String str = value.toString();

        // 查找文本中的零宽字符，返回对应的 bit 值
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == ZWSP) {
                return 0;
            }
            if (c == ZWNJ) {
                return 1;
            }
        }

        return -1; // 未检测到水印字符
    }

    /**
     * 移除字符串中的零宽字符（U+200B ~ U+200F 范围）。
     * 用于在嵌入前清除旧水印，确保每次嵌入都是干净的。
     */
    private String removeZeroWidthChars(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c < '\u200B' || c > '\u200F') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 基于密钥和位置信息计算哈希位置。
     * 使用混合函数确保不同 (secret, row, col) 组合产生不同的插入位置。
     */
    private int hashPosition(String secret, int row, int col) {
        long hash = secret.hashCode();
        hash = hash * 31 + row;
        hash = hash * 31 + col;
        hash = (hash ^ (hash >>> 16)) * 0x45D9F3B;
        hash = (hash ^ (hash >>> 16));
        return (int) hash;
    }
}
