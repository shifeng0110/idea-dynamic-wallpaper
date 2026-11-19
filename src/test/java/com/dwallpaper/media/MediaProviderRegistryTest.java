package com.dwallpaper.media;

import com.dwallpaper.core.FrameSource;
import com.dwallpaper.welibrary.WeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 帧源注册表分派（design.md §9.1/§9.4 + 任务口径第 1/2/4 条）。 */
class MediaProviderRegistryTest {

    @TempDir
    Path tempDir;

    private static WeEntry entry(String type, Path folder) {
        // t4 契约：WeEntry 9 参（mediaPath 位于 previewPath 之前）
        return new WeEntry("entry-1", "Demo", type, folder == null ? "F:/nowhere" : folder.toString(),
                null, null, null, true, "projects");
    }

    @Test
    void sceneEntryReturnsUnsupportedWithMessage() throws Exception {
        FrameSource source = new MediaProviderRegistry().resolve(entry("scene", null));
        assertInstanceOf(UnsupportedEntrySource.class, source);
        String message = ((UnsupportedEntrySource) source).message();
        assertTrue(message.contains("暂不支持"), "任务口径：scene 显示『暂不支持』，实际=" + message);
        assertTrue(message.contains("scene"));
        assertNull(source.pollFrame());
        assertFalse(source.isDynamic());
        source.close();
    }

    @Test
    void applicationEntryAlsoUnsupported() {
        FrameSource source = new MediaProviderRegistry().resolve(entry("application", null));
        assertInstanceOf(UnsupportedEntrySource.class, source);
        assertTrue(((UnsupportedEntrySource) source).message().contains("暂不支持"));
    }

    @Test
    void mixedCaseTypeNormalizedDefensively() {
        // welibrary 已归一化小写；注册表再防御一次（t1 实测存在 Video/video 混用）
        assertNull(new MediaProviderRegistry().resolve(entry("Video", tempDir)),
                "无 JCEF 时 video 分支必须降级为 null（禁选）");
    }

    @Test
    void videoEntryDegradesWithoutJcef() {
        JcefGate.resetForTest();
        try {
            assertNull(new MediaProviderRegistry().resolve(entry("video", tempDir)),
                    "无 JCEF 时 video 必须 null（禁选 + 降级日志/UI 提示）");
        } finally {
            JcefGate.resetForTest();
        }
    }

    @Test
    void webEntryDegradesWithoutJcef() {
        JcefGate.resetForTest();
        try {
            assertNull(new MediaProviderRegistry().resolve(entry("web", tempDir)));
        } finally {
            JcefGate.resetForTest();
        }
    }

    @Test
    void gifEntryResolvesT4GifSource() throws Exception {
        // t4 构造契约：GifFrameSource(Path) + start()；注册表按内容探测分派（§9.1）
        Path dir = tempDir.resolve("gif-entry");
        Files.createDirectories(dir);
        assertTrue(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "gif",
                dir.resolve("anim.gif").toFile()), "测试 GIF 必须可写");

        FrameSource source = new MediaProviderRegistry().resolve(entry("gif", dir));
        assertInstanceOf(GifFrameSource.class, source);
        assertTrue(source.isDynamic());
        // 等待后台解码完成（释放测试文件句柄，避免 @TempDir 清理竞态），再关闭
        com.dwallpaper.core.Frame first = awaitFirstFrame(source);
        assertNotNull(first, "GIF 首帧应完成后台解码");
        source.close();
    }

    @Test
    void unknownEntryWithSinglePngResolvesStaticSource() throws Exception {
        Path dir = tempDir.resolve("unknown-entry");
        Files.createDirectories(dir);
        assertTrue(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png",
                dir.resolve("shot.png").toFile()));

        FrameSource source = new MediaProviderRegistry().resolve(entry("unknown-type", dir));
        assertInstanceOf(StaticImageSource.class, source);
        assertFalse(source.isDynamic());
        // t9 F4：延迟后台解码——等解码完成（释放 shot.png 句柄）再 close，避免 @TempDir 清理竞态
        assertNotNull(awaitFirstFrame(source), "静态图应完成后台解码");
        source.close();
    }

    /** 有界等待异步帧源（GIF/静态图延迟解码）产出首帧；10s 预算（沙箱首跑含 ImageIO 初始化/类加载预热），超时返回 null。 */
    private static com.dwallpaper.core.Frame awaitFirstFrame(FrameSource source) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            com.dwallpaper.core.Frame f = source.pollFrame();
            if (f != null) {
                return f;
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    void resolvePathDispatchesByContentUnderGate() throws Exception {
        JcefGate.resetForTest();
        try {
            MediaProviderRegistry registry = new MediaProviderRegistry();
            // 视频文件直连 → JCEF 分支 → 无 JCEF 降级 null（且 LocalMediaServer 不启动）
            Path mp4 = tempDir.resolve("clip.mp4");
            Files.write(mp4, new byte[]{1});
            assertNull(registry.resolvePath(mp4));

            // html 文件直连 → JCEF web 分支 → 降级 null
            Path html = tempDir.resolve("index.html");
            Files.writeString(html, "<html></html>");
            assertNull(registry.resolvePath(html));

            // 静态图文件直连 → t4 StaticImageSource（无 JCEF 依赖，应成功）
            Path png = tempDir.resolve("shot.png");
            assertTrue(ImageIO.write(new BufferedImage(6, 3, BufferedImage.TYPE_INT_RGB), "png", png.toFile()));
            FrameSource staticSource = registry.resolvePath(png);
            assertInstanceOf(StaticImageSource.class, staticSource);
            staticSource.close();

            // 不存在路径 → null
            assertNull(registry.resolvePath(tempDir.resolve("missing.gif")));
        } finally {
            JcefGate.resetForTest();
        }
    }
}
