package com.jotlog.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：给 /api/** 挂 JWT 拦截器，只放行登录和发验证码。
 *
 * SPA 路由兜底不在这里，见 {@link com.jotlog.web.SpaFallbackController} ——
 * 那里还要处理"前端没构建"的情况，用 addViewControllers 表达不了。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public WebConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                // 只放行这两个。logout / me / 改密码 都必须登录才能调
                .excludePathPatterns("/api/auth/login", "/api/auth/code");
    }

}
