/**
 * API 模块集中定义：与后端 Controller 一一对应（见 docs/api/ 接口文档）。
 */
import http from './http'
import type { ApiResult } from './http'

// ===== 类型定义（与后端 VO 对应） =====
export interface UserVO {
  id: number
  username: string
  nickname: string
  email?: string
  phone?: string
  avatar?: string
  status: number
  roles: string[]
  createdAt?: string
}

export interface ActivityVO {
  id: number
  title: string
  category: number
  categoryName: string
  coverUrl?: string
  location?: string
  startTime: string
  endTime: string
  signupStartTime: string
  signupEndTime: string
  capacity: number
  confirmedCount: number
  status: number
  statusDesc: string
  organizerId: number
  organizerName: string
  viewCount?: number
  createdAt?: string
}

export interface ActivityDetailVO extends ActivityVO {
  description?: string
  remaining?: number
  reviewRemark?: string
  myRegistrationId?: number
  myRegistrationStatus?: string
  myWaitlistStatus?: string
  mine?: boolean
}

export interface RegistrationVO {
  id: number
  activityId: number
  activityTitle: string
  activityLocation?: string
  activityStartTime?: string
  activityEndTime?: string
  activityStatus?: number
  userId: number
  username: string
  nickname: string
  status: number
  statusDesc: string
  registeredAt: string
  cancelledAt?: string
  checkinAt?: string
  hasCheckinToken?: boolean
}

export interface WaitlistVO {
  id: number
  activityId: number
  activityTitle: string
  userId: number
  queueNo: number
  status: number
  statusDesc: string
  aheadCount?: number
  joinTime: string
  promotedAt?: string
  confirmDeadline?: string
  confirmedAt?: string
  closedAt?: string
}

export interface CheckinTokenVO {
  registrationId: number
  activityId: number
  activityTitle: string
  userId: number
  qrPayload: string
  status: number
  statusDesc: string
  expireAt: string
  issuedAt: string
}

export interface NotifyVO {
  id: number
  userId: number
  title: string
  content: string
  type: number
  bizRef?: string
  isRead: number
  readAt?: string
  createdAt: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  pageNum: number
  pageSize: number
}

export interface ActivityStatsVO {
  activityId: number
  activityTitle: string
  capacity: number
  confirmedCount: number
  checkedInCount: number
  cancelledCount: number
  waitlistWaitingCount: number
  pendingReservationCount: number
  remainingStock?: number
  viewCount?: number
  dailyRegistrations: { date: string; count: number }[]
  dailyCheckins: { date: string; count: number }[]
}

export interface AdminDashboardVO {
  totalUsers: number
  totalActivities: number
  pendingReviewCount: number
  totalRegistrations: number
  todayRegistrations: number
  totalCheckins: number
  pendingCompensationTasks: number
  deadCompensationTasks: number
  failedMessageCount: number
  categoryDistribution: { categoryName: string; count: number }[]
  registrationTrend: { date: string; count: number }[]
}

// ===== 认证 =====
export const authApi = {
  login: (username: string, password: string): Promise<ApiResult<{ token: string; user: UserVO }>> =>
    http.post('/auth/login', { username, password }),
  register: (body: { username: string; password: string; nickname: string; role: string; email?: string; phone?: string }): Promise<ApiResult<UserVO>> =>
    http.post('/auth/register', body),
  me: (): Promise<ApiResult<UserVO>> => http.get('/auth/me')
}

// ===== 活动 =====
export const activityApi = {
  list: (params: { pageNum: number; pageSize: number; category?: number; keyword?: string }): Promise<ApiResult<PageResult<ActivityVO>>> =>
    http.get('/activities', { params }),
  recommend: (limit = 6): Promise<ApiResult<ActivityVO[]>> => http.get('/activities/recommend', { params: { limit } }),
  detail: (id: number | string): Promise<ApiResult<ActivityDetailVO>> => http.get('/activities/' + id)
}

// ===== 报名 =====
export const registrationApi = {
  signup: (activityId: number | string): Promise<ApiResult<{ registrationId: number | null; reservationId: number; result: string; message: string }>> =>
    http.post(`/activities/${activityId}/registrations`),
  myList: (params: { pageNum: number; pageSize: number; status?: number }): Promise<ApiResult<PageResult<RegistrationVO>>> =>
    http.get('/my/registrations', { params }),
  cancel: (id: number): Promise<ApiResult<void>> => http.delete('/my/registrations/' + id),
  checkinToken: (registrationId: number): Promise<ApiResult<CheckinTokenVO>> =>
    http.get(`/my/registrations/${registrationId}/checkin-token`)
}

// ===== 候补 =====
export const waitlistApi = {
  join: (activityId: number | string): Promise<ApiResult<WaitlistVO>> => http.post(`/activities/${activityId}/waitlists`),
  confirm: (id: number): Promise<ApiResult<{ result: string; message: string }>> => http.post(`/my/waitlists/${id}/confirm`),
  cancel: (id: number): Promise<ApiResult<void>> => http.delete('/my/waitlists/' + id),
  myList: (params: { pageNum: number; pageSize: number }): Promise<ApiResult<PageResult<WaitlistVO>>> =>
    http.get('/my/waitlists', { params })
}

// ===== 通知 =====
export const notifyApi = {
  list: (params: { pageNum: number; pageSize: number; unreadOnly?: boolean }): Promise<ApiResult<PageResult<NotifyVO>>> =>
    http.get('/my/notifications', { params }),
  unreadCount: (): Promise<ApiResult<number>> => http.get('/my/notifications/unread-count'),
  markRead: (id: number): Promise<ApiResult<void>> => http.post(`/my/notifications/${id}/read`),
  markAllRead: (): Promise<ApiResult<void>> => http.post('/my/notifications/read-all')
}

// ===== 个人信息 =====
export const profileApi = {
  get: (): Promise<ApiResult<UserVO>> => http.get('/my/profile'),
  update: (body: { nickname?: string; email?: string; phone?: string; avatar?: string }): Promise<ApiResult<UserVO>> =>
    http.put('/my/profile', body),
  changePassword: (oldPassword: string, newPassword: string): Promise<ApiResult<void>> =>
    http.put('/my/profile/password', { oldPassword, newPassword }),
  /** 头像上传（multipart/form-data，服务端校验图片魔数与大小），返回可访问 URL */
  uploadAvatar: (form: FormData): Promise<ApiResult<string>> =>
    http.post('/my/profile/avatar', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
}

// ===== 组织者 =====
export const organizerApi = {
  myActivities: (params: { pageNum: number; pageSize: number; status?: number }): Promise<ApiResult<PageResult<ActivityVO>>> =>
    http.get('/organizer/activities', { params }),
  create: (body: Record<string, unknown>): Promise<ApiResult<ActivityVO>> => http.post('/organizer/activities', body),
  update: (id: number, body: Record<string, unknown>): Promise<ApiResult<ActivityVO>> => http.put('/organizer/activities/' + id, body),
  submit: (id: number): Promise<ApiResult<void>> => http.post(`/organizer/activities/${id}/submit`),
  cancel: (id: number): Promise<ApiResult<void>> => http.post(`/organizer/activities/${id}/cancel`),
  adjustCapacity: (id: number, capacity: number): Promise<ApiResult<void>> =>
    http.put(`/organizer/activities/${id}/capacity`, null, { params: { capacity } }),
  registrations: (id: number, params: { pageNum: number; pageSize: number; status?: number }): Promise<ApiResult<PageResult<RegistrationVO>>> =>
    http.get(`/organizer/activities/${id}/registrations`, { params }),
  waitlists: (id: number, params: { pageNum: number; pageSize: number; status?: number }): Promise<ApiResult<PageResult<WaitlistVO>>> =>
    http.get(`/organizer/activities/${id}/waitlists`, { params }),
  stats: (id: number): Promise<ApiResult<ActivityStatsVO>> => http.get(`/organizer/activities/${id}/stats`),
  export: (id: number): Promise<ApiResult<{ id: number; status: number }>> => http.post(`/organizer/activities/${id}/exports`),
  exportStatus: (taskId: number): Promise<ApiResult<{ id: number; status: number; rowCount?: number; filePath?: string; error?: string }>> =>
    http.get('/organizer/exports/' + taskId)
}

// ===== 签到核销 =====
export const checkinApi = {
  scan: (body: { activityId: number; token: string }): Promise<ApiResult<{ checkinId: number; userId: number; checkinAt: string; message: string }>> =>
    http.post('/checkins', body)
}

// ===== 管理员 =====
export const adminApi = {
  dashboard: (): Promise<ApiResult<AdminDashboardVO>> => http.get('/admin/dashboard'),
  users: (params: { pageNum: number; pageSize: number; keyword?: string }): Promise<ApiResult<PageResult<UserVO & { status: number }>>> =>
    http.get('/admin/users', { params }),
  setUserStatus: (id: number, disabled: boolean): Promise<ApiResult<void>> =>
    http.put(`/admin/users/${id}/status`, null, { params: { disabled } }),
  activities: (params: { pageNum: number; pageSize: number; status?: number; keyword?: string }): Promise<ApiResult<PageResult<ActivityVO>>> =>
    http.get('/admin/activities', { params }),
  approve: (id: number, remark?: string): Promise<ApiResult<void>> =>
    http.post(`/admin/activities/${id}/approve`, null, { params: { remark } }),
  reject: (id: number, remark: string): Promise<ApiResult<void>> =>
    http.post(`/admin/activities/${id}/reject`, null, { params: { remark } }),
  activityStats: (id: number): Promise<ApiResult<ActivityStatsVO>> => http.get(`/admin/activities/${id}/stats`),
  audits: (params: { pageNum: number; pageSize: number; action?: string }): Promise<ApiResult<PageResult<Record<string, unknown>>>> =>
    http.get('/admin/audits', { params }),
  compensations: (params: { pageNum: number; pageSize: number; bizType?: string; status?: number }): Promise<ApiResult<PageResult<Record<string, unknown>>>> =>
    http.get('/admin/compensations', { params }),
  requeue: (id: number): Promise<ApiResult<void>> => http.post(`/admin/compensations/${id}/requeue`),
  messages: (params: { pageNum: number; pageSize: number; status?: number }): Promise<ApiResult<PageResult<Record<string, unknown>>>> =>
    http.get('/admin/messages', { params })
}
