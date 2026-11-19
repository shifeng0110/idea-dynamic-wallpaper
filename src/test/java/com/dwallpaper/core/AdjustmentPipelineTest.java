package com.dwallpaper.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调节管线单元测试（design.md AC-ADJ-1 blur=0 跳过、AC-ADJ-4 亮度 LUT 像素级、D5 档位、§4.3 sizeBucket）。
 */
class AdjustmentPipelineTest {

    @Test
    void blurBucketCoverage() {
        assertEquals(0, AdjustmentPipeline.blurBucketOf(0));
        assertEquals(1, AdjustmentPipeline.blurBucketOf(5));
        assertEquals(12, AdjustmentPipeline.blurBucketOf(60));
        assertEquals(13, AdjustmentPipeline.blurBucketOf(60) + 1); // 13 档（0..12）
    }

    @Test
    void sizeBucketGrid64() {
        assertEquals(64, AdjustmentPipeline.sizeBucketOf(1));
        assertEquals(64, AdjustmentPipeline.sizeBucketOf(64));
        assertEquals(1920, AdjustmentPipeline.sizeBucketOf(1920));
        assertEquals(1984, AdjustmentPipeline.sizeBucketOf(1921));
    }

    @Test
    void blurZeroSkipsPass() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB_PRE);
        assertSame(img, AdjustmentPipeline.boxBlur(img, 0, 3)); // radius=0 原样返回（AC-ADJ-1 代码路径）
    }

    @Test
    void brightnessLutPixelExact40And160() {
        // AC-ADJ-4：40%/160% 输出与参考 LUT 像素级一致。
        // 注意：TYPE_INT_ARGB_PRE 为预乘存储，alpha<255 时 setRGB 存在预乘舍入 → 全部用 alpha=255（无损）。
        BufferedImage src = new BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB_PRE);
        src.setRGB(0, 0, pack(255, 100, 60, 10));
        src.setRGB(1, 0, pack(255, 255, 255, 255));
        src.setRGB(2, 0, pack(255, 1, 2, 3));
        src.setRGB(3, 0, pack(255, 0, 0, 0));

        int[] lut40 = AdjustmentPipeline.brightnessLut(40);
        BufferedImage out40 = AdjustmentPipeline.applyBrightness(src, lut40);
        assertPixel(out40, 0, 0, ref(100, 40), ref(60, 40), ref(10, 40));
        assertPixel(out40, 1, 0, ref(255, 40), ref(255, 40), ref(255, 40));
        assertPixel(out40, 2, 0, ref(1, 40), ref(2, 40), ref(3, 40));
        assertPixel(out40, 3, 0, ref(0, 40), ref(0, 40), ref(0, 40));

        int[] lut160 = AdjustmentPipeline.brightnessLut(160);
        // applyBrightness 对同类型输入为就地修改（契约）→ 160% 用独立副本验证
        BufferedImage src2 = new BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB_PRE);
        src2.setRGB(0, 0, pack(255, 100, 60, 10));
        src2.setRGB(1, 0, pack(255, 255, 255, 255));
        src2.setRGB(2, 0, pack(255, 1, 2, 3));
        src2.setRGB(3, 0, pack(255, 0, 0, 0));
        BufferedImage out160 = AdjustmentPipeline.applyBrightness(src2, lut160);
        assertPixel(out160, 0, 0, ref(100, 160), ref(60, 160), ref(10, 160));
        assertPixel(out160, 2, 0, ref(1, 160), ref(2, 160), ref(3, 160));
    }

    @Test
    void lutBounds() {
        int[] lut = AdjustmentPipeline.brightnessLut(160);
        assertEquals(255, lut[200]); // 200*1.6=320 → clamp 255
        int[] lut40 = AdjustmentPipeline.brightnessLut(40);
        assertEquals(100, lut40[250]); // 250*0.4=100
    }

    @Test
    void boxBlurPreservesSizeAndAlphaAt0() {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB_PRE);
        img.setRGB(0, 0, pack(255, 255, 0, 0));
        BufferedImage out = AdjustmentPipeline.boxBlur(img, 5, 3);
        assertEquals(8, out.getWidth());
        assertEquals(8, out.getHeight());
        int p = out.getRGB(0, 0);
        assertTrue(((p >>> 24) & 0xFF) <= 255);
    }

    private static int ref(int v, int percent) {
        return Math.max(0, Math.min(255, (int) Math.round(v * percent / 100.0)));
    }

    private static int pack(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void assertPixel(BufferedImage img, int x, int y, int r, int g, int b) {
        int p = img.getRGB(x, y);
        assertEquals(255, (p >>> 24) & 0xFF, "alpha@" + x);
        assertEquals(r, (p >>> 16) & 0xFF, "red@" + x);
        assertEquals(g, (p >>> 8) & 0xFF, "green@" + x);
        assertEquals(b, p & 0xFF, "blue@" + x);
    }
}
