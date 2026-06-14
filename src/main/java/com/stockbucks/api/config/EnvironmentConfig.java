package com.stockbucks.api.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 統一讀取 StockBucks API 需要的環境變數。
 *
 * 讀取優先順序：
 * 1. Java system property，例如 -DKEY=value
 * 2. 作業系統環境變數
 * 3. 專案根目錄 .env
 * 4. 專案根目錄 stockbucks.local.env
 * 5. 專案根目錄 stockbucks.env
 * 6. 使用者家目錄 ~/.stockbucks/.env
 */
public final class EnvironmentConfig {
    private static final String LOCAL_ENV_FILE = "stockbucks.local.env";
    private static final List<String> LOCAL_ENV_TEMPLATE = List.of(
            "# StockBucks 本機環境變數。這個檔案只放在自己的電腦，不要提交到 Git。",
            "# 空白代表尚未設定；Debug 介面會用這些欄位判斷缺少哪些 API Key。",
            "# 本機 AI key 只給本機/openai-compatible 服務使用；Ollama 本身不需要真正的 API Key。",
            "",
            "# === AI 預設選擇 ===",
            "AI_PROVIDER=gemini",
            "AI_MODEL=gemini-2.5-flash",
            "AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta",
            "",
            "# === 付費/雲端 AI API Key ===",
            "OPENAI_API_KEY=",
            "GEMINI_API_KEY=",
            "GOOGLE_API_KEY=",
            "ANTHROPIC_API_KEY=",
            "OPENROUTER_API_KEY=",
            "",
            "# === OpenAI-Compatible 自訂端點 ===",
            "AI_API_KEY=",
            "LOCAL_AI_API_KEY=",
            "AI_COMPATIBLE_BASE_URL=",
            "AI_COMPATIBLE_MODEL=",
            "OPENAI_COMPATIBLE_BASE_URL=",
            "OPENAI_COMPATIBLE_MODEL=",
            "",
            "# === Ollama 本機 AI ===",
            "OLLAMA_BASE_URL=http://localhost:11434",
            "OLLAMA_MODEL=stockbucks-traditional-zh:latest",
            "OLLAMA_EXE_PATH=",
            "OLLAMA_MODELS=",
            "",
            "# === 股票來源優先順序 ===",
            "STOCK_PROVIDER_CHAIN=broker,fugle,web,twse,tpex,finmind,local",
            "STOCK_INTRADAY_PROVIDER_CHAIN=broker,web,fugle,twse,tpex,finmind,local",
            "STOCK_HISTORY_PROVIDER_CHAIN=twse,tpex,web,finmind,local",
            "",
            "# === 股票快取，加快報價、歷史資料與盤中 K 線讀取 ===",
            "STOCK_CACHE_ENABLED=true",
            "STOCK_CACHE_DIR=data/api_cache",
            "STOCK_CACHE_QUOTE_TTL_SECONDS=30",
            "STOCK_CACHE_INTRADAY_TTL_SECONDS=300",
            "STOCK_CACHE_HISTORY_TTL_SECONDS=86400",
            "",
            "# === FinMind 股票 API ===",
            "FINMIND_TOKEN=",
            "FINMIND_SNAPSHOT_URL=https://api.finmindtrade.com/api/v4/taiwan_stock_tick_snapshot",
            "FINMIND_BASE_URL=https://api.finmindtrade.com/api/v4/data",
            "",
            "# === Fugle 股票 API ===",
            "FUGLE_API_KEY=",
            "FUGLE_BASE_URL=https://api.fugle.tw/marketdata/v1.0/stock",
            "",
            "# === 券商 API。沒有券商端點時可先留空。 ===",
            "BROKER_BASE_URL=",
            "BROKER_API_KEY=",
            "BROKER_ACCOUNT=",
            "BROKER_USERNAME=",
            "BROKER_PASSWORD=",
            "BROKER_AUTH_TOKEN=",
            "BROKER_LOGIN_ENDPOINT=",
            "BROKER_QUOTE_ENDPOINT=",
            "BROKER_INTRADAY_BARS_ENDPOINT=",
            "BROKER_ACCOUNT_ENDPOINT=",
            "BROKER_POSITIONS_ENDPOINT=",
            "",
            "# === TWSE / TPEx / Web 資料來源 ===",
            "TWSE_WEB_BASE_URL=https://www.twse.com.tw",
            "TWSE_OPENAPI_BASE_URL=https://openapi.twse.com.tw/v1",
            "TPEX_OPENAPI_BASE_URL=https://www.tpex.org.tw/openapi/v1",
            "WEB_STOCK_SOURCES=google,yahoo,cnbc,msn,wantgoo",
            "WEB_STOCK_GOOGLE_URL_TEMPLATE=https://www.google.com/finance/quote/%s:TPE?hl=zh-TW",
            "WEB_STOCK_YAHOO_URL_TEMPLATE=https://tw.stock.yahoo.com/quote/%s.TW",
            "WEB_STOCK_YAHOO_CHART_URL_TEMPLATE=https://query1.finance.yahoo.com/v8/finance/chart/%s.TW?period1=%d&period2=%d&interval=1d",
            "WEB_STOCK_YAHOO_INTRADAY_URL_TEMPLATE=https://query1.finance.yahoo.com/v8/finance/chart/%s.TW?period1=%d&period2=%d&interval=%s",
            "WEB_STOCK_CNBC_URL_TEMPLATE=https://www.cnbc.com/quotes/%s.TW",
            "WEB_STOCK_MSN_URL_TEMPLATE=",
            "WEB_STOCK_WANTGOO_URL_TEMPLATE=https://www.wantgoo.com/stock/%s",
            "WEB_STOCK_USER_AGENT=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36 StockBucks/1.0",
            "",
            "# === 本地資料備援 ===",
            "LOCAL_STOCK_DATA_DIR=data",
            "LOCAL_STOCK_CSV_NAME=TestDataTSMC"
    );

    private static final Map<String, String> DOTENV = loadDotEnv();

    private EnvironmentConfig() {
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String defaultValue) {
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
        ensureLocalEnvTemplate();
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
                // 個別 env 檔案讀不到時略過，仍可使用其他來源的設定。
            }
        }
        return values;
    }

    private static void ensureLocalEnvTemplate() {
        Path projectRoot = findProjectRoot(Path.of(".").toAbsolutePath().normalize());
        Path baseDir = projectRoot == null ? Path.of(".").toAbsolutePath().normalize() : projectRoot;
        Path localEnv = baseDir.resolve(LOCAL_ENV_FILE).normalize();

        try {
            if (!Files.exists(localEnv)) {
                Files.write(localEnv, LOCAL_ENV_TEMPLATE, StandardCharsets.UTF_8);
                return;
            }

            List<String> currentLines = Files.readAllLines(localEnv, StandardCharsets.UTF_8);
            Set<String> currentKeys = collectKeys(currentLines);
            List<String> missingLines = new ArrayList<>();
            for (String templateLine : LOCAL_ENV_TEMPLATE) {
                String key = keyOf(templateLine);
                if (key != null && !currentKeys.contains(key)) {
                    missingLines.add(templateLine);
                }
            }

            if (!missingLines.isEmpty()) {
                List<String> updatedLines = new ArrayList<>(currentLines);
                updatedLines.add("");
                updatedLines.add("# === StockBucks 自動補齊缺少的環境欄位 ===");
                updatedLines.addAll(missingLines);
                Files.write(localEnv, updatedLines, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // 沒有寫入權限時不阻止程式啟動；Debug 介面仍會顯示缺少哪些設定。
        }
    }

    private static Set<String> collectKeys(List<String> lines) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : lines) {
            String key = keyOf(line);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String keyOf(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return null;
        }

        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
            return null;
        }
        return trimmed.substring(0, separator).trim();
    }

    private static List<Path> candidatePaths() {
        String userHome = System.getProperty("user.home", ".");
        Set<Path> paths = new LinkedHashSet<>();
        addLocalEnvPaths(paths, Path.of(".").toAbsolutePath().normalize());

        Path projectRoot = findProjectRoot(Path.of(".").toAbsolutePath().normalize());
        if (projectRoot != null) {
            addLocalEnvPaths(paths, projectRoot);
        }

        paths.add(Path.of(userHome, ".stockbucks", ".env"));
        return new ArrayList<>(paths);
    }

    private static void addLocalEnvPaths(Set<Path> paths, Path baseDir) {
        paths.add(baseDir.resolve(".env").normalize());
        paths.add(baseDir.resolve(LOCAL_ENV_FILE).normalize());
        paths.add(baseDir.resolve("stockbucks.env").normalize());
    }

    private static Path findProjectRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static void parseLine(String line, Map<String, String> values) {
        String key = keyOf(line);
        if (key == null) {
            return;
        }

        String trimmed = line.trim();
        int separator = trimmed.indexOf('=');
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
