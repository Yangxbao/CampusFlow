-- =====================================================================
-- 取消报名释放名额 Lua 脚本：成员移除 + 库存回补 + 预留清理 原子执行
-- 幂等性核心：SREM 只有此前确实是成员才返回 1，
-- 并发取消/重复消费第二次调用返回 0，不会重复 INCR（库存重复加回防护）
--
-- KEYS[1] = campusflow:activity:{id}:stock
-- KEYS[2] = campusflow:activity:{id}:members
-- KEYS[3] = campusflow:reservation:{aid}:{uid}
-- ARGV[1] = userId
-- 返回: 1=本次实际释放（是成员） 0=非成员（幂等 no-op，无需释放）
-- =====================================================================
local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 1 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[3])
    return 1
end
return 0
