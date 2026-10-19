<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import {
  currentUser,
  errorMessage,
  request,
  streamChat,
} from "../api";
import type { Conversation, Message, Page, StreamEvent } from "../types";
import Icon from "./Icon.vue";
import MessageContent from "./MessageContent.vue";

const emit = defineEmits<{ login: []; notify: [message: string] }>();
const conversations = ref<Conversation[]>([]);
const messages = ref<Message[]>([]);
const selectedId = ref("");
const content = ref("");
const loading = ref(false);
const sending = ref(false);
const stopping = ref(false);
const activeTaskId = ref("");
const loadingConversations = ref(false);
const error = ref("");
const conversationPage = ref(1);
const conversationTotal = ref(0);
const hasMoreMessages = ref(false);
const needsReload = ref(false);
const search = ref("");
const messageArea = ref<HTMLElement | null>(null);
let controller: AbortController | null = null;
let selectionVersion = 0;
let disposed = false;
const selectedTitle = computed(
  () =>
    conversations.value.find((item) => item.conversationId === selectedId.value)
      ?.title || "新的灵感，从这里开始",
);
const filtered = computed(() =>
  conversations.value.filter((item) =>
    (item.title || "未命名对话")
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
const suggestions = [
  {
    icon: "file",
    title: "理清一个复杂概念",
    text: "请用通俗的语言解释 RAG 的工作流程，并给出一个具体例子。",
    caption: "把复杂的问题，拆成清楚的步骤",
  },
  {
    icon: "spark",
    title: "一起探索新想法",
    text: "我想做一个个人知识管理工具，请帮我梳理核心使用场景。",
    caption: "从一个念头，找到更多可能",
  },
  {
    icon: "edit",
    title: "打磨一段表达",
    text: "请帮我润色接下来提供的内容，让表达更清晰、自然。",
    caption: "让每一次表达都更清晰",
  },
];

async function scrollBottom(): Promise<void> {
  await nextTick();
  if (messageArea.value) {
    messageArea.value.scrollTop = messageArea.value.scrollHeight;
  }
}

async function loadConversations(append = false): Promise<void> {
  if (!currentUser.value || loadingConversations.value || sending.value) {
    return;
  }
  const user = currentUser.value;
  loadingConversations.value = true;
  try {
    const nextPage = append ? conversationPage.value + 1 : 1;
    const data = await request<Page<Conversation>>(
      `/api/conversations?pageNo=${nextPage}&pageSize=30`,
    );
    if (disposed || currentUser.value !== user) {
      return;
    }
    conversations.value = append
      ? [...conversations.value, ...data.records]
      : data.records;
    conversationPage.value = nextPage;
    conversationTotal.value = data.total;
  } catch (failure) {
    if (!disposed && currentUser.value === user) {
      error.value = errorMessage(failure);
    }
  } finally {
    loadingConversations.value = false;
  }
}

async function loadMessages(append = false): Promise<void> {
  const conversationId = selectedId.value;
  if (!conversationId || !currentUser.value) {
    return;
  }
  const version = ++selectionVersion;
  loading.value = true;
  error.value = "";
  try {
    let cursor = "";
    if (append) {
      const questions = messages.value.filter((item) => item.role === "user");
      cursor = questions[questions.length - 1]?.id || "";
    }
    const query = cursor ? `&afterMessageId=${encodeURIComponent(cursor)}` : "";
    const data = await request<Message[]>(
      `/api/conversations/${encodeURIComponent(conversationId)}/messages?limit=100${query}`,
    );
    if (disposed || version !== selectionVersion) {
      return;
    }
    messages.value = append ? [...messages.value, ...data] : data;
    needsReload.value = false;
    hasMoreMessages.value =
      data.filter((item) => item.role === "user").length === 100;
    await scrollBottom();
  } catch (failure) {
    if (!disposed && version === selectionVersion) {
      error.value = errorMessage(failure);
      needsReload.value = true;
    }
  } finally {
    if (version === selectionVersion) {
      loading.value = false;
    }
  }
}

function newConversation(): void {
  if (sending.value) {
    return;
  }
  selectionVersion += 1;
  selectedId.value = "";
  messages.value = [];
  loading.value = false;
  content.value = "";
  error.value = "";
  hasMoreMessages.value = false;
  needsReload.value = false;
  activeTaskId.value = "";
}

async function selectConversation(conversation: Conversation): Promise<void> {
  if (sending.value) {
    return;
  }
  selectedId.value = conversation.conversationId;
  messages.value = [];
  await loadMessages();
}

async function send(): Promise<void> {
  if (
    !content.value.trim() ||
    sending.value ||
    loading.value ||
    loadingConversations.value ||
    hasMoreMessages.value ||
    needsReload.value
  ) {
    return;
  }
  if (!currentUser.value) {
    emit("login");
    return;
  }
  const question = content.value.trim();
  sending.value = true;
  error.value = "";
  activeTaskId.value = "";
  stopping.value = false;
  let assistant: Message | undefined;
  let accepted = false;
  try {
    content.value = "";
    messages.value.push({
      id: "pending-user",
      role: "user",
      content: question,
    });
    messages.value.push({
      id: "pending-assistant",
      role: "assistant",
      content: "",
      thinkingContent: "",
      status: "generating",
    });
    assistant = messages.value[messages.value.length - 1];
    const userMessage = messages.value[messages.value.length - 2];
    controller = new AbortController();
    await scrollBottom();
    await streamChat(
      selectedId.value,
      question,
      controller.signal,
      (name: string, event: StreamEvent) => {
        if (disposed || !assistant) {
          return;
        }
        if (name === "start") {
          if (!event.conversationId || !event.taskId || !event.userMessageId) {
            throw new Error("本轮对话缺少任务信息，请刷新会话列表核对");
          }
          accepted = true;
          selectedId.value = event.conversationId;
          activeTaskId.value = event.taskId;
          userMessage.id = event.userMessageId;
          assistant.lastMessageId = event.userMessageId;
          const index = conversations.value.findIndex(
            (item) => item.conversationId === event.conversationId,
          );
          if (index < 0) {
            conversations.value.unshift({
              conversationId: event.conversationId,
              title: event.title || question.slice(0, 20),
              createTime: "",
              updateTime: "",
            });
            conversationTotal.value += 1;
          }
        }
        if (name === "text.delta") {
          assistant.content += event.delta || "";
        }
        if (name === "thinking.delta") {
          assistant.thinkingContent =
            (assistant.thinkingContent || "") + (event.delta || "");
        }
        if (name === "done" || name === "error") {
          activeTaskId.value = "";
          if (event.assistantMessageId) {
            assistant.id = event.assistantMessageId;
          }
          assistant.status = event.status || "interrupted";
          if (
            name === "error" ||
            (event.reason && event.reason !== "user_stop")
          ) {
            error.value = event.message || `回复未正常完成（${event.reason || "未知原因"}）`;
          }
          if (event.reason === "persistence_error"
              || (name === "error" && !event.assistantMessageId)) {
            needsReload.value = true;
          }
        }
        void scrollBottom();
      },
    );
  } catch (failure) {
    if (disposed) {
      return;
    }
    error.value = errorMessage(failure);
    if (assistant) {
      assistant.status = "interrupted";
      needsReload.value = true;
    }
    if (!accepted) {
      content.value = question;
      error.value += "；发送结果尚未确认，请刷新会话列表核对后再发送。";
    }
  } finally {
    sending.value = false;
    stopping.value = false;
    activeTaskId.value = "";
    controller = null;
    if (!disposed && currentUser.value) {
      void loadConversations();
    }
  }
}

async function stop(): Promise<void> {
  if (!sending.value || !activeTaskId.value || stopping.value) {
    return;
  }
  const taskId = activeTaskId.value;
  stopping.value = true;
  try {
    await request(
      `/api/agent/stop?taskId=${encodeURIComponent(taskId)}`,
      { method: "POST" },
    );
    if (disposed || activeTaskId.value !== taskId) {
      return;
    }
    emit("notify", "已请求停止，正在等待后端保存回复");
  } catch (failure) {
    if (!disposed && activeTaskId.value === taskId) {
      stopping.value = false;
      error.value = errorMessage(failure);
    }
  }
}

function composerKey(event: KeyboardEvent): void {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}

watch(
  currentUser,
  (user) => {
    controller?.abort();
    conversations.value = [];
    newConversation();
    if (user) {
      void loadConversations();
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
});
</script>

<template>
  <div class="chat-layout">
    <aside class="conversation-panel">
      <button
        class="primary new-chat"
        :disabled="sending"
        @click="newConversation"
      >
        <Icon name="plus" :size="18" />开启新对话<span>↗</span>
      </button>
      <div class="search-input">
        <Icon name="search" :size="16" /><input
          v-model="search"
          aria-label="搜索已加载的对话"
          placeholder="搜索对话…"
        />
      </div>
      <div class="section-line">
        <span>最近对话</span
        ><button
          class="icon-button"
          title="刷新对话列表"
          aria-label="刷新对话列表"
          :disabled="sending || loadingConversations"
          @click="loadConversations()"
        >
          <Icon name="refresh" :size="14" />
        </button>
      </div>
      <div class="conversation-list">
        <div
          v-for="conversation in filtered"
          :key="conversation.conversationId"
          class="conversation-item"
          :class="{ selected: selectedId === conversation.conversationId }"
        >
          <button :disabled="sending" @click="selectConversation(conversation)">
            <Icon name="chat" :size="16" /><span>{{
              conversation.title || "未命名对话"
            }}</span>
          </button>
        </div>
        <div v-if="!filtered.length" class="history-empty">
          <span class="history-glyph"><Icon name="chat" :size="22" /></span>
          <p>{{ currentUser ? "还没有对话记录" : "你的想法，值得被记住" }}</p>
          <small>{{
            currentUser ? "开启一个新对话吧" : "登录后在这里找回每一次对话"
          }}</small
          ><button
            v-if="!currentUser"
            class="text-button"
            @click="emit('login')"
          >
            登录账号 <Icon name="chevron" :size="13" />
          </button>
        </div>
        <button
          v-if="conversations.length < conversationTotal"
          class="text-button load-more"
          :disabled="sending || loadingConversations"
          @click="loadConversations(true)"
        >
          加载更多对话
        </button>
      </div>
      <div class="conversation-foot">
        <Icon name="link" :size="15" /><span>每一个问题，都是新的起点</span>
      </div>
    </aside>
    <section class="chat-main">
      <div class="chat-heading">
        <span><span class="agent-indicator"></span>Agent 助手</span
        ><span class="subtle">{{
          sending ? "正在生成回复…" : "思考 · 探索 · 创造"
        }}</span>
      </div>
      <div ref="messageArea" class="message-area">
        <div v-if="!messages.length && !loading" class="welcome">
          <div class="welcome-emblem">
            <Icon name="spark" :size="38" /><span class="emblem-dot"></span>
          </div>
          <p class="eyebrow">YOUR EVERYDAY THINKING PARTNER</p>
          <h1>把问题交给我，<br /><span>把灵感留给你。</span></h1>
          <p class="welcome-description">
            从一个问题开始，一起梳理思路、探索答案。<br />这里是属于你的思考空间。
          </p>
          <div class="suggestions">
            <button
              v-for="item in suggestions"
              :key="item.title"
              @click="content = item.text"
            >
              <span class="suggestion-icon"
                ><Icon :name="item.icon" :size="20" /></span
              ><strong>{{ item.title }}</strong
              ><small>{{ item.caption }}</small
              ><Icon name="chevron" :size="16" />
            </button>
          </div>
        </div>
        <div v-if="loading" class="loading-state">正在读取对话…</div>
        <div v-if="messages.length" class="messages">
          <div class="conversation-title">{{ selectedTitle }}</div>
          <article
            v-for="message in messages"
            :key="message.id"
            class="message"
            :class="message.role"
          >
            <span class="message-avatar"
              ><Icon
                v-if="message.role === 'assistant'"
                name="spark"
                :size="18"
              /><template v-else>{{
                currentUser?.username?.slice(0, 1) || "我"
              }}</template></span
            >
            <div class="message-body">
              <div class="message-author">
                {{ message.role === "assistant" ? "Agent 助手" : "你"
                }}<small v-if="message.status === 'interrupted'"
                  >回复已中断</small
                >
              </div>
              <details v-if="message.thinkingContent" class="thinking">
                <summary>思考过程</summary>
                <p>{{ message.thinkingContent }}</p>
              </details>
              <MessageContent
                v-if="message.role === 'assistant' && message.content"
                :content="message.content"
              />
              <div v-else class="message-text">
                {{
                  message.content ||
                  (sending && message.role === "assistant"
                    ? "正在思考…"
                    : "（暂无回复内容）")
                }}
              </div>
            </div>
          </article>
          <button
            v-if="hasMoreMessages"
            class="secondary load-more"
            :disabled="loading"
            @click="loadMessages(true)"
          >
            继续加载后续消息
          </button>
        </div>
      </div>
      <div class="composer-wrapper">
        <div v-if="error" class="error-banner" role="alert">
          <span>{{ error }}</span
          ><button
            v-if="selectedId && !sending"
            class="text-button"
            @click="loadMessages()"
          >
            刷新消息</button
          ><button
            class="icon-button"
            aria-label="关闭错误提示"
            @click="error = ''"
          >
            <Icon name="close" :size="16" />
          </button>
        </div>
        <p v-if="needsReload" class="muted">
          发送结果尚未确认，请刷新消息后继续。
        </p>
        <p v-else-if="hasMoreMessages" class="muted">
          请加载剩余消息后继续对话。
        </p>
        <form class="composer" @submit.prevent="send">
          <textarea
            v-model="content"
            maxlength="20000"
            aria-label="输入消息"
            placeholder="有什么想聊的？输入你的问题…"
            rows="2"
            :disabled="sending || loading"
            @keydown="composerKey"
          ></textarea>
          <div class="composer-toolbar">
            <span class="composer-mode"
              ><Icon name="spark" :size="15" />Agent 对话</span
            ><span class="composer-hint">Shift + Enter 换行</span
            ><button
              v-if="sending"
              type="button"
              class="stop-button"
              :disabled="
                stopping || !activeTaskId
              "
              @click="stop"
            >
              {{ stopping ? "停止中…" : "停止生成" }}</button
            ><button
              v-else
              class="send-button"
              aria-label="发送消息"
              :disabled="
                !content.trim() || loading || loadingConversations || hasMoreMessages || needsReload
              "
            >
              <Icon name="arrow" :size="21" />
            </button>
          </div>
        </form>
        <p class="composer-footnote">
          AI 的回答可能不够准确，重要信息请进一步核实。<span>AGENTICRAG</span>
        </p>
      </div>
    </section>
  </div>
</template>
