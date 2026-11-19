package com.dwallpaper.fog;

import com.dwallpaper.core.WallpaperEngine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 雾镜挂载管理（design.md §6.4）：每窗 GlassPane 单个 FogLayer（contains=false 事件穿透）；
 * 只在有启用区时挂载由参数供给方控制（全 0 → 层不绘制）。区边界经 ZoneResolver 在 EDT 解析；
 * 262 实测 ToolWindowManagerListener 迁包不可用 → 1s 轻量定时器重解析跟随（AC-FOG-4 ≤1s，
 * 仅读 bounds，成本受控）；玻璃窗格尺寸变化经 ComponentAdapter 同步层尺寸。
 */
public final class FogOverlayManager {

    private static final Logger LOG = Logger.getInstance(FogOverlayManager.class);

    private final Map<Window, FogLayer> installed = new HashMap<>();
    private final Map<Window, ComponentAdapter> glassAdapters = new HashMap<>(); // F7：按窗记录 adapter（防泄漏）
    /** 【可读性架构】区矩形缓存（供 WallpaperBorder 经 controller 查询面板所属雾区）。 */
    private final Map<Window, java.util.Map<FogZone, java.awt.Rectangle>> cachedZones = new HashMap<>();
    private final Map<Window, Project> zoneProjects = new HashMap<>();
    private Timer refreshTimer;
    private volatile Supplier<Project[]> projectsSupplier = () -> new Project[0];
    private volatile Supplier<Map<FogZone, ZoneParams>> paramsSupplier = Map::of;
    private volatile WallpaperEngine engine;

    public void configure(Supplier<Project[]> projects, WallpaperEngine engineRef,
                          Supplier<Map<FogZone, ZoneParams>> params) {
        this.projectsSupplier = projects;
        this.engine = engineRef;
        this.paramsSupplier = params;
    }

    /** 挂载（EDT）；重复调用幂等。 */
    public void install() {
        runOnEdt(() -> {
            Project[] projects = projectsSupplier.get();
            for (Project p : projects) {
                try {
                    Window w = windowOf(p);
                    if (w != null) {
                        installOn(w, p);
                    }
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: fog install issue on project window", t);
                }
            }
            if (refreshTimer == null && !installed.isEmpty()) {
                refreshTimer = new Timer(1000, e -> refresh());
                refreshTimer.setRepeats(true);
                refreshTimer.start();
                LOG.info("dwallpaper: fog overlay installed on " + installed.size() + " window(s)");
            }
        });
    }

    private void installOn(Window window, Project project) {
        if (!(window instanceof RootPaneContainer rpc) || installed.containsKey(window)) {
            return;
        }
        JRootPane rootPane = rpc.getRootPane();
        Component glass = rootPane.getGlassPane();
        if (!(glass instanceof JComponent glassPane)) {
            return;
        }
        // 【可读性架构 2026-11-19】缓存区矩形（1s 跟随刷新），供 WallpaperBorder
        // 经 controller 查询"面板属于哪个雾区"（雾已移至内容之下）。
        cachedZones.put(window, ZoneResolver.resolve(window, project));
        zoneProjects.put(window, project);
        FogLayer layer = new FogLayer(new FogLayer.Model() {
            @Override
            public Map<FogZone, Rectangle> zoneBounds() {
                Map<FogZone, Rectangle> fresh = ZoneResolver.resolve(window, project);
                if (fresh != null) {
                    cachedZones.put(window, fresh);
                }
                return fresh;
            }

            @Override
            public Map<FogZone, ZoneParams> zoneParams() {
                return paramsSupplier.get();
            }

            @Override
            public BufferedImage snapshot() {
                return engine == null ? null : engine.staticSnapshot();
            }
        });
        layer.setBounds(0, 0, glassPane.getWidth(), glassPane.getHeight());
        ComponentAdapter adapter = new ComponentAdapter() { // F7：引用保存，uninstall 时移除
            @Override
            public void componentResized(ComponentEvent e) {
                layer.setBounds(0, 0, glassPane.getWidth(), glassPane.getHeight());
            }
        };
        glassPane.addComponentListener(adapter);
        glassAdapters.put(window, adapter);
        glassPane.add(layer);
        glassPane.revalidate();
        layer.repaint();
        installed.put(window, layer);
    }

    /** 1s 跟随：层重绘（边界在 paint 时经 model 重解析，≤1s 生效，AC-FOG-4）。 */
    private void refresh() {
        for (Map.Entry<Window, FogLayer> en : installed.entrySet()) {
            en.getValue().repaint();
            Project p = zoneProjects.get(en.getKey());
            if (p != null) {
                try {
                    cachedZones.put(en.getKey(), ZoneResolver.resolve(en.getKey(), p));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 【可读性架构】查询窗口的缓存区矩形（Border 侧判定面板所属雾区）。
     * 无缓存/已卸载 → null。
     */
    public java.util.Map<FogZone, java.awt.Rectangle> cachedZones(Window window) {
        return cachedZones.get(window);
    }

    /** 参数变化即时重绘（三滑杆/预设切换，§8.2 即时生效）。 */
    public void reapplyParams() {
        runOnEdt(() -> {
            for (FogLayer layer : installed.values()) {
                layer.invalidateCache();
                layer.repaint();
            }
            LOG.info("dwallpaper: fog params reapplied (" + (engine != null ? engine.surface().getWidth() : 0) + "px window ref)");
        });
    }

    /** 卸载：严格逆序（移除层、停定时器、还原玻璃窗格内容）。 */
    public void uninstall() {
        runOnEdt(() -> {
            if (refreshTimer != null) {
                refreshTimer.stop();
                refreshTimer = null;
            }
            cachedZones.clear(); // 【可读性架构】区缓存同步清理
            zoneProjects.clear();
            for (Map.Entry<Window, FogLayer> en : installed.entrySet()) {
                try {
                    Window w = en.getKey();
                    if (w instanceof RootPaneContainer rpc && rpc.getRootPane() != null
                            && rpc.getRootPane().getGlassPane() instanceof JComponent glassPane) {
                        glassPane.remove(en.getValue());
                        ComponentAdapter adapter = glassAdapters.remove(w); // F7：同步移除监听器
                        if (adapter != null) {
                            glassPane.removeComponentListener(adapter);
                        }
                        glassPane.revalidate();
                        glassPane.repaint();
                    }
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: fog uninstall issue", t);
                }
            }
            installed.clear();
            glassAdapters.clear();
            LOG.info("dwallpaper: fog overlay uninstalled");
        });
    }

    private static Window windowOf(Project p) {
        try {
            return com.intellij.openapi.wm.WindowManager.getInstance().getFrame(p);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** 供面板显示六区名（LinkedHashMap 保序）。 */
    public static Map<FogZone, String> zoneTitles() {
        Map<FogZone, String> m = new LinkedHashMap<>();
        m.put(FogZone.EDITOR, "编辑器");
        m.put(FogZone.TOOLWINDOW_SIDE, "左右工具窗");
        m.put(FogZone.TOOLWINDOW_BOTTOM, "底部工具窗");
        m.put(FogZone.TOOLBAR, "主工具栏");
        m.put(FogZone.STATUSBAR, "状态栏");
        m.put(FogZone.POPUP, "弹窗(可选)");
        return m;
    }
}
