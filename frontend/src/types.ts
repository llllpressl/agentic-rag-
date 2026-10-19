export interface User {
  userId?: string;
  username: string;
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
