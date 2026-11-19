package com.dwallpaper.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地兜底媒体服务（design.md §9.3 / OPEN-3，t5 实现）：
 * com.sun.net.httpserver 绑 127.0.0.1 随机端口、仅回环客户端、仅服务单一媒体文件 + 生成的包装页、
 * 支持 HTTP Range（CEF &lt;video&gt; seek 必需），直接回读原文件 —— 零转码（t1 §4.1 moov/faststart 教训）。
 * <p>t5 决策：视频条目以此为主路径。理由：file:// 页面的媒体子资源加载失败是元素级错误，
 * 不可经 CefLoadHandler（页面级）观测，回读需 JS-query 管线（t5 不引入）；§9.3 草案的
 * 「file:// 优先 + 服务端兜底」改为「服务端优先」，端到端（t7）可复核（详见 README「t5 实现决策」）。
 * 安全面：单文件白名单、回环绑定、仅 GET/HEAD、URL 固定 /media.&lt;ext&gt; 与 /wrapper（不可枚举）。</p>
 */
public final class LocalMediaServer {

    private final File mediaFile;
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public LocalMediaServer(File mediaFile) {
        this.mediaFile = mediaFile;
    }

    public synchronized void start() throws IOException {
        if (started.get()) {
            return;
        }
        if (mediaFile == null || !mediaFile.isFile()) {
            throw new IOException("媒体文件缺失：" + mediaFile);
        }
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::handle);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "dwallpaper-media-server");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(executor);
        http.start();
        server = http;
        started.set(true);
        MediaLog.info("LocalMediaServer 启动：port=" + port() + " media=" + mediaFile.length()
                + "B（仅回环 127.0.0.1，零转码）");
    }

    public synchronized int port() {
        HttpServer http = server;
        return http == null ? -1 : http.getAddress().getPort();
    }

    /** 媒体文件 URL：http://127.0.0.1:&lt;port&gt;/media.&lt;ext&gt;。 */
    public synchronized String url() {
        int p = port();
        return p < 0 ? null : "http://127.0.0.1:" + p + "/media" + extensionOf(mediaFile.getName());
    }

    /** 视频包装页 URL：http 同源页，规避 file→http 混合内容不确定性（t5 决策）。 */
    public synchronized String wrapperUrl() {
        int p = port();
        return p < 0 ? null : "http://127.0.0.1:" + p + "/wrapper";
    }

    public synchronized boolean isStarted() {
        return started.get();
    }

    public synchronized void close() {
        if (!started.get()) {
            return;
        }
        started.set(false);
        try {
            server.stop(0);
        } catch (Throwable t) {
            MediaLog.warn("LocalMediaServer stop 异常：" + t);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        server = null;
        executor = null;
    }

    /** 媒体请求计数（诊断：确认 CEF 到底有没有来取视频字节）。 */
    private volatile long mediaRequests;

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRemoteAddress() == null
                    || exchange.getRemoteAddress().getAddress() == null
                    || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                respondEmpty(exchange, 403);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                respondEmpty(exchange, 405);
                return;
            }
            if ("/wrapper".equals(path) || "/wrapper.html".equals(path)) {
                serveWrapper(exchange, method);
                return;
            }
            serveMedia(exchange, method, path);
        } finally {
            exchange.close();
        }
    }

    private void serveWrapper(HttpExchange exchange, String method) throws IOException {
        String html = VideoWrapperHtml.build(url(), "#000000");
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void serveMedia(HttpExchange exchange, String method, String path) throws IOException {
        String expected = "/media" + extensionOf(mediaFile.getName());
        if (!expected.equals(path)) {
            MediaLog.info("媒体请求 404：path=" + path + " expected=" + expected);
            respondEmpty(exchange, 404);
            return;
        }
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        mediaRequests++;
        if (mediaRequests <= 12) {
            MediaLog.info("媒体请求 #" + mediaRequests + " " + method + " " + path
                    + " range=" + rangeHeader + " len=" + mediaFile.length());
        }
        long length = mediaFile.length();
        exchange.getResponseHeaders().set("Content-Type", contentType(mediaFile.getName()));
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        long start = 0;
        long end = length - 1;
        boolean partial = false;
        String range = exchange.getRequestHeaders().getFirst("Range");        if (range != null && range.startsWith("bytes=")) {
            long[] parsed = parseRange(range, length);
            if (parsed == null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + length);
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            start = parsed[0];
            end = parsed[1];
            partial = true;
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        long contentLength = Math.max(0, end - start + 1);
        if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(partial ? 206 : 200, -1);
            return;
        }
        if (contentLength <= 0) {
            exchange.sendResponseHeaders(partial ? 206 : 200, -1);
            return;
        }
        exchange.sendResponseHeaders(partial ? 206 : 200, contentLength);
        if (mediaRequests <= 12) {
            MediaLog.info("媒体响应 #" + mediaRequests + " -> " + (partial ? "206" : "200")
                    + " bytes=" + start + "-" + end + "/" + length);
        }
        try (OutputStream out = exchange.getResponseBody();
             InputStream in = Files.newInputStream(mediaFile.toPath())) {
            in.skipNBytes(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = contentLength;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    private void respondEmpty(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
    }

    /** 解析 bytes=start-end / start- / -suffix；不可满足返回 null（调用方 416）。 */
    static long[] parseRange(String range, long length) {
        String spec = range.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        try {
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0 || length == 0) {
                    return null;
                }
                return new long[]{Math.max(0L, length - suffix), length - 1};
            }
            long start = Long.parseLong(startPart);
            long end = endPart.isEmpty() ? length - 1 : Long.parseLong(endPart);
            if (length == 0 || start < 0 || start >= length || end < start) {
                return null;
            }
            if (end > length - 1) {
                end = length - 1;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extensionOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot) : "";
    }

    static String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }
}
