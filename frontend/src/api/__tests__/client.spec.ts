import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { request, commandKey, clearCsrf, query, ApiError } from '../client'
const fetchMock = vi.fn<typeof fetch>()
beforeEach(() => {
  clearCsrf()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())
describe('HTTP session contract', () => {
  it('loads CSRF before writing, includes cookie credentials and preserves the command key', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-123' } })),
    )
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { id: '9007199254740993' } })),
    )
    const result = await request<{ id: string }>(
      '/bookings',
      'POST',
      { quoteId: '99' },
      'command-123',
    )
    expect(result.id).toBe('9007199254740993')
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      credentials: 'same-origin',
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': 'csrf-123', 'Idempotency-Key': 'command-123' },
      body: '{"quoteId":"99"}',
    })
  })
  it('does not retry an uncertain write automatically', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-123' } })),
    )
    fetchMock.mockRejectedValueOnce(new TypeError('network disconnected'))
    await expect(
      request('/mail/messages', 'POST', { subject: 'Hello' }, 'mail-123'),
    ).rejects.toMatchObject({ status: 0, code: 'NETWORK' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
  it('preserves server denial and trace information', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'Denied', traceId: 'trace-123' }), {
        status: 403,
      }),
    )
    await expect(request('/admin/users')).rejects.toEqual(
      new ApiError(403, 'FORBIDDEN', 'Denied', 'trace-123'),
    )
  })
  it('retains a key for identical retries and creates another after input changes', () => {
    const key = commandKey(),
      first = key({ body: 'Hello' })
    expect(key({ body: 'Hello' })).toBe(first)
    expect(key({ body: 'Changed' })).not.toBe(first)
  })
  it('encodes filters without losing zero and false values', () => {
    expect(query({ page: 0, unreadOnly: false, email: 'a+b@test.local', missing: '' })).toBe(
      '?page=0&unreadOnly=false&email=a%2Bb%40test.local',
    )
  })
})
