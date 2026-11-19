package com.dwallpaper.core;

import java.awt.image.BufferedImage;
import java.awt.Component;

/**
 * 壁纸绘制适配（【可读性架构修正 2026-11-19】）：
 * Border 通过本接口取帧——普通区画清晰帧（壁纸不透明度），雾区画"模糊+调亮度"
 * 的雾化帧（雾镜语义移到内容之下，对齐 DSH backdrop-filter；GlassPane 蒙层停用）。
 * 由 WallpaperEngine 实现；fog 的 ZoneParams 实现 {@link FogZoneParamsLike} 桥接
 * 区参数（依赖方向：fog → core，core 不 import fog，§2.3）。
 */
public interface WallpaperPaint {

    /** 当前已调节清晰帧（无壁纸时 null）。 */
    BufferedImage sharpFrame();

    /** 壁纸不透明度 0-90（Surface 口径）。 */
    int opacityPercent();

    /** 雾化帧（模糊 blurPx + 亮度偏移 brightnessOffset 的缓存帧；无帧时 null）。 */
    BufferedImage frostedFrame(int blurPx, int brightnessOffsetPercent);

    /** 面板所属雾区的参数（面板中心不在任何雾区/无数据时 null → 画清晰帧）。 */
    FogZoneParamsLike zoneFor(Component panel);

    /** 【方案 E】视频/网页直显模式的面板底色（主题 Panel 色；null=画清晰帧路径）。 */
    default java.awt.Color scrimColor() {
        return null;
    }

    /** 底色不透明度（%）：视频直显时玻璃面板的可读性底。default 70。 */
    default int scrimAlphaPercent() {
        return 70;
    }

    /** 雾区参数桥接（fog.ZoneParams 实现本接口）。 */
    interface FogZoneParamsLike {
        int fogIntensity();

        int blurRadiusPx();

        int brightnessOffsetPercent();

        int opacityPercent();
    }
}
