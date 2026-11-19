package com.dwallpaper.core;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.image.BufferedImage;

/**
 * 壁纸 Border（【Border 方案 + 可读性架构 + 方案 E 2026-11-19】）：
 * paintBorder 在 paintComponent 之后、paintChildren 之前绘制——面板"背景位置"画：
 * ① 雾区：模糊+调亮度的壁纸帧（雾化帧，文字可读）；
 * ② 非雾区：清晰壁纸帧（GIF/静态管线）；
 * ③ 视频/网页直显模式（无 BufferedImage 帧）：主题底色 scrim（可读性底，下层 OSR
 *    直显组件的画面透过残余透明度显示）。
 * 坐标：按 anchor（主 Surface=全窗）1:1 取帧区域（零缩放开销、跨面板连续）。
 * 无壁纸帧且无 scrim 时不画任何像素。
 */
public final class WallpaperBorder implements Border {

    private final WallpaperPaint paint;
    private final JComponent anchor; // 主 Surface：帧按其尺寸预缩放
    private final java.util.function.Supplier<BufferedImage> directFrame; // anchor 直取帧（消除断链）
    private static volatile long drawCount; // 【帧流动遥测】全局 border 绘制计数
    private static volatile long nullFrameCount; // 【断链遥测】

    public WallpaperBorder(WallpaperPaint paint, JComponent anchor,
                           java.util.function.Supplier<BufferedImage> directFrame) {
        this.paint = paint;
        this.anchor = anchor;
        this.directFrame = directFrame;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        try {
            paintBorderInner(c, g, x, y, w, h);
        } catch (Throwable t) {
            // 【断链定位 2026-11-19】paint 异常被 Swing 吞掉会让"壁纸不显示但 UI 正常"
            // 极难排查——显式记录（每 50 次采样）。
            paintErrorCount++;
            if (paintErrorCount == 1 || paintErrorCount % 50 == 0) {
                com.intellij.openapi.diagnostic.Logger.getInstance(WallpaperBorder.class)
                        .warn("dwallpaper: Border paint 异常 count=" + paintErrorCount + ": " + t);
            }
        }
    }

    private static volatile long paintErrorCount;

    private void paintBorderInner(Component c, Graphics g, int x, int y, int w, int h) {
        if (!(c instanceof JComponent jc) || w <= 0 || h <= 0) {
            return;
        }
        WallpaperPaint.FogZoneParamsLike zone = paint.zoneFor(jc);
        BufferedImage img;
        int alpha;
        if (zone != null && zone.fogIntensity() > 0 && zone.opacityPercent() > 0) {
            BufferedImage frosted = paint.frostedFrame(zone.blurRadiusPx(), zone.brightnessOffsetPercent());
            if (frosted == null) {
                frosted = paint.sharpFrame(); // 雾化帧未就绪 → 清晰帧兜底（视频直显模式为 null → scrim）
            }
            img = frosted;
            alpha = (int) Math.round(paint.opacityPercent() / 100.0
                    * zone.fogIntensity() / 100.0 * zone.opacityPercent() / 100.0 * 100);
        } else {
            // 【断链修复 2026-11-19】优先直接取 anchor surface 的帧（同实例，无间接层）；
            // 直取为 null 时回退 paint.sharpFrame()。
            img = directFrame != null ? directFrame.get() : null;
            if (img == null) {
                img = paint.sharpFrame();
            }
            alpha = paint.opacityPercent();
        }
        if (img == null) {
            // 【遥测】sharpFrame 断链诊断（每 200 次采样）
            nullFrameCount++;
            if (nullFrameCount == 1 || nullFrameCount % 200 == 0) {
                com.intellij.openapi.diagnostic.Logger.getInstance(WallpaperBorder.class)
                        .warn("dwallpaper: Border 帧断链：directFrame=" + (directFrame != null)
                                + " sharpFrame=null count=" + nullFrameCount);
            }
        }
        java.awt.Color scrim = img == null ? paint.scrimColor() : null;
        int scrimAlpha = img == null ? paint.scrimAlphaPercent() : 0;
        if (scrim == null && (img == null || alpha <= 0)) {
            return;
        }
        drawCount++;
        if (drawCount == 1 || drawCount % 600 == 0) {
            com.intellij.openapi.diagnostic.Logger.getInstance(WallpaperBorder.class)
                    .info("dwallpaper: Border 绘制壁纸：count=" + drawCount
                            + " mode=" + (scrim != null ? "scrim(直显)" : (zone != null ? "fogged" : "sharp"))
                            + " on=" + jc.getClass().getSimpleName());
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            // 【渐进式发白根因修复 2026-11-19】玻璃面板必须先画不透明底色再叠半透明
            // 壁纸——透明面板 + 半透明 Border 在 Swing 双缓冲下会与残留像素逐帧叠加
            //（用户实测：图标渐进式发白、侧栏重影）。底色用 LaF Panel 色（与主题一致）。
            java.awt.Color base = baseColor();
            g2.setComposite(AlphaComposite.SrcOver.derive(1f));
            g2.setColor(base);
            g2.fillRect(0, 0, w, h);
            if (scrim != null) {
                // 【方案 E】视频/网页直显模式：不透明底 + 轻 scrim 层（12%）。
                g2.setComposite(AlphaComposite.SrcOver.derive(scrimAlpha / 100f));
                g2.setColor(scrim);
                g2.fillRect(0, 0, w, h);
                return;
            }
            alpha = Math.min(alpha, 100);
            // 【位置修复 2026-11-19】帧尺寸与面板尺寸可能不一致（多屏/缩放）——
            // 按比例缩放绘制整帧到面板区域（覆盖式）。
            int iw = img.getWidth();
            int ih = img.getHeight();
            g2.setComposite(AlphaComposite.SrcOver.derive(alpha / 100f));
            g2.drawImage(img, 0, 0, w, h, 0, 0, iw, ih, null);
        } finally {
            g2.dispose();
        }
    }

    /** LaF Panel 底色（与 WallpaperSurface.baseColor 同源）。 */
    private static java.awt.Color baseColor() {
        java.awt.Color c = javax.swing.UIManager.getColor("Panel.background");
        return c != null ? c : new java.awt.Color(0x1E1F22);
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(0, 0, 0, 0); // 零占位：不影响布局
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }
}
