package io.github.nameof.watermark;

/**
 * 水印配置类，封装水印嵌入/提取所需的参数。
 */
public class WatermarkConfig {

    /**
     * 密钥，用于确定性地选择嵌入位置和编码水印。
     * 嵌入和提取必须使用相同的密钥。
     */
    private final String secret;

    /**
     * 最小重复因子：每个水印 bit 至少在多少个单元格中重复嵌入。
     * 值越大，对数据删除的容忍度越高，但可嵌入的有效载荷越小。
     * 默认值为 5。
     */
    private final int minRepetition;

    /**
     * 是否使用 bit-level 水印模式。
     * true = bit-level（高隐蔽性、高健壮性），false = simple（简单字符串水印）。
     * 默认为 true。
     */
    private boolean useBitLevel = true;

    /**
     * 使用默认最小重复因子（5）创建配置。
     *
     * @param secret 密钥
     */
    public WatermarkConfig(String secret) {
        this(secret, 5);
    }

    /**
     * 使用指定的最小重复因子创建配置。
     *
     * @param secret        密钥
     * @param minRepetition 最小重复因子，必须 >= 3
     */
    public WatermarkConfig(String secret, int minRepetition) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        if (minRepetition < 3) {
            throw new IllegalArgumentException("最小重复因子必须 >= 3");
        }
        this.secret = secret;
        this.minRepetition = minRepetition;
    }

    public String getSecret() {
        return secret;
    }

    public int getMinRepetition() {
        return minRepetition;
    }

    /**
     * 是否使用 bit-level 水印模式。
     */
    public boolean isUseBitLevel() {
        return useBitLevel;
    }

    /**
     * 设置是否使用 bit-level 水印模式。
     *
     * @param useBitLevel true = bit-level，false = simple
     * @return this（支持链式调用）
     */
    public WatermarkConfig setUseBitLevel(boolean useBitLevel) {
        this.useBitLevel = useBitLevel;
        return this;
    }
}
