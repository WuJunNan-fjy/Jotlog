# Jotlog 鉴权与前端

> 2026-10-08 补。这一轮把「无鉴权」这个开源前必须解决的硬伤补上了，同时把前端从零建了起来。

---

## 一、登录流程

```
用户名 + 密码 ──► 后端验密码 ──► 通过后发邮箱验证码 ──► 用户填验证码 ──► 签发 JWT
```

校验顺序是**密码在前、验证码在后**，这是刻意的：
反过来的话，任何人只要知道用户名就能消耗掉你的验证码，等于给你制造一个"永远登不上"的拒绝服务。

对外错误消息不区分"用户不存在"和"密码错误"，统一说"用户名或密码错误"。

四道闸防止验证码被滥用：

| 闸 | 默认值 | 挡什么 |
|---|---|---|
| 发送冷却 | 60 秒 | 狂点发送刷爆邮箱 |
| 有效期 | 5 分钟 | 验证码不是永久通行证 |
| 试错次数 | 5 次 | 暴力枚举 6 位数字 |
| 超限锁定 | 30 分钟 | 试错到上限直接锁死 |

验证码状态放**内存**，不落库也不放 Redis。单实例自用，验证码 5 分钟就过期，为它引入外部依赖不划算。代价是重启后未使用的验证码丢失，重发一次即可。

---

## 二、为什么有会话表

JWT 是无状态的，签出去就收不回来。如果只靠 JWT，点"退出登录"只是前端删 token，服务端那张票还能继续用。

所以 `auth_sessions` 表存的是**还活着的票**（jti → 用户 → 过期时间）：

- 登出 = 删这一行，那张票立刻失效
- 改密码 = 删这个用户的所有行，所有设备下线
- 每小时清理一次过期行，表不会无限涨

存"白名单"而不是"黑名单"，是因为黑名单会无限增长，白名单可以随过期清理。

代价是每次请求多一次主键查询。自用场景 QPS 个位数，完全可以接受。

---

## 三、为什么不用 Spring Security

这个服务只有两种接口：完全公开的登录接口，和必须登录的业务接口。

没有角色、没有权限矩阵、没有方法级注解。为了这点需求引入一整套 Security 过滤器链，配置成本远大于收益，出问题时也更难排查。

所以是自己写的 `JwtInterceptor`，挂在 `/api/**` 上，只放行 `/api/auth/login` 和 `/api/auth/code`。

**一个必须记住的点**：拦截器在 `afterCompletion` 里清 ThreadLocal。Tomcat 线程是复用的，不清的话下一个请求会继承上一个用户的 id —— 这种 bug 只在并发下偶现，是最难查的那一类。

---

## 四、接口清单

### 公开（不需要登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/code` | 发验证码到用户登记的邮箱 |
| POST | `/api/auth/login` | 用户名 + 密码 + 验证码 → JWT |

### 需要登录（`Authorization: Bearer <token>`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/logout` | 废掉当前这张票 |
| GET | `/api/auth/me` | 当前用户信息 |
| PUT | `/api/auth/password` | 改密码，成功后所有设备下线 |
| PUT | `/api/auth/email` | 换邮箱，需要验证码（发到**旧邮箱**） |
| GET | `/api/entries` | 列表 / 搜索 / 分页 |
| POST | `/api/entries?text=` | 手动录入 |
| GET | `/api/entries/{id}` | 单条 |
| PATCH | `/api/entries/{id}` | 改星标 / 归档 / 备注 |
| DELETE | `/api/entries/{id}` | 删除 |
| GET | `/api/stats` | 总数 / 今天 / 本周 / 星标 / 归档 |

统一错误格式：`{"error": "..."}` + HTTP 状态码。没有 code 字段、没有嵌套数组 —— 自用系统的错误提示是给人看的。

未知异常一律不回传原始消息：堆栈里可能有 JDBC URL、文件路径这类信息，公网服务不该把它们吐出去。

> 注意：`/api/entries` 现在返回 `{items: [...], total: n}` 而不是裸数组。
> 早期文档里的 `curl` 示例输出结构已过时。

---

## 五、前端结构

```
web/
├── index.html
├── public/                 # 直接拷进 dist，PWA 资产在这
│   ├── manifest.webmanifest
│   ├── sw.js
│   └── icon-*.png / icon.svg
├── scripts/gen-icons.py    # 图标生成（需要 Pillow）
└── src/
    ├── api/http.ts         # fetch 封装：token、401 统一处理、错误归一
    ├── stores/auth.ts      # 登录态（pinia）
    ├── router/index.ts     # 路由 + 登录守卫
    ├── components/
    │   ├── AppShell.vue    # 顶栏 + 移动端底部 tab
    │   ├── ComposeBox.vue  # "记一笔"输入框
    │   ├── EntryCard.vue   # 单条记录
    │   └── Icon.vue        # 十来个手写 SVG 图标
    ├── views/
    │   ├── LoginView.vue
    │   ├── TimelineView.vue  # 复用为 全部 / 星标 / 归档
    │   ├── SearchView.vue
    │   └── SettingsView.vue
    ├── utils/format.ts     # 日期分组（今天/昨天/10月8日 周三）
    └── style.css           # 设计 token + 基础组件类
```

几个设计取舍：

- **token 存 localStorage。** 前后端分离 + Bearer JWT 下这是最简单可靠的做法。代价是 XSS 能读到 token，所以任何地方都**不要**用 `v-html` 渲染用户输入的内容。
- **移动端优先。** 手机是随手记的主入口，所以底部 tab 用 `fixed` + `safe-area-inset`，拇指够得着。桌面端换成顶部导航。
- **图标手写，不引图标库。** 一共十来个图标，为它拉一个依赖不划算。
- **日期分组在时间线里是必需的。** 没有日期分隔的流水账根本没法回溯。

---

## 六、配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `jotlog.auth.username` | `admin` | 初始用户名 |
| `jotlog.auth.email` | 空 | 初始邮箱，验证码发到这里 |
| `jotlog.auth.password` | 空 | 初始密码。留空则随机生成一个打在日志里 |
| `jotlog.auth.jwt-secret` | 空 | 留空则随机生成，重启后所有人要重新登录 |
| `jotlog.auth.jwt-ttl-hours` | 168 | 7 天 |
| `jotlog.auth.code-ttl-minutes` | 5 | 验证码有效期 |
| `jotlog.auth.code-cooldown-seconds` | 60 | 发送冷却 |
| `jotlog.auth.max-attempts` | 5 | 验证码最多试错几次 |
| `jotlog.auth.lock-minutes` | 30 | 超限锁定时间 |
| `jotlog.auth.dev-code-to-log` | false | **调试用**：验证码只写日志。生产必须 false |
| `spring.mail.*` | 空 | SMTP。没配 host 时按"未配置"处理 |

初始密码只在**第一次启动**时生效。用户建好之后，改密码走数据库，配置文件里不再有明文密码。

---

## 七、部署前必做

1. **配 HTTPS。** 密码和 token 都是明文在网线上跑，没有 TLS 等于把家门钥匙挂在门外。
2. **关掉 `dev-code-to-log`。**
3. **`jwt-secret` 填固定值**，否则每次重启都要重新登录。
4. **数据库别再用 root。** 见 `scripts/init-db.sql` 里注释掉的建账号语句。
5. **JDBC URL 去掉 `useSSL=false`**（那是给 MySQL 之间加密用的，不是给公网传密码用的），或者让 MySQL 只监听 `127.0.0.1`、应用同机部署。

---

## 八、已知取舍

| 取舍 | 理由 | 什么时候要改 |
|---|---|---|
| 单用户，没有注册 | 自托管，一个人用 | 要给第二个人用时，得加 users 之外的隔离设计 |
| 验证码状态在内存 | 单实例，5 分钟就过期 | 多实例部署时换成 Redis |
| token 存 localStorage | 最简单可靠 | 若要防 XSS 窃取，改 HttpOnly Cookie |
| 拦截器手写 | 只有两级权限 | 出现角色 / 权限矩阵时上 Spring Security |
| 物理删除 | "归档"已经是软删了 | 需要回收站时再加一层 |
