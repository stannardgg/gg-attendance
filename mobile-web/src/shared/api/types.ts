/** 与 contracts/openapi/java-api.yaml、face-api.yaml 对齐的移动端类型。 */

export type Role = 'ADMIN' | 'TEACHER' | 'STUDENT'
export type AttendanceStatus = 'PRESENT' | 'LATE' | 'ABSENT' | 'LEAVE' | 'EXEMPT'
export type AppealStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type FaceStatus = 'NONE' | 'PENDING_REVIEW' | 'ACTIVE' | 'REJECTED'
export type SessionStatus = 'ACTIVE' | 'CLOSED' | 'CANCELLED'

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

export interface ActiveSession {
  sessionId: number
  title: string
  className?: string
  courseName?: string
  startAt: string
  endAt: string
  remainingSec: number
  myStatus: 'NOT_STARTED' | 'IN_PROGRESS' | 'PRESENT' | 'LATE'
  /** 本人对该场次最新一条申诉的状态；未申诉为 null/缺失 */
  appealStatus?: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | null
  /**
   * 剩余可重试次数（CR-023）。0 = 识别失败已达上限：首页/入口页显示
   * 「识别失败」并拒绝点击（join 会以 FA-3005 拒绝）。已签成功时无意义。
   */
  retryLeft?: number
}

export interface JoinData {
  checkinTicket: string
  ttl: number
  className: string
  courseName: string
  sessionTitle?: string
  remainingSec?: number
  challengeRequired: boolean
  retryLeft?: number
}

export interface MobileAttendance {
  /** attendance 主键；还没签成功时为 0 */
  id?: number
  sessionId: number
  sessionTitle?: string
  className?: string
  courseName?: string
  /** 会话进行中、本人尚未签到成功时为 null（此时仍可申诉） */
  status: AttendanceStatus | null
  checkinAt?: string | null
  canAppeal: boolean
  appealId?: number | null
  appealStatus?: AppealStatus
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface FaceStatusDetail {
  studentId: number
  status: FaceStatus
  sampleCount: number
  submittedAt?: string | null
  reviewedAt?: string | null
  rejectReason?: string | null
  /** 当前生效的同意书版本；撤回或删除人脸数据后为 null（CR-028） */
  consentVersion?: string | null
  /** 首次单独同意时间；撤回或删除人脸数据后为 null（CR-028） */
  consentAt?: string | null
}

export interface Appeal {
  id: number
  attendanceId: number
  sessionId: number
  sessionTitle?: string
  studentName?: string
  className?: string
  courseName?: string
  reason: string
  status: AppealStatus
  reply?: string | null
  createdAt: string
}

/* ---------------- face-api ---------------- */

export type Gate = 'liveness' | 'identity' | 'action_count' | 'multi_face' | 'no_face' | 'match'

// CR-018 停用嘴部动作、CR-029 停用闭眼，服务端只会下发这四个（历史 action_seq 另按 string 处理）
export type FaceAction =
  | 'MOVE_CLOSER' | 'MOVE_AWAY' | 'TURN_LEFT' | 'TURN_RIGHT'

export interface ChallengeStartData {
  challengeId: string
  action: FaceAction
  instruction: string
  windowMs: number
  minFrames: number
  baselineDegraded: boolean
  expiresIn: number
  needCount?: number
  className?: string
  courseName?: string
}

export interface FrameData {
  accepted: boolean
  seq: number
  progress: number
  hint?: string | null
  detectedCount?: number
  requiredCount?: number
  faceCount?: number
  smallFacesIgnored?: number
}

export interface GateResult {
  passed: boolean
  value?: number | null
  threshold?: number | null
}

export interface FinishData {
  passed: boolean
  attendanceId?: number | null
  status?: 'PRESENT' | 'LATE' | null
  checkinAt?: string | null
  similarity?: number | null
  rejectGate?: Gate | null
  retryLeft?: number
  baselineDegraded?: boolean
  gates?: {
    liveness?: GateResult
    identity?: GateResult
    actionCount?: GateResult & { detected?: number; required?: number }
  }
}

export type EnrollReason =
  | 'BLURRY'
  | 'TOO_DARK'
  | 'TOO_BRIGHT'
  | 'FACE_TOO_SMALL'
  | 'YAW_TOO_LARGE'
  | 'PITCH_TOO_LARGE'
  | 'MULTI_FACE'
  | 'NO_FACE'
  | 'DUPLICATE'

export interface EnrollSampleData {
  passed: boolean
  reasons?: EnrollReason[]
  advice?: string | null
  sampleKey?: string | null
  quality?: {
    laplacian: number
    brightness: number
    faceWidth: number
    faceHeight: number
    yaw?: number | null
    pitch?: number | null
    qualityScore: number
  }
}

/** 学生密码修改申请（CR-024）；学生只能申请改密，学号等资料由管理员维护 */
export interface StudentProfileRequest {
  id: number
  studentId: number
  studentNo?: string
  studentName?: string
  className?: string
  passwordChange?: boolean
  reason?: string
  status: AppealStatus
  reviewNote?: string
  reviewerUsername?: string
  reviewedAt?: string
  createdAt: string
}
