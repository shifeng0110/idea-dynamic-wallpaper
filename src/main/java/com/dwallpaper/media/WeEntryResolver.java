package com.dwallpaper.media;

import com.dwallpaper.welibrary.WeEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * WE 条目 → 媒体目标解析（design.md §9.3，t5）。任务口径：type=web 加载其 index.html / project.json
 * 指定入口；type=video（webm/mp4）供包装页播放。
 * <ul>
 *   <li>video：project.json 的 "file"（t1 §3.2：原样拼接）优先，回退目录扫描 *.mp4/*.webm（mp4 优先、名称排序稳定）；</li>
 *   <li>web：project.json "file" 以 .html 结尾优先 → index.html → 名称排序首个 *.html；</li>
 *   <li>解析失败返回 null（调用方降级日志、条目禁选）。</li>
 * </ul>
 */
public final class WeEntryResolver {

    /** 解析结果：kind=VIDEO/WEB；path=绝对化媒体/入口文件。 */
    public record MediaTarget(Kind kind, Path path) {
        public enum Kind { VIDEO, WEB }
    }

    private static final long MAX_PROJECT_JSON_BYTES = 256 * 1024;

    private WeEntryResolver() {
    }

    public static MediaTarget resolve(WeEntry entry) {
        if (entry == null || entry.folderPath() == null || entry.folderPath().isBlank()) {
            return null;
        }
        Path folder = Path.of(entry.folderPath());
        if (!Files.isDirectory(folder)) {
            return null;
        }
        String type = entry.type() == null ? "" : entry.type().trim().toLowerCase(Locale.ROOT);
        if ("video".equals(type)) {
            return resolveVideo(folder);
        }
        if ("web".equals(type)) {
            return resolveWeb(folder);
        }
        return null; // 其余类型由 MediaProviderRegistry 分派
    }

    private static MediaTarget resolveVideo(Path folder) {
        String file = readProjectFileField(folder);
        if (file != null && isVideoExtension(file)) {
            Path candidate = folder.resolve(file).normalize();
            if (Files.isRegularFile(candidate)) {
                return new MediaTarget(MediaTarget.Kind.VIDEO, candidate);
            }
        }
        Path scanned = scanFirst(folder, new String[]{".mp4", ".webm"});
        return scanned == null ? null : new MediaTarget(MediaTarget.Kind.VIDEO, scanned);
    }

    private static MediaTarget resolveWeb(Path folder) {
        String file = readProjectFileField(folder);
        if (file != null && file.toLowerCase(Locale.ROOT).endsWith(".html")) {
            Path candidate = folder.resolve(file).normalize();
            if (Files.isRegularFile(candidate)) {
                return new MediaTarget(MediaTarget.Kind.WEB, candidate);
            }
        }
        Path index = folder.resolve("index.html").normalize();
        if (Files.isRegularFile(index)) {
            return new MediaTarget(MediaTarget.Kind.WEB, index);
        }
        Path scanned = scanFirst(folder, new String[]{".html"});
        return scanned == null ? null : new MediaTarget(MediaTarget.Kind.WEB, scanned);
    }

    /** 读 project.json 的字符串字段（≤256KB；失败返回 null，不 throw）。供 registry 路径直连复用。 */
    public static String readProjectStringField(Path folder, String field) {
        Path json = folder.resolve("project.json");
        if (!Files.isRegularFile(json)) {
            return null;
        }
        try {
            long size = Files.size(json);
            if (size <= 0 || size > MAX_PROJECT_JSON_BYTES) {
                return null;
            }
            return JsonFieldScanner.extractString(Files.readString(json, StandardCharsets.UTF_8), field);
        } catch (IOException | RuntimeException e) {
            MediaLog.warn("project.json 读取/解析失败（按回退规则继续）：" + json + " :: " + e);
            return null;
        }
    }

    /** 读 project.json（≤256KB）并抽取 "file" 字段；失败返回 null（不 throw）。 */
    private static String readProjectFileField(Path folder) {
        return readProjectStringField(folder, "file");
    }

    /** 目录扫描：按扩展优先级（数组顺序）+ 文件名不区分大小写排序取首个常规文件。 */
    private static Path scanFirst(Path folder, String[] extensionsInPriorityOrder) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            MediaLog.warn("条目目录扫描失败（降级）：" + folder + " :: " + e);
            return null;
        }
        for (String ext : extensionsInPriorityOrder) {
            Path best = files.stream()
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext))
                    .min(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .orElse(null);
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean isVideoExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm");
    }
}
