interface Envelope<T> {
  data: T
}
interface Csrf {
  headerName: string
  token: string
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public traceId?: string,
  ) {
    super(message)
  }
}
let csrf: Csrf | undefined
export function clearCsrf() {
  csrf = undefined
}
export function query(values: object) {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== '' && value !== undefined && value !== null) params.set(key, String(value))
  })
  return params.toString() ? `?${params}` : ''
}
/** Session cookies are HttpOnly. Every mutation uses the server-issued CSRF token. */
export async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown,
  key?: string,
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    csrf ??= await request<Csrf>('/auth/csrf')
    headers[csrf.headerName] = csrf.token
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (key) headers['Idempotency-Key'] = key
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    })
  } catch {
    throw new ApiError(
      0,
      'NETWORK',
      '连接失败或请求超时。写操作可能已完成，请刷新核对，或以原数据重试。',
    )
  }
  if (response.status === 204) return undefined as T
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) clearCsrf()
    if (response.status === 403 && path !== '/auth/login')
      window.dispatchEvent(new Event('authorization-changed'))
    if (response.status === 401 && path !== '/auth/me' && path !== '/auth/login')
      window.dispatchEvent(new Event('session-expired'))
    const fields = payload?.fieldErrors
      ?.map((f: { field: string; message: string }) => `${f.field}: ${f.message}`)
      .join('；')
    throw new ApiError(
      response.status,
      payload?.code ?? 'HTTP_ERROR',
      fields || payload?.message || `请求失败 (${response.status})`,
      payload?.traceId,
    )
  }
  if (!payload || !('data' in payload))
    throw new ApiError(502, 'INVALID_RESPONSE', '服务响应格式异常')
  return (payload as Envelope<T>).data
}
/** A failed write keeps its key; changing the payload produces a new command. */
export function commandKey() {
  let previous = ''
  let key = ''
  return (payload: unknown) => {
    const signature = JSON.stringify(payload)
    if (!key || signature !== previous) {
      key = crypto.randomUUID()
      previous = signature
    }
    return key
  }
}
