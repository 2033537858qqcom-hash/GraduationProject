export interface FoundationDepartment { id: number; name: string; kind: 'COLLEGE' | 'ADMIN'; version: number }
export interface FoundationMajor { id: number; departmentId: number; name: string; version: number }
export interface FoundationClass { id: number; majorId: number; name: string; version: number }
export interface FoundationRole { code: string; name: string; scope: string; permissions: { code: string; description: string }[] }
export interface FoundationCatalog {
  campuses: { id: number; name: string }[]; departments: FoundationDepartment[]; majors: FoundationMajor[]
  classGroups: FoundationClass[]; roles: FoundationRole[]
}
export interface ManagedAccount {
  id: number; username: string; fullName: string; identityNumber: string; campusId: number | null; campusName: string | null
  departmentId: number | null; departmentName: string | null; classGroupId: number | null; className: string | null
  enabled: boolean; demo: boolean; version: number; roles: string[]; passwordChangeRequired: boolean
}
export interface Paged<T> { items: T[]; total: number; page: number; pageSize: number }
export interface FoundationAudit { id: number; actorName: string; action: string; entityType: string; entityId: number; summary: string; reason: string; createdAt: string }

export interface ResponsibilityGroup { code: string; name: string; scopeCount: number; pendingCount: number; path: string; permission: string }
export interface ResponsibilityImpact { userId: number; version: number; blockingCount: number; groups: ResponsibilityGroup[] }
export interface ResponsibilityException { code: string; name: string; count: number; unit: string; path: string; permission: string }
