package com.stockbucks.api.ai;

/**
 * AI 文字模型的最小介面。
 *
 * 只保留 ask，讓 AIHub 不需要知道背後是 OpenAI、Gemini、Ollama 或其他服務。
 */
public interface ModelClient {
    String ask(String prompt);
}
