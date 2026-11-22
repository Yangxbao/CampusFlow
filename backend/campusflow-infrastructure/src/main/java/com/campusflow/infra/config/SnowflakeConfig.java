package com.campusflow.infra.config;

import com.campusflow.common.util.SnowflakeIdWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花 ID 生成器装配。
 * <p>多实例部署时通过 CAMPUSFLOW_WORKER_ID 环境变量为每个实例分配不同机器号（0-1023），
 * 保证全局 ID 不冲突；单实例默认 1。
 */
@Configuration
public class SnowflakeConfig {

    @Bean
    public SnowflakeIdWorker snowflakeIdWorker(
            @Value("${campusflow.worker-id:${CAMPUSFLOW_WORKER_ID:1}}") long workerId) {
        return new SnowflakeIdWorker(workerId);
    }
}
