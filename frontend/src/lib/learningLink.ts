export function learningLinkSelection(terms: { id: number; startsOn: string; endsOn: string }[], term: unknown, week: unknown): { termId: number; date: string | null } | null {
  if (typeof term !== 'string' || !/^[1-9]\d{0,14}$/.test(term)) return null
  const selected = terms.find(t => t.id === Number(term))
  if (!selected) return null
  let date: string | null = null
  if (typeof week === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(week)) {
    const parsed = new Date(week + 'T12:00:00Z')
    if (!Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === week) {
      // Week links may point to the Monday just before a term starts.
      const firstMonday = new Date(selected.startsOn + 'T12:00:00Z')
      firstMonday.setUTCDate(firstMonday.getUTCDate() - ((firstMonday.getUTCDay() + 6) % 7))
      if (week >= firstMonday.toISOString().slice(0, 10) && week <= selected.endsOn) date = week
    }
  }
  return { termId: selected.id, date }
}
