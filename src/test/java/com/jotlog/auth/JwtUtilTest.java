package com.jotlog.auth;

import com.jotlog.config.AuthProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 签发与校验。
 *
 * 不碰 Spring 上下文，也不需要数据库 —— JwtUtil 是个纯对象，
 * 这样这个测试在任何环境下都能跑，包括没配数据库的 CI。
 */
class JwtUtilTest {

    /** HS256 要求密钥至少 256 bit，短了会被 jjwt 直接拒绝。 */
    private static final String SECRET = "jotlog-test-secret-key-0123456789abcdef";

    private JwtUtil jwt() {
        AuthProperties props = new AuthProperties();
        props.setJwtSecret(SECRET);
        props.setJwtTtlHours(1);
        return new JwtUtil(props);
    }

    @Test
    void 签发的票能解出用户和会话() {
        JwtUtil jwt = jwt();
        String token = jwt.create(42L, "jti-abc");

        Claims claims = jwt.parse(token);
        assertEquals(42L, ((Number) claims.get(JwtUtil.CLAIM_USER)).longValue());
        assertEquals("jti-abc", claims.get(JwtUtil.CLAIM_JTI, String.class));
        assertNotNull(claims.getExpiration());
    }

    @Test
    void 空票据和伪造票据都会被拒() {
        JwtUtil jwt = jwt();

        assertThrows(AuthException.class, () -> jwt.parse(null));
        assertThrows(AuthException.class, () -> jwt.parse("  "));
        assertThrows(AuthException.class, () -> jwt.parse("not.a.jwt"));
    }

    @Test
    void 另一个密钥签的票验不过() {
        AuthProperties other = new AuthProperties();
        other.setJwtSecret("another-secret-key-abcdefghijklmnopqrst");
        String forged = new JwtUtil(other).create(42L, "jti-abc");

        assertThrows(AuthException.class, () -> jwt().parse(forged));
    }

    @Test
    void 没配密钥时随机生成一个而不是用弱密钥() {
        JwtUtil ephemeral = new JwtUtil(new AuthProperties());
        // 随机的也能正常签发解析，只是重启后失效
        Claims claims = ephemeral.parse(ephemeral.create(1L, "jti-x"));
        assertEquals(1L, ((Number) claims.get(JwtUtil.CLAIM_USER)).longValue());
    }
}
