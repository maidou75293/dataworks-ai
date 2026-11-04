// @vitest-environment jsdom
/**
 * REQ-UX-ERRMSG-1 行为锁：HTTP 错误的用户可见文案必须是中文，
 * 禁止 res.statusText 英文裸词（Bad Request/Conflict/Forbidden/
 * Internal Server Error）或浏览器原生 "Failed to fetch" 直达 toast。
 * 断网 / 409 / 403 / 500 四类判据在单测层钉死；UI 层见
 * scripts/e2e/errmsg-zh-toast-test.py（截图入账）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, errMsg, statusFallbackMessage } from '../api'

/** api() 只消费 status/ok/statusText/text()，用纯对象即可，不依赖 Response。 */
function fakeRes(status: number, statusText: string, body: string) {
  return { status, ok: status >= 200 && status < 300, statusText, text: async () => body }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const EN_BARE_WORDS = [
  'Bad Request', 'Conflict', 'Forbidden', 'Internal Server Error', 'Failed to fetch',
]

describe('api() 错误文案降级（REQ-UX-ERRMSG-1）', () => {
  it('断网：中文提示，不含 Failed to fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(api('/api/x')).rejects.toThrow(/无法连接服务器/)
    const e = await api('/api/x').catch((e) => e)
    for (const w of EN_BARE_WORDS) expect(e.message).not.toContain(w)
  })

  it.each([
    [409, 'Conflict', '他人'],
    [403, 'Forbidden', '权限'],
    [500, 'Internal Server Error', '服务器'],
    [400, 'Bad Request', '请求'],
  ] as Array<[number, string, string]>)('%i 空响应体：按码降级中文，不含英文裸词 %s', async (status, statusText, zhNeedle) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(fakeRes(status, statusText, '')))
    const e = await api('/api/x').catch((x) => x)
    expect(e).toBeInstanceOf(Error)
    expect(e.status).toBe(status)
    expect(e.message).toContain(zhNeedle)
    for (const w of EN_BARE_WORDS) expect(e.message).not.toContain(w)
    expect(/[一-鿿]/.test(e.message)).toBe(true)
  })

  it('后端给了 message 时优先原文（不吞业务提示）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(fakeRes(409, 'Conflict', JSON.stringify({ message: '名称已存在' }))))
    const e = await api('/api/x').catch((x) => x)
    expect(e.message).toBe('名称已存在')
    expect(e.status).toBe(409)
  })

  it('表外状态码：兜底也是中文（请求失败（HTTP 418））', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(fakeRes(418, "I'm a Teapot", '')))
    const e = await api('/api/x').catch((x) => x)
    expect(e.message).toBe('请求失败（HTTP 418）')
    expect(e.message).not.toContain("Teapot")
  })

  it('statusFallbackMessage 覆盖判据四类 + 401', () => {
    expect(statusFallbackMessage(409)).toBe('数据刚被他人修改，请刷新页面后重试')
    expect(statusFallbackMessage(403)).toBe('您没有该操作的权限，请联系管理员申请')
    expect(statusFallbackMessage(500)).toBe('服务器内部错误，请稍后重试')
    expect(statusFallbackMessage(401)).toBe('未登录或登录已过期')
  })
})

describe('errMsg() toast 兜底', () => {
  it('空 message / 空白 message / 非 Error 都落中文兜底', () => {
    expect(errMsg(new Error(''))).toBe('操作失败，请稍后重试')
    expect(errMsg(new Error('   '))).toBe('操作失败，请稍后重试')
    expect(errMsg(undefined)).toBe('操作失败，请稍后重试')
    expect(errMsg(null, '保存失败')).toBe('保存失败')
    expect(errMsg('字符串错误', '保存失败')).toBe('保存失败')
  })

  it('非空 message 原样透出', () => {
    expect(errMsg(new Error('数据刚被他人修改，请刷新页面后重试'))).toBe('数据刚被他人修改，请刷新页面后重试')
  })
})
