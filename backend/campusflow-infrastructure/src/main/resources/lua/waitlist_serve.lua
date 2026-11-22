-- =====================================================================
-- 候补晋级出队 Lua 脚本：ZPOPMIN 弹出队首 + 扣库存 + 登记成员 + 写预留 原子执行
-- 调用前提：已持有活动级 Redisson 分布式锁（串行化晋级），单脚本内仍保证原子
--
-- KEYS[1] = campusflow:activity:{id}:waitlist      候补队列(ZSet, member=userId, score=入队时间)
-- KEYS[2] = campusflow:activity:{id}:stock         剩余名额(String)
-- KEYS[3] = campusflow:activity:{id}:members       已报名成员(Set)
-- KEYS[4] = campusflow:reservation:{aid}:          预留 Key 前缀（脚本内拼接 userId）
-- ARGV[1] = 预留TTL(秒)
-- 返回: 用户ID字符串=晋级成功  '0'=库存不足  '1'=队列为空
-- 注意1：本脚本结果类型为 String，所有分支必须返回字符串；
--       返回 Lua 数字会让 Lettuce 在 ValueOutput 上执行 set(long) 抛 UnsupportedOperationException
-- 注意2：ZPOPMIN 弹出即移除，若后续 DB 写失败由补偿任务兜底（预留 TTL 过期 -> 幂等释放）
-- =====================================================================
local stock = redis.call('GET', KEYS[2])
if (not stock) then
    return '0'
end
stock = tonumber(stock)
if stock <= 0 then
    return '0'
end

local head = redis.call('ZPOPMIN', KEYS[1])
if #head == 0 then
    return '1'
end

local userId = head[1]
redis.call('DECR', KEYS[2])
redis.call('SADD', KEYS[3], userId)
redis.call('SET', KEYS[4] .. userId, 'WAITLIST_RESERVED', 'EX', tonumber(ARGV[1]))
return tostring(userId)
