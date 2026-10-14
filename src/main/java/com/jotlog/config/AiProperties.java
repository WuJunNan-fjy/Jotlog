package com.jotlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 增强配置。
 *
 * 真值只走 application-local/prod.yml 或环境变量，application.yml 里只有占位符。
 * enabled=false 或 api-key 为空时，enrich 包的 Bean 全部不创建，
 * 条目安静停在 pending，Jotlog 不依赖 AI 也完全可用。
 */
@ConfigurationProperties(prefix = "jotlog.ai")
public class AiProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    /** 纯文本 note 是否也调 AI。默认关：省钱，也不污染原话。网页端可改（user_settings 覆盖）。 */
    private boolean polishNotes = false;
    private int fetchTimeoutSeconds = 8;

    /** 配置是否足以真正发起调用：开关打开且 key 非空。 */
    public boolean isReady() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isPolishNotes() {
        return polishNotes;
    }

    public void setPolishNotes(boolean polishNotes) {
        this.polishNotes = polishNotes;
    }

    public int getFetchTimeoutSeconds() {
        return fetchTimeoutSeconds;
    }

    public void setFetchTimeoutSeconds(int fetchTimeoutSeconds) {
        this.fetchTimeoutSeconds = fetchTimeoutSeconds;
    }
}
