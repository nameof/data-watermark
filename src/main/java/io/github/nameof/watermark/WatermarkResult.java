package io.github.nameof.watermark;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * 水印操作结果，用于 embed 和 extract 方法的返回值。
 *
 * @param <T> 结果数据类型（embed 返回 List<Map<String,Object>>，extract 返回 String）
 */
public class WatermarkResult<T> {

    /** 操作是否成功 */
    private final boolean success;

    /** 结果数据：embed 时为加水印后的表数据，extract 时为还原出的水印字符串 */
    private final T data;

    /** 错误信息（失败时） */
    private final String message;

    /** 水印嵌入/提取涉及的列名列表 */
    private final List<String> watermarkedColumns;

    /** 实际使用的重复因子 */
    private final int repetition;

    /** 水印模式：bit-level 或 simple */
    private String watermarkMode;

    /** 可嵌入/可提取的总单元格数 */
    private int totalCells;

    /** 成功提取的单元格数 */
    private int validExtractions;

    /** 提取置信度（0-100%），-1 表示未计算 */
    private double confidence = -1;

    private WatermarkResult(boolean success, T data, String message,
                            List<String> watermarkedColumns, int repetition) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.watermarkedColumns = watermarkedColumns != null
                ? Collections.unmodifiableList(watermarkedColumns)
                : Collections.emptyList();
        this.repetition = repetition;
    }

    /**
     * 创建成功的嵌入结果。
     */
    public static WatermarkResult<List<java.util.Map<String, Object>>> embedSuccess(
            List<java.util.Map<String, Object>> watermarkedTable,
            List<String> watermarkedColumns,
            int repetition) {
        return new WatermarkResult<>(true, watermarkedTable, null, watermarkedColumns, repetition);
    }

    /**
     * 创建成功的提取结果。
     */
    public static WatermarkResult<String> extractSuccess(String payload,
                                                          List<String> watermarkedColumns,
                                                          int repetition) {
        return new WatermarkResult<>(true, payload, null, watermarkedColumns, repetition);
    }

    /**
     * 创建成功的通用结果（支持任意数据类型）。
     *
     * @param data              结果数据
     * @param watermarkedColumns 涉及的列名列表
     * @param repetition        重复因子
     * @param <T>               结果数据类型
     * @return 成功的结果
     */
    public static <T> WatermarkResult<T> success(T data, List<String> watermarkedColumns,
                                                  int repetition) {
        return new WatermarkResult<>(true, data, null, watermarkedColumns, repetition);
    }

    /**
     * 创建失败结果。
     */
    public static <T> WatermarkResult<T> failure(String message) {
        return new WatermarkResult<>(false, null, message, null, 0);
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getWatermarkedColumns() {
        return watermarkedColumns;
    }

    public int getRepetition() {
        return repetition;
    }

    // ==================== 报告元数据 ====================

    /**
     * 获取水印模式。
     *
     * @return "bit-level" 或 "simple"
     */
    public String getWatermarkMode() {
        return watermarkMode;
    }

    /**
     * 设置水印模式（内部使用）。
     */
    public WatermarkResult<T> setWatermarkMode(String watermarkMode) {
        this.watermarkMode = watermarkMode;
        return this;
    }

    /**
     * 获取可嵌入/可提取的总单元格数。
     */
    public int getTotalCells() {
        return totalCells;
    }

    /**
     * 设置总单元格数（内部使用）。
     */
    public WatermarkResult<T> setTotalCells(int totalCells) {
        this.totalCells = totalCells;
        return this;
    }

    /**
     * 获取成功提取的单元格数。
     */
    public int getValidExtractions() {
        return validExtractions;
    }

    /**
     * 设置有效提取数（内部使用）。
     */
    public WatermarkResult<T> setValidExtractions(int validExtractions) {
        this.validExtractions = validExtractions;
        return this;
    }

    /**
     * 获取提取置信度（0-100%）。
     *
     * @return 置信度百分比，-1 表示未计算
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * 设置置信度（内部使用）。
     */
    public WatermarkResult<T> setConfidence(double confidence) {
        this.confidence = confidence;
        return this;
    }

    // ==================== 报告图片 ====================

    /**
     * 生成水印提取报告图片。
     * <p>
     * 使用 Java 2D 生成一张可视化的报告图片，包含提取时间、水印模式、
     * 重复因子、校验状态、还原载荷、提取统计等信息。
     * 可作为数据泄露追责的证据展示。
     * </p>
     *
     * @return 报告图片，可通过 ImageIO 写入文件
     * @throws IllegalStateException 如果此结果不是提取结果（无水印模式信息）
     */
    public BufferedImage getReportImage() {
        return WatermarkReportGenerator.generateReport(this);
    }

    @Override
    public String toString() {
        if (success) {
            return "WatermarkResult{success=true, data=" + data
                    + ", watermarkedColumns=" + watermarkedColumns
                    + ", repetition=" + repetition + "}";
        } else {
            return "WatermarkResult{success=false, message='" + message + "'}";
        }
    }
}
