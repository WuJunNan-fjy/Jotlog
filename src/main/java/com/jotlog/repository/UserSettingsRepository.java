package com.jotlog.repository;

/**
 * 极简用户偏好键值存储。首版只存 ai_polish_notes 一个键。
 */
public interface UserSettingsRepository {

    /** 不存在返回 null。 */
    String get(String key);

    boolean getBool(String key, boolean fallback);

    void put(String key, String value);
}
