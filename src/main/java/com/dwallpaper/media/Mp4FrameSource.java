package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameSource;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP4/MOV 视频帧源（【2026-11-19 视频改走纯 Java 解码】）。
 *
 * <p>背景：CEF 对用户壁纸库里的 H.264（{@code avc1}）mp4 直接报
 * {@code <video> v.error}（界面状态通道实证"红"），即该 JCEF 运行时不具备解码该
 * 文件的编解码能力；而媒体服务器侧 206 Range 完全正常（日志实证字节已发出）。
 * 与其继续依赖浏览器解码，这里直接用纯 Java 的 JCodec 完成 MP4 demux + H.264 解码，
 * 把帧送进与 GIF/图片完全相同的 {@link FrameSlot} → 管线 → Surface 通道。</p>
 *
 * <p>线程模型：{@link #start()} 起一条守护解码线程；按视频自身 PTS 节奏（用轨道 fps
 * 归一）节流，目标帧率由 {@link #setRate(int)} 下发（≤0 = 暂停，保留位置），
 * 播完自动从头循环。单槽语义：新帧覆盖旧帧，不积压。</p>
 */
public final class Mp4FrameSource implements FrameSource {

    private final Path file;
    private final FrameSlot slot = new FrameSlot();
    private volatile int rate = 24;
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile Thread worker;
    private volatile int renderWidth;
    private volatile int renderHeight;
    private volatile long published;

    public Mp4FrameSource(Path file) {
        this.file = file;
    }

    @Override
    public void start() {
        if (started || closed || file == null || !Files.isRegularFile(file)) {
            return;
        }
        started = true;
        Thread t = new Thread(this::decodeLoop, "dwallpaper-mp4-decoder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        worker = t;
        t.start();
        MediaLog.info("MP4 帧源启动（纯 Java 解码，零 CEF 依赖）：" + file.getFileName());
    }

    @Override
    public Frame pollFrame() {
        if (closed) {
            return null;
        }
        return slot.pollFrame();
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public void setRate(int fps) {
        if (fps <= 0) {
            paused = true;
        } else {
            paused = false;
            rate = Math.max(1, Math.min(60, fps));
        }
    }

    @Override
    public void setRenderSize(int width, int height) {
        if (width > 0 && height > 0) {
            renderWidth = width;
            renderHeight = height;
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        slot.close();
        MediaLog.info("MP4 帧源关闭（已发布 " + published + " 帧）");
    }

    // ---------------- 解码循环 ----------------

    private void decodeLoop() {
        while (!closed) {
            org.jcodec.common.io.SeekableByteChannel ch = null;
            try {
                ch = NIOUtils.readableChannel(file.toFile());
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                double videoFps = 24;
                try {
                    // JCodec 的 DemuxerTrackMeta 没有 getFps()：用总帧数/总时长推。
                    org.jcodec.common.DemuxerTrackMeta meta = grab.getVideoTrack().getMeta();
                    int total = meta.getTotalFrames();
                    double dur = meta.getTotalDuration();
                    if (total > 1 && dur > 0.1) {
                        double f = total / dur;
                        if (f >= 2 && f <= 240) {
                            videoFps = f;
                        }
                    }
                } catch (Throwable ignored) {
                    // 拿不到 fps 用 24 兜底
                }
                if (videoFps <= 1 || videoFps > 240) {
                    videoFps = 24;
                }
                int skip = Math.max(1, (int) Math.round(videoFps / Math.max(1, rate)));
                long frameDurNanos = (long) (skip * 1_000_000_000.0 / videoFps);
                long next = System.nanoTime();
                int index = 0;
                while (!closed) {
                    Picture pic = grab.getNativeFrame();
                    if (pic == null) {
                        break; // 播完 → 外层重建，从头循环
                    }
                    if (index % skip == 0) {
                        if (!paused) {
                            publish(pic);
                            // 目标帧率可能被滑杆/状态机改过，按当前值重算节奏
                            skip = Math.max(1, (int) Math.round(videoFps / Math.max(1, rate)));
                            frameDurNanos = (long) (skip * 1_000_000_000.0 / videoFps);
                            next += frameDurNanos;
                            long sleepNs = next - System.nanoTime();
                            if (sleepNs > 0) {
                                Thread.sleep(sleepNs / 1_000_000L,
                                        (int) (sleepNs % 1_000_000L));
                            } else if (sleepNs < -600_000_000L) {
                                next = System.nanoTime(); // 落后太多 → 重置节奏，避免追帧
                            }
                        } else {
                            Thread.sleep(50);
                        }
                    }
                    index++;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                MediaLog.warn("MP4 解码失败（重试中）：" + file.getFileName() + " - " + t);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } finally {
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (Throwable ignored) {
                        // 关闭失败不掩盖主流程
                    }
                }
            }
        }
    }

    private void publish(Picture pic) {
        try {
            BufferedImage src = AWTUtil.toBufferedImage(pic);
            if (src == null) {
                return;
            }
            int w = renderWidth > 0 ? renderWidth : src.getWidth();
            int h = renderHeight > 0 ? renderHeight : src.getHeight();
            BufferedImage target = slot.acquire(w, h);
            if (target == null) {
                return;
            }
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            slot.publish(target);
            published++;
            if (published == 1 || published % 240 == 0) {
                MediaLog.info("MP4 帧已发布：" + published + " 帧（" + w + "x" + h + "）");
            }
        } catch (Throwable t) {
            if (published < 3) {
                MediaLog.warn("MP4 帧转换失败：" + t);
            }
        }
    }
}
