<script setup>
import { computed, ref } from 'vue'
import StatusStamp from './StatusStamp.vue'

const props = defineProps({
  result: { type: Object, default: null },
  emptyHint: { type: String, default: '尚未发送请求' },
  compact: { type: Boolean, default: false },
})

const tab = ref('pretty')
const expanded = ref(false)
const copied = ref(false)

const prettyText = computed(() => {
  if (!props.result) return ''
  if (props.result.json !== null && props.result.json !== undefined) {
    return JSON.stringify(props.result.json, null, 2)
  }
  return props.result.rawText || '（空响应体）'
})

const rawText = computed(() => props.result?.rawText || '（空响应体）')

const timeText = computed(() => {
  if (!props.result?.at) return '—'
  return new Date(props.result.at).toLocaleTimeString('zh-CN', { hour12: false })
})

const businessCode = computed(() => {
  const json = props.result?.json
  return json && typeof json === 'object' && 'code' in json ? json.code : '—'
})

const verdictLabel = computed(() => {
  if (props.result?.networkError) return '连接失败'
  return props.result?.success ? '成功' : '失败'
})

async function copyText() {
  try {
    await navigator.clipboard.writeText(props.result?.rawText || '')
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <section class="response" :class="{ 'is-compact': compact }">
    <div v-if="!result" class="empty">
      <p>{{ emptyHint }}</p>
    </div>

    <template v-else>
      <header class="response-head">
        <StatusStamp :tone="result.success ? 'pass' : 'fail'" :label="verdictLabel" />
        <span class="meta">{{ result.request.method }} {{ result.request.url }}</span>
        <span v-if="result.httpStatus != null" class="meta">HTTP {{ result.httpStatus }}</span>
        <span class="meta">{{ result.elapsedMs }} ms</span>
        <span class="meta">code {{ businessCode }}</span>
      </header>

      <p v-if="result.networkError" class="error-text">{{ result.networkError }}</p>
      <p v-else-if="result.json?.message" class="response-message">{{ result.json.message }}</p>

      <button v-if="compact" type="button" class="btn ghost small toggle" @click="expanded = !expanded">
        {{ expanded ? '收起响应' : '展开响应' }}
      </button>

      <template v-if="!compact || expanded">
        <div class="response-tools">
          <div class="tabs" role="tablist">
            <button type="button" :class="{ active: tab === 'pretty' }" @click="tab = 'pretty'">格式化</button>
            <button type="button" :class="{ active: tab === 'raw' }" @click="tab = 'raw'">原始</button>
          </div>
          <div class="tools-right">
            <span class="meta time">{{ timeText }}</span>
            <button type="button" class="btn ghost small" @click="copyText">{{ copied ? '已复制' : '复制' }}</button>
          </div>
        </div>
        <pre class="code-block">{{ tab === 'pretty' ? prettyText : rawText }}</pre>
      </template>
    </template>
  </section>
</template>

<style scoped>
.response {
  display: flex;
  flex-direction: column;
  min-height: 240px;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius);
}

.response.is-compact {
  min-height: 0;
}

.response-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line);
  background: #fafbfc;
}

.meta {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}

.response-message {
  padding: 10px 14px 0;
  font-size: 13px;
}

.error-text {
  padding: 10px 14px 0;
  font-size: 13px;
  color: var(--red);
}

.empty {
  margin: auto;
  padding: 36px 20px;
  text-align: center;
  font-size: 13px;
  color: var(--ink-faint);
}

.toggle {
  align-self: flex-start;
  margin: 8px 14px 0;
}

.response-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px 0;
}

.tools-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.time {
  color: var(--ink-faint);
}

.tabs {
  display: flex;
  overflow: hidden;
  border: 1px solid var(--line-strong);
  border-radius: 2px;
}

.tabs button {
  border: 0;
  padding: 4px 11px;
  background: #fff;
  font-size: 12px;
  color: var(--ink-soft);
}

.tabs button + button {
  border-left: 1px solid var(--line);
}

.tabs button.active {
  background: var(--ink);
  color: #fff;
}

.code-block {
  max-height: 420px;
  margin: 10px 14px 14px;
  padding: 12px 14px;
  overflow: auto;
  border-radius: 2px;
  background: #101c2b;
  color: #d7e2ec;
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.is-compact .code-block {
  max-height: 260px;
}
</style>
