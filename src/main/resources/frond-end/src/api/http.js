/**
 * 统一请求封装。
 *
 * - 路径中的 {id} 占位符会被 pathParams 替换
 * - query 中的空 key 行会被忽略
 * - 无论 HTTP 状态如何，都返回同一形状的对象，方便结果面板统一展示
 * - success 以业务码 code === 0 为准；非 Result 结构（如 /actuator/health）回退到 HTTP ok
 */
export async function sendRequest({
  method = 'GET',
  path = '',
  pathParams = {},
  query = [],
  body = null,
} = {}) {
  const upperMethod = method.toUpperCase()
  let url = String(path || '').trim()

  if (!url) {
    throw new Error('请填写请求路径')
  }

  for (const [key, value] of Object.entries(pathParams || {})) {
    url = url.replaceAll(`{${key}}`, encodeURIComponent(value ?? ''))
  }

  const search = new URLSearchParams()
  for (const row of query || []) {
    if (!row || !row.key) continue
    search.append(row.key, row.value ?? '')
  }
  if ([...search.keys()].length > 0) {
    url += (url.includes('?') ? '&' : '?') + search.toString()
  }

  const canHaveBody = !['GET', 'HEAD'].includes(upperMethod)
  const hasBody = canHaveBody && body !== null && body !== undefined && String(body).trim() !== ''

  const headers = { Accept: 'application/json' }
  const init = { method: upperMethod, headers }
  if (hasBody) {
    headers['Content-Type'] = 'application/json'
    init.body = typeof body === 'string' ? body : JSON.stringify(body)
  }

  const startedAt = performance.now()

  try {
    const response = await fetch(url, init)
    const elapsedMs = Math.round(performance.now() - startedAt)
    const rawText = await response.text()

    let json = null
    try {
      json = rawText ? JSON.parse(rawText) : null
    } catch {
      // 非 JSON 响应（例如代理错误页），保留 rawText 即可
    }

    return {
      request: { method: upperMethod, url, body: init.body ?? null },
      httpStatus: response.status,
      httpStatusText: response.statusText,
      httpOk: response.ok,
      elapsedMs,
      headers: Object.fromEntries(response.headers.entries()),
      rawText,
      json,
      networkError: null,
      success:
        json !== null && typeof json === 'object' && 'code' in json
          ? json.code === 0
          : response.ok,
      at: new Date().toISOString(),
    }
  } catch (error) {
    return {
      request: { method: upperMethod, url, body: init.body ?? null },
      httpStatus: null,
      httpStatusText: '',
      httpOk: false,
      elapsedMs: Math.round(performance.now() - startedAt),
      headers: {},
      rawText: '',
      json: null,
      networkError: error?.message || String(error),
      success: false,
      at: new Date().toISOString(),
    }
  }
}
