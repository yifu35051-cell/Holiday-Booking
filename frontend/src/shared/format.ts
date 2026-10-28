export const money = (value: string | number) =>
  new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value))
export const time = (value?: string | null) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
export const day = (offset = 1) => {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const labels: Record<string, string> = {
  PENDING_PAYMENT: '待付款',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
  PENDING: '处理中',
  SUCCEEDED: '已成功',
  READY: '待投递',
  SENT: '已接受',
  SIMULATED: '模拟完成',
  RETRY: '等待重试',
  SENDING: '投递中',
  FAILED: '失败',
  UNKNOWN: '结果待核实',
  SKIPPED: '已跳过',
  APPLIED: '已生效',
  QUOTED: '待确认',
  AWAITING_PAYMENT: '等待补款',
  CUSTOMER: '顾客',
  STAFF: '员工',
  ADMIN: '管理员',
  ACTIVE: '有效',
  CLOSED: '已关闭',
  CREATED: '已创建',
  PROCESSING: '处理中',
}
export const label = (value: string) => labels[value] ?? value
