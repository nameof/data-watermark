package io.github.nameof.watermark.core.bit;

/**
 * bit 载体策略的单值嵌入结果。
 * <p>
 * 封装嵌入水印后的值和是否成功嵌入的标记。
 * 当 embedded=false 时，value 为原始值（未修改）。
 * </p>
 */
public class BitEmbedResult {

    /** 嵌入水印后的值（如果嵌入失败则为原始值） */
    private final Object value;

    /** 是否成功嵌入水印 */
    private final boolean embedded;

    public BitEmbedResult(Object value, boolean embedded) {
        this.value = value;
        this.embedded = embedded;
    }

    public Object getValue() {
        return value;
    }

    public boolean isEmbedded() {
        return embedded;
    }
}
