package io.github.nameof.watermark.core;

/**
 * 水印配置类，封装水印嵌入/提取所需的参数。
 */
public class WatermarkConfig {

    /**
     * 密钥，用于确定性地选择嵌入位置和编码水印。
     * <p>
     * bit-level 模式：密钥同时决定嵌入位置与载荷置乱密钥流，嵌入和提取必须使用相同的密钥；
     * simple 模式：密钥仅决定嵌入位置，提取不依赖密钥（SuffixMarker 为可见标记，设计上不保密）。
     * </p>
     */
    private final String secret;

    /**
     * 最小重复因子：每个水印 bit 至少在多少个单元格中重复嵌入。
     * 值越大，对数据删除的容忍度越高，但可嵌入的有效载荷越小。
     * 默认值为 5。
     */
    private final int minRepetition;

    /**
     * 切片行数，供 Database 层使用。
     * 默认值为 2000。
     */
    private final int chunkSize;

    /**
     * 使用默认参数创建配置（minRepetition=5, chunkSize=2000）。
     *
     * @param secret 密钥
     */
    public WatermarkConfig(String secret) {
        this(secret, 5, 2000);
    }

    /**
     * 使用指定的最小重复因子创建配置（chunkSize=2000）。
     *
     * @param secret        密钥
     * @param minRepetition 最小重复因子，必须 >= 3
     */
    public WatermarkConfig(String secret, int minRepetition) {
        this(secret, minRepetition, 2000);
    }

    /**
     * 使用指定参数创建配置。
     *
     * @param secret        密钥
     * @param minRepetition 最小重复因子，必须 >= 3
     * @param chunkSize     切片行数，默认 2000
     */
    public WatermarkConfig(String secret, int minRepetition, int chunkSize) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        if (minRepetition < 3) {
            throw new IllegalArgumentException("最小重复因子必须 >= 3");
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("切片行数必须 >= 1");
        }
        this.secret = secret;
        this.minRepetition = minRepetition;
        this.chunkSize = chunkSize;
    }

    public String getSecret() {
        return secret;
    }

    public int getMinRepetition() {
        return minRepetition;
    }

    public int getChunkSize() {
        return chunkSize;
    }
}
