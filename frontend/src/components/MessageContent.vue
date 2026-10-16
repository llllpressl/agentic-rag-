<script setup lang="ts">
import { computed } from "vue";
import DOMPurify from "dompurify";
import { marked } from "marked";

const props = defineProps<{ content: string }>();
/** 模型输出和历史消息均不可信；Markdown 转换后移除 HTML 与链接注入。 */
const rendered = computed(() =>
  DOMPurify.sanitize(
    marked.parse(props.content, { async: false, breaks: true }),
    {
      USE_PROFILES: { html: true },
      FORBID_TAGS: ["img", "style", "iframe", "form", "input", "button"],
      FORBID_ATTR: ["style"],
    },
  ),
);
</script>

<template>
  <div class="markdown-content" v-html="rendered"></div>
</template>
