package com.dwallpaper.media;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * CEF onPaint framebuffer → ARGB 帧拷贝（design.md §9.3：onPaint 内同步拷贝，CEF 线程禁止回存）。
 * 字节序口径（t5 核验落点）：CEF windowless buffer 为 BGRA 序（小端机器内存序）；
 * 按小端 int 直接读取即得 0xAARRGGBB —— 与 {@link BufferedImage#TYPE_INT_ARGB} 布局一致，无需逐字节重排。
 * captain 口径「onPaint 取 ARGB 帧」在此落地；runIde 首帧色彩核验留 t7 端到端（design.md OPEN-3 附注）。
 */
public final class BgraFrameCopy {

    private BgraFrameCopy() {
    }

    /**
     * 拷贝至多 {@code pixels} 个像素到 dst（dst.length ≥ 写入数）；返回实际写入像素数；
     * buffer 剩余字节不足时按剩余量截断。不推进源 buffer position（CEF 复用该 buffer）。
     */
    public static int copyToArgb(ByteBuffer buffer, int[] dst, int pixels) {
        if (buffer == null || dst == null || pixels <= 0) {
            return 0;
        }
        int available = buffer.remaining() / 4;
        int count = Math.min(Math.min(pixels, dst.length), available);
        if (count <= 0) {
            return 0;
        }
        ByteOrder original = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            IntBuffer ints = buffer.asIntBuffer();
            ints.get(dst, 0, count);
        } finally {
            buffer.order(original);
        }
        return count;
    }

    /** 将 ARGB int[] 写入（或复用同尺寸 TYPE_INT_ARGB）图像；返回承载图像。 */
    public static BufferedImage intoImage(int[] argb, int width, int height, BufferedImage reuse) {
        BufferedImage image = (reuse != null
                && reuse.getWidth() == width
                && reuse.getHeight() == height
                && reuse.getType() == BufferedImage.TYPE_INT_ARGB)
                ? reuse
                : new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(argb, 0, raster, 0, width * height);
        return image;
    }
}
