package com.jotlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件发件人配置。
 *
 * 账号、密码、SMTP 主机这些走 Spring 自带的 {@code spring.mail.*}，
 * 这里只放两件 Spring 没有的东西：发件人显示名，以及在没配 personal 时
 * 用什么兜底。
 */
@Component
@ConfigurationProperties(prefix = "jotlog.mail")
public class MailProperties {

    /** 显示的发件人名，比如「Jotlog」。 */
    private String personal = "Jotlog";

    /** 发件地址。留空则回落到 spring.mail.username。 */
    private String from = "";

    public String getPersonal() {
        return personal == null || personal.isBlank() ? "Jotlog" : personal;
    }

    public void setPersonal(String personal) {
        this.personal = personal;
    }

    public String getFrom() {
        return from == null ? "" : from;
    }

    public void setFrom(String from) {
        this.from = from;
    }
}
