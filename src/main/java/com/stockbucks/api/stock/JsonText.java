package com.stockbucks.api.stock;

import java.util.ArrayList;
import java.util.List;

/**
 * 極簡 JSON/數字文字工具。
 *
 * 專案目前不額外引入 JSON 函式庫，所以這裡只處理 API 回應中常見的簡單欄位擷取。
 */
final class JsonText {
    private JsonText() {
    }

    static String value(String objectText, String fieldName) {
        // 從 JSON 片段中取出指定欄位的第一個值，支援字串與簡單數值。
        String key = "\"" + fieldName + "\"";
        int keyIndex = objectText.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }

        int colon = objectText.indexOf(':', keyIndex + key.length());
        if (colon < 0) {
            return "";
        }

        int valueStart = colon + 1;
        while (valueStart < objectText.length() && Character.isWhitespace(objectText.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= objectText.length()) {
            return "";
        }

        if (objectText.charAt(valueStart) == '"') {
            return quotedValue(objectText, valueStart + 1);
        }

        int valueEnd = valueStart;
        while (valueEnd < objectText.length()) {
            char c = objectText.charAt(valueEnd);
            if (c == ',' || c == '}' || c == ']') {
                break;
            }
            valueEnd++;
        }
        return objectText.substring(valueStart, valueEnd).trim();
    }

    static List<String> objects(String json) {
        // 掃描 JSON 文字中的物件片段，供簡單陣列解析使用。
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }
        return objects;
    }

    static String cleanNumber(String value) {
        // 把逗號、加號、中文單位等非數字內容清掉，方便 parse。
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replace(",", "")
                .replace("+", "")
                .replace("--", "")
                .replaceAll("[^0-9.\\-]", "")
                .trim();
        if (cleaned.equals("-") || cleaned.equals(".")) {
            return "";
        }
        return cleaned;
    }

    static double parseDouble(String value) {
        String cleaned = cleanNumber(value);
        return cleaned.isBlank() ? 0 : Double.parseDouble(cleaned);
    }

    static long parseLong(String value) {
        String cleaned = cleanNumber(value);
        return cleaned.isBlank() ? 0 : Long.parseLong(cleaned);
    }

    static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private static String quotedValue(String text, int start) {
        // 讀取 JSON 字串值，處理基本跳脫字元。
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                value.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return value.toString();
            }
            value.append(c);
        }
        return "";
    }
}
