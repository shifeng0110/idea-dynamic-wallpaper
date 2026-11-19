package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameSource;
import com.dwallpaper.core.NaturalOrder;
import com.intellij.openapi.diagnostic.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 序列帧/目录轮播帧源（任务口径 §4 + design.md §9.1）：目录内图片自然序排序
 * （F17：统一委托 core.NaturalOrder——数字段长度安全比较，任意位数不溢出），
 * 统一 fps 默认 24；后台线程逐帧解码循环播放；
 * alpha PNG 支持（TYPE_INT_ARGB 由 ImageIO 原生保留）。单槽交付 + 帧序去重。
 * 【F6 配套】setRate(int)：≤0 暂停解码线程（wait/notify），>0 恢复——disable 期间不空转。
 */
public final class SequenceFrameSource implements FrameSource {

    private static final Logger LOG = Logger.getInstance(SequenceFrameSource.class);
    private static final AtomicLong SEQ = new AtomicLong();

    private final List<Path> files;
    private final int frameIntervalMs;
    private final AtomicReference<Frame> slot = new AtomicReference<>();
    private final Object pauseLock = new Object();
    private Thread decodeThread;
    private volatile boolean closed;
    private volatile boolean decodePaused; // F6
    private volatile long deliveredSeq = -1;

    public SequenceFrameSource(List<Path> imageFiles, int fps) {
        this.files = List.copyOf(imageFiles);
        this.frameIntervalMs = Math.max(33, 1000 / Math.max(1, fps));
    }

    /** 目录 → 自然序图片列表（含 gif 单帧；t4 轮播按静态帧处理）。 */
    public static List<Path> listImages(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> isImage(f.getFileName().toString()))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString().toLowerCase(Locale.ROOT),
                            NaturalOrder.comparator()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp") || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    /**
     * 自然序比较器（F17：委托 core.NaturalOrder 单一实现——数字段按长度安全比较，任意位数不溢出）。
     * 保留静态入口以兼容既有调用方/测试。
     */
    public static Comparator<String> naturalOrder() {
        return NaturalOrder.comparator();
    }

    @Override
    public void start() {
        if (decodeThread != null) {
            return;
        }
        decodeThread = new Thread(this::loop, "dwallpaper-sequence-decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    private void loop() {
        int i = 0;
        while (!closed && !files.isEmpty()) {
            Path file = files.get(i);
            try {
                BufferedImage img = ImageIO.read(file.toFile());
                if (img != null) {
                    slot.set(new Frame(img, SEQ.incrementAndGet(), System.currentTimeMillis(),
                            img.getWidth(), img.getHeight()));
                }
            } catch (Throwable t) {
                LOG.warn("dwallpaper: sequence frame read failed: " + file + " -> " + t);
            }
            try {
                sleepWithPause(frameIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
            i = (i + 1) % files.size();
        }
    }

    /** 帧间延迟：暂停态转为挂起等待，恢复/关闭立即返回。 */
    private void sleepWithPause(long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!closed && System.currentTimeMillis() < deadline) {
            synchronized (pauseLock) {
                if (decodePaused) {
                    pauseLock.wait(500);
                    return;
                }
            }
            Thread.sleep(20);
        }
    }

    /** F6 配套：≤0 暂停解码（disable/IDLE/MANUAL），>0 恢复。 */
    @Override
    public void setRate(int fps) {
        decodePaused = fps <= 0;
        if (!decodePaused) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        LOG.info("dwallpaper: sequence decode " + (decodePaused ? "paused" : "resumed") + " (rate=" + fps + ")");
    }

    @Override
    public Frame pollFrame() {
        Frame f = slot.get();
        if (f == null || f.frameId() == deliveredSeq) {
            return null;
        }
        deliveredSeq = f.frameId();
        return f;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public void close() {
        closed = true;
        decodePaused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (decodeThread != null) {
            decodeThread.interrupt();
            decodeThread = null;
        }
    }
}
