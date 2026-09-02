package io.github.nameof.watermark.bit;

/**
 * 列级 bit-level 水印策略接口。
 * <p>
 * 每种数据类型（中文文本、拉丁文本、数值）有各自的嵌入/提取策略。
 * 策略负责在单个单元格值中嵌入或提取一个 bit，同时保持值的视觉不可区分性。
 * </p>
 * <p>
 * 核心设计：每个策略只处理单个单元格中的一个 bit。
 * 上层引擎（DataWatermarker）负责将载荷编码为 bit 序列，
 * 并通过冗余分配机制将多个 bit 分散到多个单元格中。
 * </p>
 */
public interface ColumnWatermarkStrategy {

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
     * @param row    行索引（用于确定修改位置）
     * @param col    列索引（用于确定修改位置）
     * @return 嵌入结果，包含修改后的值和是否成功嵌入的标记
     */
    ColumnEmbedResult embed(Object value, int bit, String secret, int row, int col);

    /**
     * 从值中提取一个 bit 的水印。
     *
     * @param value  可能包含水印的值
     * @param secret 密钥，必须与嵌入时相同
     * @param row    行索引，必须与嵌入时相同
     * @param col    列索引，必须与嵌入时相同
     * @return 提取出的 bit（0 或 1），如果无法提取则返回 -1
     */
    int extract(Object value, String secret, int row, int col);
}
