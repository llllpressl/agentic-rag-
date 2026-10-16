<script setup lang="ts">
import { computed, ref } from "vue";
import { errorMessage, jsonBody, request } from "../api";
import type { KnowledgeBase, KnowledgeDocument } from "../types";
import Icon from "./Icon.vue";

const emit = defineEmits<{ notify: [message: string] }>();
const storageKey = "agenticrag.local-library.v1";
const bases = ref<KnowledgeBase[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const selectedId = ref("");
const search = ref("");
const error = ref("");
const busy = ref(false);
const uploadInput = ref<HTMLInputElement | null>(null);
const dragging = ref(false);
const dialog = ref("");
const formId = ref("");
const formName = ref("");
const formCollection = ref("");
const formModel = ref("text-embedding-v4");
const formError = ref("");
const editingDocument = ref<KnowledgeDocument | null>(null);

/** 仅记录本浏览器已知的 ID 与上传响应；不能据此推断后端当前状态。 */
try {
  const cached = JSON.parse(localStorage.getItem(storageKey) || "{}");
  if (Array.isArray(cached.bases) && Array.isArray(cached.documents)) {
    bases.value = cached.bases.filter(
      (base: KnowledgeBase) =>
        typeof base.id === "string" && typeof base.name === "string",
    );
    documents.value = cached.documents.filter(
      (document: KnowledgeDocument) =>
        typeof document.id === "string" &&
        typeof document.kbId === "string" &&
        typeof document.docName === "string",
    );
    selectedId.value = bases.value[0]?.id || "";
  }
} catch {
  error.value = "本机记录无法读取，可通过“登记已有知识库”恢复 ID。";
}

const selectedBase = computed(() =>
  bases.value.find((base) => base.id === selectedId.value),
);
const visibleDocuments = computed(() =>
  documents.value.filter(
    (document) =>
      document.kbId === selectedId.value &&
      document.docName.toLowerCase().includes(search.value.toLowerCase()),
  ),
);
const selectedCount = computed(
  () =>
    documents.value.filter((document) => document.kbId === selectedId.value)
      .length,
);
const dialogTitle = computed(
  () =>
    ({
      create: "创建知识库",
      register: "登记已有知识库",
      rename: "重命名知识库",
      document: "重命名文档",
      registerDocument: "登记已有文档",
    })[dialog.value] || "知识库",
);

function persist(): void {
  try {
    localStorage.setItem(
      storageKey,
      JSON.stringify({ bases: bases.value, documents: documents.value }),
    );
  } catch {
    emit("notify", "后端操作已返回，但本机记录保存失败，请记录页面上的 ID");
  }
}

function openDialog(mode: string, document?: KnowledgeDocument): void {
  dialog.value = mode;
  formError.value = "";
  formId.value = "";
  formName.value =
    mode === "rename"
      ? selectedBase.value?.name || ""
      : document?.docName || "";
  formCollection.value = "";
  formModel.value = "text-embedding-v4";
  editingDocument.value = document || null;
}

async function saveDialog(): Promise<void> {
  if (!formName.value.trim() || busy.value) {
    return;
  }
  busy.value = true;
  formError.value = "";
  const name = formName.value.trim();
  try {
    if (dialog.value === "create" || dialog.value === "register") {
      let id = formId.value.trim();
      if (dialog.value === "create") {
        id = await request<string>("/knowledge/base", {
          method: "POST",
          body: jsonBody({
            name,
            collection: formCollection.value.trim(),
            embeddingModel: formModel.value.trim(),
          }),
        });
      }
      if (bases.value.some((base) => base.id === id)) {
        throw new Error("这个知识库 ID 已在本机记录中");
      }
      bases.value.push({
        id,
        name,
        collection: formCollection.value.trim(),
        embeddingModel: formModel.value.trim(),
      });
      selectedId.value = id;
    } else if (dialog.value === "rename" && selectedBase.value) {
      await request(`/knowledge/base/${encodeURIComponent(selectedId.value)}`, {
        method: "PUT",
        body: jsonBody({ name }),
      });
      selectedBase.value.name = name;
    } else if (dialog.value === "document" && editingDocument.value) {
      await request(
        `/knowledge/document/${encodeURIComponent(editingDocument.value.id)}`,
        { method: "PUT", body: jsonBody({ docName: name }) },
      );
      editingDocument.value.docName = name;
    } else if (dialog.value === "registerDocument") {
      const id = formId.value.trim();
      if (documents.value.some((document) => document.id === id)) {
        throw new Error("这个文档 ID 已在本机记录中");
      }
      documents.value.push({ id, kbId: selectedId.value, docName: name });
    }
    persist();
    emit(
      "notify",
      dialog.value.startsWith("register")
        ? "已登记到本机记录；ID 是否有效由后续操作确认"
        : "操作成功",
    );
    dialog.value = "";
  } catch (failure) {
    formError.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

async function upload(files: FileList | null): Promise<void> {
  if (!files?.length || !selectedBase.value || busy.value) {
    return;
  }
  const kbId = selectedId.value;
  busy.value = true;
  error.value = "";
  let uploaded = 0;
  const failures: string[] = [];
  try {
    for (const file of Array.from(files)) {
      const body = new FormData();
      body.append("file", file);
      try {
        const document = await request<KnowledgeDocument>(
          `/knowledge/base/${encodeURIComponent(kbId)}/document`,
          { method: "POST", body },
        );
        documents.value.push(document);
        uploaded += 1;
        persist();
      } catch (failure) {
        failures.push(`${file.name}：${errorMessage(failure)}`);
      }
    }
    if (uploaded) {
      emit("notify", `已上传 ${uploaded} 个文档，可按需提交切分`);
    }
    error.value = failures.join("；");
  } finally {
    busy.value = false;
    if (uploadInput.value) {
      uploadInput.value.value = "";
    }
  }
}

function dropped(event: DragEvent): void {
  dragging.value = false;
  void upload(event.dataTransfer?.files || null);
}

async function startChunk(document: KnowledgeDocument): Promise<void> {
  busy.value = true;
  error.value = "";
  try {
    await request(
      `/knowledge/document/${encodeURIComponent(document.id)}/chunk`,
      { method: "POST" },
    );
    document.submittedAt = new Date().toLocaleString("zh-CN");
    persist();
    emit("notify", "切分请求已提交；实际处理结果需在后端确认");
  } catch (failure) {
    error.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

async function deleteDocument(document: KnowledgeDocument): Promise<void> {
  if (
    !window.confirm(`删除后端文档“${document.docName}”？此操作无法在前端撤销。`)
  ) {
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await request(`/knowledge/document/${encodeURIComponent(document.id)}`, {
      method: "DELETE",
    });
    documents.value = documents.value.filter((item) => item.id !== document.id);
    persist();
    emit("notify", "文档删除接口已返回成功");
  } catch (failure) {
    error.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

async function deleteBase(): Promise<void> {
  if (
    !selectedBase.value ||
    !window.confirm(
      `删除后端知识库“${selectedBase.value.name}”？后端要求先删除库中所有文档。`,
    )
  ) {
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const id = selectedId.value;
    await request(`/knowledge/base/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
    bases.value = bases.value.filter((base) => base.id !== id);
    documents.value = documents.value.filter(
      (document) => document.kbId !== id,
    );
    selectedId.value = bases.value[0]?.id || "";
    persist();
    emit("notify", "知识库删除接口已返回成功");
  } catch (failure) {
    error.value = errorMessage(failure);
  } finally {
    busy.value = false;
  }
}

function forgetBase(): void {
  const id = selectedId.value;
  if (
    !window.confirm(
      "仅移除这个知识库及其文档的本机记录？后端数据会保留。请先记下 ID，以便重新登记。",
    )
  ) {
    return;
  }
  bases.value = bases.value.filter((base) => base.id !== id);
  documents.value = documents.value.filter((document) => document.kbId !== id);
  selectedId.value = bases.value[0]?.id || "";
  persist();
}

function formatBytes(bytes?: number): string {
  if (bytes === undefined || bytes === null) {
    return "大小未知";
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function exportRecords(): void {
  const blob = new Blob(
    [
      JSON.stringify(
        { bases: bases.value, documents: documents.value },
        null,
        2,
      ),
    ],
    { type: "application/json" },
  );
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "agenticrag-library-records.json";
  anchor.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <section class="workspace-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">YOUR KNOWLEDGE, CONNECTED</p>
        <h1>让资料，有迹可循<span class="heading-dot">.</span></h1>
        <p class="muted">收集、整理，为每一次思考积累素材。</p>
      </div>
      <button class="primary" :disabled="busy" @click="openDialog('create')">
        <Icon name="plus" :size="18" />创建知识库
      </button>
    </div>
    <div class="info-note">
      <Icon name="link" :size="18" />
      <div>
        这里展示本浏览器保存的知识库与文档记录。后端暂未提供列表和进度查询，记录可能与后端现状不同。<br /><span
          >RAG 本地环境使用 dev-user 身份；与 Agent 登录账号独立。</span
        >
      </div>
      <button class="text-button" @click="exportRecords">导出记录 ↗</button>
    </div>
    <div v-if="error" class="error-banner" role="alert">
      {{ error
      }}<button class="icon-button" aria-label="关闭错误" @click="error = ''">
        <Icon name="close" :size="16" />
      </button>
    </div>
    <div class="library-layout">
      <aside class="library-bases">
        <div class="section-line">
          <span
            >我的知识库 <small>{{ bases.length }}</small></span
          >
        </div>
        <button
          v-for="base in bases"
          :key="base.id"
          class="base-button"
          :class="{ selected: base.id === selectedId }"
          :disabled="busy"
          @click="
            selectedId = base.id;
            search = '';
          "
        >
          <span class="base-icon"><Icon name="book" :size="20" /></span
          ><span
            ><strong>{{ base.name }}</strong
            ><small>{{ base.collection || "已登记的知识库" }}</small></span
          ><Icon name="chevron" :size="15" />
        </button>
        <div v-if="!bases.length" class="base-empty">
          还没有本机记录<br />创建一个，或登记已有知识库
        </div>
        <button
          class="register-button"
          :disabled="busy"
          @click="openDialog('register')"
        >
          <Icon name="link" :size="16" />登记已有知识库
        </button>
      </aside>
      <div class="library-content">
        <template v-if="selectedBase"
          ><div class="library-title">
            <div>
              <h2>{{ selectedBase.name }}</h2>
              <div class="id-line">
                ID <code>{{ selectedBase.id }}</code>
              </div>
            </div>
            <div class="row-actions">
              <button
                class="icon-button"
                aria-label="重命名知识库"
                title="重命名"
                :disabled="busy"
                @click="openDialog('rename')"
              >
                <Icon name="edit" :size="17" /></button
              ><button
                class="icon-button danger"
                aria-label="删除知识库"
                title="删除后端知识库"
                :disabled="busy"
                @click="deleteBase"
              >
                <Icon name="trash" :size="17" />
              </button>
            </div>
          </div>
          <div class="library-meta">
            <span
              ><span class="status-dot"></span
              >{{ selectedCount }} 份本机文档记录</span
            ><span v-if="selectedBase.embeddingModel">{{
              selectedBase.embeddingModel
            }}</span
            ><button class="text-button" :disabled="busy" @click="forgetBase">
              移除本机记录
            </button>
          </div>
          <button
            class="upload-zone"
            :class="{ dragging }"
            :disabled="busy"
            @click="uploadInput?.click()"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="dropped"
          >
            <span class="upload-icon"><Icon name="upload" :size="24" /></span
            ><strong>{{
              busy ? "正在处理，请稍候…" : "把资料放进来，让知识沉淀下来"
            }}</strong
            ><span>拖拽文件至此，或 <em>点击选择文件</em></span
            ><small
              >上传后可单独提交切分 · 格式与大小限制以后端配置为准</small
            ></button
          ><input
            ref="uploadInput"
            class="visually-hidden"
            type="file"
            multiple
            aria-label="上传文档"
            :disabled="busy"
            @change="upload(($event.target as HTMLInputElement).files)"
          />
          <div class="document-toolbar">
            <h3>
              文档记录 <span>{{ selectedCount }}</span>
            </h3>
            <div class="document-controls">
              <button
                class="text-button"
                :disabled="busy"
                @click="openDialog('registerDocument')"
              >
                <Icon name="plus" :size="15" />登记文档
              </button>
              <div class="search-input">
                <Icon name="search" :size="15" /><input
                  v-model="search"
                  placeholder="查找文档…"
                  aria-label="查找文档"
                />
              </div>
            </div>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>文档名称</th>
                  <th>上传响应 / 本机记录</th>
                  <th class="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="document in visibleDocuments" :key="document.id">
                  <td>
                    <div class="document-name">
                      <span class="file-icon"
                        ><Icon name="file" :size="21"
                      /></span>
                      <div>
                        <strong>{{ document.docName }}</strong
                        ><small
                          >{{ formatBytes(document.fileSize)
                          }}<span v-if="document.fileType">
                            · {{ document.fileType }}</span
                          ></small
                        ><code>{{ document.id }}</code>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span
                      class="status-pill"
                      :class="{ submitted: document.submittedAt }"
                      >{{
                        document.submittedAt
                          ? "已提交切分"
                          : document.status || "已登记"
                      }}</span
                    ><small class="table-note">{{
                      document.submittedAt || "当前状态未查询"
                    }}</small>
                  </td>
                  <td>
                    <div class="row-actions">
                      <button
                        class="text-button"
                        :disabled="busy"
                        @click="startChunk(document)"
                      >
                        提交切分</button
                      ><button
                        class="icon-button"
                        aria-label="重命名文档"
                        title="重命名文档"
                        :disabled="busy"
                        @click="openDialog('document', document)"
                      >
                        <Icon name="edit" :size="16" /></button
                      ><button
                        class="icon-button danger"
                        aria-label="删除文档"
                        title="删除文档"
                        :disabled="busy"
                        @click="deleteDocument(document)"
                      >
                        <Icon name="trash" :size="16" />
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="!visibleDocuments.length" class="documents-empty">
            <Icon name="file" :size="30" />
            <h3>
              {{ search ? "没有匹配的文档" : "留一个位置，给下一份好资料" }}
            </h3>
            <p>
              {{ search ? "换一个关键词试试" : "上传文档后，会在这里留下记录" }}
            </p>
          </div>
          <p class="library-footnote">
            “已提交切分”仅表示接口返回成功，不代表解析、向量化或入库已完成。
          </p></template
        >
        <div v-else class="library-welcome">
          <span class="welcome-emblem"><Icon name="book" :size="38" /></span>
          <h2>建一座自己的知识库</h2>
          <p>把零散的资料，变成有序的积累。</p>
          <button
            class="primary"
            :disabled="busy"
            @click="openDialog('create')"
          >
            <Icon name="plus" :size="17" />创建第一个知识库</button
          ><button
            class="text-button"
            :disabled="busy"
            @click="openDialog('register')"
          >
            已有知识库？登记 ID <Icon name="chevron" :size="14" />
          </button>
        </div>
      </div>
    </div>
    <div
      v-if="dialog"
      class="modal-backdrop"
      @click.self="!busy && (dialog = '')"
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="library-dialog-heading"
        @keydown.esc="!busy && (dialog = '')"
      >
        <button
          class="icon-button modal-close"
          aria-label="关闭"
          :disabled="busy"
          @click="dialog = ''"
        >
          <Icon name="close" /></button
        ><span class="modal-symbol"><Icon name="book" :size="25" /></span>
        <h2 id="library-dialog-heading">{{ dialogTitle }}</h2>
        <p class="muted">
          {{
            dialog.startsWith("register")
              ? "登记只保存本机记录，不创建或校验后端数据。"
              : "每一份积累，都从一个清晰的名字开始。"
          }}
        </p>
        <form @submit.prevent="saveDialog">
          <label v-if="dialog.startsWith('register')"
            >已有 ID<input
              v-model="formId"
              required
              pattern="[0-9]+"
              inputmode="numeric"
              placeholder="输入后端返回的完整 ID" /></label
          ><label
            >名称<input
              v-model="formName"
              required
              maxlength="255"
              placeholder="例如：项目资料、学习笔记" /></label
          ><template v-if="dialog === 'create'"
            ><label
              >集合名称 collection<input
                v-model="formCollection"
                required
                pattern="[a-zA-Z0-9_-]+"
                maxlength="100"
                placeholder="例如 project-notes"
              /><small
                >作为存储路径的一部分，使用英文、数字、横线或下划线</small
              ></label
            ><label
              >Embedding 模型<input
                v-model="formModel"
                required
                maxlength="100"
                placeholder="text-embedding-v4"
              /><small>需与实际后端向量模型配置一致</small></label
            ></template
          >
          <p v-if="formError" class="error-inline" role="alert">
            {{ formError }}
          </p>
          <div class="modal-actions">
            <button
              class="secondary"
              type="button"
              :disabled="busy"
              @click="dialog = ''"
            >
              取消</button
            ><button class="primary" :disabled="busy">
              {{ busy ? "正在保存…" : "确认保存" }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
