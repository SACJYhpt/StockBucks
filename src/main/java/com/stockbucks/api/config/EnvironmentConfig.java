package com.stockbucks.api.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 統一讀取環境設定的工具。
 *
 * 優先順序：
 * 1. Java system property
 * 2. 作業系統環境變數
 * 3. 專案 .env
 * 4. 專案 stockbucks.env
 * 5. 使用者家目錄 ~/.stockbucks/.env
 */
public final class EnvironmentConfig {
    private static final Map<String, String> DOTENV = loadDotEnv(); // 啟動時讀一次，避免每次查設定都碰檔案系統。

    private EnvironmentConfig() {
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String defaultValue) {
        // system property 方便測試時用 -DKEY=value 覆蓋。
        String systemValue = System.getProperty(key);
        if (hasText(systemValue)) {
            return systemValue.trim();
        }

        String envValue = System.getenv(key);
        if (hasText(envValue)) {
            return envValue.trim();
        }

        String dotenvValue = DOTENV.get(key);
        if (hasText(dotenvValue)) {
            return dotenvValue.trim();
        }

        return defaultValue;
    }

    public static String first(String defaultValue, String... keys) {
        // 多個別名取第一個有值的，例如 GEMINI_API_KEY / GOOGLE_API_KEY。
        for (String key : keys) {
            String value = get(key);
            if (hasText(value)) {
                return value;
            }
        }
        return defaultValue;
    }

    public static boolean has(String key) {
        return hasText(get(key));
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new HashMap<>();
        for (Path path : candidatePaths()) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    parseLine(line, values);
                }
            } catch (IOException ignored) {
                // .env 不存在或讀不到時，仍可使用系統環境變數。
            }
        }
        return values;
    }

    private static List<Path> candidatePaths() {
        String userHome = System.getProperty("user.home", ".");
        return List.of(
                Path.of(".env"),
                Path.of("stockbucks.env"),
                Path.of(userHome, ".stockbucks", ".env")
        );
    }

    private static void parseLine(String line, Map<String, String> values) {
        // 支援 KEY=value、單引號、雙引號；不支援複雜 shell 語法。
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return;
        }

        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
            return;
        }

        String key = trimmed.substring(0, separator).trim();
        String value = trimmed.substring(separator + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        values.putIfAbsent(key, value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
