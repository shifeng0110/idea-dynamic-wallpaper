package com.dwallpaper.welibrary;

import com.intellij.openapi.diagnostic.Logger;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Steam/WE 安装根定位（design.md §3.1，严格按 t1 §3.1 已验证算法）：
 * 设置覆盖 → 注册表 HKCU\Software\Valve\Steam SteamPath（JNA Advapi32Util，异常回退 reg query 子进程）
 * → 默认路径表；libraryfolders.vdf 多库扫描（"path" 条目）；安装判据双布局认标记文件不认 exe 单点
 * （顶层 wallpaper32/64.exe 或 distribution\version.json），E:\Steam 残留不得命中（AC-WL-2）；
 * 工坊目录只认真正会被扫描的 <库>\steamapps\workshop\content\431960（AC-WL-4 容错）。
 */
public final class WeLocator {

    public static final String WE_APPID = "431960";

    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
    private static final Pattern REG_QUERY_VALUE =
            Pattern.compile("REG_SZ\\s+(\\S.*)", Pattern.CASE_INSENSITIVE);
    private static final Logger LOG = Logger.getInstance(WeLocator.class);

    /** 定位结果：Steam 根（可能 null）+ 全部库路径。 */
    public record SteamRoots(Path steamRoot, List<Path> libraries) {
    }

    private WeLocator() {
    }

    /** 定位链入口（短路式，命中即停）。overrideRoot 允许为空。 */
    public static SteamRoots locate(String overrideRoot) {
        Path steam = null;
        if (overrideRoot != null && !overrideRoot.isBlank()) {
            Path p = Path.of(overrideRoot.trim());
            if (Files.isDirectory(p)) {
                steam = p;
            }
        }
        if (steam == null) {
            steam = registrySteamPath();
        }
        if (steam == null) {
            steam = defaultSteamPath();
        }
        return new SteamRoots(steam, steam == null ? List.of() : parseLibraries(steam));
    }

    /** ① JNA 注册表读取；异常回退 ② reg query 子进程（设计 §3.1）。 */
    static Path registrySteamPath() {
        try {
            String v = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam", "SteamPath");
            if (v != null && !v.isBlank()) {
                return Path.of(v.replace('/', '\\'));
            }
        } catch (Throwable ignored) {
            // JNA 失败 → reg query
        }
        return regQuerySteamPath();
    }

    private static Path regQuerySteamPath() {
        Process p = null;
        try {
            p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam",
                    "/v", "SteamPath").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // F15：waitFor 带 5s 超时 + 超时 destroyForcibly（防 reg 挂起卡死单线程扫描 executor）
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOG.warn("dwallpaper: reg query timed out (5s), destroyed; fallback to defaults");
                return null;
            }
            Matcher m = REG_QUERY_VALUE.matcher(out);
            if (m.find()) {
                return Path.of(m.group(1).trim().replace('/', '\\'));
            }
        } catch (Throwable ignored) {
            // 回退默认路径表
        } finally {
            if (p != null) {
                p.destroyForcibly(); // 双保险：无论成功/失败/异常都回收子进程
            }
        }
        return null;
    }

    /** ③ 默认路径表（C:\Program Files (x86)\Steam、C:\Program Files\Steam、<盘>:\Steam）。 */
    static Path defaultSteamPath() {
        List<String> candidates = new ArrayList<>(List.of(
                "C:\\Program Files (x86)\\Steam",
                "C:\\Program Files\\Steam"));
        for (char c = 'C'; c <= 'Z'; c++) {
            candidates.add(c + ":\\Steam");
        }
        for (String s : candidates) {
            Path p = Path.of(s);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    /** libraryfolders.vdf 多库解析：逐行匹配 "path"\s+"..."；首库=Steam 根自身；按绝对路径去重。 */
    public static List<Path> parseLibraries(Path steamRoot) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.add(steamRoot);
        Path vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
        if (Files.isRegularFile(vdf)) {
            try {
                String text = Files.readString(vdf, StandardCharsets.UTF_8);
                Matcher m = VDF_PATH.matcher(text);
                while (m.find()) {
                    String raw = m.group(1).replace("\\\\", "\\");
                    Path lib = Path.of(raw);
                    if (Files.isDirectory(lib)) {
                        out.add(lib);
                    }
                }
            } catch (IOException ignored) {
                // vdf 读取失败 → 仅 Steam 根
            }
        }
        return new ArrayList<>(out);
    }

    /** 双布局安装根判据（认标记文件不认 exe 单点）；非安装目录返回 null（残留不得命中，AC-WL-2）。 */
    public static Path installRootOf(Path library) {
        Path dir = library.resolve("steamapps").resolve("common").resolve("wallpaper_engine");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        boolean classic = Files.isRegularFile(dir.resolve("wallpaper32.exe"))
                || Files.isRegularFile(dir.resolve("wallpaper64.exe"));
        boolean dist = Files.isRegularFile(dir.resolve("distribution").resolve("version.json"));
        return (classic || dist) ? dir : null;
    }

    /** 工坊内容目录（不依赖安装根有效性）；缺失 → 空表（AC-WL-4）。 */
    public static List<Path> workshopDirs(Path library) {
        Path content = library.resolve("steamapps").resolve("workshop")
                .resolve("content").resolve(WE_APPID);
        if (!Files.isDirectory(content)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(content)) {
            s.filter(Files::isDirectory).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // 不可读 → 空
        }
        return out;
    }
}
