package com.jotlog.auth;

import com.jotlog.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 邮箱验证码的生命周期。
 *
 * 状态放内存，不放 Redis，也不落库 —— 单实例自用，验证码 5 分钟就过期，
 * 为它引入一个外部依赖不划算。代价是重启后未使用的验证码丢失，
 * 用户重发一次即可，可接受。
 *
 * 四道闸：
 *   1. 发送冷却，防止狂点发送把邮箱刷爆
 *   2. 有效期，验证码不是永久通行证
 *   3. 试错次数，防止暴力枚举 6 位数字
 *   4. 超限锁定，试错到上限直接锁死一段时间
 */
@Service
public class VerifyCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerifyCodeService.class);

    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    private String code;
    private Instant expiresAt;
    private Instant cooldownUntil;
    private int attempts;
    private Instant lockedUntil;

    public VerifyCodeService(AuthProperties props) {
        this.props = props;
    }

    /**
     * 发一个新验证码。
     *
     * @return 6 位验证码，交给调用方发邮件。这里不碰邮件，职责分开。
     */
    public synchronized String issue() {
        Instant now = Instant.now();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw AuthException.tooManyRequests("尝试次数过多，请 " + lockRemainingMinutes() + " 分钟后再试");
        }
        if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
            throw AuthException.tooManyRequests("验证码已发送，请 " + cooldownSeconds() + " 秒后再试");
        }
        String next = String.format("%06d", random.nextInt(1_000_000));
        this.code = next;
        this.expiresAt = now.plusSeconds(props.getCodeTtlMinutes() * 60L);
        this.cooldownUntil = now.plusSeconds(props.getCodeCooldownSeconds());
        this.attempts = 0;
        this.lockedUntil = null;
        log.info("已生成邮箱验证码，有效期 {} 分钟", props.getCodeTtlMinutes());
        return next;
    }

    /**
     * 校验验证码。
     *
     * 失败会累加尝试次数，达到上限就锁。成功会清空全部状态 ——
     * 一个验证码只能用一次，用完即焚。
     */
    public synchronized boolean verify(String input) {
        Instant now = Instant.now();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw AuthException.tooManyRequests("尝试次数过多，请 " + lockRemainingMinutes() + " 分钟后再试");
        }
        if (input == null || input.isBlank()) {
            return false;
        }
        if (code == null || expiresAt == null || now.isAfter(expiresAt)) {
            // 验证码不存在或已过期。不告诉用户是哪种，一律说"错误或已过期"
            recordFailure();
            return false;
        }
        if (!code.equals(input.trim())) {
            recordFailure();
            return false;
        }
        clear();
        return true;
    }

    private void recordFailure() {
        attempts++;
        int remaining = props.getMaxAttempts() - attempts;
        log.warn("验证码校验失败，剩余尝试次数 {}", Math.max(remaining, 0));
        if (attempts >= props.getMaxAttempts()) {
            lockedUntil = Instant.now().plusSeconds(props.getLockMinutes() * 60L);
            log.warn("验证码连续输错 {} 次，锁定 {} 分钟", attempts, props.getLockMinutes());
        }
    }

    public synchronized void clear() {
        this.code = null;
        this.expiresAt = null;
        this.attempts = 0;
        this.lockedUntil = null;
        // 冷却不清：刚验证成功就允许再发一封，等于绕过了频率限制
    }

    public synchronized long cooldownSeconds() {
        if (cooldownUntil == null) {
            return 0;
        }
        long s = cooldownUntil.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(s, 0);
    }

    public synchronized long lockRemainingMinutes() {
        if (lockedUntil == null) {
            return 0;
        }
        long m = (lockedUntil.getEpochSecond() - Instant.now().getEpochSecond()) / 60;
        return Math.max(m, 0);
    }

    public synchronized long remainingAttempts() {
        return Math.max(props.getMaxAttempts() - attempts, 0);
    }
}
