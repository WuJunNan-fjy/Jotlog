package com.jotlog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全站安全响应头。
 *
 * 单页应用 + 自托管静态资源，没有任何第三方脚本 / 统计 / CDN，所以 CSP 可以收得很紧：
 *   default-src 'self'                  一切默认只许同源
 *   script-src 'self'                   不许内联脚本、不许 eval（Vite 产物全是外链 hash 文件）
 *   style-src  'self' 'unsafe-inline'   Vue 运行时会写 style 属性，inline 样式必须放行
 *   img/font/media 'self' data: blob:   附件预览走 fetch→blob URL，图标可能有 data URI
 *   connect-src 'self'                  只许调自己的 /api
 *   object-src 'none'                   不要 Flash/插件
 *   frame-ancestors 'none'              等价于 X-Frame-Options:DENY，且为现代浏览器标准写法
 *
 * 开发期 Vite 在 5190 端口直接提供页面（HMR 用内联脚本和 ws:），但页面响应根本不经过
 * 这个过滤器 —— 它只作用于后端 8095，dev 下只有 /api 请求走这里，CSP 对接口响应无意义
 * 也无影响。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "media-src 'self' blob:",
            "connect-src 'self'",
            "manifest-src 'self'",
            "worker-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        chain.doFilter(request, response);
    }
}
