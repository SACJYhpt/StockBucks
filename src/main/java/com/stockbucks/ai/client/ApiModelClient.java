package com.stockbucks.ai.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiModelClient implements ModelClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String model;

    public ApiModelClient() {
        this.apiKey = System.getenv("OPENAI_API_KEY");
        this.model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4.1-mini");
    }

    @Override
    public String ask(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            return "[AI 錯誤] 找不到 OPENAI_API_KEY 環境變數";
        }

        String body = """
                {
                  "model": %s,
                  "input": %s
                }
                """.formatted(toJsonString(model), toJsonString(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "[AI API 失敗] HTTP " + response.statusCode() + "\n" + response.body();
            }

            return extractText(response.body());
        } catch (IOException | InterruptedException e) {
            return "[AI API 例外] " + e.getMessage();
        }
    }

    private String toJsonString(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private String extractText(String json) {
        Pattern outputTextPattern = Pattern.compile("\"output_text\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher outputMatcher = outputTextPattern.matcher(json);
        if (outputMatcher.find()) {
            return unescape(outputMatcher.group(1));
        }

        Pattern textPattern = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher textMatcher = textPattern.matcher(json);
        if (textMatcher.find()) {
            return unescape(textMatcher.group(1));
        }

        return json;
    }

    private String unescape(String text) {
        return text
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}