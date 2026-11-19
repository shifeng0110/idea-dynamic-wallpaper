package com.dwallpaper.core;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.JComponent;
import javax.swing.JWindow;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 【原生分层透明覆盖窗 2026-11-19】用独立的 per-pixel 透明窗口显示壁纸，替代 Swing 玻璃面板。
 *
 * <p>为什么必须换：玻璃面板挂在 RootPane 上，任何一次 repaint 都会让 IDEA **整窗重绘**
 * （面板 + 编辑器 + 工具栏全画一遍），24fps 下 EDT 被压垮——这是"壁纸一动界面就卡"的根因，
 * 也是覆盖层方案的天花板。</p>
 *
 * <p>本实现改为：</p>
 * <ul>
 *   <li>一个 <b>owned JWindow</b>（owner = IDEA 主窗口）：始终浮在 IDEA 之上，跟随移动/缩放，
 *       IDEA 最小化时自动隐藏；不用 alwaysOnTop，所以不会盖住其他程序；</li>
 *   <li>窗口背景设为全透明（per-pixel alpha），壁纸按百分比 alpha 画上去，由**操作系统**
 *       与下方的 IDEA 合成 → IDEA 完全不需要重绘，卡顿根因消失；</li>
 *   <li>用 JNA 设置 `WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW`
 *       实现<b>点击穿透</b>（点到的是下面的 IDEA）；设置失败则整体放弃并回退玻璃面板方案。</li>
 * </ul>
 */
public final class NativeWallpaperOverlay {

    private static final Logger LOG = Logger.getInstance(NativeWallpaperOverlay.class);

    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;

    /** user32 最小接口（只用 core jna，无需 jna-platform）。 */
    public interface User32Api extends com.sun.jna.Library {
        int GetWindowLong(com.sun.jna.Pointer hWnd, int nIndex);

        int SetWindowLong(com.sun.jna.Pointer hWnd, int nIndex, int dwNewLong);
    }

    private static volatile User32Api user32;
    private static volatile boolean user32Failed;

    private JWindow window;
    private Surface surface;
    private Window owner;
    private final Supplier<BufferedImage> frames;
    private final IntSupplier alphaPercent;
    private javax.swing.Timer syncTimer;
    private long paintNanos;
    private long paintCount;

    private NativeWallpaperOverlay(Window owner, Supplier<BufferedImage> frames, IntSupplier alphaPercent) {
        this.owner = owner;
        this.frames = frames;
        this.alphaPercent = alphaPercent;
    }

    /**
     * 尝试挂载；成功返回实例，任何环节不可用返回 null（调用方回退玻璃面板）。
     */
    public static NativeWallpaperOverlay tryAttach(Window owner,
                                                   Supplier<BufferedImage> frames,
                                                   IntSupplier alphaPercent) {
        if (!isSupported()) {
            return null;
        }
        try {
            NativeWallpaperOverlay o = new NativeWallpaperOverlay(owner, frames, alphaPercent);
            o.build();
            return o;
        } catch (Throwable t) {
            LOG.warn("dwallpaper: 原生覆盖窗挂载失败（回退玻璃面板）：" + t);
            return null;
        }
    }

    /** 仅 Windows + 有 JNA 时启用。 */
    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("win")) {
            return false;
        }
        try {
            Class.forName("com.sun.jna.Native");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void build() {
        window = new JWindow(owner);
        // per-pixel 透明背景：需要 undecorated（JWindow 本身就是）+ 支持透明
        window.setBackground(new Color(0, 0, 0, 0));
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setAlwaysOnTop(false);
        surface = new Surface();
        window.setContentPane(surface);
        syncBounds();
        window.setVisible(true);
        // 关键：设置点击穿透；失败则放弃整个方案
        if (!makeClickThrough(window)) {
            dispose();
            throw new IllegalStateException("无法设置点击穿透（WS_EX_TRANSPARENT）");
        }
        // 跟随 owner：移动/缩放
        owner.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                syncBounds();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                syncBounds();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                syncBounds();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                if (window != null) {
                    window.setVisible(false);
                }
            }
        });
        // 兜底轮询：位置/尺寸/可见性变化（窗口拖拽、全屏切换等）
        syncTimer = new javax.swing.Timer(300, e -> syncBounds());
        syncTimer.setRepeats(true);
        syncTimer.start();
        LOG.info("dwallpaper: 已启用原生分层透明覆盖窗（点击穿透，IDEA 零重绘）");
    }

    private void syncBounds() {
        try {
            if (window == null || owner == null) {
                return;
            }
            if (!owner.isShowing()) {
                if (window.isVisible()) {
                    window.setVisible(false);
                }
                return;
            }
            java.awt.Point p = owner.getLocationOnScreen();
            java.awt.Dimension d = owner.getSize();
            if (!window.isVisible()) {
                window.setVisible(true);
                window.setLocation(p);
                window.setSize(d);
                return;
            }
            if (!window.getLocation().equals(p) || !window.getSize().equals(d)) {
                window.setLocation(p);
                window.setSize(d);
            }
        } catch (Throwable ignored) {
            // 同步失败下次重试
        }
    }

    /** 帧更新：只重绘本窗（不影响 IDEA）。 */
    public void requestPaint() {
        Surface s = surface;
        if (s != null) {
            s.repaint();
        }
    }

    public void dispose() {
        try {
            if (syncTimer != null) {
                syncTimer.stop();
                syncTimer = null;
            }
            if (window != null) {
                window.setVisible(false);
                window.dispose();
                window = null;
            }
            surface = null;
        } catch (Throwable ignored) {
            // 卸载失败不阻断
        }
    }

    private final class Surface extends JComponent {
        Surface() {
            setOpaque(false);
        }

        @Override
        public boolean contains(int x, int y) {
            return false; // 双保险：即使系统没穿透，Swing 也不认这块区域
        }

        @Override
        protected void paintComponent(Graphics g) {
            BufferedImage img = frames.get();
            if (img == null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int alpha = Math.max(0, Math.min(100, alphaPercent.getAsInt()));
            if (alpha <= 0) {
                return;
            }
            long t0 = System.nanoTime();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // 先清成全透明，避免残留（本窗独立缓冲，不影响 IDEA）
                g2.setComposite(AlphaComposite.Src);
                g2.setColor(new Color(0, 0, 0, 0));
                g2.fillRect(0, 0, w, h);
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha / 100f));
                if (img.getWidth() == w && img.getHeight() == h) {
                    g2.drawImage(img, 0, 0, null);
                } else {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, w, h, null);
                }
            } finally {
                g2.dispose();
            }
            paintNanos += System.nanoTime() - t0;
            paintCount++;
            if (paintCount % 120 == 0) {
                LOG.info("dwallpaper: 覆盖窗绘制 平均 "
                        + String.format(java.util.Locale.ROOT, "%.1f", paintNanos / 1_000_000.0 / 120)
                        + "ms/帧（" + w + "x" + h + "）");
                paintNanos = 0;
            }
        }
    }

    // ---------------- 点击穿透（JNA / user32） ----------------

    private static boolean makeClickThrough(JWindow w) {
        try {
            long hwnd = hwndOf(w);
            if (hwnd == 0) {
                LOG.warn("dwallpaper: 取不到窗口 HWND，无法设置点击穿透");
                return false;
            }
            User32Api api = user32();
            if (api == null) {
                return false;
            }
            com.sun.jna.Pointer ptr = com.sun.jna.Pointer.createConstant(hwnd);
            int ex = api.GetWindowLong(ptr, GWL_EXSTYLE);
            int wanted = ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
            api.SetWindowLong(ptr, GWL_EXSTYLE, wanted);
            int after = api.GetWindowLong(ptr, GWL_EXSTYLE);
            if ((after & WS_EX_TRANSPARENT) == 0) {
                LOG.warn("dwallpaper: 设置 WS_EX_TRANSPARENT 未生效（exStyle=" + after + "）");
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("dwallpaper: 点击穿透设置异常：" + t);
            return false;
        }
    }

    private static User32Api user32() {
        if (user32 != null) {
            return user32;
        }
        if (user32Failed) {
            return null;
        }
        try {
            User32Api api = com.sun.jna.Native.load("user32", User32Api.class);
            user32 = api;
            return api;
        } catch (Throwable t) {
            user32Failed = true;
            LOG.warn("dwallpaper: 加载 user32 失败：" + t);
            return null;
        }
    }

    /** 通过 AWT peer 反射拿 HWND（IDEA 的 JVM 参数已 --add-opens java.desktop/sun.awt.windows）。 */
    private static long hwndOf(Window w) {
        try {
            java.lang.reflect.Field peerField = Component.class.getDeclaredField("peer");
            peerField.setAccessible(true);
            Object peer = peerField.get(w);
            if (peer == null) {
                return 0;
            }
            java.lang.reflect.Method m = peer.getClass().getMethod("getHWnd");
            m.setAccessible(true);
            Object v = m.invoke(peer);
            return v instanceof Number ? ((Number) v).longValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
