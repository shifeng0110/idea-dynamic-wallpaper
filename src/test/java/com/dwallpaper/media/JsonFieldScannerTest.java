package com.dwallpaper.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** project.json "file" 字段抽取（t1 §3.2：含空格/括号/非 ASCII/转义，原样还原）。 */
class JsonFieldScannerTest {

    @Test
    void extractsPlainValue() {
        String json = "{\"title\":\"demo\",\"file\":\"video.mp4\",\"preview\":\"p.jpg\"}";
        assertEquals("video.mp4", JsonFieldScanner.extractString(json, "file"));
        assertEquals("demo", JsonFieldScanner.extractString(json, "title"));
    }

    @Test
    void keepsSpacesParensAndSlash() {
        String json = "{\"file\":\"my folder (1)\\\\sub\\\\clip.webm\"}";
        assertEquals("my folder (1)\\sub\\clip.webm", JsonFieldScanner.extractString(json, "file"));
    }

    @Test
    void decodesUnicodeEscapes() {
        String json = "{\"file\":\"\\u4e2d\\u6587\\u58c1\\u7eb8.mp4\"}";
        assertEquals("中文壁纸.mp4", JsonFieldScanner.extractString(json, "file"));
    }

    @Test
    void decodesEscapedQuotesAndControlChars() {
        String json = "{\"file\":\"a\\\"b\\tc.mp4\"}";
        assertEquals("a\"b\tc.mp4", JsonFieldScanner.extractString(json, "file"));
    }

    @Test
    void toleratesWhitespace() {
        String json = "{  \"file\"  :   \"video.mp4\"  }";
        assertEquals("video.mp4", JsonFieldScanner.extractString(json, "file"));
    }

    @Test
    void missingFieldReturnsNull() {
        assertNull(JsonFieldScanner.extractString("{\"title\":\"demo\"}", "file"));
    }

    @Test
    void nonStringValueReturnsNull() {
        assertNull(JsonFieldScanner.extractString("{\"file\": 3}", "file"));
        assertNull(JsonFieldScanner.extractString("{\"file\": true}", "file"));
    }

    @Test
    void malformedInputReturnsNull() {
        assertNull(JsonFieldScanner.extractString(null, "file"));
        assertNull(JsonFieldScanner.extractString("", "file"));
        assertNull(JsonFieldScanner.extractString("{\"file\":\"unterminated", "file"));
        assertNull(JsonFieldScanner.extractString("not json at all", "file"));
    }
}
