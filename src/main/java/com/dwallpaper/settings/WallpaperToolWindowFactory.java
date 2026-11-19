package com.dwallpaper.settings;

import com.dwallpaper.fog.FogOverlayManager;
import com.dwallpaper.fog.FogPresetStore;
import com.dwallpaper.fog.FogZone;
import com.dwallpaper.welibrary.WeEntry;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

/**
 * 右侧 ToolWindow「Dynamic Wallpaper」完整面板（design.md §8.2，即时生效原则：任何控件变更 ≤300ms）：
 * 头部（全局开关/播放暂停/fps 5–30/空闲暂停/刷新库）+ 壁纸库列表（96×54 缩略图 + 标题 + 类型徽标，
 * 双击/回车应用，scene/application 灰显「仅索引」，video/web 徽标「视频 · JCEF」/「网页 · JCEF」可选，
 * JCEF 不可用时应用侧降级提示——t9 F1 修复后口径）
 * + 效果三滑杆（模糊 0–60(1)/亮度 40–160(5)/不透明度 0–90(5)）+ 六区雾镜编辑器（预设下拉 + 另存 + 区×参数网格）
 * + 状态行。面板不注册任何快捷键，不吞编辑器按键（§8.1）。
 */
public final class WallpaperToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final Logger LOG = Logger.getInstance(WallpaperToolWindowFactory.class);
    public static final String TOOL_WINDOW_ID = "Dynamic Wallpaper";
    private static final int THUMB_W = 96;
    private static final int THUMB_H = 54;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        try {
            WallpaperController controller = com.intellij.openapi.application.ApplicationManager
                    .getApplication().getService(WallpaperController.class);
            controller.ensureInit();
            toolWindow.getComponent().add(buildPanel(controller, toolWindow), java.awt.BorderLayout.CENTER);
        } catch (Throwable t) {
            LOG.error("dwallpaper: tool window panel init failed", t);
        }
    }

    private JComponent buildPanel(WallpaperController controller, ToolWindow toolWindow) {
        SettingsState.State st = controller.settings().current();

        JPanel root = new JPanel();
        root.setLayout(new javax.swing.BoxLayout(root, javax.swing.BoxLayout.Y_AXIS));
        root.setBorder(JBUI.Borders.empty(6));

        // ---- 头部（§8.2） ----
        JPanel header = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        JCheckBox enableBox = new JCheckBox("启用壁纸", st.wallpaperEnabled);
        JToggleButton pauseBtn = new JToggleButton("暂停", st.manualPaused);
        pauseBtn.setToolTipText("播放/暂停");
        JLabel fpsLabel = new JLabel("fps");
        JSpinner fpsSpinner = new JSpinner(new SpinnerNumberModel(st.fpsLimit, 5, 60, 1));
        JCheckBox idleBox = new JCheckBox("空闲暂停", st.idlePauseEnabled);
        JButton refreshBtn = new JButton("刷新库");
        header.add(enableBox);
        header.add(pauseBtn);
        header.add(fpsLabel);
        header.add(fpsSpinner);
        header.add(idleBox);
        header.add(refreshBtn);

        // ---- 壁纸库列表（§8.2） ----
        DefaultListModel<WeEntry> listModel = new DefaultListModel<>();
        JBList<WeEntry> list = new JBList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EntryRenderer(controller));
        Map<String, WeEntry> byId = new java.util.concurrent.ConcurrentHashMap<>();
        Runnable loadLibrary = () -> controller.ensureLibraryLoaded(entries -> {
            byId.clear();
            listModel.clear();
            for (WeEntry e : entries) {
                byId.put(e.id(), e);
                listModel.addElement(e);
            }
            LOG.info("dwallpaper: library list populated: " + entries.size());
        });
        loadLibrary.run();
        refreshBtn.addActionListener(e -> controller.refreshLibrary(entries -> {
            byId.clear();
            listModel.clear();
            for (WeEntry en : entries) {
                byId.put(en.id(), en);
                listModel.addElement(en);
            }
        }));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    applySelected(controller, list);
                }
            }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                    applySelected(controller, list);
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(320, 220));
        listScroll.setBorder(JBUI.Borders.empty());

        // ---- 三滑杆（§5.1 定稿量程） ----
        JPanel sliders = new JPanel(new GridLayout(3, 2, 6, 2));
        JLabel blurVal = new JLabel(String.valueOf(st.blurPx), SwingConstants.RIGHT);
        JSlider blur = new JSlider(JSlider.HORIZONTAL, 0, 60, st.blurPx);
        blur.setMajorTickSpacing(20);
        JLabel brightVal = new JLabel(st.brightnessPercent + "%", SwingConstants.RIGHT);
        JSlider bright = new JSlider(JSlider.HORIZONTAL, 40, 160, st.brightnessPercent);
        JLabel opVal = new JLabel(st.opacityPercent + "%", SwingConstants.RIGHT);
        JSlider op = new JSlider(JSlider.HORIZONTAL, 0, 90, st.opacityPercent);
        sliders.add(labeled("模糊(0–60)", blur));
        sliders.add(blurVal);
        sliders.add(labeled("亮度(40–160%)", bright));
        sliders.add(brightVal);
        sliders.add(labeled("不透明度(0–90%)", op));
        sliders.add(opVal);
        blur.addChangeListener(e -> {
            blurVal.setText(String.valueOf(blur.getValue()));
            controller.applySliderParams(blur.getValue(), bright.getValue(), op.getValue());
        });
        bright.addChangeListener(e -> {
            brightVal.setText(bright.getValue() + "%");
            controller.applySliderParams(blur.getValue(), bright.getValue(), op.getValue());
        });
        op.addChangeListener(e -> {
            opVal.setText(op.getValue() + "%");
            controller.applySliderParams(blur.getValue(), bright.getValue(), op.getValue());
        });

        // ---- 六区雾镜编辑器（§6/§8.2） ----
        JPanel fogBox = new JPanel();
        fogBox.setLayout(new javax.swing.BoxLayout(fogBox, javax.swing.BoxLayout.Y_AXIS));
        JPanel presetRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        javax.swing.JComboBox<String> presetCombo = new javax.swing.JComboBox<>();
        Runnable reloadPresets = () -> {
            presetCombo.removeAllItems();
            for (String id : controller.presetIds()) {
                presetCombo.addItem(FogPresetStore.displayName(id));
            }
            presetCombo.setSelectedItem(
                    FogPresetStore.displayName(controller.settings().current().activeFogPreset));
        };
        reloadPresets.run();
        JButton savePreset = new JButton("另存预设");
        presetRow.add(new JLabel("预设:"));
        presetRow.add(presetCombo);
        presetRow.add(savePreset);
        fogBox.add(presetRow);

        JPanel zoneGrid = new JPanel(new GridLayout(FogZone.values().length, 5, 4, 2));
        Map<FogZone, JSpinner[]> zoneSpinners = new java.util.LinkedHashMap<>();
        for (Map.Entry<FogZone, String> zt : FogOverlayManager.zoneTitles().entrySet()) {
            SettingsState.ZoneState zs = st.fogZones.get(zt.getKey().name());
            if (zs == null) {
                zs = new SettingsState.ZoneState(0, 0, 0, 0);
            }
            zoneGrid.add(new JLabel(zt.getValue()));
            JSpinner fogS = new JSpinner(new SpinnerNumberModel(zs.fogIntensity, 0, 100, 5));
            JSpinner blurS = new JSpinner(new SpinnerNumberModel(zs.blurRadiusPx, 0, 30, 1));
            JSpinner brightS = new JSpinner(new SpinnerNumberModel(zs.brightnessOffsetPercent, -30, 30, 5));
            JSpinner opS = new JSpinner(new SpinnerNumberModel(zs.opacityPercent, 0, 100, 5));
            zoneGrid.add(fogS);
            zoneGrid.add(blurS);
            zoneGrid.add(brightS);
            zoneGrid.add(opS);
            zoneSpinners.put(zt.getKey(), new JSpinner[]{fogS, blurS, brightS, opS});
            javax.swing.event.ChangeListener l = e -> {
                SettingsState.ZoneState cur = controller.settings().current().fogZones.get(zt.getKey().name());
                int f0 = cur == null ? 0 : cur.fogIntensity;
                int b0 = cur == null ? 0 : cur.blurRadiusPx;
                int br0 = cur == null ? 0 : cur.brightnessOffsetPercent;
                int o0 = cur == null ? 0 : cur.opacityPercent;
                int f = (Integer) fogS.getValue();
                int b = (Integer) blurS.getValue();
                int br = (Integer) brightS.getValue();
                int o = (Integer) opS.getValue();
                if (f != f0 || b != b0 || br != br0 || o != o0) {
                    controller.updateZoneParams(zt.getKey(),
                            new com.dwallpaper.fog.ZoneParams(f, b, br, o));
                }
            };
            fogS.addChangeListener(l);
            blurS.addChangeListener(l);
            brightS.addChangeListener(l);
            opS.addChangeListener(l);
        }
        fogBox.add(zoneGrid);
        fogBox.add(new JLabel("区参数：雾化0–100｜模糊0–30｜亮度偏移-30..30｜不透明0–100"));

        presetCombo.addActionListener(e -> {
            Object sel = presetCombo.getSelectedItem();
            if (sel == null) {
                return;
            }
            for (String id : controller.presetIds()) {
                if (FogPresetStore.displayName(id).equals(sel)) {
                    controller.applyPreset(id);
                    syncZoneSpinners(zoneSpinners, controller);
                    break;
                }
            }
        });
        savePreset.addActionListener(e -> {
            String name = Messages.showInputDialog("预设名称:", "另存自定义预设", Messages.getQuestionIcon());
            if (name != null && !name.isBlank()) {
                controller.saveCustomPreset(name.trim());
                reloadPresets.run();
            }
        });

        // ---- 状态行（§8.2） ----
        JLabel status = new JLabel(controller.statusLine());
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        javax.swing.Timer statusTimer = new javax.swing.Timer(2000, e -> status.setText(controller.statusLine()));
        statusTimer.setRepeats(true);
        statusTimer.start();
        // F8：定时器随工具窗生命周期释放（关闭工具窗/工程关闭后不再泄漏 controller 引用链）
        com.intellij.openapi.util.Disposer.register(toolWindow.getDisposable(), statusTimer::stop);

        // ---- 头部事件（开关类即时） ----
        enableBox.addActionListener(e -> controller.setGlobalEnabled(enableBox.isSelected()));
        pauseBtn.addActionListener(e -> controller.setManualPaused(pauseBtn.isSelected()));
        idleBox.addActionListener(e -> controller.setIdlePauseEnabled(idleBox.isSelected()));
        fpsSpinner.addChangeListener(e -> controller.setFpsLimit((Integer) fpsSpinner.getValue()));

        // ---- 组装 ----
        root.add(header);
        root.add(sectionTitle("壁纸库（双击应用）"));
        root.add(listScroll);
        root.add(sectionTitle("效果"));
        root.add(sliders);
        root.add(sectionTitle("六区雾镜"));
        root.add(fogBox);
        root.add(sectionTitle("状态"));
        root.add(status);
        root.add(Box.createVerticalGlue());

        JScrollPane outer = new JScrollPane(root);
        outer.setBorder(JBUI.Borders.empty());
        outer.getVerticalScrollBar().setUnitIncrement(16);
        return outer;
    }

    private static void applySelected(WallpaperController controller, JList<WeEntry> list) {
        WeEntry e = list.getSelectedValue();
        if (e != null) {
            controller.applyEntry(e);
        }
    }

    private static void syncZoneSpinners(Map<FogZone, JSpinner[]> zoneSpinners, WallpaperController controller) {
        SettingsState.State st = controller.settings().current();
        for (Map.Entry<FogZone, JSpinner[]> en : zoneSpinners.entrySet()) {
            SettingsState.ZoneState zs = st.fogZones.get(en.getKey().name());
            if (zs == null) {
                continue;
            }
            JSpinner[] s = en.getValue();
            s[0].setValue(zs.fogIntensity);
            s[1].setValue(zs.blurRadiusPx);
            s[2].setValue(zs.brightnessOffsetPercent);
            s[3].setValue(zs.opacityPercent);
        }
    }

    private static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent labeled(String text, JSlider slider) {
        JPanel p = new JPanel(new java.awt.BorderLayout());
        JLabel l = new JLabel(text);
        l.setToolTipText(text);
        p.add(l, java.awt.BorderLayout.NORTH);
        p.add(slider, java.awt.BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** 库条目渲染：96×54 缩略图 + 标题 + 类型徽标；不支持/待 t5 → 灰显（§8.2）。 */
    private static final class EntryRenderer extends JPanel implements ListCellRenderer<WeEntry> {
        private final JLabel thumb = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel badge = new JLabel();
        private final WallpaperController controller;

        EntryRenderer(WallpaperController controller) {
            this.controller = controller;
            setLayout(new java.awt.BorderLayout(6, 2));
            thumb.setPreferredSize(new Dimension(THUMB_W, THUMB_H));
            thumb.setHorizontalAlignment(SwingConstants.CENTER);
            JPanel text = new JPanel();
            text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
            title.setFont(title.getFont().deriveFont(Font.PLAIN, 12f));
            badge.setFont(badge.getFont().deriveFont(Font.PLAIN, 10f));
            text.add(title);
            text.add(badge);
            add(thumb, java.awt.BorderLayout.WEST);
            add(text, java.awt.BorderLayout.CENTER);
            setBorder(JBUI.Borders.empty(2, 4));
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends WeEntry> list, WeEntry value,
                                                      int index, boolean selected, boolean focus) {
            title.setText(value.title() == null ? value.id() : value.title());
            String type = value.type() == null ? "other" : value.type();
            boolean indexOnly = !value.supported() || type.equals("scene") || type.equals("application");
            // F1 修复：video/web 不再灰显禁选——registry 分派 t5 JCEF 通道（无 JCEF 时应用侧降级提示）
            String badgeText = switch (type) {
                case "gif" -> "GIF";
                case "sequence" -> "序列帧";
                case "static" -> "静态图";
                case "video" -> "视频 · JCEF";
                case "web" -> "网页 · JCEF";
                case "scene" -> "scene · 仅索引";
                case "application" -> "application · 仅索引";
                default -> "未知类型 · 仅索引";
            };
            badge.setText(badgeText);
            title.setEnabled(!indexOnly);
            badge.setEnabled(!indexOnly);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            thumb.setIcon(null);
            Icon icon = (Icon) list.getClientProperty("thumb:" + value.id());
            if (icon != null) {
                thumb.setIcon(icon);
            } else {
                controller.library().thumbnail(value, ic -> {
                    if (ic != null) {
                        list.putClientProperty("thumb:" + value.id(), ic);
                        list.repaint();
                    }
                });
            }
            return this;
        }
    }
}
