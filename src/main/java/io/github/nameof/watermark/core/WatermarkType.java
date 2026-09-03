package io.github.nameof.watermark.core;

/**
 * 水印类型枚举，包含所有内置的水印策略。
 * <p>
 * 每种类型关联一个 {@link Mode}（bit-level 或 simple），
 * 用于区分不同的编码机制。
 * </p>
 */
public enum WatermarkType {

    // --- bit-level 策略（每单元格承载 1 bit）---
    /** 中文零宽字符 */
    BIT_CHINESE_ZERO_WIDTH(Mode.BIT_LEVEL),
    /** 拉丁同形字替换 */
    BIT_LATIN_HOMOGLYPH(Mode.BIT_LEVEL),
    /** 数值末位微扰 */
    BIT_NUMERIC_LSB(Mode.BIT_LEVEL),

    // --- simple 策略（每单元格承载完整载荷）---
    /** 文本后缀标记 */
    SIMPLE_SUFFIX_MARKER(Mode.SIMPLE),
    /** 文本零宽填充 */
    SIMPLE_INVISIBLE_PADDING(Mode.SIMPLE);

    /**
     * 水印模式枚举。
     */
    public enum Mode {
        /** bit-level：每单元格承载 1 bit，两段式头部 + 载荷 + CRC32，交叉分配，多数投票 */
        BIT_LEVEL,
        /** simple：每单元格承载完整载荷，独立编码，多数投票 */
        SIMPLE
    }

    private final Mode mode;

    WatermarkType(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isBitLevel() {
        return mode == Mode.BIT_LEVEL;
    }
}
