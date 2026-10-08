package com.jotlog.auth;

import jakarta.validation.constraints.NotBlank;

/** 请求发送验证码。验证码发到该用户在库里登记的邮箱，不接受前端传邮箱。 */
public record SendCodeRequest(
        @NotBlank(message = "用户名不能为空") String username
) {
}
