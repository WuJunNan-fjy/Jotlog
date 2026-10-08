package com.jotlog.auth;

/**
 * 当前登录用户。
 *
 * 用 ThreadLocal 而不是把 userId 一路透传到 service，是因为鉴权是
 * 横切关注点，让业务方法签名里都多一个 userId 参数纯属噪音。
 *
 * 代价：必须在拦截器里 finally / afterCompletion 清理，否则线程复用会串号。
 * 这个清理在 {@link JwtInterceptor#afterCompletion} 里做，别删。
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    /** 取不到就抛，说明这个接口没被拦截器保护，是配置漏了。 */
    public static long require() {
        Long id = USER_ID.get();
        if (id == null) {
            throw AuthException.unauthorized("未登录");
        }
        return id;
    }

    public static void clear() {
        USER_ID.remove();
    }
}
