package com.stockbucks.ai.client;

public class ApiModelClient implements ModelClient {

    @Override
    public String ask(String prompt) {
        return "[API MODEL 回應]\n" + prompt;
    }
}