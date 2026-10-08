package com.jotlog.auth;

import com.jotlog.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 鉴权业务。
 *
 * 校验顺序是刻意设计的：密码 → 验证码。
 * 反过来的话，任何人只要知道用户名就能消耗掉你的验证码，
 * 等于给你制造一个"永远登不上"的拒绝服务。
 *
 * 对外错误消息一律不区分"用户不存在"和"密码错误"，
 * 只说"用户名或密码错误"。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final SessionRepository sessions;
    private final VerifyCodeService codes;
    private final EmailService email;
    private final JwtUtil jwt;
    private final AuthProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository users,
                       SessionRepository sessions,
                       VerifyCodeService codes,
                       EmailService email,
                       JwtUtil jwt,
                       AuthProperties props) {
        this.users = users;
        this.sessions = sessions;
        this.codes = codes;
        this.email = email;
        this.jwt = jwt;
        this.props = props;
    }

    public record UserVO(long id, String username, String email, String nickname, String lastLoginAt) {
    }

    public record LoginResult(String token, long expiresInSeconds, UserVO user) {
    }

    /**
     * 发验证码到用户登记的邮箱。
     *
     * 邮箱不接受前端传，只认库里的 —— 否则这就是一个可以指定任意收件人的
     * 邮件轰炸接口。
     */
    public void sendCode(String username) {
        UserRepository.User user = users.findByUsername(username);
        if (user == null) {
            // 不透露用户是否存在，但也不伪造成功 —— 伪造成功会让攻击者靠耗时差异探测账号。
            // 这里统一走"已发送"，实际没发。自用单用户场景，账号名本来就不是秘密。
            log.warn("请求验证码的用户不存在，已静默忽略: {}", username);
            return;
        }
        String code = codes.issue();
        boolean sent = email.sendVerifyCode(user.email(), code);
        log.info("登录验证码已下发（邮件={}）", sent);
    }

    public LoginResult login(LoginRequest req, String ip, String userAgent) {
        UserRepository.User user = users.findByUsername(req.username());
        if (user == null || !encoder.matches(req.password(), user.passwordHash())) {
            log.warn("登录失败：用户名或密码错误 user={} ip={}", req.username(), ip);
            throw AuthException.unauthorized("用户名或密码错误");
        }

        if (!codes.verify(req.code())) {
            throw AuthException.badRequest("验证码错误或已过期，还可以试 " + codes.remainingAttempts() + " 次");
        }

        String jti = UUID.randomUUID().toString();
        String token = jwt.create(user.id(), jti);
        Instant expiresAt = Instant.now().plusMillis(jwt.ttlMillis());
        sessions.create(user.id(), jti, userAgent, ip, expiresAt);
        users.touchLogin(user.id());

        log.info("登录成功 user={} ip={}", user.username(), ip);
        return new LoginResult(token, jwt.ttlMillis() / 1000,
                toVO(users.findById(user.id())));
    }

    public void logout(long userId, String tokenId) {
        if (tokenId != null && !tokenId.isBlank()) {
            sessions.delete(tokenId);
        }
        log.info("已登出 userId={}", userId);
    }

    /** 登出全部设备。改密码后调用。 */
    public void logoutAll(long userId) {
        int n = sessions.deleteByUser(userId);
        log.info("已登出全部设备 userId={} 会话数={}", userId, n);
    }

    public UserVO me(long userId) {
        UserRepository.User user = users.findById(userId);
        if (user == null) {
            throw AuthException.unauthorized("用户不存在");
        }
        return toVO(user);
    }

    public void changePassword(long userId, ChangePasswordRequest req) {
        UserRepository.User user = users.findById(userId);
        if (user == null) {
            throw AuthException.unauthorized("用户不存在");
        }
        if (!encoder.matches(req.oldPassword(), user.passwordHash())) {
            throw AuthException.badRequest("原密码不正确");
        }
        String hash = encoder.encode(req.newPassword());
        if (hash.equals(user.passwordHash())) {
            throw AuthException.badRequest("新密码不能与原密码相同");
        }
        users.updatePassword(userId, hash);
        // 改密码必须踢掉别的设备，否则泄露的密码对应的旧会话还活着
        logoutAll(userId);
        log.info("密码已修改，所有设备已登出 userId={}", userId);
    }

    /**
     * 换绑邮箱。
     *
     * 验证码是发到【旧邮箱】的，所以这里能证明"改邮箱的人仍然是原来那个人"。
     */
    public void changeEmail(long userId, ChangeEmailRequest req) {
        UserRepository.User user = users.findById(userId);
        if (user == null) {
            throw AuthException.unauthorized("用户不存在");
        }
        if (!codes.verify(req.code())) {
            throw AuthException.badRequest("验证码错误或已过期，还可以试 " + codes.remainingAttempts() + " 次");
        }
        users.updateEmail(userId, req.email());
        log.info("邮箱已换绑 userId={}", userId);
    }

    /** 改昵称。昵称用于界面显示，不是登录凭据。 */
    public void changeNickname(long userId, String nickname) {
        users.updateNickname(userId, nickname == null || nickname.isBlank() ? null : nickname.trim());
    }

    public long codeTtlMinutes() {
        return props.getCodeTtlMinutes();
    }

    /** 给 Controller 用：登出时要靠它从 token 里解出 jti。 */
    public JwtUtil jwt() {
        return jwt;
    }

    private static UserVO toVO(UserRepository.User u) {
        return new UserVO(u.id(), u.username(), u.email(), u.nickname(),
                u.lastLoginAt() == null ? null : u.lastLoginAt().toString());
    }
}
