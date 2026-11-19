package com.dwallpaper.core;

import com.intellij.ide.ApplicationInitializedListenerJavaShim;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import javax.swing.SwingUtilities;

/**
 * t3 脚手架占位 → 【F2 修复】启动恢复接线（design.md §2.4 双保险之一）：
 * applicationInitializedListener 在 IDE 组件就绪后回调 → invokeLater 调用
 * settings.WallpaperController.ensureInit()，恢复 enabled/上次壁纸/fps/滑杆/雾镜，
 * 并自动触发 -Ddwallpaper.selftest 自测。
 * 纪律说明：core 不直接 import settings（§2.3 依赖方向）—— 经反射软引用获取服务
 * （类探测风格，与 S3 的 IdeBackgroundUtil 反射先例一致）；headless/命令行守卫 + 全 try-catch。
 */
public final class WallpaperBootstrap extends ApplicationInitializedListenerJavaShim {

    private static final Logger LOG = Logger.getInstance(WallpaperBootstrap.class);
    private static final String CONTROLLER_CLASS = "com.dwallpaper.settings.WallpaperController";

    @Override
    public void componentsInitialized() {
        try {
            Application app = ApplicationManager.getApplication();
            if (app == null || app.isHeadlessEnvironment() || app.isCommandLine()) {
                LOG.info("dwallpaper: bootstrap skipped (headless/command-line environment)");
                return;
            }
            SwingUtilities.invokeLater(() -> {
                try {
                    Class<?> controller = Class.forName(CONTROLLER_CLASS, true,
                            WallpaperBootstrap.class.getClassLoader());
                    Object svc = app.getService(controller);
                    svc.getClass().getMethod("ensureInit").invoke(svc); // 启动恢复接线（F2）
                    LOG.info("dwallpaper: bootstrap -> controller.ensureInit() dispatched");
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: bootstrap ensureInit failed", t);
                }
            });
        } catch (Throwable t) {
            LOG.warn("dwallpaper: bootstrap init failed", t);
        }
    }
}
