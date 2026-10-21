# Jev 知识库路由交付说明

## 调用链与职责

```text
AgentScope → KnowledgeSearchTool → RagService.search
  → KnowledgeBaseRouter
      → KnowledgeBaseMapper：查询未删除知识库的 id、name、description
      → JevClient：一次提交全部候选的 Noul 判断
      → Java 阈值决策：SINGLE / MULTI / GLOBAL / NONE
  → NONE：直接返回空列表，不提交召回任务
  → 其他模式：原有 PG 向量召回与 ES BM25 并行执行
  → 原有 RRF 去重
  → SINGLE / MULTI 候选不足时执行一次 GLOBAL 召回
  → 原有 Rerank 与最终 TopK 截取
```

`JevClient` 只负责 HTTP 和协议，不决定路由。每个知识库独立判断，使用请求内的 `questionId → kbId` 映射还原结果；概率不归一化。提示词集中在客户端中维护，知识库描述作为待判断数据。

`KnowledgeBaseRouter` 负责读取元数据、调用客户端、排序和选择知识库。概率达到 mid-threshold 的知识库为空时返回 NONE；一个且达到 high-threshold 时返回 SINGLE；至少两个时返回 MULTI；只有一个且未达到 high-threshold 时返回 GLOBAL。同分按知识库 ID 排序。

`RetrievalScope` 统一传给 PG、ES。关闭路由时 unrestricted=true，沿用原有全局检索；启用路由后即使模式是 GLOBAL，也传入本次请求读取到的可用知识库集合。受限空集合始终表示不检索。

`RecallRoundResult` 保存每轮两路结果及已经完成的 Query Embedding。向量缓存仅存在于本次请求内；兜底使用单独的状态对象，读取首次完成时的向量快照，不再次生成向量，也不接收已取消旧任务的迟到写入。

## 数据库与知识库 API

部署 Java 代码之前，手动执行 `database/knowledge_base_description.sql`。应用沿用现有配置，不会在启动时修改数据库。SQL 增加可空的 `description VARCHAR(1000)`，允许重复执行。

即使两个路由开关关闭，也必须先增加字段，因为知识库实体查询已经包含 description。

- POST `/knowledge/base`：可选 description，不提供时为 NULL。
- PUT `/knowledge/base/{kb-id}`：沿用现有 name 必填要求；description 不提供或为 null 时保留旧值，空字符串或全空白时清空为 NULL。
- GET 知识库详情、分页：返回 description。
- Service 校验描述不超过 1000 个 Unicode 字符，非空描述去掉首尾空白。

description 不做 Embedding，不写入 ES，不改变 collection、知识库 ID、文档入库或双写流程。

## 配置与启用

实际统一启动配置为 `bootstrap/src/main/resources/agenticrag.yaml`，新增内容如下：

```yaml
jev:
  enabled: ${JEV_ENABLED:false}
  base-url: ${JEV_BASE_URL:https://api.typesafe.ai}
  api-key: ${JEV_API_KEY:}
  model: ${JEV_MODEL:jev-latest}
  connect-timeout: 1500ms
  request-timeout: 3s
  max-response-bytes: 2097152

rag:
  routing:
    enabled: ${RAG_ROUTING_ENABLED:false}
    high-threshold: 0.7
    mid-threshold: 0.5
    fallback-min-chunks: 3
```

两个 enabled 都为 true 时才调用 Jev。任意一个关闭时跳过元数据查询和 Jev，沿用原检索范围。

需要配置 `JEV_API_KEY`，并将 `JEV_ENABLED`、`RAG_ROUTING_ENABLED` 设为 true。可选环境变量为 `JEV_BASE_URL`、`JEV_MODEL`。阈值和超时可直接修改上述 YAML，或使用 Spring Boot 对应的配置覆盖方式。

base-url 为服务根地址，客户端追加 `/v1/systemone`，使用 Bearer 认证。客户端独立管理 HTTP 连接，不复用 Rerank 的密钥或超时；连接、连接池等待和响应超时有界，关闭自动重试和重定向，限制响应体大小。

启用路由但没有配置密钥时记录明确警告，降级到可用知识库 GLOBAL，不发出无凭据请求。启用时非法阈值会阻止启动；必须满足 `0 <= mid < high <= 1`，且 `1 <= fallback-min-chunks <= fusion-top-k`。

## 故障、兜底和访问边界

- Jev 返回 401/403、429、5xx、连接或读取失败、空响应、非法 JSON、答案缺失、错误类型、非数字、非有限或越界概率，统一按调用失败降级 GLOBAL；不将这些情况视为 NONE。
- Jev 成功返回全部低分时才走 NONE；当前没有可用知识库也直接结束。
- 元数据读取失败时无法确认可用范围，返回现有的检索不可用异常，由 Agent Tool 转为不可用提示，不扩大范围或误报未命中。
- 只有 SINGLE、MULTI 且 RRF 去重后候选不足时，执行一次全局兜底；GLOBAL、NONE 不兜底。
- 兜底不重新调用 Jev 或 Embedding。首次没有可复用向量时，兜底的向量通道报告失败，关键词通道仍可执行。
- 全局召回取得有效候选时替换首次集合，再走原有 RRF、Rerank，不合并两轮排名、不重复计分。
- 全局召回失败且无候选时保留首次证据。最终没有证据且有通道故障时沿用 RetrievalUnavailableException，不宣称知识库没有资料。
- 每轮使用现有召回超时和有界执行器；Jev 单独计时，兜底具有单独一轮召回预算。用户取消不会被当成 Jev 故障后继续全局检索。
- 当前是共享公共知识库，没有知识库级 ACL。createBy 是审计字段，不是权限。若未来增加 ACL，必须同时收紧元数据查询和各调用方的检索范围。

PG 保留 Chunk 启用/删除、文档启用/删除/成功状态和知识库删除检查。ES 保留已有 Chunk 状态过滤，按现有写入字段 kbId 使用 bool.filter 下的 term/terms。ES 与 PG 的文档状态一致性仍沿用现有同步机制，不新增每次召回强制回查 PG 的依赖。

启用后的可用库列表是请求开始时的快照，不跨外部模型调用持有数据库锁；并发删除和 ES 索引可见性仍受现有同步机制影响。

每次路由记录 routingMode、candidateKbCount、selectedKbIds、jevDurationMs、routingFallback、retrievalFallback；candidateKbCount 为送入判断的知识库数量。默认不记录完整问题、描述或密钥。模式仍记录最初路由结果，由 retrievalFallback 标识是否扩大到全局。

## 文件清单

新增：

- `database/knowledge_base_description.sql`
- `rag/src/main/java/com/llll/rag/search/routing/JevProperties.java`
- `rag/src/main/java/com/llll/rag/search/routing/RagRoutingProperties.java`
- `rag/src/main/java/com/llll/rag/search/routing/JevCallException.java`
- `rag/src/main/java/com/llll/rag/search/routing/JevClient.java`
- `rag/src/main/java/com/llll/rag/search/routing/KnowledgeBaseRouter.java`
- `rag/src/main/java/com/llll/rag/search/routing/model/KnowledgeBaseMeta.java`
- `rag/src/main/java/com/llll/rag/search/routing/model/KbScore.java`
- `rag/src/main/java/com/llll/rag/search/routing/model/RouteMode.java`
- `rag/src/main/java/com/llll/rag/search/routing/model/RouteDecision.java`
- `rag/src/main/java/com/llll/rag/search/routing/model/RetrievalScope.java`
- `rag/src/main/java/com/llll/rag/search/model/RecallRoundResult.java`
- `rag/docs/jev-routing.md`

修改：

- `rag/src/main/java/com/llll/rag/knowledge/dao/entity/KnowledgeBaseDO.java`
- `rag/src/main/java/com/llll/rag/knowledge/dao/mapper/KnowledgeBaseMapper.java`
- `rag/src/main/java/com/llll/rag/knowledge/controller/request/KnowledgeBaseCreateRequest.java`
- `rag/src/main/java/com/llll/rag/knowledge/controller/request/KnowledgeBaseUpdateRequest.java`
- `rag/src/main/java/com/llll/rag/knowledge/controller/vo/KnowledgeBaseVO.java`
- `rag/src/main/java/com/llll/rag/knowledge/service/serviceImpl/KnowledgeBaseServiceImpl.java`
- `rag/src/main/java/com/llll/rag/knowledge/dao/mapper/RagSearchMapper.java`
- `rag/src/main/java/com/llll/rag/search/retriever/VectorRetriever.java`
- `rag/src/main/java/com/llll/rag/search/retriever/KeywordRetriever.java`
- `rag/src/main/java/com/llll/rag/search/retriever/impl/PgVectorRetriever.java`
- `rag/src/main/java/com/llll/rag/search/retriever/impl/EsBm25Retriever.java`
- `rag/src/main/java/com/llll/rag/service/serviceImpl/RagServiceImpl.java`
- `bootstrap/src/main/resources/agenticrag.yaml`
- `rag-eval/java/src/main/java/com/llll/eval/EvaluationApplication.java`

## 验证边界

按工作约束不编写或运行测试，也不运行评测。只进行静态正确性核对和跳过测试阶段的业务代码编译。

已完成的核对：framework、rag、agent、bootstrap 及独立 rag-eval-runner 以 Java 21 目标编译通过；所有编译均仅执行 compile 阶段并设置 maven.test.skip=true。没有执行 test-compile、test 或评测入口。现有主 POM 的模块列表和依赖方向保持不变，独立入口通过本地 tmp 目录下的临时聚合 POM 一并编译。

动态 SQL 的两个生效 script 块均通过 XML 语法解析，向量距离运算符使用正确的 XML 转义；git diff --check 通过。静态审查已核对 NONE 的提前返回、两路范围一致、仅一次兜底、首次向量复用、缺失密钥与非法响应降级，以及原有调用方的装配。

未执行数据库 DDL，未调用真实 Jev API。当前无法连接配置的 ES 地址，因此未完成实际 Mapping 核对和 PG/ES 检索联调；启用前应确认现有索引 kbId 为支持精确过滤的 keyword 字段。没有重建索引、修改双写或改变解析分块流程。

阈值是初版配置，没有用真实业务数据校准，不能将编译通过解释为路由准确率或外部服务可用性证明。
