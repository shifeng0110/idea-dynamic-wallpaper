package com.dwallpaper.welibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 扫描器（design.md §3.1–3.2）：枚举 projects\defaultprojects/myprojects/downloaded
 * （逐目录判存在，缺失跳过，AC-WL-4）+ 工坊 content/431960/*（不依赖安装根有效性）
 * + 手动兜底目录/文件（§3.1 额外 roots，条目合并按 folder 绝对路径去重）。
 * project.json 经 JsonCodec 宽松解析；type 先 toLowerCase() 再分派（t1 §3.2）；
 * 未知/缺失类型按文件探测兜底（gif/static/sequence）。单线程有界执行器运行（禁止 EDT，§3.2）。
 */
public final class WeScanner {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "webp");

    public record ScanInput(Path installRoot, List<Path> libraries, List<String> extraRoots) {
    }

    public record ScanResult(List<WeEntry> entries, String installRoot, int workshopCount) {
    }

    /** 全量扫描；调用方保证非 EDT（§3.2）。 */
    public ScanResult scan(ScanInput in) {
        LinkedHashMap<String, WeEntry> byFolder = new LinkedHashMap<>();
        List<Path> sourceDirs = new ArrayList<>();
        List<Path> extraFiles = new ArrayList<>();
        String root = null;
        int workshop = 0;

        if (in.installRoot() != null) {
            root = in.installRoot().toString();
            for (String sub : List.of("defaultprojects", "myprojects", "downloaded")) {
                Path d = in.installRoot().resolve("projects").resolve(sub);
                if (Files.isDirectory(d)) { // AC-WL-4：缺失容错
                    sourceDirs.add(d);
                }
            }
        }
        for (Path lib : in.libraries()) {
            for (Path wd : WeLocator.workshopDirs(lib)) {
                sourceDirs.add(wd);
                workshop++;
            }
        }
        for (String extra : in.extraRoots()) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            Path p = Path.of(extra.trim());
            if (Files.isDirectory(p)) {
                sourceDirs.add(p);
            } else if (Files.isRegularFile(p)) {
                extraFiles.add(p);
            }
        }

        for (Path dir : sourceDirs) {
            // 工坊形态（t1 §3.1/3.4）：sourceDir 本身即壁纸目录（project.json 直在其下）
            if (Files.isRegularFile(dir.resolve("project.json"))) {
                WeEntry self = buildEntry(dir, dir.getFileName().toString());
                byFolder.putIfAbsent(self.folderPath(), self);
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isDirectory).sorted().forEach(folder -> {
                    WeEntry e = buildEntry(folder, dir.getFileName().toString());
                    byFolder.putIfAbsent(e.folderPath(), e);
                });
            } catch (IOException ignored) {
                // 目录不可读 → 跳过
            }
        }
        for (Path f : extraFiles) {
            WeEntry e = singleFileEntry(f);
            byFolder.putIfAbsent(e.folderPath(), e);
        }

        return new ScanResult(new ArrayList<>(byFolder.values()), root, workshop);
    }

    /** 单目录 → 条目（project.json 宽松解析；无 json → 文件探测兜底）。 */
    static WeEntry buildEntry(Path folder, String sourceDir) {
        Path pj = folder.resolve("project.json");
        String title = folder.getFileName().toString();
        String id = title;
        String typeRaw = null;
        Path media = null;
        Path preview = null;

        if (Files.isRegularFile(pj)) {
            JsonCodec.Parsed p = JsonCodec.parseProject(pj);
            if (p != null) {
                if (p.title() != null) {
                    title = p.title();
                }
                typeRaw = p.typeRaw() == null ? null : p.typeRaw().toLowerCase(Locale.ROOT).trim();
                if (p.file() != null && !p.file().isBlank()) {
                    media = folder.resolve(p.file());
                }
                if (p.preview() != null && !p.preview().isBlank()) {
                    Path cand = folder.resolve(p.preview());
                    preview = Files.isRegularFile(cand) ? cand : null;
                }
            }
        }
        if (preview == null) {
            preview = firstImage(folder);
        }
        String type = normalizeType(typeRaw, media, folder);
        boolean supported = switch (type) {
            case "video", "web", "gif", "static", "sequence" -> true;
            default -> false; // scene/application/other 仅索引
        };
        return new WeEntry(id, title, type, folder.toString(), pj.toString(),
                media == null ? null : media.toString(),
                preview == null ? null : preview.toString(),
                supported, sourceDir);
    }

    private static WeEntry singleFileEntry(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        String type;
        if (lower.endsWith(".gif")) {
            type = "gif";
        } else if (isImageName(lower)) {
            type = "static";
        } else {
            type = "other";
        }
        return new WeEntry(name, name, type, file.getParent().toString(), file.toString(),
                file.toString(), file.toString(),
                type.equals("gif") || type.equals("static"), "manual");
    }

    /** 类型归一化：显式 type 优先；未知/缺失 → 文件探测兜底（§3.2）。 */
    static String normalizeType(String typeRaw, Path media, Path folder) {
        if (typeRaw != null && !typeRaw.isBlank()) {
            return switch (typeRaw) {
                case "video", "web", "scene", "application" -> typeRaw;
                case "image", "photo", "picture" -> probeType(media, folder);
                default -> "other";
            };
        }
        return probeType(media, folder);
    }

    private static String probeType(Path media, Path folder) {
        if (media != null && Files.isRegularFile(media)) {
            String lower = media.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".gif")) {
                return "gif";
            }
            if (isImageName(lower)) {
                return "static";
            }
            if (lower.endsWith(".mp4") || lower.endsWith(".webm")) {
                return "video";
            }
            if (lower.endsWith(".html")) {
                return "web";
            }
        }
        if (folder != null && countImages(folder) >= 2) {
            return "sequence"; // 目录轮播（任务口径 §4）
        }
        return "other";
    }

    static boolean isImageName(String lower) {
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    static int countImages(Path folder) {
        try (Stream<Path> s = Files.list(folder)) {
            return (int) s.filter(Files::isRegularFile)
                    .filter(f -> isImageName(f.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    static Path firstImage(Path folder) {
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> isImageName(f.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .filter(f -> !f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif"))
                    .sorted()
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 自然序（F17：委托 core.NaturalOrder 单一实现——数字段按长度安全比较，
     * 任意位数不溢出；此前 Long.parseLong 20 位数字段会抛 NumberFormatException 中断排序）。
     */
    public static Comparator<String> naturalOrder() {
        return com.dwallpaper.core.NaturalOrder.comparator();
    }
}
