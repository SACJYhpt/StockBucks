package com.stockbucks.ai.client;

public class LocalModelClient implements ModelClient {

    @Override
    public String ask(String prompt) {
        return "[LOCAL MODEL 回應]\n" + prompt;
    }
}