package com.dwallpaper.core;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSplitPane;
import javax.swing.RootPaneContainer;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S1 根窗格底层挂载（design.md §4.1 主路线）：
 * 取根窗格 layeredPane → 沿祖先链把「普通容器面板」（JPanel/JBPanel/JSplitPane）置 opaque=false
 * （逐个记录原值）→ 在最低层插入按窗实例化的 WallpaperSurface（fills 全窗、contains()=false
 * 不拦截事件，F18：每窗独立 surface，经 ctx.registerSurface 登记进引擎扇出）。
 * 编辑器文字区由 S2 透明化后透过本底图可见（AC-CP-1）；关闭开关严格逆序还原（AC-CP-3）。
 * 纪律：只用通用 Swing API（RootPaneContainer/JLayeredPane），不 import 平台 impl 组件类。
 */
public final class RootPaneMountStrategy implements MountStrategy {

    private static final Logger LOG = Logger.getInstance(RootPaneMountStrategy.class);
    private static final String ORIG_BG_KEY = "dwallpaper.origBg"; // 原底色（玻璃化还原用）
    private static final String ORIG_OPAQUE_KEY = "dwallpaper.origOpaque"; // 历史残留键：仅用于卸载清理

    /** EDT 专用安装状态（严格逆序还原）。 */
    private static final class State {
        final JLayeredPane layeredPane;
        final WallpaperSurface surface;
        final List<JComponent> flipped;
        final ComponentListener resize;
        javax.swing.Timer sizeSync; // 【尺寸同步 2026-11-19】把 0×0 的 Surface 跟住窗口

        State(JLayeredPane layeredPane, WallpaperSurface surface, List<JComponent> flipped, ComponentListener resize) {
            this.layeredPane = layeredPane;
            this.surface = surface;
            this.flipped = flipped;
            this.resize = resize;
        }
    }

    private final Map<Window, State> installed = new HashMap<>();
    private boolean sizeLogged;
    private boolean centerLogged;

    /**
     * 【遮挡定位 2026-11-19】壁纸只在两侧工具栏可见、编辑区不可见 → 说明窗口中心被
     * 某个不透明组件覆盖。取窗口中心点，用 getDeepestComponentAt 下钻后逐层向上打印
     * 类名/opaque/尺寸，直接点名"谁盖住了中心"。
     */
    private static void logCenterChain(Window window) {
        try {
            if (!(window instanceof RootPaneContainer rpc) || rpc.getRootPane() == null) {
                return;
            }
            JLayeredPane lp = rpc.getRootPane().getLayeredPane();
            int w = lp.getWidth();
            int h = lp.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // 注意：不能依赖 SwingUtilities.getDeepestComponentAt —— 它尊重
            // Component.contains() 覆盖，而 WallpaperSurface 故意返回 false，
            // 实测会让探测结果为空。这里按 bounds 手动下钻（取最上面的子组件）。
            StringBuilder sb = new StringBuilder();
            Component cur = lp;
            int px = w / 2;
            int py = h / 2;
            sb.append("\n    ").append(cur.getClass().getSimpleName())
                    .append(" size=").append(w).append("x").append(h)
                    .append(" children=").append(lp.getComponentCount());
            int depth = 0;
            while (cur instanceof java.awt.Container ct && depth < 24) {
                Component next = null;
                // 取"该点上最上层"的子组件：
                //  - 普通容器：getComponents() 是绘制顺序，最后一个匹配者在上层；
                //  - JLayeredPane：getComponents() 按层降序（第 0 个是最上层），第一个匹配者在上层。
                boolean layered = ct instanceof JLayeredPane;
                for (Component ch : ct.getComponents()) {
                    if (!ch.isVisible()) {
                        continue;
                    }
                    java.awt.Rectangle b = ch.getBounds();
                    if (b.width <= 0 || b.height <= 0 || !b.contains(px, py)) {
                        continue;
                    }
                    if (layered) {
                        next = ch;
                        break;
                    }
                    next = ch;
                }
                if (next == null) {
                    break;
                }
                px = px - next.getX();
                py = py - next.getY();
                if (next instanceof JComponent jc) {
                    sb.append("\n    ").append(jc.getClass().getName())
                            .append(" opaque=").append(jc.isOpaque())
                            .append(" size=").append(jc.getWidth()).append("x").append(jc.getHeight());
                } else {
                    sb.append("\n    ").append(next.getClass().getName())
                            .append(" size=").append(next.getWidth()).append("x").append(next.getHeight());
                }
                cur = next;
                depth++;
            }
            LOG.info("dwallpaper: 窗口中心组件链（内→外）：" + sb);
        } catch (Throwable t) {
            LOG.info("dwallpaper: 中心组件链探测失败：" + t);
        }
    }
    /** 【方案 E】S1 挂载成功后的补挂回调（引擎注入：OSR 直显组件挂到新挂载窗背景层）。 */
    private volatile Runnable osrRemountHook;

    public void setOsrRemountHook(Runnable hook) {
        this.osrRemountHook = hook;
    }

    @Override
    public boolean probe(Window window) {
        return window instanceof RootPaneContainer rpc
                && rpc.getRootPane() != null
                && rpc.getRootPane().getLayeredPane() != null
                && rpc.getContentPane() != null;
    }

    @Override
    public boolean install(MountContext context) {
        Window window = context.window();
        if (!(window instanceof RootPaneContainer rpc)) {
            return false;
        }
        try {
            JRootPane root = rpc.getRootPane();
            JLayeredPane lp = root.getLayeredPane();
            if (!(rpc.getContentPane() instanceof JComponent content)) {
                return false;
            }
            List<JComponent> flipped = new ArrayList<>();
            WallpaperSurface surface = context.surface(); // F18：按窗新建
            context.registerSurface(surface);
            // 【合成模型定稿 2026-11-19】全树玻璃化 = 只翻 opaque（不再安装任何
            // WallpaperBorder）。实证：替换平台组件的 border 会触发
            // ClassCastException（平台自身把 border 转成 StickyLineShadowBorder 等
            // 具体类型），进而中断整条 paint 链——这正是"图标渐进式发白、Border
            // 只画一次、主背景无壁纸"的根因。壁纸统一由 opaque 的 Surface 绘制。
            int glassed = glassify(content, flipped, 0);
            // contentPane 自身也玻璃化（它通常是最外层实心底色）
            if (content.isOpaque() && content.getClientProperty(ORIG_BG_KEY) == null) {
                java.awt.Color orig = content.getBackground();
                if (orig != null) {
                    content.putClientProperty(ORIG_BG_KEY, orig);
                    content.setBackground(withAlpha(orig, glassAlphaPercent()));
                    flipped.add(content);
                }
            }
            surface.setBounds(0, 0, lp.getWidth(), lp.getHeight());
            try {
                // 【黑屏根因修复 2026-11-19】JRootPane 把 contentPane 放在
                // FRAME_CONTENT_LAYER(-30000)，DEFAULT_LAYER(0) 高于它 —— 原实现
                // 用 DEFAULT_LAYER 使 Surface 盖住整个 UI（全黑、contains=false
                // 点击穿透）。改为专用层 = FRAME_CONTENT_LAYER - 1，确定性位于
                // contentPane 与 menuBar 之下、不依赖任何 position 语义。
                Integer belowContent = JLayeredPane.FRAME_CONTENT_LAYER.intValue() - 1;
                lp.add(surface, belowContent);
                lp.moveToBack(surface);
            } catch (Throwable t) {
                restoreOpaque(flipped);
                context.unregisterSurface(surface);
                LOG.warn("dwallpaper: S1 layeredPane add failed", t);
                return false;
            }
            ComponentListener resize = new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    surface.setBounds(0, 0, lp.getWidth(), lp.getHeight());
                    surface.repaint();
                }
            };
            lp.addComponentListener(resize);
            lp.repaint();
            State state = new State(lp, surface, flipped, resize);
            installed.put(window, state);
            // 【尺寸同步 2026-11-19】实证：挂载时 layeredPane 常为 0×0（窗口尚未完成
            // 布局），此时 setBounds 得到 0×0；而窗口之后不再产生 resize 事件，
            // 于是 Surface 永久 0×0 —— 壁纸帧"到达"却无处可画（日志实证
            // surface=0x0 comp=1x1 showing=false），JCEF 也因此 viewRect=0 不出画。
            // 用一个轻量轮询把 Surface 跟住 layeredPane 的真实尺寸。
            javax.swing.Timer sync = new javax.swing.Timer(150, e -> {
                try {
                    int w = lp.getWidth();
                    int h = lp.getHeight();
                    if (w > 0 && h > 0
                            && (surface.getWidth() != w || surface.getHeight() != h)) {
                        surface.setBounds(0, 0, w, h);
                        surface.revalidate();
                        surface.repaint();
                        if (!sizeLogged) {
                            sizeLogged = true;
                            LOG.info("dwallpaper: Surface 尺寸同步 " + w + "x" + h);
                        }
                    }
                } catch (Throwable ignored) {
                    // 单次同步失败不影响轮询
                }
            });
            sync.setRepeats(true);
            sync.start();
            state.sizeSync = sync;
            LOG.info("dwallpaper: S1 mounted on window " + window.getClass().getSimpleName()
                    + " (glass " + flipped.size() + " containers, no borders, surface "
                    + surface.getWidth() + "x" + surface.getHeight() + ")");
            return true;
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S1 install failed", t);
            return false;
        }
    }

    @Override
    public void uninstall() {
        for (Map.Entry<Window, State> en : installed.entrySet()) {
            State st = en.getValue();
            try {
                if (st.sizeSync != null) {
                    st.sizeSync.stop();
                    st.sizeSync = null;
                }
                st.layeredPane.removeComponentListener(st.resize);
                st.layeredPane.remove(st.surface);
                restoreOpaque(st.flipped);
                restoreBorders(st.layeredPane);
                st.layeredPane.repaint();
            } catch (Throwable t) {
                LOG.warn("dwallpaper: S1 uninstall issue", t);
            }
        }
        installed.clear();
    }

    /**
     * 【玻璃板全树遍历 2026-11-19】对给定窗执行一次玻璃化补装（幂等：已有
     * ORIG_OPAQUE_KEY 标记的组件跳过）。引擎在 S2 延迟成功后错峰调用、
     * ToolWindowManagerListener 触发调用、applyEntry 时调用——抓取懒创建组件。
     * 编辑器子树跳过（EditorEx 由 S2 专管背景与父链）。
     */
    public void ensureGlass(Window window, MountStrategy.MountContext context) {
        State st = installed.get(window);
        if (st == null || !(window instanceof RootPaneContainer rpc)
                || !(rpc.getContentPane() instanceof JComponent content)) {
            return;
        }
        try {
            int added = glassify(content, st.flipped, 0);
            if (added > 0) {
                LOG.info("dwallpaper: glass pass added " + added + " panel(s) on "
                        + window.getClass().getSimpleName());
            }
            if (!centerLogged) {
                centerLogged = true;
                logCenterChain(window);
            }
        } catch (Throwable t) {
            LOG.warn("dwallpaper: glass pass issue", t);
        }
    }

    /**
     * 全树玻璃化（无深度/类型上限）。
     *
     * <p>【2026-11-19 关键修正：改为"半透明底色"而非 opaque=false】实证：
     * 把平台组件设成 {@code setOpaque(false)} 之后，画布纯色探针依然只在两侧
     * 工具栏缝隙里可见 —— 说明这些组件**不管 opaque 标志如何都会画自己的底色**
     * （大量 IDEA 面板的 UI 直接用 {@code getBackground()} 填充）。因此正确杠杆是
     * <b>把底色本身变成半透明</b>：组件照常绘制，但它画出来的是一层玻璃，壁纸从
     * 下面透出来。同时 {@code opaque} 保持原值，平台的分隔线/边框语义不受影响。</p>
     */
    private static int glassify(JComponent c, List<JComponent> flipped, int depth) {
        if (depth > 64) {
            return 0; // 防御性环/极深树护栏
        }
        if (c instanceof com.intellij.openapi.editor.ex.EditorEx) {
            return 0; // S2 域：编辑器背景与父链由 EditorTransparentStrategy 处理
        }
        int added = 0;
        if (c.isOpaque() && c.getClientProperty(ORIG_BG_KEY) == null) {
            java.awt.Color orig = c.getBackground();
            if (orig != null) {
                c.putClientProperty(ORIG_BG_KEY, orig);
                c.setBackground(withAlpha(orig, glassAlphaPercent()));
                flipped.add(c);
                added++;
            }
        }
        for (Component child : c.getComponents()) {
            if (child instanceof JComponent jc) {
                added += glassify(jc, flipped, depth + 1);
            }
        }
        return added;
    }

    /** 玻璃板底色不透明度（%）：0=全透（壁纸最强）…100=不透明（等同原生）。
     * 属性 {@code -Ddwallpaper.glass.alpha} 可覆盖；默认 45。 */
    private static int glassAlphaPercent() {
        try {
            String v = java.lang.System.getProperty("dwallpaper.glass.alpha");
            if (v != null && !v.isBlank()) {
                return Math.max(0, Math.min(100, Integer.parseInt(v.trim())));
            }
        } catch (Throwable ignored) {
            // 非法覆盖值走默认
        }
        return 45;
    }

    private static java.awt.Color withAlpha(java.awt.Color base, int percent) {
        return new java.awt.Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.max(0, Math.min(100, percent)) * 255 / 100);
    }

    /** 【Border 方案】卸载：遍历还原所有由本策略设置的 WallpaperBorder（逆序还原纪律）。 */
    private static void restoreBorders(JLayeredPane lp) {
        restoreBordersRecursive(lp);
    }

    private static final String ORIG_BORDER_KEY = "dwallpaper.origBorder"; // 仅用于卸载清理历史 border

    private static void restoreBordersRecursive(Component c) {
        if (c instanceof JComponent jc) {
            if (jc.getBorder() instanceof WallpaperBorder) {
                Object orig = jc.getClientProperty(ORIG_BORDER_KEY);
                jc.putClientProperty(ORIG_BORDER_KEY, null);
                jc.setBorder(orig instanceof javax.swing.border.Border b ? b : null);
            }
        }
        if (c instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                restoreBordersRecursive(child);
            }
        }
    }

    /** 还原玻璃化的底色（严格逆序纪律；历史 ORIG_OPAQUE_KEY 一并清理）。 */
    private static void restoreOpaque(List<JComponent> flipped) {
        for (JComponent c : flipped) {
            try {
                Object origBg = c.getClientProperty(ORIG_BG_KEY);
                c.putClientProperty(ORIG_BG_KEY, null);
                c.putClientProperty(ORIG_OPAQUE_KEY, null);
                if (origBg instanceof java.awt.Color bg) {
                    c.setBackground(bg);
                }
            } catch (Throwable ignored) {
                // 还原单面板失败不中断
            }
        }
        flipped.clear();
    }
}


