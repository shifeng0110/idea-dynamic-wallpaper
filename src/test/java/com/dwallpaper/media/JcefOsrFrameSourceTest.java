package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OSR 帧源语义（假绑定，无 CEF）：速率 clamp、暂停/恢复下发、pollFrame 契约、close 生命周期。
 * 与 t4 状态机的接线口径：24fps 上限 / 失焦 8fps / 空闲暂停 setRate(0)（任务口径第 3 条）。
 */
class JcefOsrFrameSourceTest {

    /** 记录型假绑定：验证 BrowserBinding 调用序列。 */
    private static final class FakeBinding implements BrowserBinding {
        boolean started;
        boolean stopped;
        boolean navigationDisabled;
        boolean audioMuted;
        boolean visibility = true;
        int windowlessRate = -1;
        int lastResizedW = -1;
        int lastResizedH = -1;
        final List<String> scripts = new ArrayList<>();

        @Override
        public void start(String pageUrl) {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public boolean isRunning() {
            return started && !stopped;
        }

        @Override
        public void setWindowlessFrameRate(int fps) {
            windowlessRate = fps;
        }

        @Override
        public void setWindowVisibility(boolean visible) {
            visibility = visible;
        }

        @Override
        public void wasResized(int width, int height) {
            lastResizedW = width;
            lastResizedH = height;
        }

        @Override
        public void runJavaScript(String script) {
            scripts.add(script);
        }

        @Override
        public void disableNavigation() {
            navigationDisabled = true;
        }

        @Override
        public void muteAudio() {
            audioMuted = true;
            runJavaScript(JcefOsrFrameSource.MUTE_MEDIA_JS);
        }

        @Override
        public javax.swing.JComponent osrUiComponent() {
            return null;
        }
    }

    @Test
    void rateClampedToWindowlessRange() {
        FakeBinding binding = new FakeBinding();
        JcefOsrFrameSource source = new JcefOsrFrameSource(binding, new FrameSlot(), null, 24);
        assertEquals(24, source.getRate());

        source.setRate(50); // 超上限 → clamp 30（§9.3 显式压低）
        assertEquals(JcefOsrFrameSource.MAX_RATE, binding.windowlessRate);
        assertEquals(JcefOsrFrameSource.MAX_RATE, source.getRate());

        source.setRate(8); // 失焦降级口径（t4 状态机下发）
        assertEquals(8, binding.windowlessRate);
        assertTrue(binding.visibility);
        assertFalse(source.isPaused());

        source.setRate(-1); // 暂停
        assertTrue(source.isPaused());
        // 【零渲染修复 2026-11-19】暂停不再 setWindowVisibility(false)——CEF windowless
        // 隐藏后渲染管线停摆且未必恢复（真实环境实证恢复全程零 onPaint）。
        assertTrue(binding.visibility, "暂停必须保持渲染层活动（不再隐藏窗口）");
        assertTrue(binding.scripts.stream().anyMatch(s -> s.contains(".pause()")), "暂停脚本下发");
        assertNull(source.pollFrame(), "挂起时不向管线供帧");

        source.setRate(24); // 恢复
        assertFalse(source.isPaused());
        assertTrue(binding.visibility);
        assertTrue(binding.scripts.stream().anyMatch(s -> s.contains(".play()")), "恢复脚本下发");
    }

    @Test
    void pollDelegatesToSlotContract() {
        FrameSlot slot = new FrameSlot();
        JcefOsrFrameSource source = new JcefOsrFrameSource(new FakeBinding(), slot, null, 24);
        assertNull(source.pollFrame(), "无新帧 → null（FrameSource 契约）");
        assertTrue(source.isDynamic());

        BufferedImage image = slot.acquire(3, 2);
        slot.publish(image);
        Frame frame = source.pollFrame();
        assertNotNull(frame);
        assertEquals(3, frame.sourceWidth());
        assertEquals(2, frame.sourceHeight());
        assertNull(source.pollFrame(), "同帧二次 poll → null");
    }

    @Test
    void renderSizeAndMuteWiring() {
        FakeBinding binding = new FakeBinding();
        JcefOsrFrameSource source = new JcefOsrFrameSource(binding, new FrameSlot(), null, 24);
        source.setRenderSize(2560, 1440); // t4 以 WallpaperSurface 实际像素接线
        assertEquals(2560, binding.lastResizedW);
        assertEquals(1440, binding.lastResizedH);

        binding.muteAudio();
        assertTrue(binding.audioMuted);
        assertTrue(binding.scripts.stream().anyMatch(s -> s.contains("muted=true")), "静音脚本下发（任务口径第 3 条）");
    }

    @Test
    void closeDisposesBindingAndStopsPolling() {
        FakeBinding binding = new FakeBinding();
        JcefOsrFrameSource source = new JcefOsrFrameSource(binding, new FrameSlot(), null, 24);
        source.close();
        assertTrue(binding.stopped, "close 必须 dispose browser（AC-MEDIA-6）");
        assertNull(source.pollFrame(), "关闭后 pollFrame 恒 null");
        assertFalse(source.isDynamic() && !source.isDynamic(), "isDynamic 语义不变");
    }

    @Test
    void muteScriptAndPauseScriptWellFormed() {
        assertTrue(JcefOsrFrameSource.MUTE_MEDIA_JS.contains("muted=true"));
        assertTrue(JcefOsrFrameSource.MUTE_MEDIA_JS.contains("audio,video"));
        assertTrue(JcefOsrFrameSource.PAUSE_MEDIA_JS.contains("pause()"));
        assertTrue(JcefOsrFrameSource.RESUME_MEDIA_JS.contains("play()"));
    }
}
