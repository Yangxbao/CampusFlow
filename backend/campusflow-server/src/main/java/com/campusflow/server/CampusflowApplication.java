package com.campusflow.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CampusFlow 启动入口。
 * <p>Spring Boot 自动装配原理落点：
 * - @SpringBootApplication -> @EnableAutoConfiguration -> META-INF/spring/...AutoConfiguration.imports
 *   加载自动配置类（DataSource/Redis/MVC/MyBatis-Plus 等按条件装配）；
 * - @MapperScan 扫描业务 Mapper 注册为 MyBatis Bean（SqlSessionFactory 由 mybatis-plus-starter 自动配置）；
 * - @EnableScheduling 开启 @Scheduled 定时补偿任务。
 */
@SpringBootApplication(scanBasePackages = "com.campusflow")
@MapperScan({"com.campusflow.**.mapper", "com.campusflow.infra.mq"})
@EnableScheduling
public class CampusflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusflowApplication.class, args);
    }
}
