/** IDs stay strings: Java Long values must not lose precision in the browser. */
export type Role = 'CUSTOMER' | 'STAFF' | 'ADMIN'
export interface User {
  id: string
  email: string
  displayName: string
  role: Role
}
export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}
export interface RoomType {
  id: string
  name: string
  description: string
  capacity: number
  basePrice: string
  active: boolean
}
export interface Room {
  id: string
  roomTypeId: string
  number: string
  active: boolean
}
export interface Availability {
  roomType: RoomType
  availableRooms: number
  nights: number
  totalPerRoom: string
}
export interface Selection {
  roomTypeId: string
  rooms: number
  guestsPerRoom: number
}
export interface StayInput {
  checkIn: string
  checkOut: string
  rooms: Selection[]
}
export interface RoomPrice {
  roomTypeId: string
  roomTypeName: string
  guests: number
  nights: { date: string; basePrice: string; payablePrice: string }[]
}
export interface Snapshot {
  checkIn: string
  checkOut: string
  discountRatio: string
  rooms: RoomPrice[]
}
export interface Quote {
  id: string
  expiresAt: string
  totalAmount: string
  currency: string
  snapshot: Snapshot
}
export interface Booking {
  id: string
  userId: string
  status: string
  checkIn: string
  checkOut: string
  totalAmount: string
  currency: string
  contactName: string
  contactEmail: string
  contactPhone: string
  expiresAt: string | null
  confirmedAt: string | null
  revision: number
  paymentOrderId: string
  rooms: { roomId: string; roomNumber: string; price: RoomPrice }[]
}
export interface Refund {
  id: string | number
  state: string
  amount: string | number
  requestNo: string
  detail: string | null
}
export interface Change {
  id: string
  bookingId: string
  kind: 'CANCEL' | 'AMEND'
  state: string
  priceDifference: string
  expiresAt: string
  paymentOrderId: string | null
  proposedStay: Snapshot | null
  refunds: Refund[]
}
export interface Attempt {
  id: string
  provider: string
  method: string
  status: string
  outTradeNo: string
  checkoutUrl: string | null
  paidAt: string | null
}
export interface Payment {
  id: string
  status: string
  amount: string
  currency: string
  expiresAt: string
  provider: string
  attempts: Attempt[]
}
export interface Plan {
  id: string
  name: string
  fee: string
  discountRatio: string
  durationDays: number
}
export interface Membership {
  active: boolean
  planId: string | null
  discountRatio: string
  validUntil: string | null
}
export interface Purchase {
  id: string
  status: string
  fee: string
  discountRatio: string
  durationDays: number
  expiresAt: string
  paymentOrderId: string
}
export interface Mail {
  id: string
  kind: string
  senderId: string | null
  recipientId: string | null
  senderEmail: string
  recipientEmail: string
  subject: string
  body: string | null
  replyToId: string | null
  createdAt: string | null
  deliveredAt: string | null
  readAt: string | null
  senderDeleted: boolean
  recipientDeleted: boolean
  state: string
  attempts: number
  lastError: string | null
}
export type Folder = 'INBOX' | 'SENT' | 'TRASH'
export interface MailInput {
  recipientEmail: string
  subject: string
  body: string
  replyToId?: string
}
export interface Notification {
  id: string
  bookingId: string
  revision: number
  type: string
  recipient: string
  state: string
  attempts: number
  dueAt: string
  lastError: string | null
}
export interface Audit {
  id: string
  actorId: string
  action: string
  resourceType: string
  resourceId: string
  detail: string
  createdAt: string
}
export interface Block {
  id: string
  start_date: string
  end_date: string
  reason: string
  released: boolean
}
export interface PaymentException {
  id: string
  payment_order_id: string
  attempt_id: string
  received_amount: string | number | null
  reason?: string
  status?: string
  detail?: string
  review_note?: string
  created_at?: string
}
export interface SandboxWallet {
  configured: boolean
  balance: string
  currency: string
  attemptsRemaining: number
  lockedUntil: string | null
}
export interface WalletEntry {
  id: string
  kind: 'CREDIT' | 'PAYMENT' | 'REFUND'
  reference: string
  amount: string
  balanceAfter: string
  createdAt: string
}
