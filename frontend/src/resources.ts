export interface ClassroomResource {
  id: number; campusId: number; campusName: string; code: string; name: string
  buildingName: string | null; roomNumber: string | null; floorLabel: string | null
  type: 'LECTURE' | 'LAB' | 'MULTIPURPOSE' | 'OTHER'; teachingCapacity: number | null; examCapacity: number | null
  enabled: boolean; origin: 'MANUAL' | 'DEMO' | 'MIGRATED'; version: number; updatedAt: string
  schedulable: boolean; referenced: boolean; upcomingLessonCount: number; publishedExamCount: number; draftExamCount: number
}
export interface RoomOccupancy { id: number; kind: 'LESSON' | 'EXAM'; classId: number; className: string; courseName: string; classroom: string; startsAt: string; endsAt: string; memberCount: number }
export interface RoomAudit { id: number; action: 'CREATED' | 'UPDATED' | 'ENABLED' | 'DISABLED'; version: number; reason: string; detail: string; actorName: string; createdAt: string }
export const roomTypes = { LECTURE: '普通教室', LAB: '实验教室', MULTIPURPOSE: '多功能教室', OTHER: '其他教室' }
export function roomStatus(room: ClassroomResource) { return !room.enabled ? '已停用' : room.schedulable ? '可安排' : '待补录' }
export function roomOption(room: ClassroomResource, exam = false) { return `${room.buildingName ?? '楼栋待补录'} · ${room.name}（${exam ? '考试' : '教学'} ${exam ? room.examCapacity ?? '待补录' : room.teachingCapacity ?? '待补录'} 人）${room.schedulable ? '' : ' · ' + roomStatus(room)}` }
