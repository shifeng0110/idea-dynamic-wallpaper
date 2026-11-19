package com.dwallpaper.media;

import javax.swing.JPanel;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;

/**
 * JCEF OSR 绑定的反射实现（design.md §9.3，t5）。
 * <p>API 事实核对（t5 经 DumpClass 对 262.10968.63 安装件实测签名）：</p>
 * <ul>
 *   <li>JBCefBrowserBuilder：setOffScreenRendering(boolean) / setWindowlessFramerate(int) /
 *       setMouseWheelEventEnable(boolean) / setEnableOpenDevToolsMenuItem(boolean) /
 *       setOSRHandlerFactory(JBCefOSRHandlerFactory) / setUrl(String) / build()；</li>
 *   <li>JBCefOSRHandlerFactory：getInstance() + createComponent(boolean) /
 *       createCefRenderHandler(JComponent) / createScreenBoundsProvider()；</li>
 *   <li>org.cef.handler.CefRenderHandler：onPaint(CefBrowser, boolean, java.awt.Rectangle[],
 *       ByteBuffer, int, int)（本 JCEF 分支 dirtyRects 为 java.awt.Rectangle[]）/
 *       getViewRect / getScreenInfo / getScreenPoint / getDeviceScaleFactor / startDragging /
 *       onCursorChange；</li>
 *   <li>org.cef.browser.CefBrowser：setWindowlessFrameRate(int) / setWindowVisibility(boolean) /
 *       wasResized(int, int) / executeJavaScript(...)</li>
 *   <li>JBCefBrowserBase：getCefBrowser() / getJBCefClient() / runJavaScript(String) /
 *       disableNavigation() / dispose()；</li>
 *   <li>JBCefClient：addLoadHandler(org.cef.handler.CefLoadHandler, CefBrowser) /
 *       removeLoadHandler(...)。</li>
 * </ul>
 * <p>模块纪律（design.md §9.5）：全部 JCEF 类引用经反射（无 jcef classpath 时构造抛异常 → 上层降级）；
 * 本类与 JcefGate 是 media 包仅有的两处 JCEF 触点。OSR handler 经动态 Proxy 拦截 onPaint ——
 * 同步拷贝 BGRA→ARGB 进 FrameSlot（CEF 线程禁止回存/长驻），其余方法按 262 语义最小实现。</p>
 * <p><b>F9（t6 审查修复）HiDPI 语义</b>：getViewRect = DIP（Swing 逻辑像素，即
 * WallpaperEngine.pushRenderSize 传入的 surface.getWidth()/getHeight()）；getDeviceScaleFactor /
 * getScreenInfo 按真实 device scale（属性覆盖 → JBCefApp.getForceDeviceScaleFactor(&gt;1.0) →
 * 默认屏 GraphicsConfiguration → 1.0 兜底）上报 —— CEF windowless 缓冲 = viewRect × scale =
 * <b>物理像素</b>（125%/150% 缩放下 onPaint w/h 为物理分辨率，FrameSlot 图像即物理尺寸，页面不再按
 * 逻辑分辨率渲染后放大发糊）。onPaint w/h 为物理像素，FrameSlot 上限按物理尺寸核算。</p>
 */
final class CefBrowserBinding implements BrowserBinding {

    private static final String CLS_APP = "com.intellij.ui.jcef.JBCefApp";
    private static final String CLS_BUILDER = "com.intellij.ui.jcef.JBCefBrowserBuilder";
    private static final String CLS_OSR_FACTORY = "com.intellij.ui.jcef.JBCefOSRHandlerFactory";
    private static final String CLS_RENDER_HANDLER = "org.cef.handler.CefRenderHandler";
    private static final String CLS_LOAD_HANDLER = "org.cef.handler.CefLoadHandler";
    private static final String CLS_CEF_BROWSER = "org.cef.browser.CefBrowser";

    /** F9：device scale 显式覆盖属性（单测推演/调试；≥1.0 才生效）。 */
    static final String SYSPROP_DEVICE_SCALE = "dwallpaper.jcef.deviceScale";

    private final FrameSlot slot;
    private final boolean forWeb;
    private final int initialRate;
    /** F9：真实设备缩放因子（构造期推导；volatile 供 CEF 渲染代理线程读取）。 */
    private final double deviceScaleFactor;

    private ClassLoader jcefClassLoader;
    private Class<?> builderCls;
    private Class<?> factoryCls;
    private Class<?> renderHandlerCls;
    private Class<?> loadHandlerCls;
    private Class<?> cefBrowserCls;

    private Object builder;
    private volatile Object browser;      // com.intellij.ui.jcef.JBCefBrowser
    private volatile Object cefBrowser;   // org.cef.browser.CefBrowser
    private volatile Object client;       // com.intellij.ui.jcef.JBCefClient
    private Object loadHandlerProxy;
    private volatile int renderWidth;
    private volatile int renderHeight;
    private volatile boolean stopped;

    /** 构建期解析类与关键方法（fail-fast，异常信息直接指明缺失面）。 */
    CefBrowserBinding(FrameSlot slot, boolean forWeb, int initialRate, int renderWidth, int renderHeight)
            throws Exception {
        this.slot = slot;
        this.forWeb = forWeb;
        this.initialRate = Math.max(JcefOsrFrameSource.MIN_RATE,
                Math.min(JcefOsrFrameSource.MAX_RATE, initialRate));
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.deviceScaleFactor = detectDeviceScaleFactor();

        Class<?> app = Class.forName(CLS_APP);
        jcefClassLoader = app.getClassLoader();
        builderCls = Class.forName(CLS_BUILDER, true, jcefClassLoader);
        factoryCls = Class.forName(CLS_OSR_FACTORY, true, jcefClassLoader);
        renderHandlerCls = Class.forName(CLS_RENDER_HANDLER, true, jcefClassLoader);
        loadHandlerCls = Class.forName(CLS_LOAD_HANDLER, true, jcefClassLoader);
        cefBrowserCls = Class.forName(CLS_CEF_BROWSER, true, jcefClassLoader);

        requireMethod(builderCls, "setOffScreenRendering", boolean.class);
        requireMethod(builderCls, "setWindowlessFramerate", int.class);
        requireMethod(builderCls, "setMouseWheelEventEnable", boolean.class);
        requireMethod(builderCls, "setEnableOpenDevToolsMenuItem", boolean.class);
        requireMethod(builderCls, "setUrl", String.class);
        requireMethod(builderCls, "build");
        requireMethod(factoryCls, "getInstance");
        requireMethod(renderHandlerCls, "onPaint", cefBrowserCls, boolean.class,
                Rectangle[].class, ByteBuffer.class, int.class, int.class);
        requireMethod(cefBrowserCls, "setWindowlessFrameRate", int.class);
        requireMethod(cefBrowserCls, "setWindowVisibility", boolean.class);
        requireMethod(cefBrowserCls, "wasResized", int.class, int.class);

        builder = builderCls.getDeclaredConstructor().newInstance();
        invoke(builder, "setOffScreenRendering", new Class[]{boolean.class},
                new Object[]{Boolean.TRUE}); // §9.3：显式开启，不依赖注册表默认
        invoke(builder, "setWindowlessFramerate", new Class[]{int.class},
                new Object[]{initialRate}); // §9.3：显式压低默认 120 回退值（≤30）
        invoke(builder, "setMouseWheelEventEnable", new Class[]{boolean.class},
                new Object[]{Boolean.FALSE}); // §9.5：无输入转发
        invoke(builder, "setEnableOpenDevToolsMenuItem", new Class[]{boolean.class},
                new Object[]{Boolean.FALSE});
        // 【关键修复 2026-11-19】不再注入自定义 OSR handler factory。
        // 实证（javap + idea.log）：自建 factory 的 createComponent 只返回一个裸 JPanel，
        // createCefRenderHandler 只返回自定义代理 —— 于是 JBCef 拿不到真正的
        // JBCefOsrComponent，内部也无法把渲染处理器绑定到组件上：
        // 表现就是我们的 handler 只被 toString 调用过、onPaint 零回调，
        // 组件自绘取帧也因此 100% 透明（OSR 帧为空白 opaque=0.00%）。
        // 交给 JCEF 自己的 JBCefOSRHandlerFactory：JBCefOsrHandler 会把每一帧存进
        // protected volatile JBHiDPIScaledImage myImage，再经反射读取即为权威帧源。
    }

    @Override
    public void start(String pageUrl) {
        try {
            // 【JBCefApp 初始化上下文修复 2026-11-19】JBCefApp 首次初始化（静态 Holder）
            // 请求平台服务（ProxyMigrationService 等），平台要求该初始化发生在 EDT/
            // 平台允许的上下文——后台线程触发会被记录 SEVERE 并使 build 失败
            //（runIde 启动恢复路径实证）。browser 构建整体 invokeAndWait 到 EDT。
            if (!java.awt.EventQueue.isDispatchThread()) {
                final String[] err = new String[1];
                java.awt.EventQueue.invokeAndWait(() -> {
                    try {
                        startOnEdt(pageUrl);
                    } catch (Throwable t) {
                        err[0] = t.toString();
                    }
                });
                if (err[0] != null) {
                    throw new IllegalStateException(err[0]);
                }
                return;
            }
            startOnEdt(pageUrl);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new IllegalStateException(ite.getCause() == null ? ite.toString() : ite.getCause().toString(), ite);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while building browser on EDT", ie);
        }
    }

    private void startOnEdt(String pageUrl) {
        try {
            invoke(builder, "setUrl", new Class[]{String.class}, new Object[]{pageUrl});
            Object created = callR(builder, "build");
            browser = created;
            cefBrowser = callR(created, "getCefBrowser");
            client = callR(created, "getJBCefClient");
            loadHandlerProxy = newLoadHandlerProxy();
            call(client, "addLoadHandler", new Class[]{loadHandlerCls, cefBrowserCls},
                    new Object[]{loadHandlerProxy, cefBrowser});
            if (forWeb) {
                // §9.5 沙箱：web 页面源不可信 → 禁导航（无输入转发另由 builder 与 contains() 保证）
                call(created, "disableNavigation");
            }
            // 构建后推送运行时帧率与渲染尺寸（getViewRect 由渲染代理返回本尺寸）
            call(cefBrowser, "setWindowlessFrameRate", new Class[]{int.class},
                    new Object[]{initialRate});
            call(cefBrowser, "wasResized", new Class[]{int.class, int.class},
                    new Object[]{renderWidth, renderHeight});
            // 【零渲染修复② 2026-11-19】windowless browser 的窗口可见性默认隐藏，
            // 渲染循环不启动（真实遥测实证：handler 被采纳但零 onPaint/零 getViewRect）。
            // 创建即显式置可见，渲染循环启动；挂起暂停由上层 paused 门控供帧。
            call(cefBrowser, "setWindowVisibility", new Class[]{boolean.class},
                    new Object[]{Boolean.TRUE});
            MediaLog.info("JCEF OSR browser 已构建：url=" + pageUrl
                    + " windowlessFramerate=" + initialRate
                    + " viewSize(DIP)=" + renderWidth + "x" + renderHeight
                    + " deviceScale=" + deviceScaleFactor
                    + " bufferPx=" + physicalOf(renderWidth, deviceScaleFactor)
                    + "x" + physicalOf(renderHeight, deviceScaleFactor)
                    + " osr=" + callR(created, "isOffScreenRendering"));
            // 【方案 E：OSR 组件直显 2026-11-19】JBCefBrowser.getComponent() 在 OSR 模式
            // 返回自绘 CEF 帧的 UI 组件（JBCefOsrHandlerBrowser 系）。把它挂到背景层即
            // 可直显视频/网页（不再依赖 onPaint 帧拷贝——遥测实证反射 handler 路径
            // 零 onPaint，为 JBCef 内部分发结构所限）。Engine 负责挂载/卸载与 scrim。
            try {
                osrUiComponentField = (javax.swing.JComponent) callR(created, "getComponent");
                MediaLog.info("JCEF OSR UI 组件已获取：" + osrUiComponentField.getClass().getName());
            } catch (Throwable t) {
                osrUiComponentField = null;
                MediaLog.warn("JCEF OSR UI 组件获取失败（回退帧拷贝路径）：" + t);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("JCEF OSR browser 构建失败: " + t, t);
        }
    }

    /** OSR 自绘 UI 组件（方案 E：直显背景层；null=不可用，走帧拷贝路径）。 */
    public javax.swing.JComponent osrUiComponent() {
        return osrUiComponentField;
    }

    private volatile javax.swing.JComponent osrUiComponentField;

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            if (client != null && loadHandlerProxy != null) {
                call(client, "removeLoadHandler", new Class[]{loadHandlerCls, cefBrowserCls},
                        new Object[]{loadHandlerProxy, cefBrowser});
            }
        } catch (Throwable t) {
            MediaLog.warn("removeLoadHandler 失败（继续 dispose）：" + t);
        }
        try {
            if (browser != null) {
                call(browser, "dispose");
            }
        } catch (Throwable t) {
            MediaLog.warn("browser dispose 失败（AC-MEDIA-6 CEF 进程回收）", t);
        }
        browser = null;
        cefBrowser = null;
        client = null;
        MediaLog.info("JCEF OSR browser 已 dispose（一次一个活动 browser，§9.3）");
    }

    @Override
    public boolean isRunning() {
        return !stopped && browser != null;
    }

    @Override
    public void setWindowlessFrameRate(int fps) {
        Object cef = cefBrowser;
        if (cef == null) {
            return;
        }
        try {
            call(cef, "setWindowlessFrameRate", new Class[]{int.class}, new Object[]{fps});
        } catch (Throwable t) {
            MediaLog.warn("setWindowlessFrameRate 失败：" + t);
        }
    }

    @Override
    public void setWindowVisibility(boolean visible) {
        Object cef = cefBrowser;
        if (cef == null) {
            return;
        }
        try {
            call(cef, "setWindowVisibility", new Class[]{boolean.class}, new Object[]{visible});
        } catch (Throwable t) {
            MediaLog.warn("setWindowVisibility 失败：" + t);
        }
    }

    @Override
    public void wasResized(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        this.renderWidth = width;
        this.renderHeight = height;
        Object cef = cefBrowser;
        if (cef == null) {
            return;
        }
        try {
            call(cef, "wasResized", new Class[]{int.class, int.class},
                    new Object[]{width, height});
        } catch (Throwable t) {
            MediaLog.warn("wasResized 失败：" + t);
        }
    }

    @Override
    public void runJavaScript(String script) {
        Object created = browser;
        if (created == null || script == null) {
            return;
        }
        try {
            call(created, "runJavaScript", new Class[]{String.class}, new Object[]{script});
        } catch (Throwable t) {
            MediaLog.warn("runJavaScript 失败：" + t);
        }
    }

    @Override
    public void disableNavigation() {
        Object created = browser;
        if (created == null) {
            return;
        }
        try {
            call(created, "disableNavigation");
        } catch (Throwable t) {
            MediaLog.warn("disableNavigation 失败：" + t);
        }
    }

    @Override
    public void muteAudio() {
        runJavaScript(JcefOsrFrameSource.MUTE_MEDIA_JS);
    }

    // ---------------- OSR 工厂/渲染代理 ----------------

    /**
     * 工厂代理：createComponent 交裸面板（永不上屏，仅占位）；createCefRenderHandler 返回拦截代理；
     * createScreenBoundsProvider 委托平台默认工厂。
     */
    private Object newOsrFactoryProxy() {
        Object defaultFactory;
        try {
            defaultFactory = factoryCls.getMethod("getInstance").invoke(null); // 静态方法
        } catch (Throwable t) {
            defaultFactory = null;
        }
        final Object platformDefault = defaultFactory;
        final java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        Object renderHandlerProxy = Proxy.newProxyInstance(jcefClassLoader,
                new Class[]{renderHandlerCls}, this::onRenderHandler);
        InvocationHandler factoryHandler = (proxy, method, args) -> {
            String name = method.getName();
            if (seen.add("factory:" + name)) {
                MediaLog.info("JCEF factory 方法首见：" + name);
            }
            switch (name) {
                case "createComponent":
                    JPanel panel = new JPanel();
                    panel.setOpaque(false);
                    return panel;
                case "createCefRenderHandler":
                    return renderHandlerProxy;
                case "createScreenBoundsProvider":
                    if (platformDefault != null) {
                        try {
                            return callR(platformDefault, "createScreenBoundsProvider");
                        } catch (Throwable ignored) {
                            return null;
                        }
                    }
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "toString":
                    return "DwallpaperJBCefOSRHandlerFactory";
                default:
                    return null;
            }
        };
        return Proxy.newProxyInstance(jcefClassLoader, new Class[]{factoryCls}, factoryHandler);
    }

    /** 渲染代理：onPaint 同步拷贝进 FrameSlot；getViewRect/getScreenInfo/getDeviceScaleFactor 由本类接管。 */
    private Object onRenderHandler(Object proxy, Method method, Object[] args) {
        if (renderSeen.add("rh:" + method.getName())) {
            MediaLog.info("JCEF renderHandler 方法首见：" + method.getName());
        }
        switch (method.getName()) {
            case "onPaint": {
                // CEF UI 线程：popup 帧忽略；同步拷贝（禁止回存/长驻，design.md §9.3）
                // 注意：onPaint 的 w/h 为物理像素（= viewRect(DIP) × deviceScale），FrameSlot 图像即物理分辨率
                boolean popup = args != null && args.length > 1 && Boolean.TRUE.equals(args[1]);
                if (popup || args == null || args.length < 6 || !(args[3] instanceof ByteBuffer)) {
                    return null;
                }
                int width = ((Number) args[4]).intValue();
                int height = ((Number) args[5]).intValue();
                copyFrame((ByteBuffer) args[3], width, height);
                return null;
            }
            case "getViewRect":
                // F9：视图语义 = DIP（Swing 逻辑像素，= WallpaperEngine.pushRenderSize 传入值）；
                // CEF 按 viewRect × deviceScale 出物理缓冲（125%/150% 缩放下不再渲染逻辑分辨率）
                return viewRect(renderWidth, renderHeight);
            case "getScreenInfo":
                if (args != null && args.length > 1 && args[1] != null) {
                    try {
                        Rectangle rect = viewRect(renderWidth, renderHeight);
                        args[1].getClass()
                                .getMethod("Set", double.class, int.class, int.class,
                                        boolean.class, Rectangle.class, Rectangle.class)
                                .invoke(args[1], deviceScaleFactor, 32, 8, false, rect, rect);
                    } catch (Throwable ignored) {
                        // 屏幕信息非关键路径
                    }
                }
                return Boolean.TRUE;
            case "getScreenPoint":
                return args != null && args.length > 1 && args[1] != null ? args[1] : new java.awt.Point();
            case "getDeviceScaleFactor":
                return deviceScaleFactor; // F9：真实 device scale（不再恒 1.0）
            case "startDragging":
            case "onCursorChange":
                return Boolean.FALSE;
            case "onPopupShow":
            case "onPopupSize":
            case "updateDragCursor":
            case "addOnPaintListener":
            case "removeOnPaintListener":
            case "setOnPaintListener":
            case "OnImeCompositionRangeChanged":
            case "OnTextSelectionChanged":
            case "onAcceleratedPaint":
                return null;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == (args == null ? null : args[0]);
            case "toString":
                return "DwallpaperCefRenderHandler";
            default:
                return null;
        }
    }

    private void copyFrame(ByteBuffer buffer, int width, int height) {
        // 【帧流动遥测 2026-11-19】首帧 + 每 120 帧打点：验证 OSR 是否真的在出帧
        //（此前"browser 构建成功"级别的日志无法证明渲染发生）。
        paintCount++;
        if (paintCount == 1 || paintCount % 120 == 0) {
            MediaLog.info("JCEF onPaint 流动：frames=" + paintCount
                    + " size=" + width + "x" + height);
        }
        BufferedImage image = slot.acquire(width, height);
        if (image == null) {
            return; // 超内存上限丢帧（FrameSlot 已记一次日志，保留最后一帧）
        }
        int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        BgraFrameCopy.copyToArgb(buffer, argb, width * height);
        slot.publish(image);
    }

    private volatile long paintCount;
    private final java.util.Set<String> renderSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // ---------------- 加载处理代理 ----------------

    private Object newLoadHandlerProxy() {
        return Proxy.newProxyInstance(jcefClassLoader, new Class[]{loadHandlerCls},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onLoadEnd":
                            // 音频默认静音（任务口径）：wrapper 自带 muted，此处对 web 页面注入兜底
                            runJavaScript(JcefOsrFrameSource.MUTE_MEDIA_JS);
                            return null;
                        case "onLoadError":
                            MediaLog.warn("页面加载错误（降级提示）：code="
                                    + (args != null && args.length > 2 ? args[2] : "?")
                                    + " failedUrl=" + (args != null && args.length > 4 ? args[4] : "?"));
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == (args == null ? null : args[0]);
                        case "toString":
                            return "DwallpaperCefLoadHandler";
                        default:
                            return null;
                    }
                });
    }

    // ---------------- F9 HiDPI 缩放（t6 审查修复，纯函数供单测推演） ----------------

    /**
     * F9：推导真实设备缩放因子（不再恒 1.0）。优先级：
     * ① 显式属性 {@code dwallpaper.jcef.deviceScale}（单测推演/调试；≥1.0 才生效）；
     * ② {@code JBCefApp.getForceDeviceScaleFactor()}（&gt;1.0 视为显式 pin；≤1.0 视为未设置，避免默认值
     *    掩盖真实缩放）；
     * ③ 默认屏 {@code GraphicsConfiguration.getDefaultTransform().getScaleX()}（Windows 125%/150%
     *    缩放 → 1.25/1.5；headless/异常 → 跳过）；
     * ④ 兜底 1.0（100% 缩放，与旧行为一致）。
     * 注：JBR 下 JComponent 宽高为 Swing 逻辑像素（DIP），多显示器差异屏以默认屏为准（近似，t7 真机复核点）。
     */
    static double detectDeviceScaleFactor() {
        String override = System.getProperty(SYSPROP_DEVICE_SCALE);
        if (override != null && !override.isBlank()) {
            try {
                double v = Double.parseDouble(override.trim());
                if (v >= 1.0d) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 非法覆盖值 → 走后续推导
            }
        }
        try {
            Object v = Class.forName(CLS_APP).getMethod("getForceDeviceScaleFactor").invoke(null);
            if (v instanceof Number n && n.doubleValue() > 1.0d) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
            // 无 JCEF classpath（纯 JVM 单测）→ 走 AWT
        }
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            AffineTransform tx = gc.getDefaultTransform();
            double sx = tx.getScaleX();
            if (sx >= 1.0d) {
                return sx;
            }
        } catch (Throwable ignored) {
            // headless 或图形环境异常 → 兜底
        }
        return 1.0d;
    }

    /** F9 语义：OSR 视图矩形 = DIP（Swing 逻辑像素；CEF 按 viewRect × deviceScale 出物理缓冲）。 */
    static Rectangle viewRect(int logicalWidth, int logicalHeight) {
        return new Rectangle(0, 0, Math.max(1, logicalWidth), Math.max(1, logicalHeight));
    }

    /** F9 推演：物理缓冲像素 = round(逻辑 × deviceScale)（CEF windowless 渲染契约）。 */
    static int physicalOf(int logical, double scale) {
        return (int) Math.round(Math.max(1, logical) * scale);
    }

    // ---------------- 反射辅助 ----------------

    private static void requireMethod(Class<?> cls, String name, Class<?>... types) throws Exception {
        cls.getMethod(name, types);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method m = target.getClass().getMethod(name, types);
        m.invoke(target, args);
    }

    private static Object callR(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    /** 无参无返回值调用（disableNavigation/dispose 等零参入口；helper 归属 t5，并入 t4-unblock 等价实现）。 */
    private static void call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.invoke(target);
    }

    private static void call(Object target, String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method m = target.getClass().getMethod(name, types);
        m.invoke(target, args);
    }
}
