-- 阶段一独立脚本：部署者显式执行，不在应用启动时建表。
CREATE TABLE t_agent_conversation (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL REFERENCES t_user(id),
    conversation_id VARCHAR(36) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL DEFAULT '新对话',
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
    UNIQUE (user_id, conversation_id),
    CHECK (length(btrim(title)) > 0)
);
CREATE INDEX idx_agent_conversation_owner
    ON t_agent_conversation(user_id, update_time DESC, id DESC) WHERE deleted = 0;

CREATE TABLE t_agent_message (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT,
    thinking_content TEXT,
    block JSONB NOT NULL CHECK (jsonb_typeof(block) = 'object'),
    last_message_id VARCHAR(20),
    status VARCHAR(16) NOT NULL CHECK (status IN ('normal', 'interrupted')),
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
    UNIQUE (id, user_id, conversation_id),
    FOREIGN KEY (user_id, conversation_id)
        REFERENCES t_agent_conversation(user_id, conversation_id),
    FOREIGN KEY (last_message_id, user_id, conversation_id)
        REFERENCES t_agent_message(id, user_id, conversation_id),
    CHECK (
        (role = 'user' AND last_message_id IS NULL
         AND status = 'normal' AND content IS NOT NULL
         AND length(btrim(content)) > 0 AND thinking_content IS NULL)
        OR (role = 'assistant' AND last_message_id IS NOT NULL)
    )
);
CREATE INDEX idx_agent_message_rounds
    ON t_agent_message(user_id, conversation_id, create_time, id)
    WHERE deleted = 0 AND role = 'user';
CREATE UNIQUE INDEX uk_agent_message_reply
    ON t_agent_message(user_id, conversation_id, last_message_id)
    WHERE role = 'assistant';

CREATE TABLE t_agent_state (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    all_content JSONB NOT NULL
        DEFAULT '{"formatVersion":1,"states":{},"lists":{}}'::jsonb,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
    UNIQUE (user_id, session_id),
    FOREIGN KEY (user_id, session_id)
        REFERENCES t_agent_conversation(user_id, conversation_id),
    CHECK (jsonb_typeof(all_content) = 'object'
        AND all_content ? 'states' AND all_content ? 'lists'
        AND jsonb_typeof(all_content -> 'states') = 'object'
        AND jsonb_typeof(all_content -> 'lists') = 'object')
);
COMMENT ON TABLE t_agent_conversation IS 'Agent 会话，删除时连同消息和状态逻辑删除';
COMMENT ON TABLE t_agent_message IS '一问一答按轮展示，assistant 后于 user 入库';
COMMENT ON COLUMN t_agent_message.last_message_id IS 'user 固定为空；assistant 指向其回复的 user';
COMMENT ON COLUMN t_agent_message.block IS '内容块与执行信息；中断原因不扩展 status';
COMMENT ON TABLE t_agent_state IS 'AgentScope 完整状态存储，session_id 等于 conversation_id';
