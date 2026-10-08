package com.jotlog.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 拦截 /api/**，校验 Bearer token。
 *
 * 为什么自己写拦截器而不引 Spring Security：
 *   这个服务只有两种接口 —— 完全公开的登录接口，和必须登录的业务接口。
 *   没有角色、没有权限矩阵、没有方法级注解。为了这点需求引入一整套
 *   Security 过滤器链，配置成本远大于收益，而且出问题时更难排查。
 *
 * 校验两步：
 *   1. JWT 验签 + 没过期（无状态，快）
 *   2. jti 在 auth_sessions 里（有状态，能真正登出）
 *   第 2 步是 JWT 唯一收得回来的手段，别省。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtInterceptor.class);

    private final JwtUtil jwt;
    private final SessionRepository sessions;

    public JwtInterceptor(JwtUtil jwt, SessionRepository sessions) {
        this.jwt = jwt;
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = bearer(request);
        if (token == null) {
            throw AuthException.unauthorized("未登录");
        }

        Claims claims = jwt.parse(token);
        Object uid = claims.get(JwtUtil.CLAIM_USER);
        String jti = claims.get(JwtUtil.CLAIM_JTI, String.class);
        if (uid == null || jti == null) {
            throw AuthException.unauthorized("登录已失效，请重新登录");
        }

        long userId = ((Number) uid).longValue();
        if (!sessions.isValid(jti)) {
            throw AuthException.unauthorized("登录已失效，请重新登录");
        }

        CurrentUser.set(userId);
        return true;
    }

    /**
     * 必须清 ThreadLocal。
     *
     * Tomcat 线程是复用的，不清的话下一个请求会继承上一个用户的 id。
     * 这种 bug 只在并发下偶现，是最难查的那一类。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= 7
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }
}
