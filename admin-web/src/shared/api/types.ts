/** 与 contracts/openapi/java-api.yaml 对齐的类型。字段名一个都不能改。 */

export type Role = 'ADMIN' | 'TEACHER' | 'STUDENT'
export type OrgStatus = 'ACTIVE' | 'ARCHIVED'
export type StudentStatus = 'ACTIVE' | 'TRANSFERRED' | 'ARCHIVED'
export type SessionStatus = 'ACTIVE' | 'CLOSED' | 'CANCELLED'
export type AttendanceStatus = 'PRESENT' | 'LATE' | 'ABSENT' | 'LEAVE' | 'EXEMPT'
export type AppealStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type FaceStatus = 'NONE' | 'PENDING_REVIEW' | 'ACTIVE' | 'REJECTED'

export interface PageMeta {
  items: unknown[]
  page: number
  size: number
  total: number
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface Profile {
  id: number
  username: string
  name: string
  role: Role
  teacherId?: number
  studentId?: number
  classId?: number
  className?: string
  faceStatus?: FaceStatus
}

export interface Me {
  profile: Profile
  permissions: string[]
  scope: { classIds: number[]; courseIds: number[] }
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  role: Role
  expiresIn: number
  profile: Profile
}

export interface ClassRow {
  id: number
  code: string
  name: string
  grade?: string
  major?: string
  ownerTeacherId?: number
  ownerTeacherName?: string
  status: OrgStatus
  remark?: string
  studentCount: number
  createdAt: string
}

export interface CourseRow {
  id: number
  code: string
  name: string
  semester?: string
  ownerTeacherId?: number
  ownerTeacherName?: string
  status: OrgStatus
  remark?: string
  createdAt: string
}

export interface StudentRow {
  id: number
  studentNo: string
  name: string
  classId: number
  className?: string
  status: StudentStatus
  activated: boolean
  faceStatus: FaceStatus
  createdAt: string
}

export interface TeacherRow {
  id: number
  teacherNo: string
  name: string
  department?: string
  title?: string
  status: string
  classCount: number
  courseCount: number
}

/** 教师账号与资料修改申请（CR-019 / CR-022）。old* 是提交时快照，new* 是申请值。 */
export interface ProfileRequestRow {
  id: number
  teacherId: number
  teacherNo?: string
  teacherName?: string
  oldUsername?: string
  oldName?: string
  oldDepartment?: string
  oldTitle?: string
  newUsername?: string
  newName?: string
  newDepartment?: string
  newTitle?: string
  /** 是否申请了重置密码；后端只给标记，永不返回哈希 */
  passwordChange?: boolean
  reason?: string
  status: AppealStatus
  reviewNote?: string
  reviewerUsername?: string
  reviewedAt?: string
  createdAt: string
}

export interface ScopeRow {
  id: number
  teacherId: number
  teacherName?: string
  scopeType: 'CLASS' | 'COURSE'
  scopeId: number
  scopeName?: string
  createdAt: string
}

export interface SessionRow {
  id: number
  title: string
  classId: number
  className?: string
  courseId: number
  courseName?: string
  teacherId?: number
  teacherName?: string
  status: SessionStatus
  startAt: string
  endAt: string
  lateAfterSec: number
  allowAppeal: boolean
  expectedCount: number
  presentCount: number
  lateCount: number
  absentCount: number
  closedAt?: string
  createdAt: string
}

export interface AttendanceRow {
  /** 按场次查询时以应到名单为基表，未签到的人还没有考勤记录，id 为 null */
  id: number | null
  sessionId: number
  sessionTitle?: string
  classId?: number
  className?: string
  courseId?: number
  courseName?: string
  studentId: number
  studentNo: string
  studentName: string
  status: AttendanceStatus
  checkinAt?: string
  similarity?: number
  livenessMedian?: number
  identitySim?: number
  actionCount?: number
  actionSeq?: string
  rejectGate?: string
  adjustReason?: string
  adjustedBy?: number
}

export interface AppealRow {
  id: number
  attendanceId: number
  sessionId: number
  sessionTitle?: string
  studentId: number
  studentNo?: string
  studentName?: string
  className?: string
  courseName?: string
  reason: string
  evidenceUrl?: string
  status: AppealStatus
  reply?: string
  handledBy?: number
  handledAt?: string
  createdAt: string
  rejectGate?: string
}

export interface Overview {
  todaySessions: number
  todayExpected: number
  todayPresent: number
  todayRate: number
  pendingAppeals: number
  trend: { date: string; rate: number }[]
}

export interface AuditRow {
  id: number
  actorId?: number
  actorName?: string
  actorRole?: string
  action: string
  targetType?: string
  targetId?: number
  before?: unknown
  after?: unknown
  clientIp?: string
  traceId?: string
  createdAt: string
}

export interface ConfigRow {
  configKey: string
  configValue: string
  valueType: string
  category: string
  description?: string
  editable: boolean
  updatedAt?: string
}

export interface FaceStatusDetail {
  studentId: number
  status: FaceStatus
  sampleCount: number
  submittedAt?: string
  reviewedAt?: string
  rejectReason?: string
}

export interface ImportResult {
  total: number
  created: number
  updated: number
  failed: number
  errors: { line: number; studentNo?: string; reason: string }[]
}

export interface AgentCode {
  code: string
  expiresAt: string
  agentUrl: string
}
