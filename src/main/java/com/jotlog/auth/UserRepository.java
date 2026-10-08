package com.jotlog.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 用户表读写。
 *
 * 单用户部署下这张表永远只有一行，但写法按"可能有多行"来写 ——
 * 检索一律走 username / id，不做 findFirst() 之类的假设。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record User(
            long id,
            String username,
            String email,
            String passwordHash,
            String nickname,
            LocalDateTime lastLoginAt
    ) {
    }

    private static final RowMapper<User> ROW = (ResultSet rs, int i) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("nickname"),
            rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime());

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }

    public User findByUsername(String username) {
        List<User> rows = jdbc.query("SELECT * FROM users WHERE username = ?", ROW, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public User findById(long id) {
        List<User> rows = jdbc.query("SELECT * FROM users WHERE id = ?", ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insert(String username, String email, String passwordHash, String nickname) {
        jdbc.update("""
                INSERT INTO users (username, email, password_hash, nickname)
                VALUES (?, ?, ?, ?)
                """, username, email, passwordHash, nickname);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    public void updateEmail(long id, String email) {
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", email, id);
    }

    public void updateNickname(long id, String nickname) {
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", nickname, id);
    }

    public void touchLogin(long id) {
        jdbc.update("UPDATE users SET last_login_at = NOW(3) WHERE id = ?", id);
    }

    /** 过期时间换算。存的是 DATETIME(3)，MySQL 用 Asia/Shanghai 还是 UTC 取决于连接时区，统一用系统默认时区写。 */
    static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
