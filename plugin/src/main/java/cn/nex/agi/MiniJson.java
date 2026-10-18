package cn.nex.agi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 工具：只处理"扁平的字符串/布尔/数字对象"，够本项目用，不引入任何依赖。
 */
public final class MiniJson {

    private MiniJson() {
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** obj("a", "1", "b", true, "c", 3) */
    public static String obj(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(esc(String.valueOf(kv[i]))).append("\":");
            Object v = kv[i + 1];
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Boolean || v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** 解析扁平对象，所有值统一转成 String（true/false 也变 "true"/"false"）。 */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        int i = 0;
        int n = text.length();
        while (i < n && text.charAt(i) != '{') i++;
        if (i >= n) return out;
        i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) i++;
            if (i >= n || text.charAt(i) == '}') break;
            if (text.charAt(i) != '"') {
                // 不是 key，跳过
                i++;
                continue;
            }
            StringBuilder key = new StringBuilder();
            i = readString(text, i, key);
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i < n && text.charAt(i) == ':') i++;
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            if (text.charAt(i) == '"') {
                StringBuilder val = new StringBuilder();
                i = readString(text, i, val);
                out.put(key.toString(), val.toString());
            } else {
                int start = i;
                while (i < n && text.charAt(i) != ',' && text.charAt(i) != '}') i++;
                String raw = text.substring(start, i).trim();
                if (raw.equals("null")) raw = "";
                out.put(key.toString(), raw);
            }
        }
        return out;
    }

    private static int readString(String text, int i, StringBuilder sb) {
        int n = text.length();
        i++; // 跳过起始引号
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = text.charAt(i + 1);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 5 < n) {
                            try {
                                sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append(e);
                            }
                        }
                        break;
                    default: sb.append(e);
                }
                i += 2;
                continue;
            }
            if (c == '"') return i + 1;
            sb.append(c);
            i++;
        }
        return i;
    }
}
