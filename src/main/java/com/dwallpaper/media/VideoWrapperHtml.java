package com.dwallpaper.media;

/**
 * 视频包装页生成器（design.md §9.3，t5）：{@code <video loop muted autoplay playsinline>}。
 * 音频默认静音 = 任务口径（t5）；muted 播放在 Chrome/CEF 默认自动播放策略下无需用户手势。
 * t5 决策：视频条目统一经 LocalMediaServer（http://127.0.0.1）供源，包装页同为 http 回环页，
 * 规避 file:// 页面加载 http 子资源的混合内容不确定性（理由详见 README「t5 实现决策」）。
 */
public final class VideoWrapperHtml {

    private VideoWrapperHtml() {
    }

    /** 生成包装页 HTML；mediaUrl 为 http://127.0.0.1:port/media.&lt;ext&gt;（LocalMediaServer#url()）。 */
    public static String build(String mediaUrl, String posterColor) {
        String src = mediaUrl == null ? "" : mediaUrl.replace("\"", "%22");
        return "<!doctype html>\n"
                + "<html><head><meta charset=\"utf-8\"><title>dwallpaper-video</title>\n"
                + "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:"
                + (posterColor == null ? "#000000" : posterColor) + ";overflow:hidden}\n"
                + "video{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}</style></head>\n"
                + "<body>\n"
                + "<video id=\"dw-video\" loop muted autoplay playsinline preload=\"auto\" src=\"" + src + "\"></video>\n"
                + "<script>(function(){var v=document.getElementById('dw-video');if(!v){return;}"
                + "v.muted=true;v.volume=0;v.loop=true;"
                + "var p=v.play();if(p&&p['catch']){p['catch'](function(){});}})();</script>\n"
                + "</body></html>\n";
    }
}
