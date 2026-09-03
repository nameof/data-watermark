package io.github.nameof.watermark.core.simple;

/**
 * 简单水印策略接口。
 * <p>
 * 与 bit-level 策略的核心区别：每个单元格独立承载<b>完整载荷</b>，
 * 而非 1 bit。无需复杂的冗余分配逻辑，适合简单场景下的数据追溯。
 * </p>
 * <p>
 * 简单策略的特点：
 * <ul>
 *   <li>实现简单，易于理解和调试</li>
 *   <li>每个单元格独立携带完整信息，提取时做多数投票</li>
 *   <li>不需要 header/payload 分离、交叉分配等复杂机制</li>
 *   <li>适合内部数据追溯，不要求极高的隐蔽性</li>
 * </ul>
 * </p>
 */
public interface SimpleWatermarkStrategy {

    /**
     * 策略名称。
     *
     * @return 策略名称
     */
    String name();

    /**
     * 判断给定值是否支持嵌入水印。
     *
     * @param value 单元格值
     * @return 是否支持水印嵌入
     */
    boolean canWatermark(Object value);

    /**
     * 在值中嵌入完整的水印载荷。
     *
     * @param value    原始值
     * @param payload  水印载荷字符串
     * @param secret   密钥
     * @param rowIndex 行索引，用于确定水印插入位置
     * @return 嵌入水印后的值
     */
    String embed(Object value, String payload, String secret, int rowIndex);

    /**
     * 从值中提取水印载荷。
     *
     * @param value    可能包含水印的值
     * @param secret   密钥，必须与嵌入时相同
     * @param rowIndex 行索引，必须与嵌入时相同
     * @return 提取的水印载荷，null 表示无水印
     */
    String extract(Object value, String secret, int rowIndex);
}
