package com.dwallpaper.media;

import com.dwallpaper.core.Frame;
import com.dwallpaper.core.FrameRateController;
import com.dwallpaper.core.FrameSource;
import com.dwallpaper.welibrary.WeEntry;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCEF OSR 帧源（design.md §9.3，t5 实现）：对标 Wallpaper Engine 动态效果核心。
 *
 * <p>数据流（与 GIF 共用同一出口，D4）：CEF onPaint（CEF UI 线程，同步 BGRA→ARGB 拷贝）
 * → {@link FrameSlot} 单槽（有界缓冲池 + 单帧内存上限）→ {@link FrameSource#pollFrame()}
 * → t4 FrameScheduler / AdjustmentPipeline（模糊 0–60px 仅对缩放缓存帧应用、亮度 40–160%、
 * 不透明度 0–90%，口径与 t4 完全一致）→ WallpaperSurface。
 * <b>与 t4 的接口对接点即 FrameSource.pollFrame()；t4 未完成期间该契约即为接口桩（本次标注），
 * 调节管线参数由 t4 侧统一作用（本源不做 CSS filter，保证与 GIF 同管线同口径）。</b></p>
 *
 * <p>速率/暂停（任务口径与 t4 一致：24fps 上限、失焦 8fps、空闲 60s 暂停）：
 * {@link #setRate(int)} 为 t4 状态机接线点 —— fps≤0 视为暂停
 * （CefBrowser.setWindowVisibility(false) + JS pause，OSR 等价挂起）；fps&gt;0 恢复并 clamp 1..30
 * （CefBrowser.setWindowlessFrameRate 运行时限帧，262 实测存在）。t4 未接线时默认 24fps
 * （FrameScheduler.DEFAULT_FPS 口径）。</p>
 *
 * <p>video：LocalMediaServer（127.0.0.1 随机端口、仅回环、Range、零转码）为主路径，
 * 包装页 {@code <video loop muted autoplay>}（音频默认静音 = 任务口径）；
 * web：WeEntryResolver 解析入口（project.json "file" → index.html → 首个 *.html）+ disableNavigation。
 * 生命周期：静态 active 槽保证一次只保留一个活动 browser（§9.3），close() dispose browser +
 * 停服 + 回收 CEF 进程（AC-MEDIA-6）。</p>
 */
public final class JcefOsrFrameSource implements FrameSource {

    /** 运行时限帧区间（§9.3：≤30，显式压低 CEF 默认 120 回退值）。 */
    public static final int MIN_RATE = 1;
    public static final int MAX_RATE = 30;
    /** 默认渲染尺寸（DIP 逻辑像素，t4 未接线时；t4 经 setRenderSize 推 WallpaperSurface 逻辑尺寸，
     *  渲染缓冲按 F9 = 逻辑 × deviceScale 出物理像素）。 */
    public static final int DEFAULT_RENDER_WIDTH = 1920;
    public static final int DEFAULT_RENDER_HEIGHT = 1080;

    /** 全量静音脚本（onLoadEnd 注入 + wrapper 自带 muted；WebAudio 无法经元素静音为已知限制）。 */
    public static final String MUTE_MEDIA_JS =
            "(function(){var f=function(){var ms=document.querySelectorAll('audio,video');"
                    + "for(var i=0;i<ms.length;i++){ms[i].muted=true;ms[i].volume=0;}};f();"
                    + "try{new MutationObserver(f).observe(document,{subtree:true,childList:true,"
                    + "attributes:true,attributeFilter:['src','muted']});}catch(x){}})();";
    /** 暂停脚本（setRate(0)：IDLE/MANUAL 暂停口径，§7）。 */
    public static final String PAUSE_MEDIA_JS =
            "(function(){var ms=document.querySelectorAll('video,audio');"
                    + "for(var i=0;i<ms.length;i++){try{ms[i].pause();}catch(x){}}})();";
    /** 恢复脚本（仅 video；audio 恢复由页面自身逻辑决定）。 */
    public static final String RESUME_MEDIA_JS =
            "(function(){var vs=document.querySelectorAll('video');"
                    + "for(var i=0;i<vs.length;i++){try{if(vs[i].paused){var p=vs[i].play();"
                    + "if(p&&p['catch']){p['catch'](function(){});}}}catch(x){}}})();";

    /** §9.3：一次只保留一个活动 browser。 */
    private static JcefOsrFrameSource activeSource;

    private final BrowserBinding binding;
    private final FrameSlot slot;
    private final LocalMediaServer localServer;
    private volatile int rate;
    private volatile boolean paused;
    private volatile boolean closed;

    /** 测试/包内装配入口（真实构建走 {@link #create(WeEntry, int)}）。 */
    JcefOsrFrameSource(BrowserBinding binding, FrameSlot slot, LocalMediaServer localServer, int initialRate) {
        this.binding = binding;
        this.slot = slot;
        this.localServer = localServer;
        this.rate = clampRate(initialRate);
    }

    /** 工厂入口（默认 24fps = FrameRateController.DEFAULT_RUNNING_FPS 口径）；失败返回 null 并输出降级日志。 */
    public static JcefOsrFrameSource create(WeEntry entry) {
        return create(entry, FrameRateController.DEFAULT_RUNNING_FPS);
    }

    /**
     * 工厂入口：JCEF 门控 → 条目解析 → 绑定装配 → 单活动槽登记。
     * 任一步失败返回 null（调用方按「禁选 + 状态行提示」降级，回退 t4 能力）。
     */
    public static JcefOsrFrameSource create(WeEntry entry, int initialFps) {
        if (entry == null) {
            return null;
        }
        if (!checkGate()) {
            return null;
        }
        // welibrary 已解析的 mediaPath 优先（video=视频文件 / web=.html 入口，WeEntry javadoc「t5 用」），
        // 缺失或不可读时回退 WeEntryResolver（project.json "file" → index.html → 首个 *.html / mp4|webm 扫描）
        WeEntryResolver.MediaTarget target = targetFromMediaPath(entry);
        if (target == null) {
            target = WeEntryResolver.resolve(entry);
        }
        if (target == null) {
            MediaLog.warn("条目无可用媒体/入口文件（降级，条目禁选）：id=" + entry.id()
                    + " type=" + entry.type() + " folder=" + entry.folderPath());
            return null;
        }
        return assemble(target.kind(), target.path(), initialFps, "entry=" + entry.id());
    }

    /** welibrary mediaPath → 媒体目标（video=视频文件；web=.html 入口）；不可用返回 null。 */
    private static WeEntryResolver.MediaTarget targetFromMediaPath(WeEntry entry) {
        String media = entry.mediaPath();
        if (media == null || media.isBlank()) {
            return null;
        }
        Path mediaPath = Path.of(media);
        if (!Files.isRegularFile(mediaPath)) {
            return null;
        }
        String name = mediaPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String type = entry.type() == null ? "" : entry.type().trim().toLowerCase(java.util.Locale.ROOT);
        if ("video".equals(type) && (name.endsWith(".mp4") || name.endsWith(".webm"))) {
            return new WeEntryResolver.MediaTarget(WeEntryResolver.MediaTarget.Kind.VIDEO, mediaPath);
        }
        if ("web".equals(type) && (name.endsWith(".html") || name.endsWith(".htm"))) {
            return new WeEntryResolver.MediaTarget(WeEntryResolver.MediaTarget.Kind.WEB, mediaPath);
        }
        return null;
    }

    /** 路径直连工厂（registry.resolvePath 用）：单一视频文件（mp4/webm），不经条目解析。 */
    public static JcefOsrFrameSource createForMediaFile(Path mediaFile) {
        if (mediaFile == null) {
            return null;
        }
        if (!checkGate()) {
            return null;
        }
        if (!Files.isRegularFile(mediaFile)) {
            MediaLog.warn("视频文件不存在（降级，条目禁选）：" + mediaFile);
            return null;
        }
        return assemble(WeEntryResolver.MediaTarget.Kind.VIDEO, mediaFile,
                FrameRateController.DEFAULT_RUNNING_FPS, "media=" + mediaFile);
    }

    /** 路径直连工厂：网页入口（html），不经条目解析。 */
    public static JcefOsrFrameSource createForWebFile(Path entryFile) {
        if (entryFile == null) {
            return null;
        }
        if (!checkGate()) {
            return null;
        }
        if (!Files.isRegularFile(entryFile)) {
            MediaLog.warn("网页入口不存在（降级，条目禁选）：" + entryFile);
            return null;
        }
        return assemble(WeEntryResolver.MediaTarget.Kind.WEB, entryFile,
                FrameRateController.DEFAULT_RUNNING_FPS, "web=" + entryFile);
    }

    /** 门控失败 = 明确降级日志 + 每会话一次 UI 提示（任务口径第 1 条）。 */
    private static boolean checkGate() {
        if (JcefGate.isJcefUsable()) {
            return true;
        }
        MediaLog.warn("JCEF 门控失败（" + JcefGate.unavailableReason() + "）：" + JcefGate.JCEF_UNAVAILABLE_HINT);
        JcefGate.showDegradedNotificationOnce();
        return false;
    }

    /** 共同装配：服务器/页面 URL → OSR 绑定 → 单活动槽登记（§9.3 一次一个活动 browser）。 */
    private static JcefOsrFrameSource assemble(WeEntryResolver.MediaTarget.Kind kind, Path path,
                                               int initialFps, String origin) {
        boolean forWeb = kind == WeEntryResolver.MediaTarget.Kind.WEB;
        LocalMediaServer server = null;
        CefBrowserBinding binding = null;
        try {
            String pageUrl;
            if (forWeb) {
                pageUrl = path.toUri().toString();
            } else {
                server = new LocalMediaServer(path.toFile());
                server.start();
                pageUrl = server.wrapperUrl(); // t5 决策：http 回环包装页（README「t5 实现决策」）
            }
            FrameSlot slot = new FrameSlot();
            binding = new CefBrowserBinding(slot, forWeb, Math.min(initialFps, MAX_RATE),
                    renderWidth(), renderHeight());
            binding.start(pageUrl);
            JcefOsrFrameSource source = new JcefOsrFrameSource(binding, slot, server, initialFps);
            synchronized (JcefOsrFrameSource.class) {
                JcefOsrFrameSource previous = activeSource;
                activeSource = source;
                if (previous != null && previous != source) {
                    MediaLog.info("切换壁纸：关闭上一个 JCEF browser（§9.3 一次一个活动 browser）");
                    previous.closeInternal();
                }
            }
            MediaLog.info("JCEF OSR 帧源启动：" + origin + " kind=" + kind + " page=" + pageUrl
                    + " rate=" + source.rate + "fps");
            return source;
        } catch (Throwable t) {
            MediaLog.warn("JCEF OSR 帧源启动失败（降级，回退 t4 能力）：" + origin, t);
            if (server != null) {
                try {
                    server.close();
                } catch (Throwable ignored) {
                    // 清理失败不掩盖主异常
                }
            }
            if (binding != null) {
                try {
                    binding.stop();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            return null;
        }
    }

    @Override
    public Frame pollFrame() {
        if (closed || paused) {
            return null; // 【零渲染修复】挂起时不向管线供帧（CEF 层保持活动，视频已 JS pause）
        }
        return slot.pollFrame();
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    /** OSR 自绘 UI 组件（【方案 E】直显背景层；null=组件不可用走帧拷贝回退）。 */
    public javax.swing.JComponent osrUiComponent() {
        return binding.osrUiComponent();
    }

    /** 在本页执行 JS（诊断/兜底用；失败静默）。 */
    public void runScript(String js) {
        try {
            binding.runJavaScript(js);
        } catch (Throwable ignored) {
            // 诊断脚本失败不影响播放
        }
    }

    /**
     * 【权威取帧 2026-11-19】直接读出 JCEF OSR 渲染处理器里的最近一帧。
     *
     * <p>依据（对 idea-2026.2.3 的 jcef 模块 javap 实证）：
     * {@code JBCefBrowser$MyPanel}（JPanel）持有 {@code myUiComp}，真正的渲染组件是
     * {@code JBCefOsrComponent}（JPanel），它持有 {@code myRenderHandler}
     * （{@code JBCefOsrHandler implements CefRenderHandler}），后者持有
     * {@code protected volatile JBHiDPIScaledImage myImage} ——
     * {@code com.intellij.util.JBHiDPIScaledImage extends java.awt.image.BufferedImage}。
     * {@code JBCefOsrComponent.paintComponent} 只是把该图像画出来。</p>
     *
     * <p>为什么不能靠 {@code component.paint(g)} 取帧（实测结论）：离屏组件未
     * {@code addNotify}、未布局时，内部子组件尺寸为 0×0，画出来 100% 透明
     * （日志实证 {@code OSR 帧为空白 opaque=0.00%}）。直接读 myImage 才是权威帧。</p>
     *
     * @return 最近帧；null = 反射路径不可用或尚未出帧（调用方回退 paint 捕获）
     */
    public java.awt.image.BufferedImage grabFrame() {
        try {
            javax.swing.JComponent ui = binding.osrUiComponent();
            if (ui == null) {
                return null;
            }
            Object osrComponent = findOsrComponent(ui);
            if (osrComponent == null) {
                if (!diagNoComponent) {
                    diagNoComponent = true;
                    MediaLog.warn("未找到 OSR 组件（回退组件自绘）：界面树=" + describeTree(ui, 0, new StringBuilder()));
                }
                return null;
            }
            Object handler = readField(osrComponent, "myRenderHandler");
            if (handler == null) {
                if (!diagNoHandler) {
                    diagNoHandler = true;
                    MediaLog.warn("OSR 组件无 myRenderHandler 字段：" + osrComponent.getClass().getName());
                }
                return null;
            }
            // ① 原生 OSR（JBCefNativeOsrHandler）：帧在共享内存 myCurrentFrame 里
            java.awt.image.BufferedImage shared = frameFromSharedMemory(handler);
            if (shared != null) {
                strategy("shared-memory(" + handler.getClass().getSimpleName() + ")");
                return shared;
            }
            // ② 交给 JCEF 自己把当前帧画出来（JBCefOsrHandler.paint(Graphics2D)）
            java.awt.image.BufferedImage painted = frameFromHandlerPaint(handler);
            if (painted != null) {
                strategy("handler-paint(" + handler.getClass().getSimpleName() + ")");
                return painted;
            }
            // ③ 软件 OSR：直接取 myImage
            Object image = readField(handler, "myImage");
            if (image instanceof java.awt.image.BufferedImage) {
                strategy("myImage");
                return (java.awt.image.BufferedImage) image;
            }
            if (!diagNoImage) {
                diagNoImage = true;
                MediaLog.info("OSR 处理器尚未产出帧（myImage=" + image + "，handler="
                        + handler.getClass().getName() + "）");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String currentStrategy = "?";

    /** 取帧策略一次性日志（下一步定位用）。 */
    private void strategy(String name) {
        if (!name.equals(currentStrategy)) {
            currentStrategy = name;
            MediaLog.info("OSR 取帧策略：" + name);
        }
    }

    /**
     * 原生 OSR 帧：{@code JBCefNativeOsrHandler.myCurrentFrame}
     * （{@code com.jetbrains.cef.SharedMemory$WithRaster}）→ {@code getWidth/getHeight} +
     * {@code wrapRaster()} 得到 BGRA 字节缓冲，拷进 ARGB 图像。
     */
    private static java.awt.image.BufferedImage frameFromSharedMemory(Object handler) {
        try {
            Object frame = readField(handler, "myCurrentFrame");
            if (frame == null) {
                return null;
            }
            Object wv = invokeNoArg(frame, "getWidth");
            Object hv = invokeNoArg(frame, "getHeight");
            if (!(wv instanceof Number) || !(hv instanceof Number)) {
                return null;
            }
            int w = ((Number) wv).intValue();
            int h = ((Number) hv).intValue();
            if (w <= 0 || h <= 0 || (long) w * h > 8192L * 8192L) {
                return null;
            }
            Object bufObj = invokeNoArg(frame, "wrapRaster");
            if (!(bufObj instanceof java.nio.ByteBuffer)) {
                return null;
            }
            java.nio.ByteBuffer buffer = (java.nio.ByteBuffer) bufObj;
            if (buffer.capacity() < w * h * 4) {
                return null;
            }
            buffer.rewind();
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] argb = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            BgraFrameCopy.copyToArgb(buffer, argb, w * h);
            return img;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 让 JCEF 的渲染处理器把自己的当前帧画进我们的图像（最贴近组件实际显示结果）。 */
    private static java.awt.image.BufferedImage frameFromHandlerPaint(Object handler) {
        try {
            int w = 1920;
            int h = 1080;
            java.lang.reflect.Method sizeM = declaredMethod(handler.getClass(), "getCurrentFrameSize");
            if (sizeM != null) {
                sizeM.setAccessible(true);
                Object v = sizeM.invoke(handler);
                if (v instanceof java.awt.Dimension) {
                    java.awt.Dimension d = (java.awt.Dimension) v;
                    if (d.width > 0 && d.height > 0) {
                        w = d.width;
                        h = d.height;
                    }
                }
            }
            java.lang.reflect.Method paintM = handler.getClass().getMethod("paint", java.awt.Graphics2D.class);
            paintM.setAccessible(true);
            java.awt.image.BufferedImage out =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            try {
                paintM.invoke(handler, g);
            } finally {
                g.dispose();
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Method declaredMethod(Class<?> c, String name, Class<?>... types) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private boolean diagNoComponent;
    private boolean diagNoHandler;
    private boolean diagNoImage;

    /** 有界的界面树指纹（只记录类名，用于下次定位 OSR 组件挂在哪一层）。 */
    private static String describeTree(java.awt.Component c, int depth, StringBuilder sb) {
        if (depth > 4 || sb.length() > 400) {
            return sb.toString();
        }
        sb.append(depth == 0 ? "" : "\n").append("  ".repeat(depth)).append(c.getClass().getName());
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                describeTree(child, depth + 1, sb);
            }
        }
        return sb.toString();
    }

    /** 优先读 MyPanel.myUiComp；否则在组件树中找类名以 JBCefOsrComponent 结尾的组件。 */
    private static Object findOsrComponent(javax.swing.JComponent root) {
        Object direct = readField(root, "myUiComp");
        if (direct != null && isOsrComponent(direct)) {
            return direct;
        }
        return searchTree(root, 0);
    }

    private static Object searchTree(java.awt.Component c, int depth) {
        if (depth > 8) {
            return null;
        }
        if (isOsrComponent(c)) {
            return c;
        }
        if (c instanceof java.awt.Container) {
            java.awt.Container container = (java.awt.Container) c;
            for (java.awt.Component child : container.getComponents()) {
                Object hit = searchTree(child, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static boolean isOsrComponent(Object o) {
        String n = o.getClass().getName();
        return n.endsWith("JBCefOsrComponent") || n.contains("OsrComponent");
    }

    /** 反射读字段（沿类层级；失败返回 null，不抛异常）。 */
    private static Object readField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * t4 状态机接线点：RUNNING=fps 上限（默认 24）、DEGRADED=8、IDLE/MANUAL 暂停=0。
     * fps≤0 → 挂起（setWindowVisibility(false) + JS pause）；fps&gt;0 → 恢复 + clamp 1..30。
     * （t4 契约：core.FrameSource.setRate default 方法由引擎状态变化处下发。）
     */
    @Override
    public void setRate(int fps) {
        if (closed) {
            return;
        }
        if (fps <= 0) {
            paused = true;
            // 【零渲染修复 2026-11-19】不再 setWindowVisibility(false)：CEF windowless
            // 模式下隐藏窗口后渲染管线停摆且未必随 true 恢复（真实日志实证恢复全程
            // 零 onPaint）。挂起=JS pause 媒体 + 本源停止向管线供帧（paused 由 pollFrame
            // 尊重），CEF 合成层保持活动，恢复无需唤醒渲染。
            try {
                binding.runJavaScript(PAUSE_MEDIA_JS);
            } catch (Throwable t) {
                MediaLog.warn("暂停脚本注入失败：" + t);
            }
            MediaLog.info("JCEF 帧源暂停（t4 状态机：IDLE_PAUSED/MANUAL_PAUSED；保留渲染层）");
        } else {
            int clamped = clampRate(fps);
            paused = false;
            rate = clamped;
            try {
                binding.setWindowlessFrameRate(clamped);
            } catch (Throwable t) {
                MediaLog.warn("帧率更新失败：" + t);
            }
            try {
                binding.runJavaScript(RESUME_MEDIA_JS);
            } catch (Throwable t) {
                MediaLog.warn("恢复脚本注入失败：" + t);
            }
            MediaLog.info("JCEF 帧率更新：" + clamped + "fps（24 上限口径，失焦 8fps 由 t4 状态机下发）");
        }
    }

    /** t4 接线点（F9 语义）：推送 WallpaperSurface 视图尺寸（Swing 逻辑像素/DIP，=
     *  WallpaperEngine.pushRenderSize 的 surface.getWidth()/getHeight() 现值）；binding 按真实
     *  device scale 上报缩放，CEF 渲染缓冲 = 逻辑 × scale = 物理像素（125%/150% 不再发糊）。
     *  resize 防抖由 t4 负责（t4 契约：core.FrameSource.setRenderSize default 方法）。 */
    @Override
    public void setRenderSize(int width, int height) {
        if (closed) {
            return;
        }
        try {
            binding.wasResized(width, height);
        } catch (Throwable t) {
            MediaLog.warn("渲染尺寸推送失败：" + t);
        }
    }

    public int getRate() {
        return rate;
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public void close() {
        synchronized (JcefOsrFrameSource.class) {
            if (activeSource == this) {
                activeSource = null;
            }
        }
        closeInternal();
    }

    private void closeInternal() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            binding.stop();
        } catch (Throwable t) {
            MediaLog.warn("browser stop 失败（AC-MEDIA-6）", t);
        }
        if (localServer != null) {
            try {
                localServer.close();
            } catch (Throwable t) {
                MediaLog.warn("LocalMediaServer 关闭失败", t);
            }
        }
        slot.close();
        MediaLog.info("JCEF OSR 帧源关闭（browser dispose + 服停 + 槽清空，AC-MEDIA-6）");
    }

    private static int clampRate(int fps) {
        return Math.max(MIN_RATE, Math.min(MAX_RATE, fps));
    }

    private static int renderWidth() {
        return Math.max(64, systemInt("dwallpaper.jcef.render.width", DEFAULT_RENDER_WIDTH));
    }

    private static int renderHeight() {
        return Math.max(64, systemInt("dwallpaper.jcef.render.height", DEFAULT_RENDER_HEIGHT));
    }

    private static int systemInt(String key, int fallback) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
