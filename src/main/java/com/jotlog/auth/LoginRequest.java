package com.jotlog.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参：用户名 + 密码 + 邮箱验证码。
 *
 * 三要素而不是两要素，是因为这是一个要部署到公网的自托管服务。
 * 密码可能因为别处泄露而被猜到，验证码保证拿到密码的人也进不来。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        @NotBlank(message = "验证码不能为空") String code
) {
}
