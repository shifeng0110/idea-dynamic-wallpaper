package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameSource;

import java.util.Locale;

/**
 * 仅索引类型占位帧源（design.md §9.4 + 任务口径「type=scene 条目显示『暂不支持』」）：
 * pollFrame 恒 null、isDynamic=false、close 无操作；{@link #message()} 供面板状态行/徽标展示（t4 接线点）。
 */
public final class UnsupportedEntrySource implements FrameSource {

    private final String entryType;

    public UnsupportedEntrySource(String entryType) {
        this.entryType = entryType == null ? "unknown" : entryType;
    }

    /** 面板展示话术（固定口径，scene/application 仅索引不渲染，design.md §9.4/R10）。 */
    public static String messageFor(String entryType) {
        String t = entryType == null ? "unknown" : entryType.toLowerCase(Locale.ROOT);
        if ("scene".equals(t)) {
            return "暂不支持：scene 为 Wallpaper Engine 原生场景（仅索引）";
        }
        if ("application".equals(t)) {
            return "暂不支持：application 为 Wallpaper Engine 原生程序（仅索引）";
        }
        return "暂不支持（类型 " + t + "，仅索引）";
    }

    public String message() {
        return messageFor(entryType);
    }

    public String entryType() {
        return entryType;
    }

    @Override
    public Frame pollFrame() {
        return null;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public void close() {
        // 仅索引类型无资源
    }
}
