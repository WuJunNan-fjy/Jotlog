package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcUserSettingsRepository implements UserSettingsRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String get(String key) {
        List<String> v = jdbc.query("SELECT `value` FROM user_settings WHERE `key` = ?",
                (rs, i) -> rs.getString("value"), key);
        return v.isEmpty() ? null : v.get(0);
    }

    @Override
    public boolean getBool(String key, boolean fallback) {
        String v = get(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    @Override
    public void put(String key, String value) {
        jdbc.update("""
                INSERT INTO user_settings (`key`, `value`) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)
                """, key, value);
    }
}
