package com.jotlog.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 鉴权接口。
 *
 * 这一组是唯一不需要登录就能访问的 API，其余 /api/** 全部要带 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /**
     * 发送登录验证码。
     *
     * 无论用户是否存在都返回成功，避免被试出账号名 ——
     * 虽然这是单用户自用系统，但接口行为不该靠"反正没人猜"来保证安全。
     */
    @PostMapping("/code")
    public ResponseEntity<?> sendCode(@Valid @RequestBody SendCodeRequest req) {
        auth.sendCode(req.username());
        return ResponseEntity.ok(Map.of(
                "sent", true,
                "ttlMinutes", auth.codeTtlMinutes()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        AuthService.LoginResult result = auth.login(req, clientIp(request), userAgent(request));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        auth.logout(CurrentUser.require(), tokenIdFrom(request));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        return ResponseEntity.ok(auth.me(CurrentUser.require()));
    }

    @PutMapping("/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(CurrentUser.require(), req);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/email")
    public ResponseEntity<?> changeEmail(@Valid @RequestBody ChangeEmailRequest req) {
        auth.changeEmail(CurrentUser.require(), req);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 从 Authorization: Bearer 里取出 jti，用于登出时删会话。
     * 解析失败返回 null，登出接口会退化成"什么都不删"，不会报错。
     */
    private String tokenIdFrom(HttpServletRequest request) {
        String token = bearer(request);
        if (token == null) {
            return null;
        }
        try {
            return auth.jwt().parse(token).get(JwtUtil.CLAIM_JTI, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }

    /**
     * 客户端 IP。
     *
     * 走过 Nginx 之后 RemoteAddr 永远是 127.0.0.1，所以要看 X-Forwarded-For。
     * 但这个头可以伪造，所以只用于日志和展示，绝不用于安全判定。
     */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua;
    }
}
