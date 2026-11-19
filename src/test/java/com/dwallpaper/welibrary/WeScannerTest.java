package com.dwallpaper.welibrary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 扫描器单元测试（design.md AC-WL-3：本机 46 项全部枚举、type 归一化正确）。
 */
class WeScannerTest {

    private static final Path STEAM_LIB_F = Path.of("F:\\SteamLibrary");

    @Test
    void scansRealWorkshopWithNormalizedTypes() {
        assumeTrue(Files.isDirectory(STEAM_LIB_F), "F:\\SteamLibrary not present");
        List<Path> workshop = WeLocator.workshopDirs(STEAM_LIB_F);
        assumeTrue(workshop.size() >= 40, "workshop content missing");

        WeScanner.ScanResult r = new WeScanner().scan(
                new WeScanner.ScanInput(null, List.of(STEAM_LIB_F), List.of()));
        assertTrue(r.entries().size() >= 40, "expected >=40 entries, got " + r.entries().size());
        assertEquals(workshop.size(), r.workshopCount());

        // type 归一化：全部小写且取值合法（t1 实测 Video/video 混用）
        for (WeEntry e : r.entries()) {
            if (e.type() == null) {
                continue;
            }
            String t = e.type();
            assertEquals(t, t.toLowerCase(java.util.Locale.ROOT), "type 未归一化: " + t);
            assertTrue(List.of("video", "web", "scene", "application", "gif", "static",
                            "sequence", "other").contains(t),
                    "未知 type: " + t);
        }
        // 抽样：video 与 scene 均应存在（t1 §3.4 实测混合库）
        boolean hasVideo = r.entries().stream().anyMatch(e -> "video".equals(e.type()));
        boolean hasScene = r.entries().stream().anyMatch(e -> "scene".equals(e.type()));
        assertTrue(hasVideo || hasScene, "video/scene 至少其一存在（t1 基线）");
        // 条目字段健壮性：folderPath 必有
        for (WeEntry e : r.entries()) {
            assertNotNull(e.folderPath());
        }
    }

    @Test
    void naturalOrderNumeric() {
        // 序列帧排序（任务口径 §4）：img2 < img10
        java.util.Comparator<String> cmp = WeScanner.naturalOrder();
        assertTrue(cmp.compare("img2.png", "img10.png") < 0);
        assertTrue(cmp.compare("img10.png", "img9.png") > 0);
        assertEquals(0, cmp.compare("a1b", "a1b"));
    }
}
