import { test, expect, type Page } from '@playwright/test'
import type { Role } from '../src/api/types'

async function installApi(page: Page, role: Role, initiallyLoggedIn = false) {
  let loggedIn = initiallyLoggedIn
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const account = { id: '12', email: 'person@holiday.test', displayName: 'Test Account', role }
    if (path.endsWith('/auth/csrf'))
      return route.fulfill({ json: { data: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } } })
    if (path.endsWith('/auth/me'))
      return route.fulfill(
        loggedIn ? { json: { data: account } } : { status: 401, json: { code: 'UNAUTHENTICATED' } },
      )
    if (path.endsWith('/auth/login')) {
      const body = route.request().postDataJSON()
      if (body.portalRole !== role)
        return route.fulfill({
          status: 403,
          json: { code: 'PORTAL_ROLE_MISMATCH', message: '账户角色与当前登录入口不符' },
        })
      loggedIn = true
      return route.fulfill({ json: { data: account } })
    }
    if (path.endsWith('/mail/unread-count')) return route.fulfill({ json: { data: { count: 2 } } })
    if (path.endsWith('/auth/logout')) {
      loggedIn = false
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
  })
}

for (const role of ['CUSTOMER', 'STAFF', 'ADMIN'] as const) {
  test(`${role} enters only its own workspace`, async ({ page }) => {
    await installApi(page, role)
    await page.goto('/login/' + role.toLowerCase())
    await page.getByLabel('邮箱', { exact: true }).fill('person@holiday.test')
    await page.getByLabel('密码', { exact: true }).fill('test-password-123')
    await page.getByRole('button', { name: /登录.*空间/ }).click()
    await expect(page).toHaveURL(new RegExp('/' + role.toLowerCase() + '$'))
    const nav = page.getByRole('navigation', { name: '工作空间导航' })
    await expect(nav.getByRole('link', { name: '我的邮箱' })).toBeVisible()
    await expect(nav.getByRole('link', { name: '探索客房' })).toHaveCount(
      role === 'CUSTOMER' ? 1 : 0,
    )
    await expect(nav.getByRole('link', { name: '预订管理' })).toHaveCount(
      role === 'CUSTOMER' ? 0 : 1,
    )
    await expect(nav.getByRole('link', { name: '账户与角色' })).toHaveCount(
      role === 'ADMIN' ? 1 : 0,
    )
  })
}

test('wrong portal is rejected before showing a workspace', async ({ page }) => {
  await installApi(page, 'CUSTOMER')
  await page.goto('/login/admin')
  await page.getByLabel('邮箱', { exact: true }).fill('person@holiday.test')
  await page.getByLabel('密码', { exact: true }).fill('test-password-123')
  await page.getByRole('button', { name: /登录.*空间/ }).click()
  await expect(page.getByRole('alert')).toContainText('账户角色与当前登录入口不符')
  await expect(page.getByRole('navigation', { name: '工作空间导航' })).toHaveCount(0)
})

test('typing an admin URL cannot elevate a customer session', async ({ page }) => {
  await installApi(page, 'CUSTOMER', true)
  await page.goto('/admin/users')
  await expect(page).toHaveURL(/\/customer$/)
  await expect(page.getByRole('link', { name: '账户与角色' })).toHaveCount(0)
})
