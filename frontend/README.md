# AgenticRag 本地前端

<!-- /** 原说明：界面依据当时的 Controller，未修改后端业务代码。当前已同步补齐会话查询。 */ -->
Vue 3 + TypeScript + Vite。前端已适配当前 Session 登录、流式聊天、停止任务和会话历史查询。

## 启动

在 `frontend` 目录运行：

```powershell
pnpm install
pnpm dev
```

打开 http://127.0.0.1:5173 。需要 Node.js 20.19+ 或 22.12+（推荐当前受支持的 LTS 版本）。本机没有全局 pnpm 时，可以双击 `start.cmd`，它会使用本机 Codex 自带的 pnpm。

开发代理：`/api` → `http://127.0.0.1:9091`（agent）；`/knowledge` → `http://127.0.0.1:9090`（rag）。需要分别启动两个后端及其依赖。若端口变化，复制 `.env.example` 为 `.env.local`，修改地址，再重启前端。

## 使用

<!-- /** 原来依赖 Token 登录、显式创建和删除会话；现改为 Session 与首条消息创建会话。 */ -->
- 智能对话：可在登录弹窗或侧栏创建账号；登录后发送首条问题，由后端创建会话。支持会话列表、按问答轮次分页读取历史、POST SSE 文本与思考增量，以及按 taskId 请求停止。删除会话入口暂不展示。
- 登录使用浏览器 Session Cookie，不发送 Bearer Token。退出会调用后端销毁 Session；创建账号不自动登录。前端暂不恢复刷新后的用户信息，刷新页面后需重新登录。
- 知识库：创建、登记已有 ID、重命名和删除；上传文档、登记已有文档 ID、重命名、删除及提交切分。后端没有列表、状态或分块查询接口，因此列表只是本浏览器的操作记录，存于 localStorage，可能过时。登记 ID 不校验后端是否存在；后续操作由后端校验。可导出记录保存 ID。
- 切分请求返回成功仅展示“已提交切分”，不等同于任务完成。没有添加自动重试或重复发送，以免制造重复操作。
- RAG 当前 `local` 环境使用固定 `dev-user` 身份，与 Agent 的登录身份独立。此工作台用于本地开发。
<!-- /** 原管理员账号管理页已停用，保留旧组件但不挂载，也不调用其管理接口。 */ -->
- 账号功能当前只开放创建和登录退出，不区分 user/admin。前端不提供账号列表、角色修改或账号删除。

## 当前 Agent 接口

| 方法 | 路径 | 请求内容 |
| --- | --- | --- |
| POST | `/api/users` | JSON：`username`、`password` |
| POST | `/api/auth/login` | JSON：`username`、`password`；返回 `userId`、`username` |
| POST | `/api/auth/logout` | 无 |
| POST | `/api/agent/chat` | 表单：`query`，可选 `conversationId`；保留同路径 GET 兼容入口 |
| POST | `/api/agent/stop` | 查询参数 `taskId` |
| GET | `/api/conversations` | `pageNo` 默认 1，`pageSize` 默认 30，最多 100 |
| GET | `/api/conversations/{conversationId}/messages` | `limit` 为问题轮数，默认 100；`afterMessageId` 为上一页最后一条 user 消息 ID |

会话与消息查询均从 `UserContext` 获取当前用户，不能读取其他用户的会话。历史按 user 后紧跟其 assistant 回复的顺序返回；一轮尚未生成回复时只返回 user。

运行前需要启动 PostgreSQL，并确认 `database/user.sql`、`database/agent_chat.sql` 对应表结构已存在；配置中不会自动建表。模型配置也需要可用。源码和类型检查不代表真实登录、数据库或模型调用已验证。

## 构建

```powershell
pnpm build
```

此命令仅进行 TypeScript 正确性核对和静态资源构建，不运行测试。没有新增测试样例或测试命令。

构建结果位于 `dist`。正式托管静态资源时需自行配置服务器反向代理，将 `/api` 和 `/knowledge` 转发到上述两个后端；开发代理仅在 Vite 开发服务中生效。

## 文件说明

- `src/api.ts`：统一响应、Session Cookie 请求、页面用户信息和 POST SSE 增量读取。
- `src/components/ChatWorkspace.vue`：会话及聊天。
- `src/components/KnowledgeWorkspace.vue`：知识库、上传和本机操作记录。
- `src/components/UsersWorkspace.vue`：旧管理员账号管理组件，当前不挂载。
- `src/style.css`：页面布局与响应式适配。
