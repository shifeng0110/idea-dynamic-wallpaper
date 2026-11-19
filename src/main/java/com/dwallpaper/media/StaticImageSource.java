package com.dwallpaper.media;

import com.dwallpaper.core.AdjustmentPipeline;
import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameSource;
import com.intellij.openapi.diagnostic.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 静态图帧源（design.md §9.1）：单帧，兜底与手动目录；isDynamic()=false。
 * 解码尺寸钳制：最长边 >2048 时预缩放（防大图爆内存，AC-MEDIA-5 同源纪律）。
 * 【F4 修复】Path 构造器不再同步解码（EDT 冻结点）——解码延迟到 start()/首次 pollFrame
 * 触发的后台线程；解码完成前 pollFrame 返回 null（泵自动重试）。BufferedImage 直建构造器
 * （测试/自测）保持同步语义。
 * 【F4 配套】FileChannel+ImageInputStream 句柄由 volatile 字段持有，close() 同步关闭——
 * Windows 下进行中解码的文件锁立即释放（temp 目录清理/壁纸切换不再被句柄卡住）。
 */
public final class StaticImageSource implements FrameSource {

    private static final Logger LOG = Logger.getInstance(StaticImageSource.class);
    private static final int MAX_DIM = 2048;

    private final Path file;            // null = 内存图直建（已解码）
    private final Frame readyFrame;     // 内存图直建路径
    private final AtomicReference<Frame> slot = new AtomicReference<>();
    private final Object startLock = new Object();
    private Thread decodeThread;
    private volatile javax.imageio.stream.ImageInputStream decodeStream; // F4：close() 同步释放文件句柄
    private volatile boolean closed;
    private volatile boolean delivered;
    private volatile long deliveredSeq = -1;
    private static final AtomicLong SEQ = new AtomicLong();

    /** 常规入口：文件路径（延迟后台解码）。 */
    public StaticImageSource(Path file) {
        this.file = file;
        this.readyFrame = null;
    }

    /** 测试/自测入口：内存图直建（同步持有帧）。 */
    public StaticImageSource(BufferedImage img) {
        this.file = null;
        this.readyFrame = new Frame(clamp(img), SEQ.incrementAndGet(),
                System.currentTimeMillis(), img.getWidth(), img.getHeight());
    }

    @Override
    public void start() {
        if (readyFrame != null || decodeThread != null) {
            return;
        }
        synchronized (startLock) {
            if (decodeThread != null || closed || file == null) {
                return;
            }
            decodeThread = new Thread(this::decodeOnce, "dwallpaper-static-decode");
            decodeThread.setDaemon(true);
            decodeThread.start();
        }
    }

    private void decodeOnce() {
        // 【t10 代修（captain 授权，t9 F4 配套回归）：createImageInputStream(FileChannel) 无 SPI 支持
        //  → 恒返 null → getImageReaders(null) 抛 IllegalArgumentException 被吞 → 静态图永无帧。
        //  改走 File 直连（FileImageInputStreamSpi，零磁盘缓存拷贝）+ null 守卫降级；
        //  F4 语义不变：延迟后台解码 + volatile decodeStream 句柄 + close() 同步释放文件锁。】
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                LOG.warn("dwallpaper: no image input stream (degrade): " + file);
                return;
            }
            decodeStream = in; // close() 可同步关闭 → 文件句柄立即释放（F4 配套）
            Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) {
                LOG.warn("dwallpaper: no image reader for (degrade): " + file);
                return;
            }
            javax.imageio.ImageReader reader = it.next();
            try {
                reader.setInput(in);
                if (closed) {
                    return;
                }
                BufferedImage img = reader.read(0);
                if (img == null) {
                    LOG.warn("dwallpaper: unreadable image (degrade): " + file);
                    return;
                }
                BufferedImage clamped = clamp(img);
                slot.set(new Frame(clamped, SEQ.incrementAndGet(), System.currentTimeMillis(),
                        clamped.getWidth(), clamped.getHeight()));
            } finally {
                reader.dispose();
            }
        } catch (Throwable t) {
            if (!closed) {
                LOG.warn("dwallpaper: static decode failed (degrade): " + file + " -> " + t);
            }
        } finally {
            decodeStream = null;
        }
    }

    private static BufferedImage clamp(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int dim = Math.max(w, h);
        if (dim <= MAX_DIM) {
            return img;
        }
        double scale = (double) MAX_DIM / dim;
        return AdjustmentPipeline.scaleTo(img, Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
    }

    @Override
    public Frame pollFrame() {
        if (readyFrame != null) {
            if (!delivered) {
                delivered = true;
                return readyFrame;
            }
            return null;
        }
        if (decodeThread == null && !closed) {
            start(); // F4：首次 poll 自动触发后台解码（调用方未显式 start 也安全）
            return null;
        }
        Frame f = slot.get();
        if (f == null || f.frameId() == deliveredSeq) {
            return null; // 解码完成前返回 null，泵自动重试
        }
        deliveredSeq = f.frameId();
        delivered = true;
        return f;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public void close() {
        closed = true;
        javax.imageio.stream.ImageInputStream in = decodeStream;
        if (in != null) {
            try {
                in.close(); // F4 配套：立即释放文件句柄（Windows 文件锁不卡切换/清理）
            } catch (Throwable ignored) {
            }
            decodeStream = null;
        }
        if (decodeThread != null) {
            decodeThread.interrupt();
            decodeThread = null;
        }
    }
}
