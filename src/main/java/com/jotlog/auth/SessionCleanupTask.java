package com.jotlog.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理过期会话。
 *
 * 不做的话 auth_sessions 会一直涨 —— 每次登录一行，登出才删一行，
 * 而大多数人从来不点登出，都是等 token 自然过期。
 */
@Component
public class SessionCleanupTask {

    private final SessionRepository sessions;

    public SessionCleanupTask(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /** 每小时清一次。表小，代价可以忽略。 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purge() {
        sessions.purgeExpired();
    }
}
