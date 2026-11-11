<script setup>
import { computed, reactive, ref } from 'vue'
import { sendRequest } from '../api/http.js'
import ResponsePanel from '../components/ResponsePanel.vue'
import StatusStamp from '../components/StatusStamp.vue'

const legalSample = {
  name: '手冲咖啡壶',
  price: '129.00',
  stock: '20',
  contactPhone: '13800138000',
  description: '容量 600ml，耐热玻璃，适合 1-2 人',
}

const form = reactive({ ...legalSample })
const submitting = ref(false)
const createResult = ref(null)
const createdProduct = ref(null)

const detailId = ref('')
const detailLoading = ref(false)
const detailResult = ref(null)

const detailProduct = computed(() =>
  detailResult.value?.success ? detailResult.value.json?.data ?? null : null,
)

const stockWarningText = computed(() => {
  const value = detailProduct.value?.stockWarning
  if (value == null) return '接口未返回'
  return value ? '低于阈值' : '正常'
})

function fillLegal() {
  Object.assign(form, legalSample)
}

function fillIllegal() {
  Object.assign(form, {
    name: 'A',
    price: '0',
    stock: '-1',
    contactPhone: '12345678901x',
    description: '超'.repeat(101),
  })
}

function toPayload() {
  const price = form.price.trim() === '' ? null : Number(form.price)
  const stock = form.stock.trim() === '' ? null : Number(form.stock)
  return {
    name: form.name,
    price: Number.isFinite(price) ? price : null,
    stock: Number.isFinite(stock) ? stock : null,
    contactPhone: form.contactPhone.trim() || null,
    description: form.description.trim() || null,
  }
}

async function submitCreate() {
  if (submitting.value) return
  submitting.value = true
  createdProduct.value = null
  try {
    const result = await sendRequest({ method: 'POST', path: '/api/product', body: toPayload() })
    createResult.value = result
    if (result.success && result.json?.data?.id != null) {
      createdProduct.value = result.json.data
      detailId.value = String(result.json.data.id)
    }
  } finally {
    submitting.value = false
  }
}

async function loadDetail() {
  const id = detailId.value.trim()
  if (!id || detailLoading.value) return

  detailLoading.value = true
  try {
    detailResult.value = await sendRequest({
      method: 'GET',
      path: `/api/product/${encodeURIComponent(id)}`,
    })
  } finally {
    detailLoading.value = false
  }
}

function viewCreated() {
  if (createdProduct.value?.id == null) return
  detailId.value = String(createdProduct.value.id)
  loadDetail()
}

function formatPrice(value) {
  if (value == null || value === '') return '—'
  const num = Number(value)
  return Number.isFinite(num) ? `¥ ${num.toFixed(2)}` : String(value)
}
</script>

<template>
  <div class="product-layout">
    <div class="column">
      <section class="panel">
        <header class="panel-head">
          <h2>新增商品</h2>
          <span class="hint">POST /api/product</span>
        </header>
        <div class="panel-body">
          <form class="form-grid" @submit.prevent="submitCreate">
            <label class="field">
              <span class="label-text">商品名称 <em class="rule">2–20 字</em></span>
              <input v-model="form.name" type="text" placeholder="例如 手冲咖啡壶" />
            </label>

            <label class="field">
              <span class="label-text">价格 <em class="rule">≥ 0.01，2 位小数</em></span>
              <input v-model="form.price" type="text" inputmode="decimal" placeholder="129.00" />
            </label>

            <label class="field">
              <span class="label-text">库存 <em class="rule">≥ 0</em></span>
              <input v-model="form.stock" type="text" inputmode="numeric" placeholder="20" />
            </label>

            <label class="field">
              <span class="label-text">联系电话 <em class="rule">11 位手机号，可不填</em></span>
              <input v-model="form.contactPhone" type="text" inputmode="tel" placeholder="13800138000" />
            </label>

            <label class="field wide">
              <span class="label-text">描述 <em class="rule">≤ 100 字</em></span>
              <textarea v-model="form.description" placeholder="商品描述，可不填"></textarea>
            </label>

            <div class="btn-row wide">
              <button class="btn primary" type="submit" :disabled="submitting">
                {{ submitting ? '提交中…' : '提交新增' }}
              </button>
              <button class="btn" type="button" @click="fillLegal">填入合法示例</button>
              <button class="btn" type="button" @click="fillIllegal">填入非法示例</button>
            </div>
          </form>

          <div v-if="createdProduct" class="created-strip">
            <StatusStamp tone="pass" label="已创建" />
            <span class="created-text">
              #{{ createdProduct.id }} · {{ createdProduct.name }}
            </span>
            <button class="btn ghost small" type="button" @click="viewCreated">查看详情</button>
          </div>
        </div>
      </section>

      <ResponsePanel
        :result="createResult"
        empty-hint="提交新增后，这里显示 HTTP 状态、业务码和完整的 ProductVO。"
      />
    </div>

    <div class="column">
      <section class="panel">
        <header class="panel-head">
          <h2>商品详情</h2>
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
            <button class="btn primary" type="button" :disabled="detailLoading || !detailId" @click="loadDetail">
              {{ detailLoading ? '查询中…' : '查询详情' }}
            </button>
          </div>
          <p class="inline-hint">新增成功后会自动填入返回的商品 ID；也可以手动输入一个不存在的 ID 验证 10001。</p>
        </div>
      </section>

      <article v-if="detailProduct" class="record">
        <div class="record-top">
          <div>
            <p class="record-id">ID {{ detailProduct.id }}</p>
            <h3>{{ detailProduct.name }}</h3>
          </div>
          <StatusStamp
            :tone="detailProduct.status === 1 ? 'pass' : 'warn'"
            :label="detailProduct.statusText || '未知状态'"
          />
        </div>
        <dl class="record-grid">
          <div>
            <dt>价格</dt>
            <dd>{{ formatPrice(detailProduct.price) }}</dd>
          </div>
          <div>
            <dt>库存</dt>
            <dd>{{ detailProduct.stock ?? '—' }}</dd>
          </div>
          <div>
            <dt>联系电话</dt>
            <dd class="mono">{{ detailProduct.contactPhone || '—' }}</dd>
          </div>
          <div>
            <dt>库存预警</dt>
            <dd>{{ stockWarningText }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd class="mono">{{ detailProduct.createTime || '—' }}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd class="mono">{{ detailProduct.updateTime || '—' }}</dd>
          </div>
          <div class="wide">
            <dt>描述</dt>
            <dd>{{ detailProduct.description || '—' }}</dd>
          </div>
        </dl>
      </article>

      <ResponsePanel
        :result="detailResult"
        empty-hint="输入商品 ID 查询后，这里显示详情响应；不存在的 ID 会返回业务码 10001。"
      />
    </div>
  </div>
</template>

<style scoped>
.product-layout {
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

.created-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  background: rgba(15, 123, 95, 0.05);
  font-size: 13px;
}

.created-text {
  font-family: var(--mono);
  font-size: 12px;
}

.lookup-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
}

.record {
  border: 1px solid var(--line-strong);
  border-left: 3px solid var(--blue);
  background: #fbfcfd;
}

.record-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.record-id {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--ink-faint);
}

.record-top h3 {
  margin-top: 2px;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.record-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.record-grid > div {
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
}

.record-grid > div:nth-child(odd) {
  border-right: 1px solid var(--line);
}

.record-grid > div.wide {
  grid-column: 1 / -1;
  border-right: 0;
}

.record-grid dt {
  font-size: 11px;
  color: var(--ink-faint);
}

.record-grid dd {
  margin-top: 2px;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .product-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .record-grid {
    grid-template-columns: 1fr;
  }

  .record-grid > div:nth-child(odd) {
    border-right: 0;
  }
}
</style>
