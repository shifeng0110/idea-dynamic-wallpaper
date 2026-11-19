package com.dwallpaper.welibrary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WE 定位/扫描单元测试（design.md AC-WL-1/2/3/4，本机实测数据 F:\SteamLibrary）。
 * 依赖本机真实 WE 安装 —— 环境缺失时按 assumeTrue 跳过（不误报失败）。
 */
class WeLocatorTest {

    private static final Path STEAM_LIB_F = Path.of("F:\\SteamLibrary");
    private static final Path STEAM_LIB_E = Path.of("E:\\Steam");

    @Test
    void registryChainHitsSteamPath() {
        // AC-WL-1：注册表链命中本机 SteamPath（无覆盖、无 JNA/子进程异常时）
        WeLocator.SteamRoots roots = WeLocator.locate("");
        if (roots.steamRoot() == null) {
            assumeTrue(false, "no steam registry entry on this machine");
        }
        assertTrue(Files.isDirectory(roots.steamRoot()));
        assertTrue(roots.libraries().size() >= 1, "至少含 Steam 根自身库");
    }

    @Test
    void installRootDetectedOnRealF() {
        // AC-WL-2 正例：F:\SteamLibrary 完整安装（wallpaper32/64.exe + distribution\version.json）
        assumeTrue(Files.isDirectory(STEAM_LIB_F), "F:\\SteamLibrary not present");
        Path root = WeLocator.installRootOf(STEAM_LIB_F);
        assertNotNull(root, "F:\\SteamLibrary 应命中安装根");
        assertTrue(Files.isDirectory(root.resolve("projects")));
    }

    @Test
    void residueNotMisdetected() {
        // AC-WL-2 反例：E:\Steam 残留（无 exe、无 distribution\version.json）不得产生安装根
        assumeTrue(Files.isDirectory(STEAM_LIB_E), "E:\\Steam not present");
        assertNull(WeLocator.installRootOf(STEAM_LIB_E), "E:\\Steam 残留不得判为安装根");
    }

    @Test
    void missingDirsTolerated() {
        // AC-WL-4：downloaded 缺失不报错（构造缺目录库验证）
        try {
            Path tmp = Files.createTempDirectory("dwl-lib-test");
            assertNull(WeLocator.installRootOf(tmp));
            assertTrue(WeLocator.workshopDirs(tmp).isEmpty());
            WeScanner.ScanResult r = new WeScanner().scan(
                    new WeScanner.ScanInput(null, List.of(tmp), List.of()));
            assertNotNull(r);
            assertEquals(0, r.entries().size());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void workshopDirs46OnRealF() {
        // AC-WL-3 前置：工坊 431960 目录 46 项（t1 实测基线 ≥40）
        assumeTrue(Files.isDirectory(STEAM_LIB_F), "F:\\SteamLibrary not present");
        List<Path> dirs = WeLocator.workshopDirs(STEAM_LIB_F);
        assertTrue(dirs.size() >= 40, "expected >=40 workshop entries, got " + dirs.size());
    }
}
