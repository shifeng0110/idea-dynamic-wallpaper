package com.dwallpaper.welibrary;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gson 隔离类（design.md §2.3 契约：project.json 解析与库索引缓存序列化全部收口在单一类内）。
 * 宽松解析：UTF-8 读取；type 先 toLowerCase() 再分派（t1 实测 Video/video 混用）；
 * file/preview 原样拼接，不做 trim/转义假设（实测含空格、括号、非 ASCII）。
 */
public final class JsonCodec {

    public static final int CACHE_VERSION = 1;

    /** project.json 关心字段。 */
    public record Parsed(@Nullable String title, @Nullable String typeRaw,
                         @Nullable String file, @Nullable String preview) {
    }

    /** 库索引缓存 DTO（plain class，兼容平台捆绑 Gson 版本对 record 的支持差异）。 */
    public static final class CacheDto {
        public int version = CACHE_VERSION;
        public long scannedAt;
        public List<EntryDto> entries = new ArrayList<>();
    }

    public static final class EntryDto {
        public String id;
        public String title;
        public String type;
        public String folderPath;
        public String projectJsonPath;
        public String mediaPath;
        public String previewPath;
        public boolean supported;
        public String sourceDir;
    }

    private JsonCodec() {
    }

    /** project.json 大小上限（F16，与 WeEntryResolver 侧 256KB 口径一致）。 */
    public static final long MAX_PROJECT_JSON_BYTES = 256 * 1024L;

    @Nullable
    public static Parsed parseProject(Path projectJson) {
        try {
            // F16：256KB 大小上限（与 WeEntryResolver 侧口径一致）——超限/畸形超大 JSON 不全量读入内存
            if (Files.size(projectJson) > MAX_PROJECT_JSON_BYTES) {
                return null;
            }
            String json = Files.readString(projectJson, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            return new Parsed(str(o, "title"), str(o, "type"), str(o, "file"), str(o, "preview"));
        } catch (Throwable t) {
            return null; // 宽松：损坏 project.json → 按未知类型兜底
        }
    }

    private static String str(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            String v = o.get(key).getAsString();
            return v == null || v.isBlank() ? null : v;
        }
        return null;
    }

    public static String serializeCache(CacheDto dto) {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        return gson.toJson(dto);
    }

    @Nullable
    public static CacheDto parseCache(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return null;
            }
            CacheDto dto = new CacheDto();
            JsonObject o = el.getAsJsonObject();
            if (o.has("version")) {
                dto.version = o.get("version").getAsInt();
            }
            if (dto.version != CACHE_VERSION) {
                return null; // 版本不符视为无缓存（§3.3）
            }
            if (o.has("scannedAt")) {
                dto.scannedAt = o.get("scannedAt").getAsLong();
            }
            if (o.has("entries") && o.get("entries").isJsonArray()) {
                com.google.gson.Gson gson = new com.google.gson.Gson();
                for (JsonElement e : o.getAsJsonArray("entries")) {
                    EntryDto ed = gson.fromJson(e, EntryDto.class);
                    if (ed != null && ed.folderPath != null) {
                        dto.entries.add(ed);
                    }
                }
            }
            return dto;
        } catch (Throwable t) {
            return null; // 缓存损坏视为无缓存（§3.3）
        }
    }
}
