export interface User {
  userId?: string;
  id?: string;
  username: string;
  /** 原来角色为必填；当前 Session 登录只返回用户 ID 和用户名。 */
  role?: string;
}

export interface Page<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface Conversation {
  conversationId: string;
  title: string;
  createTime: string;
  updateTime: string;
}

export interface Message {
  id: string;
  role: string;
  content: string;
  thinkingContent?: string;
  status?: string;
  lastMessageId?: string;
}

export interface StreamEvent {
  conversationId: string;
  /** 原来每个事件都要求两个消息 ID；现在 start 和终止事件分别提供对应 ID。 */
  taskId: string;
  title?: string;
  userMessageId?: string | null;
  assistantMessageId?: string | null;
  sequence?: number;
  delta?: string;
  status?: string;
  reason?: string;
  message?: string;
}

export interface KnowledgeBase {
  id: string;
  name: string;
  collection: string;
  embeddingModel: string;
}

export interface KnowledgeDocument {
  id: string;
  kbId: string;
  docName: string;
  fileSize?: number;
  fileType?: string;
  status?: string;
  submittedAt?: string;
}
