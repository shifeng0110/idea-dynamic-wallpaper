package com.dwallpaper.core;

import org.jetbrains.annotations.Nullable;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.Consumer;

/**
 * 调节管线（design.md §5）：blur 桶 + 亮度 LUT + 预缩放 → PreparedFrame。
 * 全部预计算在后台单线程执行（单槽 latest，慢即丢帧，§4.3/§11），EDT 只做最终 blit（§5.2）。
 * 模糊 = 5px/档分盒（0–60 共 13 档，D5），3 次可分离盒式模糊近似高斯；blur=0 完全跳过模糊 pass（AC-ADJ-1）。
 * 亮度 = LUT（40–160%），=100% 跳过（AC-ADJ-4 像素级断言对象）。
 * 不透明度不在本管线：绘制端 AlphaComposite（§5.1，保证 90 仍混 10% 底色语义）。
 * 预缩放至 sizeBucket（64px 网格，限制重建频率 §4.3）→ TYPE_INT_ARGB_PRE（预乘，盒式模糊线性正确）。
 */
public final class AdjustmentPipeline {

    public static final int MAX_BLUR_PX = 60;
    public static final int BLUR_BUCKET_STEP_PX = 5;
    public static final int MIN_BRIGHTNESS_PERCENT = 40;
    public static final int MAX_BRIGHTNESS_PERCENT = 160;
    public static final int MAX_OPACITY_PERCENT = 90;

    private static final int SIZE_BUCKET_GRID = 64;
    private static final int MAX_BUCKET_DIM = 2560;
    private static final int SNAPSHOT_MAX_DIM = 512;

    /** 已调节完成、可直接绘制的帧（EDT 回调交付）。 */
    public record PreparedFrame(BufferedImage image, long frameId, int blurBucket, int width, int height, long seq) {
    }

    private record Params(int blurPx, int brightnessPercent, int targetW, int targetH) {
    }

    private final Object lock = new Object();
    private Frame latest;              // 单槽：新帧覆盖旧帧，丢弃积压（§4.3）
    private Frame lastFrame;           // 参数变化重算对象
    private Params params = new Params(0, 100, 0, 0);
    private boolean recomputeRequested;
    private Thread worker;
    private volatile boolean closed;
    private volatile Consumer<PreparedFrame> listener;
    private volatile BufferedImage snapshot; // ≤512 降采样，雾镜静态快照来源（§6.4）
    private long seqCounter;

    public void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::loop, "dwallpaper-adjustment");
        worker.setDaemon(true);
        worker.start();
    }

    public void setListener(Consumer<PreparedFrame> listener) {
        this.listener = listener;
    }

    /** 参数变更（任意线程）；blurPx 钳制 0–60、brightness 钳制 40–160；有 lastFrame 即触发异步重算（§5.2 防抖后调用）。 */
    public void updateParams(int blurPx, int brightnessPercent, int targetW, int targetH) {
        int blur = Math.max(0, Math.min(MAX_BLUR_PX, blurPx));
        int brightness = Math.max(MIN_BRIGHTNESS_PERCENT, Math.min(MAX_BRIGHTNESS_PERCENT, brightnessPercent));
        synchronized (lock) {
            params = new Params(blur, brightness, targetW, targetH);
            if (lastFrame != null) {
                recomputeRequested = true;
            }
            lock.notifyAll();
        }
    }

    /** 产帧入口：单槽覆盖，慢即丢帧（design.md §4.3）。 */
    public void submit(Frame frame) {
        synchronized (lock) {
            latest = frame;
            lock.notifyAll();
        }
    }

    private void loop() {
        while (!closed) {
            Frame frame;
            Params p;
            synchronized (lock) {
                while (!closed && latest == null && !recomputeRequested) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                if (latest != null) {
                    frame = latest;
                    latest = null;
                    lastFrame = frame;
                    recomputeRequested = false;
                } else {
                    frame = lastFrame;
                    recomputeRequested = false;
                }
                p = params;
            }
            try {
                long seq = ++seqCounter;
                PreparedFrame pf = prepare(frame, p, seq);
                makeSnapshot(pf.image());
                Consumer<PreparedFrame> l = listener;
                if (l != null) {
                    l.accept(pf);
                }
            } catch (Throwable ignored) {
                // 单帧失败不中断管线（§11 慢即丢帧纪律）
            }
        }
    }

    /** 【性能 2026-11-19】帧缓冲复用环（3 个）：避免每帧新建 BufferedImage，同时保证
     *  管线线程写下一帧时，EDT 正在绘制的那一帧不会被覆盖（撕裂/滚动条感）；
     *  对象身份也会随帧轮转，覆盖层据此判定"有新帧"。 */
    private final BufferedImage[] reuseRing = new BufferedImage[3];
    private int reuseRingIdx;
    private int reuseW = -1;
    private int reuseH = -1;

    /**
     * 【直通模式 2026-11-19】当媒体源已经用 ffmpeg 的 C 代码完成了缩放与亮度，管线
     * 再做一遍纯属浪费（实测：1600→1920 双线性缩放要 42ms/帧，直接把帧率压到 17fps）。
     * 直通模式跳过拷贝/缩放/亮度，直接把源帧当 PreparedFrame 交出。
     */
    private volatile boolean passThrough;

    public void setPassThrough(boolean on) {
        this.passThrough = on;
    }

    private PreparedFrame prepare(Frame frame, Params p, long seq) {
        long t0 = System.nanoTime();
        BufferedImage src = frame.image();
        if (passThrough) {
            prepareNanos += System.nanoTime() - t0;
            prepareCount++;
            if (prepareCount % 120 == 0) {
                com.intellij.openapi.diagnostic.Logger.getInstance(AdjustmentPipeline.class)
                        .info("dwallpaper: 管线直通（ffmpeg 已做缩放/亮度）"
                                + String.format(java.util.Locale.ROOT, " 平均 %.1fms/帧",
                                prepareNanos / 1_000_000.0 / 120));
                prepareNanos = 0;
            }
            return new PreparedFrame(src, frame.frameId(), 0,
                    src.getWidth(), src.getHeight(), seq);
        }
        int bucket = blurBucketOf(p.blurPx());
        int bw;
        int bh;
        if (bucket > 0) {
            // 需要模糊时才降采样（降采样让 boxBlur 便宜且效果更柔）
            bw = Math.min(MAX_BUCKET_DIM, p.targetW() > 0 ? sizeBucketOf(p.targetW()) : sizeBucketOf(src.getWidth()));
            bh = Math.min(MAX_BUCKET_DIM, p.targetH() > 0 ? sizeBucketOf(p.targetH()) : sizeBucketOf(src.getHeight()));
        } else {
            // 【性能】不模糊时直接用目标像素尺寸（通常与源一致 → 走同尺寸快速拷贝，
            // 且与覆盖层 1:1 blit 对齐，避免二次重采样）。
            bw = p.targetW() > 0 ? p.targetW() : src.getWidth();
            bh = p.targetH() > 0 ? p.targetH() : src.getHeight();
        }
        // 复用缓冲拷贝（同尺寸时是快速 blit；不同尺寸才重采样）
        BufferedImage img = copyIntoReuse(src, bw, bh);
        if (bucket > 0) {
            img = boxBlur(img, bucket * BLUR_BUCKET_STEP_PX, 3);
        }
        if (p.brightnessPercent() != 100) {
            img = applyBrightnessInPlace(img, brightnessLut(p.brightnessPercent()));
        }
        // 【性能量测】管线每帧耗时（含拷贝 + 亮度），每 60 帧报一次
        prepareNanos += System.nanoTime() - t0;
        prepareCount++;
        if (prepareCount % 60 == 0) {
            com.intellij.openapi.diagnostic.Logger.getInstance(AdjustmentPipeline.class)
                    .info("dwallpaper: 管线处理 平均 "
                            + String.format(java.util.Locale.ROOT, "%.1f",
                            prepareNanos / 1_000_000.0 / 60)
                            + "ms/帧（" + bw + "x" + bh + "）");
            prepareNanos = 0;
        }
        return new PreparedFrame(img, frame.frameId(), bucket, bw, bh, seq);
    }

    private long prepareNanos;
    private long prepareCount;

    /** 把 src 拷进复用环的下一块 ARGB 缓冲（尺寸变化才重新分配）。 */
    private synchronized BufferedImage copyIntoReuse(BufferedImage src, int w, int h) {
        if (reuseW != w || reuseH != h || reuseRing[0] == null) {
            for (int i = 0; i < reuseRing.length; i++) {
                reuseRing[i] = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            }
            reuseW = w;
            reuseH = h;
            reuseRingIdx = 0;
        }
        BufferedImage buf = reuseRing[reuseRingIdx];
        reuseRingIdx = (reuseRingIdx + 1) % reuseRing.length;
        java.awt.Graphics2D g = buf.createGraphics();
        try {
            if (src.getWidth() != w || src.getHeight() != h) {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return buf;
    }

    /** 原地套亮度 LUT（不额外分配）。 */
    private static BufferedImage applyBrightnessInPlace(BufferedImage img, int[] lut) {
        try {
            java.awt.image.DataBufferInt db =
                    (java.awt.image.DataBufferInt) img.getRaster().getDataBuffer();
            int[] px = db.getData();
            for (int i = 0; i < px.length; i++) {
                int argb = px[i];
                int a = argb & 0xFF000000;
                int r = lut[(argb >> 16) & 0xFF];
                int g = lut[(argb >> 8) & 0xFF];
                int b = lut[argb & 0xFF];
                px[i] = a | (r << 16) | (g << 8) | b;
            }
            return img;
        } catch (Throwable t) {
            return applyBrightness(img, lut); // 非 INT 栅格回退
        }
    }

    private void makeSnapshot(BufferedImage prepared) {
        int w = prepared.getWidth();
        int h = prepared.getHeight();
        int dim = Math.max(w, h);
        if (dim <= SNAPSHOT_MAX_DIM) {
            snapshot = prepared;
            return;
        }
        double scale = SNAPSHOT_MAX_DIM / (double) dim;
        snapshot = scaleTo(prepared, Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
    }

    /** 雾镜静态快照（最长边 ≤512，§6.4）；动态壁纸播放时雾镜跟随调用方持有的静态引用。 */
    @Nullable
    public BufferedImage snapshot() {
        return snapshot;
    }

    public void shutdown() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    // ---------- 纯函数（包内可见，供单元测试：AC-ADJ-1/4、D5 档位） ----------

    static int blurBucketOf(int blurPx) {
        return Math.round(blurPx / (float) BLUR_BUCKET_STEP_PX);
    }

    static int sizeBucketOf(int v) {
        int b = ((Math.max(1, v) + SIZE_BUCKET_GRID - 1) / SIZE_BUCKET_GRID) * SIZE_BUCKET_GRID;
        return Math.max(SIZE_BUCKET_GRID, b);
    }

    /** 亮度 LUT（40–160%）：v' = clamp(round(v * percent/100))。 */
    static int[] brightnessLut(int percent) {
        int[] lut = new int[256];
        double f = Math.max(MIN_BRIGHTNESS_PERCENT, Math.min(MAX_BRIGHTNESS_PERCENT, percent)) / 100.0;
        for (int i = 0; i < 256; i++) {
            lut[i] = Math.max(0, Math.min(255, (int) Math.round(i * f)));
        }
        return lut;
    }

    public static BufferedImage scaleTo(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    static int[] pixelsOf(BufferedImage img) {
        if (img.getRaster().getDataBuffer() instanceof DataBufferInt db) {
            return db.getData();
        }
        throw new IllegalArgumentException("expected int-based raster (TYPE_INT_ARGB_PRE), got type=" + img.getType());
    }

    /** 3 次可分离盒式模糊（滑动窗口 O(n)），预乘 ARGB 上线性正确（D5：分盒模糊×3 近高斯）。radius=0 原样返回。 */
    public static BufferedImage boxBlur(BufferedImage img, int radius, int passes) {
        if (radius <= 0) {
            return img;
        }
        BufferedImage work = img.getType() == BufferedImage.TYPE_INT_ARGB_PRE
                ? img : scaleTo(img, img.getWidth(), img.getHeight());
        int[] a = pixelsOf(work);
        int[] b = new int[a.length];
        int w = work.getWidth();
        int h = work.getHeight();
        for (int i = 0; i < passes; i++) {
            boxPassH(a, b, w, h, radius);
            boxPassV(b, a, w, h, radius);
        }
        return work;
    }

    private static void boxPassH(int[] src, int[] dst, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sa = 0;
            long sr = 0;
            long sg = 0;
            long sb = 0;
            for (int k = -r; k <= r; k++) {
                int x = Math.min(w - 1, Math.max(0, k));
                int p = src[row + x];
                sa += p >>> 24;
                sr += (p >>> 16) & 0xFF;
                sg += (p >>> 8) & 0xFF;
                sb += p & 0xFF;
            }
            int cnt = 2 * r + 1;
            for (int x = 0; x < w; x++) {
                int oa = (int) (sa / cnt);
                int or = (int) (sr / cnt);
                int og = (int) (sg / cnt);
                int ob = (int) (sb / cnt);
                dst[row + x] = (oa << 24) | (or << 16) | (og << 8) | ob;
                int out = Math.max(0, x - r);
                int inn = Math.min(w - 1, x + r + 1);
                int po = src[row + out];
                int pi = src[row + inn];
                sa += (pi >>> 24) - (po >>> 24);
                sr += ((pi >>> 16) & 0xFF) - ((po >>> 16) & 0xFF);
                sg += ((pi >>> 8) & 0xFF) - ((po >>> 8) & 0xFF);
                sb += (pi & 0xFF) - (po & 0xFF);
            }
        }
    }

    private static void boxPassV(int[] src, int[] dst, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            long sa = 0;
            long sr = 0;
            long sg = 0;
            long sb = 0;
            for (int k = -r; k <= r; k++) {
                int y = Math.min(h - 1, Math.max(0, k));
                int p = src[y * w + x];
                sa += p >>> 24;
                sr += (p >>> 16) & 0xFF;
                sg += (p >>> 8) & 0xFF;
                sb += p & 0xFF;
            }
            int cnt = 2 * r + 1;
            for (int y = 0; y < h; y++) {
                int oa = (int) (sa / cnt);
                int or = (int) (sr / cnt);
                int og = (int) (sg / cnt);
                int ob = (int) (sb / cnt);
                dst[y * w + x] = (oa << 24) | (or << 16) | (og << 8) | ob;
                int out = Math.max(0, y - r);
                int inn = Math.min(h - 1, y + r + 1);
                int po = src[out * w + x];
                int pi = src[inn * w + x];
                sa += (pi >>> 24) - (po >>> 24);
                sr += ((pi >>> 16) & 0xFF) - ((po >>> 16) & 0xFF);
                sg += ((pi >>> 8) & 0xFF) - ((po >>> 8) & 0xFF);
                sb += (pi & 0xFF) - (po & 0xFF);
            }
        }
    }

    /**
     * 亮度 LUT 套用：预乘 RGB 三通道缩放、alpha 不变（=100% 由调用方跳过）。
     * 【契约】同类型（TYPE_INT_ARGB_PRE）输入时为就地修改 —— 调用方必须传入私有缓冲
     * （管线主路径每次 prepare 都先 scaleTo 产生新缓冲，不受影响）。
     */
    public static BufferedImage applyBrightness(BufferedImage img, int[] lut) {
        BufferedImage work = img.getType() == BufferedImage.TYPE_INT_ARGB_PRE
                ? img : scaleTo(img, img.getWidth(), img.getHeight());
        int[] a = pixelsOf(work);
        for (int i = 0; i < a.length; i++) {
            int p = a[i];
            int r = lut[(p >>> 16) & 0xFF];
            int g = lut[(p >>> 8) & 0xFF];
            int b = lut[p & 0xFF];
            a[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        return work;
    }
}
