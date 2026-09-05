package io.github.nameof.watermark.core.simple;

import io.github.nameof.watermark.core.WatermarkType;

import java.nio.charset.StandardCharsets;

/**
 * 隐形填充策略 —— 在文本末尾追加零宽字符编码的完整载荷。
 * <p>
 * 原理：将载荷字符串的每个字节用零宽字符编码后追加到文本末尾。
 * 肉眼不可见，比 bit-level 简单但健壮性较低。
 * </p>
 * <p>
 * 编码方式：
 * <ul>
 *   <li>每个字节拆为 8 个 bit</li>
 *   <li>bit 0 → U+200B (Zero Width Space)</li>
 *   <li>bit 1 → U+200C (Zero Width Non-Joiner)</li>
 *   <li>末尾追加 U+200D (Zero Width Joiner) 作为终止符</li>
 * </ul>
 * </p>
 * <p>
 * 特点：
 * <ul>
 *   <li>肉眼不可见，隐蔽性较好</li>
 *   <li>实现比 bit-level 简单得多</li>
 *   <li>每个单元格独立携带完整载荷，提取时做多数投票</li>
 *   <li>需要 MySQL 表/列使用 utf8mb4 编码</li>
 * </ul>
 * </p>
 */
public class InvisiblePaddingStrategy implements SimpleWatermarkStrategy {

    /** 零宽空格 - 编码 bit 0 */
    private static final char ZWSP = '\u200B';

    /** 零宽非连接符 - 编码 bit 1 */
    private static final char ZWNJ = '\u200C';

    /** 零宽连接符 - 终止符 */
    private static final char ZWJ = '\u200D';

    @Override
    public WatermarkType type() {
        return WatermarkType.SIMPLE_INVISIBLE_PADDING;
    }

    @Override
    public boolean canWatermark(Object value) {
        // 仅支持字符串类型的文本值：数值等对象若被 toString 后追加零宽字符，
        // 会破坏原始数据类型（数字不再是数字），因此必须在类型层拦截。
        if (!(value instanceof String)) {
            return false;
        }
        String str = (String) value;
        // 去除已有零宽字符后检查是否有实际内容
        String cleaned = removeZeroWidthChars(str);
        return !cleaned.trim().isEmpty();
    }

    @Override
    public String embed(Object value, String payload, String secret, long seed) {
        // 前置校验：避免调用方绕过 canWatermark 直接嵌入导致数据被破坏
        if (!canWatermark(value)) {
            throw new IllegalArgumentException("值不支持零宽填充水印，仅支持非空文本: " + type());
        }
        String str = (String) value;
        // 先清除已有零宽字符，避免多次嵌入叠加
        str = removeZeroWidthChars(str);

        // 将载荷编码为零宽字符序列
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(str);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                int bit = (b >> i) & 1;
                sb.append(bit == 0 ? ZWSP : ZWNJ);
            }
        }
        // 追加终止符
        sb.append(ZWJ);

        return sb.toString();
    }

    @Override
    public String extract(Object value, String secret, long seed) {
        if (value == null) {
            return null;
        }
        String str = value.toString();

        // 从零宽字符序列中提取载荷
        int startIdx = findFirstZeroWidthChar(str);
        if (startIdx < 0) {
            return null;
        }

        // 收集零宽字符序列
        StringBuilder bits = new StringBuilder();
        for (int i = startIdx; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == ZWSP) {
                bits.append('0');
            } else if (c == ZWNJ) {
                bits.append('1');
            } else if (c == ZWJ) {
                // 终止符，停止收集
                break;
            } else {
                // 非零宽字符，序列结束
                break;
            }
        }

        // 解码 bit 序列为字节
        String bitStr = bits.toString();
        if (bitStr.length() % 8 != 0 || bitStr.isEmpty()) {
            return null;
        }

        byte[] bytes = new byte[bitStr.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | (bitStr.charAt(i * 8 + j) - '0');
            }
            bytes[i] = (byte) b;
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 移除字符串中的零宽字符（U+200B ~ U+200F 范围）。
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
     * 查找字符串中第一个零宽字符的位置。
     */
    private int findFirstZeroWidthChar(String str) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= '\u200B' && c <= '\u200F') {
                return i;
            }
        }
        return -1;
    }
}
