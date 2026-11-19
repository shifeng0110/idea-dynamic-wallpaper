package com.dwallpaper.core;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S2 编辑器背景透明化（design.md §4.1，配合 S1 使文字之下底图可见，AC-CP-1）。
 * 【2026.2 实测】Editor.setColorsScheme / EditorColors.BACKGROUND 在 262 已不可用；
 * 正确路径 = EditorEx.setBackgroundColor(Color) + createBoundColorSchemeDelegate(null)
 * （编辑器级配色代理）改 GUTTER_BACKGROUND 透明。原值逐编辑器记录，卸载严格还原（AC-CP-3）。
 * 监听 FileEditorManagerListener（selectionChanged/fileOpened 重新应用，fileClosed 还原清理）。
 */
public final class EditorTransparentStrategy implements MountStrategy {

    private static final Logger LOG = Logger.getInstance(EditorTransparentStrategy.class);

    private static final class Patched {
        final Color origBackground;
        final EditorColorsScheme origScheme;

        Patched(Color origBackground, EditorColorsScheme origScheme) {
            this.origBackground = origBackground;
            this.origScheme = origScheme;
        }
    }

    /** 编辑器链上被我们透明化的组件（卸载严格还原原 opaque）。 */
    private static final class ChainPatch {
        final JComponent component;
        final boolean origOpaque;

        ChainPatch(JComponent component, boolean origOpaque) {
            this.component = component;
            this.origOpaque = origOpaque;
        }
    }

    private record ListenerEntry(Project project, FileEditorManagerListenerShim listener) {
    }

    /** 平台 listener 以函数式接收（262 上 FileEditorManagerListener 接口含 default 方法）。 */
    private interface FileEditorManagerListenerShim extends com.intellij.openapi.fileEditor.FileEditorManagerListener {
    }

    private final Map<Editor, Patched> patched = new HashMap<>();
    private final List<ChainPatch> chainPatches = new ArrayList<>();
    private final List<ListenerEntry> listeners = new ArrayList<>();
    /** 已安装监听器的项目（【S2 延迟重试 2026-11-19】幂等守卫，防重复挂监听器）。 */
    private final java.util.Set<Project> installedProjects = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override
    public boolean probe(Window window) {
        return true; // 探测在 install 时按 project 判定
    }

    @Override
    public boolean install(MountContext context) {
        Project project = context.project();
        if (project == null) {
            return false;
        }
        if (!installedProjects.add(project)) {
            return true; // 已安装过（幂等）：按成功计，触发方据此停止重试
        }
        try {
            FileEditorManager fem = FileEditorManager.getInstance(project);
            FileEditorManagerListenerShim listener = new FileEditorManagerListenerShim() {
                @Override
                public void selectionChanged(@Nullable com.intellij.openapi.fileEditor.FileEditorManagerEvent event) {
                    applyToSelected(project);
                }

                @Override
                public void fileOpened(@Nullable com.intellij.openapi.fileEditor.FileEditorManager manager,
                                       @Nullable com.intellij.openapi.vfs.VirtualFile file) {
                    applyToSelected(project);
                }

                @Override
                public void fileClosed(@Nullable com.intellij.openapi.fileEditor.FileEditorManager manager,
                                       @Nullable com.intellij.openapi.vfs.VirtualFile file) {
                    cleanupDisposed();
                }
            };
            fem.addFileEditorManagerListener(listener);
            listeners.add(new ListenerEntry(project, listener));
            lastContext = context; // 【Border 方案】apply 时经 context.registerBorderPanel 登记扇出
            applyToSelected(project);
            LOG.info("dwallpaper: S2 editor transparency installed for project " + project.getName());
            return true;
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S2 install failed", t);
            return false;
        }
    }

    private void applyToSelected(Project project) {
        try {
            Editor ed = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (ed != null) {
                apply(ed);
            }
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S2 apply failed", t);
        }
    }

    private void apply(Editor editor) {
        if (patched.containsKey(editor) || !(editor instanceof EditorEx ex)) {
            return;
        }
        try {
            // 【可读性架构修正 2026-11-19】对齐 DSH 内容面方案：编辑器背景不再全透明
            //（全透明=代码直接压在壁纸上，用户实测"代码看不清"）。改为"近不透明玻璃底"：
            // 主题编辑器底色 + 可调不透明度（默认 85%，系统属性
            // dwallpaper.editor.scrim 覆盖，0=全透 100=不透）。文字对比度回到可读区间，
            // 壁纸以底色残余透明度隐约透出。
            int scrim = editorScrimPercent();
            Color origBg = ex.getBackgroundColor();
            EditorColorsScheme origScheme = ex.getColorsScheme();
            Color glass = withAlpha(origBg, scrim);
            EditorColorsScheme delegate = ex.createBoundColorSchemeDelegate(null);
            // 【2026-11-19 关键修正】只调 setBackgroundColor 不够：实测"空编辑器可见、
            // 一打开文件壁纸就消失"——说明编辑器实际是用**配色方案**里的默认背景填充，
            // 而不是 getBackgroundColor()。因此把三处一起改：
            // ① 方案默认背景（实际绘制用）；② 行号槽；③ 编辑器背景属性；④ 内容组件底色。
            // 262 的 EditorColorsScheme 接口没有 setDefaultBackground(Color)（编译期不支持），
            // 但具体实现可能有 —— 反射尝试，失败则忽略（其余三处已足够）。
            try {
                java.lang.reflect.Method m = delegate.getClass()
                        .getMethod("setDefaultBackground", Color.class);
                m.invoke(delegate, glass);
            } catch (Throwable ignored) {
                // 本版本不支持，跳过
            }
            delegate.setColor(EditorColors.GUTTER_BACKGROUND, glass);
            ex.setColorsScheme(delegate);
            ex.setBackgroundColor(glass);
            try {
                java.awt.Component contentComp = ex.getContentComponent();
                if (contentComp instanceof JComponent jcContent) {
                    jcContent.setBackground(glass);
                }
            } catch (Throwable ignored) {
                // 内容组件不可用不影响主链路
            }
            patched.put(editor, new Patched(origBg, origScheme));
            // 编辑器父链：既改透明标志，也把底色改成同一套玻璃色（平台面板往往用
            // getBackground() 自绘，光改 opaque 无效）。
            java.awt.Container c = ex.getComponent().getParent();
            int depth = 0;
            while (c instanceof JComponent jc && depth < 8) {
                if (jc.getClientProperty(ORIG_BORDER_KEY) == null) {
                    chainPatches.add(new ChainPatch(jc, jc.isOpaque()));
                    jc.putClientProperty(ORIG_BORDER_KEY, Boolean.TRUE); // 幂等标记（本策略不设 border）
                    if (jc.isOpaque()) {
                        jc.setOpaque(false);
                    }
                    java.awt.Color pbg = jc.getBackground();
                    if (pbg != null) {
                        jc.putClientProperty("dwallpaper.origChainBg", pbg);
                        jc.setBackground(withAlpha(pbg, scrim));
                    }
                }
                c = c.getParent();
                depth++;
            }
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S2 patch editor failed", t);
        }
    }

    private static final String ORIG_BORDER_KEY = "dwallpaper.origBorder";

    /** 编辑器内容面玻璃底不透明度（%）：默认 20。
     * 【2026-11-19 实证修正】默认 85→60→20：日志实测暗视频（luma≈47）在
     * 「Surface 不透明度 60% × 编辑器底色 60%」下有效贡献仅约 16%，最终亮度 33 与
     * 主题底色 30 无法区分，用户看到的就是"什么都没有"。20 时壁纸贡献约 72%，
     * 深色主题下代码仍可读；可用 -Ddwallpaper.editor.scrim=0..100 覆盖。 */
    private static int editorScrimPercent() {
        int v = 20;
        try {
            String s = java.lang.System.getProperty("dwallpaper.editor.scrim");
            if (s != null) {
                v = Math.max(0, Math.min(100, Integer.parseInt(s.trim())));
            }
        } catch (Throwable ignored) {
        }
        return v;
    }

    /** 主题底色 → 同 RGB、指定不透明度（%）。 */
    private static Color withAlpha(Color base, int percent) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.max(0, Math.min(100, percent)) * 255 / 100);
    }

    /** 最近一次 install 的 context（【Border 方案】apply 时登记 border 面板进引擎扇出）。 */
    private volatile MountContext lastContext;

    private void cleanupDisposed() {
        patched.keySet().removeIf(ed -> {
            try {
                return ed.isDisposed();
            } catch (Throwable t) {
                return true;
            }
        });
    }

    @Override
    public void uninstall() {
        for (Map.Entry<Editor, Patched> en : patched.entrySet()) {
            Editor ed = en.getKey();
            Patched p = en.getValue();
            try {
                if (!ed.isDisposed() && ed instanceof EditorEx ex) {
                    ex.setColorsScheme(p.origScheme);
                    ex.setBackgroundColor(p.origBackground);
                }
            } catch (Throwable ignored) {
                // 编辑器已销毁
            }
        }
        patched.clear();
        // 还原编辑器链上的透明化与玻璃底色（逆序）
        for (ChainPatch cp : chainPatches) {
            try {
                cp.component.putClientProperty("dwallpaper.origBorder", null);
                cp.component.setOpaque(cp.origOpaque);
                Object bg = cp.component.getClientProperty("dwallpaper.origChainBg");
                cp.component.putClientProperty("dwallpaper.origChainBg", null);
                if (bg instanceof Color origChainBg) {
                    cp.component.setBackground(origChainBg);
                }
                cp.component.repaint();
            } catch (Throwable ignored) {
            }
        }
        chainPatches.clear();
        for (ListenerEntry le : listeners) {
            try {
                FileEditorManager.getInstance(le.project()).removeFileEditorManagerListener(le.listener());
            } catch (Throwable ignored) {
                // project 已关闭
            }
        }
        listeners.clear();
        installedProjects.clear();
    }

    /** 供引擎注入 surface 上下文时使用（JComponent 占位避免未使用告警）。 */
    static JComponent unusedAnchor() {
        return null;
    }
}
