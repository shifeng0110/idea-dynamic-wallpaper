package com.dwallpaper.media;

/**
 * project.json 字符串字段最小抽取器（t5）：media 侧只读 "file" 字段（t1 §3.2：file/preview 原样拼接，
 * 含空格/括号/非 ASCII，不做 trim/转义假设）。
 * design.md §1.2 要求 Gson 隔离在 welibrary JsonCodec（t4 落地）；t5 只需单字段，为保持 media 包
 * 零第三方依赖、可在纯 JVM 单测，这里实现最小字面量扫描：支持双引号/反斜杠/斜杠/控制符转义与
 * Unicode（反斜杠 u + 4 位十六进制）转义。
 * 契约：不 throw（任何解析失败一律返回 null，调用方降级日志）。
 */
public final class JsonFieldScanner {

    private JsonFieldScanner() {
    }

    /** 提取顶层（含嵌套位置首次出现）字符串字段值；缺失/非字符串/畸形返回 null。 */
    public static String extractString(String json, String field) {
        if (json == null || field == null || json.isEmpty()) {
            return null;
        }
        int n = json.length();
        int i = 0;
        int[] end = new int[1];
        while (i < n) {
            char c = json.charAt(i);
            if (c != '"') {
                i++;
                continue;
            }
            String key = readString(json, i, end);
            if (key == null) {
                return null; // 畸形 JSON：该字符串未闭合
            }
            i = end[0];
            // 跳过空白与冒号后判断值
            while (i < n && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i < n && json.charAt(i) == ':') {
                i++;
                while (i < n && Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                if (i < n && json.charAt(i) == '"' && field.equals(key)) {
                    return readString(json, i, end);
                }
            }
            // 非目标键：从 key 闭合处继续扫描（值串会被当作下一个候选 key 重扫，无副作用）
        }
        return null;
    }

    /** 从 start（必须指向 '"'）读取完整 JSON 字符串；endOut[0] = 闭合引号后一位置。失败返回 null。 */
    private static String readString(String s, int start, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                endOut[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\') {
                i++;
                if (i >= n) {
                    return null;
                }
                char e = s.charAt(i);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 >= n) {
                            return null;
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                        i += 4;
                        break;
                    default:
                        return null;
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return null; // 未闭合
    }
}
