<script setup>
import { computed, reactive, ref } from 'vue'
import { sendRequest } from '../api/http.js'
import ResponsePanel from '../components/ResponsePanel.vue'
import StatusStamp from '../components/StatusStamp.vue'

const scenarios = [
  {
    id: 'validation',
    title: '参数校验失败',
    method: 'POST',
    path: '/api/product',
    body: {
      name: 'A',
      price: 0,
      stock: -1,
      contactPhone: '12345678901x',
      description: '超'.repeat(101),
    },
    expectedHttp: 400,
    expectedCode: 40000,
    note: '触发 @Valid + MethodArgumentNotValidException；message 会按字段拼接所有错误。',
  },
  {
    id: 'product-not-found',
    title: '商品不存在',
    method: 'GET',
    path: '/api/product/999999',
    expectedHttp: 400,
    expectedCode: 10001,
    note: '走 ProductServiceImpl.getById 的空结果分支，抛出 PRODUCT_NOT_FOUND。',
  },
  {
    id: 'stock-decrease-validation',
    title: '扣减参数校验失败',
    method: 'POST',
    path: '/api/product/1/stock/decrease',
    body: {
      count: 0,
      reason: '',
    },
    expectedHttp: 400,
    expectedCode: 40000,
    note: 'count 违反 @Min(1)，空 reason 违反 @Size(min=1)；确认两个字段的错误都会拼接进 message。',
  },
  {
    id: 'stock-product-not-found',
    title: '扣减不存在的商品',
    method: 'POST',
    path: '/api/product/999999/stock/decrease',
    body: {
      count: 1,
      reason: '验收扣减',
    },
    expectedHttp: 400,
    expectedCode: 10001,
    note: 'StockLogServiceImpl 先查商品，空结果抛 PRODUCT_NOT_FOUND，不会写 product_stock_log。',
  },
  {
    id: 'business',
    title: '业务异常',
    method: 'GET',
    path: '/api/lab/businessException',
    expectedHttp: 400,
    expectedCode: 10001,
    note: 'BusinessException 由全局处理器转成统一 Result，HTTP 400。',
  },
  {
    id: 'not-found-route',
    title: '接口不存在',
    method: 'GET',
    path: '/api/no-such-endpoint',
    expectedHttp: 200,
    expectedCode: 40400,
    note: 'Spring Boot 3 抛 NoResourceFoundException；处理器未标注 @ResponseStatus，所以 HTTP 仍是 200。',
  },
  {
    id: 'system',
    title: '系统异常',
    method: 'GET',
    path: '/api/lab/exception',
    expectedHttp: 200,
    expectedCode: 50000,
    note: 'Exception 兜底处理；响应 HTTP 200、业务码 50000，完整堆栈记录在日志中。',
  },
]

const results = reactive({})
const running = reactive({})
const runningAll = ref(false)

async function run(scenario) {
  if (running[scenario.id]) return

  running[scenario.id] = true
  try {
    results[scenario.id] = await sendRequest({
      method: scenario.method,
      path: scenario.path,
      body: scenario.body ?? null,
    })
  } finally {
    running[scenario.id] = false
  }
}

async function runAll() {
  if (runningAll.value) return

  runningAll.value = true
  try {
    for (const scenario of scenarios) {
      await run(scenario)
    }
  } finally {
    runningAll.value = false
  }
}

function clearAll() {
  for (const key of Object.keys(results)) {
    delete results[key]
  }
}

function verdict(scenario) {
  const result = results[scenario.id]
  if (!result) return null

  const codeOk = result.json?.code === scenario.expectedCode
  const httpOk = result.httpStatus === scenario.expectedHttp
  return { pass: codeOk && httpOk, codeOk, httpOk }
}

const summary = computed(() => {
  const done = scenarios.filter(scenario => results[scenario.id])
  if (done.length === 0) return ''
  const passed = done.filter(scenario => verdict(scenario)?.pass).length
  return `${passed} / ${done.length} 通过`
})
</script>

<template>
  <div>
    <div class="lab-actions">
      <span v-if="summary" class="summary">{{ summary }}</span>
      <button class="btn primary" type="button" :disabled="runningAll" @click="runAll">
        {{ runningAll ? '执行中…' : '全部执行' }}
      </button>
      <button class="btn" type="button" :disabled="runningAll" @click="clearAll">清除结果</button>
    </div>

    <div class="scenario-grid">
      <article v-for="scenario in scenarios" :key="scenario.id" class="scenario">
        <header class="scenario-head">
          <div>
            <h3>{{ scenario.title }}</h3>
            <p class="expect">预期 HTTP {{ scenario.expectedHttp }} · 业务码 {{ scenario.expectedCode }}</p>
          </div>
          <StatusStamp
            v-if="verdict(scenario)"
            :tone="verdict(scenario).pass ? 'pass' : 'fail'"
            :label="verdict(scenario).pass ? '通过' : '不符预期'"
          />
        </header>

        <p class="note">{{ scenario.note }}</p>
        <p class="path-line">{{ scenario.method }} {{ scenario.path }}</p>

        <div class="scenario-actions">
          <button
            class="btn small"
            type="button"
            :disabled="running[scenario.id] || runningAll"
            @click="run(scenario)"
          >
            {{ running[scenario.id] ? '执行中…' : '执行' }}
          </button>
          <span v-if="verdict(scenario) && !verdict(scenario).codeOk" class="mismatch">
            实际业务码 {{ results[scenario.id].json?.code ?? '—' }}
          </span>
          <span v-if="verdict(scenario) && !verdict(scenario).httpOk" class="mismatch">
            实际 HTTP {{ results[scenario.id].httpStatus ?? '—' }}
          </span>
        </div>

        <ResponsePanel v-if="results[scenario.id]" :result="results[scenario.id]" compact />
      </article>
    </div>
  </div>
</template>

<style scoped>
.lab-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 18px;
}

.summary {
  margin-right: auto;
  font-family: var(--mono);
  font-size: 12px;
  color: var(--ink-soft);
}

.scenario-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  align-items: start;
}

.scenario {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius);
  background: var(--panel);
}

.scenario-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.scenario h3 {
  font-size: 15px;
  font-weight: 650;
}

.expect {
  margin-top: 2px;
  font-family: var(--mono);
  font-size: 11px;
  color: var(--ink-faint);
}

.note {
  font-size: 12.5px;
  color: var(--ink-soft);
}

.path-line {
  padding: 6px 9px;
  border-left: 2px solid var(--line-strong);
  background: #f2f5f7;
  font-family: var(--mono);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.scenario-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.mismatch {
  font-size: 12px;
  color: var(--red);
}

@media (max-width: 1100px) {
  .scenario-grid {
    grid-template-columns: 1fr;
  }
}
</style>
