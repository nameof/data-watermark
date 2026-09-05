package io.github.nameof.watermark.core.simple;

import io.github.nameof.watermark.core.WatermarkType;

/**
 * 后缀标记策略 —— 在文本的<b>随机位置</b>插入可见标记。
 * <p>
 * 嵌入格式示例（payload = "operator:zhangsan"）：
 * <ul>
 *   <li>第 0 行：{@code "[::operator:zhangsan::]张三"}（插入在头部）</li>
 *   <li>第 1 行：{@code "张[::operator:zhangsan::]三"}（插入在中间）</li>
 *   <li>第 2 行：{@code "张三[::operator:zhangsan::]"}（插入在尾部）</li>
 * </ul>
 * 每个值的插入位置由密钥 + 位置种子确定性决定，不同值位置不同。
 * </p>
 * <p>
 * 提取方式：在字符串中搜索 {@code [::...::]} 标记，不依赖固定位置。
 * </p>
 * <p>
 * 特点：
 * <ul>
 *   <li>水印位置随机分布，攻击者无法通过统一截断来批量去除</li>
 *   <li>提取时不依赖 seed，通过模式匹配定位标记</li>
 *   <li>嵌入时依赖 seed 确定位置，保证同一值可重复嵌入</li>
 *   <li>适合内部数据追溯、开发调试</li>
 * </ul>
 * </p>
 */
public class SuffixMarkerStrategy implements SimpleWatermarkStrategy {

    /** 标记前缀 */
    private static final String MARKER_PREFIX = "[::";

    /** 标记后缀 */
    private static final String MARKER_SUFFIX = "::]";

    @Override
    public WatermarkType type() {
        return WatermarkType.SIMPLE_SUFFIX_MARKER;
    }

    @Override
    public boolean canWatermark(Object value) {
        // 仅支持字符串类型的文本值：数值等对象被 toString 后插入标记会破坏数据类型完整性
        if (!(value instanceof String)) {
            return false;
        }
        String str = ((String) value).trim();
        return !str.isEmpty();
    }

    @Override
    public String embed(Object value, String payload, String secret, long seed) {
        // 前置校验：避免调用方绕过 canWatermark 直接嵌入导致数据被破坏
        if (!canWatermark(value)) {
            throw new IllegalArgumentException("值不支持后缀标记水印，仅支持非空文本: " + type());
        }
        String str = (String) value;
        // 先清除已有的标记，避免多次嵌入叠加
        str = removeMarker(str);

        String marker = MARKER_PREFIX + payload + MARKER_SUFFIX;
        int insertPos = computeInsertPosition(str, secret, seed);

        return str.substring(0, insertPos) + marker + str.substring(insertPos);
    }

    @Override
    public String extract(Object value, String secret, long seed) {
        if (value == null) {
            return null;
        }
        String str = value.toString();
        int startIdx = str.indexOf(MARKER_PREFIX);
        if (startIdx < 0) {
            return null;
        }
        int endIdx = str.indexOf(MARKER_SUFFIX, startIdx);
        if (endIdx < 0) {
            return null;
        }
        return str.substring(startIdx + MARKER_PREFIX.length(), endIdx);
    }

    /**
     * 根据密钥和位置种子，确定性地计算标记在文本中的插入位置。
     * <p>
     * 对于长度为 N 的文本，插入点范围为 [0, N]：
     * <ul>
     *   <li>0 = 文本最前面</li>
     *   <li>N = 文本最末尾</li>
     *   <li>其他值 = 对应字符之后</li>
     * </ul>
     * </p>
     *
     * @param text   原始文本
     * @param secret 密钥
     * @param seed   位置种子
     * @return 插入位置 [0, text.length()]
     */
    private int computeInsertPosition(String text, String secret, long seed) {
        int len = text.length();
        if (len <= 1) {
            // 单字符或空文本：只有头部和尾部两个位置
            long h = mixHash(secret, seed);
            return (int) (Math.abs(h) % 2); // 0 或 1
        }
        // 插入点范围 [0, len]，共 len+1 个位置
        long h = mixHash(secret, seed);
        return (int) (Math.abs(h) % (len + 1));
    }

    /**
     * 密钥与位置种子的混合哈希，产生确定性伪随机值。
     */
    private long mixHash(String secret, long seed) {
        long h = secret.hashCode();
        h = h * 31 + seed;
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = (h ^ (h >>> 16)) * 0x45D9F3BL;
        h = h ^ (h >>> 16);
        return h;
    }

    /**
     * 移除文本中已有的标记。
     */
    private String removeMarker(String str) {
        int idx = str.indexOf(MARKER_PREFIX);
        if (idx >= 0) {
            int endIdx = str.indexOf(MARKER_SUFFIX, idx);
            if (endIdx >= 0) {
                return str.substring(0, idx) + str.substring(endIdx + MARKER_SUFFIX.length());
            }
        }
        return str;
    }
}
