# Agent 会话并发控制

本次沿用现有 `streamChat → readyStream → launchStream`，只增加并发准入和对应的生命周期清理。

## 文件与职责

| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/llll/agent/concurrency/AgentRunGate.java` | 新增；申请会话锁和用户槽位，区分 409、429、503，并补偿申请阶段异常 |
| `src/main/java/com/llll/agent/concurrency/AgentRunLease.java` | 新增；保存两把锁和 owner，控制释放责任移交、一次性释放和释放结果 |
| `src/main/java/com/llll/agent/config/AgentRedisProperties.java` | 新增；绑定单节点 `spring.data.redis` 配置，不接管登录 Session |
| `src/main/java/com/llll/agent/config/AgentRedisConfiguration.java` | 新增；显式创建 RedissonClient，设置认证、超时、Watchdog 和关闭方法 |
| `src/main/java/com/llll/agent/config/AgentProperties.java` | 增加用户并发上限、Watchdog 时间和配置校验 |
| `src/main/java/com/llll/agent/service/impl/AgentChatServiceImpl.java` | 生成数字 taskId，先准入再创建 Agent 和写消息，处理启动失败与责任移交 |
| `src/main/java/com/llll/agent/runtime/AgentRunHandle.java` | 在订阅前接管锁，实际收尾后按顺序关闭资源、移除任务、解锁、完成 SSE |
| `src/main/java/com/llll/agent/runtime/AgentStreamEventBridge.java` | 持久化和事件格式沿用原逻辑；终态暂存到资源清理后发送，不操作锁 |
| `../pom.xml`、`pom.xml` | 固定 Redisson Core 4.6.1，不引入 Redisson Spring Data Redis Starter |
| `../bootstrap/src/main/resources/agenticrag.yaml` | 增加连接与并发配置 |
| `../bootstrap/src/main/resources/agenticrag-local.yaml` | 写入 Docker Redis 的实际认证密码；该文件已经被 Git 忽略 |

原业务代码被替换的部分保留在 `/** ... */` 注释中。没有修改 Controller、SSE 字段、前端、数据库表或 AgentScope 状态存储实现。

## 申请与释放时序

1. 从认证上下文取得 userId，保留 query 和 conversationId 的原有校验。
2. 缺少 conversationId 时仍生成 UUID；每轮 taskId 改用 `IdWorker.getIdStr()`，整个生命周期以字符串传递，仅在锁组件内转成 long owner。
3. 用 `tryLockAsync(owner)` 单次尝试 `agent:run-lock:{userId}:{conversationId}`。忙则同步抛出 HTTP 409，不创建 Agent、不保存消息。
4. 遍历 `agent:run-permit:{userId}:{slot}`，默认 slot 为 0 至 4。全满时释放会话锁并返回 HTTP 429。Redis 命令异常返回 HTTP 503，不绕过准入。
5. 两把锁取得后创建 Lease，再创建 Agent，以原有短事务保存会话和 user 消息。
6. 创建 AgentRun、EventBridge 和 Handle，用 `putIfAbsent` 注册到本 JVM 的 runningTasks。
7. Handle 先绑定移除任务的回调，再将 Lease 标记为已移交，最后注册 SSE 生命周期回调、发送 start 并订阅 Agent 事件流。移交点在订阅之前，与任务结束快慢无关。
8. Service 正常返回不执行解锁。框架执行模型、工具和状态持久化；本实现保留原有状态保存及失败处理行为。
9. Agent 流结束后，Handle 用 finished 的一次性保护进入收尾。EventBridge 保存 assistant 并准备 done/error，暂不发送终态。
10. 依次关闭本轮 Agent 的缓存和关闭钩子、按 `(taskId, handle)` 移除 runningTasks、释放槽位锁、释放会话锁。每项清理失败记录日志并继续后续清理。
11. 解锁处理结束后才发送终态、完成 SSE。释放失败会产生 `cleanup_error`，并以异常完成连接，避免表现为正常成功。

正常框架执行中的状态保存发生在应用收到流终止之前；不会再在 Handle 中重复保存状态。关闭 Agent 不会删除数据库内的 AgentState。

## 异常与竞争

| 场景 | 处理 |
| --- | --- |
| 会话锁忙 | 返回 409，未进入业务准备阶段 |
| 槽位全满 | 释放会话锁，返回 429 |
| 加锁命令异常、响应超时 | 对本次尝试的锁也按相同 owner 补偿，再释放之前成功取得的锁；不能假定超时就是未加锁 |
| Agent 创建、短事务、prepareRun、Handle 创建或注册失败 | 尚未移交时由 Service 关闭已创建的 Agent、移除本次注册并释放 Lease；关闭失败不会跳过外层解锁 |
| 移交后的同步启动异常 | Handle 进入与异步结束相同的 finish，Service 不再重复关闭或解锁 |
| 正常完成、模型/工具异常、事件处理异常 | 仍沿用原有 assistant 持久化与协作中断路径，最终统一释放 |
| 主动停止、SSE 超时、断连 | 只请求协作中断，不立即解锁；等实际流终止和业务收尾完成 |
| 正常结束与超时回调竞争 | finished 只允许一次收尾；Lease 的同步释放只执行一次，重复调用返回首次释放结果 |
| 旧任务延迟释放 | 解锁始终调用 `unlockAsync(owner)`，由 Redisson 校验客户端与逻辑 owner；不使用 delete 或 forceUnlock |
| 单把锁解锁失败 | 记录 key、owner 和异常，仍尝试另一把锁；本轮连接报告资源清理异常 |
| JVM 崩溃 | Watchdog 停止续期，残留锁等待 TTL 过期 |

Redisson 4.6.1 的 `RedissonBaseLock.unlockAsync0` 在异步解锁结果回调中取消对应 owner 的续期，包括 Redis 命令异常的分支。此处不增加无限重试或强制删除；异常时保留日志，等待残留 TTL 过期。应用没有自己调用 Redisson 的内部续期接口。

如果短事务已经提交，后续启动失败不会自动删除已保存的 user 消息，这与原聊天准备流程一致。并发锁不提供消息和 AgentState 的跨步骤事务。

## Docker Redis 与配置

本机 `redis` 容器已启用密码认证，容器 6379 映射到宿主机 6379。后端运行在 Windows，因此连接 `127.0.0.1:6379`，使用数据库 0。

公共配置提供以下环境变量入口：

| 配置 | 默认值或来源 |
| --- | --- |
| `spring.data.redis.host` | `REDIS_HOST`，默认 `127.0.0.1` |
| `spring.data.redis.port` | `REDIS_PORT`，默认 `6379` |
| `spring.data.redis.database` | `REDIS_DATABASE`，默认 `0` |
| `spring.data.redis.username` | `REDIS_USERNAME`，默认不显式指定 ACL 用户 |
| `spring.data.redis.password` | `REDIS_PASSWORD`；local 配置以实际容器密码作为本机默认值 |
| `spring.data.redis.connect-timeout` | `3s` |
| `spring.data.redis.timeout` | `3s` |
| `agent.max-concurrent-runs-per-user` | `AGENT_MAX_CONCURRENT_RUNS_PER_USER`，默认 `5`，必须大于 0 |
| `agent.lock-watchdog-timeout-ms` | `AGENT_LOCK_WATCHDOG_TIMEOUT_MS`，默认 `30000`，至少 1000 毫秒 |

不设置固定 leaseTime，Watchdog 按其机制续期。客户端不重试失败的 Redis 命令；两次加锁用应用补偿衔接。Redisson 本身的锁实现使用内部脚本，应用未增加自定义 Lua。

RedissonClient 随 Spring Bean 初始化连接，初始认证或连接失败会导致启动失败；运行期间 Redis 不可用则拒绝新任务。Spring 关闭时调用客户端 shutdown。

## 当前边界

- Redis 续期失败、长时间 JVM 停顿或 Redis 数据丢失后，旧 Agent 不一定停止。没有实现 fencing token、版本写入屏障或旧任务写库隔离，不能宣称严格的分布式执行隔离。
- 若协作中断一直不能完成，锁会继续续期。本次不增加强制取消超时；提前解锁可能让新旧任务同时写入同一会话。
- 客户端断连、容器 SSE 超时可能先结束网络连接；此时继续等待 Agent 收尾后释放锁，不能保证客户端仍能收到终态事件。
- Redis 锁可供多个实例共享准入，但实例必须使用同一 Redis 数据库、键规则和用户并发上限。运行中变更上限不主动终止已有任务。
- runningTasks 和登录 Session 仍在单 JVM 内，没有跨节点停止、共享登录会话或任务恢复；多实例部署需要单独处理路由与认证。
- AgentScope 原有状态持久化失败语义不变；本次不为数据库写入增加事务重试或保证失败状态一定保存成功。
- 未新增队列、任务表、调度器、Semaphore 或人工确认流程，也没有自动推送或合并代码。

## 核对范围

按项目约束未编写或运行单元测试、Mockito 测试或 Redis 锁集成测试。只读取了 Docker 和 Redis 的运行配置，未在实际 Redis 上执行加锁、并发或 Watchdog 场景。

兼容性核对使用 JDK 21，执行 Maven compile，并显式设置 `maven.test.skip=true` 和项目所需的 `maven.compiler.proc=full`；没有执行 test、verify 或测试编译阶段。编译成功只证明源码和依赖可编译，不代表已验证真实 Redis 的锁行为或完整模型对话。

参考源码：

- [ragent AgentRunGate](https://github.com/nageoffer/ragent/blob/main/agent/src/main/java/com/nageoffer/ai/ragent/agent/service/handler/AgentRunGate.java)
- [Redisson 4.6.1 RedissonBaseLock](https://github.com/redisson/redisson/blob/redisson-4.6.1/redisson/src/main/java/org/redisson/RedissonBaseLock.java)
- [Redisson 配置文档](https://redisson.pro/docs/configuration/)
