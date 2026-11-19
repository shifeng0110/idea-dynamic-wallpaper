package com.dwallpaper.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地兜底媒体服务（design.md §9.3/OPEN-3，t5 实现）：回环随机端口、Content-Type、
 * 全量 200 / Range 206 / 非法 Range 416、包装页 http 同源（t5 决策）。
 */
class LocalMediaServerTest {

    @TempDir
    Path tempDir;

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    void servesFullFileThenRanges() throws Exception {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        Path mp4 = tempDir.resolve("clip.mp4");
        Files.write(mp4, data);

        LocalMediaServer server = new LocalMediaServer(mp4.toFile());
        server.start();
        try {
            String url = server.url();
            assertTrue(url.startsWith("http://127.0.0.1:"), "仅回环绑定，实际=" + url);
            assertTrue(url.endsWith("/media.mp4"), "URL 固定 /media.<ext>");

            // 全量 200
            HttpURLConnection full = (HttpURLConnection) new URL(url).openConnection();
            assertEquals(200, full.getResponseCode());
            assertEquals("video/mp4", full.getContentType());
            assertEquals("bytes", full.getHeaderField("Accept-Ranges"));
            assertArrayEquals(data, readAll(full.getInputStream()));
            full.disconnect();

            // Range 206（bytes=100-199）
            HttpURLConnection range = (HttpURLConnection) new URL(url).openConnection();
            range.setRequestProperty("Range", "bytes=100-199");
            assertEquals(206, range.getResponseCode());
            assertEquals("bytes 100-199/1024", range.getHeaderField("Content-Range"));
            byte[] slice = readAll(range.getInputStream());
            assertEquals(100, slice.length);
            for (int i = 0; i < 100; i++) {
                assertEquals(data[100 + i], slice[i]);
            }
            range.disconnect();

            // 后缀 Range（bytes=-10）
            HttpURLConnection suffix = (HttpURLConnection) new URL(url).openConnection();
            suffix.setRequestProperty("Range", "bytes=-10");
            assertEquals(206, suffix.getResponseCode());
            assertEquals("bytes 1014-1023/1024", suffix.getHeaderField("Content-Range"));
            byte[] tail = readAll(suffix.getInputStream());
            assertEquals(10, tail.length);
            suffix.disconnect();

            // 开放 Range（bytes=900-）
            HttpURLConnection open = (HttpURLConnection) new URL(url).openConnection();
            open.setRequestProperty("Range", "bytes=900-");
            assertEquals(206, open.getResponseCode());
            byte[] rest = readAll(open.getInputStream());
            assertEquals(124, rest.length);
            open.disconnect();

            // 非法 Range → 416
            HttpURLConnection invalid = (HttpURLConnection) new URL(url).openConnection();
            invalid.setRequestProperty("Range", "bytes=2000-");
            assertEquals(416, invalid.getResponseCode());
            assertEquals("bytes */1024", invalid.getHeaderField("Content-Range"));
            invalid.disconnect();

            // POST → 405；未知路径 → 404
            HttpURLConnection post = (HttpURLConnection) new URL(url).openConnection();
            post.setRequestMethod("POST");
            assertEquals(405, post.getResponseCode());
            post.disconnect();
            HttpURLConnection missing = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + server.port() + "/media.mpg").openConnection();
            assertEquals(404, missing.getResponseCode());
            missing.disconnect();

            // 包装页：http 同源 + muted/autoplay（t5 决策）
            HttpURLConnection wrapper = (HttpURLConnection) new URL(server.wrapperUrl()).openConnection();
            assertEquals(200, wrapper.getResponseCode());
            assertTrue(wrapper.getContentType().startsWith("text/html"));
            String html = new String(readAll(wrapper.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(html.contains("muted"));
            assertTrue(html.contains("src=\"" + url + "\""), "包装页媒体源必须指向本服务");
            wrapper.disconnect();
        } finally {
            server.close();
        }
        assertEquals(false, server.isStarted(), "close 后必须停服");
    }
}
