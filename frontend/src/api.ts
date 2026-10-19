import { ref } from "vue";
import type { StreamEvent, User } from "./types";

export const currentUser = ref<User | null>(null);

export function setSession(user: User): void {
  currentUser.value = user;
}

export function clearSession(): void {
  currentUser.value = null;
}

function headersFor(body?: BodyInit | null): Headers {
  const headers = new Headers();
  if (body && !(body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  return headers;
}

/** 登录失败与已有 Session 失效分别处理，避免把密码错误提示成登录过期。 */
async function responseError(response: Response, loginRequest = false): Promise<Error> {
  let message = `请求失败（${response.status}），请检查后端是否已启动`;
  try {
    const result = await response.json();
    /** 兼容统一 Result 和 Spring 的 ProblemDetail 错误响应。 */
    message = result.message || result.detail || message;
  } catch {
    // 代理失败或非 JSON 响应使用可读的通用说明。
  }
  if (response.status === 401) {
    if (loginRequest) {
      message = "用户名或密码错误";
    } else {
      clearSession();
      message = "登录已失效，请重新登录";
    }
  }
  return new Error(message);
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(path, {
    ...options,
    credentials: "same-origin",
    headers: headersFor(options.body),
  });
  if (!response.ok) {
    throw await responseError(response, path === "/api/auth/login");
  }
  const result = await response.json();
  if (String(result.code) !== "0") {
    throw new Error(result.message || "操作未完成");
  }
  return result.data as T;
}

export function jsonBody(value: unknown): string {
  return JSON.stringify(value);
}

/** POST SSE：保留跨网络分片的 UTF-8 字符和事件边界。 */
export async function streamChat(
  conversationId: string,
  content: string,
  signal: AbortSignal,
  onEvent: (name: string, event: StreamEvent) => void,
): Promise<void> {
  const body = new URLSearchParams();
  body.set("query", content);
  if (conversationId) {
    body.set("conversationId", conversationId);
  }
  const headers = new Headers();
  headers.set("Accept", "text/event-stream");
  headers.set("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
  const response = await fetch(
    "/api/agent/chat",
    {
      method: "POST",
      body,
      headers,
      signal,
      credentials: "same-origin",
      cache: "no-store",
    },
  );
  if (!response.ok) {
    throw await responseError(response);
  }
  if (
    !response.headers.get("content-type")?.includes("text/event-stream") ||
    !response.body
  ) {
    throw new Error("后端未返回流式响应，请检查 Agent 服务");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let terminalReceived = false;
  try {
    while (true) {
      const chunk = await reader.read();
      buffer += decoder.decode(chunk.value, { stream: !chunk.done });
      let separator = /\r?\n\r?\n/.exec(buffer);
      while (separator) {
        const frame = buffer.slice(0, separator.index);
        buffer = buffer.slice(separator.index + separator[0].length);
        let name = "message";
        const data: string[] = [];
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith("event:")) {
            name = line.slice(6).trim();
          }
          if (line.startsWith("data:")) {
            data.push(line.slice(5).trimStart());
          }
        }
        if (data.length > 0) {
          onEvent(name, JSON.parse(data.join("\n")) as StreamEvent);
          if (name === "done" || name === "error") {
            terminalReceived = true;
          }
        }
        separator = /\r?\n\r?\n/.exec(buffer);
      }
      if (chunk.done) {
        break;
      }
    }
    if (!terminalReceived) {
      throw new Error(
        "连接已断开，回复状态尚未确认，请重新打开会话查看已保存内容",
      );
    }
  } catch (failure) {
    try {
      // 事件解析失败时结束这次传输，让后端有机会感知断线并协作中断。
      await reader.cancel();
    } catch {
      // 请求已经被 AbortController 结束时，取消 reader 也可能失败。
    }
    throw failure;
  } finally {
    reader.releaseLock();
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return "操作失败，请稍后重试";
}
