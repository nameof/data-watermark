package io.github.nameof.watermark.diag;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯内存验证：SIMPLE_INVISIBLE_PADDING（零宽填充）单独使用时能否提取。
 * <p>
 * 不经数据库，排除"MySQL 未保存零宽字符"的可能，直接判定是否为提取路由问题。
 * 对照组：同一份值用 SIMPLE_SUFFIX_MARKER 嵌入 + 提取。
 */
public class InvisiblePaddingCheck {

    public static void main(String[] args) {
        Watermarker wm = new Watermarker(new WatermarkConfig("demo-secret"));
        String payload = "这是隐蔽信息";

        List<Object> vals = new ArrayList<Object>();
        for (int i = 0; i < 50; i++) vals.add("\u7528\u6237" + i + "\u6d4b\u8bd5"); // 用户i测试

        // ---------- A: InvisiblePadding ----------
        List<WatermarkType> ip = Collections.singletonList(WatermarkType.SIMPLE_INVISIBLE_PADDING);
        WatermarkResult<List<Object>> ea = wm.embed(vals, payload, ip);
        System.out.println("[A] InvisiblePadding 嵌入: success=" + ea.isSuccess()
                + " type=" + ea.getWatermarkType());
        if (ea.isSuccess()) {
            Object v0 = ea.getData().get(0);
            System.out.println("    [A] 第 0 个值原长=" + vals.get(0).toString().length()
                    + " → 嵌入后长=" + v0.toString().length()
                    + "（差值即追加的零宽字符数，非 0 说明水印确实写进了内存对象）");
            WatermarkResult<String> xa = wm.extractValues(ea.getData(), ip);
            System.out.println("    [A] 显式指定策略提取: success=" + xa.isSuccess()
                    + " msg=" + xa.getMessage());
            WatermarkResult<String> xa2 = wm.extract(ea.getData()); // 不给 types，等价于自动
            System.out.println("    [A] 不指定策略提取: success=" + xa2.isSuccess()
                    + " msg=" + xa2.getMessage());
        }

        // ---------- B: 对照组 SuffixMarker ----------
        List<WatermarkType> sm = Collections.singletonList(WatermarkType.SIMPLE_SUFFIX_MARKER);
        WatermarkResult<List<Object>> eb = wm.embed(vals, payload, sm);
        System.out.println("[B] SuffixMarker 嵌入: success=" + eb.isSuccess());
        if (eb.isSuccess()) {
            System.out.println("    [B] 第 0 个值嵌入后 = " + eb.getData().get(0));
            WatermarkResult<String> xb = wm.extractValues(eb.getData(), sm);
            System.out.println("    [B] 显式指定策略提取: success=" + xb.isSuccess()
                    + " payload=" + xb.getData());
        }
    }
}
