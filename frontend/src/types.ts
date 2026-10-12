export interface Campus { id: number; code: string; name: string; address: string }
export interface School {
  name: string; englishName: string; motto: string; officialWebsite: string; campuses: Campus[]
}
export interface Profile {
  id: number; username: string; fullName: string; identityNumber: string
  campusName: string | null; departmentName: string | null; className: string | null
  demo: boolean; roles: string[]; permissions: string[]
}
export interface PublicContent {
  id: number; slug: string; category: string; title: string; summary: string; body: string
  sourceUrl: string | null; sourceType: 'PUBLIC_SOURCE' | 'DEMO'; publishedAt: string | null
}
export interface ManagedContent extends PublicContent {
  status: 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'WITHDRAWN'; version: number; createdBy: number | null
  reviewReason: string | null; changeReason: string | null
  live: (Omit<PublicContent, 'id'> & { version: number; visible: boolean }) | null
}
export interface ContentHistoryItem { version: number; action: string; reason: string; actorName: string; recordedAt: string; hasSnapshot: boolean }
export interface ContentHistory { items: ContentHistoryItem[]; total: number; page: number; pageSize: number }
export interface ContentRevision { content: ManagedContent; action: string; reason: string; actorName: string; recordedAt: string }
export interface Lesson {
  id: number; courseName: string; teacherName: string; classroom: string; campusName: string
  startsAt: string; endsAt: string
}
export interface Notice { id: number; title: string; body: string; read: boolean; createdAt: string; targetPath: string | null }
export interface Overview { profile: Profile; timetable: Lesson[]; notices: Notice[]; unreadCount: number }

export interface AcademicTerm { id: number; name: string; startsOn: string; endsOn: string }
export interface Course { id: number; code: string; name: string; credits: number }
export interface TeachingClass { id: number; termId: number; courseId: number; name: string; courseName: string; credits: number; departmentName: string; memberCount: number }
export interface AcademicPerson { id: number; fullName: string; identityNumber: string; className: string | null }
export interface TeachingLesson extends Lesson { classId: number; className: string }
export interface CourseMaterial { id: number; classId: number; authorId: number; title: string; body: string; status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'; version: number; authorName: string; updatedAt: string; fileName: string | null }
export interface TeachingNotice { id: number; title: string; body: string; authorName: string; createdAt: string }
export interface AcademicGrade { id: number; classId: number; studentId: number; studentName: string; courseName: string; credits: number; score: number; status: 'DRAFT' | 'PUBLISHED'; version: number; updatedAt: string }
export interface AcademicExam { id: number; classId: number; courseName: string; campusId: number; campusName: string; classroom: string; roomId: number | null; startsAt: string; endsAt: string; status: 'DRAFT' | 'PUBLISHED'; version: number; updatedAt: string }
export interface TeachingDetail { info: TeachingClass; instructors: AcademicPerson[]; members: AcademicPerson[]; materials: CourseMaterial[]; notices: TeachingNotice[]; grades: AcademicGrade[]; exams: AcademicExam[] }
export interface AcademicCatalog { terms: AcademicTerm[]; courses: Course[]; classes: TeachingClass[]; teachers: AcademicPerson[]; students: AcademicPerson[]; campuses: { id: number; name: string }[]; rooms: import('./resources').ClassroomResource[] }
