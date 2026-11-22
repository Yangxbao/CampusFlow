# Day 19 · 签到凭证与并发核销（阶段五收网）

> 阶段五第 3 天。签到是"票务核销"问题的完整教学案例：签发、防伪、并发核销、审计。

## 一、今天学习什么

**签发 issueToken**（学生端）：
- 前置：报名 CONFIRMED/CHECKED_IN + 活动 ONGOING/PUBLISHED；
- 凭证唯一：同报名复用已有 VALID 凭证；USED/已签到直接拒绝；
- 明文 token = UUID + 雪花 ID，**只在首次签发响应里出现一次**；DB 存 SHA-256 摘要；
  重取时重签发（覆盖 hash，主键不变）——刷新页面拿不到旧明文。

**核销 scan**（组织者端，三层防线，类注释原文）：
1. Redis SETNX 一次性令牌 `campusflow:checkin:once:{tokenHash}`（TTL 5min）快路径；
2. DB 事务内 CAS：token status 0→1 与 registration status 1→2 同事务推进，任一失败回滚；
3. `checkin_record uk_registration` 唯一约束兜底。
配套校验：token 存在且属本活动/未过期/窗口（开始前 30 分钟起）/操作者是组织者或管理员；
核销后 `incrementCheckedInCount` + 发 CHECKIN 消息 + 通知（发件箱随事务提交）。

**防伪设计**：截获二维码内容只能拿到明文一次（扫码后即 USE），
摘要单向不可逆 → 无法反推他人凭证；凭证明文不落库。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/checkin/service/CheckinService.java` | 全文（今天主教材，约 280 行） |
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/CheckinTokenStatus.java` | VALID/USED |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/checkin/entity/CheckinTokenDO.java` | tokenHash/status/expireAt |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/CheckinConcurrencyIT.java` | 10 并发核销同一凭证 |
| `frontend/src/views/student/`（选读） | 二维码展示（qrPayload） |

## 三、搞懂哪些八股

- 摘要存储（SHA-256）的通用模式：密码（BCrypt）、token（SHA-256）、文件校验——
  "明文只出现一次，落库只有摘要"；
- 三层防线的失效顺序分析：SETNX 挂了 → CAS 兜；CAS 都成功但 record 插入冲突 → 唯一约束兜；
  反过来"约束兜底时事务已回滚，状态干净"；
- 一次性令牌与幂等的区别：SETNX 是"第一次赢"（拒绝重复），幂等是"重复无害"（返回相同结果）；
  核销两者都要（重复扫码必须报错，不能静默成功）；
- 核销的审计要求：checkin_record 记 operatorId + tokenHash + checkinAt，可追溯谁核销的。

## 四、运行哪些命令

```bash
# 学生签发凭证
curl -s http://127.0.0.1:8080/api/v1/my/registrations/<regId>/checkin-token \
  -H "Authorization: Bearer $TOKEN_STU"     # qrPayload 里有明文 token（只出现这一次）

# 组织者核销
curl -s -X POST http://127.0.0.1:8080/api/v1/activities/<aid>/checkin \
  -H "Authorization: Bearer $TOKEN_ORG" -H "Content-Type: application/json" \
  -d '{"activityId":<aid>,"token":"<明文>"}'

# 三处痕迹
redis-cli -a 123321 GET campusflow:checkin:once:<hash>          # 一次性令牌（5min TTL）
mysql -e "SELECT status FROM checkin_token WHERE registration_id=<regId>;"   # USED
mysql -e "SELECT * FROM checkin_record ORDER BY id DESC LIMIT 1;"            # 审计
```

## 五、完成什么实验

1. **并发核销**：跑 CheckinConcurrencyIT（10 线程同一凭证），断言仅 1 次成功；
   在日志中找 SETNX 拒绝与 CAS 兜底的两种路径；
2. **防伪实验**：核销成功后用同一明文再扫码 → ALREADY_CHECKED_IN（SETNX 命中）；
   手动删掉 once Key 再试 → CAS/唯一约束兜底仍拒绝——验证三层独立性；
3. **过期实验**：把 token 的 expire_at 改到过去再核销 → CHECKIN_TOKEN_EXPIRED。

## 六、面试自测（口头回答）

1. **两个管理员同时扫同一个码，会发生什么？**
   （SETNX 只有一个成功进入；另一个拿不到令牌直接报已签到；若 Redis 恰好失效，
   事务内 CAS 推进 token/registration 只有一方成功，整体回滚；最终还有 uk_registration
   兜底——结果确定：恰好一次核销。）
2. **为什么 DB 只存 token 的 SHA-256？**
   （DB 泄漏场景下无法伪造有效凭证（要明文）；明文只在签发响应中出现一次；
   这是"凭证型系统"（票务/兑换码）的标准设计。）
3. **签到的窗口校验为什么放服务端？**
   （前端校验可绕过；CHECKIN_NOT_IN_TIME_WINDOW（开始前 30 分钟起、结束截止）
   是安全边界，服务端是唯一可信执行点。）
4. **签到消息为什么走发件箱？**
   （与核销同事务提交，保证"核销成功但统计消息丢失"不会发生——统计与通知最终一致。）

## 七、阶段五总复盘（day17-19 收网测验）

- [ ] 候补双轨设计 + 晋级闭环 + 签到三层防线，三张时序图完成；
- [ ] 三个 IT（Cancel/Checkin/Idempotency）跑通并解释断言；
- [ ] 讲义 14 的 Q7（候补设计）与 Q14（签到并发）口头回答达标；
- [ ] 自查：能否回答"如果让你设计一个演唱会抢票系统，你会复用本项目哪些机制？"——
      这是把项目讲成能力的关键题（答：Lua 预留+候补滚动+唯一约束+补偿+核销摘要 全套复用）。

## 附一、知识点详解

**1. 明文 token 为什么是"UUID+雪花 ID"拼接**
UUID 提供随机性（防猜测），雪花 ID 提供唯一性（防碰撞）——单用 UUID 已够，
拼接雪花 ID 让日志排查时可以从 token 尾部直接定位签发时间。
**凭证格式设计要兼顾随机性、唯一性、可排查性**。

**2. 核销 CAS 的"双表推进"为什么必须同事务**
token USED 与 registration CHECKED_IN 分属两张表——若分两个事务，
第一个成功第二个失败会出现"凭证作废但报名未签到"的中间态（用户无法再核销）。
同事务保证原子回滚，SETNX 与唯一约束各兜一侧。

**3. 一次性 SETNX（5min TTL）的语义选择**
为什么 TTL 5 分钟而不是永久？——SETNX 是"防重放窗口"不是"永久防重"：
永久防重由 CAS/唯一约束负责；5 分钟覆盖"两个管理员同时扫"的现实窗口，
过期后 Key 清理避免 Redis 治理负担。**快路径 TTL 与慢路径强约束的分工**。

**4. CheckinConsumer 的异步职责**
核销事务内发 CHECKIN 消息（发件箱）→ 消费端做统计/通知——核销接口保持轻
（3 张表写 + 2 条消息），把"下游工作"全部异步化。**核销是现场操作，
延迟敏感优先于一切**。

## 附二、三层防线失效推演表

| 失效层 | 效果 | 兜底层 | 用户看到 |
|---|---|---|---|
| Redis SETNX 挂 | 快路径失效 | 事务 CAS | 已签到(仍正确) |
| CAS 均成功但 record 冲突 | 事务回滚 | uk_registration | 已签到 |
| token 明文丢失 | 无法核销 | 重签发(重取凭证) | 新二维码 |
| token 被截获 | 第二人可扫 | SHA-256 摘要+一次性 | 第一人成功后即 USE |

---

## 附三、面试加练（3 题）

**1. 二维码被截图转发怎么办？**
要点：本项目凭证与报名绑定+一次性 USE，转发的凭证"先扫先用"（票务同理）；
增强手段：二维码动态刷新（30s 换明文、重签 hash）、扫码后显示学生姓名
供人工核对——防伪是概率工程，不是绝对工程。

**2. 现场网络差（离线核销）怎么办？**
要点：SETNX/事务都依赖在线 DB——离线场景只能"本地记账+恢复后同步"，
复杂度高；本项目不覆盖离线（校园现场 WiFi 可用），列为边界与演进方向。

**3. 签到率/签到处耗时的统计口径？**
要点：签到率 = CHECKED_IN / CONFIRMED（registration 状态聚合）；
核销耗时需在 scan 里打点（请求时间-核销时间）——**统计口径在代码里，
不在汇报 PPT 里**。

**今日金句**：明文只出现一次、落库只有摘要——凭证系统的两条铁律。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 三层防线 | 14_面试题 Q14 |
| 凭证防伪 | 14_面试题 Q29 |

**容易说错的表述**：
- ✗ "token 存在数据库里" → ✓ "DB 只存 SHA-256 摘要，明文仅签发响应出现一次"；
- ✗ "重复扫码会重复签到" → ✓ "SETNX+双表 CAS+唯一约束三层防线，恰好一次核销"。

---

## 明日预告

Day 20 进入阶段六（分布式锁与一致性）：Redisson 看门狗深挖。
