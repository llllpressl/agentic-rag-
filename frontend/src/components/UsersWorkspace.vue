<script setup lang="ts">
import { onMounted, ref } from "vue";
import { currentUser, errorMessage, jsonBody, request } from "../api";
import type { Page, User } from "../types";
import Icon from "./Icon.vue";

const emit = defineEmits<{ notify: [message: string] }>();
const users = ref<User[]>([]);
const search = ref("");
const page = ref(1);
const total = ref(0);
const busy = ref(false);
const error = ref("");
const dialog = ref(false);
const editing = ref<User | null>(null);
const username = ref("");
const password = ref("");
const role = ref("user");
const formError = ref("");

async function load(): Promise<void> {
  busy.value = true;
  error.value = "";
  try {
    const result = await request<Page<User>>(
      `/api/admin/users?page=${page.value}&size=20&username=${encodeURIComponent(search.value.trim())}`,
    );
    users.value = result.records;
    total.value = result.total;
  } catch (failure) {
    error.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

function open(user: User | null = null): void {
  editing.value = user;
  username.value = user?.username || "";
  password.value = "";
  role.value = user?.role || "user";
  formError.value = "";
  dialog.value = true;
}

async function save(): Promise<void> {
  busy.value = true;
  formError.value = "";
  try {
    const body: Record<string, string> = {
      username: username.value.trim(),
      role: role.value,
    };
    if (password.value) {
      body.password = password.value;
    }
    const path = editing.value
      ? `/api/admin/users/${encodeURIComponent(editing.value.id || "")}`
      : "/api/admin/users";
    const updated = await request<User>(path, {
      method: editing.value ? "PATCH" : "POST",
      body: jsonBody(body),
    });
    if (editing.value?.id === currentUser.value?.userId && currentUser.value) {
      currentUser.value = {
        ...currentUser.value,
        username: updated.username,
        role: updated.role,
      };
    }
    password.value = "";
    dialog.value = false;
    emit("notify", "账号已保存");
    await load();
  } catch (failure) {
    formError.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

async function remove(user: User): Promise<void> {
  if (!window.confirm(`确认删除账号“${user.username}”？`)) {
    return;
  }
  busy.value = true;
  try {
    await request(`/api/admin/users/${encodeURIComponent(user.id || "")}`, {
      method: "DELETE",
    });
    if (users.value.length === 1 && page.value > 1) {
      page.value -= 1;
    }
    await load();
    emit("notify", "账号已删除");
  } catch (failure) {
    error.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

function searchUsers(): void {
  page.value = 1;
  void load();
}

onMounted(load);
</script>

<template>
  <section class="workspace-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">WORKSPACE PEOPLE</p>
        <h1>管理你的工作空间<span class="heading-dot">.</span></h1>
        <p class="muted">创建账号，分配角色，让每个人各得其所。</p>
      </div>
      <button class="primary" :disabled="busy" @click="open()">
        <Icon name="plus" :size="18" />创建账号
      </button>
    </div>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div class="users-card">
      <div class="document-toolbar">
        <h3>
          全部账号 <span>{{ total }}</span>
        </h3>
        <form class="user-search" @submit.prevent="searchUsers">
          <div class="search-input">
            <Icon name="search" :size="16" /><input
              v-model="search"
              placeholder="按用户名查找…"
              aria-label="按用户名查找"
            />
          </div>
          <button class="secondary" :disabled="busy">查找</button>
        </form>
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>用户名</th>
              <th>用户 ID</th>
              <th>角色</th>
              <th class="align-right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td>
                <div class="user-cell">
                  <span class="avatar">{{
                    user.username.slice(0, 1).toUpperCase()
                  }}</span
                  ><strong>{{ user.username }}</strong>
                </div>
              </td>
              <td>
                <code>{{ user.id }}</code>
              </td>
              <td>
                <span class="status-pill">{{
                  user.role === "admin" ? "管理员" : "普通用户"
                }}</span>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    class="text-button"
                    :disabled="busy"
                    @click="open(user)"
                  >
                    编辑账号</button
                  ><button
                    class="icon-button danger"
                    :disabled="busy || user.id === currentUser?.userId"
                    aria-label="删除账号"
                    @click="remove(user)"
                  >
                    <Icon name="trash" :size="17" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!users.length" class="documents-empty">
        <Icon name="users" :size="30" />
        <h3>{{ busy ? "正在读取账号…" : "暂无账号记录" }}</h3>
      </div>
      <div class="pagination">
        <span>共 {{ total }} 个账号 · 第 {{ page }} 页</span
        ><button
          class="secondary"
          :disabled="page === 1 || busy"
          @click="
            page -= 1;
            load();
          "
        >
          上一页</button
        ><button
          class="secondary"
          :disabled="page * 20 >= total || busy"
          @click="
            page += 1;
            load();
          "
        >
          下一页
        </button>
      </div>
    </div>
    <div
      v-if="dialog"
      class="modal-backdrop"
      @click.self="!busy && (dialog = false)"
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="user-dialog-heading"
        @keydown.esc="!busy && (dialog = false)"
      >
        <button
          class="icon-button modal-close"
          :disabled="busy"
          aria-label="关闭"
          @click="dialog = false"
        >
          <Icon name="close" /></button
        ><span class="modal-symbol"><Icon name="users" :size="25" /></span>
        <h2 id="user-dialog-heading">
          {{ editing ? "编辑账号" : "创建新账号" }}
        </h2>
        <p class="muted">角色与操作权限最终由后端校验。</p>
        <form @submit.prevent="save">
          <label
            >用户名<input
              v-model="username"
              required
              maxlength="64"
              autocomplete="off"
              placeholder="输入用户名" /></label
          ><label
            >{{ editing ? "新密码（留空则保留）" : "密码"
            }}<input
              v-model="password"
              type="password"
              :required="!editing"
              minlength="8"
              maxlength="72"
              autocomplete="new-password"
              placeholder="输入 8–72 位密码" /></label
          ><label
            >角色<select
              v-model="role"
              :disabled="editing?.id === currentUser?.userId"
            >
              <option value="user">普通用户</option>
              <option value="admin">管理员</option>
            </select></label
          >
          <p v-if="formError" class="error-inline" role="alert">
            {{ formError }}
          </p>
          <div class="modal-actions">
            <button
              class="secondary"
              type="button"
              :disabled="busy"
              @click="dialog = false"
            >
              取消</button
            ><button class="primary" :disabled="busy">
              {{ busy ? "正在保存…" : "保存账号" }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
