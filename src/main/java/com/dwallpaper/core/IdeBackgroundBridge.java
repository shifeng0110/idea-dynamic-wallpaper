package com.dwallpaper.core;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.Painter;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.impl.IdeBackgroundUtil;

import javax.swing.JComponent;
import javax.swing.RootPaneContainer;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 【IDEA 原生背景通道 2026-11-19】把壁纸帧接进 IntelliJ 自带的「背景图像」绘制链。
 *
 * <p>为什么必须走这条路：实证表明 IDEA 2026.2 的每个区域都由平台组件自绘实心底色，
 * 既不认 {@code setOpaque(false)}，也不读 {@code getBackground()}（把全树底色改半透明、
 * 甚至把底层画布刷成纯绿探针，绿色也只出现在工具栏缝隙）。而用户实测「外观 → 背景图像」
 * 选一张图时，图片能正常出现在文件树和代码区 —— 说明平台内部有一条**替换实心底色填充**
 * 的绘制通道：{@code IdeBackgroundUtil} 用 {@code MyGraphics} 包装 Graphics，把组件的
 * 背景填充改画成背景图。</p>
 *
 * <p>本类通过公开 API {@code IdeBackgroundUtil.createTemporaryBackgroundTransform(
 * JComponent, Painter, Disposable)} 注册一个<b>动态</b> Painter：每次平台绘制背景时都读
 * 当前最新帧，因此视频/动图壁纸也能走这条通道，且天然具备"在所有实心底色处显示"的能力。</p>
 */
public final class IdeBackgroundBridge {

    private IdeBackgroundBridge() {
    }

    /**
     * 【文件属性通道 2026-11-19】把一帧写成 PNG，并把它设为 IDEA 自己的「背景图像」
     * （`idea.background.frame` 属性）。这是用户实测**确实能在文件树和代码区显示**
     * 的通道：IDEA 的 `IdeGlassPaneImpl` 背景绘制器会读该属性并替组件底色绘制。
     * 动态性来自"反复改写文件 + 复位绘制器 + 重绘窗口"。
     *
     * @param png   已写好的 PNG 文件
     * @param alpha 0..1 的透明度
     * @return 是否成功写入属性
     */
    public static boolean setFrameBackgroundFile(java.nio.file.Path png, int alphaPercent) {
        try {
            // 【格式实证 2026-11-19】IDEA 的背景图规格用**逗号**分隔：
            //   路径,alpha(0-100 整数),Fill,Anchor[,flip]
            // 之前按分号拼接会被整体解析失败而静默丢弃（属性写了等于没写）。
            int alpha = Math.max(1, Math.min(100, alphaPercent));
            String spec = png.toAbsolutePath() + "," + alpha + ",SCALE,CENTER";
            com.intellij.ide.util.PropertiesComponent pc =
                    com.intellij.ide.util.PropertiesComponent.getInstance();
            pc.setValue(IdeBackgroundUtil.FRAME_PROP, spec);
            IdeBackgroundUtil.resetBackgroundImagePainters();
            IdeBackgroundUtil.repaintAllWindows();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 保存并把当前 FRAME 背景设置清空（安装前调用）。 */
    public static String takeOverFrameProperty() {
        try {
            com.intellij.ide.util.PropertiesComponent pc =
                    com.intellij.ide.util.PropertiesComponent.getInstance();
            String prev = pc.getValue(IdeBackgroundUtil.FRAME_PROP);
            return prev == null ? "" : prev;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 还原安装前的 FRAME 背景设置（卸载时调用）。 */
    public static void restoreFrameProperty(String previous) {
        try {
            com.intellij.ide.util.PropertiesComponent pc =
                    com.intellij.ide.util.PropertiesComponent.getInstance();
            if (previous == null || previous.isEmpty()) {
                pc.unsetValue(IdeBackgroundUtil.FRAME_PROP);
            } else {
                pc.setValue(IdeBackgroundUtil.FRAME_PROP, previous);
            }
            IdeBackgroundUtil.resetBackgroundImagePainters();
            IdeBackgroundUtil.repaintAllWindows();
        } catch (Throwable ignored) {
            // 还原失败不影响主流程
        }
    }

    /** 把一帧编码成 PNG（等比缩到 maxWidth 以内，控制编码与解码开销）。 */
    public static boolean writeFramePng(BufferedImage img, java.nio.file.Path target, int maxWidth) {
        try {
            if (img == null) {
                return false;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) {
                return false;
            }
            if (w > maxWidth) {
                h = Math.max(1, (int) Math.round(h * (double) maxWidth / w));
                w = maxWidth;
            }
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            java.nio.file.Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(scaled, "png", target.toFile());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把整窗背景交给平台绘制（挂在 contentPane 上，覆盖文件树/编辑器等全部实心底色）。 */
    public static Disposable attachToWindow(Window window,
                                            Supplier<BufferedImage> frames,
                                            IntSupplier alphaPercent) {
        try {
            if (!(window instanceof RootPaneContainer rpc) || rpc.getRootPane() == null) {
                return null;
            }
            Component cp = rpc.getContentPane();
            if (!(cp instanceof JComponent content)) {
                return null;
            }
            return attach(content, frames, alphaPercent);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把某个组件的背景交给平台绘制（编辑器内容面用）。 */
    public static Disposable attach(JComponent target,
                                    Supplier<BufferedImage> frames,
                                    IntSupplier alphaPercent) {
        if (target == null || frames == null) {
            return null;
        }
        try {
            Disposable d = Disposer.newDisposable("dwallpaper-ide-background");
            IdeBackgroundUtil.createTemporaryBackgroundTransform(
                    target, new WallpaperPainter(frames, alphaPercent), d);
            return d;
        } catch (Throwable t) {
            try {
                com.intellij.openapi.diagnostic.Logger.getInstance(IdeBackgroundBridge.class)
                        .warn("dwallpaper: 原生背景通道挂载失败（降级为覆盖层）：" + t);
            } catch (Throwable ignored) {
                // 日志不可用时静默
            }
            return null;
        }
    }

    /** 帧序号变化时返回 true，避免平台无谓地连续重绘。 */
    private static volatile long frameSeq;

    public static void onNewFrame(long seq) {
        frameSeq = seq;
    }

    private static final class WallpaperPainter implements Painter {
        private final Supplier<BufferedImage> frames;
        private final IntSupplier alphaPercent;
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private long lastSeq = -1;

        WallpaperPainter(Supplier<BufferedImage> frames, IntSupplier alphaPercent) {
            this.frames = frames;
            this.alphaPercent = alphaPercent;
        }

        @Override
        public boolean needsRepaint() {
            return frameSeq != lastSeq;
        }

        @Override
        public void paint(Component c, Graphics2D g) {
            draw(c, g);
        }

        @Override
        public void paint(Component host, Component c, Graphics2D g) {
            draw(c, g);
        }

        private void draw(Component c, Graphics2D g) {
            try {
                BufferedImage img = frames.get();
                if (img == null || c == null || g == null) {
                    return;
                }
                int w = c.getWidth();
                int h = c.getHeight();
                if (w <= 0 || h <= 0) {
                    return;
                }
                int alpha = Math.max(0, Math.min(100, alphaPercent.getAsInt()));
                if (alpha <= 0) {
                    return;
                }
                lastSeq = frameSeq;
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setComposite(AlphaComposite.SrcOver.derive(alpha / 100f));
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, w, h, null);
                } finally {
                    g2.dispose();
                }
            } catch (Throwable ignored) {
                // 单次绘制失败不影响其他区域
            }
        }

        @Override
        public void addListener(Listener listener) {
            if (listener != null) {
                listeners.add(listener);
            }
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }
    }
}
