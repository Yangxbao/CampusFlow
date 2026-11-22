package com.campusflow.infra.redis;

import com.campusflow.infra.property.CampusflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;

/**
 * 布隆过滤器（Redisson RBloomFilter，底层为 Redis 位图）：活动存在性快速判断，
 * 防缓存穿透 —— 对不存在的活动ID的恶意查询直接拦截，不落到 DB。
 * <p>特点与代价：
 * <ul>
 *   <li>存在误判（fpp=0.01）：会把"不存在"误判为"存在"放行到缓存/DB，但不会漏判，
 *       因此作为穿透防线是安全的；</li>
 *   <li>不支持删除：活动取消后仍会放行查询，由缓存空值兜底（缓存 404 空对象短 TTL）；</li>
 *   <li>Redis 重启数据丢失：启动时从 DB 全量重建（活动量小，代价可忽略）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterService {

    public static final String ACTIVITY_BLOOM_NAME = "campusflow:bloom:activity";

    private final RedissonClient redissonClient;
    private final CampusflowProperties properties;

    /**
     * 初始化（应用启动/重建时调用）。幂等：已存在则跳过（保存已有数据）。
     */
    public void initActivityBloomIfAbsent() {
        RBloomFilter<Long> bloom = redissonClient.getBloomFilter(ACTIVITY_BLOOM_NAME);
        if (!bloom.tryInit(properties.getCache().getBloomExpectedInsertions(),
                properties.getCache().getBloomFpp())) {
            log.info("活动布隆过滤器已初始化，跳过（保留既有位图）");
        } else {
            log.info("活动布隆过滤器初始化完成 expected={} fpp={}",
                    properties.getCache().getBloomExpectedInsertions(),
                    properties.getCache().getBloomFpp());
        }
    }

    public void addActivityId(long activityId) {
        redissonClient.getBloomFilter(ACTIVITY_BLOOM_NAME).add(activityId);
    }

    /**
     * 活动是否存在（可能误判放行，绝不漏判）。
     */
    public boolean mightContainActivity(long activityId) {
        return redissonClient.getBloomFilter(ACTIVITY_BLOOM_NAME).contains(activityId);
    }

    /**
     * 全量重建（Redis 重启后/一致性修复时调用）。全删重建需先删除位图 Key。
     */
    public void rebuildActivityBloom(LongSupplier countSupplier, java.util.function.LongConsumer adder) {
        redissonClient.getBucket(ACTIVITY_BLOOM_NAME).delete();
        RBloomFilter<Long> bloom = redissonClient.getBloomFilter(ACTIVITY_BLOOM_NAME);
        bloom.tryInit(properties.getCache().getBloomExpectedInsertions(), properties.getCache().getBloomFpp());
        long count = countSupplier.getAsLong();
        log.info("布隆过滤器重建完成，装载 {} 个活动ID", count);
    }
}
