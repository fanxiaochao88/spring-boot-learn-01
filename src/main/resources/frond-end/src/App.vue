<script setup>
import { computed, onMounted, ref } from 'vue'
import { endpointGroups } from './api/endpoints.js'
import { loadEndpoint, switchView, workspace } from './stores/workspace.js'
import DebugView from './views/DebugView.vue'
import ErrorLabView from './views/ErrorLabView.vue'
import ProductManageView from './views/ProductManageView.vue'
import ProductView from './views/ProductView.vue'

const views = [
  {
    key: 'product',
    label: '商品验收',
    title: '商品验收',
    caption: '实际提交表单并读取详情，核对新增结果、字段脱敏与参数校验的真实表现。',
    component: ProductView,
  },
  {
    key: 'product-manage',
    label: '商品管理',
    title: '商品管理',
    caption: '分页查询商品，按行修改或删除，核对 PageResult 结构与写操作的业务码。',
    component: ProductManageView,
  },
  {
    key: 'debug',
    label: '接口调试',
    title: '接口调试',
    caption: '自由组合 method、path、query 与 JSON body；后续新接口从左侧清单载入即可测试。',
    component: DebugView,
  },
  {
    key: 'lab',
    label: '异常演练',
    title: '异常演练',
    caption: '一次跑完校验失败、业务异常、404 与 500，确认全局异常处理器的状态码和业务码。',
    component: ErrorLabView,
  },
]

const activeView = computed(() => views.find(view => view.key === workspace.view) || views[0])

const health = ref({ state: 'idle', label: '未检查后端', detail: '' })

async function checkBackend() {
  health.value = { state: 'checking', label: '检查后端…', detail: '' }

  try {
    const response = await fetch('/actuator/health')
    const payload = await response.json().catch(() => null)

    if (response.ok && payload?.status === 'UP') {
      health.value = { state: 'up', label: '后端在线', detail: '8089 · UP' }
    } else {
      health.value = { state: 'down', label: '后端异常', detail: `HTTP ${response.status}` }
    }
  } catch {
    health.value = { state: 'down', label: '无法连接', detail: '请先启动 Spring Boot' }
  }
}

onMounted(checkBackend)
</script>

<template>
  <div class="shell">
    <aside class="rail">
      <header class="brand">
        <div class="brand-mark">M</div>
        <div>
          <p class="brand-name">Mall 验收台</p>
          <p class="brand-sub">mall-product · 本地联调</p>
        </div>
      </header>

      <nav class="rail-nav" aria-label="工作区">
        <button
          v-for="view in views"
          :key="view.key"
          class="nav-item"
          type="button"
          :class="{ active: workspace.view === view.key }"
          @click="switchView(view.key)"
        >
          {{ view.label }}
        </button>
      </nav>

      <section v-for="group in endpointGroups" :key="group.name" class="rail-section">
        <h2>{{ group.name }}</h2>
        <button
          v-for="endpoint in group.endpoints"
          :key="endpoint.id"
          class="endpoint"
          type="button"
          :class="{ active: workspace.view === 'debug' && workspace.draft.sourceId === endpoint.id }"
          @click="loadEndpoint(endpoint)"
        >
          <span class="method" :data-method="endpoint.method">{{ endpoint.method }}</span>
          <span class="endpoint-name">{{ endpoint.name }}</span>
        </button>
      </section>

      <footer class="rail-foot">
        <p>Vite 5173 → 后端 8089</p>
        <p>新增接口：src/api/endpoints.js</p>
      </footer>
    </aside>

    <main class="main">
      <header class="masthead">
        <div>
          <h1>{{ activeView.title }}</h1>
          <p class="lede">{{ activeView.caption }}</p>
        </div>
        <button
          class="health"
          type="button"
          :data-state="health.state"
          title="点击重新检查 /actuator/health"
          @click="checkBackend"
        >
          <span class="health-dot" aria-hidden="true"></span>
          <span>{{ health.label }}</span>
          <span v-if="health.detail" class="health-detail">{{ health.detail }}</span>
        </button>
      </header>

      <component :is="activeView.component" />
    </main>
  </div>
</template>
