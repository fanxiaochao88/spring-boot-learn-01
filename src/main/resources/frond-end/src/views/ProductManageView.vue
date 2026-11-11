<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { sendRequest } from '../api/http.js'
import ResponsePanel from '../components/ResponsePanel.vue'
import StatusStamp from '../components/StatusStamp.vue'

const filters = reactive({
  keyword: '',
  minPrice: '',
  maxPrice: '',
  status: '',
  pageSize: '10',
})

const pageNum = ref(1)
const listResult = ref(null)
const listLoading = ref(false)

const editingId = ref(null)
const editForm = reactive({ name: '', price: '', contactPhone: '', description: '' })
const maskedPhone = ref('')
const editLoading = ref(false)

const deletingId = ref(null)
const writeResult = ref(null)

const page = computed(() => (listResult.value?.success ? listResult.value.json?.data ?? null : null))
const records = computed(() => page.value?.records ?? [])
const total = computed(() => Number(page.value?.total ?? 0))
const currentPage = computed(() => Number(page.value?.pageNum ?? pageNum.value) || 1)
const totalPages = computed(() => {
  const size = Number(page.value?.pageSize ?? pageSizeValue()) || 10
  return Math.max(1, Math.ceil(total.value / size))
})

function pageSizeValue() {
  const parsed = Number.parseInt(filters.pageSize, 10)
  if (!Number.isFinite(parsed)) return 10
  return Math.min(100, Math.max(1, parsed))
}

function buildQuery(targetPage) {
  const rows = [
    { key: 'pageNum', value: String(targetPage) },
    { key: 'pageSize', value: String(pageSizeValue()) },
  ]
  const keyword = filters.keyword.trim()
  if (keyword) rows.push({ key: 'keyword', value: keyword })

  for (const key of ['minPrice', 'maxPrice']) {
    const value = filters[key].trim()
    if (value) rows.push({ key, value })
  }

  if (filters.status !== '') rows.push({ key: 'status', value: filters.status })
  return rows
}

async function loadPage(target = pageNum.value) {
  if (listLoading.value) return

  pageNum.value = target
  listLoading.value = true
  try {
    const result = await sendRequest({
      method: 'GET',
      path: '/api/product',
      query: buildQuery(target),
    })
    listResult.value = result
    if (result.success) {
      pageNum.value = Number(result.json?.data?.pageNum) || target
    }
  } finally {
    listLoading.value = false
  }
}

function search() {
  loadPage(1)
}

function resetFilters() {
  Object.assign(filters, { keyword: '', minPrice: '', maxPrice: '', status: '', pageSize: '10' })
  loadPage(1)
}

function goPage(target) {
  if (target < 1 || target > totalPages.value) return
  loadPage(target)
}

function isMaskedPhone(phone) {
  return typeof phone === 'string' && phone.includes('*')
}

function startEdit(record) {
  const phone = record.contactPhone ?? ''
  editingId.value = record.id
  maskedPhone.value = isMaskedPhone(phone) ? phone : ''
  Object.assign(editForm, {
    name: record.name ?? '',
    price: record.price == null ? '' : String(record.price),
    contactPhone: isMaskedPhone(phone) ? '' : phone,
    description: record.description ?? '',
  })
}

function cancelEdit() {
  editingId.value = null
  maskedPhone.value = ''
}

function toEditPayload() {
  const price = editForm.price.trim() === '' ? null : Number(editForm.price)
  return {
    name: editForm.name,
    price: Number.isFinite(price) ? price : null,
    contactPhone: editForm.contactPhone.trim() || null,
    description: editForm.description.trim() || null,
  }
}

async function submitEdit() {
  if (editingId.value == null || editLoading.value) return

  const id = editingId.value
  editLoading.value = true
  try {
    const result = await sendRequest({
      method: 'PUT',
      path: `/api/product/${encodeURIComponent(id)}`,
      body: toEditPayload(),
    })
    writeResult.value = result
    if (result.success) {
      cancelEdit()
      await loadPage(pageNum.value)
    }
  } finally {
    editLoading.value = false
  }
}

async function removeRecord(record) {
  if (deletingId.value != null) return

  const confirmed = window.confirm(
    `确认删除商品 #${record.id} · ${record.name}？\n逻辑删除后，详情接口将返回业务码 10001。`,
  )
  if (!confirmed) return

  deletingId.value = record.id
  try {
    const result = await sendRequest({
      method: 'DELETE',
      path: `/api/product/${encodeURIComponent(record.id)}`,
    })
    writeResult.value = result
    if (result.success) {
      if (editingId.value === record.id) cancelEdit()
      const target = records.value.length === 1 && pageNum.value > 1 ? pageNum.value - 1 : pageNum.value
      await loadPage(target)
    }
  } finally {
    deletingId.value = null
  }
}

function formatPrice(value) {
  if (value == null || value === '') return '—'
  const num = Number(value)
  return Number.isFinite(num) ? `¥ ${num.toFixed(2)}` : String(value)
}

onMounted(() => loadPage(1))
</script>

<template>
  <div class="manage-layout">
    <section class="panel">
      <header class="panel-head">
        <h2>商品分页</h2>
        <span class="hint">GET /api/product</span>
      </header>

      <div class="panel-body">
        <form class="query-grid" @submit.prevent="search">
          <label class="field">
            <span class="label-text">关键字 <em class="rule">名称模糊匹配</em></span>
            <input v-model="filters.keyword" type="search" placeholder="例如 咖啡" />
          </label>

          <label class="field">
            <span class="label-text">最低价</span>
            <input v-model="filters.minPrice" type="text" inputmode="decimal" placeholder="0.00" />
          </label>

          <label class="field">
            <span class="label-text">最高价</span>
            <input v-model="filters.maxPrice" type="text" inputmode="decimal" placeholder="999.99" />
          </label>

          <label class="field">
            <span class="label-text">状态</span>
            <select v-model="filters.status">
              <option value="">全部</option>
              <option value="1">上架</option>
              <option value="0">下架</option>
            </select>
          </label>

          <label class="field">
            <span class="label-text">每页 <em class="rule">1–100</em></span>
            <input v-model="filters.pageSize" type="text" inputmode="numeric" placeholder="10" />
          </label>

          <div class="query-actions">
            <button class="btn primary" type="submit" :disabled="listLoading">
              {{ listLoading ? '查询中…' : '查询' }}
            </button>
            <button class="btn" type="button" :disabled="listLoading" @click="resetFilters">重置</button>
          </div>
        </form>

        <p class="inline-hint">
          minPrice 大于 maxPrice 返回业务码 40000；pageSize 超过 100 时服务端按 100 兜底。
        </p>
      </div>

      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>名称</th>
              <th>价格</th>
              <th>库存</th>
              <th>状态</th>
              <th>联系电话</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="record in records"
              :key="record.id"
              :class="{ editing: editingId === record.id }"
            >
              <td class="mono">{{ record.id }}</td>
              <td>{{ record.name }}</td>
              <td class="mono">{{ formatPrice(record.price) }}</td>
              <td class="mono">{{ record.stock ?? '—' }}</td>
              <td>
                <StatusStamp
                  :tone="record.status === 1 ? 'pass' : 'warn'"
                  :label="record.statusText || (record.status === 1 ? '上架' : '下架')"
                />
              </td>
              <td class="mono">{{ record.contactPhone || '—' }}</td>
              <td class="mono">{{ record.createTime || '—' }}</td>
              <td>
                <div class="row-actions">
                  <button class="btn ghost small" type="button" @click="startEdit(record)">编辑</button>
                  <button
                    class="btn ghost small danger"
                    type="button"
                    :disabled="deletingId !== null"
                    @click="removeRecord(record)"
                  >
                    {{ deletingId === record.id ? '删除中…' : '删除' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="records.length === 0">
              <td colspan="8" class="empty-cell">
                {{
                  listLoading
                    ? '加载中…'
                    : listResult?.success
                      ? '没有符合条件的商品。'
                      : '暂无数据；检查下方响应面板。'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="pager">
        <span class="pager-meta">
          共 {{ total }} 条 · 第 {{ currentPage }} / {{ totalPages }} 页 · 每页
          {{ page?.pageSize ?? pageSizeValue() }} 条
        </span>
        <div class="pager-actions">
          <button
            class="btn small"
            type="button"
            :disabled="listLoading || currentPage <= 1"
            @click="goPage(currentPage - 1)"
          >
            上一页
          </button>
          <button
            class="btn small"
            type="button"
            :disabled="listLoading || currentPage >= totalPages"
            @click="goPage(currentPage + 1)"
          >
            下一页
          </button>
        </div>
      </footer>
    </section>

    <section v-if="editingId !== null" class="panel">
      <header class="panel-head">
        <h2>编辑商品 #{{ editingId }}</h2>
        <span class="hint">PUT /api/product/{id}</span>
      </header>

      <div class="panel-body">
        <form class="form-grid" @submit.prevent="submitEdit">
          <label class="field">
            <span class="label-text">商品名称 <em class="rule">2–20 字，必填</em></span>
            <input v-model="editForm.name" type="text" placeholder="例如 手冲咖啡壶" />
          </label>

          <label class="field">
            <span class="label-text">价格 <em class="rule">≥ 0.01，2 位小数，必填</em></span>
            <input v-model="editForm.price" type="text" inputmode="decimal" placeholder="129.00" />
          </label>

          <label class="field">
            <span class="label-text">联系电话 <em class="rule">11 位手机号，可不填</em></span>
            <input
              v-model="editForm.contactPhone"
              type="text"
              inputmode="tel"
              placeholder="13800138000"
            />
          </label>

          <label class="field wide">
            <span class="label-text">描述 <em class="rule">≤ 100 字</em></span>
            <textarea v-model="editForm.description" placeholder="商品描述，可不填"></textarea>
          </label>

          <div class="btn-row wide">
            <button class="btn primary" type="submit" :disabled="editLoading">
              {{ editLoading ? '保存中…' : '保存修改' }}
            </button>
            <button class="btn" type="button" :disabled="editLoading" @click="cancelEdit">取消</button>
          </div>
        </form>

        <p v-if="maskedPhone" class="inline-hint">
          后端返回的联系电话已脱敏（{{ maskedPhone }}），留空表示不修改；要更新请填写完整 11 位号码。
        </p>
        <p class="inline-hint">
          更新接口只接收 name / price / contactPhone / description，库存与状态不在此接口范围内。
        </p>
      </div>
    </section>

    <div class="response-grid">
      <ResponsePanel
        :result="listResult"
        empty-hint="点击「查询」后，这里显示分页响应（total / pageNum / pageSize / records）。"
      />
      <ResponsePanel
        :result="writeResult"
        empty-hint="保存修改或删除商品后，这里显示最近一次写操作的响应。"
      />
    </div>
  </div>
</template>

<style scoped>
.manage-layout {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.query-grid {
  display: grid;
  grid-template-columns:
    minmax(150px, 1.6fr) repeat(3, minmax(100px, 1fr))
    minmax(90px, 0.8fr) auto;
  gap: 12px 14px;
  align-items: end;
}

.query-actions {
  display: flex;
  gap: 8px;
}

.table-wrap {
  overflow-x: auto;
  border-top: 1px solid var(--line);
}

.data-table {
  width: 100%;
  min-width: 920px;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th,
.data-table td {
  padding: 9px 14px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  white-space: nowrap;
}

.data-table th {
  background: #f7f9fb;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--ink-faint);
}

.data-table tbody tr:hover {
  background: #fafbfc;
}

.data-table tbody tr.editing {
  background: rgba(30, 90, 168, 0.06);
}

.row-actions {
  display: flex;
  gap: 4px;
}

.empty-cell {
  padding: 26px 14px;
  text-align: center;
  white-space: normal;
  color: var(--ink-faint);
}

.btn.ghost.danger {
  color: var(--red);
}

.btn.ghost.danger:hover {
  background: rgba(180, 35, 24, 0.07);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
}

.pager-meta {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--ink-soft);
}

.pager-actions {
  display: flex;
  gap: 8px;
}

.response-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  align-items: start;
}

@media (max-width: 1100px) {
  .query-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .response-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .query-grid {
    grid-template-columns: 1fr;
  }

  .pager {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
