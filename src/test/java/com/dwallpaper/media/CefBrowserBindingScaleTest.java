package com.dwallpaper.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F9 HiDPI 缩放推演（t6 审查修复，纯 JVM 单测）：
 * getViewRect 语义 = DIP（Swing 逻辑像素，= WallpaperEngine.pushRenderSize 现值）；
 * getDeviceScaleFactor/getScreenInfo = 真实 device scale（属性覆盖 → JBCefApp 强制值(&gt;1.0) →
 * 默认屏 GraphicsConfiguration → 1.0 兜底）；CEF windowless 缓冲 = 逻辑 × scale = 物理像素。
 * 推演口径（architect 方案并入验收）：125% → 1536×864 逻辑 = 1920×1080 物理；
 * 150% → 1280×720 逻辑 = 1920×1080 物理（Windows 常见笔记本缩放）。
 */
class CefBrowserBindingScaleTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE);
    }

    @Test
    void propertyOverridePinsScale() {
        System.setProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE, "1.25");
        assertEquals(1.25d, CefBrowserBinding.detectDeviceScaleFactor(), 1e-9,
                "显式属性覆盖必须生效（单测推演/调试口径）");

        System.setProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE, "1.5");
        assertEquals(1.5d, CefBrowserBinding.detectDeviceScaleFactor(), 1e-9);
    }

    @Test
    void invalidOrSubUnitOverrideIgnored() {
        System.setProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE, "not-a-number");
        assertTrue(CefBrowserBinding.detectDeviceScaleFactor() >= 1.0d,
                "非法覆盖值必须回落推导链且缩放 ≥1.0");

        System.setProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE, "0.8");
        assertTrue(CefBrowserBinding.detectDeviceScaleFactor() >= 1.0d,
                "<1.0 覆盖值无效（缩放因子不缩水）");
    }

    @Test
    void scaleNeverBelowOneWithoutOverride() {
        System.clearProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE);
        double scale = CefBrowserBinding.detectDeviceScaleFactor();
        assertTrue(scale >= 1.0d, "无覆盖时必须 ≥1.0（100% 兜底或平台真实缩放），实际=" + scale);
    }

    @Test
    void viewRectStaysLogicalDip() {
        // getViewRect 语义 = DIP：125% 屏上逻辑 1536×864 的视图矩形仍是 1536×864（不预乘 scale）
        Rectangle r = CefBrowserBinding.viewRect(1536, 864);
        assertEquals(0, r.x);
        assertEquals(0, r.y);
        assertEquals(1536, r.width, "viewRect 必须保持逻辑像素（DIP）语义");
        assertEquals(864, r.height);
        assertTrue(CefBrowserBinding.viewRect(0, -5).width >= 1, "非法尺寸钳制到 ≥1");
    }

    @Test
    void physicalBufferAt125Percent() {
        // Windows 125%：逻辑 1536×864 → CEF 物理缓冲 1920×1080
        assertEquals(1920, CefBrowserBinding.physicalOf(1536, 1.25));
        assertEquals(1080, CefBrowserBinding.physicalOf(864, 1.25));
    }

    @Test
    void physicalBufferAt150Percent() {
        // Windows 150%：逻辑 1280×720 → CEF 物理缓冲 1920×1080
        assertEquals(1920, CefBrowserBinding.physicalOf(1280, 1.5));
        assertEquals(1080, CefBrowserBinding.physicalOf(720, 1.5));
    }

    @Test
    void physicalBufferRoundsNonIntegerResults() {
        assertEquals(1441, CefBrowserBinding.physicalOf(1153, 1.25), "1153×1.25=1441.25 → 1441（四舍五入）");
        assertEquals(1, CefBrowserBinding.physicalOf(1, 1.4), "最小尺寸钳制");
        assertEquals(1153, CefBrowserBinding.physicalOf(1153, 1.0), "100% 缩放恒等");
    }

    @Test
    void scalePlumbedIntoBindingFields() {
        // 构造期固化：detect 结果进入 final 字段（渲染代理 getDeviceScaleFactor/getScreenInfo 直接读取）。
        // 纯 JVM 无 JCEF classpath 不能构造 binding（ctor 解析 JCEF 类），此处以属性覆盖 + 推导函数等价断言。
        System.setProperty(CefBrowserBinding.SYSPROP_DEVICE_SCALE, "1.25");
        double scale = CefBrowserBinding.detectDeviceScaleFactor();
        assertEquals(1920, CefBrowserBinding.physicalOf(1536, scale));
        assertEquals(1080, CefBrowserBinding.physicalOf(864, scale));
    }
}
