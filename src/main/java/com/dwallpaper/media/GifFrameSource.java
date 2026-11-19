package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameSource;
import com.intellij.openapi.diagnostic.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GIF 帧源（design.md §9.1）：javax.imageio ImageReader + GIF 元数据 delayTime/disposal；
 * 后台线程解码（帧间隔取元数据，任务口径 §4），当前帧经单槽交付（新帧覆盖旧帧，§4.3）。
 * 解码循环=逐帧合成画布（disposal none/doNotDispose 保留、restoreToBackground 清画布、
 * restoreToPrevious 简化为清画布——WE GIF 实测均为全幅帧）。
 * 【F5 修复】双 canvas 交替（偶/奇帧换缓冲）：交付出去的帧在其下一次交付前不会被就地重绘，
 * pipeline worker（scaleTo 读）与解码线程（写）无读写并发撕裂。
 * 【F6 配套】setRate(int)：≤0 暂停解码线程（wait/notify），>0 恢复——disable 期间解码挂起不空转。
 * pollFrame 按帧序去重（24fps 泵 > GIF 原生帧率时不重复提交）。
 */
public final class GifFrameSource implements FrameSource {

    private static final Logger LOG = Logger.getInstance(GifFrameSource.class);
    private static final int MIN_DELAY_MS = 33;
    private static final int DEFAULT_DELAY_MS = 100;

    private final Path file;
    private final AtomicReference<Frame> slot = new AtomicReference<>();
    private final Object pauseLock = new Object();
    private Thread decodeThread;
    private volatile boolean closed;
    private volatile boolean decodePaused; // F6：disable→setRate(0) 暂停解码
    private volatile long deliveredSeq = -1;
    private static final AtomicLong seqCounter = new AtomicLong();

    public GifFrameSource(Path file) {
        this.file = file;
    }

    @Override
    public void start() {
        if (decodeThread != null) {
            return;
        }
        decodeThread = new Thread(this::loop, "dwallpaper-gif-decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    private void loop() {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            java.util.Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) {
                LOG.warn("dwallpaper: no gif reader for " + file);
                return;
            }
            ImageReader reader = it.next();
            try {
                reader.setInput(in);
                int n = reader.getNumImages(true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                // F5：双 canvas 交替——刚交付的帧不再被就地重绘
                BufferedImage[] canvases = {
                        new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB),
                        new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                };
                int i = 0;
                int flip = 0;
                while (!closed && n > 0) {
                    awaitResume(); // F6：暂停态在此挂起（不空转）
                    if (closed) {
                        return;
                    }
                    FrameInfo info = frameInfo(reader.getImageMetadata(i), w, h);
                    BufferedImage frame = reader.read(i);
                    BufferedImage canvas = canvases[flip];
                    Graphics2D g = canvas.createGraphics();
                    try {
                        if (info.disposal == 2 || info.disposal == 3) {
                            // restoreToBackground/Previous：当前帧绘制前清画布（简化口径）
                            g.setComposite(java.awt.AlphaComposite.Clear);
                            g.fillRect(0, 0, w, h);
                            g.setComposite(java.awt.AlphaComposite.SrcOver);
                        }
                        if (frame.getWidth() == w && frame.getHeight() == h) {
                            g.drawImage(frame, 0, 0, null);
                        } else {
                            g.drawImage(frame, info.left, info.top, null);
                        }
                    } finally {
                        g.dispose();
                    }
                    slot.set(new Frame(canvas, seqCounter.incrementAndGet(),
                            System.currentTimeMillis(), w, h));
                    flip = flip ^ 1; // 下一帧写另一块缓冲（交付中的帧不被覆盖）
                    i = (i + 1) % n;
                    sleepWithPause(Math.max(info.delayMs, MIN_DELAY_MS));
                }
            } finally {
                reader.dispose();
            }
        } catch (InterruptedException ignored) {
            // close 中断
        } catch (Throwable t) {
            LOG.warn("dwallpaper: gif decode stopped: " + file + " -> " + t);
        }
    }

    /** F6：暂停期挂起（wait/notify）；恢复/关闭立即返回。 */
    private void awaitResume() throws InterruptedException {
        synchronized (pauseLock) {
            while (decodePaused && !closed) {
                pauseLock.wait(500);
            }
        }
    }

    /** 帧间延迟：暂停态转为挂起等待，恢复/关闭立即返回。 */
    private void sleepWithPause(long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!closed && System.currentTimeMillis() < deadline) {
            synchronized (pauseLock) {
                if (decodePaused) {
                    pauseLock.wait(500);
                    return; // 暂停即跳出本帧延迟，回循环头挂起
                }
            }
            Thread.sleep(20);
        }
    }

    /** F6 配套：≤0 暂停解码（disable/IDLE/MANUAL），>0 恢复。 */
    @Override
    public void setRate(int fps) {
        decodePaused = fps <= 0;
        if (!decodePaused) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        LOG.info("dwallpaper: gif decode " + (decodePaused ? "paused" : "resumed") + " (rate=" + fps + ")");
    }

    private record FrameInfo(int delayMs, int disposal, int left, int top, int fw, int fh) {
    }

    /** 解析 javax_imageio_gif_image_1.0 + GraphicControlExtension；解析失败 → 默认值。 */
    private static FrameInfo frameInfo(IIOMetadata meta, int w, int h) {
        int delayMs = DEFAULT_DELAY_MS;
        int disposal = 0;
        int left = 0;
        int top = 0;
        int fw = w;
        int fh = h;
        try {
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
            for (int i = 0; i < root.getLength(); i++) {
                org.w3c.dom.Node node = root.item(i);
                if ("GraphicControlExtension".equals(node.getNodeName())) {
                    String d = attr((IIOMetadataNode) node, "delayTime");
                    if (d != null) {
                        delayMs = Integer.parseInt(d) * 10; // 1/100 秒 → ms
                    }
                    String dm = attr((IIOMetadataNode) node, "disposalMethod");
                    if (dm != null) {
                        disposal = switch (dm) {
                            case "restoreToBackground" -> 2;
                            case "restoreToPrevious" -> 3;
                            default -> 0;
                        };
                    }
                }
                if ("ImageDescriptor".equals(node.getNodeName())) {
                    left = num(attr((IIOMetadataNode) node, "imageLeftPosition"), 0);
                    top = num(attr((IIOMetadataNode) node, "imageTopPosition"), 0);
                    fw = num(attr((IIOMetadataNode) node, "imageWidth"), w);
                    fh = num(attr((IIOMetadataNode) node, "imageHeight"), h);
                }
            }
        } catch (Throwable ignored) {
            // 元数据异常 → 默认全幅 + 默认延迟
        }
        return new FrameInfo(Math.max(delayMs, MIN_DELAY_MS), disposal, left, top, fw, fh);
    }

    private static String attr(IIOMetadataNode node, String name) {
        org.w3c.dom.NamedNodeMap m = node.getAttributes();
        org.w3c.dom.Node a = m.getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    private static int num(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public Frame pollFrame() {
        Frame f = slot.get();
        if (f == null || f.frameId() == deliveredSeq) {
            return null; // 泵快于 GIF 原生帧率 → 不重复提交
        }
        deliveredSeq = f.frameId();
        return f;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public void close() {
        closed = true;
        decodePaused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (decodeThread != null) {
            decodeThread.interrupt();
            decodeThread = null;
        }
    }
}
