export interface DormMeter {
  id: number; roomId: number; building: string; room: string; meterCode: string; category: 'WATER' | 'ELECTRICITY'
  standardId: number; standardName: string; unitPriceCents: number; unit: string; balance: number; enabled: boolean; standardStatus: string; standardAvailable: boolean; version: number
}
export interface DormRechargeRecord {
  id: number; billId: number; payer: string; status: string; amountCents: number; quantity: number; category: string
  createdAt: string; creditedAt: string | null; reversedAt: string | null
}
export interface DormRechargeOverview { meters: DormMeter[]; history: DormRechargeRecord[] }
export interface DormMeterCatalog { rooms: { id: number; building: string; name: string }[]; meters: DormMeter[] }
export interface DormQuote { amountCents: number; quantity: number }
export const dormUtilityName = (category: string) => category === 'WATER' ? '水费' : '电费'
export const dormQuantity = (value: number) => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 9 })
