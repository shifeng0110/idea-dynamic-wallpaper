package com.dwallpaper.core;

import com.intellij.ui.JBColor;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * 自绘非透明底图组件（design.md §4.2）：
 *  1) opaque=false、focusable=false；2) contains()=false 事件穿透；3) paintComponent 三步：
 *     ① 底色填充（LaF Panel 色，保证不透明度语义：0=隐藏壁纸只剩底色，90=仍混 ≥10% 底色）
 *     ② AlphaComposite 按不透明度绘制 prepared 帧（EDT 内仅 drawImage，§5.2）
 *     ③ 雾镜层不在本组件（GlassPane 独立，§6.4）。
 * 【F12 修复】VolatileImage 尺寸匹配时跨帧复用（仅重 blit 内容），尺寸变化才重建并 dispose 旧实例；
 * {@link #releaseGraphics()} 供卸载/退出链释放 VRAM。默认不透明度 = 定稿口径 60（§5.1，F11）。
 */
public class WallpaperSurface extends JComponent {

    /** 不透明度默认（§5.1/D6：0–90）。【2026-11-19】默认 60 实测"几乎看不见"
     * （暗视频 luma≈47 × 60% × 编辑器 60% 底色 ≈ 33，与主题背景 30 无法区分）→ 提到 90。 */
    public static final int DEFAULT_OPACITY_PERCENT = 90;

    private volatile BufferedImage prepared;      // TYPE_INT_ARGB_PRE，已调节帧
    private volatile long preparedSeq;            // prepared 变更序号
    private volatile int opacityPercent = DEFAULT_OPACITY_PERCENT; // F11：定稿默认 60
    private volatile int sourceWidth;
    private volatile int sourceHeight;

    private VolatileImage vimg;
    private long vimgSeq = -1;
    private int vimgW = -1;
    private int vimgH = -1;

    public WallpaperSurface() {
        // 【合成模型定稿 2026-11-19】Surface 必须 opaque=true：
        // 它是整个窗口唯一的「清屏层」——每次重绘都从本组件的不透明底色开始，
        // RepaintManager 把本组件当作脏区的最上层不透明祖先，先画底色再画上层
        // 半透明子级。opaque=false 时缓冲区不清屏，半透明面板/边框逐帧叠加
        // 才是「图标渐进式发白、面板重影」的真正根因（实证）。
        setOpaque(true);
        setFocusable(false);
        // 【壁纸不显示修复 2026-11-19】挂载早于首次布局时 Surface 尺寸为 0×0，
        // 引擎据此推 pipeline/renderSize 会得到 0 → 壁纸帧永不产出。组件自身
        // 跟随布局变化通知引擎重推尺寸（resize listener 之外的第二保险）。
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                java.util.function.BiConsumer<Integer, Integer> l = sizeListener;
                if (l != null && getWidth() > 0 && getHeight() > 0) {
                    l.accept(getWidth(), getHeight());
                }
            }
        });
    }

    /** 尺寸变化回调（引擎设置；EDT 调用）。参数=(width, height)，仅非零时回调。 */
    public void setSizeListener(java.util.function.BiConsumer<Integer, Integer> listener) {
        this.sizeListener = listener;
    }

    private volatile java.util.function.BiConsumer<Integer, Integer> sizeListener;

    /** 事件穿透：背景层不接收任何鼠标/键盘事件（design.md §4.2/§9.5）。 */
    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // ① 不透明底色（无条件）：本组件是清屏层，必须先铺不透明底再叠壁纸，
            //    否则上层半透明子级会与残留像素逐帧叠加（渐进发白/重影）。
            //    Src 合成（非 SrcOver）确保覆盖旧内容而不是叠加。
            g2.setComposite(AlphaComposite.Src);
            g2.setColor(baseColor());
            g2.fillRect(0, 0, w, h);
            // ② 无帧/不透明度 0：只留底色（视觉=原生主题，不再黑屏、不拦截事件）
            BufferedImage img = prepared;
            if (coverageProbe) {
                return; // 探针期间只显示纯色，不叠壁纸
            }
            if (img == null || opacityPercent <= 0) {
                return;
            }
            VolatileImage v = volatileFor(img);
            g2.setComposite(AlphaComposite.SrcOver.derive(opacityPercent / 100f));
            if (v != null) {
                g2.drawImage(v, 0, 0, w, h, null);
            } else {
                g2.drawImage(img, 0, 0, w, h, null);
            }
            // ③ 雾镜层独立挂 GlassPane，此处不画
        } finally {
            g2.dispose();
        }
    }

    /**
     * 与当前 prepared 匹配的 VolatileImage（F12：尺寸匹配复用、仅内容重 blit；
     * 尺寸变化才重建并 dispose 旧实例；失败回退直接 drawImage(BufferedImage)）。
     */
    private VolatileImage volatileFor(BufferedImage img) {
        long seq = preparedSeq;
        int iw = img.getWidth();
        int ih = img.getHeight();
        try {
            java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
            if (vimg != null && vimgW == iw && vimgH == ih
                    && vimg.validate(gc) != VolatileImage.IMAGE_INCOMPATIBLE) {
                blitTo(vimg, img, gc); // F12：尺寸匹配复用，仅刷新内容
                vimgSeq = seq;
                return vimg;
            }
            if (vimg != null && (vimgW != iw || vimgH != ih)) {
                disposeVolatile(); // F12：尺寸变化才重建，旧实例显式释放
            }
            VolatileImage nv = gc.createCompatibleVolatileImage(iw, ih);
            blitTo(nv, img, gc);
            vimg = nv;
            vimgSeq = seq;
            vimgW = iw;
            vimgH = ih;
            return vimg;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void blitTo(VolatileImage target, BufferedImage img, java.awt.GraphicsConfiguration gc) {
        if (target.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new IllegalStateException("volatile image incompatible");
        }
        Graphics2D vg = target.createGraphics();
        try {
            vg.drawImage(img, 0, 0, null);
        } finally {
            vg.dispose();
        }
    }

    /** F12：释放 VRAM（卸载/IDE 退出链调用）；调用后 paint 回退 BufferedImage 直绘。 */
    public void releaseGraphics() {
        disposeVolatile();
        prepared = null;
        preparedSeq++;
    }

    private void disposeVolatile() {
        if (vimg != null) {
            try {
                vimg.flush();
            } catch (Throwable ignored) {
            }
            try {
                vimg = null;
            } finally {
                vimgSeq = -1;
                vimgW = -1;
                vimgH = -1;
            }
        }
    }

    /**
     * 【覆盖范围探针 2026-11-19】壁纸只在两侧细条可见 → 需要一次性区分
     * 「Surface 被平台不透明组件盖住」与「帧内容太暗/无内容」。
     * 置位期间 paintComponent 直接铺一块高饱和纯色（不叠壁纸），
     * 肉眼即可看出壁纸画布究竟能透出多大面积。
     */
    private static volatile boolean coverageProbe;
    private static volatile Color coverageColor = new Color(0x00, 0xFF, 0x00);

    public static void setCoverageProbe(boolean on, Color color) {
        coverageProbe = on;
        if (color != null) {
            coverageColor = color;
        }
    }

    /** 不透明底色（探针期间用纯色）。 */
    private static Color baseColor() {
        if (coverageProbe) {
            return coverageColor;
        }
        Color c = UIManager.getColor("Panel.background");
        if (c != null) {
            return c;
        }
        return new JBColor(Color.WHITE, new Color(0x1E1F22));
    }

    /** 【性能 2026-11-19】覆盖层模式下 Surface 被实心面板完全盖住，重绘它是纯浪费
     *  （每帧一次整窗重绘）。关闭后 setPrepared 只存帧不触发重绘。 */
    private volatile boolean repaintEnabled = true;

    public void setRepaintEnabled(boolean enabled) {
        this.repaintEnabled = enabled;
    }

    /** 帧就绪（由 AdjustmentPipeline 回调，EDT）；全帧变化 → repaint()（design.md §4.2）。 */
    public void setPrepared(BufferedImage image, int sourceW, int sourceH) {
        this.prepared = image;
        this.sourceWidth = sourceW;
        this.sourceHeight = sourceH;
        this.preparedSeq++;
        preparedCount++;
        if (preparedCount == 1 || preparedCount % 120 == 0) {
            com.intellij.openapi.diagnostic.Logger.getInstance(WallpaperSurface.class)
                    .info("dwallpaper: prepared 帧到达 Surface：count=" + preparedCount
                            + " size=" + (image == null ? "null" : image.getWidth() + "x" + image.getHeight()));
        }
        if (repaintEnabled) {
            repaint();
        }
        Runnable l = frameListener; // 【Border 方案 2026-11-19】通知 Border 面板重绘（引擎设置）
        if (l != null) {
            l.run();
        }
    }

    private volatile long preparedCount;

    /** 当前已调节帧（【Border 方案】WallpaperBorder 画壁纸用；无壁纸时 null）。 */
    public BufferedImage preparedFrame() {
        return prepared;
    }

    /** 帧更新回调（引擎设置）：Border 面板重绘等扇出。 */
    public void setFrameListener(Runnable listener) {
        this.frameListener = listener;
    }

    private volatile Runnable frameListener;

    public boolean hasPrepared() {
        return prepared != null;
    }

    public int getOpacityPercent() {
        return opacityPercent;
    }

    /** 不透明度 0–90（§5.1 定稿口径）；变化触发整帧重绘。 */
    public void setOpacityPercent(int percent) {
        int v = Math.max(0, Math.min(AdjustmentPipeline.MAX_OPACITY_PERCENT, percent));
        if (v != opacityPercent) {
            opacityPercent = v;
            repaint();
        }
    }

    /** 脏区重绘入口：参数/帧变化仅 repaint(rect)，全窗变化才 repaint()（design.md §4.2）。 */
    public void repaintDirty(Rectangle rect) {
        if (rect == null) {
            repaint();
        } else {
            repaint(rect.x, rect.y, rect.width, rect.height);
        }
    }
}
