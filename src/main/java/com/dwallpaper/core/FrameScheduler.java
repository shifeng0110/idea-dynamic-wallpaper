package com.dwallpaper.core;

import javax.swing.Timer;
import java.awt.event.ActionListener;

/**
 * EDT 帧泵（design.md §4.3/§7）：自适应 delay=1000/fps；fps 由 FrameRateController 状态机给定
 * （RUNNING=上限（默认 24，可调 5–30）/ DEGRADED=8 / 暂停=0）。media 层产帧 pace 由帧源自限
 * （GIF 帧间隔取元数据），泵只取最新帧交调节管线（单槽丢帧）。
 */
public final class FrameScheduler {

    /** 默认帧率上限（t5 JcefOsrFrameSource 工厂口径引用；§7 可调 5–30）。 */
    public static final int DEFAULT_FPS = 24;

    private final AdjustmentPipeline pipeline;
    private volatile FrameSource source;
    private volatile int fps = FrameRateController.DEFAULT_RUNNING_FPS;
    private Timer timer;

    public FrameScheduler(AdjustmentPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public void start(FrameSource source) {
        this.source = source;
        restartTimer();
    }

    public void setFps(int fps) {
        this.fps = Math.max(0, fps);
        restartTimer();
    }

    public void stop() {
        this.source = null;
        restartTimer();
    }

    private void restartTimer() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            doRestart();
        } else {
            javax.swing.SwingUtilities.invokeLater(this::doRestart);
        }
    }

    private void doRestart() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        FrameSource s = source;
        if (s == null || fps <= 0) {
            return;
        }
        int delay = Math.max(1000 / fps, 10);
        timer = new Timer(delay, pumpAction());
        timer.setRepeats(true);
        timer.start();
    }

    private ActionListener pumpAction() {
        return e -> {
            FrameSource s = source;
            if (s == null) {
                return;
            }
            try {
                Frame f = s.pollFrame();
                if (f != null) {
                    pipeline.submit(f);
                }
            } catch (Throwable ignored) {
                // 单次泵失败不中断
            }
        };
    }
}
