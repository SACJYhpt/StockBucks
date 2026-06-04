package com.stockbucks.api.stock;

import com.stockbucks.api.config.EnvironmentConfig;

/**
 * 券商登入與授權資訊。
 *
 * 只從本機設定讀取，不應寫入 git 或傳到我們自己的伺服器。
 */
public class BrokerCredentials {
    private final String provider; // 券商或 adapter 名稱。
    private final String username; // 使用者帳號。
    private final String password; // 使用者密碼。
    private final String apiKey; // API key 授權模式使用。
    private final String authToken; // 已取得 token 時可直接使用。
    private final String certificatePath; // 憑證檔路徑，部分券商需要。
    private final String certificatePassword; // 憑證密碼，必須留在本機。

    public BrokerCredentials(String provider,
                             String username,
                             String password,
                             String apiKey,
                             String authToken,
                             String certificatePath,
                             String certificatePassword) {
        this.provider = provider == null ? "" : provider;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.authToken = authToken == null ? "" : authToken;
        this.certificatePath = certificatePath == null ? "" : certificatePath;
        this.certificatePassword = certificatePassword == null ? "" : certificatePassword;
    }

    public static BrokerCredentials fromEnvironment() {
        // 所有券商敏感資訊都集中從環境設定讀取。
        return new BrokerCredentials(
                EnvironmentConfig.first("generic-http", "BROKER_PROVIDER", "STOCKBUCKS_BROKER_PROVIDER"),
                EnvironmentConfig.first("", "BROKER_USERNAME", "STOCKBUCKS_BROKER_USERNAME"),
                EnvironmentConfig.first("", "BROKER_PASSWORD", "STOCKBUCKS_BROKER_PASSWORD"),
                EnvironmentConfig.first("", "BROKER_API_KEY", "STOCKBUCKS_BROKER_API_KEY"),
                EnvironmentConfig.first("", "BROKER_AUTH_TOKEN", "STOCKBUCKS_BROKER_AUTH_TOKEN"),
                EnvironmentConfig.first("", "BROKER_CERT_PATH", "STOCKBUCKS_BROKER_CERT_PATH"),
                EnvironmentConfig.first("", "BROKER_CERT_PASSWORD", "STOCKBUCKS_BROKER_CERT_PASSWORD")
        );
    }

    public String getProvider() {
        return provider;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getCertificatePath() {
        return certificatePath;
    }

    public String getCertificatePassword() {
        return certificatePassword;
    }
}
