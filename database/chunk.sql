
-- ============================================
-- 知识库文档分块表
-- PostgreSQL + pgvector
-- Embedding 维度：1024
-- 向量索引：HNSW（余弦距离）
-- 关键词检索：Elasticsearch BM25
-- ============================================

-- 1. 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 创建分块表
CREATE TABLE t_knowledge_chunk (
                                   id VARCHAR(20) NOT NULL PRIMARY KEY,
                                   kb_id VARCHAR(20) NOT NULL,
                                   collection VARCHAR(64) NOT NULL,
                                   doc_id VARCHAR(20) NOT NULL,
                                   chunk_index INTEGER NOT NULL,

                                   content TEXT NOT NULL,
                                   embedding vector(1024),
                                   char_count INTEGER NOT NULL,
                                   token_count INTEGER NOT NULL,

                                   create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   create_by VARCHAR(20) NOT NULL,
                                   update_by VARCHAR(20),

                                   enabled SMALLINT NOT NULL DEFAULT 1,
                                   deleted SMALLINT NOT NULL DEFAULT 0,

                                   CONSTRAINT chk_chunk_index
                                       CHECK (chunk_index >= 0),
                                   CONSTRAINT chk_chunk_char_count
                                       CHECK (char_count >= 0),
                                   CONSTRAINT chk_chunk_token_count
                                       CHECK (token_count >= 0),
                                   CONSTRAINT chk_chunk_enabled
                                       CHECK (enabled IN (0, 1)),
                                   CONSTRAINT chk_chunk_deleted
                                       CHECK (deleted IN (0, 1))
);

-- 3. 表注释
COMMENT ON TABLE t_knowledge_chunk IS '知识文档分块表';

-- 4. 字段注释
COMMENT ON COLUMN t_knowledge_chunk.id IS '分块主键ID';
COMMENT ON COLUMN t_knowledge_chunk.kb_id IS '所属知识库ID';
COMMENT ON COLUMN t_knowledge_chunk.doc_id IS '所属文档ID';
COMMENT ON COLUMN t_knowledge_chunk.chunk_index IS '文档内分块序号，从0开始';
COMMENT ON COLUMN t_knowledge_chunk.content IS '分块正文';
COMMENT ON COLUMN t_knowledge_chunk.embedding IS '1024维文本向量，未生成时允许为空';
COMMENT ON COLUMN t_knowledge_chunk.char_count IS '正文Unicode码点数量，包含空格和换行';
COMMENT ON COLUMN t_knowledge_chunk.token_count IS '正文Token数量，由应用统一维护';
COMMENT ON COLUMN t_knowledge_chunk.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_chunk.update_time IS '最后更新时间，由应用维护';
COMMENT ON COLUMN t_knowledge_chunk.create_by IS '创建人用户ID';
COMMENT ON COLUMN t_knowledge_chunk.update_by IS '最后更新人用户ID';
COMMENT ON COLUMN t_knowledge_chunk.enabled IS '启用状态：0-禁用，1-启用';
COMMENT ON COLUMN t_knowledge_chunk.deleted IS '逻辑删除标识：0-未删除，1-已删除';

-- ============================================
-- 5. 创建业务索引
-- ============================================

-- 同一文档内的分块序号必须唯一
-- 同时支持按 doc_id 查询文档分块
CREATE UNIQUE INDEX uk_knowledge_chunk_doc_id_chunk_index
    ON t_knowledge_chunk (doc_id, chunk_index);

-- 知识库过滤索引
-- 仅索引未删除且启用的分块
CREATE INDEX idx_knowledge_chunk_kb_id
    ON t_knowledge_chunk (kb_id)
    WHERE deleted = 0 AND enabled = 1;

-- ============================================
-- 6. 创建 HNSW 向量索引
-- ============================================

CREATE INDEX idx_knowledge_chunk_embedding_hnsw
    ON t_knowledge_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (
    m = 16,
    ef_construction = 64
    )
    WHERE deleted = 0
    AND enabled = 1
    AND embedding IS NOT NULL;
