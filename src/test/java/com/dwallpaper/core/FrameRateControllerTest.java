package com.dwallpaper.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 帧率状态机单元测试（design.md §7 + AC-FPS-2/3 逻辑层）。 */
class FrameRateControllerTest {

    @Test
    void priorityManualOverIdle() {
        assertEquals(FrameRateController.State.MANUAL_PAUSED,
                FrameRateController.compute(true, true, true, true));
    }

    @Test
    void idlePausesWhenTimedOut() {
        assertEquals(FrameRateController.State.IDLE_PAUSED,
                FrameRateController.compute(false, true, true, true));
    }

    @Test
    void idleDisabledKeepsRunning() {
        assertEquals(FrameRateController.State.RUNNING,
                FrameRateController.compute(false, false, true, true));
    }

    @Test
    void focusLossDegrades() {
        assertEquals(FrameRateController.State.DEGRADED,
                FrameRateController.compute(false, true, false, false));
        assertEquals(FrameRateController.State.RUNNING,
                FrameRateController.compute(false, true, false, true));
    }

    @Test
    void degradedFpsIs8() {
        FrameRateController c = new FrameRateController();
        assertEquals(FrameRateController.DEGRADED_FPS, FrameRateController.compute(false, false, false, false) == FrameRateController.State.DEGRADED ? 8 : -1);
    }
}
