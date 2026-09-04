package io.github.nameof.watermark.core.bit;

import io.github.nameof.watermark.core.WatermarkType;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 数值水印策略 —— 末位微扰。
 * <p>
 * 原理：对数值的最低有效位进行 ±1 的微调来编码 bit 信息。
 * 修改后的值与原始值在视觉上几乎无差别（差异不超过 1 个最小精度单位）。
 * </p>
 * <p>
 * 编码规则：
 * <ul>
 *   <li>bit 0 → 最低有效位为偶数</li>
 *   <li>bit 1 → 最低有效位为奇数</li>
 * </ul>
 * 例如：123.45 (精度 2 位小数)
 * <ul>
 *   <li>嵌入 bit 0 → 123.44（末位 4，偶数）或 123.46（末位 6，偶数）</li>
 *   <li>嵌入 bit 1 → 123.45（末位 5，奇数）保持不变</li>
 * </ul>
 * </p>
 * <p>
 * 注意：此策略假设数据在 ETL 过程中保持精度不变。
 * 如果数值被四舍五入到更低的精度，水印可能会丢失。
 * </p>
 */
public class NumericWatermarkStrategy implements BitCarrierStrategy {

    @Override
    public WatermarkType type() {
        return WatermarkType.BIT_NUMERIC_LSB;
    }

    @Override
    public boolean canWatermark(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number) {
            return true;
        }
        try {
            Double.parseDouble(value.toString());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public BitEmbedResult embed(Object value, int bit, String secret, long seed) {
        double num = toDouble(value);
        int precision = getPrecision(value);

        // 将数值放大到整数域操作
        double factor = Math.pow(10, precision);
        long scaled = Math.round(num * factor);

        // 检查最低位奇偶性
        long absScaled = Math.abs(scaled);
        int currentBit = (int) (absScaled % 2);

        if (currentBit != bit) {
            // 需要调整：±1 使最低位匹配
            scaled += (bit == 1) ? 1 : -1;
            // 避免从 0 变成负数等边界情况
            if (scaled < 0 && num >= 0) {
                scaled += 2;
            }
        }

        // 还原为原始精度
        double result = scaled / factor;

        // 保持原始类型
        Object resultValue = preserveType(value, result, precision);

        return new BitEmbedResult(resultValue, true);
    }

    @Override
    public int extract(Object value, String secret, long seed) {
        if (value == null) {
            return -1;
        }

        double num = toDouble(value);
        int precision = getPrecision(value);

        double factor = Math.pow(10, precision);
        long scaled = Math.round(num * factor);

        return (int) (Math.abs(scaled) % 2);
    }

    /**
     * 将值转换为 double。
     */
    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    /**
     * 获取数值的小数精度位数。
     */
    private int getPrecision(Object value) {
        String str = value.toString();
        int dotIndex = str.indexOf('.');
        if (dotIndex < 0) {
            return 0; // 整数
        }
        // 保留原始精度（不去零），因为数据库可能保留固定精度
        String decimalPart = str.substring(dotIndex + 1);
        return decimalPart.length();
    }

    /**
     * 尽量保持原始数据类型。
     */
    private Object preserveType(Object original, double result, int precision) {
        if (original instanceof Integer && precision == 0) {
            return (int) Math.round(result);
        }
        if (original instanceof Long && precision == 0) {
            return Math.round(result);
        }
        if (original instanceof Float) {
            return (float) result;
        }
        if (original instanceof Short && precision == 0) {
            return (short) Math.round(result);
        }
        if (original instanceof Byte && precision == 0) {
            return (byte) Math.round(result);
        }
        // 对于 String 类型的数值，保持为 String
        if (original instanceof String) {
            if (precision == 0) {
                return String.valueOf((long) result);
            }
            return String.format(Locale.ROOT, "%." + precision + "f", result);
        }
        // 对于 BigDecimal，保持类型以避免精度丢失
        if (original instanceof BigDecimal) {
            return BigDecimal.valueOf(result).setScale(precision, java.math.RoundingMode.HALF_UP);
        }
        return result;
    }
}
