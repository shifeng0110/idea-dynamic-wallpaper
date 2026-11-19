package com.dwallpaper.core;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 轻量防抖器（design.md §5.2）：滑杆变更 → 防抖 200ms → 单次异步重算。
 * EDT 安全：非 EDT 调用自动投递。
 */
public final class Debouncer {

    private final Timer timer;
    private final Runnable action;

    public Debouncer(int delayMs, Runnable action) {
        this.action = action;
        this.timer = new Timer(delayMs, e -> {
            ((Timer) e.getSource()).stop();
            this.action.run();
        });
        this.timer.setRepeats(false);
    }

    public void ping() {
        if (SwingUtilities.isEventDispatchThread()) {
            timer.restart();
        } else {
            SwingUtilities.invokeLater(timer::restart);
        }
    }

    public void dispose() {
        timer.stop();
    }
}
