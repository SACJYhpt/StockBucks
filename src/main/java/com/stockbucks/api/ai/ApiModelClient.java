package com.stockbucks.api.ai;

import com.stockbucks.api.config.EnvironmentConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多 AI 供應商的統一呼叫器。
 *
 * 目前支援 OpenAI、OpenRouter、OpenAI-compatible、Anthropic、Gemini、Ollama。
 * 供應商、模型、API key 都從 EnvironmentConfig 讀取，避免把金鑰寫死在程式碼。
 */
public class ApiModelClient implements ModelClient {
    private final HttpClient client; // Java 標準 HTTP client，不額外引入套件。
    private final String provider; // AI_PROVIDER，例如 gemini、anthropic、ollama。
    private final String apiKey; // 依供應商解析出的 key；Ollama 可為空。
    private final String baseUrl; // 供應商 API base URL。
    private final String model; // 供應商模型名稱。

    public ApiModelClient() {
        this(EnvironmentConfig.first("openai", "AI_PROVIDER", "STOCKBUCKS_AI_PROVIDER"));
    }

    public ApiModelClient(String provider) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.provider = normalizeProvider(provider)
                .trim()
                .toLowerCase(Locale.ROOT);
        this.apiKey = resolveApiKey(this.provider);
        this.baseUrl = resolveBaseUrl(this.provider);
        this.model = resolveModel(this.provider);
    }

    @Override
    public String ask(String prompt) {
        String safePrompt = enforceTraditionalChinese(prompt == null ? "" : prompt);
        // 不同供應商的 request body 格式不同，因此在這裡分流。
        String answer = switch (provider) {
            case "anthropic", "claude" -> askAnthropic(safePrompt);
            case "gemini", "google" -> askGemini(safePrompt);
            case "ollama", "local" -> askOllama(safePrompt);
            case "openrouter" -> askChatCompletions(safePrompt, true);
            case "openai-compatible", "compatible", "chat-completions" -> askChatCompletions(safePrompt, false);
            case "openai" -> askOpenAiResponses(safePrompt);
            default -> "[AI 設定] 不支援的 AI_PROVIDER：" + provider
                    + "。請使用 openai、openrouter、openai-compatible、anthropic、gemini 或 ollama。";
        };
        return normalizeTraditionalChinese(answer);
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isReady() {
        return getMissingApiKeyName().isBlank();
    }

    public String getMissingApiKeyName() {
        // UI 可以用這個方法直接提示使用者缺少哪個環境變數。
        return switch (provider) {
            case "ollama", "local" -> "";
            case "anthropic", "claude" -> apiKey.isBlank() ? "ANTHROPIC_API_KEY" : "";
            case "gemini", "google" -> apiKey.isBlank() ? "GEMINI_API_KEY" : "";
            case "openrouter" -> apiKey.isBlank() ? "OPENROUTER_API_KEY" : "";
            case "openai-compatible", "compatible", "chat-completions" -> apiKey.isBlank() ? "AI_API_KEY" : "";
            default -> apiKey.isBlank() ? "OPENAI_API_KEY" : "";
        };
    }

    public String getConfigurationStatus() {
        String missing = getMissingApiKeyName();
        if (missing.isBlank()) {
            return "AI provider: " + provider + " | model: " + model + " | ready";
        }
        return "AI provider: " + provider + " | missing: " + missing;
    }

    public String getShortConfigurationStatus() {
        String missing = getMissingApiKeyName();
        String state = missing.isBlank() ? "可用" : "缺 " + missing;
        return provider + "：" + state + " | " + model; // DEBUG UI 用一行顯示每個 AI 方案。
    }

    private String askOpenAiResponses(String prompt) {
        if (apiKey.isBlank()) {
            return missingKey("OPENAI_API_KEY");
        }

        String body = """
                {
                  "model": %s,
                  "input": %s
                }
                """.formatted(toJsonString(model), toJsonString(prompt));

        HttpRequest request = requestBuilder(baseUrl + "/responses")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return sendAndExtract(request, this::extractOpenAiText);
    }

    private String askChatCompletions(String prompt, boolean openRouterHeaders) {
        if (apiKey.isBlank()) {
            return missingKey(openRouterHeaders ? "OPENROUTER_API_KEY" : "AI_API_KEY");
        }

        String body = """
                {
                  "model": %s,
                  "messages": [
                    {
                      "role": "user",
                      "content": %s
                    }
                  ]
                }
                """.formatted(toJsonString(model), toJsonString(prompt));

        HttpRequest.Builder builder = requestBuilder(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey);
        if (openRouterHeaders) {
            builder.header("HTTP-Referer", "https://stockbucks.local")
                    .header("X-Title", "StockBucks");
        }

        return sendAndExtract(
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                this::extractChatCompletionText
        );
    }

    private String askAnthropic(String prompt) {
        if (apiKey.isBlank()) {
            return missingKey("ANTHROPIC_API_KEY");
        }

        String body = """
                {
                  "model": %s,
                  "max_tokens": 1024,
                  "messages": [
                    {
                      "role": "user",
                      "content": %s
                    }
                  ]
                }
                """.formatted(toJsonString(model), toJsonString(prompt));

        HttpRequest request = requestBuilder(baseUrl + "/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", EnvironmentConfig.first("2023-06-01", "ANTHROPIC_VERSION", "STOCKBUCKS_ANTHROPIC_VERSION"))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return sendAndExtract(request, this::extractAnthropicText);
    }

    private String askGemini(String prompt) {
        if (apiKey.isBlank()) {
            return missingKey("GEMINI_API_KEY");
        }

        String body = """
                {
                  "contents": [
                    {
                      "parts": [
                        {
                          "text": %s
                        }
                      ]
                    }
                  ]
                }
                """.formatted(toJsonString(prompt));

        String modelPath = model.startsWith("models/") ? model : "models/" + model;
        HttpRequest request = requestBuilder(baseUrl + "/" + modelPath + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return sendAndExtract(request, this::extractGeminiText);
    }

    private String askOllama(String prompt) {
        ensureOllamaServerRunning(); // 本機服務未啟動時，嘗試用專案旁邊的 Ollama 工具自動開起來。

        String body = """
                {
                  "model": %s,
                  "prompt": %s,
                  "stream": false
                }
                """.formatted(toJsonString(model), toJsonString(prompt));

        HttpRequest request = requestBuilder(baseUrl + "/api/generate")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return sendAndExtract(request, json -> firstJsonString(json, "response"));
    }

    private void ensureOllamaServerRunning() {
        if (!provider.equals("ollama") && !provider.equals("local")) {
            return;
        }
        if (isOllamaServerAlive()) {
            return;
        }

        Path ollamaExe = resolveOllamaExecutable();
        if (!Files.isRegularFile(ollamaExe)) {
            return;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(ollamaExe.toString(), "serve");
            Path modelsPath = resolveOllamaModelsPath();
            if (Files.isDirectory(modelsPath)) {
                builder.environment().put("OLLAMA_MODELS", modelsPath.toString());
            }
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start();

            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                if (isOllamaServerAlive()) {
                    return;
                }
                Thread.sleep(250);
            }
        } catch (IOException ignored) {
            // 啟動失敗時保留原本 HTTP 檢查結果，讓 UI 顯示可讀錯誤。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isOllamaServerAlive() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private HttpRequest.Builder requestBuilder(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(uri)))
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "StockBucks/1.0");
    }

    private String sendAndExtract(HttpRequest request, TextExtractor extractor) {
        // 統一處理 HTTP 錯誤與中斷，避免每個供應商重複寫例外處理。
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "[AI API error] HTTP " + response.statusCode() + " " + summarizeResponseBody(response.body());
            }
            String text = extractor.extract(response.body());
            return text == null || text.isBlank() ? summarizeResponseBody(response.body()) : text;
        } catch (IOException e) {
            return "[AI API request failed] " + readableIOException(request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[AI API request interrupted] " + e.getMessage();
        }
    }

    private String resolveApiKey(String provider) {
        // 每家供應商慣用的 key 名稱不同，這裡集中處理。
        return switch (provider) {
            case "anthropic", "claude" -> EnvironmentConfig.first("", "ANTHROPIC_API_KEY", "STOCKBUCKS_ANTHROPIC_API_KEY", "AI_API_KEY");
            case "gemini", "google" -> EnvironmentConfig.first("", "GEMINI_API_KEY", "GOOGLE_API_KEY", "STOCKBUCKS_GEMINI_API_KEY", "AI_API_KEY");
            case "openrouter" -> EnvironmentConfig.first("", "OPENROUTER_API_KEY", "STOCKBUCKS_OPENROUTER_API_KEY", "AI_API_KEY");
            case "ollama", "local" -> "";
            case "openai-compatible", "compatible", "chat-completions" -> EnvironmentConfig.first("", "AI_API_KEY", "STOCKBUCKS_AI_API_KEY");
            default -> EnvironmentConfig.first("", "OPENAI_API_KEY", "STOCKBUCKS_OPENAI_API_KEY", "AI_API_KEY");
        };
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "openai" : provider;
    }

    private String resolveBaseUrl(String provider) {
        // 預設值可直接使用；也可以用 AI_BASE_URL 覆蓋。
        if (provider.equals("ollama") || provider.equals("local")) {
            return trimTrailingSlash(EnvironmentConfig.first(
                    "http://localhost:11434",
                    "OLLAMA_BASE_URL",
                    "STOCKBUCKS_OLLAMA_BASE_URL"
            ));
        }

        String defaultUrl = switch (provider) {
            case "anthropic", "claude" -> "https://api.anthropic.com/v1";
            case "gemini", "google" -> "https://generativelanguage.googleapis.com/v1beta";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "openai-compatible", "compatible", "chat-completions" -> "http://localhost:8000/v1";
            default -> "https://api.openai.com/v1";
        };
        return trimTrailingSlash(EnvironmentConfig.first(defaultUrl, "AI_BASE_URL", "STOCKBUCKS_AI_BASE_URL"));
    }

    private String resolveModel(String provider) {
        // 每個供應商提供一個可用預設模型；正式部署可由 AI_MODEL 覆蓋。
        if (provider.equals("ollama") || provider.equals("local")) {
            return EnvironmentConfig.first(
                    "stockbucks-traditional-zh:latest",
                    "OLLAMA_MODEL",
                    "STOCKBUCKS_OLLAMA_MODEL"
            );
        }

        String defaultModel = switch (provider) {
            case "anthropic", "claude" -> "claude-sonnet-4-5-20250929";
            case "gemini", "google" -> "gemini-2.5-flash";
            case "openrouter" -> "anthropic/claude-sonnet-4.5";
            case "openai-compatible", "compatible", "chat-completions" -> "local-model";
            default -> "gpt-4.1-mini";
        };
        return EnvironmentConfig.first(defaultModel, "AI_MODEL", "STOCKBUCKS_AI_MODEL");
    }

    private String missingKey(String keyName) {
        return "[AI 設定] 缺少 " + keyName
                + "。請設定在作業系統環境變數、專案 .env、stockbucks.env 或 ~/.stockbucks/.env。";
    }

    private String enforceTraditionalChinese(String prompt) {
        return """
                請遵守以下輸出規則：
                1. 一律使用繁體中文，並盡量使用台灣常見用語。
                2. 不要使用簡體字。
                3. 英文專有名詞、API 名稱、股票代號與程式碼可以保留原文。
                4. 不要使用 Markdown 粗體符號，不要輸出 **文字** 這種格式。
                5. 回答保持精簡，每次優先給 3 到 5 個重點。

                使用者內容：
                %s
                """.formatted(prompt);
    }

    private String normalizeTraditionalChinese(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 本機小模型偶爾會混入簡體常用字；這裡先做 UI 顯示前的保守修正。
        String normalized = text
                .replace("投资", "投資")
                .replace("股票", "股票")
                .replace("市场", "市場")
                .replace("数据", "資料")
                .replace("资讯", "資訊")
                .replace("获利", "獲利")
                .replace("收益", "收益")
                .replace("风险", "風險")
                .replace("策略", "策略")
                .replace("技术", "技術")
                .replace("分析", "分析")
                .replace("趋势", "趨勢")
                .replace("预测", "預測")
                .replace("价格", "價格")
                .replace("买入", "買入")
                .replace("卖出", "賣出")
                .replace("持有", "持有")
                .replace("财务", "財務")
                .replace("报表", "報表")
                .replace("成长", "成長")
                .replace("长期", "長期")
                .replace("短期", "短期")
                .replace("个股", "個股")
                .replace("金额", "金額")
                .replace("帐号", "帳號")
                .replace("账户", "帳戶")
                .replace("环境", "環境")
                .replace("变量", "變數")
                .replace("设置", "設定")
                .replace("缺少", "缺少")
                .replace("无法", "無法")
                .replace("启用", "啟用")
                .replace("响应", "回應")
                .replace("来源", "來源")
                .replace("实时", "即時")
                .replace("历史", "歷史")
                .replace("台湾", "台灣")
                .replace("证券", "證券")
                .replace("券商", "券商")
                .replace("监控", "監控")
                .replace("总结", "總結")
                .replace("重点", "重點")
                .replace("用户", "使用者")
                .replace("问题", "問題")
                .replace("回复", "回覆")
                .replace("默认", "預設")
                .replace("运行", "執行")
                .replace("检测", "檢測")
                .replace("状态", "狀態")
                .replace("成功", "成功");
        return removeMarkdownMarks(normalized);
    }

    private String removeMarkdownMarks(String text) {
        return text
                .replace("**", "")
                .replace("__", "")
                .replace("###", "")
                .replace("##", "")
                .replace("#", "")
                .replace("`", "")
                .replace("> ", "");
    }

    private String extractOpenAiText(String json) {
        String outputText = firstJsonString(json, "output_text");
        return outputText.isBlank() ? firstJsonString(json, "text") : outputText;
    }

    private String extractChatCompletionText(String json) {
        return firstJsonString(json, "content");
    }

    private String extractAnthropicText(String json) {
        return firstJsonString(json, "text");
    }

    private String extractGeminiText(String json) {
        return firstJsonString(json, "text");
    }

    private String firstJsonString(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : "";
    }

    private String toJsonString(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private String unescape(String text) {
        return text
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private Path resolveOllamaExecutable() {
        String configured = EnvironmentConfig.first("", "OLLAMA_EXE_PATH", "STOCKBUCKS_OLLAMA_EXE_PATH");
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("..", "ai", "tools", "ollama", "ollama.exe").toAbsolutePath().normalize();
    }

    private Path resolveOllamaModelsPath() {
        String configured = EnvironmentConfig.first("", "OLLAMA_MODELS", "STOCKBUCKS_OLLAMA_MODELS");
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("..", "ai", "data", "ollama_models").toAbsolutePath().normalize();
    }

    private String readableIOException(HttpRequest request, IOException e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }

        String uri = request == null || request.uri() == null ? "" : request.uri().toString();
        if (uri.contains("localhost") || uri.contains("127.0.0.1")) {
            return e.getClass().getSimpleName() + ": 無法連線到 " + uri + "，請確認本機 AI 服務是否已啟動。";
        }
        return e.getClass().getSimpleName();
    }

    private String summarizeResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        String cleaned = body
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) + "..." : cleaned;
    }

    private interface TextExtractor {
        String extract(String json);
    }
}
