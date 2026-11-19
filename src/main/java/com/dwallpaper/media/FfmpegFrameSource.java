package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameSource;
import com.intellij.openapi.diagnostic.Logger;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于系统 ffmpeg 的视频帧源（2026-11-19 性能定稿 v2）。
 *
 * <p>传输层双通道，自动择优：</p>
 * <ul>
 *   <li><b>TCP 回环 + rawvideo（首选）</b>：套接字支持整块大读取（不受 stdio 管道每次
 *       ~8KB 的限制），且<b>完全不需要解码</b>；每帧 BGRA→ARGB 是一次批量 int 拷贝
 *       （实测 1–3ms）。缩放与亮度都在 ffmpeg 的 C 代码里完成，Java 管线直通。</li>
 *   <li><b>stdio 管道 + MJPEG（回退）</b>：若 TCP 建链失败（防火墙/沙箱/端口限制），
 *       自动退回已验证可用的 MJPEG 方案（约 30fps 能力）。</li>
 * </ul>
 *
 * <p>历史实测依据（都来自 idea.log 的真实数字，避免再走弯路）：
 * 纯 Java JCodec 1080p≈22fps；管道 rawvideo 因每次 read 仅 8KB → 360ms/帧；
 * MJPEG 管道读 1ms/帧但 JDK JPEG 解码 28–50ms/帧；管线里做缩放 42ms/帧。</p>
 */
public final class FfmpegFrameSource implements FrameSource {

    private static final Logger LOG = Logger.getInstance(FfmpegFrameSource.class);

    private final Path file;
    private final FrameSlot slot = new FrameSlot();
    private volatile int rate = 30;
    private volatile int brightnessPercent = 100;
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile int renderWidth = 1920;
    private volatile int renderHeight = 1080;
    private volatile Thread worker;
    private volatile Process process;
    private volatile ServerSocket serverSocket;
    private volatile Socket socket;
    private volatile long published;
    private volatile String ffmpegPath;
    private volatile String transport = "?";

    public FfmpegFrameSource(Path file) {
        this.file = file;
    }

    // ---------------- ffmpeg 定位 ----------------

    public static String resolveFfmpeg() {
        String prop = System.getProperty("dwallpaper.ffmpeg");
        if (prop != null && !prop.isBlank() && Files.isRegularFile(Path.of(prop))) {
            return prop;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                Path exe = Path.of(dir, "ffmpeg.exe");
                if (Files.isRegularFile(exe)) {
                    return exe.toString();
                }
                Path exeNoExt = Path.of(dir, "ffmpeg");
                if (Files.isRegularFile(exeNoExt)) {
                    return exeNoExt.toString();
                }
            }
        }
        List<String> roots = new ArrayList<>();
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            roots.add(local + "\\Microsoft\\WinGet\\Packages");
            roots.add(local + "\\Programs");
        }
        roots.add("C:\\ffmpeg\\bin");
        roots.add("C:\\Program Files\\ffmpeg\\bin");
        roots.add("F:\\ffmpeg\\bin");
        for (String root : roots) {
            String hit = scanForFfmpeg(Path.of(root), 0, 4);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String scanForFfmpeg(Path dir, int depth, int maxDepth) {
        if (depth > maxDepth || dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            List<Path> children = s.toList();
            for (Path c : children) {
                String name = c.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(c) && (name.equals("ffmpeg.exe") || name.equals("ffmpeg"))) {
                    return c.toString();
                }
            }
            for (Path c : children) {
                if (Files.isDirectory(c)) {
                    String hit = scanForFfmpeg(c, depth + 1, maxDepth);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 无权限目录跳过
        }
        return null;
    }

    public static boolean isAvailable() {
        return resolveFfmpeg() != null;
    }

    // ---------------- FrameSource ----------------

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        started = true;
        Thread t = new Thread(this::runLoop, "dwallpaper-ffmpeg");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        worker = t;
        t.start();
    }

    @Override
    public Frame pollFrame() {
        return closed ? null : slot.pollFrame();
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
            int clamped = Math.max(5, Math.min(60, fps));
            if (clamped != rate) {
                rate = clamped;
                restart();
            }
        }
    }

    @Override
    public void setRenderSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width != renderWidth || height != renderHeight) {
            renderWidth = width;
            renderHeight = height;
            restart();
        }
    }

    public void setBrightnessPercent(int percent) {
        int v = Math.max(40, Math.min(160, percent));
        if (v != brightnessPercent) {
            brightnessPercent = v;
            restart();
        }
    }

    @Override
    public void close() {
        closed = true;
        stopProcess();
        slot.close();
        LOG.info("ffmpeg 帧源关闭（已发布 " + published + " 帧，通道=" + transport + "）");
    }

    // ---------------- 循环 ----------------

    private synchronized void restart() {
        if (closed || !started) {
            return;
        }
        stopProcess();
    }

    private void stopProcess() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private void runLoop() {
        while (!closed) {
            try {
                if (paused) {
                    Thread.sleep(100);
                    continue;
                }
                if (process == null) {
                    startProcess();
                }
                if (socket != null) {
                    readRawFrames();
                } else {
                    readMjpegFrames();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOG.warn("ffmpeg 读取中断（重试）：" + t);
                stopProcess();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ---------------- 进程启动（TCP 优先，失败回退管道） ----------------

    private synchronized void startProcess() throws IOException {
        if (process != null) {
            return;
        }
        if (ffmpegPath == null) {
            ffmpegPath = resolveFfmpeg();
        }
        if (ffmpegPath == null) {
            LOG.warn("未找到 ffmpeg，无法使用原生解码");
            throw new IOException("ffmpeg not found");
        }
        String mode = System.getProperty("dwallpaper.ffmpeg.transport", "tcp");
        if (!"pipe".equalsIgnoreCase(mode) && startTcp()) {
            return;
        }
        startPipe();
    }

    /** TCP 回环 + rawvideo：失败返回 false（调用方回退 MJPEG）。 */
    private boolean startTcp() {
        ServerSocket ss = null;
        Process p = null;
        try {
            ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            int port = ss.getLocalPort();
            int w = Math.min(Math.max(64, renderWidth), capWidth(true));
            int h = Math.max(1, (int) Math.round((double) w * Math.max(1, renderHeight) / Math.max(1, renderWidth)));
            ProcessBuilder pb = new ProcessBuilder(buildCommand(w, h, "tcp://127.0.0.1:" + port));
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
            ss.setSoTimeout(4000);
            Socket s = ss.accept();
            s.setTcpNoDelay(true);
            try {
                s.setReceiveBufferSize(1 << 20);
            } catch (Throwable ignored) {
                // 缓冲设置失败不影响
            }
            process = p;
            socket = s;
            serverSocket = ss;
            transport = "tcp-raw";
            LOG.info("ffmpeg 原生解码启动（TCP rawvideo，免解码）：" + file.getFileName()
                    + " → " + w + "x" + h + " @" + rate + "fps，端口 " + port);
            return true;
        } catch (Throwable t) {
            LOG.warn("TCP 通道建链失败，回退 MJPEG 管道：" + t);
            try {
                if (p != null) {
                    p.destroy();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                if (ss != null) {
                    ss.close();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            socket = null;
            serverSocket = null;
            process = null;
            return false;
        }
    }

    private void startPipe() throws IOException {
        int w = Math.min(Math.max(64, renderWidth), capWidth(false));
        int h = Math.max(1, (int) Math.round((double) w * Math.max(1, renderHeight) / Math.max(1, renderWidth)));
        ProcessBuilder pb = new ProcessBuilder(buildCommand(w, h, "pipe:1"));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();
        transport = "mjpeg-pipe";
        LOG.info("ffmpeg 原生解码启动（MJPEG 管道）：" + file.getFileName()
                + " → " + w + "x" + h + " @" + rate + "fps");
    }

    /** 构造 ffmpeg 命令行；output=pipe:1 走 MJPEG，output=tcp://… 走 rawvideo。 */
    private List<String> buildCommand(int w, int h, String output) {
        boolean tcp = output.startsWith("tcp://");
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-stream_loop");
        cmd.add("-1");
        cmd.add("-re"); // 按输入真实时间轴输出，否则壁纸变快放
        cmd.add("-i");
        cmd.add(file.toAbsolutePath().toString());
        cmd.add("-an");
        cmd.add("-vf");
        // 缩放与亮度都在 ffmpeg 的 C 代码完成 → Java 管线直通，不重复全帧处理
        cmd.add("scale=" + w + ":" + h + "," + eqFilter());
        cmd.add("-r");
        cmd.add(String.valueOf(Math.max(5, Math.min(60, rate))));
        if (tcp) {
            cmd.add("-pix_fmt");
            cmd.add("bgra");
            cmd.add("-f");
            cmd.add("rawvideo");
        } else {
            cmd.add("-q:v");
            cmd.add("4");
            cmd.add("-f");
            cmd.add("mjpeg");
        }
        cmd.add(output);
        return cmd;
    }

    private String eqFilter() {
        double b = (brightnessPercent - 100) / 100.0;
        b = Math.max(-0.6, Math.min(0.6, b));
        if (Math.abs(b) < 0.005) {
            return "null";
        }
        return String.format(Locale.ROOT, "eq=brightness=%.3f", b);
    }

    /** 输出宽度上限：TCP 免解码 → 可按窗口原尺寸；MJPEG 需解码 → 默认 1280。 */
    private static int capWidth(boolean tcp) {
        String key = tcp ? "dwallpaper.video.width.tcp" : "dwallpaper.video.width";
        try {
            String v = System.getProperty(key);
            if (v != null && !v.isBlank()) {
                return Math.max(320, Math.min(3840, Integer.parseInt(v.trim())));
            }
        } catch (Throwable ignored) {
            // 走默认
        }
        return tcp ? 1920 : 1280;
    }

    // ---------------- 读取：rawvideo over TCP ----------------

    private void readRawFrames() throws IOException {
        int w = Math.min(Math.max(64, renderWidth), capWidth(true));
        int h = Math.max(1, (int) Math.round((double) w * Math.max(1, renderHeight) / Math.max(1, renderWidth)));
        int frameBytes = w * h * 4;
        byte[] raw = new byte[frameBytes];
        InputStream in = new java.io.BufferedInputStream(socket.getInputStream(), 1 << 20);
        long tRead = 0;
        long tConv = 0;
        long tStart = System.currentTimeMillis();
        long n = 0;
        while (!closed && !paused) {
            long r0 = System.nanoTime();
            int read = 0;
            while (read < frameBytes) {
                int got = in.read(raw, read, frameBytes - read);
                if (got < 0) {
                    return;
                }
                read += got;
            }
            long r1 = System.nanoTime();
            BufferedImage img = slot.acquire(w, h);
            if (img == null) {
                continue;
            }
            int[] argb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            BgraFrameCopy.copyToArgb(ByteBuffer.wrap(raw), argb, w * h);
            slot.publish(img);
            long r2 = System.nanoTime();
            tRead += r1 - r0;
            tConv += r2 - r1;
            published++;
            n++;
            if (n % 60 == 0) {
                long elapsed = Math.max(1, System.currentTimeMillis() - tStart);
                LOG.info("ffmpeg 吞吐[tcp-raw]：" + n + " 帧 / " + elapsed + "ms = "
                        + String.format(Locale.ROOT, "%.1f", n * 1000.0 / elapsed)
                        + "fps｜读套接字 " + (tRead / 1_000_000 / Math.max(1, n))
                        + "ms/帧｜转换+发布 " + (tConv / 1_000_000 / Math.max(1, n))
                        + "ms/帧（" + w + "x" + h + "）");
                tRead = 0;
                tConv = 0;
                tStart = System.currentTimeMillis();
                n = 0;
            }
        }
    }

    // ---------------- 读取：MJPEG over pipe ----------------

    private void readMjpegFrames() throws IOException {
        int w = Math.min(Math.max(64, renderWidth), capWidth(false));
        int h = Math.max(1, (int) Math.round((double) w * Math.max(1, renderHeight) / Math.max(1, renderWidth)));
        InputStream in = new java.io.BufferedInputStream(process.getInputStream(), 1 << 16);
        byte[] chunk = new byte[1 << 16];
        java.io.ByteArrayOutputStream frameBuf = new java.io.ByteArrayOutputStream(1 << 17);
        boolean inFrame = false;
        int prev = -1;
        long tRead = 0;
        long tConv = 0;
        long tStart = System.currentTimeMillis();
        long n = 0;
        while (!closed && !paused) {
            long r0 = System.nanoTime();
            int got = in.read(chunk);
            if (got < 0) {
                return;
            }
            long r1 = System.nanoTime();
            tRead += r1 - r0;
            for (int i = 0; i < got; i++) {
                int b = chunk[i] & 0xFF;
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true;
                        frameBuf.reset();
                        frameBuf.write(0xFF);
                        frameBuf.write(0xD8);
                    }
                } else {
                    frameBuf.write(b);
                    if (prev == 0xFF && b == 0xD9) {
                        inFrame = false;
                        long c0 = System.nanoTime();
                        publishJpeg(frameBuf.toByteArray(), w, h);
                        tConv += System.nanoTime() - c0;
                        n++;
                        if (n % 60 == 0) {
                            long elapsed = Math.max(1, System.currentTimeMillis() - tStart);
                            LOG.info("ffmpeg 吞吐[mjpeg]：" + n + " 帧 / " + elapsed + "ms = "
                                    + String.format(Locale.ROOT, "%.1f", n * 1000.0 / elapsed)
                                    + "fps｜读管道 " + (tRead / 1_000_000 / Math.max(1, n))
                                    + "ms/帧｜解码+发布 " + (tConv / 1_000_000 / Math.max(1, n))
                                    + "ms/帧（" + w + "x" + h + "）");
                            tRead = 0;
                            tConv = 0;
                            tStart = System.currentTimeMillis();
                            n = 0;
                        }
                    }
                }
                prev = b;
            }
        }
    }

    private void publishJpeg(byte[] jpeg, int w, int h) {
        try {
            if (jpegReader == null) {
                java.util.Iterator<javax.imageio.ImageReader> it =
                        javax.imageio.ImageIO.getImageReadersByFormatName("jpeg");
                if (!it.hasNext()) {
                    return;
                }
                jpegReader = it.next();
                readParam = jpegReader.getDefaultReadParam();
            }
            if (decodeW != w || decodeH != h || decodeRing[0] == null) {
                for (int i = 0; i < decodeRing.length; i++) {
                    decodeRing[i] = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                }
                decodeW = w;
                decodeH = h;
            }
            BufferedImage target = decodeRing[decodeRingIdx];
            decodeRingIdx = (decodeRingIdx + 1) % decodeRing.length;
            javax.imageio.stream.ImageInputStream iis =
                    new javax.imageio.stream.MemoryCacheImageInputStream(
                            new java.io.ByteArrayInputStream(jpeg));
            try {
                jpegReader.setInput(iis, true, true);
                readParam.setDestination(target);
                jpegReader.read(0, readParam);
            } finally {
                try {
                    iis.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
            slot.publish(target);
            published++;
        } catch (Throwable t) {
            // 单帧解码失败丢弃
        }
    }

    private javax.imageio.ImageReader jpegReader;
    private javax.imageio.ImageReadParam readParam;
    private final BufferedImage[] decodeRing = new BufferedImage[3];
    private int decodeRingIdx;
    private int decodeW = -1;
    private int decodeH = -1;
}
