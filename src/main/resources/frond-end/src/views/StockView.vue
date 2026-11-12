<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { sendRequest } from '../api/http.js'
import ResponsePanel from '../components/ResponsePanel.vue'
import StatusStamp from '../components/StatusStamp.vue'

const detailId = ref('1')
const detailLoading = ref(false)
const detailResult = ref(null)

const product = computed(() =>
  detailResult.value?.success ? detailResult.value.json?.data ?? null : null,
)

const form = reactive({ count: '1', reason: '验收扣减' })
const decreasing = ref(false)
const decreaseResult = ref(null)

const records = ref([])
let recordSeq = 0

const countValue = computed(() => {
  const raw = form.count.trim()
  if (!raw) return null
  const num = Number(raw)
  return Number.isInteger(num) ? num : null
})

const reasonValue = computed(() => form.reason.trim() || null)

const formError = computed(() => {
  if (countValue.value == null) return '扣减数量必须是整数'
  if (countValue.value < 1 || countValue.value > 100) return '扣减数量需在 1–100 之间'
  if (reasonValue.value && reasonValue.value.length > 100) return '原因不能超过 100 个字符'
  return ''
})

const stockEnough = computed(() => {
  if (!product.value || countValue.value == null) return true
  return Number(product.value.stock) >= countValue.value
})

async function fetchDetail(id) {
  if (!id || detailLoading.value) return null

  detailLoading.value = true
  try {
    const result = await sendRequest({
      method: 'GET',
      path: `/api/product/${encodeURIComponent(id)}`,
    })
    detailResult.value = result
    return result
  } finally {
    detailLoading.value = false
  }
}

function loadDetail() {
  return fetchDetail(detailId.value.trim())
}

async function submitDecrease() {
  if (decreasing.value || formError.value || !product.value) return

  const id = String(product.value.id)
  const count = countValue.value
  const beforeStock = product.value.stock ?? null
  const reason = reasonValue.value

  decreasing.value = true
  records.value.unshift({
    id: `r${(recordSeq += 1)}`,
    at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    productId: id,
    productName: product.value.name ?? '',
    count,
    beforeStock,
    afterStock: null,
    reason: reason ?? '—',
    code: null,
    message: '请求中…',
    success: false,
  })
  const record = records.value[0]

  try {
    const result = await sendRequest({
      method: 'POST',
      path: `/api/product/${encodeURIComponent(id)}/stock/decrease`,
      body: { count, reason },
    })
    decreaseResult.value = result
    record.code = result.json?.code ?? null
    record.message = result.json?.message ?? result.networkError ?? `HTTP ${result.httpStatus}`
    record.success = result.success

    detailId.value = id
    const refreshed = await fetchDetail(id)
    if (result.success && refreshed?.success) {
      record.afterStock = refreshed.json?.data?.stock ?? null
    }
  } finally {
    decreasing.value = false
  }
}

function fillOverflow() {
  if (!product.value) return
  form.count = String(Math.min(100, Number(product.value.stock ?? 0) + 1))
}

function clearRecords() {
  records.value = []
}

onMounted(loadDetail)
</script>

<template>
  <div class="stock-layout">
    <div class="column">
      <section class="panel">
        <header class="panel-head">
          <h2>查询商品库存</h2>
          <span class="hint">GET /api/product/{id}</span>
        </header>
        <div class="panel-body">
          <div class="lookup-row">
            <label class="field">
              <span class="label-text">商品 ID</span>
              <input
                v-model.trim="detailId"
                type="text"
                inputmode="numeric"
                placeholder="例如 1"
                @keydown.enter.prevent="loadDetail"
              />
            </label>
            <button
              class="btn primary"
              type="button"
              :disabled="detailLoading || !detailId"
              @click="loadDetail"
            >
              {{ detailLoading ? '查询中…' : '查询库存' }}
            </button>
          </div>
          <p class="inline-hint">进入本工作区会自动查询 ID=1；查询失败时无法提交扣减。</p>
        </div>
      </section>

      <article v-if="product" class="stock-card">
        <div class="stock-head">
          <div>
            <p class="stock-id">ID {{ product.id }}</p>
            <h3>{{ product.name }}</h3>
          </div>
          <StatusStamp
            :tone="product.status === 1 ? 'pass' : 'warn'"
            :label="product.statusText || (product.status === 1 ? '上架' : '下架')"
          />
        </div>
        <div class="stock-metric">
          <span class="stock-label">当前库存</span>
          <strong class="stock-value">{{ product.stock ?? '—' }}</strong>
        </div>
        <dl class="stock-meta">
          <div>
            <dt>价格</dt>
            <dd>{{ product.price == null ? '—' : `¥ ${Number(product.price).toFixed(2)}` }}</dd>
          </div>
          <div>
            <dt>最近更新</dt>
            <dd class="mono">{{ product.updateTime || '—' }}</dd>
          </div>
        </dl>
      </article>

      <section v-if="product" class="panel">
        <header class="panel-head">
          <h2>扣减库存</h2>
          <span class="hint">POST /api/product/{id}/stock/decrease</span>
        </header>
        <div class="panel-body">
          <form class="form-grid" @submit.prevent="submitDecrease">
            <label class="field">
              <span class="label-text">扣减数量 <em class="rule">1–100，必填</em></span>
              <input v-model="form.count" type="text" inputmode="numeric" placeholder="1" />
            </label>

            <label class="field">
              <span class="label-text">原因 <em class="rule">≤ 100 字，可空</em></span>
              <input v-model="form.reason" type="text" placeholder="例如 验收扣减" />
            </label>

            <div class="btn-row wide">
              <button
                class="btn primary"
                type="submit"
                :disabled="decreasing || !!formError"
              >
                {{ decreasing ? '扣减中…' : '提交扣减' }}
              </button>
              <button class="btn" type="button" :disabled="decreasing" @click="fillOverflow">
                填入超额数量
              </button>
            </div>
          </form>

          <p v-if="formError" class="inline-error">{{ formError }}</p>
          <p v-else-if="!stockEnough" class="inline-error">
            当前库存 {{ product.stock }}，本次扣减会触发业务码 10002（库存不足）。
          </p>
          <p v-else class="inline-hint">
            后端用 UPDATE ... WHERE stock &gt;= count 原子扣减，成功后写 product_stock_log；
            库存 ≥ 100 时受 count 上限限制，无法构造 10002。
          </p>
        </div>
      </section>

      <ResponsePanel
        :result="decreaseResult"
        empty-hint="提交扣减后，这里显示 Result&lt;Void&gt;：成功时 data 为空，失败时看业务码。"
      />
    </div>

    <div class="column">
      <section class="panel">
        <header class="panel-head">
          <h2>本次会话记录</h2>
          <span class="hint">{{ records.length }} 条 · 仅前端内存</span>
        </header>

        <div v-if="records.length" class="table-wrap">
          <table class="ledger-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>商品</th>
                <th>数量</th>
                <th>扣减前</th>
                <th>扣减后</th>
                <th>原因</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in records" :key="record.id">
                <td class="mono">{{ record.at }}</td>
                <td>#{{ record.productId }} · {{ record.productName }}</td>
                <td class="mono">-{{ record.count }}</td>
                <td class="mono">{{ record.beforeStock ?? '—' }}</td>
                <td class="mono">{{ record.afterStock ?? '—' }}</td>
                <td>{{ record.reason }}</td>
                <td>
                  <StatusStamp
                    :tone="record.success ? 'pass' : 'fail'"
                    :label="record.success ? '成功' : '失败'"
                  />
                  <span class="record-msg">
                    code {{ record.code ?? '—' }} · {{ record.message }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-ledger">
          还没有扣减操作。提交后这里会记录本次页面内的扣减前后数量。
        </p>

        <footer class="ledger-foot">
          <button
            class="btn ghost small"
            type="button"
            :disabled="records.length === 0"
            @click="clearRecords"
          >
            清空记录
          </button>
        </footer>
      </section>

      <section class="panel">
        <header class="panel-head">
          <h2>后端流水表</h2>
          <span class="hint">product_stock_log</span>
        </header>
        <div class="panel-body ledger-note">
          <p>
            每次成功扣减都会写一条流水：<code>product_id</code>、<code>change_amount</code>（负数）、
            <code>before_stock</code>、<code>after_stock</code>、<code>reason</code>、
            <code>create_time</code>。
          </p>
          <p>
            后端目前没有提供流水查询接口，页面只能展示本次会话的操作；完整流水请到 MySQL 核对：
          </p>
          <pre class="sql-block">SELECT id, product_id, change_amount, before_stock,
       after_stock, reason, create_time
FROM product_stock_log
WHERE product_id = 1
ORDER BY id DESC;</pre>
          <p class="inline-hint">
            扣减失败（10001 / 10003 / 10002）不会写流水；事务回滚后商品库存也不会变化。
          </p>
        </div>
      </section>

      <ResponsePanel
        :result="detailResult"
        empty-hint="查询商品后，这里显示详情响应；扣减成功会自动按同一 ID 重新查询。"
      />
    </div>
  </div>
</template>

<style scoped>
.stock-layout {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  align-items: start;
}

.column {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.lookup-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
}

.stock-card {
  border: 1px solid var(--line-strong);
  border-left: 3px solid var(--green);
  background: #fbfcfd;
}

.stock-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.stock-id {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--ink-faint);
}

.stock-head h3 {
  margin-top: 2px;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.stock-metric {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.stock-label {
  font-size: 12px;
  color: var(--ink-soft);
}

.stock-value {
  font-family: var(--mono);
  font-size: 28px;
  line-height: 1;
  font-weight: 700;
  color: var(--green);
}

.stock-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.stock-meta > div {
  padding: 10px 16px;
}

.stock-meta > div + div {
  border-left: 1px solid var(--line);
}

.stock-meta dt {
  font-size: 11px;
  color: var(--ink-faint);
}

.stock-meta dd {
  margin-top: 2px;
  font-size: 13px;
}

.table-wrap {
  overflow-x: auto;
  border-top: 1px solid var(--line);
}

.ledger-table {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: 12.5px;
}

.ledger-table th,
.ledger-table td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  white-space: nowrap;
}

.ledger-table th {
  background: #f7f9fb;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-faint);
}

.ledger-table tbody tr:hover {
  background: #fafbfc;
}

.record-msg {
  display: inline-block;
  margin-left: 6px;
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--ink-faint);
}

.empty-ledger {
  padding: 26px 16px;
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-faint);
}

.ledger-foot {
  display: flex;
  justify-content: flex-end;
  padding: 10px 14px;
}

.ledger-note {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-soft);
}

.ledger-note code {
  padding: 0 3px;
  background: #f2f5f7;
  color: var(--ink);
}

.sql-block {
  margin: 2px 0;
  padding: 10px 12px;
  overflow-x: auto;
  background: #101c2b;
  color: #d7e2ec;
  font-family: var(--mono);
  font-size: 11.5px;
  line-height: 1.6;
}

@media (max-width: 1100px) {
  .stock-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .stock-meta {
    grid-template-columns: 1fr;
  }

  .stock-meta > div + div {
    border-left: 0;
    border-top: 1px solid var(--line);
  }
}
</style>
