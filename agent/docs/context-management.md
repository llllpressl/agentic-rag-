# Agent 会话上下文管理实施说明

## 范围和调用链

当前实现继续使用 AgentScope Java **2.0.4** 的 `ReActAgent`、现有 `PgAgentStateStore`、Redisson 会话锁和 SSE。

主路径：

`prepareRun → 框架加载 AgentState 并追加本轮 User → onReasoning → 字符统计 → 单一策略 → 构造候选上下文 → 重建 ReasoningInput 并检查硬上限 → 提交 AgentState.context → DeepSeek → 框架保存状态 → 业务保存 assistant → 关闭 Agent → 释放会话锁`

Middleware 的运行标志放在本轮 `RuntimeContext`，单例组件不保存某个用户的上下文。没有从 `t_agent_message` 重新拼接历史，没有新增数据库表，也没有修改检索工具的单次长度限制。

## 修改文件

| 文件 | 职责 |
| --- | --- |
| `memory/AgentMemoryProperties.java` | 预算、白名单、近期额度、摘要长度及超时配置 |
| `memory/AgentContextChars.java` | 累计文本、Thinking、工具名称/JSON 参数、工具输出和摘要的 `String.length()` |
| `memory/AgentContextTrimmer.java` | 按工具 ID 划分循环，生成只替换历史白名单工具 output 的候选消息 |
| `memory/AgentContextCompactor.java` | 寻找完整轮次切点，合并旧摘要和新历史，构造唯一的新摘要 |
| `memory/AgentConversationSummarizer.java` | 单独调用现有 DeepSeek Model，移除素材中的 Thinking，校验摘要结果 |
| `memory/AgentContextCompactionMiddleware.java` | 选择策略、保留框架前缀、同步上行消息和工作状态、拦截超限请求 |
| `memory/AgentContextLimitException.java` | 可识别的上下文超限异常 |
| `config/ReActAgentProvider.java` | 在原 builder 上追加注册 Middleware |
| `runtime/AgentStreamEventBridge.java` | 追加超限异常到既有 reason 字段的映射 |
| `bootstrap/src/main/resources/agenticrag.yaml` | 追加 `agent.memory` 配置 |

表中 Java 文件均位于 `agent/src/main/java/com/llll/agent/` 下。现有文件只追加接线、配置和错误细分，未覆盖原实现。

## 水位和保护规则

默认配置：总预算 100000 字符，裁剪水位 50000，摘要水位 80000，近期保护额度 20000，摘要正文最多 5000 字符，摘要输出最多 8192 Token，摘要调用超时 60000 毫秒。

这组字符水位不等于精确 Token 预算。固定 System Prompt 不计入字符水位；工具定义、消息格式及输出还有额外开销，DeepSeek 服务的实际 Token 窗口仍然是独立约束。当前没有引入精确 Tokenizer，也不宣称已经实现完整请求的精确 Token 预算。

每轮第一次推理且上下文最后一条是真实 User 消息时才管理历史：

| 长度 | 策略 |
| --- | --- |
| `< 50000` | 原样继续 |
| `>= 50000 && < 80000` | 只裁剪历史白名单工具结果 |
| `>= 80000` | 只尝试摘要；禁用、失败或没有安全切点时不降级裁剪 |
| 最终实际上行消息 `> 100000` | 抛出超限异常，不发送该模型请求 |

本轮后续工具循环不再裁剪或摘要，但每次真实模型调用都检查硬上限。`onModelCall` 也覆盖框架达到最大迭代次数后的收尾模型调用；`onAgent` 在框架把该异常转换为收尾结果的情况下，仍将超限错误交回业务层。

### 工具裁剪

- 白名单默认仅 `search_knowledge`。
- 当前用户轮次、最近两个已完成的工具调用循环，以及所有未闭合循环都受保护；RUNNING 和挂起结果不视作闭合。
- 工具调用的名称和参数不动；结果只替换 output，保留 id、name、metadata、state，普通 User/Assistant/Thinking 不动。
- 占位文字为 `[历史工具结果已省略，如有需要请重新检索]`，只有原 output 比占位更长才替换；该占位再次进入裁剪时会直接跳过。
- 使用 `Msg.withContent()` 构造新消息，所有候选先构造完成，再由 Middleware 提交。

### 滚动摘要

- 当前轮完整保留，且不计入近期 20000 字符额度。
- 从当前轮向前累计已完成历史轮次，直到至少 20000 字符，跨边界的一整轮完整保留；不足额度时保留全部历史。
- 更早的未完成轮次额外保护，从该轮开始的整个后缀保留，避免拆开工具调用链。
- 旧摘要不占近期额度，但计入总预算。素材为“上一份摘要 + 本次可压缩的完整旧轮次”。
- 回填一条 `role=USER, name=__compaction_summary__` 的背景消息，明确声明它不是当前用户指令；保留段仍使用原来的 Msg 对象及 Thinking 内容。
- 摘要素材按 JSON 保留 role/name、工具调用 ID 和输出，不包含 Thinking。摘要调用不传工具定义，关闭 Thinking，不运行 ReAct 工具循环。
- 接受条件：模型以 `stop` 完成、正文非空、不超过摘要上限、包含五个必要小节、没有工具调用；新上下文必须减少字符数，且实际请求通过硬上限检查。
- 摘要请求序列化后的素材同样受字符预算约束；超大素材不能通过摘要调用绕过保护。
- 不满足条件、超时或异常时保留原上下文。若原请求已经超限，则终止本轮并通过既有 SSE reason 返回 `context_limit_exceeded`。

## 2.0.4 API 核对及持久化

本次阅读的是本机 Maven 缓存中的 `agentscope-core-2.0.4-sources.jar` 和 `agentscope-extensions-model-openai-2.0.4-sources.jar`，没有改依赖库源码。

- `AgentState.contextMutable()` 返回工作消息的可变列表。
- `PreReasoningEvent` 复制列表但不复制 Msg；`MessageUtils.prependSystemMessage()` 在前方附加 System Prompt。因此通过尾部 Msg 引用逐条核对可以识别框架前缀。
- `ReasoningInput` 是消息、工具定义和生成选项三个字段的 record。重建消息时保留原来的工具定义、选项和框架前缀，不能只改 AgentState。
- 原始工具输出的 Thinking、ToolUse 和 ToolResult 配对不会被单独拆开；保留段继续由现有 `DeepSeekFormatter` 处理思考模式的 `reasoning_content`。
- `ReActAgent.doCall()` 正常完成、空完成和普通失败都会执行状态保存；普通失败保存的是已进入 AgentState 的安全消息，不包括尚未完成的模型输出片段。
- 协作中断走 `handleInterrupt()`：先补齐未配对工具结果，再追加中断恢复消息并保存。现有 `AgentRunHandle` 保留订阅等待协作中断完成，本次没有改成直接取消订阅。
- 因此当前路径不额外调用 `saveAgentState()`，避免抢在框架的工具配对修复之前保存中间状态。
- 框架保存结束后，运行句柄才关闭 Agent、移除本轮活动任务，最后释放会话锁。保存失败时仍按现有异常链路报告/记录，再清理释放，没有提前释放锁。
- 下一轮按原来的 `userId + sessionId + agent_state` 加载状态。摘要和保留消息通过现有 Msg/ContentBlock codec 保存到 `t_agent_state.all_content`，不需要新表或从业务消息表补历史。

## 正确性核对和验证范围

遵守仓库要求：**未编写测试样例，未运行测试，未调用真实 DeepSeek，也未执行数据库或 SSE 场景测试。** 以下是源码核对结论，不是运行验收结果。

| 任务验收项 | 源码核对依据 |
| --- | --- |
| 低于 50000 不处理，达到 50000 进入裁剪 | Middleware 使用 `< trimTrigger` 返回原请求 |
| 白名单、当前轮及近期循环保护 | Trimmer 按调用 ID 追踪 pending，跳过当前轮和两个最近完成循环 |
| 保留工具 ID 和属性 | 只重建 output，复制原 id/name/metadata/state，ToolUse 未修改 |
| 80000 优先摘要且失败不裁剪 | 单一分支判断；错误恢复只返回原 snapshot |
| 完整保护最近至少 20000 字符历史 | Compactor 以真实 User 起点划分轮次，只累计已完成轮次，当前轮和旧摘要不计入 |
| 多代摘要仅保留一条 | 压缩前缀取出旧摘要，候选列表仅加入一条新摘要及保留后缀 |
| 失败无半成品 | 裁剪器和压缩器不修改输入；上行列表、字符数校验通过后统一提交 |
| Thinking 与工具协议 | 只从摘要素材去掉 Thinking，原保留段不动；压缩前核对工具配对 |
| 模型实际使用新上下文 | 用框架前缀加候选上下文构造新 ReasoningInput 后调用 next |
| PostgreSQL 正常保存及恢复 | 沿用框架 save/get 路径及现有状态 codec；尚无本次真实数据库验证 |
| 停止、断线、异常、关闭和锁顺序 | 沿用 AgentRunHandle 和框架协作中断，核对安全状态保存发生在关闭/释放之前；尚无本次运行验证 |
| 锁、taskId、SSE | 未改锁及 taskId 生命周期；SSE 只在已有 reason 字段细分超限错误 |

编译入口：

```powershell
.\mvnw.cmd -B -ntp -pl bootstrap -am '-Dmaven.test.skip=true' '-Dmaven.compiler.proc=full' compile
```

已使用本机 JDK 21（`C:\Users\Lijianwen\.jdks\ms-21.0.12.1`）编译通过全部五个 Maven 模块，结果为 `BUILD SUCCESS`。检查期间也用 JDK 25、Java 21 release 编译通过。首次默认编译因 JDK 25 未自动执行 Lombok 注解处理失败，显式开启注解处理后通过，没有为此修改项目 POM。

## 数据保留边界

`t_agent_message` 中既有用户消息和已保存助手回复不被裁剪或删除；前端可继续展示业务聊天记录。但业务表不保证包含所有中间工具过程。被占位替换的工具正文和被摘要替代的旧工作消息没有独立归档，不能承诺完整恢复压缩前的执行历史。

本次没有新增补偿保存机制、长期记忆、审计表或原始执行日志归档。进程在框架保存前被强制终止、数据库不可用等情况仍受原有持久化边界约束。用户在摘要模型调用期间停止任务时仍采用既有协作中断，可能需要等待摘要调用返回或超时；不提前清缓存或释放会话锁。

## 参考实现

已阅读本机 `D:\Program\ragent` 中的六个上下文管理核心类，参考仓库为 [nageoffer/ragent](https://github.com/nageoffer/ragent)，本机参考版本 `ed05927188512e6bc94d78fd19a2ef082f9c7873`。借鉴字符水位、工具循环保护、完整轮次前缀摘要及框架前缀重建；按本任务删除了参考实现的摘要失败降级裁剪、摘要审计落库等行为。
