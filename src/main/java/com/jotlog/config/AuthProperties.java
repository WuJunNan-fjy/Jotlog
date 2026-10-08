package com.jotlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 鉴权配置。
 *
 * 单用户自托管，所以没有"注册"这个配置项 —— 账号在首次启动时
 * 由 {@code jotlog.auth.username / email / password} 自动建出来，
 * 之后密码和邮箱都可以在页面上改，配置文件不再生效。
 *
 * jwt-secret 留空是允许的：启动时随机生成一个，代价是重启后所有人要重新登录。
 * 想让登录态跨重启存活，就在这里填一个至少 32 字符的固定串。
 */
@Component
@ConfigurationProperties(prefix = "jotlog.auth")
public class AuthProperties {

    /** 初始用户名。只在建第一个用户时用。 */
    private String username = "admin";

    /** 初始邮箱。验证码发到这里。 */
    private String email = "";

    /** 初始密码。只在建第一个用户时用一次，之后以 BCrypt 哈希落库。 */
    private String password = "";

    /** JWT 签名密钥。留空则启动时随机生成（重启后登录态失效）。 */
    private String jwtSecret = "";

    /** JWT 有效期（小时）。默认 7 天。 */
    private long jwtTtlHours = 168;

    /** 邮箱验证码有效期（分钟）。 */
    private int codeTtlMinutes = 5;

    /** 两次发送验证码之间的冷却（秒）。挡住"狂点发送"刷爆邮箱。 */
    private int codeCooldownSeconds = 60;

    /** 一个验证码最多能试错几次，超过就锁。 */
    private int maxAttempts = 5;

    /** 试错超限后锁多久（分钟）。 */
    private int lockMinutes = 30;

    /** 启动时是否自动创建初始用户。设为 false 则需要自己插库。 */
    private boolean bootstrap = true;

    /**
     * 开发用：验证码只写日志、不发邮件。
     *
     * 没配 SMTP 又想跑通登录流程时开这个。默认 false。
     * 打开后任何人读到日志就能登录，所以生产环境必须关掉。
     */
    private boolean devCodeToLog = false;

    public String getUsername() {
        return username == null ? "" : username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email == null ? "" : email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password == null ? "" : password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getJwtSecret() {
        return jwtSecret == null ? "" : jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getJwtTtlHours() {
        return jwtTtlHours <= 0 ? 168 : jwtTtlHours;
    }

    public void setJwtTtlHours(long jwtTtlHours) {
        this.jwtTtlHours = jwtTtlHours;
    }

    public int getCodeTtlMinutes() {
        return codeTtlMinutes <= 0 ? 5 : codeTtlMinutes;
    }

    public void setCodeTtlMinutes(int codeTtlMinutes) {
        this.codeTtlMinutes = codeTtlMinutes;
    }

    public int getCodeCooldownSeconds() {
        return codeCooldownSeconds <= 0 ? 60 : codeCooldownSeconds;
    }

    public void setCodeCooldownSeconds(int codeCooldownSeconds) {
        this.codeCooldownSeconds = codeCooldownSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts <= 0 ? 5 : maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getLockMinutes() {
        return lockMinutes <= 0 ? 30 : lockMinutes;
    }

    public void setLockMinutes(int lockMinutes) {
        this.lockMinutes = lockMinutes;
    }

    public boolean isBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(boolean bootstrap) {
        this.bootstrap = bootstrap;
    }

    public boolean isDevCodeToLog() {
        return devCodeToLog;
    }

    public void setDevCodeToLog(boolean devCodeToLog) {
        this.devCodeToLog = devCodeToLog;
    }
}
