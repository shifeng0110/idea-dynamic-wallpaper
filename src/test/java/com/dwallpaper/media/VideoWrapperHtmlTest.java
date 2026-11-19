package com.dwallpaper.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 视频包装页契约（任务口径第 3 条：音频默认静音；design.md §9.3：loop muted autoplay）。 */
class VideoWrapperHtmlTest {

    @Test
    void containsMutedLoopAutoplay() {
        String html = VideoWrapperHtml.build("http://127.0.0.1:54321/media.mp4", "#000000");
        assertTrue(html.contains("muted"), "音频默认静音");
        assertTrue(html.contains("loop"), "循环播放");
        assertTrue(html.contains("autoplay"), "自动播放");
        assertTrue(html.contains("playsinline"), "内联播放");
        assertTrue(html.contains("object-fit:cover"), "铺满视口");
        assertTrue(html.contains("src=\"http://127.0.0.1:54321/media.mp4\""), "媒体地址");
        assertTrue(html.contains("v.muted=true"), "脚本级静音兜底");
        assertTrue(html.contains("v.volume=0"), "音量归零兜底");
    }

    @Test
    void escapesDoubleQuotesInUrl() {
        String html = VideoWrapperHtml.build("http://127.0.0.1:1/media\"x.mp4", null);
        assertFalse(html.contains("src=\"http://127.0.0.1:1/media\"x.mp4\""), "引号必须转义");
        assertTrue(html.contains("%22"));
    }

    @Test
    void nullUrlYieldsEmptySrc() {
        String html = VideoWrapperHtml.build(null, null);
        assertTrue(html.contains("src=\"\""));
        assertTrue(html.contains("#000000"), "posterColor 缺省为纯黑");
    }
}
