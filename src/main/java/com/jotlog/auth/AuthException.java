package com.jotlog.auth;

import org.springframework.http.HttpStatus;

/**
 * 鉴权相关异常。
 *
 * 带一个 HTTP 状态，交给全局异常处理器直接转成响应。
 * 业务异常自己带状态码，比在 Controller 里 try-catch 再拼 ResponseEntity 干净。
 */
public class AuthException extends RuntimeException {

    private final HttpStatus status;

    private AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(HttpStatus.UNAUTHORIZED, message);
    }

    public static AuthException forbidden(String message) {
        return new AuthException(HttpStatus.FORBIDDEN, message);
    }

    public static AuthException badRequest(String message) {
        return new AuthException(HttpStatus.BAD_REQUEST, message);
    }

    /** 429：发送太频繁 / 验证码锁死。 */
    public static AuthException tooManyRequests(String message) {
        return new AuthException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static AuthException serverError(String message) {
        return new AuthException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    public HttpStatus status() {
        return status;
    }
}
