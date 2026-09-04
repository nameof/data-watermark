package io.github.nameof.watermark.core.bit;

import io.github.nameof.watermark.core.WatermarkType;

/**
 * bit 载体策略接口 —— 在<b>单个值</b>中隐蔽地嵌入/提取一个 bit。
 * <p>
 * 本接口处于架构中的<b>载体层</b>（carrier layer）：只回答"如何把 1 bit
 * 藏进一个值里且保持视觉不可区分"，完全不感知数据集的形态
 * （表格、文档、JSON 等均可复用同一策略）。
 * </p>
 * <p>
 * 与之相对，{@link io.github.nameof.watermark.core.Watermarker} 处于<b>编码/扩频层</b>：
 * 负责把载荷编码为 bit 流，并通过冗余分配机制将多个 bit 分散到多个值中
 * （两段式头部、交叉分配、多数投票）。一次完整水印的嵌入天然需要多个值，
 * 这层职责不在策略接口内。
 * </p>
 * <p>
 * {@code seed} 是由上层引擎从值的稳定标识计算出的确定性种子，
 * 策略用它选择嵌入在值内部的哪个位置（使不同值的水印位置各不相同，
 * 防止攻击者通过统一截断批量去除水印）。策略不假设 seed 的来源是
 * 行号、列号还是其他坐标 —— 这正是本层与数据集形态解耦的关键。
 * </p>
 */
public interface BitCarrierStrategy {

    /**
     * 本策略对应的水印类型。
     *
     * @return 水印类型枚举值
     */
    WatermarkType type();

    /**
     * 判断给定值是否支持嵌入水印。
     * 例如：文本长度是否足够、数值是否有足够精度等。
     *
     * @param value 单元格值
     * @return 是否支持水印嵌入
     */
    boolean canWatermark(Object value);

    /**
     * 在值中嵌入一个 bit 的水印。
     *
     * @param value  原始值
     * @param bit    要嵌入的 bit（0 或 1）
     * @param secret 密钥，用于确定修改位置
     * @param seed   确定性位置种子，由上层引擎从值的稳定标识计算
     * @return 嵌入结果，包含修改后的值和是否成功嵌入的标记
     */
    BitEmbedResult embed(Object value, int bit, String secret, long seed);

    /**
     * 从值中提取一个 bit 的水印。
     *
     * @param value  可能包含水印的值
     * @param secret 密钥，必须与嵌入时相同
     * @param seed   确定性位置种子，必须与嵌入时相同
     * @return 提取出的 bit（0 或 1），如果无法提取则返回 -1
     */
    int extract(Object value, String secret, long seed);
}
