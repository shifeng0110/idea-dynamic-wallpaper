package com.dwallpaper.settings;

import com.dwallpaper.core.FrameRateController;
import com.dwallpaper.core.FrameSource;
import com.dwallpaper.core.SelfTestRunner;
import com.dwallpaper.core.WallpaperEngine;
import com.dwallpaper.core.WallpaperPaint;
import com.dwallpaper.fog.FogOverlayManager;
import com.dwallpaper.fog.FogPresetStore;
import com.dwallpaper.fog.FogZone;
import com.dwallpaper.fog.ZoneParams;
import com.dwallpaper.media.MediaProviderRegistry;
import com.dwallpaper.welibrary.LibraryIndex;
import com.dwallpaper.welibrary.WeEntry;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 装配点（design.md §2.3：settings 是唯一装配点）：面板/持久化 → 驱动 core 引擎、
 * welibrary 索引、media 帧源解析、fog 雾镜挂载。所有 UI 事件经此分发；滑杆走 200ms 防抖
 * （§5.2），开关类即时。启动恢复：全局开关 + 上次壁纸（bootstrap 调 init，§2.4 双保险之一）。
 */
public final class WallpaperController {

    private static final Logger LOG = Logger.getInstance(WallpaperController.class);
    public static final int SLIDER_DEBOUNCE_MS = 200;

    private final SettingsBus bus = new SettingsBus();
    private final MediaProviderRegistry registry = new MediaProviderRegistry();
    private final FogOverlayManager fogManager = new FogOverlayManager();
    private final AtomicReference<int[]> pendingParams = new AtomicReference<>(new int[]{0, 100, 60});
    private final DebouncerShim paramDebouncer = new DebouncerShim();
    private volatile boolean initialized;
    private volatile String lastStatusMessage = "";

    private WallpaperEngine engine;
    private LibraryIndex library;
    private SettingsState settings;

    public SettingsBus bus() {
        return bus;
    }

    public LibraryIndex library() {
        ensureInit();
        return library;
    }

    public SettingsState settings() {
        ensureInit();
        return settings;
    }

    /** 启动入口（WallpaperBootstrap.applicationInitializedListener → 此处，§2.4）。 */
    public synchronized void ensureInit() {
        if (initialized) {
            return;
        }
        initialized = true;
        engine = ApplicationManager.getApplication().getService(WallpaperEngine.class);
        library = ApplicationManager.getApplication().getService(LibraryIndex.class);
        settings = ApplicationManager.getApplication().getService(SettingsState.class);
        SettingsState.State st = settings.current();
        // 默认六区（首启无值 → 默认预设「编辑器清透、面板轻雾」，§6.2）
        if (st.fogZones.isEmpty()) {
            applyPresetState(FogPresetStore.PRESET_DEFAULT);
        }
        // 引擎装配：fog 钩子（fog → core 单向；core 不 import fog，经钩子反转调用）
        engine.setFogInstaller(this::installFog);
        engine.setFogUninstaller(fogManager::uninstall);
        engine.setSnapshotRefreshHook(fogManager::reapplyParams); // F13 配套：首帧快照刷新→雾镜缓存失效
        fogManager.configure(
                () -> ProjectManager.getInstance().getOpenProjects(),
                engine,
                this::zoneParams);
        // 【可读性架构 2026-11-19】面板→雾区解析注入引擎（WallpaperBorder 经此判定
        // 面板是否处于雾区，雾化帧在内容之下绘制）。
        engine.setZoneForProvider(this::zoneFor);
        // 恢复参数与帧率
        engine.noteParams(st.blurPx, st.brightnessPercent);
        engine.setParams(st.blurPx, st.brightnessPercent, st.opacityPercent);
        FrameRateController rate = engine.rateController();
        rate.setRunningFps(st.fpsLimit);
        rate.setIdlePauseEnabled(st.idlePauseEnabled);
        rate.setIdleTimeoutSec(st.idleTimeoutSec);
        rate.setManualPaused(st.manualPaused);
        // 恢复上次启用状态（§2.4 双保险之一）
        if (st.wallpaperEnabled) {
            engine.enable();
        }
        // 恢复上次壁纸（【启动过早初始化修复 2026-11-19】延迟到 IDE 加载完成）：
        // 之前恢复路径在 CONFIGURATION_STORE_INITIALIZED 极早期同步触发 JBCefApp
        // 静态初始化（平台 LoadingState 违规，CEF 基础设施在坏状态创建）。等
        // COMPONENTS_LOADED 后再应用；用户手动点面板的路径不受影响。
        String path = st.currentEntryPath;
        if (path != null && !path.isBlank()) {
            String entryId = st.currentEntryId;
            final int[] tries = {0};
            javax.swing.Timer defer = new javax.swing.Timer(1000, e -> {
                javax.swing.Timer self = (javax.swing.Timer) e.getSource();
                tries[0]++;
                boolean loaded;
                try {
                    loaded = com.intellij.diagnostic.LoadingState.COMPONENTS_LOADED.isOccurred();
                } catch (Throwable t) {
                    loaded = true; // 状态 API 不可用（极老平台）→ 直接恢复
                }
                if (!loaded && tries[0] < 30) {
                    return; // 继续等下一秒
                }
                if (!loaded) {
                    LOG.warn("dwallpaper: restore deferred gave up waiting for COMPONENTS_LOADED");
                }
                self.stop();
                library.ensureLoaded(st.steamRootOverride, st.extraRoots, entries -> {
                    WeEntry match = null;
                    for (WeEntry e2 : entries) {
                        if (e2.id() != null && e2.id().equals(entryId)) {
                            match = e2;
                            break;
                        }
                    }
                    if (match != null && match.supported()) {
                        applyEntry(match);
                    } else {
                        applyPath(Path.of(path));
                    }
                });
            });
            defer.setRepeats(true);
            defer.start();
        }
        // runIde 自测（-Ddwallpaper.selftest=true）
        if (Boolean.getBoolean("dwallpaper.selftest")) {
            SelfTestRunner.maybeRun(engine);
        }
        LOG.info("dwallpaper: controller initialized (enabled=" + st.wallpaperEnabled + ")");
    }

    // ---------- 全局开关 / 播放 / 帧率（即时生效） ----------

    public void setGlobalEnabled(boolean on) {
        ensureInit();
        settings.current().wallpaperEnabled = on;
        if (on) {
            engine.enable();
        } else {
            engine.disable();
        }
        fire();
    }

    public void setManualPaused(boolean paused) {
        ensureInit();
        settings.current().manualPaused = paused;
        engine.rateController().setManualPaused(paused);
        fire();
    }

    public void setFpsLimit(int fps) {
        ensureInit();
        settings.current().fpsLimit = FrameRateController.clampFps(fps);
        engine.rateController().setRunningFps(settings.current().fpsLimit);
        fire();
    }
    public void setIdlePauseEnabled(boolean enabled) {
        ensureInit();
        settings.current().idlePauseEnabled = enabled;
        engine.rateController().setIdlePauseEnabled(enabled);
        fire();
    }

    // ---------- 三滑杆（防抖 200ms，§5.2；标签即时更新由面板负责） ----------

    public void applySliderParams(int blurPx, int brightnessPercent, int opacityPercent) {
        ensureInit();
        pendingParams.set(new int[]{blurPx, brightnessPercent, opacityPercent});
        paramDebouncer.ping();
    }

    private void applyPendingParams() {
        int[] p = pendingParams.get();
        SettingsState.State st = settings.current();
        st.blurPx = p[0];
        st.brightnessPercent = p[1];
        st.opacityPercent = p[2];
        engine.noteParams(p[0], p[1]);
        engine.setParams(p[0], p[1], p[2]);
        fire();
    }

    // ---------- 壁纸应用 ----------

    /**
     * 库条目应用（双击/回车，§8.2）。
     * 【F1 修复】删除 video/web 预拒绝——registry.resolve 已分派 t5 JCEF 通道（video/web →
     * JcefOsrFrameSource.create，门控失败返回 null 走既有降级路径）；scene/application 保持拒绝（仅索引）。
     */
    public void applyEntry(@Nullable WeEntry entry) {
        ensureInit();
        if (entry == null) {
            return;
        }
        String type = entry.type() == null ? "" : entry.type();
        if (!entry.supported() || type.equals("scene") || type.equals("application")) {
            lastStatusMessage = "该类型仅索引不支持渲染：" + type;
            LOG.info("dwallpaper: apply rejected (index-only): " + entry.id() + " type=" + type);
            fire();
            return;
        }
        FrameSource fs = registry.resolve(entry); // video/web → JCEF（t5）；gif/sequence/static → t4 域
        if (fs == null) {
            // video/web 无 JCEF / 媒体缺失 → 既有降级路径（禁选提示，回退 GIF/序列/静态）
            lastStatusMessage = "无法解析媒体：" + entry.title()
                    + (type.equals("video") || type.equals("web") ? "（JCEF 不可用，已降级）" : "");
            LOG.info("dwallpaper: apply degraded (resolve null): " + entry.id() + " type=" + type);
            fire();
            return;
        }
        engine.applyGlassPass(); // 【玻璃板补装】应用壁纸前抓一遍懒创建组件（工具窗/新面板）
        engine.setFrameSource(fs); // F13：引擎在首个 prepared 帧自动刷新雾镜静态快照
        settings.current().currentEntryId = entry.id() == null ? "" : entry.id();
        settings.current().currentEntryPath =
                entry.mediaPath() != null ? entry.mediaPath() : entry.folderPath();
        fogManager.reapplyParams();
        lastStatusMessage = "已应用：" + entry.title() + " (" + type + ")";
        fire();
    }

    /** 路径直连应用（手动目录/恢复兜底）。 */
    public void applyPath(Path path) {
        ensureInit();
        FrameSource fs = registry.resolvePath(path);
        if (fs == null) {
            lastStatusMessage = "无法解析媒体：" + path;
            fire();
            return;
        }
        engine.applyGlassPass(); // 【玻璃板补装】路径直连应用同理
        engine.setFrameSource(fs); // F13：引擎在首个 prepared 帧自动刷新雾镜静态快照
        settings.current().currentEntryPath = path.toString();
        fogManager.reapplyParams();
        lastStatusMessage = "已应用：" + path;
        fire();
    }

    // ---------- 库 ----------

    public void ensureLibraryLoaded(Consumer<List<WeEntry>> onReady) {
        ensureInit();
        SettingsState.State st = settings.current();
        library.ensureLoaded(st.steamRootOverride, st.extraRoots, onReady);
    }

    public void refreshLibrary(Consumer<List<WeEntry>> onReady) {
        ensureInit();
        SettingsState.State st = settings.current();
        library.rescan(st.steamRootOverride, st.extraRoots, onReady);
    }

    // ---------- 六区雾镜 ----------

    /** 当前六区参数（fog 层供给）。 */
    public Map<FogZone, ZoneParams> zoneParams() {
        ensureInit();
        Map<FogZone, ZoneParams> out = new LinkedHashMap<>();
        SettingsState.State st = settings.current();
        for (FogZone z : FogZone.values()) {
            SettingsState.ZoneState zs = st.fogZones.get(z.name());
            if (zs == null) {
                zs = new SettingsState.ZoneState(0, 0, 0, 0);
            }
            out.put(z, new ZoneParams(zs.fogIntensity, zs.blurRadiusPx, zs.brightnessOffsetPercent, zs.opacityPercent));
        }
        return out;
    }

    /**
     * 【可读性架构 2026-11-19】面板→雾区参数解析（WallpaperPaint.zoneFor 实现）：
     * 面板中心转窗口坐标 → 命中 FogOverlayManager 缓存的区矩形 → 返回该区参数。
     * 不在任何区/无窗口 → null（Border 画清晰帧）。
     */
    public WallpaperPaint.FogZoneParamsLike zoneFor(javax.swing.JComponent panel) {
        if (panel == null || !panel.isShowing()) {
            return null;
        }
        java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(panel);
        if (w == null) {
            return null;
        }
        var rects = fogManager.cachedZones(w);
        if (rects == null || rects.isEmpty()) {
            return null;
        }
        java.awt.Point ctr = new java.awt.Point(panel.getWidth() / 2, panel.getHeight() / 2);
        java.awt.Point inWin = javax.swing.SwingUtilities.convertPoint(panel, ctr, w);
        for (var en : rects.entrySet()) {
            if (en.getValue() != null && en.getValue().contains(inWin)) {
                ZoneParams zp = zoneParams().get(en.getKey());
                return zp; // ZoneParams 已实现 FogZoneParamsLike
            }
        }
        return null;
    }

    /** 单区参数更新（网格编辑，即时生效，AC-FOG-1）。 */
    public void updateZoneParams(FogZone zone, ZoneParams p) {
        ensureInit();
        settings.current().fogZones.put(zone.name(),
                new SettingsState.ZoneState(p.fogIntensity(), p.blurRadiusPx(), p.brightnessOffsetPercent(), p.opacityPercent()));
        settings.current().activeFogPreset = "custom";        fogManager.reapplyParams();
        fire();
    }

    /** 应用预设（内置或自定义，AC-FOG-2）。 */
    public void applyPreset(String presetId) {
        ensureInit();
        applyPresetState(presetId);
        fogManager.reapplyParams();
        fire();
    }

    private void applyPresetState(String presetId) {
        SettingsState.State st = settings.current();
        if (FogPresetStore.builtinIds().contains(presetId)) {
            Map<FogZone, com.dwallpaper.fog.ZoneParams> src = FogPresetStore.builtin(presetId);
            Map<String, SettingsState.ZoneState> zones = new LinkedHashMap<>();
            src.forEach((z, p) -> zones.put(z.name(), new SettingsState.ZoneState(
                    p.fogIntensity(), p.blurRadiusPx(), p.brightnessOffsetPercent(), p.opacityPercent())));
            st.fogZones = zones;
            st.activeFogPreset = presetId;
        } else {
            SettingsState.PresetDto dto = st.customPresets.get(presetId);
            if (dto != null) {
                Map<String, SettingsState.ZoneState> zones = new LinkedHashMap<>();
                dto.zones.forEach((k, v) -> zones.put(k, new SettingsState.ZoneState(
                        v.fogIntensity, v.blurRadiusPx, v.brightnessOffsetPercent, v.opacityPercent)));
                st.fogZones = zones;
                st.activeFogPreset = presetId;
            }
        }
    }

    /** 另存自定义预设（命名 + 列表管理，§6.2）。 */
    public void saveCustomPreset(String name) {
        ensureInit();
        if (name == null || name.isBlank()) {
            return;
        }
        SettingsState.State st = settings.current();
        Map<String, SettingsState.ZoneState> zones = new LinkedHashMap<>();
        st.fogZones.forEach((k, v) -> zones.put(k, new SettingsState.ZoneState(
                v.fogIntensity, v.blurRadiusPx, v.brightnessOffsetPercent, v.opacityPercent)));
        st.customPresets.put(name, new SettingsState.PresetDto(zones));
        st.activeFogPreset = name;
        fire();
        LOG.info("dwallpaper: custom preset saved: " + name);
    }

    public List<String> presetIds() {
        ensureInit();
        List<String> ids = new java.util.ArrayList<>(FogPresetStore.builtinIds());
        ids.addAll(settings.current().customPresets.keySet());
        return ids;
    }

    // ---------- 状态行（§8.2） ----------

    public String statusLine() {
        ensureInit();
        String base = engine.status();
        return lastStatusMessage.isEmpty() ? base : lastStatusMessage + "｜" + base;
    }

    private void fire() {
        try {
            bus.fire(settings.current());
        } catch (Throwable t) {
            LOG.warn("dwallpaper: bus fire failed", t);
        }
    }

    private void installFog() {
        fogManager.install();
    }

    /** 200ms 防抖（design §5.2）—— core.Debouncer 在 core 包，settings→core 依赖允许。 */
    private final class DebouncerShim {
        private final com.dwallpaper.core.Debouncer delegate =
                new com.dwallpaper.core.Debouncer(SLIDER_DEBOUNCE_MS, () -> SwingUtilities.invokeLater(this::run));
        private long lastPing;

        void ping() {
            lastPing = System.currentTimeMillis();
            delegate.ping();
        }

        void run() {
            applyPendingParams();
        }
    }

    /** Consumer 别名（java.util.function.Consumer 与平台 consumer 混用时的可读性占位）。 */
    public interface Consumer<T> extends java.util.function.Consumer<T> {
    }
}
