# Jotlog · 随手记

把"发给文件传输助手"这个习惯，搬到一个自己完全掌控的地方。

飞书发一条消息 → 落进你自己的 MySQL → 手机和电脑打开同一个网址查看和搜索。

**仓库**：https://github.com/WuJunNan-fjy/Jotlog ｜ **许可**：MIT ｜ **当前阶段**：M0 验证中

---

> ## ⚠️ 部署前必读：这个项目还没有鉴权
>
> 当前所有 HTTP 接口**没有任何登录校验**。
> 这意味着：如果你把它部署在公网，**任何人都能读写你的全部笔记**。
>
> 在补上鉴权之前，请只做这两件事之一：
> - 只在**内网 / 本机 / VPN** 下访问
> - 用 Nginx 加一层 HTTP Basic Auth 或 IP 白名单
>
> 补鉴权已列在路线图 M4（开源就绪），是硬性要求，不是可选项。

---

## 它是什么

一个自托管的私人随手记系统。

- **飞书是入口**：手机上一键发给机器人，桌面端用快捷键
- **MySQL 是仓库**：数据只在你自己服务器上，不经过任何第三方
- **PWA 是界面**：手机添加到主屏，电脑直接访问，同一个网址

核心承诺：**`raw_input` 永不可变。** AI 只补充，不覆盖。

---

## 技术栈

| 层 | 选择 |
|---|---|
| 后端 | Java 17 + Spring Boot 3.5 |
| 数据库 | MySQL 8.0（`ngram` 中文全文索引） |
| 表结构 | Flyway |
| 飞书通道 | `com.larksuite.oapi:oapi-sdk` 2.8.5（长连接） |
| 前端 | Preact + Vite + Tailwind（PWA） |
| 部署 | jar + systemd + Nginx |
| 许可证 | MIT |

---

## 快速开始

```bash
# 1. 建库（内含 ngram 自检）
mysql -u root -p < scripts/init-db.sql

# 2. 配凭证（推荐放 config/application-local.yml，该文件已被 gitignore）
cp config/application-local.yml.example config/application-local.yml
#    然后填真实值。也可以用环境变量：
export FEISHU_APP_ID=cli_xxx
export FEISHU_APP_SECRET=xxx
export JOTLOG_DB_PASSWORD=xxx

# 3. 打开飞书通道
#    在 application-local.yml 里把 jotlog.channel.feishu.enabled 改成 true

# 4. 跑
mvn spring-boot:run
```

详细步骤见 [`docs/M0-验证清单.md`](docs/M0-验证清单.md)。

> MySQL 侧有个必须做的设置，否则搜不到含 `a`/`i` 的英文词（比如 `Java`）：
> `SET GLOBAL innodb_ft_enable_stopword = 0`，并写进 `my.cnf`。
> 见 `scripts/init-db.sql`。

---

## 文档

| 文档 | 内容 |
|---|---|
| [初版设计文档](docs/Jotlog-初版设计文档.md) | 产品定位、竞品调研、数据模型 |
| [技术选型](docs/Jotlog-技术选型.md) | v1.0 选型、ngram 的坑、飞书 SDK 真实 API |
| [多通道接入方案](docs/Jotlog-多通道接入方案.md) | 通道即配置的架构 |
| [AI 润色规范](docs/Jotlog-AI润色规范.md) | 四类内容四种策略、原文不可变原则 |
| [运行场景与开源策略](docs/Jotlog-运行场景与开源策略.md) | 数据存哪、怎么查看、怎么部署 |
| [M0 验证清单](docs/M0-验证清单.md) | **现在就读这个** |
| [交接文档](docs/HANDOFF-2026-10-08.md) | 2026-10-08 进度、踩过的坑、接手步骤 |

---

## 当前状态

M0 阶段：飞书长连接打通，AI 润色和 PWA 界面尚未实现。

- [x] Maven 骨架 + Flyway 建表
- [x] 飞书长连接接收消息
- [x] 幂等入库 + 回执
- [x] 中文全文检索
- [x] 异步增强队列骨架
- [ ] 图片 / 文件接收
- [ ] AI 润色
- [ ] PWA 三屏界面
- [ ] 每日回顾
- [ ] 鉴权（开源前必须补）

---

## 设计原则

1. **原文不可变。** `raw_input` 永不被 UPDATE。AI 只写`ai_*` 字段。
2. **入库 → 回执 → 异步增强。** 飞书要求 3 秒内返回，慢操作一律异步。
3. **AI 是增强不是命脉。** AI 挂了，随手记依然完全可用。
4. **通道即配置。** 换通道不动管线。
5. **AI 不碰金句。** 用户随手写的话、触动的句子，默认跳过 AI。
