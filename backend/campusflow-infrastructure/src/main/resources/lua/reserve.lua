-- =====================================================================
-- 高并发报名核心 Lua 脚本：校验 + 扣减 + 登记 一次原子执行
-- 通过 StringRedisTemplate 执行（Redis 单线程执行脚本，天然串行无竞态）
--
-- KEYS[1] = campusflow:activity:{id}:stock     剩余名额(String)
-- KEYS[2] = campusflow:activity:{id}:members   已报名成员(Set)
-- KEYS[3] = campusflow:activity:{id}:meta      活动元数据(Hash: status/signupStart/signupEnd)
-- KEYS[4] = campusflow:reservation:{aid}:{uid} 预留状态(String, REDIS_RESERVED)
-- KEYS[5] = campusflow:ratelimit:signup:{aid}:{uid} 限流计数(String)
-- ARGV[1] = userId
-- ARGV[2] = 当前时间戳(毫秒)
-- ARGV[3] = 预留TTL(秒)
-- ARGV[4] = 限流窗口(秒)
-- ARGV[5] = 限流窗口内最大请求数
--
-- 返回码:
--   1  = 预留成功（后续走 MQ 异步落库确认）
--   0  = 名额已满（可加入候补）
--  -1  = 活动状态不可报名
--  -2  = 不在报名时间窗口
--  -3  = 重复报名
--  -4  = 触发用户级限流
--  -5  = 活动元数据/库存缺失（未预热或 Key 失效）
--
-- 为什么不用 GET+DECR 组合：两条命令间存在并发窗口，
-- 1000 个并发同时 GET 到 stock=1 再各自 DECR 会变成 -999（超卖）。
-- Lua 脚本在 Redis 中原子执行，检查与扣减之间不会插入其他命令。
-- =====================================================================
local stockKey   = KEYS[1]
local membersKey = KEYS[2]
local metaKey    = KEYS[3]
local reserveKey = KEYS[4]
local rateKey    = KEYS[5]

local userId       = ARGV[1]
local now          = tonumber(ARGV[2])
local reserveTtl   = tonumber(ARGV[3])
local rateWindow   = tonumber(ARGV[4])
local rateMax      = tonumber(ARGV[5])

-- 1. 活动元数据校验（未预热直接拒绝，防止脏读）
local status = redis.call('HGET', metaKey, 'status')
if (not status) then
    return -5
end
-- 3=已发布 4=进行中
if status ~= '3' and status ~= '4' then
    return -1
end

-- 2. 报名时间窗口校验
local signupStart = tonumber(redis.call('HGET', metaKey, 'signupStart') or '0')
local signupEnd   = tonumber(redis.call('HGET', metaKey, 'signupEnd') or '0')
if now < signupStart or now > signupEnd then
    return -2
end

-- 3. 用户级固定窗口限流（防刷：窗口内第一次 INCR 时设置过期）
local cnt = redis.call('INCR', rateKey)
if cnt == 1 then
    redis.call('EXPIRE', rateKey, rateWindow)
end
if cnt > rateMax then
    return -4
end

-- 4. 一人一单前置校验（成员集合，幂等）
if redis.call('SISMEMBER', membersKey, userId) == 1 then
    return -3
end

-- 5. 库存原子校验与扣减（防超卖核心）
local stock = redis.call('GET', stockKey)
if (not stock) then
    return -5
end
stock = tonumber(stock)
if stock <= 0 then
    return 0
end
redis.call('DECR', stockKey)

-- 6. 登记成员 + 写预留状态（带 TTL，超时自动失效交给补偿释放）
redis.call('SADD', membersKey, userId)
redis.call('SET', reserveKey, 'REDIS_RESERVED', 'EX', reserveTtl)

return 1
