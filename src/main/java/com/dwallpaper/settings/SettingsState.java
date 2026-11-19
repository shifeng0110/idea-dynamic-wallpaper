package com.dwallpaper.settings;

import com.dwallpaper.core.AdjustmentPipeline;
import com.dwallpaper.core.FrameRateController;
import com.dwallpaper.fog.FogZone;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用级设置持久化（design.md §10.1）：全部可调参数、六区参数、预设列表、库覆盖目录、
 * fps/空闲开关、全局开关、当前壁纸。白名单键（public 字段即白名单，未知键平台自动丢弃）
 * + 数值范围钳制（loadState 统一 clamp，AC-PST-2）；版本字段向前兼容。
 * 存放位置为 IDE 配置目录小型 XML（设置非下载缓存，不适用 F 盘规则，§10.1 显式豁免）。
 * 量程【定稿口径 §5.1/§6.1/§7】：blur 0–60 默认 0；brightness 40–160 默认 100；opacity 0–90 默认 60
 * （不透明方向）；fps 5–30 默认 24；雾区 fog 0–100/blur 0–30/亮度偏移 -30…+30（OPEN-5 暂定）/opacity 0–100。
 */
@State(name = "DWallpaperSettings", storages = @Storage("dwallpaper.xml"))
public final class SettingsState implements PersistentStateComponent<SettingsState.State> {

    /** 持久化快照（白名单字段，public 供平台 XML 序列化）。 */
    public static final class State {
        public int version = 1;
        /** 【可见性迁移 2026-11-19】默认值从"几乎看不见"上调后的一次性迁移标记。 */
        public int visibilityRev = 0;
        /** 非持久化的迁移通知标记（loadState 置位，controller 消费后清零）。 */
        public transient boolean visibilityMigrated = false;
        public boolean wallpaperEnabled = true;
        public boolean manualPaused = false;
        public int fpsLimit = 24;
        public int blurPx = 0;
        public int brightnessPercent = 100;
        public int opacityPercent = 90;
        public boolean idlePauseEnabled = true;
        public int idleTimeoutSec = 60;
        public String activeFogPreset = "editor-clear-panels-mist";
        public Map<String, ZoneState> fogZones = new LinkedHashMap<>();
        public Map<String, PresetDto> customPresets = new LinkedHashMap<>();
        public String steamRootOverride = "";
        public List<String> extraRoots = new ArrayList<>();
        public String currentEntryId = "";
        public String currentEntryPath = "";

        public State() {
            // 默认预设「编辑器清透、面板轻雾」（§6.2）由 controller 在 init 时若空则填充
        }
    }

    /** 单区雾参数（XML 友好 bean）。 */
    public static final class ZoneState {
        public int fogIntensity;
        public int blurRadiusPx;
        public int brightnessOffsetPercent;
        public int opacityPercent;

        public ZoneState() {
        }

        public ZoneState(int fogIntensity, int blurRadiusPx, int brightnessOffsetPercent, int opacityPercent) {
            this.fogIntensity = fogIntensity;
            this.blurRadiusPx = blurRadiusPx;
            this.brightnessOffsetPercent = brightnessOffsetPercent;
            this.opacityPercent = opacityPercent;
        }
    }

    /** 自定义预设 DTO。 */
    public static final class PresetDto {
        public Map<String, ZoneState> zones = new LinkedHashMap<>();

        public PresetDto() {
        }

        public PresetDto(Map<String, ZoneState> zones) {
            this.zones = zones;
        }
    }

    private State state = new State();

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = clamp(loaded);
        // 【可见性迁移 2026-11-19】旧版默认不透明度 60 实测"画了但看不见"（暗视频
        // 47 × 60% × 编辑器 60% 底 ≈ 33 ≈ 主题底色 30）。用户配置里已持久化旧值，
        // 只改代码默认值不会生效 → 用一次性版本号强制上调并回写。
        if (state.visibilityRev < 5) {
            state.visibilityRev = 5;
            state.opacityPercent = 22;
            state.brightnessPercent = Math.min(AdjustmentPipeline.MAX_BRIGHTNESS_PERCENT, 140);
            // 【帧率上调 2026-11-19】显示已改成原生分层透明窗（IDEA 零重绘），
            // 之前为省 EDT 压到 15/24 的限制不再需要：默认 30fps，滑杆上限放到 60。
            state.fpsLimit = 30;
            state.visibilityMigrated = true;
        }
        if (state.visibilityRev < 4) {
            state.visibilityRev = 4;
            state.opacityPercent = 22;
            state.brightnessPercent = Math.min(AdjustmentPipeline.MAX_BRIGHTNESS_PERCENT, 140);
            // 【性能/流畅度 2026-11-19】上一版为省 CPU 把默认帧率降到 15，用户反馈
            // "壁纸太卡"。帧缓冲已改为复用环 + 1:1 blit，恢复 24fps 仍可承受。
            state.fpsLimit = 24;
            state.visibilityMigrated = true;
        }
        if (state.visibilityRev < 3) {
            state.visibilityRev = 3;
            // 【定稿：覆盖层强度】实测 55% 时"界面和代码看不见了"（文字只剩 45% 对比度），
            // 90% 直接糊死。22% 是"壁纸明确可见 + 代码清晰"的默认平衡点；
            // 滑杆 0–90 仍可自由调（亮度滑杆可再压暗画面）。
            state.opacityPercent = 22;
            state.brightnessPercent = Math.min(AdjustmentPipeline.MAX_BRIGHTNESS_PERCENT, 140);
            // 【性能 2026-11-19】默认 24fps 在 1080p/4K 视频上明显卡顿：
            // 解码 + 管线 + 整窗重绘的成本随帧率线性上升，15fps 足够"动感"且流畅。
            state.fpsLimit = 15;
            state.visibilityMigrated = true;
        }
    }

    /** 本次加载是否发生了可见性迁移（controller 据此回写配置并立即重推参数）。 */
    public boolean consumeVisibilityMigrated() {
        boolean v = state != null && state.visibilityMigrated;
        if (state != null) {
            state.visibilityMigrated = false;
        }
        return v;
    }

    public State current() {
        return state;
    }

    // ---------- 白名单钳制（AC-PST-2；纯函数供单测） ----------

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static State clamp(State s) {
        if (s == null) {
            return new State();
        }
        s.version = clamp(s.version, 1, 999);
        s.fpsLimit = clamp(s.fpsLimit, FrameRateController.MIN_FPS, FrameRateController.MAX_FPS);
        s.blurPx = clamp(s.blurPx, 0, AdjustmentPipeline.MAX_BLUR_PX);
        s.brightnessPercent = clamp(s.brightnessPercent,
                AdjustmentPipeline.MIN_BRIGHTNESS_PERCENT, AdjustmentPipeline.MAX_BRIGHTNESS_PERCENT);
        s.opacityPercent = clamp(s.opacityPercent, 0, AdjustmentPipeline.MAX_OPACITY_PERCENT);
        s.idleTimeoutSec = clamp(s.idleTimeoutSec, 5, 3600);
        if (s.activeFogPreset == null || s.activeFogPreset.isBlank()) {
            s.activeFogPreset = "editor-clear-panels-mist";
        }
        // 六区：只保留已知区名（未知键丢弃）+ 越界钳制
        Map<String, ZoneState> zones = new LinkedHashMap<>();
        for (FogZone z : FogZone.values()) {
            ZoneState zs = s.fogZones.get(z.name());
            if (zs == null) {
                zs = new ZoneState(0, 0, 0, 0);
            }
            zones.put(z.name(), new ZoneState(
                    clamp(zs.fogIntensity, 0, 100),
                    clamp(zs.blurRadiusPx, 0, 30),
                    clamp(zs.brightnessOffsetPercent, -30, 30),
                    clamp(zs.opacityPercent, 0, 100)));
        }
        s.fogZones = zones;
        if (s.customPresets == null) {
            s.customPresets = new LinkedHashMap<>();
        } else {
            Map<String, PresetDto> presets = new LinkedHashMap<>();
            for (Map.Entry<String, PresetDto> e : s.customPresets.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                Map<String, ZoneState> pz = new LinkedHashMap<>();
                for (FogZone z : FogZone.values()) {
                    ZoneState zs = e.getValue().zones.get(z.name());
                    if (zs == null) {
                        continue;
                    }
                    pz.put(z.name(), new ZoneState(
                            clamp(zs.fogIntensity, 0, 100),
                            clamp(zs.blurRadiusPx, 0, 30),
                            clamp(zs.brightnessOffsetPercent, -30, 30),
                            clamp(zs.opacityPercent, 0, 100)));
                }
                presets.put(e.getKey(), new PresetDto(pz));
            }
            s.customPresets = presets;
        }
        if (s.extraRoots == null) {
            s.extraRoots = new ArrayList<>();
        } else {
            s.extraRoots.removeIf(x -> x == null || x.isBlank());
        }
        if (s.steamRootOverride == null) {
            s.steamRootOverride = "";
        }
        if (s.currentEntryId == null) {
            s.currentEntryId = "";
        }
        if (s.currentEntryPath == null) {
            s.currentEntryPath = "";
        }
        return s;
    }
}
