package com.dwallpaper.fog;

import com.dwallpaper.core.WallpaperPaint;

/**
 * 单区雾参数（design.md §6.1）：
 * fogIntensity 0–100；blurRadiusPx 0–30（step 1）；brightnessOffsetPercent −30…+30（暂定量程，OPEN-5）；
 * opacityPercent 0–100。
 * 语义：mix(底色, 预模糊壁纸快照×亮度偏移LUT, fogIntensity/100)，再以整体 alpha=opacity/100 覆盖该区；
 * fogIntensity=0 或 opacity=0 → 该区层不绘制。
 * 【可读性架构 2026-11-19】实现 core.WallpaperPaint.FogZoneParamsLike——雾由面板
 * WallpaperBorder 在内容之下按区绘制（GlassPane 蒙层停用）。
 */
public record ZoneParams(int fogIntensity, int blurRadiusPx, int brightnessOffsetPercent, int opacityPercent)
        implements WallpaperPaint.FogZoneParamsLike {

    /** 层不绘制（清透）。 */
    public static final ZoneParams DISABLED = new ZoneParams(0, 0, 0, 0);
}
