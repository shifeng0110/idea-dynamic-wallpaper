package com.dwallpaper.media;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * BGRA→ARGB 字节序核验（t5 核验落点，design.md OPEN-3 附注）：
 * CEF windowless buffer 为 BGRA 序，小端 int 直读 = 0xAARRGGBB（TYPE_INT_ARGB）。
 */
class BgraFrameCopyTest {

    @Test
    void bgraLittleEndianReadsAsArgb() {
        // 像素1 = B=0x10,G=0x20,R=0x30,A=0x40；像素2 = 全 F（不透明）
        byte[] bytes = {0x10, 0x20, 0x30, 0x40, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int[] dst = new int[2];
        int copied = BgraFrameCopy.copyToArgb(buffer, dst, 2);
        assertEquals(2, copied);
        assertEquals(0x40302010, dst[0], "小端读取 [B,G,R,A] → 0xAARRGGBB");
        assertEquals(0xFFFFFFFF, dst[1]);
        assertEquals(0, buffer.position(), "拷贝不得推进源 buffer（CEF 复用）");
    }

    @Test
    void truncatesWhenBufferShorterThanPixels() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}); // 1.25 像素
        int[] dst = new int[8];
        int copied = BgraFrameCopy.copyToArgb(buffer, dst, 8);
        assertEquals(1, copied, "按剩余字节截断");
        assertEquals(0x04030201, dst[0]);
    }

    @Test
    void zeroLengthBufferCopiesNothing() {
        int copied = BgraFrameCopy.copyToArgb(ByteBuffer.allocate(0), new int[4], 4);
        assertEquals(0, copied);
        assertEquals(0, BgraFrameCopy.copyToArgb(null, new int[4], 4));
    }

    @Test
    void intoImageReusesMatchingBuffer() {
        BufferedImage reuse = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = {0xFF112233, 0x80445566};
        BufferedImage out = BgraFrameCopy.intoImage(pixels, 2, 1, reuse);
        assertSame(reuse, out, "同尺寸同类型必须复用（帧池纪律）");
        assertEquals(0xFF112233, out.getRGB(0, 0));
        assertEquals(0x80445566, out.getRGB(1, 0));

        // 尺寸不符必须新建：3x1 需要 3 个像素
        int[] three = {0xFF112233, 0x80445566, 0x40302010};
        BufferedImage fresh = BgraFrameCopy.intoImage(three, 3, 1, reuse);
        assertNotSame(reuse, fresh, "尺寸不符必须新建");
        assertEquals(3, fresh.getWidth());
        assertEquals(0x40302010, fresh.getRGB(2, 0));
    }
}
