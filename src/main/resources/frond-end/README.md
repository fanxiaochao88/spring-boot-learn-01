# Mall 验收台

配合 `mall-product` 后端使用的本地联调前端，基于 Vue 3 + Vite。

五个工作区：

- **商品验收**：新增商品、按 ID 查询详情，展示字段脱敏、状态文案以及完整的统一响应。
- **商品管理**：分页查询商品，按行修改或删除，操作后自动刷新列表。
- **库存验收**：查询商品库存、提交扣减，自动刷新并记录本次会话的扣减前后数量。
- **接口调试**：自由组合 method / path / query / JSON body，作为后续接口的通用测试台。
- **异常演练**：一次跑完参数校验失败、业务异常、接口 404、系统 500 与库存扣减异常，并对照预期判断是否通过。

## 启动

1. 先启动后端（默认 `http://localhost:8089`，需要本地 MySQL 已就绪）。
2. 在本目录执行：

```bash
npm install
npm run dev
```

3. 打开终端里输出的地址，默认是 `http://localhost:5173`。

页面右上角会请求 `/actuator/health`，显示「后端在线」即说明前后端已打通。

## 为什么不需要改后端 CORS

Vite 开发服务器把 `/api` 和 `/actuator` 代理到 `http://localhost:8089`，浏览器看到的始终是同源请求，因此后端无需额外配置 CORS。

需要改目标地址时，二选一：

```bash
# 方式一：启动时指定
VITE_API_TARGET=http://192.168.1.20:8089 npm run dev
```

或直接修改 `vite.config.js` 中的默认 `target`。

## 新增一个接口怎么接入

编辑 `src/api/endpoints.js`，追加一条配置即可，左侧清单会自动出现：

```js
{
  id: 'product-update',
  name: '更新商品',
  method: 'PUT',
  path: '/api/product/{id}',
  summary: '按 ID 更新商品。',
  pathParams: { id: '1' },
  body: { name: '手冲咖啡壶', price: 139.0 },
}
```

`path` 里的 `{id}` 会自动生成路径参数输入框，`body` 会自动格式化成 JSON 文本。

## 库存扣减与流水

接口：`POST /api/product/{id}/stock/decrease`

```json
{ "count": 1, "reason": "验收扣减" }
```

- `count`：1–100，必填；`reason`：≤ 100 字，可空（前端留空时发送 `null`，而不是空字符串）。
- 成功返回 `Result<Void>`（`data` 为 `null`），商品库存减少并写入 `product_stock_log`。
- 业务码：商品不存在 `10001`、库存不足 `10002`、商品已下架 `10003`，均为 HTTP 400。
- 「库存验收」工作区会自动刷新详情，并记录**本次页面会话**的扣减前后数量。

后端目前没有暴露流水查询接口，页面无法直接读取 `product_stock_log`。完整流水请到 MySQL 核对：

```sql
SELECT id, product_id, change_amount, before_stock, after_stock, reason, create_time
FROM product_stock_log
WHERE product_id = 1
ORDER BY id DESC;
```

扣减失败时不会写流水，事务回滚后库存也不会变化。

## 目录结构

```text
frond-end/
├── index.html
├── vite.config.js          # 开发代理配置
├── package.json
└── src/
    ├── App.vue             # 侧边栏、工作区切换、后端健康检查
    ├── api/
    │   ├── http.js         # 统一请求封装（路径参数、query、耗时、原始响应）
    │   └── endpoints.js    # 接口清单（新增接口改这里）
    ├── stores/
    │   └── workspace.js    # 当前工作区与调试台草稿
    ├── components/
    │   ├── ResponsePanel.vue
    │   └── StatusStamp.vue
    └── views/
        ├── ProductView.vue
        ├── ProductManageView.vue
        ├── StockView.vue
        ├── DebugView.vue
        └── ErrorLabView.vue
```

## 构建

```bash
npm run build     # 产物在 dist/
npm run preview   # 用预览服务器打开，代理配置同样生效
```

如果想让 Spring Boot 直接托管前端：

```bash
npm run build
# 将 dist/ 中的内容拷贝到 src/main/resources/static/
```

然后访问 `http://localhost:8089/`。注意此时走的是后端同源，不再经过 Vite 代理。

`src/main/resources/frond-end` 已在 Maven 的 resources 中排除，`node_modules` 不会被打进 jar。

## 两个需要留意的后端行为

- **接口不存在（404 路由）**：`GlobalExceptionHandler` 处理 `NoResourceFoundException` 时没有 `@ResponseStatus`，所以 HTTP 状态是 200，业务码是 `40400`。
- **系统异常（500）**：兜底 `Exception` 处理器同样返回 HTTP 200 + 业务码 `50000`。判断请求是否成功时，以业务码 `code === 0` 为准。
