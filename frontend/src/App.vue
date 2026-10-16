<script setup lang="ts">
import { ref } from "vue";
import Icon from "./components/Icon.vue";
import ChatWorkspace from "./components/ChatWorkspace.vue";
import KnowledgeWorkspace from "./components/KnowledgeWorkspace.vue";
import UsersWorkspace from "./components/UsersWorkspace.vue";
import {
  clearSession,
  currentUser,
  errorMessage,
  jsonBody,
  request,
  setSession,
} from "./api";
import type { User } from "./types";

const page = ref("chat");
const sidebarOpen = ref(false);
const loginOpen = ref(false);
const loginBusy = ref(false);
const loginError = ref("");
const creatingAccount = ref(false);
const logoutBusy = ref(false);
const username = ref("");
const password = ref("");
const notice = ref("");
const chatVersion = ref(0);
let noticeTimer: ReturnType<typeof setTimeout>;

function notify(message: string): void {
  notice.value = message;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    notice.value = "";
  }, 6500);
}

function navigate(destination: string): void {
  page.value = destination;
  sidebarOpen.value = false;
}

async function login(): Promise<void> {
  if (loginBusy.value) {
    return;
  }
  loginBusy.value = true;
  loginError.value = "";
  try {
    /** 原来读取 { accessToken, user }；现在直接读取 Session 登录返回的 UserInfo。 */
    const user = await request<User>(
      "/api/auth/login",
      {
        method: "POST",
        body: jsonBody({ username: username.value.trim(), password: password.value }),
      },
    );
    setSession(user);
    chatVersion.value += 1;
    password.value = "";
    loginOpen.value = false;
    notify(`欢迎回来，${user.username}`);
  } catch (error) {
    loginError.value = errorMessage(error);
  } finally {
    loginBusy.value = false;
  }
}

/** 原来 logout 只清空页面状态；现在先请求后端销毁 Session。 */
async function logout(): Promise<void> {
  if (logoutBusy.value) {
    return;
  }
  logoutBusy.value = true;
  try {
    await request<void>("/api/auth/logout", { method: "POST" });
    clearSession();
    page.value = "chat";
    chatVersion.value += 1;
    notify("已退出登录");
  } catch (failure) {
    notify(errorMessage(failure));
  } finally {
    logoutBusy.value = false;
  }
}

function openLogin(): void {
  creatingAccount.value = false;
  loginError.value = "";
  password.value = "";
  loginOpen.value = true;
}

function openRegistration(): void {
  creatingAccount.value = true;
  loginError.value = "";
  password.value = "";
  loginOpen.value = true;
}

function toggleAccountMode(): void {
  creatingAccount.value = !creatingAccount.value;
  loginError.value = "";
  password.value = "";
}

async function createUser(): Promise<void> {
  if (loginBusy.value) {
    return;
  }
  loginBusy.value = true;
  loginError.value = "";
  try {
    await request<User>("/api/users", {
      method: "POST",
      body: jsonBody({ username: username.value.trim(), password: password.value }),
    });
    password.value = "";
    creatingAccount.value = false;
    notify("账号创建成功，请使用新账号登录");
  } catch (failure) {
    loginError.value = errorMessage(failure);
  } finally {
    loginBusy.value = false;
  }
}
</script>

<template>
  <div class="app-shell">
    <button
      v-if="sidebarOpen"
      class="sidebar-scrim"
      aria-label="关闭导航"
      @click="sidebarOpen = false"
    ></button>
    <aside class="sidebar" :class="{ expanded: sidebarOpen }">
      <a class="brand" href="#" @click.prevent="navigate('chat')">
        <span class="brand-mark"><Icon name="spark" :size="24" /></span>
        <span
          >Agentic<span class="brand-light">Rag</span
          ><small>PERSONAL WORKSPACE</small></span
        >
      </a>
      <div class="sidebar-label">工作空间</div>
      <nav aria-label="主导航">
        <button :class="{ active: page === 'chat' }" @click="navigate('chat')">
          <Icon name="chat" />智能对话<span
            class="nav-dot"
            v-if="page === 'chat'"
          ></span>
        </button>
        <button
          :class="{ active: page === 'knowledge' }"
          @click="navigate('knowledge')"
        >
          <Icon name="book" />知识库<span
            class="nav-dot"
            v-if="page === 'knowledge'"
          ></span>
        </button>
        <!-- /** 原来进入管理员账号管理页；当前只开放创建账号。 */ -->
        <button @click="openRegistration">
          <Icon name="users" />创建账号
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="workspace-note">
          <span class="tiny-label">A SPACE FOR YOUR IDEAS</span>
          <h3>让知识，<br />成为你的下一步。</h3>
          <div class="orbit-art">
            <span></span><span></span><Icon name="spark" :size="28" />
          </div>
        </div>
        <div class="account-row">
          <span class="avatar">{{
            currentUser?.username?.slice(0, 1).toUpperCase() || "访"
          }}</span>
          <button class="account-name" @click="!currentUser && openLogin()">
            <strong>{{ currentUser?.username || "访客工作空间" }}</strong
            ><small>{{
              currentUser ? "个人账号" : "登录后开始对话"
            }}</small>
          </button>
          <button
            v-if="currentUser"
            class="icon-button"
            title="退出登录"
            aria-label="退出登录"
            :disabled="logoutBusy"
            @click="logout"
          >
            <Icon name="logout" :size="18" />
          </button>
          <button
            v-else
            class="icon-button"
            aria-label="登录"
            @click="openLogin"
          >
            <Icon name="chevron" :size="18" />
          </button>
        </div>
      </div>
    </aside>
    <main class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-menu"
            aria-label="打开导航"
            @click="sidebarOpen = true"
          >
            <Icon name="menu" /></button
          ><span>我的工作空间</span><span class="crumb-slash">/</span
          ><strong>{{
            page === "chat"
              ? "智能对话"
              : page === "knowledge"
                ? "知识库"
                : "账号管理"
          }}</strong>
        </div>
        <div class="topbar-right">
          <span class="local-badge"><span></span>本地工作台</span
          ><span class="topbar-divider"></span
          ><button
            class="text-button"
            @click="
              currentUser
                ? notify('当前以 ' + currentUser.username + ' 身份登录')
                : openLogin()
            "
          >
            {{ currentUser?.username || "登录账号"
            }}<Icon name="chevron" :size="14" />
          </button>
        </div>
      </header>
      <ChatWorkspace
        v-show="page === 'chat'"
        :key="`${chatVersion}-${currentUser?.userId || 'guest'}`"
        @login="openLogin"
        @notify="notify"
      />
      <KnowledgeWorkspace v-if="page === 'knowledge'" @notify="notify" />
      <!-- /** 原管理页依赖尚未实现的列表、修改和删除接口，本阶段不挂载。 */ -->
      <UsersWorkspace
        v-if="false"
        @notify="notify"
      />
    </main>
    <Transition name="toast"
      ><div v-if="notice" class="toast" role="status">
        <Icon name="check" :size="18" /><span>{{ notice }}</span
        ><button class="icon-button" aria-label="关闭提示" @click="notice = ''">
          <Icon name="close" :size="16" />
        </button></div
    ></Transition>
    <div
      v-if="loginOpen"
      class="modal-backdrop"
      @click.self="!loginBusy && (loginOpen = false)"
    >
      <section
        class="modal login-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="login-heading"
        @keydown.esc="!loginBusy && (loginOpen = false)"
      >
        <button
          class="icon-button modal-close"
          aria-label="关闭"
          :disabled="loginBusy"
          @click="loginOpen = false"
        >
          <Icon name="close" />
        </button>
        <span class="modal-symbol"><Icon name="spark" :size="26" /></span>
        <p class="eyebrow">{{ creatingAccount ? "START HERE" : "WELCOME BACK" }}</p>
        <h2 id="login-heading">{{ creatingAccount ? "创建你的账号" : "回到你的工作空间" }}</h2>
        <p class="muted">{{ creatingAccount ? "创建账号后，登录即可开始对话。" : "使用已有账号登录，继续与 Agent 对话。" }}</p>
        <!-- /** 原表单只处理 login；现在按模式调用创建用户或登录。 */ -->
        <form @submit.prevent="creatingAccount ? createUser() : login()">
          <label
            >用户名<input
              v-model="username"
              required
              maxlength="64"
              :minlength="creatingAccount ? 3 : undefined"
              :pattern="creatingAccount ? '[A-Za-z0-9_][A-Za-z0-9_.-]{2,63}' : undefined"
              :disabled="loginBusy"
              autocomplete="username"
              placeholder="输入用户名"
              autofocus
          /></label>
          <label
            >密码<input
              v-model="password"
              required
              type="password"
              maxlength="72"
              :minlength="creatingAccount ? 6 : undefined"
              :disabled="loginBusy"
              :autocomplete="creatingAccount ? 'new-password' : 'current-password'"
              placeholder="输入密码"
          /></label>
          <p v-if="loginError" class="error-inline" role="alert">
            {{ loginError }}
          </p>
          <button class="primary full" :disabled="loginBusy">
            {{ loginBusy ? "正在处理…" : creatingAccount ? "创建账号" : "登录工作台"
            }}<Icon name="chevron" :size="16" />
          </button>
        </form>
        <!-- /** 原来由管理员创建账号；现在提供独立创建入口，刷新后仍需重新登录。 */ -->
        <p class="form-footnote">
          <button class="text-button" type="button" :disabled="loginBusy" @click="toggleAccountMode">
            {{ creatingAccount ? "已有账号，去登录" : "没有账号？创建一个" }}
          </button>
        </p>
        <p v-if="!creatingAccount" class="form-footnote">刷新页面后请重新登录</p>
      </section>
    </div>
  </div>
</template>
