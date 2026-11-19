package com.dwallpaper.media;

import com.dwallpaper.core.Frame;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单槽帧缓存 + 有界缓冲池（design.md §4.3「单槽队列，新帧覆盖旧帧」+ 任务口径「单壁纸帧缓存内存上限」）。
 *
 * <p><b>F14 修复（t6 审查）：已交付缓冲隔离（borrow isolation），借用纪律升级为永久隔离。</b>
 * 旧实现的「借用纪律」只保证到「下一次 pollFrame」，而 core AdjustmentPipeline.lastFrame 会长期持有
 * 交付帧引用并在参数变化 recompute 时异步重读 —— 旧实现此时可能已把该缓冲回池并被 CEF acquire
 * 复用写入，造成 recompute 帧撕裂。新纪律：</p>
 * <ul>
 *   <li><b>已交付缓冲（pollFrame 曾返回）永不回池复用</b> —— 进入隔离区（引用上限
 *       {@link #DELIVERED_QUARANTINE_LIMIT}=2，超出弃置最旧引用交 GC；无论引用是否被弃置，
 *       该缓冲都不会再被 {@link #acquire} 交付），pipeline.lastFrame 持有期间绝无并发写入；</li>
 *   <li><b>未交付缓冲（消费者从未见过，被新帧覆盖）回池复用</b> —— 无任何外部引用，写入安全；</li>
 *   <li>池上限 {@link #FRAME_POOL_BUFFERS}=4 仅约束可复用缓冲；单帧字节上限
 *       {@link #DEFAULT_MAX_FRAME_BYTES}=48MB（≈4K ARGB；系统属性 dwallpaper.jcef.maxFrameBytes 覆盖）。
 *       存活缓冲总量上界 = 池 4 + CEF 写入中 1 + current 1 + 隔离区 2 = 8 × maxFrameBytes
 *       （1080p 典型 8MB → 64MB，≤ design.md §4.3 全局 256MB 预算）。</li>
 * </ul>
 * <p>并发模型：acquire/publish 由 CEF UI 线程（onPaint 回调）调用；pollFrame/close 由 EDT（t4 调度器）
 * 调用；全部方法 synchronized（O(1) 临界区）。</p>
 */
public final class FrameSlot {

    /** 可复用缓冲池上限（仅「未交付」缓冲入池）。 */
    public static final int FRAME_POOL_BUFFERS = 6;
    /** 已交付缓冲隔离区引用上限（超出弃置最旧引用交 GC；隔离语义不受影响）。 */
    public static final int DELIVERED_QUARANTINE_LIMIT = 3;
    /** 单帧内存上限默认值（48MB ≈ 3840×2160×4）。 */
    public static final long DEFAULT_MAX_FRAME_BYTES = 48L * 1024 * 1024;

    private final long maxFrameBytes;
    private final Deque<BufferedImage> pool = new ArrayDeque<>();                 // 仅未交付缓冲（可安全复用）
    private final Deque<BufferedImage> deliveredQuarantine = new ArrayDeque<>(); // 已交付缓冲（永不复用）
    private BufferedImage current;        // 可消费帧
    private BufferedImage deliveredFrame; // pollFrame 最近一次交付的图像（隔离标记）
    private boolean hasNew;
    private long frameIdSeq;
    private long framesPublished;
    private long framesDropped;
    private boolean capLogged;

    public FrameSlot() {
        this(DEFAULT_MAX_FRAME_BYTES);
    }

    public FrameSlot(long maxFrameBytes) {
        this.maxFrameBytes = Math.max(1L, maxFrameBytes);
    }

    /** CEF 线程：取一块可写缓冲（同尺寸优先复用「未交付」池内缓冲）；超内存上限返回 null（丢帧）。 */
    public synchronized BufferedImage acquire(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if ((long) width * height * 4L > maxFrameBytes) {
            logCapOnce(width, height);
            framesDropped++;
            return null;
        }
        BufferedImage img = null;
        while (!pool.isEmpty() && img == null) {
            BufferedImage candidate = pool.pollFirst();
            if (candidate.getWidth() == width && candidate.getHeight() == height) {
                img = candidate;
            }
            // 异尺寸旧缓冲直接弃置（GC 回收）；已交付缓冲不在池中，此处不可能取到
        }
        if (img == null) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }
        return img;
    }

    /**
     * CEF 线程：发布新帧（覆盖旧帧，design.md §4.3 单槽语义）。
     * F14 隔离纪律：被覆盖的旧 current 若「已交付」→ 进隔离区永不回池（pipeline.lastFrame 可能
     * 长期持有，复用写入 = recompute 撕裂）；若「未交付」（消费者从未见过）→ 回池复用（安全）。
     */
    public synchronized void publish(BufferedImage image) {
        if (image == null) {
            return;
        }
        framesPublished++;
        BufferedImage old = current;
        current = image;
        hasNew = true;
        if (old != null) {
            if (old == deliveredFrame) {
                quarantine(old);
            } else {
                recycle(old);
            }
        }
    }

    /** 调度器（EDT）：无新帧返回 null（FrameSource 契约）；交付的图像自此进入隔离（永不复用）。 */
    public synchronized Frame pollFrame() {
        if (!hasNew || current == null) {
            return null;
        }
        hasNew = false;
        deliveredFrame = current;
        return new Frame(current, ++frameIdSeq, System.currentTimeMillis(),
                current.getWidth(), current.getHeight());
    }

    public synchronized long getFramesPublished() {
        return framesPublished;
    }

    public synchronized long getFramesDropped() {
        return framesDropped;
    }

    public synchronized int getPoolSize() {
        return pool.size();
    }

    public synchronized int getQuarantineSize() {
        return deliveredQuarantine.size();
    }

    public synchronized void close() {
        pool.clear();
        deliveredQuarantine.clear();
        current = null;
        deliveredFrame = null;
        hasNew = false;
    }

    /** 未交付缓冲回池（池满才弃置由 GC 回收）。 */
    private void recycle(BufferedImage image) {
        if (image == null) {
            return;
        }
        if (pool.size() < FRAME_POOL_BUFFERS) {
            pool.addLast(image);
        }
    }

    /**
     * F14：已交付缓冲隔离（隔离窗口内不复用，避免 recompute 撕裂）；
     * 但<b>超出窗口的最旧缓冲必须回池</b>——【2026-11-19 性能修复】原实现直接丢弃，
     * 导致 1920×1032 每帧都要 {@code new BufferedImage}（≈8MB/帧，30fps = 240MB/s），
     * GC 把实际帧率压到 ~4fps（日志实证：prepared 帧 35 秒才 120 帧）。这跟帧率设置无关，
     * 所以"调 fps 没用"。
     */
    private void quarantine(BufferedImage image) {
        if (image == null) {
            return;
        }
        while (deliveredQuarantine.size() >= DELIVERED_QUARANTINE_LIMIT) {
            recycle(deliveredQuarantine.pollFirst()); // 回池复用，而不是丢弃
        }
        deliveredQuarantine.addLast(image);
    }

    private void logCapOnce(int width, int height) {
        if (capLogged) {
            return;
        }
        capLogged = true;
        MediaLog.warn("帧缓存内存上限生效（丢帧，保留最后一帧）：单帧 " + width + "x" + height
                + " ARGB 需 " + ((long) width * height * 4L) + "B，上限 " + maxFrameBytes + "B"
                + "（系统属性 dwallpaper.jcef.maxFrameBytes 可调）");
    }
}
