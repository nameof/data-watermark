package io.github.nameof.watermark;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 水印提取报告图片生成器。
 * <p>
 * 使用 Java 2D（java.awt）生成一张可视化的水印提取报告图片，
 * 用于数据泄露追责时的证据展示。无需第三方图片库。
 * </p>
 * <p>
 * 报告内容包括：
 * <ul>
 *   <li>提取时间</li>
 *   <li>水印模式（bit-level / simple）</li>
 *   <li>重复因子 R</li>
 *   <li>CRC32 校验状态</li>
 *   <li>还原的载荷内容</li>
 *   <li>提取统计（总单元格、有效提取数、置信度）</li>
 *   <li>涉及的列名</li>
 * </ul>
 * </p>
 */
public class WatermarkReportGenerator {

    /** 图片宽度 */
    private static final int WIDTH = 700;

    /** 边距 */
    private static final int MARGIN = 40;

    /** 行高 */
    private static final int LINE_HEIGHT = 28;

    /** 标题字体 */
    private static final Font TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 22);

    /** 标签字体 */
    private static final Font LABEL_FONT = new Font("Microsoft YaHei", Font.PLAIN, 14);

    /** 载荷字体（等宽） */
    private static final Font PAYLOAD_FONT = new Font("Consolas", Font.BOLD, 16);

    /** 小字体 */
    private static final Font SMALL_FONT = new Font("Microsoft YaHei", Font.PLAIN, 12);

    // 颜色
    private static final Color BG_COLOR = new Color(255, 255, 255);
    private static final Color HEADER_BG = new Color(41, 65, 114);
    private static final Color HEADER_FG = Color.WHITE;
    private static final Color LABEL_COLOR = new Color(80, 80, 80);
    private static final Color VALUE_COLOR = new Color(30, 30, 30);
    private static final Color SUCCESS_COLOR = new Color(34, 139, 34);
    private static final Color FAIL_COLOR = new Color(200, 50, 50);
    private static final Color PAYLOAD_BG = new Color(245, 245, 250);
    private static final Color PAYLOAD_BORDER = new Color(41, 65, 114);
    private static final Color DIVIDER_COLOR = new Color(220, 220, 220);

    /**
     * 生成水印提取报告图片。
     *
     * @param result 水印提取结果
     * @return 报告图片
     */
    public static BufferedImage generateReport(WatermarkResult<?> result) {
        // 计算图片高度
        int height = calculateHeight(result);
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // 抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 背景
        g2d.setColor(BG_COLOR);
        g2d.fillRect(0, 0, WIDTH, height);

        int y = 0;

        // 1. 标题栏
        y = drawHeader(g2d, y);

        // 2. 基本信息区
        y = drawBasicInfo(g2d, y, result);

        // 3. 载荷区
        y = drawPayloadSection(g2d, y, result);

        // 4. 统计区
        y = drawStatistics(g2d, y, result);

        // 5. 底部
        drawFooter(g2d, y);

        g2d.dispose();
        return image;
    }

    /**
     * 绘制标题栏。
     */
    private static int drawHeader(Graphics2D g2d, int y) {
        int headerHeight = 60;
        g2d.setColor(HEADER_BG);
        g2d.fillRect(0, 0, WIDTH, headerHeight);

        g2d.setColor(HEADER_FG);
        g2d.setFont(TITLE_FONT);
        FontMetrics fm = g2d.getFontMetrics();
        String title = "数据水印提取报告";
        int titleWidth = fm.stringWidth(title);
        g2d.drawString(title, (WIDTH - titleWidth) / 2, 38);

        return headerHeight;
    }

    /**
     * 绘制基本信息区。
     */
    private static int drawBasicInfo(Graphics2D g2d, int y, WatermarkResult<?> result) {
        y += 20;
        g2d.setFont(LABEL_FONT);

        // 提取时间
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        y = drawInfoRow(g2d, y, "提取时间", time);

        // 水印模式
        String mode = result.getWatermarkMode();
        if (mode == null || mode.isEmpty()) mode = "未知";
        y = drawInfoRow(g2d, y, "水印模式", mode);

        // 重复因子
        y = drawInfoRow(g2d, y, "重复因子", "R = " + result.getRepetition());

        // 校验状态
        String crcStatus = result.isSuccess() ? "✓ 通过（CRC32 校验成功）" : "✗ 失败";
        Color statusColor = result.isSuccess() ? SUCCESS_COLOR : FAIL_COLOR;
        y = drawInfoRow(g2d, y, "校验状态", crcStatus, statusColor);

        // 涉及列
        List<String> cols = result.getWatermarkedColumns();
        String colStr = cols != null && !cols.isEmpty() ? cols.toString() : "无";
        y = drawInfoRow(g2d, y, "涉及列", colStr);

        // 分隔线
        y += 5;
        g2d.setColor(DIVIDER_COLOR);
        g2d.drawLine(MARGIN, y, WIDTH - MARGIN, y);

        return y;
    }

    /**
     * 绘制载荷区。
     */
    private static int drawPayloadSection(Graphics2D g2d, int y, WatermarkResult<?> result) {
        y += 20;

        // 标签
        g2d.setFont(LABEL_FONT);
        g2d.setColor(LABEL_COLOR);
        g2d.drawString("还原载荷：", MARGIN, y);
        y += 15;

        // 载荷框
        String payload = result.isSuccess() && result.getData() != null
                ? result.getData().toString() : "(提取失败)";
        g2d.setFont(PAYLOAD_FONT);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(payload);
        int boxWidth = Math.min(textWidth + 40, WIDTH - MARGIN * 2);
        int boxHeight = 45;

        // 背景
        g2d.setColor(PAYLOAD_BG);
        g2d.fillRoundRect(MARGIN, y, boxWidth, boxHeight, 8, 8);

        // 边框
        g2d.setColor(result.isSuccess() ? PAYLOAD_BORDER : FAIL_COLOR);
        g2d.setStroke(new BasicStroke(2f));
        g2d.drawRoundRect(MARGIN, y, boxWidth, boxHeight, 8, 8);

        // 文字
        g2d.setColor(result.isSuccess() ? VALUE_COLOR : FAIL_COLOR);
        int textX = MARGIN + 20;
        int textY = y + (boxHeight + fm.getAscent() - fm.getDescent()) / 2;
        g2d.drawString(payload, textX, textY);

        return y + boxHeight + 10;
    }

    /**
     * 绘制统计区。
     */
    private static int drawStatistics(Graphics2D g2d, int y, WatermarkResult<?> result) {
        y += 15;

        // 分隔线
        g2d.setColor(DIVIDER_COLOR);
        g2d.drawLine(MARGIN, y, WIDTH - MARGIN, y);
        y += 20;

        g2d.setFont(LABEL_FONT);
        g2d.setColor(LABEL_COLOR);
        g2d.drawString("提取统计：", MARGIN, y);
        y += LINE_HEIGHT;

        // 总单元格
        int totalCells = result.getTotalCells();
        if (totalCells > 0) {
            y = drawInfoRow(g2d, y, "可嵌入单元格", String.valueOf(totalCells));
        }

        // 有效提取
        int validExtractions = result.getValidExtractions();
        if (validExtractions > 0) {
            y = drawInfoRow(g2d, y, "有效提取数", String.valueOf(validExtractions));
        }

        // 置信度
        double confidence = result.getConfidence();
        if (confidence >= 0) {
            String confStr = String.format("%.1f%%", confidence);
            Color confColor = confidence >= 90 ? SUCCESS_COLOR
                    : confidence >= 70 ? new Color(200, 150, 0)
                    : FAIL_COLOR;
            y = drawInfoRow(g2d, y, "投票置信度", confStr, confColor);
        }

        // 错误信息（失败时）
        if (!result.isSuccess() && result.getMessage() != null) {
            y += 5;
            g2d.setFont(LABEL_FONT);
            g2d.setColor(FAIL_COLOR);
            g2d.drawString("错误信息：" + result.getMessage(), MARGIN, y);
            y += LINE_HEIGHT;
        }

        return y;
    }

    /**
     * 绘制底部。
     */
    private static void drawFooter(Graphics2D g2d, int y) {
        y += 10;
        g2d.setColor(DIVIDER_COLOR);
        g2d.drawLine(MARGIN, y, WIDTH - MARGIN, y);
        y += 20;

        g2d.setFont(SMALL_FONT);
        g2d.setColor(new Color(150, 150, 150));
        String footer = "Data Watermark Library · 本报告由系统自动生成，可作为数据追溯证据";
        FontMetrics fm = g2d.getFontMetrics();
        int footerWidth = fm.stringWidth(footer);
        g2d.drawString(footer, (WIDTH - footerWidth) / 2, y);
    }

    /**
     * 绘制一行信息。
     */
    private static int drawInfoRow(Graphics2D g2d, int y, String label, String value) {
        return drawInfoRow(g2d, y, label, value, VALUE_COLOR);
    }

    /**
     * 绘制一行信息（指定值颜色）。
     */
    private static int drawInfoRow(Graphics2D g2d, int y, String label, String value, Color valueColor) {
        g2d.setFont(LABEL_FONT);

        // 标签
        g2d.setColor(LABEL_COLOR);
        g2d.drawString(label + "：", MARGIN, y);

        // 值
        FontMetrics labelFm = g2d.getFontMetrics();
        int labelWidth = labelFm.stringWidth(label + "：");
        g2d.setColor(valueColor);
        g2d.drawString(value, MARGIN + labelWidth + 5, y);

        return y + LINE_HEIGHT;
    }

    /**
     * 计算图片高度。
     */
    private static int calculateHeight(WatermarkResult<?> result) {
        int height = 60; // header
        height += 20 + LINE_HEIGHT * 5 + 5; // basic info (5 rows + divider)
        height += 20 + 15 + 45 + 10; // payload section
        height += 15 + 5 + 20 + LINE_HEIGHT; // stats header

        // 统计行数
        int statRows = 0;
        if (result.getTotalCells() > 0) statRows++;
        if (result.getValidExtractions() > 0) statRows++;
        if (result.getConfidence() >= 0) statRows++;
        height += LINE_HEIGHT * statRows;

        if (!result.isSuccess() && result.getMessage() != null) {
            height += LINE_HEIGHT + 5;
        }

        height += 10 + 10 + 20 + 20; // footer
        return height;
    }
}
