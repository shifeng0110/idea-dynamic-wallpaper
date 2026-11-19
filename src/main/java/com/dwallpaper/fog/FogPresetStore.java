package com.dwallpaper.fog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 雾镜预设（design.md §6.2）：内置①「编辑器清透、面板轻雾」（默认启用，Z1 全 0=清透、
 * Z2/Z3=30/8/0/45、Z4/Z5=20/6/0/35、Z6 关）②「全关」③「重雾」（面板 60/16、工具栏状态栏 50/12）。
 * 自定义预设另存由 SettingsState.customPresets 持久化（白名单键 + 数值钳制，controller 管理）。
 */
public final class FogPresetStore {

    /** 内置默认预设名（「编辑器清透、面板轻雾」）。 */
    public static final String PRESET_DEFAULT = "editor-clear-panels-mist";
    public static final String PRESET_NONE = "all-off";
    public static final String PRESET_HEAVY = "heavy";

    private FogPresetStore() {
    }

    public static List<String> builtinIds() {
        return List.of(PRESET_DEFAULT, PRESET_NONE, PRESET_HEAVY);
    }

    /** 预设名 → 中文显示名（面板下拉）。 */
    public static String displayName(String id) {
        return switch (id) {
            case PRESET_DEFAULT -> "编辑器清透、面板轻雾";
            case PRESET_NONE -> "全关";
            case PRESET_HEAVY -> "重雾";
            default -> id;
        };
    }

    /** 内置预设 → 六区参数（全部六区必有条目；fogIntensity=0 区层不绘制=清透）。 */
    public static Map<FogZone, ZoneParams> builtin(String id) {
        Map<FogZone, ZoneParams> m = new LinkedHashMap<>();
        switch (id) {
            case PRESET_DEFAULT -> {
                m.put(FogZone.EDITOR, new ZoneParams(0, 0, 0, 0));          // Z1 清透
                m.put(FogZone.TOOLWINDOW_SIDE, new ZoneParams(30, 8, 0, 45));
                m.put(FogZone.TOOLWINDOW_BOTTOM, new ZoneParams(30, 8, 0, 45));
                m.put(FogZone.TOOLBAR, new ZoneParams(20, 6, 0, 35));
                m.put(FogZone.STATUSBAR, new ZoneParams(20, 6, 0, 35));
                m.put(FogZone.POPUP, new ZoneParams(0, 0, 0, 0));           // Z6 关
            }
            case PRESET_NONE -> {
                for (FogZone z : FogZone.values()) {
                    m.put(z, ZoneParams.DISABLED);
                }
            }
            case PRESET_HEAVY -> {
                m.put(FogZone.EDITOR, new ZoneParams(0, 0, 0, 0));
                m.put(FogZone.TOOLWINDOW_SIDE, new ZoneParams(60, 16, 0, 60));
                m.put(FogZone.TOOLWINDOW_BOTTOM, new ZoneParams(60, 16, 0, 60));
                m.put(FogZone.TOOLBAR, new ZoneParams(50, 12, 0, 50));
                m.put(FogZone.STATUSBAR, new ZoneParams(50, 12, 0, 50));
                m.put(FogZone.POPUP, new ZoneParams(0, 0, 0, 0));
            }
            default -> {
                for (FogZone z : FogZone.values()) {
                    m.put(z, ZoneParams.DISABLED);
                }
            }
        }
        return m;
    }
}
