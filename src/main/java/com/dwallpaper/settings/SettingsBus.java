package com.dwallpaper.settings;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 面板与各模块间的事件总线（design.md §2.3/§8.2）：纯监听器列表，无 EP。
 * 事件流：控件 → SettingsBus → 分发到对应模块；滑杆数值走 200ms 防抖（§5.2），开关类即时。
 * 实现在 t4 扩展（防抖、节流、生命周期绑定）。
 */
public final class SettingsBus {

    /** 设置变更监听器（core 的调节管线 / fog 的区参数等订阅；core 引用此 Listener 契约）。 */
    public interface Listener {
        void onSettingsChanged(SettingsState.State snapshot);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void fire(SettingsState.State snapshot) {
        for (Listener listener : listeners) {
            listener.onSettingsChanged(snapshot);
        }
    }
}
