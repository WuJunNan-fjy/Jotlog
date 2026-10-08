# Jotlog · 随手记

把"发给文件传输助手"这个习惯，搬到一个自己完全掌控的地方。

飞书发一条消息 → 落进你自己的 MySQL → 手机和电脑打开同一个网址查看和搜索。

**仓库**：https://github.com/WuJunNan-fjy/Jotlog ｜ **许可**：MIT ｜ **当前阶段**：M0 已完成，M1 进行中

---

## 登录方式

用户名 + 密码 + **邮箱验证码**，三要素。

登录后发 JWT，默认 7 天有效。会话记在 MySQL 里，所以退出登录是真的把那张票废掉，不是只在浏览器里删个 localStorage。

邮件必须配 SMTP。没配的话点发送验证码会直接报错告诉你原因，不会假装成功。
本地调试可以把 `jotlog.auth.dev-code-to-log` 设成 `true`，验证码只打日志不真发（**生产环境必须关掉**）。

---

## 技术栈

| 层 | 选择 |
|---|---|
| 后端 | Java 17 + Spring Boot 3.5 + JdbcTemplate |
| 鉴权 | 自写拦截器 + JWT（jjwt 0.12）+ BCrypt + 会话表 |
| 数据库 | MySQL 8.0（`ngram` 中文全文索引） |
| 表结构 | Flyway |
| 飞书通道 | `com.larksuite.oapi:oapi-sdk` 2.8.5（长连接） |
| 前端 | Vue 3 + Vite + TypeScript + Tailwind 4（PWA） |
| 部署 | 单个可执行 jar + systemd + Nginx |
| 许可证 | MIT |

没有 Redis，没有 Spring Security。前者用一个每小时跑一次的清理任务代替，后者对这个"只有登录/未登录两种状态"的服务来说是过度设计。

---

## 快速开始

### 1. 建库

```bash
mysql -u root -p < scripts/init-db.sql
```

### 2. 配凭证

```bash
cp config/application-local.yml.example config/application-local.yml
```

填数据库、SMTP、初始账号三块。这个文件已被 gitignore，不会进仓库。

### 3. 跑后端

```bash
mvn spring-boot:run
```

首次启动会自动建出初始用户，用户名和初始密码打在日志里（一大段 `====` 包着的警告）。**登录后立刻去设置页改掉。**

### 4. 跑前端

```bash
cd web && npm install
npm run dev          # http://localhost:5173，/api 代理到 8080
```

需要 Node 20+（Vite 6 的硬性要求）。

### 5. 打包成一个 jar

```bash
make dist            # = npm run build + mvn package
java -jar target/jotlog.jar
```

前端产物会被塞进 jar 的 `static/` 下，部署只传一个文件。手机浏览器打开后"添加到主屏幕"，就是一个 PWA。

> MySQL 侧有个必须做的设置，否则搜不到含 `a`/`i` 的英文词（比如 `Java`）：
> `SET GLOBAL innodb_ft_enable_stopword = 0`，并写进 `my.cnf`。
> 见 `scripts/init-db.sql`。

---

## 界面

三个屏，移动端优先（手机才是随手记的主入口）。

- **时间线** —— 顶部一排数字，下面一个输入框，然后是按日期分组的记录流。滚到底自动加载。
- **搜索** —— 搜原文、标题和 AI 摘要。两个及以上的词走全文索引，单个字退化成模糊匹配。
- **设置** —— 改密码、换邮箱、退出登录。

星标和归档是条目的两个开关，不是单独的"文件夹"——归档只是让它从时间线上消失，不删内容。

---

## 文档

| 文档 | 内容 |
|---|---|
| [鉴权与前端](docs/Jotlog-鉴权与前端.md) | 登录流程、会话表、前端结构、接口清单 |
| [初版设计文档](docs/Jotlog-初版设计文档.md) | 产品定位、竞品调研、数据模型 |
| [技术选型](docs/Jotlog-技术选型.md) | v1.0 选型、ngram 的坑、飞书 SDK 真实 API |
| [多通道接入方案](docs/Jotlog-多通道接入方案.md) | 通道即配置的架构 |
| [AI 润色规范](docs/Jotlog-AI润色规范.md) | 四类内容四种策略、原文不可变原则 |
| [运行场景与开源策略](docs/Jotlog-运行场景与开源策略.md) | 数据存哪、怎么查看、怎么部署 |
| [M0 验证清单](docs/M0-验证清单.md) | 飞书链路的验收步骤和排查表 |
| [交接文档](docs/HANDOFF-2026-10-08.md) | 2026-10-08 进度、踩过的坑、接手步骤 |

---

## 当前状态

- [x] Maven 骨架 + Flyway 建表
- [x] 飞书长连接接收消息
- [x] 幂等入库 + 回执
- [x] 中文全文检索（含 LIKE 兜底）
- [x] **邮箱验证码登录 + JWT + 会话表**
- [x] **PWA 界面（时间线 / 搜索 / 设置）**
- [ ] 图片 / 文件接收
- [ ] AI 润色
- [ ] 每日回顾

---

## 设计原则

1. **原文不可变。** `raw_input` 永不被 UPDATE。AI 只写 `ai_*` 字段。
2. **入库 → 回执 → 异步增强。** 飞书要求 3 秒内返回，慢操作一律异步。
3. **AI 是增强不是命脉。** AI 挂了，随手记依然完全可用。
4. **通道即配置。** 换通道不动管线。
5. **AI 不碰金句。** 用户随手写的话、触动的句子，默认跳过 AI。
6. **只引真正需要的依赖。** 没有 Redis、没有 Spring Security、没有图标库、没有 UI 组件库。
