package com.dwallpaper.fog;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.EnumMap;
import java.util.Map;

/**
 * 区边界解析（design.md §6.3）：优先公开 API —— 编辑器=FileEditorManager.getSelectedTextEditor()；
 * 工具窗=ToolWindowManager.getToolWindowIds() + getAnchor()（LEFT/RIGHT→Z2，BOTTOM→Z3，TOP 不属六区）；
 * 状态栏=WindowManager.getStatusBar(project).getComponent()；主工具栏=根窗格内容区顶部条带启发式
 * （无内部类依赖，探测失败该区跳过并日志标注）。全部在 EDT 调用（组件 bounds）；坐标基准=layeredPane
 * （GlassPane 同尺寸）。262 实测：ToolWindowManagerListener 已不在原包 → 区跟随由
 * FogOverlayManager 1s 轻量重解析定时器保证（AC-FOG-4 ≤1s）。
 */
public final class ZoneResolver {

    public static final int MAX_BLUR_RADIUS_PX = 30;
    private static final Logger LOG = Logger.getInstance(ZoneResolver.class);

    private ZoneResolver() {
    }

    /** 解析六区边界（layeredPane 坐标）；解析失败的区不进结果（该区层不绘制）。 */
    public static Map<FogZone, Rectangle> resolve(Window window, Project project) {
        Map<FogZone, Rectangle> out = new EnumMap<>(FogZone.class);
        if (!(window instanceof RootPaneContainer rpc)) {
            return out;
        }
        JRootPane rootPane = rpc.getRootPane();
        if (rootPane == null || rootPane.getLayeredPane() == null) {
            return out;
        }
        Component base = rootPane.getLayeredPane();

        // Z1 EDITOR
        try {
            if (project != null) {
                Editor ed = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (ed != null) {
                    add(out, FogZone.EDITOR, convert(ed.getContentComponent(), base));
                }
            }
        } catch (Throwable t) {
            LOG.info("dwallpaper: Z1 editor bounds resolve skipped: " + t);
        }

        // Z2/Z3 tool windows
        try {
            if (project != null) {
                ToolWindowManager tm = ToolWindowManager.getInstance(project);
                for (String id : tm.getToolWindowIds()) {
                    ToolWindow tw = tm.getToolWindow(id);
                    if (tw == null || !tw.isVisible()) {
                        continue;
                    }
                    ToolWindowAnchor a = tw.getAnchor();
                    Rectangle r = convert(tw.getComponent(), base);
                    if (r == null) {
                        continue;
                    }
                    if (a == ToolWindowAnchor.LEFT || a == ToolWindowAnchor.RIGHT) {
                        union(out, FogZone.TOOLWINDOW_SIDE, r);
                    } else if (a == ToolWindowAnchor.BOTTOM) {
                        union(out, FogZone.TOOLWINDOW_BOTTOM, r);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.info("dwallpaper: Z2/Z3 tool window bounds resolve skipped: " + t);
        }

        // Z5 STATUSBAR
        try {
            if (project != null) {
                StatusBar sb = WindowManager.getInstance().getStatusBar(project);
                if (sb != null) {
                    JComponent c = sb.getComponent();
                    if (c != null && c.isShowing()) {
                        add(out, FogZone.STATUSBAR, convert(c, base));
                    }
                }
            }
        } catch (Throwable t) {
            LOG.info("dwallpaper: Z5 status bar bounds resolve skipped: " + t);
        }

        // Z4 TOOLBAR（启发式：内容区顶部全宽条带）
        try {
            Rectangle tb = detectToolbar(rootPane, base);
            if (tb != null) {
                add(out, FogZone.TOOLBAR, tb);
            } else {
                LOG.info("dwallpaper: Z4 toolbar not detected (zone skipped)");
            }
        } catch (Throwable t) {
            LOG.info("dwallpaper: Z4 toolbar resolve skipped: " + t);
        }

        // Z6 POPUP（可选，默认关）：由 FogOverlayManager 弹窗状态驱动，本解析恒不产出
        return out;
    }

    /** 顶部条带启发式：递归 ≤3 层，找 y≈0、全宽、矮（≤70px）的可见面板。 */
    private static Rectangle detectToolbar(JRootPane rootPane, Component base) {
        Component content = rootPane.getContentPane();
        if (!(content instanceof JComponent jc)) {
            return null;
        }
        Rectangle best = null;
        int baseW = base.getWidth();
        for (Component c : jc.getComponents()) {
            if (!c.isShowing() || !(c instanceof JComponent child)) {
                continue;
            }
            Rectangle r = convert(child, base);
            if (r == null || r.width < baseW * 7 / 10 || r.height > 70 || r.y > 60) {
                continue;
            }
            if (best == null || r.height < best.height) {
                best = r;
            }
        }
        return best;
    }

    private static Rectangle convert(Component c, Component base) {
        if (c == null || !c.isShowing()) {
            return null;
        }
        Rectangle r = SwingUtilities.convertRectangle(c, new Rectangle(c.getSize()), base);
        Rectangle clip = base.getBounds();
        Rectangle inter = r.intersection(clip);
        return inter.isEmpty() ? null : inter;
    }

    private static void add(Map<FogZone, Rectangle> out, FogZone z, Rectangle r) {
        if (r != null) {
            out.put(z, r);
        }
    }

    private static void union(Map<FogZone, Rectangle> out, FogZone z, Rectangle r) {
        Rectangle cur = out.get(z);
        out.put(z, cur == null ? r : cur.union(r));
    }
}
