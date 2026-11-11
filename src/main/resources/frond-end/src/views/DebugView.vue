<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { allEndpoints } from '../api/endpoints.js'
import { sendRequest } from '../api/http.js'
import { makeQueryRow, resetDraft, workspace } from '../stores/workspace.js'
import ResponsePanel from '../components/ResponsePanel.vue'

const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

const result = ref(null)
const sending = ref(false)
const formatMessage = ref('')

const source = computed(
  () => allEndpoints.find(endpoint => endpoint.id === workspace.draft.sourceId) || null,
)

const pathParams = computed(() => {
  const names = []
  for (const match of String(workspace.draft.path || '').matchAll(/\{([^{}]+)\}/g)) {
    if (!names.includes(match[1])) names.push(match[1])
  }
  return names
})

watch(
  pathParams,
  names => {
    for (const name of names) {
      if (!(name in workspace.draft.pathParams)) {
        workspace.draft.pathParams[name] = ''
      }
    }
  },
  { immediate: true },
)

const hasBodyMethod = computed(() => !['GET', 'HEAD'].includes(workspace.draft.method))

const bodyError = computed(() => {
  if (!hasBodyMethod.value) return ''
  const raw = workspace.draft.body.trim()
  if (!raw) return ''

  try {
    JSON.parse(raw)
    return ''
  } catch (error) {
    return `JSON 格式有误：${error.message}`
  }
})

function addQueryRow() {
  workspace.draft.query.push(makeQueryRow())
}

function removeQueryRow(index) {
  workspace.draft.query.splice(index, 1)
  if (workspace.draft.query.length === 0) {
    workspace.draft.query.push(makeQueryRow())
  }
}

function formatBody() {
  formatMessage.value = ''
  const raw = workspace.draft.body.trim()
  if (!raw) return

  try {
    workspace.draft.body = JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    formatMessage.value = '当前内容不是合法 JSON，无法格式化。'
  }
}

async function send() {
  if (sending.value || !workspace.draft.path.trim() || bodyError.value) return

  const draft = workspace.draft
  const body = hasBodyMethod.value && draft.body.trim() ? draft.body : null

  sending.value = true
  try {
    result.value = await sendRequest({
      method: draft.method,
      path: draft.path,
      pathParams: draft.pathParams,
      query: draft.query,
      body,
    })
  } finally {
    sending.value = false
  }
}

function clearDraft() {
  resetDraft()
  result.value = null
  formatMessage.value = ''
}

function onKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    send()
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="workbench">
    <section class="panel">
      <header class="panel-head">
        <h2>请求</h2>
        <span class="hint">代理 → localhost:8089</span>
      </header>
      <div class="panel-body">
        <p v-if="source" class="source-note">{{ source.summary }}</p>

        <div class="request-line">
          <select v-model="workspace.draft.method" class="method-select" aria-label="请求方法">
            <option v-for="method in methods" :key="method" :value="method">{{ method }}</option>
          </select>
          <input
            v-model.trim="workspace.draft.path"
            class="path-input"
            type="text"
            spellcheck="false"
            placeholder="/api/product 或 /api/product/{id}"
            aria-label="请求路径"
          />
        </div>
        <p class="inline-hint">路径可包含 {id} 之类的占位符，下方会自动生成参数输入框。</p>

        <div v-if="pathParams.length" class="section">
          <div class="subhead">路径参数</div>
          <div class="path-param-list">
            <label v-for="name in pathParams" :key="name" class="field inline-field">
              <span>{{ name }}</span>
              <input v-model="workspace.draft.pathParams[name]" type="text" spellcheck="false" />
            </label>
          </div>
        </div>

        <div class="section">
          <div class="subhead">
            <span>Query 参数</span>
            <button class="btn ghost small" type="button" @click="addQueryRow">添加一行</button>
          </div>
          <div class="query-rows">
            <div v-for="(row, index) in workspace.draft.query" :key="row.id" class="query-row">
              <input v-model.trim="row.key" type="text" spellcheck="false" placeholder="key" aria-label="参数名" />
              <input v-model="row.value" type="text" spellcheck="false" placeholder="value" aria-label="参数值" />
              <button class="query-remove" type="button" title="删除该行" @click="removeQueryRow(index)">×</button>
            </div>
          </div>
        </div>

        <div class="section">
          <div class="subhead">
            <span>JSON Body</span>
            <button class="btn ghost small" type="button" @click="formatBody">格式化</button>
          </div>
          <textarea
            v-model="workspace.draft.body"
            class="body-editor"
            spellcheck="false"
            placeholder='{ "name": "手冲咖啡壶", "price": 129.00, "stock": 20 }'
            aria-label="JSON 请求体"
          ></textarea>
          <p v-if="bodyError" class="inline-error">{{ bodyError }}</p>
          <p v-else-if="formatMessage" class="inline-error">{{ formatMessage }}</p>
          <p v-else-if="!hasBodyMethod" class="inline-hint">
            {{ workspace.draft.method }} 请求不会发送 body，文本仍会保留，切回 POST/PUT/PATCH 即可使用。
          </p>
        </div>

        <div class="btn-row">
          <button
            class="btn primary"
            type="button"
            :disabled="sending || !!bodyError || !workspace.draft.path"
            @click="send"
          >
            {{ sending ? '发送中…' : '发送请求' }}
          </button>
          <span class="kbd">Ctrl</span>
          <span class="kbd">Enter</span>
          <button class="btn ghost" type="button" @click="clearDraft">清空</button>
        </div>
      </div>
    </section>

    <ResponsePanel
      :result="result"
      empty-hint="发送请求后，这里显示 HTTP 状态、业务码、耗时和完整响应。"
    />
  </div>
</template>

<style scoped>
.workbench {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: 20px;
  align-items: start;
}

.source-note {
  margin-bottom: 16px;
  padding: 9px 12px;
  border-left: 2px solid var(--orange);
  background: rgba(217, 72, 15, 0.045);
  font-size: 12.5px;
  color: var(--ink-soft);
}

.request-line {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 8px;
}

.method-select {
  font-family: var(--mono);
  font-size: 12.5px;
  font-weight: 700;
}

.path-input {
  font-family: var(--mono);
  font-size: 13px;
}

.path-param-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}

.inline-field {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.inline-field > span {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--ink-soft);
}

.query-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.query-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 34px;
  gap: 8px;
}

.query-row input {
  font-family: var(--mono);
  font-size: 12px;
}

.query-remove {
  border: 1px solid var(--line-strong);
  border-radius: 2px;
  background: #fff;
  color: var(--ink-faint);
  font-size: 16px;
  line-height: 1;
}

.query-remove:hover {
  border-color: var(--red);
  color: var(--red);
}

.body-editor {
  min-height: 158px;
  font-family: var(--mono);
  font-size: 12.5px;
}

@media (max-width: 1100px) {
  .workbench {
    grid-template-columns: 1fr;
  }
}
</style>
