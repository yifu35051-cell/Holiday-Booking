import { request as req, query } from './client'
import type {
  User,
  Role,
  RoomType,
  Room,
  Availability,
  StayInput,
  Quote,
  Booking,
  Change,
  Payment,
  Refund,
  Plan,
  Membership,
  Purchase,
  Page,
  Mail,
  MailInput,
  Notification,
  Audit,
  Block,
  PaymentException,
  SandboxWallet,
  WalletEntry,
} from './types'

export const authApi = {
  me: () => req<User>('/auth/me'),
  login: (email: string, password: string, portalRole: Role) =>
    req<User>('/auth/login', 'POST', { email, password, portalRole }),
  register: (email: string, password: string, displayName: string) =>
    req<User>('/auth/register', 'POST', { email, password, displayName }),
  logout: () => req<void>('/auth/logout', 'POST'),
}
export const catalogApi = {
  types: (admin = false) => req<RoomType[]>(admin ? '/admin/room-types' : '/room-types'),
  detail: (id: string) => req<RoomType>(`/room-types/${id}`),
  availability: (input: object) => req<Availability[]>(`/availability${query(input)}`),
  saveType: (input: object, id?: string) =>
    req<RoomType>(`/admin/room-types${id ? '/' + id : ''}`, id ? 'PUT' : 'POST', input),
  rates: (id: string, input: object) => req<void>(`/admin/room-types/${id}/rates`, 'PUT', input),
  rooms: (roomTypeId: string) => req<Room[]>(`/admin/rooms${query({ roomTypeId })}`),
  room: (roomTypeId: string, number: string) =>
    req<Room>('/admin/rooms', 'POST', { roomTypeId, number }),
  active: (id: string, active: boolean) =>
    req<Room>(`/admin/rooms/${id}/active`, 'PATCH', { active }),
  retire: (id: string) => req<Room>(`/admin/rooms/${id}`, 'DELETE'),
  blocks: (id: string) => req<Block[]>(`/admin/rooms/${id}/blocks`),
  block: (id: string, input: object) => req<string>(`/admin/rooms/${id}/blocks`, 'POST', input),
  unblock: (id: string) => req<void>(`/admin/room-blocks/${id}`, 'DELETE'),
}
export const bookingApi = {
  quote: (input: StayInput) => req<Quote>('/quotes', 'POST', input),
  create: (input: object, key: string) => req<Booking>('/bookings', 'POST', input, key),
  list: (filters: object, admin = false) =>
    req<Page<Booking>>(`${admin ? '/admin' : ''}/bookings${query(filters)}`),
  get: (id: string, admin = false) => req<Booking>(`${admin ? '/admin' : ''}/bookings/${id}`),
  changes: (id: string, page = 0) => req<Page<Change>>(`/bookings/${id}/changes${query({ page })}`),
  change: (id: string, changeId: string) => req<Change>(`/bookings/${id}/changes/${changeId}`),
  quoteCancel: (id: string) => req<Change>(`/bookings/${id}/cancellation-quotes`, 'POST'),
  quoteAmend: (id: string, input: StayInput) =>
    req<Change>(`/bookings/${id}/amendment-quotes`, 'POST', input),
  submitChange: (id: string, change: Change, key: string) =>
    req<Change>(
      `/bookings/${id}/${change.kind === 'CANCEL' ? 'cancellations' : 'amendments'}`,
      'POST',
      { changeQuoteId: change.id },
      key,
    ),
}
export const paymentApi = {
  get: (id: string) => req<Payment>(`/payment-orders/${id}`),
  start: (id: string, amount: string, method: string, key: string) =>
    req<Payment>(`/payment-orders/${id}/attempts`, 'POST', { amount, method }, key),
  refresh: (id: string) => req<Payment>(`/payment-orders/${id}/refresh`, 'POST'),
  authorize: (id: string, pin: string) =>
    req<Payment>(`/sandbox/payment-attempts/${id}/authorize`, 'POST', { pin }),
  cancel: (id: string) => req<Payment>(`/sandbox/payment-attempts/${id}/cancel`, 'POST'),
  wallet: () => req<SandboxWallet>('/sandbox/wallet'),
  setupWallet: (loginPassword: string, pin: string, confirmation: string) =>
    req<SandboxWallet>('/sandbox/wallet/setup', 'POST', { loginPassword, pin, confirmation }),
  changePin: (oldPin: string, pin: string, confirmation: string) =>
    req<SandboxWallet>('/sandbox/wallet/pin', 'PUT', { oldPin, pin, confirmation }),
  credit: (amount: string, pin: string, key: string) =>
    req<SandboxWallet>('/sandbox/wallet/credits', 'POST', { amount, pin }, key),
  entries: (page = 0) => req<WalletEntry[]>(`/sandbox/wallet/entries${query({ page, size: 20 })}`),
  refund: (id: string | number, refresh = false) =>
    req<Refund>(`/refunds/${id}${refresh ? '/refresh' : ''}`, refresh ? 'POST' : 'GET'),
}
export const membershipApi = {
  plans: () => req<Plan[]>('/membership/plans'),
  me: () => req<Membership>('/membership/me'),
  purchases: (page = 0) => req<Page<Purchase>>(`/membership/purchases${query({ page })}`),
  purchase: (id: string) => req<Purchase>(`/membership/purchases/${id}`),
  buy: (planId: string, key: string) =>
    req<Purchase>('/membership/purchases', 'POST', { planId }, key),
  create: (input: object) => req<Plan>('/admin/membership-plans', 'POST', input),
  retire: (id: string) => req<void>(`/admin/membership-plans/${id}`, 'DELETE'),
}
export const mailApi = {
  list: (filters: object, admin = false) =>
    req<Page<Mail>>(`${admin ? '/admin' : ''}/mail/messages${query(filters)}`),
  unread: () => req<{ count: number }>('/mail/unread-count'),
  recipient: (email: string) =>
    req<{ email: string; displayName: string }>(`/mail/recipient${query({ email })}`),
  get: (id: string, admin = false) => req<Mail>(`${admin ? '/admin' : ''}/mail/messages/${id}`),
  send: (input: MailInput, key: string) => req<Mail>('/mail/messages', 'POST', input, key),
  read: (id: string, read: boolean) => req<Mail>(`/mail/messages/${id}/read`, 'PUT', { read }),
  visibility: (id: string, folder: 'INBOX' | 'SENT', deleted: boolean) =>
    req<void>(`/mail/messages/${id}/visibility`, 'PUT', { folder, deleted }),
  retry: (id: string, acknowledgeUnknown: boolean, admin = false) =>
    req<Mail>(`${admin ? '/admin' : ''}/mail/messages/${id}/retry`, 'POST', { acknowledgeUnknown }),
}
export const managementApi = {
  users: (page = 0) => req<Page<User>>(`/admin/users${query({ page })}`),
  role: (id: string, role: Role) => req<User>(`/admin/users/${id}/role`, 'PUT', { role }),
  notifications: (page = 0) => req<Notification[]>(`/admin/notifications${query({ page })}`),
  retryNotification: (id: string, acknowledgeUnknown: boolean) =>
    req<void>(`/admin/notifications/${id}/retry`, 'POST', { acknowledgeUnknown }),
  exceptions: (page = 0) => req<PaymentException[]>(`/admin/payment-exceptions${query({ page })}`),
  review: (id: string, note: string) =>
    req<void>(`/admin/payment-exceptions/${id}/review`, 'POST', { note }),
  audit: (page = 0) => req<Audit[]>(`/admin/audit-logs${query({ page })}`),
}
