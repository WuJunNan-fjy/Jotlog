package com.jotlog.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 登录会话表。
 *
 * JWT 本身无状态，签出去就收不回来。这张表是"吊销列表"的反面 ——
 * 它存的是【还活着】的票，查不到就是已登出或已过期。
 *
 * 选白名单而不是黑名单，是因为黑名单会无限增长，而白名单可以随过期清理。
 */
@Repository
public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(long userId, String tokenId, String userAgent, String ip, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO auth_sessions (user_id, token_id, user_agent, ip, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId, tokenId, truncate(userAgent, 255), truncate(ip, 64),
                Timestamp.from(expiresAt));
    }

    /** 票还在列表里且没过期，才算登录有效。 */
    public boolean isValid(String tokenId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE token_id = ? AND expires_at > NOW(3)",
                Integer.class, tokenId);
        return n != null && n > 0;
    }

    public void delete(String tokenId) {
        jdbc.update("DELETE FROM auth_sessions WHERE token_id = ?", tokenId);
    }

    /** 改密码后调这个：把所有设备的登录态踢掉。 */
    public int deleteByUser(long userId) {
        return jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
    }

    public int purgeExpired() {
        int n = jdbc.update("DELETE FROM auth_sessions WHERE expires_at < NOW(3)");
        if (n > 0) {
            log.debug("清理过期会话 {} 条", n);
        }
        return n;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
