package com.dwallpaper.welibrary;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IconLoader;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 库索引缓存服务（applicationService，design.md §3.3）：
 * 缓存文件 <cacheDir>/library-index.json（cacheDir 见 §10.2，本机经 -Didea-wallpaper.cache.dir 落 F 盘）；
 * 失效龄 >10 分钟后台重扫；「刷新库」强制全量；缓存损坏/版本不符视为无缓存。
 * 缩略图：96×54（预览取首帧），内存 LRU ≤200，懒加载 + 占位图，解码在后台线程（§3.2）。
 * 缓存只加速 UI 列表呈现，应用壁纸时逐项再校验（调用方负责）。
 */
public final class LibraryIndex {

    private static final Logger LOG = Logger.getInstance(LibraryIndex.class);
    private static final long CACHE_MAX_AGE_MS = 10 * 60_000L;
    private static final int THUMB_LIMIT = 200;
    private static final int THUMB_W = 96;
    private static final int THUMB_H = 54;

    private final List<WeEntry> entries = new ArrayList<>();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dwallpaper-scanner");
        t.setDaemon(true);
        return t;
    });
    private final Object thumbLock = new Object();
    private final LinkedHashMap<String, Icon> thumbs = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<String> pendingThumbs = ConcurrentHashMap.newKeySet();
    private volatile long lastScanMs;

    public synchronized List<WeEntry> snapshot() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long lastScanMs() {
        return lastScanMs;
    }

    public synchronized void replaceAll(List<WeEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
    }

    /** 面板打开入口（§3.3）：内存/缓存命中（<10min）直接交付；否则后台重扫。 */
    public void ensureLoaded(String steamRootOverride, List<String> extraRoots,
                             Consumer<List<WeEntry>> onReady) {
        List<WeEntry> current = snapshot();
        boolean fresh = System.currentTimeMillis() - lastScanMs < CACHE_MAX_AGE_MS;
        if (!current.isEmpty() && fresh) {
            onReady.accept(current);
            return;
        }
        if (current.isEmpty() && loadCache() && System.currentTimeMillis() - lastScanMs < CACHE_MAX_AGE_MS) {
            onReady.accept(snapshot());
            return;
        }
        rescan(steamRootOverride, extraRoots, onReady);
    }

    /** 强制全量重扫（「刷新库」/首扫）；单线程有界执行器（非 EDT），结果 invokeLater（§3.2）。 */
    public void rescan(String steamRootOverride, List<String> extraRoots, Consumer<List<WeEntry>> onReady) {
        List<String> extras = extraRoots == null ? List.of() : List.copyOf(extraRoots);
        String override = steamRootOverride == null ? "" : steamRootOverride;
        scanner.submit(() -> {
            try {
                WeLocator.SteamRoots roots = WeLocator.locate(override);
                Path installRoot = null;
                for (Path lib : roots.libraries()) {
                    Path r = WeLocator.installRootOf(lib);
                    if (r != null) {
                        installRoot = r;
                        break;
                    }
                }
                WeScanner.ScanResult result = new WeScanner().scan(
                        new WeScanner.ScanInput(installRoot, roots.libraries(), extras));
                replaceAll(result.entries());
                lastScanMs = System.currentTimeMillis();
                saveCache(result.entries());
                LOG.info("dwallpaper: library scan done: entries=" + result.entries().size()
                        + " installRoot=" + result.installRoot()
                        + " steamRoot=" + roots.steamRoot()
                        + " workshopDirs=" + result.workshopCount());
            } catch (Throwable t) {
                LOG.warn("dwallpaper: library scan failed", t);
            } finally {
                List<WeEntry> snap = snapshot();
                SwingUtilities.invokeLater(() -> onReady.accept(snap));
            }
        });
    }

    /** 缩略图懒加载（LRU ≤200）：命中同步回调；未命中后台解码（占位由调用方渲染）。 */
    public void thumbnail(WeEntry entry, Consumer<Icon> onReady) {
        String key = entry.previewPath() != null ? entry.previewPath()
                : entry.mediaPath() != null ? entry.mediaPath() : entry.folderPath();
        Icon cached;
        synchronized (thumbLock) {
            cached = thumbs.get(key);
        }
        if (cached != null) {
            onReady.accept(cached);
            return;
        }
        if (!pendingThumbs.add(key)) {
            return; // 已在解码，占位等待
        }
        scanner.submit(() -> {
            Icon icon = decodeThumb(entry);
            synchronized (thumbLock) {
                thumbs.put(key, icon);
                if (thumbs.size() > THUMB_LIMIT) {
                    Iterator<String> it = thumbs.keySet().iterator();
                    while (thumbs.size() > THUMB_LIMIT && it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
            pendingThumbs.remove(key);
            SwingUtilities.invokeLater(() -> onReady.accept(icon));
        });
    }

    private Icon decodeThumb(WeEntry entry) {
        try {
            Path src = entry.previewPath() != null ? Path.of(entry.previewPath())
                    : entry.mediaPath() != null ? Path.of(entry.mediaPath()) : null;
            if (src == null || !Files.isRegularFile(src)) {
                return placeholder();
            }
            BufferedImage img = readImage(src);
            if (img == null) {
                return placeholder();
            }
            Image scaled = img.getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Throwable t) {
            return placeholder();
        }
    }

    /** 读图；GIF 取首帧（§3.2）。 */
    static BufferedImage readImage(Path src) throws IOException {
        String lower = src.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gif")) {
            try (ImageInputStream in = ImageIO.createImageInputStream(src.toFile())) {
                Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReaders(in);
                if (!it.hasNext()) {
                    return null;
                }
                javax.imageio.ImageReader r = it.next();
                try {
                    r.setInput(in);
                    return r.read(0);
                } finally {
                    r.dispose();
                }
            }
        }
        return ImageIO.read(src.toFile());
    }

    private static Icon placeholder() {
        return IconLoader.getIcon("/icons/wallpaper.svg", LibraryIndex.class);
    }

    // ---------- 磁盘缓存（§3.3） ----------

    private synchronized boolean loadCache() {
        try {
            Path file = cacheDir().resolve("library-index.json");
            if (!Files.isRegularFile(file)) {
                return false;
            }
            JsonCodec.CacheDto dto = JsonCodec.parseCache(Files.readString(file));
            if (dto == null || dto.entries.isEmpty()) {
                return false;
            }
            List<WeEntry> list = new ArrayList<>();
            for (JsonCodec.EntryDto d : dto.entries) {
                list.add(new WeEntry(d.id, d.title, d.type, d.folderPath, d.projectJsonPath,
                        d.mediaPath, d.previewPath, d.supported, d.sourceDir));
            }
            entries.clear();
            entries.addAll(list);
            lastScanMs = dto.scannedAt;
            LOG.info("dwallpaper: library cache loaded: " + list.size() + " entries");
            return true;
        } catch (Throwable t) {
            LOG.warn("dwallpaper: library cache load failed (treated as no cache)", t);
            return false;
        }
    }

    private void saveCache(List<WeEntry> list) {
        try {
            JsonCodec.CacheDto dto = new JsonCodec.CacheDto();
            dto.scannedAt = lastScanMs;
            for (WeEntry e : list) {
                JsonCodec.EntryDto d = new JsonCodec.EntryDto();
                d.id = e.id();
                d.title = e.title();
                d.type = e.type();
                d.folderPath = e.folderPath();
                d.projectJsonPath = e.projectJsonPath();
                d.mediaPath = e.mediaPath();
                d.previewPath = e.previewPath();
                d.supported = e.supported();
                d.sourceDir = e.sourceDir();
                dto.entries.add(d);
            }
            Path file = cacheDir().resolve("library-index.json");
            Path tmp = file.resolveSibling("library-index.json.tmp");
            Files.writeString(tmp, JsonCodec.serializeCache(dto));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            LOG.warn("dwallpaper: library cache save failed", t);
        }
    }

    /** 运行时缓存目录（§10.2）：-Didea-wallpaper.cache.dir 落 F 盘；缺省随 IDE system 目录。 */
    public static Path cacheDir() throws IOException {
        String p = System.getProperty("idea-wallpaper.cache.dir");
        Path dir = p != null && !p.isBlank()
                ? Path.of(p)
                : Path.of(PathManager.getSystemPath(), "dwallpaper");
        Files.createDirectories(dir);
        return dir;
    }
}
