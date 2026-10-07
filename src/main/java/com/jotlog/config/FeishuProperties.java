package com.jotlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书通道配置。
 *
 * 对应 config.example.toml 的 [channel.feishu] 段。
 * app-secret 只能从环境变量读，不要写进配置文件。
 */
@ConfigurationProperties(prefix = "jotlog.channel.feishu")
public class FeishuProperties {

    /** 关闭后不建立长连接，本地无凭证也能启动。 */
    private boolean enabled = false;

    private String appId = "";

    private String appSecret = "";

    /** 群聊里机器人是否只响应 @ 自己的消息。 */
    private boolean mentionOnly = true;

    /** 回执文案，为空则使用默认。 */
    private String receiptText = "已记下，稍后补全";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public boolean isMentionOnly() {
        return mentionOnly;
    }

    public void setMentionOnly(boolean mentionOnly) {
        this.mentionOnly = mentionOnly;
    }

    public String getReceiptText() {
        return receiptText;
    }

    public void setReceiptText(String receiptText) {
        this.receiptText = receiptText;
    }
}
