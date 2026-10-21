-- 由部署者在发布包含 description 的 Java 代码前执行；应用不自动修改数据库。
-- 允许重复执行，不修改知识库的其他字段及约束。
ALTER TABLE t_knowledge_base
ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
