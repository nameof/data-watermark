package io.github.nameof.watermark.diag;

import io.github.nameof.watermark.core.WatermarkConfig;
import io.github.nameof.watermark.core.WatermarkResult;
import io.github.nameof.watermark.core.WatermarkType;
import io.github.nameof.watermark.core.Watermarker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 最小删除对照测试：同一个程序里跑 4 个场景，说明——
 *
 *   [1] bit 档   不删任何行        -> 成功（功能本身没坏）
 *   [2] bit 档   删【中间】1 行     -> 失败（BUG 现场：后面所有 0/1 槽位前移，暗号错乱）
 *   [3] bit 档   删【尾部】1 行     -> 成功（现有单测只删尾部，所以一直是绿的）
 *   [4] simple 档 随机删 100 行(20%) -> 成功（MySQL 集成测试用的是这一档，删除本来就没事）
 *
 * 只用真实库类，无 mock。运行：java MinDeleteDemo
 */
public class MinDeleteDemo {

    public static void main(String[] args) {
        Watermarker wm = new Watermarker(new WatermarkConfig("demo-secret"));
        List<WatermarkType> bitZh = Collections.singletonList(WatermarkType.BIT_CHINESE_ZERO_WIDTH);
        List<WatermarkType> simple = Collections.singletonList(WatermarkType.SIMPLE_SUFFIX_MARKER);
        String payload = "test";

        // 准备 900 个纯中文值（保证 bit 档路由到 ChineseText 策略）
        List<Object> orig = new ArrayList<>();
        for (int i = 0; i < 900; i++) orig.add("\u7528\u6237" + i + "\u6d4b\u8bd5"); // 用户i测试

        // ===== 场景 1/2/3：bit 档 =====
        WatermarkResult<List<Object>> er = wm.embed(orig, payload, bitZh);
        System.out.println("bit 档嵌入: success=" + er.isSuccess()
                + " repetition=" + er.getRepetition() + " type=" + er.getWatermarkType()
                + (er.isSuccess() ? "" : " msg=" + er.getMessage()));
        if (!er.isSuccess()) return;
        List<Object> marked = new ArrayList<>(er.getData());

        check("[1] bit 档   不删任何行      ", wm.extract(marked), payload);
        List<Object> delMid = new ArrayList<>(marked); delMid.remove(100);
        check("[2] bit 档   删中间第100行   ", wm.extract(delMid), payload);
        List<Object> delTail = new ArrayList<>(marked); delTail.remove(delTail.size() - 1);
        check("[3] bit 档   删尾部最后1行   ", wm.extract(delTail), payload);

        // ===== 场景 4：simple 档（MySQL 集成测试用的就是这档）=====
        WatermarkResult<List<Object>> er2 = wm.embed(orig, payload, simple);
        List<Object> marked2 = new ArrayList<>(er2.getData());
        List<Object> delRand = new ArrayList<>(marked2);
        Random rnd = new Random(42);
        for (int k = 0; k < 100; k++) delRand.remove(rnd.nextInt(delRand.size())); // 随机删 100 行（含头部/中间）
        check("[4] simple 档 随机删100行(20%)", wm.extract(delRand), payload);
    }

    static void check(String label, WatermarkResult<String> r, String expected) {
        String got = r.isSuccess() ? r.getData() : null;
        String verdict = (r.isSuccess() && expected.equals(got))
                ? "OK   payload=\"" + got + "\""
                : "FAIL " + (r.isSuccess() ? "payload 错误: \"" + got + "\"" : r.getMessage());
        System.out.println(label + " -> " + verdict);
    }
}
