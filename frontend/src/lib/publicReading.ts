const chapterNames: Record<string, string> = {
  home: '首页', about: '学校概况', departments: '院系专业', education: '教育教学',
  research: '科学研究', admissions: '招生就业', exchange: '合作交流', life: '校园生活', disclosure: '信息公开',
}

export function contentOrigin(slug: unknown, from: unknown) {
  const id = typeof from === 'string' && Object.hasOwn(chapterNames, from)
    ? from : slug === 'school-introduction' ? 'about' : 'disclosure'
  return { id, name: chapterNames[id]!, target: { path: '/', query: id === 'home' ? {} : { chapter: id } } }
}

export function publicContentLink(slug: string, from: string) {
  return { path: '/content/' + encodeURIComponent(slug), query: { from: contentOrigin(slug, from).id } }
}

export function formatPublicDate(value: string | null | undefined) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  })
}

export function publicSource(value: string | null | undefined) {
  if (!value) return null
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? { href: url.href, host: url.hostname } : null
  } catch { return null }
}

export function contentParagraphs(value: string) {
  return value.split(/\r?\n\s*\r?\n/).map(paragraph => paragraph.trim()).filter(Boolean)
}
