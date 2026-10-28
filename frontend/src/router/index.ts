import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuth, portal } from '@/stores/auth'
import type { Role } from '@/api/types'
const roles: Role[] = ['CUSTOMER', 'STAFF', 'ADMIN']
const routes: RouteRecordRaw[] = [
  { path: '/', component: () => import('@/views/auth/EntryView.vue') },
  { path: '/unavailable', component: () => import('@/views/auth/UnavailableView.vue') },
  ...roles.map((role) => ({
    path: `/login/${role.toLowerCase()}`,
    component: () => import('@/views/auth/LoginView.vue'),
    props: { role },
    meta: { loginRole: role },
  })),
  ...roles.map((role) => ({
    path: portal(role),
    component: () => import('@/layouts/PortalLayout.vue'),
    meta: { role },
    children: [
      { path: '', component: () => import('@/views/DashboardView.vue') },
      { path: 'mail', component: () => import('@/views/mail/MailboxView.vue') },
      ...(role === 'CUSTOMER'
        ? [
            { path: 'explore', component: () => import('@/views/catalog/ExploreView.vue') },
            { path: 'bookings', component: () => import('@/views/booking/BookingsView.vue') },
            {
              path: 'bookings/:id',
              component: () => import('@/views/booking/BookingDetailView.vue'),
            },
            { path: 'payments/:id', component: () => import('@/views/payment/PaymentView.vue') },
            { path: 'wallet', component: () => import('@/components/SandboxWalletPanel.vue') },
            {
              path: 'membership',
              component: () => import('@/views/membership/MembershipView.vue'),
            },
          ]
        : [
            { path: 'rooms', component: () => import('@/views/operations/RoomsView.vue') },
            { path: 'bookings', component: () => import('@/views/booking/BookingsView.vue') },
            {
              path: 'bookings/:id',
              component: () => import('@/views/booking/BookingDetailView.vue'),
            },
            {
              path: 'notifications',
              component: () => import('@/views/operations/NotificationsView.vue'),
            },
          ]),
      ...(role === 'ADMIN'
        ? [
            { path: 'catalog', component: () => import('@/views/admin/CatalogAdminView.vue') },
            { path: 'plans', component: () => import('@/views/admin/PlansAdminView.vue') },
            { path: 'users', component: () => import('@/views/admin/UsersView.vue') },
            {
              path: 'all-mail',
              component: () => import('@/views/mail/MailboxView.vue'),
              props: { administrative: true },
            },
            { path: 'exceptions', component: () => import('@/views/admin/ExceptionsView.vue') },
            { path: 'audit', component: () => import('@/views/admin/AuditView.vue') },
          ]
        : []),
    ],
  })),
  { path: '/:pathMatch(.*)*', redirect: '/' },
]
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})
router.beforeEach(async (to) => {
  const auth = useAuth()
  // DB role is revalidated on every protected navigation, never inferred from local storage.
  if (to.meta.role || to.meta.loginRole || to.path === '/') {
    try {
      await auth.refresh()
    } catch {
      return '/unavailable'
    }
  }
  if (to.meta.role) {
    if (!auth.user) return `/login/${String(to.meta.role).toLowerCase()}`
    if (auth.user.role !== to.meta.role) return portal(auth.user.role)
  }
  if ((to.path === '/' || to.meta.loginRole) && auth.user) return portal(auth.user.role)
})
window.addEventListener('session-expired', () => {
  const auth = useAuth()
  const role =
    auth.user?.role ?? (router.currentRoute.value.meta.role as Role | undefined) ?? 'CUSTOMER'
  auth.user = null
  void router.replace(`/login/${role.toLowerCase()}`)
})

// A role changed in another administrator session must also invalidate an already open workspace.
async function revalidateWorkspace() {
  if (!router.currentRoute.value.meta.role) return
  const auth = useAuth()
  try {
    const account = await auth.refresh()
    if (!account) window.dispatchEvent(new Event('session-expired'))
    else if (account.role !== router.currentRoute.value.meta.role)
      await router.replace(portal(account.role))
  } catch {
    /* Keep the current page on temporary network failure; protected API calls still fail closed. */
  }
}
window.addEventListener('authorization-changed', () => {
  void revalidateWorkspace()
})
window.addEventListener('focus', () => {
  void revalidateWorkspace()
})
export default router
