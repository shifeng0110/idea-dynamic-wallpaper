package com.dwallpaper.core;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Window;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 可插拔挂载策略（design.md §4.1 三级回退链 S1→S2→S3）。
 * S1 的组件树探测以 runIde 实测定型（design.md OPEN-2）；前级失败自动落到后级，
 * 保证任何情况下「有图可看」。卸载必须严格逆序还原（design.md §11）。
 * 【F18 修复】surface 由 MountContext 工厂按窗口实例化（多窗口/多显示器每窗独立底图），
 * 每个新 surface 经 {@code surfaceRegistrar} 登记进引擎扇出列表；卸载时经 {@code surfaceUnregistrar} 注销。
 */
public interface MountStrategy {

    /** 探测该策略在给定窗口上是否可用（只读，不修改 UI 状态）。 */
    boolean probe(Window window);

    /** 安装；返回 false 表示失败，由回退链落到下一级。 */
    boolean install(MountContext context);

    /** 卸载：严格逆序还原所有被修改的 Swing/编辑器状态（opaque、配色、组件移除、surface 注销）。 */
    void uninstall();

    /** 安装上下文：surface 工厂（每窗新建）+ 登记/注销回调；project 仅 S2（编辑器透明化）需要。 */
    interface MountContext {
        /** 新建（或返回主）surface；S1 每窗调用一次。 */
        WallpaperSurface surface();

        /** 登记新 surface 进引擎扇出列表（F18）。 */
        void registerSurface(WallpaperSurface surface);

        /** 从引擎扇出列表注销 surface（F18 卸载逆序）。 */
        void unregisterSurface(WallpaperSurface surface);

        Window window();

        @Nullable
        Project project();

        /**
         * 【Border 方案 2026-11-19】登记一个装了 {@link WallpaperBorder} 的面板：
         * 引擎维护集合用于帧更新扇出重绘与卸载清理。default 空实现兼容旧调用方。
         */
        default void registerBorderPanel(JComponent panel) {
        }

        /** 注销 border 面板（卸载逆序）。default 空实现兼容旧调用方。 */
        default void unregisterBorderPanel(JComponent panel) {
        }

        /**
         * 【可读性架构 2026-11-19】壁纸绘制适配（清晰帧/雾化帧/区参数）。
         * 引擎实现；default null 兼容旧调用方（Border 装配跳过）。
         */
        default WallpaperPaint paint() {
            return null;
        }
    }
}
