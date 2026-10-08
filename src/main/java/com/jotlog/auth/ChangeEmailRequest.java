package com.jotlog.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 换绑邮箱。
 *
 * 必须带验证码，且验证码发到【旧邮箱】——
 * 否则拿到会话的人可以把邮箱改成自己的，从此永久接管账号。
 */
public record ChangeEmailRequest(
        @NotBlank(message = "验证码不能为空") String code,
        @NotBlank(message = "新邮箱不能为空")
        @Email(message = "邮箱格式不正确") String email
) {
}
