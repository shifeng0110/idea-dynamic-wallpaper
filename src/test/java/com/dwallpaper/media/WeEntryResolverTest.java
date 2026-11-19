package com.dwallpaper.media;

import com.dwallpaper.welibrary.WeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WE 条目 → 媒体目标解析（任务口径第 2 条：project.json 指定入口优先，回退链稳定）。 */
class WeEntryResolverTest {

    @TempDir
    Path tempDir;

    private static WeEntry entry(String type, Path folder) {
        // t4 契约：WeEntry 9 参（mediaPath 位于 previewPath 之前）
        return new WeEntry("entry-1", "Demo", type, folder.toString(), null, null, null, true, "projects");
    }

    private static void writeString(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void videoUsesProjectJsonFileField() throws Exception {
        Path dir = tempDir.resolve("video-entry");
        Files.createDirectories(dir);
        writeString(dir.resolve("project.json"), "{\"type\":\"Video\",\"file\":\"clip one (hd).webm\"}");
        Files.write(dir.resolve("clip one (hd).webm"), new byte[]{1, 2, 3});

        WeEntryResolver.MediaTarget target = WeEntryResolver.resolve(entry("video", dir));
        assertEquals(WeEntryResolver.MediaTarget.Kind.VIDEO, target.kind());
        assertTrue(target.path().endsWith("clip one (hd).webm"), "含空格/括号路径原样拼接");
    }

    @Test
    void videoFallsBackToFolderScanPreferMp4() throws Exception {
        Path dir = tempDir.resolve("video-scan");
        Files.createDirectories(dir);
        writeString(dir.resolve("project.json"), "{\"file\":\"missing.mp4\"}");
        Files.write(dir.resolve("b.webm"), new byte[]{1});
        Files.write(dir.resolve("a.mp4"), new byte[]{1});

        WeEntryResolver.MediaTarget target = WeEntryResolver.resolve(entry("video", dir));
        assertEquals(WeEntryResolver.MediaTarget.Kind.VIDEO, target.kind());
        assertTrue(target.path().endsWith("a.mp4"), "project.json 失效后回退扫描且 mp4 优先");
    }

    @Test
    void webUsesProjectJsonHtmlEntry() throws Exception {
        Path dir = tempDir.resolve("web-entry");
        Files.createDirectories(dir);
        writeString(dir.resolve("project.json"), "{\"file\":\"main.html\"}");
        writeString(dir.resolve("main.html"), "<html></html>");
        writeString(dir.resolve("index.html"), "<html></html>");

        WeEntryResolver.MediaTarget target = WeEntryResolver.resolve(entry("web", dir));
        assertEquals(WeEntryResolver.MediaTarget.Kind.WEB, target.kind());
        assertTrue(target.path().endsWith("main.html"), "project.json 指定入口优先于 index.html");
    }

    @Test
    void webFallsBackToIndexHtml() throws Exception {
        Path dir = tempDir.resolve("web-index");
        Files.createDirectories(dir);
        writeString(dir.resolve("index.html"), "<html></html>");
        writeString(dir.resolve("other.html"), "<html></html>");

        WeEntryResolver.MediaTarget target = WeEntryResolver.resolve(entry("web", dir));
        assertEquals(WeEntryResolver.MediaTarget.Kind.WEB, target.kind());
        assertTrue(target.path().endsWith("index.html"));
    }

    @Test
    void webFallsBackToFirstHtmlSorted() throws Exception {
        Path dir = tempDir.resolve("web-scan");
        Files.createDirectories(dir);
        writeString(dir.resolve("b.html"), "<html></html>");
        writeString(dir.resolve("a.html"), "<html></html>");

        WeEntryResolver.MediaTarget target = WeEntryResolver.resolve(entry("web", dir));
        assertTrue(target.path().endsWith("a.html"), "无 index.html 时取名称排序首个");
    }

    @Test
    void returnsNullWhenNothingResolves() throws Exception {
        Path dir = tempDir.resolve("empty");
        Files.createDirectories(dir);
        assertNull(WeEntryResolver.resolve(entry("video", dir)), "video：无 json.file、无媒体文件 → null");
        assertNull(WeEntryResolver.resolve(entry("web", dir)), "web：无入口 → null");
        assertNull(WeEntryResolver.resolve(entry("scene", dir)), "非 video/web 类型由注册表分派 → null");
    }
}
