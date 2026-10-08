package com.jotlog.auth;

import com.jotlog.config.AuthProperties;
import com.jotlog.config.MailProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 验证码邮件。
 *
 * 用 ObjectProvider 而不是直接注入 JavaMailSender，是因为
 * Spring 只在配了 {@code spring.mail.host} 时才创建这个 bean。
 * 直接注入会让"没配邮件"变成启动失败 —— 那太脆弱了，
 * 一个随手记不应该因为 SMTP 没配就起不来。
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final MailProperties mailProps;
    private final AuthProperties authProps;
    private final String mailUsername;
    private final String mailHost;

    public EmailService(ObjectProvider<JavaMailSender> senderProvider,
                        MailProperties mailProps,
                        AuthProperties authProps,
                        @Value("${spring.mail.username:}") String mailUsername,
                        @Value("${spring.mail.host:}") String mailHost) {
        this.senderProvider = senderProvider;
        this.mailProps = mailProps;
        this.authProps = authProps;
        this.mailUsername = mailUsername == null ? "" : mailUsername;
        this.mailHost = mailHost == null ? "" : mailHost.trim();
    }

    /**
     * SMTP 是否真的配好了。
     *
     * 光看 JavaMailSender 这个 bean 在不在是不够的：spring.mail.host 配成
     * 空串时 Spring 照样创建 bean，但一发信就炸。所以连 host 一起看。
     */
    public boolean isMailConfigured() {
        return senderProvider.getIfAvailable() != null && !mailHost.isBlank();
    }

    /**
     * 发验证码。
     *
     * @return true 真的发出去了；false 走了 dev 兜底（只写日志）
     */
    public boolean sendVerifyCode(String toEmail, String code) {
        JavaMailSender sender = senderProvider.getIfAvailable();

        if (!isMailConfigured()) {
            if (authProps.isDevCodeToLog()) {
                log.warn("[DEV] 未配置 SMTP，验证码只写日志：{} -> {}", toEmail, code);
                return false;
            }
            throw AuthException.serverError(
                    "邮件服务未配置，无法发送验证码。请配置 spring.mail.*，" +
                    "或把 jotlog.auth.dev-code-to-log 设为 true 让验证码只打到日志里（仅开发环境）");
        }

        String from = mailProps.getFrom().isBlank() ? mailUsername : mailProps.getFrom();
        if (from.isBlank()) {
            throw AuthException.serverError("发件人为空，请配置 jotlog.mail.from 或 spring.mail.username");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            // 第二个参数 true = multipart。验证码邮件要同时给 HTML 和纯文本两个版本：
            // 只发 HTML 的话，部分邮件客户端（以及一些手机自带邮箱 App）会把
            // 整段标签当正文显示出来 —— 用户看到的就是一屏源码。
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, mailProps.getPersonal());
            helper.setTo(toEmail);
            helper.setSubject("Jotlog 登录验证码");
            helper.setText(plainContent(code), htmlContent(code));
            sender.send(message);
            log.info("验证码邮件已发送");
            return true;
        } catch (Exception e) {
            // 邮件发送失败的细节不暴露给前端，只留日志
            log.error("发送验证码邮件失败 to={} ex={}", toEmail, e.getMessage());
            throw AuthException.serverError("验证码邮件发送失败，请检查 SMTP 配置");
        }
    }

    /** 纯文本版本。不支持 HTML 的客户端看这个。 */
    private String plainContent(String code) {
        int minutes = authProps.getCodeTtlMinutes();
        return "Jotlog 登录验证\n"
                + "\n"
                + "有人正在登录你的 Jotlog。如果这不是你，请忽略这封邮件，并尽快修改密码。\n"
                + "\n"
                + "验证码：" + code + "\n"
                + "（" + minutes + " 分钟内有效）\n"
                + "\n"
                + "此邮件由系统自动发送，请勿回复。\n";
    }

    private String htmlContent(String code) {
        int minutes = authProps.getCodeTtlMinutes();
        return "<div style=\"font-family:'PingFang SC','Microsoft YaHei',sans-serif;"
                + "max-width:520px;margin:0 auto;padding:32px;color:#1f2328\">"
                + "<h2 style=\"margin:0 0 16px;font-size:20px;font-weight:500\">Jotlog 登录验证</h2>"
                + "<p style=\"margin:0 0 20px;font-size:14px;line-height:1.7;color:#57606a\">"
                + "有人正在登录你的 Jotlog。如果这不是你，请忽略这封邮件，并尽快修改密码。</p>"
                + "<div style=\"background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;"
                + "padding:20px;text-align:center;margin:24px 0\">"
                + "<div style=\"font-size:32px;letter-spacing:8px;font-family:'Courier New',monospace;"
                + "font-weight:600\">" + code + "</div>"
                + "<div style=\"margin-top:10px;font-size:12px;color:#8c959f\">" + minutes + " 分钟内有效</div>"
                + "</div>"
                + "<p style=\"margin:0;font-size:12px;color:#8c959f\">此邮件由系统自动发送，请勿回复。</p>"
                + "</div>";
    }
}
