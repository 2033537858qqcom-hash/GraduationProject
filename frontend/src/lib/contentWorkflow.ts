import type { ManagedContent } from '../types.ts'

export function contentActions(item: Pick<ManagedContent, 'status' | 'createdBy' | 'live'>, user: { id: number; permissions: readonly string[] } | null) {
  const edit = !!user?.permissions.includes('content:edit')
  const review = !!user?.permissions.includes('content:review')
  const own = !!user && item.createdBy === user.id
  return {
    edit: edit && own && ['DRAFT', 'REJECTED'].includes(item.status),
    submit: edit && own && ['DRAFT', 'REJECTED'].includes(item.status),
    recall: edit && own && item.status === 'IN_REVIEW',
    publish: review && !own && item.status === 'IN_REVIEW',
    reject: review && !own && item.status === 'IN_REVIEW',
    revise: edit && ['PUBLISHED', 'WITHDRAWN'].includes(item.status),
    unpublish: review && !!item.live?.visible,
    publicLink: !!item.live?.visible,
  }
}
