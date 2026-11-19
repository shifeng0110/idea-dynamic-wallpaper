package com.dwallpaper.fog;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 雾镜层（design.md §6.4）：挂载于每窗 GlassPane，事件穿透（contains()=false）；只在有启用区时绘制。
 * 合成语义（§6.1）：区内 mix(底色, 预模糊快照×亮度偏移LUT, fogIntensity/100)，整体 alpha=opacity/100
 * —— 绘制实现：底色填充@op，快照绘制@op×fog/100（视觉等价、无逐像素操作，§6.4 成本受控）。
 * 壁纸底图 = 静态快照（§6.4：动态播放默认跟随静态快照，不逐帧雾化）。
 * 【F10 修复】blur 档预计算全部移交后台单线程（paintComponent 未命中时只画底色层并请求后台计算，
 * 完成后 EDT 重绘——卷积零 EDT）；缓存 LRU 上限 ≤8（键空间不再无界）。
 * fogIntensity=0 或 opacity=0 → 该区层不绘制（清透）。
 */
public final class FogLayer extends JComponent {

    private static final Logger LOG = Logger.getInstance(FogLayer.class);
    private static final int CACHE_LIMIT = 8;

    /** 雾层模型：区边界/参数/快照均由调用方供给（fog 不反向依赖 settings/core 服务内部）。 */
    public interface Model {
        Map<FogZone, Rectangle> zoneBounds();

        Map<FogZone, com.dwallpaper.fog.ZoneParams> zoneParams();

        BufferedImage snapshot();
    }

    private final Model model;
    private final Map<String, BufferedImage> ready = new HashMap<>();      // F10：LRU≤8 就绪缓存
    private final Set<String> inFlight = new HashSet<>();                  // 后台计算去重
    private BufferedImage cachedSource;
    private final ExecutorService precompute = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dwallpaper-fog-precompute");
        t.setDaemon(true);
        return t;
    });
    private int cacheHits;
    private int cacheMisses;

    public FogLayer(Model model) {
        this.model = model;
        setOpaque(false);
        setFocusable(false);
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        // 【可读性架构修正 2026-11-19】GlassPane 蒙层停用：雾画在内容之上会盖住文字/
        // 按钮（用户实测"重影/看不清"）。雾镜语义已移至各面板 WallpaperBorder
        //（内容之下，backdrop-filter 对齐 DSH）。本层保留挂载以备将来"整体磨砂覆盖"
        // 模式，但当前零绘制。
    }

    /** F10：未命中 → 后台预计算（单线程 daemon；EDT 零卷积），完成后 EDT 重绘。 */
    private void requestPrecompute(String key, BufferedImage snapshot, int blurRadiusPx,
                                   int brightnessOffsetPercent) {
        if (snapshot == null || !inFlight.add(key)) {
            return; // 已在计算
        }
        cacheMisses++;
        precompute.execute(() -> {
            try {
                // applyBrightness 就地修改（契约）→ 先 scaleTo 复制出新缓冲，避免污染共享快照
                BufferedImage src = com.dwallpaper.core.AdjustmentPipeline.scaleTo(
                        snapshot, snapshot.getWidth(), snapshot.getHeight());
                int offBucket = Math.round(brightnessOffsetPercent / 5f);
                if (offBucket != 0) {
                    int[] lut = new int[256];
                    double f = 1.0 + offBucket * 5 / 100.0;
                    for (int i = 0; i < 256; i++) {
                        lut[i] = Math.max(0, Math.min(255, (int) Math.round(i * f)));
                    }
                    src = com.dwallpaper.core.AdjustmentPipeline.applyBrightness(src, lut);
                }
                int bucket = Math.max(0, Math.min(6, blurRadiusPx / 5));
                BufferedImage out = com.dwallpaper.core.AdjustmentPipeline.boxBlur(src, bucket * 5, 3);
                SwingUtilities.invokeLater(() -> {
                    inFlight.remove(key);
                    if (out != null) {
                        ready.put(key, out);
                        evictOverLimit();
                        repaint(); // 就绪 → 下一帧绘制雾面
                    }
                });
            } catch (Throwable t) {
                inFlight.remove(key);
                LOG.warn("dwallpaper: fog precompute failed for " + key, t);
            }
        });
    }

    /** F10：LRU 淘汰（上限 8 项，键空间有界）。 */
    private void evictOverLimit() {
        if (ready.size() <= CACHE_LIMIT) {
            return;
        }
        List<String> order = new ArrayList<>(ready.keySet());
        Iterator<String> it = order.iterator();
        while (ready.size() > CACHE_LIMIT && it.hasNext()) {
            ready.remove(it.next());
        }
    }

    private static String cacheKey(int blurRadiusPx, int brightnessOffsetPercent) {
        int bucket = Math.max(0, Math.min(6, blurRadiusPx / 5));
        int offBucket = Math.round(brightnessOffsetPercent / 5f);
        return bucket + "_" + offBucket;
    }

    /** 缓存统计（供管理器日志）。 */
    public String cacheStats() {
        return "fogBlurCache hits=" + cacheHits + " misses=" + cacheMisses + " ready=" + ready.size();
    }

    /** 参数变化：就绪缓存保留（键含参数），仅重绘（后台按需补算）。 */
    void invalidateCache() {
        repaint();
    }

    private static Color baseColor() {
        Color c = javax.swing.UIManager.getColor("Panel.background");
        return c != null ? c : new Color(0x1E1F22);
    }
}
