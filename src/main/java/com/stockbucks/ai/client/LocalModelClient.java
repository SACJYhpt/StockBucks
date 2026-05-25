package com.stockbucks.ai.client;

public class LocalModelClient implements ModelClient {

    @Override
    public String ask(String prompt) {
        return """
                [LOCAL MODEL 模擬回應]
                目前尚未接上本地模型。

                收到的 prompt：
                %s
                """.formatted(prompt);
    }
}