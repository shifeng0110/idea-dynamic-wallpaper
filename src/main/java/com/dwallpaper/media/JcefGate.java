package com.dwallpaper.media;

/**
 * JCEF 可用性门控（design.md §9.2，t5 实现）：
 * <pre>
 * isJcefUsable() = Class.forName("com.intellij.ui.jcef.JBCefApp") 成功
 *               && JBCefApp.isSupported()                       // 覆盖无 JCEF JDK / registry 关闭 / headless
 *               && JBCefApp.isOffScreenRenderingModeEnabled()   // OSR 注册表检查（262 实测存在，任务口径）
 * </pre>
 * 全部经反射承载（media 包不 import JCEF 类，design.md §9.5），任一环节失败即降级：
 * video/web 条目禁选 + 明确日志 + UI 提示（BALLOON 通知，notificationGroup "dwallpaper" 见 plugin.xml）
 * 回退 t4 能力（GIF/序列帧/静态照常，design.md §8.2「需 JCEF」徽标同源口径）。
 * 乐观例外：未来版本若缺失 isOffScreenRenderingModeEnabled 方法（NoSuchMethodException），
 * 不做硬降级（仅告警），isSupported() 已覆盖主判定 —— 262 实测该方法存在（t5 DumpClass 核对）。
 */
public final class JcefGate {

    /** 降级 UI 提示/日志固定话术（任务口径）。 */
    public static final String JCEF_UNAVAILABLE_HINT =
            "JCEF 不可用：视频/网页壁纸已禁用，回退 GIF/序列帧/静态能力";

    private static final String CLS_APP = "com.intellij.ui.jcef.JBCefApp";

    private static volatile Boolean usable;
    private static volatile String reason;
    private static volatile boolean notificationAttempted;

    private JcefGate() {
    }

    /** 是否可用（结果缓存；测试经 resetForTest 重置）。 */
    public static boolean isJcefUsable() {
        Boolean u = usable;
        if (u != null) {
            return u;
        }
        synchronized (JcefGate.class) {
            if (usable == null) {
                evaluate();
            }
            return usable;
        }
    }

    /** 不可用原因（可用时返回 null）。 */
    public static String unavailableReason() {
        isJcefUsable();
        return reason;
    }

    /** 面板状态行口径（design.md §8.2）：可用 / 不可用 + 原因 + 回退提示。 */
    public static String describeState() {
        if (isJcefUsable()) {
            return "JCEF 可用（OSR 就绪）";
        }
        String r = reason;
        return "JCEF 不可用（" + (r == null ? "未知原因" : r) + "）；" + JCEF_UNAVAILABLE_HINT;
    }

    /** 降级 UI 提示（每次会话至多一次；通知组缺失时仅日志）。 */
    public static void showDegradedNotificationOnce() {
        if (notificationAttempted) {
            return;
        }
        notificationAttempted = true;
        notifyDegraded();
        MediaLog.warn(JCEF_UNAVAILABLE_HINT + "；原因：" + unavailableReason());
    }

    /** 仅测试用：重置缓存判定。 */
    public static void resetForTest() {
        synchronized (JcefGate.class) {
            usable = null;
            reason = null;
        }
        notificationAttempted = false;
    }

    private static void evaluate() {
        Class<?> app;
        try {
            app = Class.forName(CLS_APP);
        } catch (Throwable t) {
            usable = Boolean.FALSE;
            reason = "JCEF 类缺失（运行环境无 JCEF：替代 JDK / 未分发 jcef-plugin）";
            return;
        }
        try {
            Object supported = app.getMethod("isSupported").invoke(null);
            if (!Boolean.TRUE.equals(supported)) {
                usable = Boolean.FALSE;
                reason = "JBCefApp.isSupported()=false（JCEF 被禁用或环境不支持，如 ide.browser.jcef.enabled=false / headless）";
                return;
            }
        } catch (Throwable t) {
            usable = Boolean.FALSE;
            reason = "调用 JBCefApp.isSupported() 失败：" + t;
            return;
        }
        try {
            Object osr = app.getMethod("isOffScreenRenderingModeEnabled").invoke(null);
            if (!Boolean.TRUE.equals(osr)) {
                usable = Boolean.FALSE;
                reason = "OSR 注册表未启用（ide.browser.jcef.osr.enabled）";
            } else {
                usable = Boolean.TRUE;
                reason = null;
            }
        } catch (NoSuchMethodException missing) {
            // 乐观通过：方法在 262 实测存在；未来版本缺失时不硬降级（isSupported 已覆盖主判定）
            MediaLog.warn("isOffScreenRenderingModeEnabled 缺失（非 262 基线？），OSR 可用性按 isSupported() 放行");
            usable = Boolean.TRUE;
            reason = null;
        } catch (Throwable t) {
            usable = Boolean.FALSE;
            reason = "OSR 注册表检查失败：" + t;
        }
    }

    private static void notifyDegraded() {
        try {
            Class<?> mgrCls = Class.forName("com.intellij.notification.NotificationGroupManager");
            Object mgr = mgrCls.getMethod("getInstance").invoke(null);
            Object group = mgrCls.getMethod("getNotificationGroup", String.class).invoke(mgr, "dwallpaper");
            Class<?> typeCls = Class.forName("com.intellij.notification.NotificationType");
            Object warning = enumValue(typeCls, "WARNING");
            Object notification = group.getClass()
                    .getMethod("createNotification", String.class, String.class, typeCls)
                    .invoke(group, "Dynamic Wallpaper", JCEF_UNAVAILABLE_HINT, warning);
            notification.getClass()
                    .getMethod("notify", Class.forName("com.intellij.openapi.project.Project"))
                    .invoke(notification, (Object[]) null);
        } catch (Throwable t) {
            MediaLog.warn("UI 提示通道不可用（仅日志降级提示）：" + t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumCls, String name) {
        return Enum.valueOf((Class) enumCls, name);
    }
}
