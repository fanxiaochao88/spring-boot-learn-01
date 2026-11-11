import { reactive } from 'vue'

let queryRowSeq = 0

export function makeQueryRow(key = '', value = '') {
  queryRowSeq += 1
  return { id: `q${queryRowSeq}`, key, value }
}

function emptyDraft() {
  return {
    sourceId: null,
    method: 'GET',
    path: '/api/lab/hello',
    pathParams: {},
    query: [makeQueryRow()],
    body: '',
  }
}

export const workspace = reactive({
  view: 'product',
  draft: emptyDraft(),
})

export function switchView(view) {
  workspace.view = view
}

export function loadEndpoint(endpoint) {
  workspace.draft = {
    sourceId: endpoint.id,
    method: endpoint.method,
    path: endpoint.path,
    pathParams: { ...(endpoint.pathParams || {}) },
    query: endpoint.query?.length
      ? endpoint.query.map(row => makeQueryRow(row.key ?? '', row.value ?? ''))
      : [makeQueryRow()],
    body: endpoint.body === undefined ? '' : JSON.stringify(endpoint.body, null, 2),
  }
  workspace.view = 'debug'
}

export function resetDraft() {
  workspace.draft = emptyDraft()
}
