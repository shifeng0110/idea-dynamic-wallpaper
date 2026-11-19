package com.dwallpaper.core;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 绘制管线编排（design.md §4/§11）：挂载链探测（S1→S2→S3）→ 帧源 → 调度器 → 雾镜挂载 → 面板绑定。
 * 核心只编排 core 内对象；fog/媒体装配由 settings 的 WallpaperController 注入
 * （fogInstaller/fogUninstaller 钩子，保持 fog → core 单向依赖，§2.3）。
 * 启动时若无 IDE 窗口（bootstrap 早于工程打开）→ 2s 重试至多 30 次（§2.4「延迟到有 IDE 窗口后挂载」）。
 * 【F18】按窗实例化 surface（ctx.surface() 工厂 + register/unregister 回调），帧/参数/渲染尺寸对全部
 * 已挂 surface 扇出。 【F13】源切换后首个 PreparedFrame 自动刷新雾镜静态快照（挂 pipeline 监听）。
 * 【F6】disable 挂起源（setRate(0)）；doMount 先 start()；暂停态 fps 保持 0 不被 clamp 到 1。
 * 【F8】应用级 Disposer 注册 IDE 退出释放链（泵/源/雾镜/管线/VRAM/S3 属性清理）。
 */
public final class WallpaperEngine implements WallpaperPaint {

    private static final Logger LOG = Logger.getInstance(WallpaperEngine.class);

    private final AdjustmentPipeline pipeline = new AdjustmentPipeline();
    private final FrameScheduler scheduler = new FrameScheduler(pipeline);
    private final FrameRateController rate = new FrameRateController();
    private final RootPaneMountStrategy s1 = new RootPaneMountStrategy();

    private final EditorTransparentStrategy s2 = new EditorTransparentStrategy();
    private final StaticBackgroundFallbackStrategy s3 = new StaticBackgroundFallbackStrategy();

    {
        // 【2026-11-19 定稿】不再给编辑器链注入 WallpaperBorder：替换平台 border 会让
        // 平台自身的 `(SpecificBorder) getBorder()` 抛 ClassCastException 并中断
        // paint 链（实证）。壁纸统一由 opaque 的 Surface 绘制。
    }

    /** 已挂载 surface 扇出列表（F18：每窗一个；EDT 修改）。 */
    private final List<WallpaperSurface> surfaces = new ArrayList<>();
    private final Set<Window> mountedWindows = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;
    private volatile boolean disposed;
    private volatile String mountLevel = "OFF";
    private volatile String lastFallbackReason = "";
    private volatile FrameSource source;
    private volatile Runnable fogInstaller = () -> {
    };
    private volatile Runnable fogUninstaller = () -> {
    };
    private volatile Runnable snapshotRefreshHook = () -> {
    };
    private volatile BufferedImage staticSnapshot; // 雾镜静态快照（源切换后首帧自动刷新，§6.4/F13）
    private volatile boolean snapshotPending;      // F13：切源后待首帧刷新
    private Timer mountRetryTimer;
    private Timer s2RetryTimer; // 【S2 延迟重试 2026-11-19】项目打开后补装编辑器透明化
    private int mountAttempts;
    private final Debouncer resizeDebouncer = new Debouncer(150, this::onSurfaceResized);

    public WallpaperEngine() {
        // 【方案 E】S1 挂载成功后的 OSR 直显组件补挂（启动恢复竞态：setFrameSource 早于 mount）
        s1.setOsrRemountHook(() -> mountOsrOverlay(source));
        pipeline.start();
        pipeline.setListener(pf -> SwingUtilities.invokeLater(() -> {
            for (WallpaperSurface s : surfaces) {
                s.setPrepared(pf.image(), pf.width(), pf.height());
            }
            IdeBackgroundBridge.onNewFrame(++backgroundSeq);
            repaintNativeBackground();            if (mountLevel.startsWith("S3")) {
                s3.showFrame(pf.image());
            }
            if (snapshotPending) { // F13：切源后首个 prepared 帧 → 刷新雾镜静态快照
                snapshotPending = false;
                refreshStaticSnapshot();
                try {
                    snapshotRefreshHook.run(); // controller 注入：fog 缓存失效+重绘
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: snapshot hook failed", t);
                }
            }
        }));
        rate.setFocusedOnIde(this::isFocusOnIde);
        // A1 接线：状态机变化 → 调度泵 + 媒体源 setRate（暂停→0、失焦→8、恢复→上限）
        rate.setFpsListener(fps -> {
            scheduler.setFps(fps);
            restartDisplayClock(fps);
            FrameSource s = source;
            if (s != null) {
                try {
                    s.setRate(fps); // core 经 FrameSource 契约下发，不 import media（§2.3）
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: source.setRate failed", t);
                }
            }
        });
        // F8：应用级退出释放链（IDE 退出时回收泵/源/雾镜/管线/VRAM/S3 属性）
        try {
            Disposer.register(ApplicationManager.getApplication(), this::disposeAll);
        } catch (Throwable t) {
            LOG.warn("dwallpaper: app-level Disposer registration failed", t);
        }
    }

    /** 主 surface（优先已在窗口中显示且有真实尺寸的那个；无则返回惰性占位，供面板/自测引用）。 */
    public WallpaperSurface surface() {
        WallpaperSurface s = pickDisplaySurface();
        if (s != null) {
            return s;
        }
        if (lazySurface == null) {
            lazySurface = createSurface();
        }
        return lazySurface;
    }

    /** 未挂载期的占位 surface（不登记进 surfaces，避免污染显示目标选择）。 */
    private WallpaperSurface lazySurface;

    /**
     * 选真正的显示目标：① 正在显示且有尺寸 → ② 有尺寸 → ③ 最新登记的。
     * 【2026-11-19】实证：旧实现取 surfaces.get(0)，可能拿到早期惰性创建、永远 0×0 的
     * 占位实例，导致 OSR 组件挂到零尺寸画布上（日志 surface=0x0 showing=false），
     * CEF 的 viewRect 随之为 0 而完全不出画。
     */
    private WallpaperSurface pickDisplaySurface() {
        List<WallpaperSurface> snap = surfaceSnapshot();
        for (WallpaperSurface s : snap) {
            if (s.isShowing() && s.getWidth() > 0 && s.getHeight() > 0) {
                return s;
            }
        }
        for (WallpaperSurface s : snap) {
            if (s.getWidth() > 0 && s.getHeight() > 0) {
                return s;
            }
        }
        return snap.isEmpty() ? null : snap.get(snap.size() - 1);
    }

    private List<WallpaperSurface> surfaceSnapshot() {
        synchronized (surfaces) {
            return new ArrayList<>(surfaces);
        }
    }

    private WallpaperSurface createSurface() {
        WallpaperSurface s = new WallpaperSurface();
        s.setOpacityPercent(currentOpacity.get());
        s.setFrameListener(() -> { // 【可读性架构】帧变更 → 雾化缓存失效 + Border 面板扇出重绘
            synchronized (frostedCache) {
                frostedCache.clear();
            }
            repaintBorderPanels();
        });
        s.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                resizeDebouncer.ping();
            }
        });
        return s;
    }

    public AdjustmentPipeline pipeline() {
        return pipeline;
    }

    public FrameRateController rateController() {
        return rate;
    }

    public void setFogInstaller(Runnable r) {
        this.fogInstaller = r != null ? r : () -> {
        };
    }

    public void setFogUninstaller(Runnable r) {
        this.fogUninstaller = r != null ? r : () -> {
        };
    }

    /** F13 配套：静态快照刷新后回调（controller 注入 fog 缓存失效+重绘）。 */
    public void setSnapshotRefreshHook(Runnable r) {
        this.snapshotRefreshHook = r != null ? r : () -> {
        };
    }

    /** 启用：挂载链探测 → 调度器/看门狗 → 雾镜挂载（钩子）。全部步骤 EDT。 */
    public void enable() {
        if (enabled || disposed) {
            return;
        }
        enabled = true;
        runOnEdt(this::doMount);
    }

    /** 停用：严格逆序卸载并还原所有被修改状态（design.md §11）；F6①：活动源同步挂起。 */
    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        runOnEdt(this::doUnmount);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 壁纸帧源切换：关闭旧源，启动新源（一次只保留一个活动源，§9.3 生命周期纪律同理）。
     * F6③：fps 用状态机当前值原样下发（暂停态保持 0，不 clamp 到 1，暂停不被静默打破）。
     */
    public void setFrameSource(@Nullable FrameSource fs) {
        FrameSource old = source;
        source = fs;
        // 【直通模式】ffmpeg 源已在 C 侧完成缩放+亮度 → 管线直通，避免重复全帧处理
        boolean ffmpegSource = fs instanceof com.dwallpaper.media.FfmpegFrameSource;
        pipeline.setPassThrough(ffmpegSource);
        if (ffmpegSource) {
            ((com.dwallpaper.media.FfmpegFrameSource) fs).setBrightnessPercent(pipelineBrightness.get());
        }
        if (old != null) {
            try {
                old.close();
            } catch (Throwable t) {
                LOG.warn("dwallpaper: old frame source close issue", t);
            }
        }
        snapshotPending = fs != null; // F13：新源首帧自动刷新静态快照
        if (fs != null && enabled) {
            try {
                fs.start(); // A3 安全网：幂等；GIF/序列帧不 start 不解码
            } catch (Throwable t) {
                LOG.warn("dwallpaper: source.start failed", t);
            }
            int fps = rate.currentFps(); // F6③：0 保持 0
            scheduler.start(fs);
            scheduler.setFps(fps);
            try {
                fs.setRate(fps); // 暂停态切源：新源同步挂起
            } catch (Throwable t) {
                LOG.warn("dwallpaper: source.setRate failed", t);
            }
            pushRenderSize();
            mountOsrOverlay(fs); // 【方案 E】OSR 自绘组件直显背景层（视频/网页）
            LOG.info("dwallpaper: frame source -> " + fs.getClass().getSimpleName() + " fps=" + fps);
        } else if (fs == null) {
            scheduler.stop();
            unmountOsrOverlay(); // 【方案 E】清源时同步移除直显组件
        }
    }

    // ---------- 【方案 E】OSR 组件直显背景层（2026-11-19） ----------

    private final Set<Window> osrOverlayWindows = new HashSet<>();
    private javax.swing.JComponent osrOverlayComponent;
    /** 【方案 E'：组件自绘取帧 2026-11-19】EDT 取帧泵（OSR 组件画面 → pipeline）。 */
    private javax.swing.Timer osrFramePump;
    private BufferedImage osrFrameBuffer;
    private long osrFrameSeq;
    /** 是否已切到「直接读 OSR 渲染缓冲」取帧（一次性日志用）。 */
    private boolean usedOsrBuffer;

    /**
     * 【方案 E'：组件自绘取帧 2026-11-19】视频/网页壁纸的取帧方式改为：在 EDT 定时
     * 调用 OSR 组件的 {@code paint(Graphics)} 把其当前画面绘制进共享 BufferedImage，
     * 再经 pipeline.submit 走既有调节/扇出链路（Surface + 玻璃面板 Border + 雾镜全部
     * 复用）。替代旧"直显背景层"方案——500+ 玻璃面板的 scrim 底色层层叠加会把背景
     * 层直显组件完全遮蔽（遥测实证 Border 全部 scrim 模式，视频不可见）。
     * 不再挂载组件到 layeredPane。
     */
    private void mountOsrOverlay(FrameSource fs) {
        runOnEdt(() -> {
            try {
                if (!(fs instanceof com.dwallpaper.media.JcefOsrFrameSource jfs)) {
                    return;
                }
                javax.swing.JComponent comp = jfs.osrUiComponent();
                if (comp == null) {
                    LOG.info("dwallpaper: OSR UI 组件不可用，视频/网页无法显示");
                    return;
                }
                // 【直显优先 2026-11-19 定稿】JCEF 只在渲染组件真正挂进窗口且可见时
                // 才产出画面：不挂载时，三条取帧路径（共享内存 myCurrentFrame /
                // handler.paint / myImage）全部为空（idea.log 实证 opaque=0.00%）。
                // 因此第一步永远是「挂成 Surface 子组件直显」——这是 JCEF 支持的标准
                // 用法（IDEA 内置浏览器就这么工作）。
                osrOverlayComponent = comp;
                attachOsrDirectDisplay(jfs);
                // 第二步：挂载后再启泵取帧喂管线（模糊/亮度用），空帧不喂管线。
                startOsrFramePump(jfs);
            } catch (Throwable t) {
                LOG.warn("dwallpaper: OSR mount issue", t);
            }
        });
    }

    /** 取帧泵：仅在组件已挂载后启动；只把真实画面喂给管线（空帧丢弃）。 */
    private void startOsrFramePump(com.dwallpaper.media.JcefOsrFrameSource jfs) {
        if (osrFramePump != null) {
            osrFramePump.stop();
            osrFramePump = null;
        }
        osrFrameBuffer = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        osrFrameSeq = 0;
        // 取帧只为管线（模糊/亮度），与直显无关，用较低帧率换取 CPU。
        int fps = 12;
        osrFramePump = new javax.swing.Timer(1000 / fps, e -> {
            try {
                BufferedImage buf = osrFrameBuffer;
                if (buf == null) {
                    return;
                }
                // 【视图尺寸跟随 2026-11-19】CEF 的 getViewRect 取组件尺寸；尺寸为 0
                // 时完全不渲染。Surface 尺寸同步后必须把新尺寸推给 CEF 并同步组件 bounds。
                syncOsrViewSize(jfs);
                BufferedImage frame = jfs.grabFrame();
                osrFrameSeq++;
                if (frame == null) {
                    return;
                }
                Graphics2D g2 = buf.createGraphics();
                try {
                    g2.setComposite(AlphaComposite.Src);
                    g2.setColor(new java.awt.Color(0, 0, 0, 0));
                    g2.fillRect(0, 0, buf.getWidth(), buf.getHeight());
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(frame, 0, 0, buf.getWidth(), buf.getHeight(), null);
                } finally {
                    g2.dispose();
                }
                if (osrFrameSeq == 1 || osrFrameSeq % 12 == 0) {
                    logFrameStats(buf, osrFrameSeq);
                }
                if (hasRealContent(buf)) {
                    pipeline.submit(new Frame(buf, osrFrameSeq, System.currentTimeMillis(),
                            buf.getWidth(), buf.getHeight()));
                }
            } catch (Throwable ignored) {
                // 取帧失败静默（下一 tick 重试）
            }
        });
        osrFramePump.setRepeats(true);
        osrFramePump.start();
        LOG.info("dwallpaper: OSR 取帧泵启动（" + fps + "fps，组件="
                + (osrOverlayComponent == null ? "?" : osrOverlayComponent.getClass().getSimpleName()) + "）");
        // 【CEF 光栅自检 2026-11-19】帧为纯黑（luma=0）但 alpha=100%：需要区分
        // 「CEF 光栅/合成不工作」与「只是视频没画进光栅」。挂载后依次把页面底色刷成
        // 纯红/纯绿/纯蓝各 3 秒，用帧采样的 luma 判别：
        //   红≈76、绿≈150、蓝≈28 → 光栅正常（问题在视频合成）；
        //   始终 0 → CEF 光栅没有产出内容。
        injectRasterSelfTest(jfs);
    }

    private void injectRasterSelfTest(com.dwallpaper.media.JcefOsrFrameSource jfs) {
        // 【诊断探针已关闭 2026-11-19】彩条/状态色探针已完成使命（曾把页面染成蓝/红/绿），
        // 会污染真实壁纸画面，默认不再注入；需要时 -Ddwallpaper.selftest.probe=true 打开。
        if (!Boolean.parseBoolean(System.getProperty("dwallpaper.selftest.probe", "false"))) {
            return;
        }
        int[][] steps = {{0, 0xFF0000}, {3000, 0x00FF00}, {6000, 0x0000FF}, {9000, -1}};
        for (int[] step : steps) {
            javax.swing.Timer t = new javax.swing.Timer(step[0], e -> {
                try {
                    if (step[1] == -1) {
                        jfs.runScript("(function(){document.body.style.background='#000000';"
                                + "var v=document.getElementById('dw-video');if(v){v.style.opacity='1';"
                                + "try{v.play();}catch(x){}}})();");
                        LOG.info("dwallpaper: 光栅自检结束（恢复视频显示）");
                        return;
                    }
                    String hex = String.format("#%06x", step[1]);
                    jfs.runScript("(function(){document.body.style.background='" + hex + "';"
                            + "document.documentElement.style.background='" + hex + "';"
                            + "var v=document.getElementById('dw-video');if(v){v.style.opacity='0';}"
                            + "})();");
                    LOG.info("dwallpaper: 光栅自检 -> " + hex);
                } catch (Throwable ignored) {
                    // 自检失败不影响主流程
                }
            });
            t.setRepeats(false);
            t.start();
        }
    }

    /**
     * 【视频状态探针 2026-11-19】视频壁纸帧为纯黑（luma=0），需要区分：
     * ① 视频没在播（autoplay 被拦 / 未加载 / 解码失败）；② 视频在播但没进光栅
     * （硬件解码不进软件合成）。做法：按 video 真实状态给 body 上色，再用帧 luma 反推
     * （白色≈255 = 在播；品红≈105 = 未播）。
     */
    private void injectVideoStateProbe(com.dwallpaper.media.JcefOsrFrameSource jfs) {
        // 用页面底色当"状态通道"，帧采样的 luma 直接映射到视频状态：
        //   white 255 = 正在播     magenta 105 = 有数据但暂停
        //   cyan  178 = readyState2  green 150 = readyState1
        //   yellow 226 = readyState0（无元数据/取不到字节）  red 76 = 解码/加载错误
        //   blue   28 = 页面里没有 video 元素
        String js = "(function(){var v=document.getElementById('dw-video');var c;"
                + "if(!v){c='#0000ff';}else{try{v.muted=true;v.volume=0;var p=v.play();"
                + "if(p&&p['catch']){p['catch'](function(){});}}catch(x){}"
                + "if(v.error){c='#ff0000';}else if(v.readyState===0){c='#ffff00';}"
                + "else if(v.readyState===1){c='#00ff00';}else if(v.readyState===2){c='#00ffff';}"
                + "else{c=(v.paused?'#ff00ff':'#ffffff');}}"
                + "document.body.style.background=c;"
                + "document.documentElement.style.background=c;})();";
        for (int delay : new int[]{5000, 10000, 16000}) {
            javax.swing.Timer t = new javax.swing.Timer(delay, e -> {
                jfs.runScript(js);
                LOG.info("dwallpaper: 视频状态探针注入（" + delay + "ms）");
            });
            t.setRepeats(false);
            t.start();
        }
    }

    /** 把 Surface 的真实尺寸同步给直显组件与 CEF（viewRect 变化才会重新渲染）。 */
    private void syncOsrViewSize(com.dwallpaper.media.JcefOsrFrameSource jfs) {
        try {
            WallpaperSurface sup = pickDisplaySurface();
            if (sup == null) {
                return;
            }
            int w = sup.getWidth();
            int h = sup.getHeight();
            if (w <= 0 || h <= 0 || (w == lastRenderW && h == lastRenderH)) {
                return;
            }
            lastRenderW = w;
            lastRenderH = h;
            javax.swing.JComponent comp = osrOverlayComponent;
            if (comp != null && (comp.getWidth() != w || comp.getHeight() != h)) {
                comp.setBounds(0, 0, w, h);
                comp.validate();
            }
            DimOverlay d = dimOverlay;
            if (d != null) {
                d.setBounds(0, 0, w, h);
            }
            jfs.setRenderSize(w, h);
            LOG.info("dwallpaper: OSR 视图尺寸推送 " + w + "x" + h);
        } catch (Throwable ignored) {
            // 尺寸推送失败下一 tick 重试
        }
    }

    private int lastRenderW = -1;
    private int lastRenderH = -1;

    /** 帧是否有真实内容（不透明像素占比 ≥1%）。 */
    private static boolean hasRealContent(BufferedImage buf) {
        try {
            int w = buf.getWidth();
            int h = buf.getHeight();
            int stride = 24;
            long opaque = 0;
            long total = 0;
            for (int y = 0; y < h; y += stride) {
                for (int x = 0; x < w; x += stride) {
                    total++;
                    if (((buf.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                        opaque++;
                    }
                }
            }
            return total > 0 && opaque * 100.0 / total >= 1.0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 【空白帧根因修复 2026-11-19】离屏取其画面之前，必须有真实尺寸并完成布局。 */
    private void ensureOsrComponentSized(javax.swing.JComponent comp) {
        try {
            int w = primaryWidth();
            int h = primaryHeight();
            if (w <= 0 || h <= 0) {
                w = 1920;
                h = 1080;
            }
            if (comp.getWidth() != w || comp.getHeight() != h) {
                comp.setSize(w, h);
                if (comp instanceof java.awt.Container) {
                    ((java.awt.Container) comp).doLayout();
                }
                comp.validate();
            }
        } catch (Throwable ignored) {
            // 尺寸同步失败不影响取帧
        }
    }

    /** 【帧内容校验 2026-11-19】采样统计 OSR 帧：不透明像素占比 + 亮度范围。 */
    private void logFrameStats(BufferedImage buf, long seq) {
        try {
            int w = buf.getWidth();
            int h = buf.getHeight();
            int stride = 16;
            long total = 0;
            long opaque = 0;
            long lumaSum = 0;
            int lumaMin = 255;
            int lumaMax = 0;
            for (int y = 0; y < h; y += stride) {
                for (int x = 0; x < w; x += stride) {
                    int argb = buf.getRGB(x, y);
                    total++;
                    int a = (argb >>> 24) & 0xFF;
                    if (a > 8) {
                        opaque++;
                        int r = (argb >> 16) & 0xFF;
                        int gg = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        int luma = (r * 30 + gg * 59 + b * 11) / 100;
                        lumaSum += luma;
                        if (luma < lumaMin) lumaMin = luma;
                        if (luma > lumaMax) lumaMax = luma;
                    }
                }
            }
            double opaquePct = total == 0 ? 0 : opaque * 100.0 / total;
            int lumaAvg = opaque == 0 ? -1 : (int) (lumaSum / opaque);
            if (opaquePct < 1.0) {
                blankFrameCount++;
                if (blankFrameCount <= 3 || blankFrameCount % 20 == 0) {
                    LOG.warn("dwallpaper: OSR 帧为空白 seq=" + seq + " opaque="
                            + String.format(java.util.Locale.ROOT, "%.2f", opaquePct)
                            + "%（连续空白 " + blankFrameCount + "）——自绘取帧未取到画面");
                }
                // 空帧不触发任何挂载（直显已是主路径）；只记录诊断。
            } else if (seq <= 120 || seq % 600 == 0) {
                blankFrameCount = 0; // 取到真实画面：重置连续空白计数
                LOG.info("dwallpaper: OSR 帧采样 seq=" + seq + " opaque="
                        + String.format(java.util.Locale.ROOT, "%.1f", opaquePct)
                        + "% luma=" + lumaAvg + "(" + lumaMin + "-" + lumaMax + ")");
            }
        } catch (Throwable ignored) {
            // 采样失败不影响取帧
        }
    }

    private long blankFrameCount;

    private boolean directDisplayAttached;
    private int attachRetries;

    /**
     * 【OSR 直显 2026-11-19 主路径】把 JCEF 的 OSR UI 组件挂成主 Surface 的子组件：
     * Surface 位于 contentPane 之下（FRAME_CONTENT_LAYER-1）且面板已全透明，
     * 组件自身真实绘制即可在背景层显示视频/网页。这是 JCEF 支持的标准嵌入方式。
     * 全部 EDT；切换壁纸时先摘掉上一个组件。
     */
    private void attachOsrDirectDisplay(com.dwallpaper.media.JcefOsrFrameSource jfs) {
        javax.swing.JComponent comp = osrOverlayComponent;
        final WallpaperSurface target = pickDisplaySurface();
        if (comp == null || target == null) {
            LOG.warn("dwallpaper: OSR 直显挂载跳过（组件或 Surface 缺失）");
            return;
        }
        // 【尺寸竞态 2026-11-19】画布可能尚未完成布局（0×0）。挂到零尺寸画布等于
        // 永远不出画（CEF viewRect=0），必须等它拿到真实尺寸再挂（最多重试 20 次）。
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            if (attachRetries < 20) {
                attachRetries++;
                javax.swing.Timer t = new javax.swing.Timer(300, e -> attachOsrDirectDisplay(jfs));
                t.setRepeats(false);
                t.start();
                return;
            }
            LOG.warn("dwallpaper: 画布尺寸始终为 0，放弃直显挂载");
            return;
        }
        attachRetries = 0;
        try {
            // 切换壁纸：先摘掉上一个直显组件
            if (comp.getParent() instanceof WallpaperSurface old && old != target) {
                old.remove(comp);
            }
            target.setLayout(null);
            target.add(comp);
            comp.setVisible(true);
            comp.setBounds(0, 0, Math.max(1, target.getWidth()), Math.max(1, target.getHeight()));
            // 蒙层：直显模式下不透明度滑杆由本蒙层承担（dim = 100 - 不透明度）。
            final DimOverlay dim = new DimOverlay();
            target.add(dim);
            dim.setBounds(0, 0, Math.max(1, target.getWidth()), Math.max(1, target.getHeight()));
            dimOverlay = dim;
            if (!directDisplayAttached) {
                target.addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent e) {
                        // 用字段取当前组件（切换壁纸后仍指向最新的那个）
                        javax.swing.JComponent cur = osrOverlayComponent;
                        if (cur != null) {
                            cur.setBounds(0, 0, target.getWidth(), target.getHeight());
                        }
                        DimOverlay d = dimOverlay;
                        if (d != null) {
                            d.setBounds(0, 0, target.getWidth(), target.getHeight());
                        }
                    }
                });
            }
            directDisplayAttached = true;
            target.revalidate();
            target.repaint();
            // 让 CEF 按真实视图尺寸重新布局（否则可能仍按 1920x1080 渲染后缩放）
            try {
                jfs.setRenderSize(target.getWidth(), target.getHeight());
            } catch (Throwable ignored) {
                // 尺寸推送失败不影响挂载
            }
            LOG.info("dwallpaper: OSR 组件已直显挂载到背景层（surface="
                    + target.getWidth() + "x" + target.getHeight()
                    + " comp=" + comp.getWidth() + "x" + comp.getHeight()
                    + " showing=" + comp.isShowing()
                    + " displayable=" + comp.isDisplayable() + "）");
        } catch (Throwable t) {
            LOG.warn("dwallpaper: OSR 直显挂载失败", t);
        }
    }

    /**
     * 直显模式的蒙层：把主题底色以 (100 - 不透明度)% 叠在视频/网页之上。
     * 直显不走 pipeline，因此不透明度语义由本蒙层承担（模糊/亮度对直显无效）。
     */
    private final class DimOverlay extends javax.swing.JComponent {
        DimOverlay() {
            setOpaque(false);
        }

        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            int dim = 100 - currentOpacity.get();
            if (dim <= 0 || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.SrcOver.derive(dim / 100f));
                g2.setColor(scrimColor());
                g2.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
        }
    }

    private volatile DimOverlay dimOverlay;

    private void unmountOsrOverlay() {        runOnEdt(() -> {
            try {
                if (osrFramePump != null) {
                    osrFramePump.stop();
                    osrFramePump = null;
                }
                javax.swing.JComponent comp = osrOverlayComponent;
                if (comp != null && comp.getParent() instanceof WallpaperSurface p) {
                    p.remove(comp); // 直显回退挂载清理
                    DimOverlay dim = dimOverlay;
                    if (dim != null) {
                        p.remove(dim);
                        dimOverlay = null;
                    }
                    p.repaint();
                }
                directDisplayAttached = false;
                // 注意：不重置 blankFrameCount —— 用户频繁切换壁纸时也要能累积到
                // 回退阈值（实证：切一次壁纸就清零导致回退永不触发）。
                osrOverlayComponent = null;
                osrFrameBuffer = null;
            } catch (Throwable t) {
                LOG.warn("dwallpaper: OSR pump unmount issue", t);
            }
        });
    }

    /** 三滑杆参数（防抖后调用，§5.2）：模糊 0–60 / 亮度 40–160 / 不透明度 0–90。 */
    public void setParams(int blurPx, int brightnessPercent, int opacityPercent) {
        noteParams(blurPx, brightnessPercent);
        // ffmpeg 源：亮度改走滤镜（管线直通）
        FrameSource src = source;
        if (src instanceof com.dwallpaper.media.FfmpegFrameSource ff) {
            ff.setBrightnessPercent(brightnessPercent);
        }
        currentOpacity.set(Math.max(0, Math.min(AdjustmentPipeline.MAX_OPACITY_PERCENT, opacityPercent)));
        for (WallpaperSurface s : surfaceSnapshot()) {
            s.setOpacityPercent(opacityPercent);
        }
        DimOverlay dim = dimOverlay; // 直显模式：不透明度由蒙层承担
        if (dim != null) {
            dim.repaint();
        }
        repaintOverlay(); // 覆盖层强度直接跟滑杆走，改滑杆要立刻可见
        pipeline.updateParams(blurPx, brightnessPercent, primaryWidth(), primaryHeight());
    }

    /** 刷新雾镜静态快照（F13：引擎在源切换后首帧自动调用；外部显式调用仍可用）。 */
    public void refreshStaticSnapshot() {
        BufferedImage s = pipeline.snapshot();
        if (s != null) {
            staticSnapshot = s;
        }
    }

    public BufferedImage staticSnapshot() {
        return staticSnapshot;
    }

    /** 面板状态行（§8.2）：挂载级别｜帧源｜状态机。 */
    public String status() {
        String src = source == null ? "未选壁纸" : source.getClass().getSimpleName();
        return "挂载=" + mountLevel + (lastFallbackReason.isEmpty() ? "" : " (" + lastFallbackReason + ")")
                + "｜帧源=" + src + "｜状态=" + rate.state();
    }

    public String mountLevel() {
        return mountLevel;
    }

    // ---------- 内部：挂载链（EDT） ----------

    private void doMount() {
        List<Window> frames = ideFrames();
        if (frames.isEmpty()) {
            if (mountAttempts < 30) {
                mountAttempts++;
                if (mountRetryTimer == null) {
                    mountRetryTimer = new Timer(2000, e -> {
                        ((Timer) e.getSource()).stop();
                        mountRetryTimer = null;
                        if (enabled) {
                            doMount();
                        }
                    });
                    mountRetryTimer.setRepeats(false);
                    mountRetryTimer.start();
                }
                LOG.info("dwallpaper: mount deferred, waiting for IDE window (attempt " + mountAttempts + ")");
                return;
            }
            mountLevel = "FAIL";
            lastFallbackReason = "无可用 IDE 窗口";
            LOG.warn("dwallpaper: mount failed, no IDE window found");
            return;
        }
        mountAttempts = 0;
        Project project = firstProject();
        boolean s1Ok = false;
        Set<Project> s2Projects = new HashSet<>();
        for (Window w : frames) {
            MountStrategy.MountContext ctx = ctx(w, projectOf(w, project));
            try {
                if (s1.probe(w) && s1.install(ctx)) {
                    mountedWindows.add(w);
                    s1Ok = true;
                }
            } catch (Throwable t) {
                LOG.warn("dwallpaper: S1 probe/install issue", t);
            }
            Project wp = projectOf(w, project);
            if (wp != null) {
                s2Projects.add(wp); // F18 配套：S2 编辑器透明化覆盖每个有窗口的工程
            }
        }
        if (s1Ok) {
            mountLevel = "S1";
            lastFallbackReason = "";
            // 【定稿 2026-11-19】显示通道优先级：
            //  ① 原生分层透明覆盖窗（点击穿透）—— IDEA 零重绘，最流畅；
            //  ② 回退 GlassPane 玻璃覆盖层（整窗重绘，会卡但一定可见）。
            sanitizeStaleBackgroundProperty();
            for (Window w : mountedWindows) {
                NativeWallpaperOverlay n = NativeWallpaperOverlay.tryAttach(
                        w, this::bridgeFrame, this::backgroundAlphaPercent);
                if (n != null) {
                    nativeOverlay = n;
                    break;
                }
            }
            if (nativeOverlay == null) {
                for (Window w : mountedWindows) {
                    mountOverlay(w);
                }
            } else {
                for (WallpaperSurface s : surfaceSnapshot()) {
                    s.setRepaintEnabled(false); // 原生窗接管显示
                }
            }
            // 固定节奏显示时钟（消除抖动）
            restartDisplayClock(rate.currentFps() > 0 ? rate.currentFps() : FrameRateController.DEFAULT_RUNNING_FPS);
            startCoverageProbe();
            for (Project p : s2Projects) {
                Window w = frameOf(p);
                if (w != null) {
                    try {
                        s2.install(ctx(w, p));
                    } catch (Throwable t) {
                        LOG.warn("dwallpaper: S2 install issue", t);
                    }
                }
            }
            LOG.info("dwallpaper: mount level S1 (per-window surfaces) on " + mountedWindows.size()
                    + " window(s), S2 on " + s2Projects.size() + " project(s)");
            // 【方案 E 补挂 2026-11-19】启动恢复竞态：setFrameSource 早于 mount 时
            // OSR 直显组件暂存未挂——此时 mountedWindows 已填充，立即补挂。
            if (osrOverlayComponent != null && source instanceof com.dwallpaper.media.JcefOsrFrameSource) {
                mountOsrOverlay(source);
            }
            // 【S2 延迟重试 2026-11-19】挂载发生在启动早期，项目常尚未打开
            // （真实日志实证 S2 on 0 project(s)），编辑器透明化因此永不生效。
            // 项目打开后周期补装 S2，直到生效或重试耗尽。
            if (s2Projects.isEmpty()) {
                scheduleS2Retry();
            }
        } else {
            // D3：S1 失败自动落 S3 静态兜底，保证任何情况下「有图可看」
            lastFallbackReason = "S1 根窗格挂载不可用";
            boolean s3Ok = s3.probe(frames.get(0)) && s3.install(ctx(frames.get(0), project));
            mountLevel = s3Ok ? "S3" : "FAIL";
            LOG.warn("dwallpaper: mount level " + mountLevel + " (fallback: " + lastFallbackReason + ")");
        }
        rate.install();
        FrameSource s = source;
        if (s != null) {
            try {
                s.start(); // F6②：停用期间经 applyEntry 设置的源在 enable 后必须启动（幂等）
            } catch (Throwable t) {
                LOG.warn("dwallpaper: source.start failed", t);
            }
            int fps = rate.currentFps(); // F6③：暂停态保持 0
            scheduler.start(s);
            scheduler.setFps(fps);
            try {
                s.setRate(fps);
            } catch (Throwable t) {
                LOG.warn("dwallpaper: source.setRate failed", t);
            }
        }
        try {
            fogInstaller.run();
        } catch (Throwable t) {
            LOG.warn("dwallpaper: fog install issue", t);
        }
        pipeline.updateParams(currentBlur(), currentBrightness(), primaryWidth(), primaryHeight());
        pushRenderSize(); // A2：挂载后 surface 获得真实像素尺寸 → 推送渲染尺寸
    }

    /**
     * 【覆盖范围探针 2026-11-19】壁纸只在两侧细条可见，需要一次性区分
     * 「画布被平台不透明组件盖住」还是「帧内容太暗」：挂载后把画布底色刷成纯绿
     * 8 秒，用户一眼就能看出画布能透出多大面积（绿色范围 = 壁纸可达范围）。
     */
    /**
     * 【壁纸覆盖层 2026-11-19】IDEA 每个区域都由平台组件自绘实心底色（实证：
     * 把全树底色改成半透明、甚至把画布刷成纯绿探针，绿色仍只在两条工具栏缝隙可见
     * → 平台面板用固定色直接填充，不读 getBackground()，也不看 opaque 标志）。
     * 因此"真·背景"在 Swing 层不可达；改用不可交互的 GlassPane 覆盖层：壁纸以
     * 可调透明度叠在最上层。视觉上等同于整窗玻璃板，且保证一定看得见。
     * 透明度 -Ddwallpaper.overlay.alpha=0..80（默认 20）。
     */
    private final class WallpaperOverlay extends javax.swing.JComponent {
        WallpaperOverlay() {
            setOpaque(false);
        }

        @Override
        public boolean contains(int x, int y) {
            return false; // 事件穿透，不影响任何点击
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            int alpha = overlayAlphaPercent();
            if (alpha <= 0) {
                return;
            }
            WallpaperSurface s = pickDisplaySurface();
            BufferedImage img = s == null ? null : s.preparedFrame();
            if (img == null) {
                // 直显模式（CEF 组件挂在 Surface 里）没有 prepared 帧，跳过
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            long t0 = System.nanoTime();
            try {
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha / 100f));
                int w = getWidth();
                int h = getHeight();
                if (img.getWidth() == w && img.getHeight() == h) {
                    // 尺寸一致 → 1:1 blit（最快路径，避免每帧缩放重采样）
                    g2.drawImage(img, 0, 0, null);
                } else {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, w, h, null);
                }
            } finally {
                g2.dispose();
            }
            // 【性能量测】每 60 次绘制报一次平均耗时，判断 EDT 是否是瓶颈
            overlayPaintNanos += System.nanoTime() - t0;
            overlayPaintCount++;
            if (overlayPaintCount % 60 == 0) {
                com.intellij.openapi.diagnostic.Logger.getInstance(WallpaperEngine.class)
                        .info("dwallpaper: 覆盖层绘制 平均 "
                                + String.format(java.util.Locale.ROOT, "%.1f",
                                overlayPaintNanos / 1_000_000.0 / 60)
                                + "ms/帧（" + getWidth() + "x" + getHeight() + "，alpha="
                                + alpha + "%）");
                overlayPaintNanos = 0;
            }
        }
    }

    /**
     * 覆盖层强度（%）：**直接由右侧"不透明度"滑杆驱动**（0–90），使滑杆马上可见。
     * 平台面板用固定色自绘、无法透明化（实证），所以覆盖层是整窗可见的唯一通路；
     * 属性 {@code -Ddwallpaper.overlay.alpha} 可作为硬覆盖用于调试。
     */
    private int overlayAlphaPercent() {
        try {
            String v = System.getProperty("dwallpaper.overlay.alpha");
            if (v != null && !v.isBlank()) {
                return Math.max(0, Math.min(90, Integer.parseInt(v.trim())));
            }
        } catch (Throwable ignored) {
            // 非法覆盖值走滑杆值
        }
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? 25 : s.getOpacityPercent();
    }

    private volatile WallpaperOverlay overlay;
    private volatile NativeWallpaperOverlay nativeOverlay;
    private long overlayPaintNanos;
    private long overlayPaintCount;

    /**
     * 清理历史版本写入的 `idea.background.frame`（指向我们的临时帧目录）。
     * 旧版本用过该属性，若残留在配置里会让 IDEA 一直画一张过期的图（表现为黑底/
     * 切壁纸不生效）——启动时主动摘掉。
     */
    private void sanitizeStaleBackgroundProperty() {
        try {
            com.intellij.ide.util.PropertiesComponent pc =
                    com.intellij.ide.util.PropertiesComponent.getInstance();
            String v = pc.getValue(com.intellij.openapi.wm.impl.IdeBackgroundUtil.FRAME_PROP);
            if (v != null && v.contains("dwallpaper-frames")) {
                pc.unsetValue(com.intellij.openapi.wm.impl.IdeBackgroundUtil.FRAME_PROP);
                com.intellij.openapi.wm.impl.IdeBackgroundUtil.resetBackgroundImagePainters();
                com.intellij.openapi.wm.impl.IdeBackgroundUtil.repaintAllWindows();
                LOG.info("dwallpaper: 已清理历史遗留的背景图属性（曾导致黑底/切换不生效）");
            }
        } catch (Throwable ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 原生背景通道的 Disposable 列表（卸载/退出时释放）。 */
    private final List<com.intellij.openapi.Disposable> ideBackgroundDisposables = new ArrayList<>();

    private javax.swing.Timer bgFilePump;
    private int bgFileToggle;
    private BufferedImage lastPushedFrame;
    private String previousFrameBackground;

    /**
     * 【文件式背景图通道】定时把最新帧写成 PNG 并设为 IDEA 的 `idea.background.frame`。
     * 间隔默认 160ms（约 6fps）：PNG 编码 + 全窗重绘开销较大，用低帧率换流畅度；
     * `-Ddwallpaper.bgfile.interval` 可调。仅在帧变化时写盘。
     */
    private void startFileBackgroundPump() {
        previousFrameBackground = IdeBackgroundBridge.takeOverFrameProperty();
        if (bgFilePump != null) {
            bgFilePump.stop();
        }
        int interval = Math.max(80, Integer.getInteger("dwallpaper.bgfile.interval", 160));
        bgFilePump = new javax.swing.Timer(interval, e -> pushFrameToBackgroundFile());
        bgFilePump.setRepeats(true);
        bgFilePump.start();
        LOG.info("dwallpaper: 文件式背景图通道已启动（间隔 " + interval + "ms，IDEA 官方背景图通道）");
    }

    private void pushFrameToBackgroundFile() {
        try {
            WallpaperSurface s = pickDisplaySurface();
            if (s == null) {
                return;
            }
            BufferedImage img = s.preparedFrame();
            if (img == null || img == lastPushedFrame) {
                return;
            }
            lastPushedFrame = img;
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"), "dwallpaper-frames");
            java.nio.file.Path file = dir.resolve((bgFileToggle++ & 1) == 0 ? "frame0.png" : "frame1.png");
            if (IdeBackgroundBridge.writeFramePng(img, file, 1280)) {
                IdeBackgroundBridge.setFrameBackgroundFile(file, s.getOpacityPercent());
            }
        } catch (Throwable ignored) {
            // 写盘/设属性失败下一帧重试
        }
    }

    private void stopFileBackgroundPump() {
        if (bgFilePump != null) {
            bgFilePump.stop();
            bgFilePump = null;
        }
        lastPushedFrame = null;
        if (previousFrameBackground != null) {
            IdeBackgroundBridge.restoreFrameProperty(previousFrameBackground);
            previousFrameBackground = null;
        }
    }

    private long backgroundSeq;
    private long lastBackgroundRepaintMs;

    /** 有新帧时驱动平台背景重绘（限 ≤16fps，避免无谓重绘拖慢 IDE）。 */
    private void repaintNativeBackground() {
        if (ideBackgroundDisposables.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackgroundRepaintMs < 60) {
            return;
        }
        lastBackgroundRepaintMs = now;
        try {
            com.intellij.openapi.wm.impl.IdeBackgroundUtil.repaintAllWindows();
        } catch (Throwable ignored) {
            // 重绘失败下一帧再试
        }
    }

    /** 释放原生透明覆盖窗。 */
    private void disposeNativeOverlay() {
        NativeWallpaperOverlay n = nativeOverlay;
        nativeOverlay = null;
        if (n != null) {
            try {
                n.dispose();
            } catch (Throwable ignored) {
                // 释放失败不阻断
            }
        }
    }

    /** 释放原生背景通道。 */
    private void disposeNativeBackground() {
        for (com.intellij.openapi.Disposable d : ideBackgroundDisposables) {
            try {
                com.intellij.openapi.util.Disposer.dispose(d);
            } catch (Throwable ignored) {
                // 单个释放失败不阻断
            }
        }
        ideBackgroundDisposables.clear();
    }

    /** 交给平台背景通道的当前帧。 */
    private BufferedImage bridgeFrame() {
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? null : s.preparedFrame();
    }

    /** 背景强度：由"不透明度"滑杆驱动（0–90）。 */
    private int backgroundAlphaPercent() {
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? 45 : s.getOpacityPercent();
    }

    /** 把壁纸覆盖层挂到窗口 GlassPane（幂等；不可交互）。 */
    private void mountOverlay(Window window) {
        try {
            if (!(window instanceof RootPaneContainer rpc) || rpc.getRootPane() == null) {
                return;
            }
            javax.swing.JRootPane root = rpc.getRootPane();
            java.awt.Component gpComp = root.getGlassPane();
            if (!(gpComp instanceof JComponent glass)) {
                return;
            }
            WallpaperOverlay o = overlay;
            if (o == null) {
                o = new WallpaperOverlay();
                overlay = o;
            }
            if (overlayWindow == window) {
                glass.repaint();
                return;
            }
            // 【关键】GlassPane 不一定被布局管理器驱动，用 null 布局 + 显式尺寸，
            // 并起一个轻量同步定时器跟随窗口尺寸；否则覆盖层可能是 0×0 而"什么都没画"。
            glass.setLayout(null);
            glass.setOpaque(false);
            glass.remove(o);
            glass.add(o);
            o.setBounds(0, 0, Math.max(1, glass.getWidth()), Math.max(1, glass.getHeight()));
            glass.setVisible(true); // GlassPane 默认不可见
            overlayWindow = window;
            if (overlaySizeSync != null) {
                overlaySizeSync.stop();
            }
            final WallpaperOverlay fo = o;
            final JComponent fg = glass;
            overlaySizeSync = new javax.swing.Timer(250, e -> {
                try {
                    if (fg.getWidth() > 0 && fg.getHeight() > 0
                            && (fo.getWidth() != fg.getWidth() || fo.getHeight() != fg.getHeight())) {
                        fo.setBounds(0, 0, fg.getWidth(), fg.getHeight());
                        fo.repaint();
                    }
                } catch (Throwable ignored) {
                    // 单次同步失败不影响
                }
            });
            overlaySizeSync.setRepeats(true);
            overlaySizeSync.start();
            installActivityTracker(); // 操作时让路、停手时满帧
            // 【性能】覆盖层已接管显示 → 关掉 Surface 自身重绘（它被实心面板完全盖住，
            // 每帧重绘纯属浪费；这是卡顿的主要来源之一）。
            for (WallpaperSurface s : surfaceSnapshot()) {
                s.setRepaintEnabled(false);
            }
            LOG.info("dwallpaper: 壁纸覆盖层已挂到 GlassPane（alpha=" + overlayAlphaPercent()
                    + "%，覆盖层尺寸=" + o.getWidth() + "x" + o.getHeight()
                    + "，glass=" + glass.getWidth() + "x" + glass.getHeight() + "）");
        } catch (Throwable t) {
            LOG.warn("dwallpaper: 覆盖层挂载失败", t);
        }
    }

    private Window overlayWindow;
    private javax.swing.Timer overlaySizeSync;

    private void unmountOverlay() {
        try {
            if (overlaySizeSync != null) {
                overlaySizeSync.stop();
                overlaySizeSync = null;
            }
            WallpaperOverlay o = overlay;
            if (o != null && o.getParent() != null) {
                o.getParent().remove(o);
            }
            overlayWindow = null;
            lastOverlayFrame = null;
            // 覆盖层撤销 → Surface 恢复自绘
            for (WallpaperSurface s : surfaceSnapshot()) {
                s.setRepaintEnabled(true);
            }
        } catch (Throwable ignored) {
            // 卸载失败不阻断
        }
    }

    /**
     * 【固定节奏显示时钟 2026-11-19】解决"时断时续"的抖动。
     *
     * <p>旧实现由"帧到达"驱动重绘 + 墙钟节流：两帧挨得近时被节流丢弃、隔得远时画面停住，
     * 于是帧率数字不低但看起来一顿一顿。现在改成 **EDT 定时器按固定间隔采样最新帧**：
     * 无论上游帧到得多不均匀，显示节奏都是恒定的 1/fps；上游跟不上就重复上一帧（观感
     * 均匀的"略慢"），而不是忽快忽慢。</p>
     */
    private void restartDisplayClock(int fps) {
        if (displayClock != null) {
            displayClock.stop();
            displayClock = null;
        }
        if (fps <= 0) {
            return;
        }
        int delay = Math.max(8, 1000 / Math.max(1, Math.min(120, fps)));
        displayClock = new javax.swing.Timer(delay, e -> onDisplayTick());
        displayClock.setRepeats(true);
        displayClock.start();
    }

    private void stopDisplayClock() {
        if (displayClock != null) {
            displayClock.stop();
            displayClock = null;
        }
    }

    /** 每个显示节拍：只有"有新帧"才重绘，节奏由定时器保证。 */
    private void onDisplayTick() {
        NativeWallpaperOverlay nativeWin = nativeOverlay;
        WallpaperOverlay o = overlay;
        if (nativeWin == null && o == null) {
            return;
        }
        WallpaperSurface s = pickDisplaySurface();
        BufferedImage img = s == null ? null : s.preparedFrame();
        if (img == null || img == lastOverlayFrame) {
            return;
        }
        lastOverlayFrame = img;
        if (nativeWin != null) {
            nativeWin.requestPaint();
        } else {
            o.repaint();
        }
    }

    private javax.swing.Timer displayClock;

    /** 帧更新 → 覆盖层/覆盖窗重绘（节流 + 只有新帧才触发）。 */
    private void repaintOverlay() {
        NativeWallpaperOverlay nativeWin = nativeOverlay;
        WallpaperOverlay o = overlay;
        if (nativeWin == null && o == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOverlayRepaintMs < overlayMinIntervalMs()) {
            return;
        }
        WallpaperSurface s = pickDisplaySurface();
        BufferedImage img = s == null ? null : s.preparedFrame();
        if (img == null || img == lastOverlayFrame) {
            return; // 没有新帧就不重绘（静止壁纸时 CPU≈0）
        }
        lastOverlayFrame = img;
        lastOverlayRepaintMs = now;
        if (nativeWin != null) {
            nativeWin.requestPaint(); // 只重绘独立透明窗，IDEA 不重绘
        } else {
            o.repaint();
        }
    }

    /**
     * 覆盖层最小重绘间隔（自适应）：
     *  - 用户在操作（最近 700ms 内有鼠标/键盘事件）→ 5fps：把 EDT 让给 IDE，
     *    这时候"壁纸卡一点"没人注意，界面跟手最重要；
     *  - 空闲 → 满帧（默认 24fps，-Ddwallpaper.overlay.fps 可调）。
     * 依据：玻璃面板重绘会连带 IDE 整窗重绘，这是 IDEA 的硬成本；真实壁纸也是
     * 这个行为（操作时让路、停手时流畅）。
     */
    private long overlayMinIntervalMs() {
        int full = Integer.getInteger("dwallpaper.overlay.fps", 30);
        full = Math.max(5, Math.min(120, full));
        long idleInterval = Math.max(8L, 1000L / full);
        // 原生透明窗模式下 IDEA 不重绘，操作时也没必要大幅降帧（只留轻量让路）
        long busyInterval = nativeOverlay != null
                ? Math.max(33L, 1000L / 30)   // 30fps
                : Math.max(120L, 1000L / 5);  // 玻璃面板模式：操作时降到 5fps
        return (System.currentTimeMillis() - lastUserActivityMs < 700) ? busyInterval : idleInterval;
    }

    private volatile long lastUserActivityMs = System.currentTimeMillis();

    /** 全局输入监听：只为判定"用户是否在操作"（不消费事件）。 */
    private void installActivityTracker() {
        if (activityTracker != null) {
            return;
        }
        activityTracker = new java.awt.event.AWTEventListener() {
            @Override
            public void eventDispatched(java.awt.AWTEvent event) {
                int id = event.getID();
                // MOUSE_FIRST..MOUSE_LAST 已覆盖鼠标滚轮（MOUSE_WHEEL_EVENT 在区间内）
                if (id >= java.awt.event.MouseEvent.MOUSE_FIRST
                        && id <= java.awt.event.MouseEvent.MOUSE_LAST
                        || id >= java.awt.event.KeyEvent.KEY_FIRST
                        && id <= java.awt.event.KeyEvent.KEY_LAST) {
                    lastUserActivityMs = System.currentTimeMillis();
                }
            }
        };
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(activityTracker,
                    java.awt.AWTEvent.MOUSE_EVENT_MASK
                            | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
                            | java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK
                            | java.awt.AWTEvent.KEY_EVENT_MASK);
        } catch (Throwable t) {
            LOG.warn("dwallpaper: 输入活动监听安装失败（覆盖层固定帧率）", t);
        }
    }

    private java.awt.event.AWTEventListener activityTracker;

    private long lastOverlayRepaintMs;
    private BufferedImage lastOverlayFrame;

    private void startCoverageProbe() {
        if (!Boolean.parseBoolean(System.getProperty("dwallpaper.selftest.coverage", "false"))) {
            return;
        }
        WallpaperSurface.setCoverageProbe(true, new java.awt.Color(0x00, 0xFF, 0x00));
        for (WallpaperSurface s : surfaceSnapshot()) {
            s.repaint();
        }
        LOG.info("dwallpaper: 覆盖范围探针开启（8 秒纯绿）");
        javax.swing.Timer t = new javax.swing.Timer(8000, e -> {
            WallpaperSurface.setCoverageProbe(false, null);
            for (WallpaperSurface s : surfaceSnapshot()) {
                s.repaint();
            }
            LOG.info("dwallpaper: 覆盖范围探针结束（绿色消失）");
        });
        t.setRepeats(false);
        t.start();
    }

    /** 【S2 延迟重试 2026-11-19】项目打开后补装 S2；2s×15 次，成功即停（幂等由 S2 策略守卫）。 */
    private void scheduleS2Retry() {
        if (s2RetryTimer != null) {
            return;
        }
        javax.swing.Timer[] holder = new javax.swing.Timer[1];
        final int[] attempts = {0};
        holder[0] = new javax.swing.Timer(2000, e -> {
            javax.swing.Timer self = holder[0];
            attempts[0]++;
            if (!enabled || disposed) {
                self.stop();
                return;
            }
            Project[] open = com.intellij.openapi.project.ProjectManager.getInstanceIfCreated() == null
                    ? new Project[0]
                    : com.intellij.openapi.project.ProjectManager.getInstanceIfCreated().getOpenProjects();
            int applied = 0;
            for (Project p : open) {
                Window w = frameOf(p);
                if (w == null) {
                    continue;
                }
                try {
                    if (s2.probe(w) && s2.install(ctx(w, p))) {
                        applied++;
                    }
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: S2 deferred install issue", t);
                }
            }
            if (applied > 0) {
                self.stop();
                s2RetryTimer = null;
                LOG.info("dwallpaper: S2 deferred install applied on " + applied
                        + " project(s) after " + attempts[0] + " attempt(s)");
                // 【玻璃板补装 2026-11-19】项目打开后工具窗/编辑器懒创建 → 错峰补装
                //（2s/5s/10s/20s），抓取项目窗口内后出现的组件。
                int[] delays = {2000, 5000, 10000, 20000};
                for (int i = 0; i < delays.length; i++) {
                    javax.swing.Timer pass = new javax.swing.Timer(delays[i], ev -> applyGlassPass());
                    pass.setRepeats(false);
                    pass.start();
                }
            } else if (attempts[0] >= 15) {
                self.stop();
                s2RetryTimer = null;
                LOG.info("dwallpaper: S2 deferred install gave up after " + attempts[0] + " attempt(s)");
            }
        });
        holder[0].setRepeats(true);
        s2RetryTimer = holder[0];
        s2RetryTimer.start();
        LOG.info("dwallpaper: S2 deferred retry scheduled (project not open at mount)");
    }

    private void doUnmount() {
        FrameSource s = source;        if (s != null) {
            try {
                s.setRate(0); // F6①：停用期间挂起源（GIF/序列帧暂停解码、JCEF 挂起）
            } catch (Throwable t) {
                LOG.warn("dwallpaper: source pause-on-disable failed", t);
            }
        }
        try {
            scheduler.stop();
        } catch (Throwable ignored) {
        }
        try {
            fogUninstaller.run();
        } catch (Throwable t) {
            LOG.warn("dwallpaper: fog uninstall issue", t);
        }
        try {
            rate.uninstall();
        } catch (Throwable ignored) {
        }
        try {
            s1.uninstall();
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S1 uninstall issue", t);
        }
        unmountOverlay();
        disposeNativeBackground();
        stopFileBackgroundPump();
        disposeNativeOverlay();
        stopDisplayClock();
        try {
            s2.uninstall();
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S2 uninstall issue", t);
        }
        try {
            s3.uninstall();
        } catch (Throwable t) {
            LOG.warn("dwallpaper: S3 uninstall issue", t);
        }
        synchronized (surfaces) {
            for (WallpaperSurface surf : surfaces) {
                surf.releaseGraphics(); // F12：卸载释放 VRAM
            }
            surfaces.clear();
        }
        mountedWindows.clear();
        mountLevel = "OFF";
        LOG.info("dwallpaper: unmounted (full restore)");
    }

    private void onSurfaceResized() {
        pipeline.updateParams(currentBlur(), currentBrightness(), primaryWidth(), primaryHeight());
        pushRenderSize(); // A2：resize 防抖后同步 JCEF 渲染尺寸（150ms 防抖）
    }

    /** A2 接线：各窗 surface 实际像素尺寸 → 媒体源（JCEF OSR 渲染缓冲；§4.2 DPI 口径）。 */
    private void pushRenderSize() {
        FrameSource src = source;
        if (src == null) {
            return;
        }
        for (WallpaperSurface s : surfaceSnapshot()) {
            try {
                src.setRenderSize(s.getWidth(), s.getHeight());
            } catch (Throwable t) {
                LOG.warn("dwallpaper: source.setRenderSize failed", t);
            }
        }
    }

    private int primaryWidth() {
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? 0 : s.getWidth();
    }

    private int primaryHeight() {
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? 0 : s.getHeight();
    }

    private int currentBlur() {
        return pipelineBlur.get();
    }

    private int currentBrightness() {
        return pipelineBrightness.get();
    }

    // 参数回读占位：真实值由 controller 每次 setParams 传入；重算时用最近值
    private final java.util.concurrent.atomic.AtomicInteger pipelineBlur = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger pipelineBrightness =
            new java.util.concurrent.atomic.AtomicInteger(100);
    private final java.util.concurrent.atomic.AtomicInteger currentOpacity =
            new java.util.concurrent.atomic.AtomicInteger(WallpaperSurface.DEFAULT_OPACITY_PERCENT);

    private List<Window> ideFrames() {
        List<Window> out = new ArrayList<>();
        try {
            for (Project p : ProjectManager.getInstance().getOpenProjects()) {
                JFrame frame = frameOf(p);
                if (frame != null && frame.isShowing()) {
                    out.add(frame);
                }
            }
        } catch (Throwable t) {
            LOG.warn("dwallpaper: ideFrames failed", t);
        }
        if (out.isEmpty()) {
            // 兜底：遍历顶层窗口中的 JFrame（Headless/无工程启动时可能缺失 project frame）
            for (Window w : Window.getWindows()) {
                if (w instanceof JFrame f && f.isShowing()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private JFrame frameOf(Project p) {
        try {
            return WindowManager.getInstance().getFrame(p);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private Project firstProject() {
        try {
            Project[] ps = ProjectManager.getInstance().getOpenProjects();
            return ps.length > 0 ? ps[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private Project projectOf(Window w, Project fallback) {
        // 窗口→工程映射（IDEA 主窗=唯一 project frame）；非 project 窗回退首个工程
        try {
            for (Project p : ProjectManager.getInstance().getOpenProjects()) {
                if (frameOf(p) == w) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private boolean isFocusOnIde() {
        try {
            Window f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (f == null) {
                return false;
            }
            if (mountedWindows.contains(f)) {
                return true;
            }
            Window owner = f.getOwner();
            while (owner != null) {
                if (mountedWindows.contains(owner)) {
                    return true;
                }
                owner = owner.getOwner();
            }
            return false;
        } catch (Throwable t) {
            return true; // 判定失败按「在焦点」处理，避免误降帧
        }
    }

    private MountStrategy.MountContext ctx(Window w, Project p) {
        return new MountStrategy.MountContext() {
            @Override
            public WallpaperSurface surface() {
                WallpaperSurface s = createSurface(); // F18：按窗实例化
                // 【Border 方案 2026-11-19】帧更新 → 重绘所有装了 WallpaperBorder 的面板
                s.setFrameListener(() -> repaintBorderPanels());
                return s;
            }

            @Override
            public void registerSurface(WallpaperSurface surface) {
                synchronized (surfaces) {
                    surfaces.add(surface);
                }
                // 【壁纸不显示修复 2026-11-19】Surface 随布局从 0×0 变为实际尺寸时，
                // 重推管线参数与 renderSize（挂载早于首次布局的兜底）。
                surface.setSizeListener((w, h) -> {
                    if (w > 0 && h > 0) {
                        pipeline.updateParams(pipelineBlur.get(), pipelineBrightness.get(), w, h);
                        pushRenderSize();
                        refreshStaticSnapshot();
                    }
                });
            }

            @Override
            public void unregisterSurface(WallpaperSurface surface) {
                synchronized (surfaces) {
                    surfaces.remove(surface);
                }
            }

            @Override
            public void registerBorderPanel(JComponent panel) {
                synchronized (borderPanels) {
                    borderPanels.add(panel);
                }
            }

            @Override
            public void unregisterBorderPanel(JComponent panel) {
                synchronized (borderPanels) {
                    borderPanels.remove(panel);
                }
            }

            @Override
            public WallpaperPaint paint() {
                return WallpaperEngine.this;
            }

            @Override
            public Window window() {
                return w;
            }

            @Override
            public Project project() {
                return p;
            }
        };
    }

    /** Border 面板集（【Border 方案】帧更新扇出重绘目标）。 */
    private final Set<JComponent> borderPanels = new HashSet<>();

    // ---------- WallpaperPaint 实现（【可读性架构 2026-11-19】） ----------

    /** 雾化帧缓存（key=blur_brightness；帧变更清空）。 */
    private final java.util.Map<String, BufferedImage> frostedCache = new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService frostedExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dwallpaper-frosted-frame");
                t.setDaemon(true);
                return t;
            });
    /** 面板→雾区参数解析（settings/controller 注入；core 不 import fog）。 */
    private volatile java.util.function.Function<JComponent, FogZoneParamsLike> zoneForProvider;

    /** controller 注入面板→雾区解析（fog.ZoneResolver 在 settings 侧组合）。 */
    public void setZoneForProvider(java.util.function.Function<JComponent, FogZoneParamsLike> provider) {
        this.zoneForProvider = provider;
    }

    @Override
    public BufferedImage sharpFrame() {
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? null : s.preparedFrame();
    }

    @Override
    public int opacityPercent() {
        WallpaperSurface s = pickDisplaySurface();
        return s == null ? WallpaperSurface.DEFAULT_OPACITY_PERCENT : s.getOpacityPercent();
    }

    @Override
    public BufferedImage frostedFrame(int blurPx, int brightnessOffsetPercent) {
        BufferedImage src = pipeline.snapshot();
        if (src == null) {
            return null;
        }
        int blur = Math.max(0, Math.min(AdjustmentPipeline.MAX_BLUR_PX, blurPx));
        int brightness = Math.max(AdjustmentPipeline.MIN_BRIGHTNESS_PERCENT,
                Math.min(AdjustmentPipeline.MAX_BRIGHTNESS_PERCENT, brightnessOffsetPercent));
        String key = blur + "_" + brightness;
        synchronized (frostedCache) {
            BufferedImage hit = frostedCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        frostedExecutor.execute(() -> {
            try {
                BufferedImage out = AdjustmentPipeline.scaleTo(src, src.getWidth(), src.getHeight());
                if (blur > 0) {
                    int bucket = Math.max(0, Math.min(6, blur / 5));
                    out = AdjustmentPipeline.boxBlur(out, bucket * 5, 3);
                }
                if (brightness != 100) {
                    int[] lut = new int[256];
                    double f = brightness / 100.0;
                    for (int i = 0; i < 256; i++) {
                        lut[i] = Math.max(0, Math.min(255, (int) Math.round(i * f)));
                    }
                    out = AdjustmentPipeline.applyBrightness(out, lut);
                }
                synchronized (frostedCache) {
                    frostedCache.put(key, out);
                }
                runOnEdt(this::repaintBorderPanels);
            } catch (Throwable t) {
                LOG.warn("dwallpaper: frosted frame compute failed", t);
            }
        });
        return null; // 首帧未就绪：调用方本帧不画，就绪后扇出重绘
    }

    @Override
    public FogZoneParamsLike zoneFor(Component panel) {
        java.util.function.Function<JComponent, FogZoneParamsLike> p = zoneForProvider;
        return p == null ? null : p.apply((JComponent) panel);
    }

    /** 【方案 E】视频直显模式的玻璃面板底色（主题 Panel 色）。 */
    @Override
    public java.awt.Color scrimColor() {
        java.awt.Color c = javax.swing.UIManager.getColor("Panel.background");
        return c != null ? c : new java.awt.Color(0x1E1F22);
    }

    /** 底色不透明度：【方案 E】直显模式轻底 12%——玻璃面板多层叠加会累积遮蔽
     * （实证"完全看不到视频"），固定低值保证视频清晰透过；代码可读性由编辑器
     * 内容面 scrim（85%）独立保障。 */
    @Override
    public int scrimAlphaPercent() {
        return 12;
    }

    private void repaintBorderPanels() {
        for (JComponent p : borderPanels) {
            try {
                p.repaint();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 【玻璃板补装 2026-11-19】对全部已挂载窗执行一次玻璃化补装（EDT，幂等）。
     * 触发点：S2 延迟成功后错峰（2/5/10/20s，覆盖项目打开后懒初始化的工具窗）、
     * applyEntry（用户每次应用壁纸）。工具窗显示监听在 262 接口签名有变，弃用。
     */
    public void applyGlassPass() {
        runOnEdt(() -> {
            List<Window> windows = new ArrayList<>(mountedWindows);
            Project first = firstProject();
            for (Window w : windows) {
                try {
                    s1.ensureGlass(w, ctx(w, projectOf(w, first)));
                } catch (Throwable t) {
                    LOG.warn("dwallpaper: glass pass issue on window", t);
                }
            }
        });
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** setParams 时同步记录管线参数供 resize 重算（避免回读 pipeline 内部）。 */
    public void noteParams(int blurPx, int brightnessPercent) {
        pipelineBlur.set(Math.max(0, Math.min(AdjustmentPipeline.MAX_BLUR_PX, blurPx)));
        pipelineBrightness.set(Math.max(AdjustmentPipeline.MIN_BRIGHTNESS_PERCENT,
                Math.min(AdjustmentPipeline.MAX_BRIGHTNESS_PERCENT, brightnessPercent)));
    }

    /** 供 SelfTest 检查已挂载窗口集合（不可变快照）。 */
    public Set<Window> mountedWindowSet() {
        return new HashSet<>(mountedWindows);
    }

    /** F8：IDE 退出释放链（Disposer 回调，幂等）。 */
    private void disposeAll() {
        if (disposed) {
            return;
        }
        disposed = true;
        enabled = false;
        if (s2RetryTimer != null) {
            s2RetryTimer.stop();
            s2RetryTimer = null;
        }
        FrameSource s = source;
        if (s != null) {
            try {
                s.setRate(0);
            } catch (Throwable ignored) {
            }
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        try {
            scheduler.stop();
        } catch (Throwable ignored) {
        }
        try {
            rate.uninstall();
        } catch (Throwable ignored) {
        }
        try {
            fogUninstaller.run();
        } catch (Throwable ignored) {
        }
        try {
            s1.uninstall();
        } catch (Throwable ignored) {
        }
        unmountOverlay();
        disposeNativeBackground();
        stopFileBackgroundPump();
        disposeNativeOverlay();
        stopDisplayClock();
        try {
            s2.uninstall();
        } catch (Throwable ignored) {
        }
        try {
            s3.uninstall(); // S3 属性清理（EDITOR_PROP 不跨重启残留）
        } catch (Throwable ignored) {
        }
        try {
            pipeline.shutdown();
        } catch (Throwable ignored) {
        }
        synchronized (surfaces) {
            for (WallpaperSurface surf : surfaces) {
                try {
                    surf.releaseGraphics();
                } catch (Throwable ignored) {
                }
            }
            surfaces.clear();
        }
        mountedWindows.clear();
        LOG.info("dwallpaper: engine disposed (app exit release chain)");
    }
}

