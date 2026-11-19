package com.dwallpaper.core;

import java.awt.image.BufferedImage;

/**
 * 媒体层产出的单帧（design.md §2.3/§4.3）。
 * 数据流唯一入口：FrameSource → AdjustmentPipeline → WallpaperSurface（媒体层只产帧，绘制层只收帧）。
 */
public record Frame(BufferedImage image, long frameId, long timestampMs, int sourceWidth, int sourceHeight) {
}
