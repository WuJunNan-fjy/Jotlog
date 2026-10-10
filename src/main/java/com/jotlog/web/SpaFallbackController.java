package com.jotlog.web;

import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * SPA 路由兜底。
 *
 * 前端用 history 路由，访问 /timeline 时服务端没有这个文件，
 * 不兜底刷新页面必 404。
 *
 * 这里额外处理一件事：前端还没构建时（本地 spring-boot:run 最常见），
 * index.html 根本不存在，直接 forward 过去会变成 500 ——
 * 看起来像后端挂了，实际只是前端没打包。与其让人猜，
 * 不如明说该怎么修。
 *
 * 路径里的 [^\\.]* 表示"不含点号"，用来放过
 * /assets/xxx.js、/favicon.ico 这类真实静态文件，交给默认的资源处理器。
 */
@Controller
public class SpaFallbackController {

    private final boolean frontendBuilt;

    public SpaFallbackController(ResourceLoader loader) {
        this.frontendBuilt = loader.getResource("classpath:/static/index.html").exists();
    }

    @GetMapping({"/", "/{p:[^\\.]*}", "/**/{p:[^\\.]*}"})
    public Object fallback() {
        return frontendBuilt ? "forward:/index.html" : "forward:/__frontend-missing";
    }

    /**
     * 前端未构建时的说明页。
     *
     * 用 503 而不是 200：这确实是一个"服务还没准备好"的状态，
     * 不该让浏览器把它当正常页面缓存下来。
     */
    @GetMapping("/__frontend-missing")
    @ResponseBody
    public ResponseEntity<String> missing() {
        String html = """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <title>Jotlog 前端未构建</title>
                <style>
                  body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:#FBFAF7;color:#1f2328;
                       max-width:640px;margin:0 auto;padding:48px 24px;line-height:1.8}
                  h1{font-size:20px;font-weight:600;margin:0 0 16px}
                  code{background:#f0efe9;padding:2px 6px;border-radius:4px;font-size:13px}
                  pre{background:#f0efe9;padding:14px 16px;border-radius:8px;overflow-x:auto}
                  p{margin:0 0 12px}
                </style></head><body>
                <h1>后端已启动，但前端还没构建</h1>
                <p>二选一：</p>
                <p><b>1. 本地开发</b>（前后端分开跑，改前端代码即时生效）</p>
                <pre>cd web
                npm install
                npm run dev      # 访问 http://localhost:5190</pre>
                <p><b>2. 打成单 jar</b>（前端产物会被塞进 static/）</p>
                <pre>make dist
                java -jar target/jotlog.jar</pre>
                <p style="color:#8c959f;font-size:13px">
                  API 现在已经可用了，只是没有页面：
                  <code>curl localhost:8095/actuator/health</code>
                </p>
                </body></html>
                """;
        return ResponseEntity.status(503)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
    }
}
