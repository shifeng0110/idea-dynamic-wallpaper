package com.dwallpaper.core;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * S3 静态兜底（design.md §4.1 第三级）：S1 探测失败（或保守模式）→
 * 经内置机制 idea.background.editor（EDITOR_PROP）写入当前帧快照单帧图，保证「有图可看」。
 * 【F3 修复】PNG 编码+写盘全部在后台单线程（仅保留最新帧、≥1s 节流），EDT 仅写
 * PropertiesComponent + repaintAllWindows（零编码零 IO）；仍使用 ≤512 快照可再降载（引擎侧可选投递）。
 * 纪律（R7）：IdeBackgroundUtil / PropertiesComponent 均 impl/ide.util 内部 API →
 * 全反射调用（类探测 + try/catch），失败静默跳过整级。
 */
public final class StaticBackgroundFallbackStrategy implements MountStrategy {

    private static final Logger LOG = Logger.getInstance(StaticBackgroundFallbackStrategy.class);
    private static final long MIN_WRITE_INTERVAL_MS = 1000;

    private static final String BG_UTIL_CLASS = "com.intellij.openapi.wm.impl.IdeBackgroundUtil";
    private static final String PROPS_CLASS = "com.intellij.ide.util.PropertiesComponent";

    private volatile boolean installed;
    private volatile boolean active; // 当前是否已写 EDITOR_PROP
    private volatile Object propertiesComponent;
    private volatile String editorProp;
    private volatile Method repaintAll;

    // F3：后台单线程写入器（仅最新帧 + 节流）
    private final AtomicReference<BufferedImage> pendingFrame = new AtomicReference<>();
    private final AtomicLong lastWriteMs = new AtomicLong();
    private Thread writerThread;
    private volatile boolean writerStopped;

    @Override
    public boolean probe(Window window) {
        try {
            Class.forName(BG_UTIL_CLASS);
            Class<?> props = Class.forName(PROPS_CLASS);
            Method getInstance = props.getMethod("getInstance");
            propertiesComponent = getInstance.invoke(null);
            editorProp = (String) Class.forName(BG_UTIL_CLASS).getField("EDITOR_PROP").get(null);
            repaintAll = Class.forName(BG_UTIL_CLASS).getMethod("repaintAllWindows");
            LOG.info("dwallpaper: S3 probe ok (IdeBackgroundUtil + PropertiesComponent found)");
            return propertiesComponent != null && editorProp != null;
        } catch (Throwable t) {
            LOG.info("dwallpaper: S3 probe failed: " + t);
            return false;
        }
    }

    @Override
    public boolean install(MountContext context) {
        if (propertiesComponent == null && !probe(context.window())) {
            return false;
        }
        installed = true;
        active = false;
        writerStopped = false;
        if (writerThread == null) {
            writerThread = new Thread(this::writerLoop, "dwallpaper-s3-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }
        LOG.info("dwallpaper: S3 static fallback installed (background writer)");
        return true;
    }

    /** S3 生效期间由引擎投递当前帧（EDT 调用也安全：本方法零编码零 IO，仅入队最新帧）。 */
    public void showFrame(BufferedImage frame) {
        if (!installed || frame == null) {
            return;
        }
        pendingFrame.set(frame); // 单槽覆盖，仅保留最新
    }

    /** F3：后台写循环——取最新帧、≥1s 节流；PNG 编码/写盘全在本线程。 */
    private void writerLoop() {
        while (!writerStopped) {
            BufferedImage frame = pendingFrame.getAndSet(null);
            long now = System.currentTimeMillis();
            boolean throttled = now - lastWriteMs.get() < MIN_WRITE_INTERVAL_MS;
            if (frame != null && !throttled) {
                try {
                    Path file = frameFile();
                    ImageIO.write(frame, "png", file.toFile()); // 后台线程：编码+IO
                    String spec = file + ",100,PLAIN";
                    Method setValue = propertiesComponent.getClass()
                            .getMethod("setValue", String.class, String.class);
                    setValue.invoke(propertiesComponent, editorProp, spec);
                    active = true;
                    repaintAll.invoke(null);
                    lastWriteMs.set(System.currentTimeMillis());
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: S3 writer failed", t);
                }
            }
            try {
                Thread.sleep(frame != null && !throttled ? 50 : 250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void uninstall() {
        if (!installed) {
            return;
        }
        installed = false;
        try {
            if (active && propertiesComponent != null && editorProp != null) {
                Method unset = propertiesComponent.getClass().getMethod("unsetValue", String.class);
                unset.invoke(propertiesComponent, editorProp);
                repaintAll.invoke(null);
                active = false;
            }
            Files.deleteIfExists(frameFile());
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S3 uninstall issue", t);
        }
        pendingFrame.set(null);
    }

    /** F8 配套：IDE 退出链调用（停后台写线程）。 */
    public void shutdownWriter() {
        writerStopped = true;
        if (writerThread != null) {
            writerThread.interrupt();
            writerThread = null;
        }
    }

    private static Path frameFile() throws java.io.IOException {
        Path dir = cacheDir();
        Files.createDirectories(dir);
        return dir.resolve("dwallpaper-frame.png");
    }

    /** 运行时缓存目录（design.md §10.2）：-Didea-wallpaper.cache.dir 覆盖为 F 盘，缺省随 IDE system 目录。 */
    public static Path cacheDir() throws java.io.IOException {
        String p = System.getProperty("idea-wallpaper.cache.dir");
        Path dir;
        if (p != null && !p.isBlank()) {
            dir = Path.of(p);
        } else {
            dir = Path.of(com.intellij.openapi.application.PathManager.getSystemPath(), "dwallpaper");
        }
        Files.createDirectories(dir);
        return dir;
    }
}
