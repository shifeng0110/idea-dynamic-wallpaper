package com.dwallpaper.core;

import com.intellij.openapi.diagnostic.Logger;

import javax.swing.Timer;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * 帧率与暂停状态机（design.md §7）：
 *  RUNNING = 上限（默认 24fps，可调 5–30）；DEGRADED = 8fps（IDE 窗口失焦）；IDLE_PAUSED = 冻结
 *  （连续 60 秒无输入，可开关默认开）；MANUAL_PAUSED = 冻结（面板暂停按钮）。
 * 输入检测 = Toolkit AWTEventListener（KEY|MOUSE|WHEEL）仅更新 lastActivity 时间戳（零成本聚合，§7）。
 * 3 秒看门狗复核（借 DSH 失焦卡死教训，t1 §4.3）：每 3s 重算状态，判定变化才切换，防事件丢失卡死。
 */
public final class FrameRateController {

    public enum State {
        RUNNING, DEGRADED, IDLE_PAUSED, MANUAL_PAUSED
    }

    public static final int DEFAULT_RUNNING_FPS = 30;
    public static final int MIN_FPS = 5;
    public static final int MAX_FPS = 60;
    public static final int DEGRADED_FPS = 15;
    public static final long WATCHDOG_INTERVAL_MS = 3000;

    /** fps 上限钳制（5–30，§7）。 */
    public static int clampFps(int fps) {
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    private static final Logger LOG = Logger.getInstance(FrameRateController.class);

    private volatile boolean manualPaused;
    private volatile boolean idlePauseEnabled = true;
    private volatile int idleTimeoutSec = 60;
    private volatile int runningFps = DEFAULT_RUNNING_FPS;
    private volatile long lastActivityMs = System.currentTimeMillis();
    private volatile State state = State.RUNNING;
    private volatile BooleanSupplier focusedOnIde = () -> true;
    private volatile IntConsumer fpsListener;
    private volatile Runnable stateListener;

    private AWTEventListener awtListener;
    private Timer watchdog;

    public State state() {
        return state;
    }

    public void setFocusedOnIde(BooleanSupplier supplier) {
        this.focusedOnIde = supplier;
    }

    /** 状态变化回调（EDT）；用于调度器调整泵间隔与面板状态行。 */
    public void setFpsListener(IntConsumer listener) {
        this.fpsListener = listener;
    }

    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    public boolean isManualPaused() {
        return manualPaused;
    }

    public void setManualPaused(boolean paused) {
        this.manualPaused = paused;
        touchIdle();
        recompute();
    }

    public boolean isIdlePauseEnabled() {
        return idlePauseEnabled;
    }

    public void setIdlePauseEnabled(boolean enabled) {
        this.idlePauseEnabled = enabled;
        recompute();
    }

    public int getIdleTimeoutSec() {
        return idleTimeoutSec;
    }

    public void setIdleTimeoutSec(int seconds) {
        this.idleTimeoutSec = Math.max(5, seconds);
        recompute();
    }

    public int getRunningFps() {
        return runningFps;
    }

    public void setRunningFps(int fps) {
        this.runningFps = Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
        recompute();
    }

    /** 任意输入/交互刷新空闲计时。 */
    public void touchIdle() {
        lastActivityMs = System.currentTimeMillis();
    }

    /** 当前状态下的泵帧率（暂停态为 0）。 */
    public int currentFps() {
        return switch (state) {
            case RUNNING -> runningFps;
            case DEGRADED -> DEGRADED_FPS;
            default -> 0;
        };
    }

    public void install() {
        if (awtListener != null) {
            return;
        }
        long mask = AWTEventMask.KEY | AWTEventMask.MOUSE | AWTEventMask.MOUSE_MOTION | AWTEventMask.WHEEL;
        awtListener = event -> lastActivityMs = System.currentTimeMillis();
        Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, mask);
        watchdog = new Timer((int) WATCHDOG_INTERVAL_MS, e -> recompute());
        watchdog.setRepeats(true);
        watchdog.start();
        recompute();
    }

    public void uninstall() {
        if (awtListener != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
            } catch (Throwable ignored) {
                // 已移除/环境退出
            }
            awtListener = null;
        }
        if (watchdog != null) {
            watchdog.stop();
            watchdog = null;
        }
    }

    /** 看门狗复核：重算状态，判定变化才切换（§7，防事件丢失卡死）。 */
    public void recompute() {
        boolean idleTimedOut =
                System.currentTimeMillis() - lastActivityMs >= idleTimeoutSec * 1000L;
        State next = compute(manualPaused, idlePauseEnabled, idleTimedOut, focusedOnIde.getAsBoolean());
        if (next != state) {
            state = next;
            LOG.info("dwallpaper: frame rate state -> " + next + " (fps=" + currentFps() + ")");
            IntConsumer fl = fpsListener;
            if (fl != null) {
                fl.accept(currentFps());
            }
            Runnable sl = stateListener;
            if (sl != null) {
                sl.run();
            }
        }
    }

    /** 纯函数（单元测试对象）：优先级 MANUAL > IDLE > 失焦降帧 > 正常。 */
    static State compute(boolean manualPaused, boolean idleEnabled, boolean idleTimedOut, boolean focused) {
        if (manualPaused) {
            return State.MANUAL_PAUSED;
        }
        if (idleEnabled && idleTimedOut) {
            return State.IDLE_PAUSED;
        }
        return focused ? State.RUNNING : State.DEGRADED;
    }

    /** AWT 事件掩码集中定义（避免魔数）。 */
    private static final class AWTEventMask {
        static final long KEY = KeyEvent.KEY_EVENT_MASK;
        static final long MOUSE = MouseEvent.MOUSE_EVENT_MASK;
        static final long MOUSE_MOTION = MouseEvent.MOUSE_MOTION_EVENT_MASK;
        static final long WHEEL = MouseEvent.MOUSE_WHEEL_EVENT_MASK;
    }
}
