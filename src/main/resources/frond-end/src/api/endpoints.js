/**
 * 接口清单。
 *
 * 后续新增后端接口时，只要在这里补一条配置，左侧侧边栏会自动出现，
 * 点击后载入「接口调试」工作区。字段说明：
 *
 *   id          唯一标识
 *   name        展示名称
 *   method      GET / POST / PUT / PATCH / DELETE
 *   path        请求路径，可含 {id} 之类的占位符
 *   summary     接口说明，载入时显示在调试台上方
 *   pathParams  路径占位符的默认值，例如 { id: '1' }
 *   query       Query 参数示例，例如 [{ key: 'pageNum', value: '1' }]
 *   body        JSON Body 示例，对象会自动格式化
 *
 * 示例：
 *   {
 *     id: 'product-update',
 *     name: '更新商品',
 *     method: 'PUT',
 *     path: '/api/product/{id}',
 *     summary: '按 ID 更新商品，name/price 为必填。',
 *     pathParams: { id: '1' },
 *     body: { name: '手冲咖啡壶', price: 139.0, contactPhone: '13800138000' },
 *   }
 */
export const endpointGroups = [
  {
    name: '商品',
    endpoints: [
      {
        id: 'product-create',
        name: '新增商品',
        method: 'POST',
        path: '/api/product',
        summary: '创建商品并返回落库结果；status 由服务端固定为 1（上架）。',
        body: {
          name: '手冲咖啡壶',
          price: 129.0,
          stock: 20,
          contactPhone: '13800138000',
          description: '容量 600ml，耐热玻璃，适合 1-2 人',
        },
      },
      {
        id: 'product-detail',
        name: '商品详情',
        method: 'GET',
        path: '/api/product/{id}',
        summary: '按 ID 查询商品；不存在时返回业务码 10001。',
        pathParams: { id: '1' },
      },
      {
        id: 'product-page',
        name: '商品分页',
        method: 'GET',
        path: '/api/product',
        summary: '分页查询商品；pageSize 上限 100，keyword 按名称模糊匹配，status 1=上架 0=下架。',
        query: [
          { key: 'pageNum', value: '1' },
          { key: 'pageSize', value: '10' },
          { key: 'keyword', value: '' },
          { key: 'minPrice', value: '' },
          { key: 'maxPrice', value: '' },
          { key: 'status', value: '' },
        ],
      },
      {
        id: 'product-update',
        name: '更新商品',
        method: 'PUT',
        path: '/api/product/{id}',
        summary: '按 ID 更新商品；name/price 必填，stock/status 不在更新范围内。',
        pathParams: { id: '1' },
        body: {
          name: '手冲咖啡壶',
          price: 139.0,
          contactPhone: '13800138000',
          description: '容量 600ml',
        },
      },
      {
        id: 'product-delete',
        name: '删除商品',
        method: 'DELETE',
        path: '/api/product/{id}',
        summary: '按 ID 逻辑删除商品；成功时 data 为空，再次查询详情会返回 10001。',
        pathParams: { id: '1' },
      },
    ],
  },
  {
    name: '连通与异常演练',
    endpoints: [
      {
        id: 'lab-hello',
        name: '连通性示例',
        method: 'GET',
        path: '/api/lab/hello',
        summary: '最简单的成功响应，用于确认前后端链路是否打通。',
      },
      {
        id: 'lab-business',
        name: '业务异常',
        method: 'GET',
        path: '/api/lab/businessException',
        summary: '抛出 BusinessException，预期 HTTP 400 / 业务码 10001。',
      },
      {
        id: 'lab-system',
        name: '系统异常',
        method: 'GET',
        path: '/api/lab/exception',
        summary: '抛出 RuntimeException，预期 HTTP 200 / 业务码 50000。',
      },
      {
        id: 'not-found-route',
        name: '接口不存在',
        method: 'GET',
        path: '/api/no-such-endpoint',
        summary: '触发 NoResourceFoundException，预期 HTTP 200 / 业务码 40400。',
      },
    ],
  },
]

export const allEndpoints = endpointGroups.flatMap(group => group.endpoints)
