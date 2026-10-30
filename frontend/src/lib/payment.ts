export function centsFromInput(value: string): number | null {
  if (!/^\d{1,7}(?:\.\d{1,2})?$/.test(value)) return null
  const [yuan, decimals = ''] = value.split('.')
  const cents = Number(yuan) * 100 + Number(decimals.padEnd(2, '0'))
  return cents > 0 && cents <= 100000000 ? cents : null
}

export function safeSandboxPaymentUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'openapi-sandbox.dl.alipaydev.com'
      && url.pathname === '/gateway.do' && !url.username && !url.password && !url.port
  } catch { return false }
}

/** 支付宝当面付二维码内容只允许 https://qr.alipay.com/ 开头。 */
export function safeAlipayQrCode(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'qr.alipay.com' && !url.username && !url.password && !url.port
  } catch { return false }
}

export const PAYMENT_POLL_MS = 3000
