package com.jotlog.auth;

import com.jotlog.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与校验。
 *
 * HS256 要求密钥至少 256 bit。配置里的 {@code jotlog.auth.jwt-secret}
 * 如果太短或被留空，这里会随机生成一个并在日志里说清楚后果 ——
 * 宁可让登录态跨不了重启，也不能用一个弱密钥签发可以被伪造的票。
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /** JWT 里放用户 id 的 claim 名。 */
    public static final String CLAIM_USER = "uid";
    /** 会话 id，对应 auth_sessions.token_id。登出就是删那一行。 */
    public static final String CLAIM_JTI = "jti";

    private final AuthProperties props;
    private final SecretKey key;
    private final boolean ephemeralSecret;

    public JwtUtil(AuthProperties props) {
        this.props = props;
        String secret = props.getJwtSecret();
        if (secret.length() < 32) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            this.ephemeralSecret = true;
            log.warn("jotlog.auth.jwt-secret 未配置或少于 32 字符，已随机生成一个。" +
                    "后果：应用重启后所有登录态失效，需要重新登录。" +
                    "想让登录态跨重启存活，请在 config/application-local.yml 里配一个至少 32 字符的固定密钥。");
        } else {
            this.ephemeralSecret = false;
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isEphemeralSecret() {
        return ephemeralSecret;
    }

    /** 签一张票。ttl 由配置决定。 */
    public String create(long userId, String jti) {
        return create(userId, jti, props.getJwtTtlHours() * 3600L * 1000L);
    }

    public String create(long userId, String jti, long ttlMillis) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .claims(Map.of(CLAIM_USER, userId, CLAIM_JTI, jti))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key);
        return builder.compact();
    }

    /**
     * 验签并取出 claims。
     *
     * @throws AuthException 签名不对、过期、格式错误都归成"未授权"，
     *                       不区分具体原因 —— 分太细等于告诉攻击者哪儿错了。
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            throw AuthException.unauthorized("未登录");
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw AuthException.unauthorized("登录已失效，请重新登录");
        }
    }

    public long ttlMillis() {
        return props.getJwtTtlHours() * 3600L * 1000L;
    }
}
