package com.dwallpaper.media;

/**
 * media 包日志门面（t5）：platform Logger 反射承载 + stderr 兜底。
 * 动机（design.md §9.5 模块纪律）：media 包须在无 platform classpath 的纯 JVM 单测中可加载，
 * 不能持有 com.intellij.openapi.diagnostic.Logger 静态字段；IDE 运行时经反射取得真实 Logger，
 * 测试 JVM 降级为 stderr —— 保证降级路径日志在两种环境均可见（任务口径第 5 条）。
 */
public final class MediaLog {

    private static volatile Object platformLogger; // com.intellij.openapi.diagnostic.Logger 或 null
    private static volatile boolean resolved;

    private MediaLog() {
    }

    public static void info(String message) {
        log("INFO", message, null);
    }

    public static void warn(String message) {
        log("WARN", message, null);
    }

    public static void warn(String message, Throwable error) {
        log("WARN", message, error);
    }

    private static void log(String level, String message, Throwable error) {
        Object logger = loggerOrNull();
        if (logger != null) {
            try {
                Class<?> c = logger.getClass();
                if (error == null) {
                    c.getMethod("INFO".equals(level) ? "info" : "warn", String.class).invoke(logger, message);
                } else {
                    c.getMethod("warn", String.class, Throwable.class).invoke(logger, message, error);
                }
                return;
            } catch (Throwable ignored) {
                // 反射失败落 stderr 兜底
            }
        }
        System.err.println("[dwallpaper.media][" + level + "] " + message + (error != null ? " :: " + error : ""));
        if (error != null) {
            error.printStackTrace(System.err);
        }
    }

    private static Object loggerOrNull() {
        if (resolved) {
            return platformLogger;
        }
        synchronized (MediaLog.class) {
            if (!resolved) {
                try {
                    Class<?> c = Class.forName("com.intellij.openapi.diagnostic.Logger");
                    platformLogger = c.getMethod("getInstance", String.class).invoke(null, "com.dwallpaper.media");
                } catch (Throwable t) {
                    platformLogger = null; // 纯 JVM（单测）：无 platform classpath
                }
                resolved = true;
            }
        }
        return platformLogger;
    }
}
