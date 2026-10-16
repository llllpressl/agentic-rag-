-- 知识库表
CREATE TABLE t_knowledge_base (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    collection VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(64) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(20) NOT NULL,
    update_by VARCHAR(20),
    deleted SMALLINT NOT NULL DEFAULT 0
);

-- 表注释
COMMENT ON TABLE t_knowledge_base IS '知识库信息表';
-- 字段注释
COMMENT ON COLUMN t_knowledge_base.id IS '知识库主键ID';
COMMENT ON COLUMN t_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN t_knowledge_base.collection IS '向量数据库集合名称';
COMMENT ON COLUMN t_knowledge_base.embedding_model IS '知识库使用的Embedding模型名称';
COMMENT ON COLUMN t_knowledge_base.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_base.update_time IS '最后更新时间';
COMMENT ON COLUMN t_knowledge_base.create_by IS '创建人用户ID';
COMMENT ON COLUMN t_knowledge_base.update_by IS '最后更新人用户ID';
COMMENT ON COLUMN t_knowledge_base.deleted IS '逻辑删除标识：0-未删除，1-已删除';
-- collection 唯一索引
CREATE UNIQUE INDEX uk_knowledge_base_collection
    ON t_knowledge_base (collection);
-- name 普通索引
CREATE INDEX idx_knowledge_base_name
    ON t_knowledge_base (name);

-- 知识文档表
CREATE TABLE t_knowledge_document (
                                      id VARCHAR(20) NOT NULL PRIMARY KEY,
                                      kb_id VARCHAR(20) NOT NULL,
                                      doc_name VARCHAR(255) NOT NULL,
                                      chunk_count INTEGER NOT NULL DEFAULT 0,

                                      file_url VARCHAR(1024) NOT NULL,
                                      file_type VARCHAR(32) NOT NULL,
                                      mime_type VARCHAR(128) NOT NULL,
                                      file_size BIGINT NOT NULL,

                                      create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      create_by VARCHAR(20),
                                      update_by VARCHAR(20) NOT NULL,

                                      status VARCHAR(16) NOT NULL DEFAULT 'pending',
                                      enabled SMALLINT NOT NULL DEFAULT 1,
                                      deleted SMALLINT NOT NULL DEFAULT 0,

                                      CONSTRAINT chk_document_status
                                          CHECK (status IN ('pending', 'running', 'success', 'failure'))
);

-- 表注释
COMMENT ON TABLE t_knowledge_document IS '知识库文档信息表';

-- 字段注释
COMMENT ON COLUMN t_knowledge_document.id IS '文档主键ID';
COMMENT ON COLUMN t_knowledge_document.kb_id IS '所属知识库ID';
COMMENT ON COLUMN t_knowledge_document.doc_name IS '文档名称';
COMMENT ON COLUMN t_knowledge_document.chunk_count IS '文档分块数量';

COMMENT ON COLUMN t_knowledge_document.file_url IS '原始文件存储地址';
COMMENT ON COLUMN t_knowledge_document.file_type IS '文件格式名称，如markdown、text、pdf、docx';
COMMENT ON COLUMN t_knowledge_document.mime_type IS '文件MIME类型，如application/pdf';
COMMENT ON COLUMN t_knowledge_document.file_size IS '文件大小，单位字节';

COMMENT ON COLUMN t_knowledge_document.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document.update_time IS '最后更新时间';
COMMENT ON COLUMN t_knowledge_document.create_by IS '创建人用户ID';
COMMENT ON COLUMN t_knowledge_document.update_by IS '最后更新人用户ID';

COMMENT ON COLUMN t_knowledge_document.status IS '文档处理状态：pending-待处理，running-处理中，success-成功，failure-失败';
COMMENT ON COLUMN t_knowledge_document.enabled IS '启用状态：0-禁用，1-启用';
COMMENT ON COLUMN t_knowledge_document.deleted IS '逻辑删除标识：0-未删除，1-已删除';

-- 所属知识库索引
CREATE INDEX idx_knowledge_document_kb_id
    ON t_knowledge_document (kb_id);
