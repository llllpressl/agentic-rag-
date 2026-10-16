-- 用户模块独立建表脚本，请在启动 agent 应用前由数据库管理人员执行。
-- 用户名全表唯一：逻辑删除后也不重复使用，避免账号身份混淆。
CREATE TABLE t_user (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    deleted SMALLINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT chk_user_id CHECK (id ~ '^[1-9][0-9]{0,18}$'),
    CONSTRAINT chk_user_username CHECK (username ~ '^[A-Za-z0-9_][A-Za-z0-9_.-]{2,63}$'),
    CONSTRAINT chk_user_role CHECK (role IN ('admin', 'user')),
    CONSTRAINT chk_user_deleted CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE t_user IS 'Agent 用户表';
COMMENT ON COLUMN t_user.id IS 'MyBatis-Plus 生成的字符串雪花 ID';
COMMENT ON COLUMN t_user.username IS '全表唯一用户名，逻辑删除后仍保留';
COMMENT ON COLUMN t_user.password IS 'BCrypt 密码哈希，禁止保存明文';
COMMENT ON COLUMN t_user.role IS '用户角色：admin 或 user';
COMMENT ON COLUMN t_user.deleted IS '逻辑删除标识：0-未删除，1-已删除';

CREATE INDEX idx_user_active_role ON t_user (role) WHERE deleted = 0;
