package com.dwallpaper.media;

/**
 * JCEF 浏览器绑定抽象（t5）：JcefOsrFrameSource 的可测边界 ——
 * 真实实现 {@link CefBrowserBinding}（反射承载 JCEF，design.md §9.5 纪律）；
 * 单测以假实现验证速率/暂停/生命周期语义，不依赖 CEF。
 * 与 t4 的对接点：t4 状态机（design.md §7：RUNNING 24fps / 失焦 8fps / 空闲 60s 暂停）经
 * JcefOsrFrameSource.setRate 驱动本接口；t4 未完成期间默认 24fps（接口桩，已标注）。
 */
public interface BrowserBinding {

    /** 构建 OSR browser 并加载 pageUrl（web=file://入口；video=http://127.0.0.1 wrapper）。 */
    void start(String pageUrl);

    /** dispose browser 并移除 handlers（design.md §9.3：切走/全局关 → CEF 进程回收，AC-MEDIA-6）。 */
    void stop();

    boolean isRunning();

    /** CefBrowser.setWindowlessFrameRate：运行时限帧（262 实测存在；≤30，design.md §9.3）。 */
    void setWindowlessFrameRate(int fps);

    /** CefBrowser.setWindowVisibility：暂停挂起 / 恢复（OSR 等价于 design.md §7 的 setVisible 挂起口径）。 */
    void setWindowVisibility(boolean visible);

    /** CefBrowser.wasResized：渲染尺寸推送（t4 以 WallpaperSurface 实际像素尺寸经 setRenderSize 接线）。 */
    void wasResized(int width, int height);

    /** 页面脚本注入（静音/暂停/恢复；音频默认静音 = 任务口径）。 */
    void runJavaScript(String script);

    /** web 条目禁导航（design.md §9.5 沙箱：页面源不可信、禁导航、无输入转发）。 */
    void disableNavigation();

    /** 全量静音（onLoadEnd 注入 mute 脚本；wrapper 自带 muted 属性）。 */
    javax.swing.JComponent osrUiComponent();

    void muteAudio();
}
