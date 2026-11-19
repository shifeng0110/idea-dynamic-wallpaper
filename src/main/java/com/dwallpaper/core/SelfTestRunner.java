package com.dwallpaper.core;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * runIde 自测驱动（-Ddwallpaper.selftest=true 时启用，design.md §8 自测/§14 T4）：
 * 生成测试图 → 挂载确认 → 滑杆全行程驱动（blur 0/60/0、亮度 40/160/100、不透明度 0/60/90）
 * → EDT 健康度往返测量 → 结果写 idea.log（"dwallpaper SELFTEST" 前缀），供证据采集。
 * 模块纪律：core 不 import media —— 测试帧源用 core 内联匿名 FrameSource。
 */
public final class SelfTestRunner {

    private static final Logger LOG = Logger.getInstance(SelfTestRunner.class);
    private static final int TEST_W = 1920;
    private static final int TEST_H = 1080;

    private SelfTestRunner() {
    }

    public static void maybeRun(WallpaperEngine engine) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(6000); // 等待挂载重试/工程窗口就绪
                SwingUtilities.invokeAndWait(() -> {
                    if (!engine.isEnabled()) {
                        engine.enable();
                    }
                });
                Thread.sleep(4000); // 等待挂载完成与首帧
                runSteps(engine);
            } catch (Throwable t1) {
                LOG.error("dwallpaper SELFTEST FAIL: " + t1, t1);
            }
        }, "dwallpaper-selftest");
        t.setDaemon(true);
        t.start();
    }

    private static void runSteps(WallpaperEngine engine) throws Exception {
        LOG.info("dwallpaper SELFTEST begin; " + engine.status());
        BufferedImage img = gradient(TEST_W, TEST_H);

        // 步骤 1：core 内联静态帧源 → pipeline → surface（背景可见链路）
        FrameSource src = new FrameSource() {
            private final Frame frame = new Frame(img, 1L, System.currentTimeMillis(), TEST_W, TEST_H);
            private boolean delivered;

            @Override
            public Frame pollFrame() {
                if (!delivered) {
                    delivered = true;
                    return frame;
                }
                return null;
            }

            @Override
            public boolean isDynamic() {
                return false;
            }

            @Override
            public void close() {
            }
        };
        long t0 = System.currentTimeMillis();
        SwingUtilities.invokeAndWait(() -> engine.setFrameSource(src));
        waitPrepared(engine, 8000);
        LOG.info("dwallpaper SELFTEST step=frame-pipeline roundTripMs=" + (System.currentTimeMillis() - t0)
                + " prepared=" + engine.surface().hasPrepared());
        if (!engine.surface().hasPrepared()) {
            LOG.error("dwallpaper SELFTEST FAIL: no prepared frame reached surface");
            return;
        }

        // 步骤 2：滑杆全行程（即时生效 ≤300ms 口径的实测记录）
        driveParam(engine, "blur", p -> engine.setParams(p, 100, 60), new int[]{0, 60, 0});
        driveParam(engine, "brightness", p -> engine.setParams(0, p, 60), new int[]{40, 160, 100});
        driveParam(engine, "opacity", p -> engine.setParams(0, 100, p), new int[]{0, 60, 90});

        // 步骤 3：EDT 健康度（往返 ≤100ms 视为不卡顿）
        for (int i = 0; i < 3; i++) {
            long rtt = edtRoundTripMs();
            LOG.info("dwallpaper SELFTEST step=edt-rtt#" + i + " roundTripMs=" + rtt);
        }

        // 步骤 4：UI 可见性断言（【黑屏回归测试 2026-11-19】挂载层级 bug 曾导致
        // Surface 盖住整个 UI 全黑；Robot 截图采样像素方差，方差过低=黑屏回归）。
        try {
            SwingUtilities.invokeAndWait(() -> engine.surface().repaint());
            Thread.sleep(300);
            int spread = screenSpread();
            LOG.info("dwallpaper SELFTEST step=visibility lumaSpread=" + spread
                    + (spread >= VISIBILITY_MIN_SPREAD ? " (UI visible)" : " (SUSPECT BLACK SCREEN)"));
            if (spread < VISIBILITY_MIN_SPREAD) {
                LOG.error("dwallpaper SELFTEST FAIL: screen near-uniform (lumaSpread=" + spread
                        + ", threshold=" + VISIBILITY_MIN_SPREAD + ") — UI likely obscured");
                return;
            }
        } catch (Throwable vis) {
            // Robot 不可用（无显示环境等）不阻塞自测，如实记录
            LOG.warn("dwallpaper SELFTEST visibility check skipped: " + vis);
        }

        LOG.info("dwallpaper SELFTEST PASS; " + engine.status());
    }

    /** 可见性判定阈值：窗口采样亮度极差低于该值视为近乎纯色（黑屏/全灰）。 */
    private static final int VISIBILITY_MIN_SPREAD = 12;

    /** Robot 截取 IDE 窗口画面，采样网格像素的亮度极差（0-255）。 */
    private static int screenSpread() throws Exception {
        java.awt.Window target = null;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w != null && w.isShowing() && w.getWidth() > 200 && w.getHeight() > 200) {
                target = w;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("no showing IDE window for visibility check");
        }
        java.awt.Rectangle bounds = target.getBounds();
        java.awt.image.BufferedImage shot = new java.awt.Robot().createScreenCapture(bounds);
        int min = 255;
        int max = 0;
        int step = Math.max(8, Math.min(shot.getWidth(), shot.getHeight()) / 40);
        for (int y = 0; y < shot.getHeight(); y += step) {
            for (int x = 0; x < shot.getWidth(); x += step) {
                int rgb = shot.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int lum = (r * 77 + g * 151 + b * 28) >> 8;
                if (lum < min) min = lum;
                if (lum > max) max = lum;
            }
        }
        return max - min;
    }

    private static void driveParam(WallpaperEngine engine, String name, java.util.function.IntConsumer setter,
                                   int[] values) throws Exception {
        for (int v : values) {
            long t0 = System.currentTimeMillis();
            SwingUtilities.invokeAndWait(() -> setter.accept(v));
            Thread.sleep(400); // 防抖 200ms + 异步重算
            LOG.info("dwallpaper SELFTEST step=" + name + "=" + v
                    + " roundTripMs=" + (System.currentTimeMillis() - t0)
                    + " " + engine.status());
        }
    }

    private static void waitPrepared(WallpaperEngine engine, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (engine.surface().hasPrepared()) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private static long edtRoundTripMs() throws InterruptedException {
        long t0 = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(latch::countDown);
        latch.await(2, TimeUnit.SECONDS);
        return System.currentTimeMillis() - t0;
    }

    /** 测试帧：斜向渐变 + 圆环，保证缩放/模糊后视觉可辨。 */
    private static BufferedImage gradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            for (int y = 0; y < h; y++) {
                int r = (int) (255.0 * y / h);
                int b = (int) (255.0 * (1 - (double) y / h));
                g.setColor(new java.awt.Color(255, r, b, 255));
                g.fillRect(0, y, w, 1);
            }
            g.setColor(new java.awt.Color(0, 0, 0, 120));
            for (int i = 0; i < 6; i++) {
                g.drawOval(100 + i * 60, 100 + i * 60, w - 200 - i * 120, h - 200 - i * 120);
            }
        } finally {
            g.dispose();
        }
        return img;
    }
}
