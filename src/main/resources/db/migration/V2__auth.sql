-- ============================================================
-- Jotlog V2 鉴权
--
-- 设计前提：单用户自托管。不是 SaaS，没有注册入口。
--
-- 为什么要有 users 表而不是把密码写进配置文件：
--   1. 密码要能被用户自己在页面上改掉，配置文件改不了运行时状态
--   2. 邮箱要能换绑，同样需要落库
--   3. 存的是 BCrypt 哈希，明文密码只在首次初始化时用一次，之后内存里都没有
--
-- 为什么要有 auth_sessions 表（而不是纯无状态 JWT）：
--   纯 JWT 在过期前无法失效，点"退出登录"只是前端删 token，
--   服务端那张票还能用。记一张会话表才能真正登出、才能列出登录设备。
--   代价是每次请求多一次主键查询 —— 自用场景 QPS 个位数，完全可以接受。
-- ============================================================

CREATE TABLE users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    username       VARCHAR(64)  NOT NULL,
    email          VARCHAR(255) NOT NULL,
    -- BCrypt 固定 60 字符。留 100 是为了将来换算法不用改表
    password_hash  VARCHAR(100) NOT NULL,
    nickname       VARCHAR(64)  NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    last_login_at  DATETIME(3)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户。单用户部署：启动时会用配置里的初始账号自动建第一条记录。';


CREATE TABLE auth_sessions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    -- JWT 的 jti。登出时删这一行，那张票立刻失效
    token_id    VARCHAR(64)  NOT NULL,
    user_agent  VARCHAR(255) NULL,
    ip          VARCHAR(64)  NULL,
    expires_at  DATETIME(3)  NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_token_id (token_id),
    KEY idx_user (user_id),
    KEY idx_expires (expires_at),

    CONSTRAINT fk_session_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '登录会话。JWT 本身无状态，靠这张表实现真正的登出。';
