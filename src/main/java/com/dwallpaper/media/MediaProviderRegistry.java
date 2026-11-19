package com.dwallpaper.media;

import com.dwallpaper.core.FrameRateController;
import com.dwallpaper.core.FrameSource;
import com.dwallpaper.welibrary.WeEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 帧源注册表（design.md §9.1，t5 实现，全类型分派）：
 * <ul>
 *   <li>video/web → {@link JcefOsrFrameSource#create(WeEntry)}（JCEF 门控失败 → null + 降级日志 +
 *       UI 提示，回退 t4 能力；design.md §9.2）；</li>
 *   <li>scene/application → {@link UnsupportedEntrySource}（「暂不支持」，仅索引不渲染 §9.4）；</li>
 *   <li>其他/未知类型 + 路径直连（{@link #resolvePath}）→ 内容探测：
 *       视频文件 → JCEF；gif → t4 {@link GifFrameSource}（构造契约 {@code (Path)} + 显式 {@code start()}）；
 *       多图目录 → t4 {@link SequenceFrameSource}（自然序，默认 24fps）；单图 → t4 {@link StaticImageSource}。
 *       t4 实现域类仅作构造委托，契约变更由 t4 主导（此文件属 media 模块，t5 持有）。</li>
 * </ul>
 */
public final class MediaProviderRegistry {

    /** 按条目（type 归一化小写，welibrary 已归一；此处再防御一次）解析帧源。 */
    public FrameSource resolve(WeEntry entry) {
        String type = entry == null || entry.type() == null
                ? ""
                : entry.type().trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "video": {
                // 【2026-11-19】视频优先走纯 Java MP4 解码（CEF 对这些 H.264 文件报
                // v.error，见 Mp4FrameSource 类注释）；非 mp4（如 webm）才回退 JCEF。
                FrameSource mp4 = tryMp4(entry);
                if (mp4 != null) {
                    return mp4;
                }
                return JcefOsrFrameSource.create(entry);
            }
            case "web":
                return JcefOsrFrameSource.create(entry);
            case "scene":
            case "application":
                MediaLog.info("仅索引类型不渲染（design.md §9.4）："
                        + UnsupportedEntrySource.messageFor(type)
                        + "，id=" + (entry == null ? "?" : entry.id()));
                return new UnsupportedEntrySource(type);
            default:
                // 未知/其他类型（design.md §3.2 兜底口径）：按目录内容探测分派
                if (entry == null || entry.folderPath() == null || entry.folderPath().isBlank()) {
                    return null;
                }
                return resolvePath(Path.of(entry.folderPath()));
        }
    }

    /**
     * 路径直连解析（t4 WallpaperController.applyPath 契约）：目录（WE 条目/媒体目录）或文件。
     * 返回 null 表示无法解析（调用方降级提示），不抛异常。
     */
    public FrameSource resolvePath(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (Files.isDirectory(path)) {
                return resolveDirectory(path);
            }
            if (Files.isRegularFile(path)) {
                return resolveFile(path);
            }
            MediaLog.warn("resolvePath：路径不存在（降级）：" + path);
            return null;
        } catch (Throwable t) {
            MediaLog.warn("resolvePath 失败（降级）：" + path, t);
            return null;
        }
    }

    private FrameSource resolveDirectory(Path dir) {
        // ① project.json type 指定（t1 §3.2：Video/video 混用，归一化）→ JCEF 通道
        String declared = WeEntryResolver.readProjectStringField(dir, "type");
        String type = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
        if ("video".equals(type)) {
            FrameSource mp4 = tryMp4(syntheticEntry(dir, type));
            if (mp4 != null) {
                return mp4;
            }
            return JcefOsrFrameSource.create(syntheticEntry(dir, type));
        }
        if ("web".equals(type)) {
            return JcefOsrFrameSource.create(syntheticEntry(dir, type));
        }
        // ② 目录内含视频文件（无 project.json 或类型未指定）→ 视频（mp4 纯 Java 解码）
        if (containsVideoFile(dir)) {
            FrameSource mp4 = tryMp4(syntheticEntry(dir, "video"));
            if (mp4 != null) {
                return mp4;
            }
            return JcefOsrFrameSource.create(syntheticEntry(dir, "video"));
        }
        // ③ gif → t4 GifFrameSource（显式 start）
        Path gif = firstMatch(dir, ".gif");
        if (gif != null) {
            return startThreaded(new GifFrameSource(gif));
        }
        // ④ 多图目录 → t4 SequenceFrameSource（自然序，默认 24fps）
        List<Path> images = SequenceFrameSource.listImages(dir);
        if (images.size() >= 2) {
            return startThreaded(new SequenceFrameSource(images, FrameRateController.DEFAULT_RUNNING_FPS));
        }
        // ⑤ 单图 → t4 StaticImageSource（t9 F4：延迟后台解码，构造不再抛 IOException）
        if (images.size() == 1) {
            return startThreadedStatic(new StaticImageSource(images.get(0)));
        }
        MediaLog.warn("目录无可识别媒体（降级）：" + dir);
        return null;
    }

    private FrameSource resolveFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov")) {
            return createVideoSource(file);
        }
        if (name.endsWith(".webm")) {
            return JcefOsrFrameSource.createForMediaFile(file);
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return JcefOsrFrameSource.createForWebFile(file);
        }
        if (name.endsWith(".gif")) {
            return startThreaded(new GifFrameSource(file));
        }
        if (SequenceFrameSource.isImage(name)) {
            // t9 F4：延迟后台解码，构造不再抛 IOException；显式 start 保证解码触发
            return startThreadedStatic(new StaticImageSource(file));
        }
        MediaLog.warn("未知媒体类型（降级，接口桩不渲染）：" + file);
        return null;
    }

    /** 条目若指向 mp4 文件，构造纯 Java MP4 帧源；否则返回 null（回退 JCEF）。 */
    private FrameSource tryMp4(WeEntry entry) {
        if (entry == null) {
            return null;
        }
        try {
            Path media = mp4PathOf(entry);
            if (media == null) {
                return null;
            }
            return createVideoSource(media);
        } catch (Throwable t) {
            MediaLog.warn("MP4 帧源构造失败（回退 JCEF）：" + t);
            return null;
        }
    }

    /** 视频文件 → 优先系统 ffmpeg 原生解码（流畅），否则纯 Java JCodec。 */
    private FrameSource createVideoSource(Path media) {
        if (FfmpegFrameSource.isAvailable()) {
            FfmpegFrameSource s = new FfmpegFrameSource(media);
            s.start();
            MediaLog.info("视频走 ffmpeg 原生解码（流畅优先）：" + media.getFileName());
            return s;
        }
        Mp4FrameSource s = new Mp4FrameSource(media);
        s.start();
        MediaLog.info("未找到 ffmpeg，视频回退纯 Java 解码：" + media.getFileName());
        return s;
    }

    /** 解析条目指向的 mp4 路径（mediaPath 优先，其次 WeEntryResolver）。 */
    private Path mp4PathOf(WeEntry entry) {
        String media = entry.mediaPath();
        if (media != null && !media.isBlank()) {
            Path p = Path.of(media);
            if (Files.isRegularFile(p) && isMp4(p)) {
                return p;
            }
        }
        WeEntryResolver.MediaTarget target = WeEntryResolver.resolve(entry);
        if (target != null && target.path() != null
                && target.kind() == WeEntryResolver.MediaTarget.Kind.VIDEO
                && Files.isRegularFile(target.path()) && isMp4(target.path())) {
            return target.path();
        }
        return null;
    }

    private static boolean isMp4(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov");
    }

    /** t4 线程化帧源契约：构造后必须显式 start()（GifFrameSource/SequenceFrameSource）。 */
    private FrameSource startThreaded(FrameSource threadedSource) {
        if (threadedSource instanceof GifFrameSource gif) {
            gif.start();
        } else if (threadedSource instanceof SequenceFrameSource sequence) {
            sequence.start();
        }
        return threadedSource;
    }

    /** t9 F4：StaticImageSource 延迟解码——显式 start() 触发后台读取（pollFrame 亦有自动触发保底）。 */
    private FrameSource startThreadedStatic(FrameSource staticSource) {
        staticSource.start();
        return staticSource;
    }

    private static WeEntry syntheticEntry(Path dir, String type) {
        // t4 契约更新：WeEntry 已加 mediaPath（第 6 位，previewPath 之前）—— 供 GIF/序列帧/静态直连
        return new WeEntry("path:" + dir, dir.getFileName() == null ? dir.toString() : dir.getFileName().toString(),
                type, dir.toString(), dir.resolve("project.json").toString(),
                null, null, true, "manual-path");
    }

    private static boolean containsVideoFile(Path dir) {
        return firstMatch(dir, ".mp4") != null || firstMatch(dir, ".webm") != null;
    }

    /** 目录内首个（名称排序，不区分大小写）匹配扩展名的常规文件。 */
    private static Path firstMatch(Path dir, String extension) {
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .min(java.util.Comparator.comparing(
                            p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .orElse(null);
        } catch (IOException e) {
            MediaLog.warn("目录扫描失败（降级）：" + dir + " :: " + e);
            return null;
        }
    }
}
