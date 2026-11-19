package com.dwallpaper.core;

import java.io.Closeable;

/**
 * 媒体帧源契约（design.md §2.3）：media 模块只产帧，core 绘制层只收帧。
 * 单槽队列语义：新帧覆盖旧帧，丢弃积压（design.md §4.3）。
 * 生命周期：构造/解析后调用 {@link #start()}（幂等），pollFrame 供泵取帧，close 释放。
 * 可选控制通道（default 空实现，动态源按需覆写；core 经契约调用，不反向 import media，§2.3）：
 * {@link #setRate(int)}（帧率/暂停状态机下发）、{@link #setRenderSize(int,int)}（目标像素尺寸推送）。
 */
public interface FrameSource extends Closeable {

    /** 取当前帧；无新帧返回 null。实现在 media 模块。 */
    Frame pollFrame();

    /** 是否动态帧源（静态图为 false；GIF/序列帧/JCEF OSR 为 true）。 */
    boolean isDynamic();

    /**
     * 启动解码/渲染（构造后必须显式调用；幂等安全网——注册表分派已 start 的源再次调用为 no-op）。
     * 默认空实现：无需线程的源（静态图、JCEF 于工厂内自启）不覆写。
     */
    default void start() {
    }

    /**
     * 帧率/暂停下发（FrameRateController 状态机 → 媒体源；§7）。
     * fps≤0 = 暂停（源挂起）；&gt;0 = 恢复并按源语义 clamp（JCEF 1..30）。默认空实现。
     */
    default void setRate(int fps) {
    }

    /**
     * 目标渲染像素尺寸推送（WallpaperSurface 实际尺寸 → JCEF OSR 渲染缓冲；§4.2 DPI 口径）。
     * 默认空实现。
     */
    default void setRenderSize(int width, int height) {
    }
}
