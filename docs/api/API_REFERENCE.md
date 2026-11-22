# CampusFlow API 接口清单

> 版本：v1（路径前缀 `/api/v1`）
> 在线文档（Knife4j，应用启动后访问）：http://localhost:8080/doc.html
> 统一响应：`{"code":0,"message":"success","data":...,"traceId":"..."}`；`code=0` 成功，非 0 见错误码清单。
> 鉴权：除 auth 接口外均需 `Authorization: Bearer <JWT>`。

## 接口一览（前后端对应表）

### 01 认证（AuthController）— 无需鉴权
| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /auth/register | 注册（STUDENT/ORGANIZER） | 公开 |
| POST | /auth/login | 登录返回 JWT | 公开 |
| GET | /auth/me | 当前用户信息 | 登录 |

### 02 个人信息（UserController）
| GET | /my/profile | 获取个人信息 | 登录 |
|---|---|---|---|
| PUT | /my/profile | 更新昵称/邮箱/手机/头像 | 登录 |
| PUT | /my/profile/password | 修改密码 | 登录 |

### 03 活动浏览（ActivityPublicController）
| GET | /activities | 分页列表（category/keyword） | 登录 |
|---|---|---|---|
| GET | /activities/recommend | 首页推荐 | 登录 |
| GET | /activities/{id} | 详情（布隆+多级缓存+个人状态装饰） | 登录 |

### 04 报名（RegistrationController）
| POST | /activities/{id}/registrations | 报名（Lua 原子预留 + MQ 异步确认） | 登录（学生） |
|---|---|---|---|
| GET | /my/registrations | 我的报名（status 过滤） | 登录 |
| GET | /my/registrations/{id} | 报名详情 | 登录（本人） |
| DELETE | /my/registrations/{id} | 取消报名（幂等 + 触发候补晋级） | 登录（本人） |

### 05 候补（WaitlistController）
| POST | /activities/{id}/waitlists | 加入候补 | 登录 |
|---|---|---|---|
| POST | /my/waitlists/{id}/confirm | 晋级确认（转正报名） | 登录（本人） |
| DELETE | /my/waitlists/{id} | 退出候补 | 登录（本人） |
| GET | /my/waitlists | 我的候补 | 登录 |

### 06 签到（CheckinController）
| GET | /my/registrations/{registrationId}/checkin-token | 获取电子凭证（QR 内容） | 登录（本人） |
|---|---|---|---|
| POST | /checkins | 核销签到（组织者/管理员） | ORGANIZER+ |

### 07 通知（NotificationController）
| GET | /my/notifications | 通知列表 | 登录 |
|---|---|---|---|
| GET | /my/notifications/unread-count | 未读数 | 登录 |
| POST | /my/notifications/{id}/read | 标记已读 | 登录（本人） |
| POST | /my/notifications/read-all | 全部已读 | 登录 |

### 08 组织者工作台（OrganizerActivityController）— 需 ORGANIZER/ADMIN
| GET/POST/PUT | /organizer/activities... | 活动列表/创建/编辑/提交审核/取消/调容量 | ORGANIZER+ |
|---|---|---|---|
| GET | /organizer/activities/{id}/registrations | 报名人员 | ORGANIZER+（本人活动） |
| GET | /organizer/activities/{id}/waitlists | 候补人员 | ORGANIZER+ |
| GET | /organizer/activities/{id}/stats | 活动统计（ECharts 数据） | ORGANIZER+ |
| POST | /organizer/activities/{id}/exports | 发起 CSV 导出（异步任务） | ORGANIZER+ |
| GET | /organizer/exports/{taskId}(/download) | 任务状态/下载 | ORGANIZER+ |

### 09 后台管理（AdminController）— 仅 ADMIN
| GET | /admin/dashboard | 平台仪表盘 | ADMIN |
|---|---|---|---|
| GET/PUT | /admin/users... | 用户管理/禁用启用 | ADMIN |
| POST | /admin/activities/{id}/approve | 审核通过（触发库存预热） | ADMIN |
| POST | /admin/activities/{id}/reject | 审核拒绝 | ADMIN |
| GET | /admin/audits | 操作审计 | ADMIN |
| GET | /admin/compensations | 补偿任务（死信重入队） | ADMIN |
| GET | /admin/messages | 本地消息表状态 | ADMIN |

## 错误码清单

| 分段 | 范围 | 示例 |
|---|---|---|
| 成功 | 0 | success |
| 通用 | 4xxxx | 40001 参数错误 / 40101 未登录 / 40301 无权限 / 40401 不存在 / 42901 重复提交 |
| 用户 | 210xx | 21001 用户已存在 / 21003 密码错误 / 21004 已禁用 |
| 活动 | 220xx | 22001 活动不存在 / 22002 未发布 / 22003 不在报名窗口 |
| 报名 | 230xx | 23001 重复报名 / **23002 名额已满(引导候补)** / 23003 限流 |
| 候补 | 240xx | 24001 已在队列 / 24003 无需候补 / 24005 晋级超时 |
| 签到 | 250xx | 25003 已签到 / 25004 不在签到窗口 / 25006 无核销权限 |
| 消息 | 260xx | 26001 发送失败(自动重试) |
| 基础设施 | 30xxx | 30001 锁超时 / 30002 Redis 异常 / 30003 MQ 异常 |

完整枚举：`backend/campusflow-common/src/main/java/com/campusflow/common/error/ErrorCode.java`

## 测试账号（种子数据）

| 角色 | 账号 | 密码 |
|---|---|---|
| 管理员 | admin | admin123456 |
| 组织者 | organizer1 | org123456 |
| 学生 | student01 ~ student20 | stu123456 |
| 压测用户 | jmeter0001 ~ jmeter1000 | Jm123456 |

## 请求/响应示例

### 报名（异步确认语义）
```json
POST /api/v1/activities/2123850011402207234/registrations
Authorization: Bearer <token>

→ 202-style 业务响应（HTTP 200）:
{"code":0,"message":"success","data":{"registrationId":null,
 "reservationId":911677510255644672,"result":"PENDING_CONFIRM",
 "message":"报名已受理，系统确认中（通常数秒内完成），请稍后在我的报名中查看"}}
```
前端约定：`result=PENDING_CONFIRM` 后轮询 `/my/registrations`，出现 `status=1` 即确认成功。

### 名额已满引导候补
```json
{"code":23002,"message":"活动名额已满，可以加入候补队列","traceId":"..."}
```
