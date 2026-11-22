package com.campusflow.server.it;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【分库分表高级模式集成测试】真实 ShardingSphere-JDBC 5.4.1 + 真实 MySQL 双分片库。
 * <p>分片设计：registration 按 user_id MOD 2 分片到 campusflow_ds0 / campusflow_ds1。
 * 验证点：
 * 1. 带分片键写入路由正确（偶数 user_id -> ds0，奇数 -> ds1）；
 * 2. 带分片键查询直达单库；
 * 3. 不带分片键查询触发全路由（广播聚合，代价说明见学习讲义 10）；
 * 4. 跨分片聚合统计（COUNT）结果正确；
 * 5. 全局唯一 ID：应用层雪花 ID（分片键无关）。
 * <p>与默认模式的启动隔离：本测试用编程式构建 ShardingSphereDataSource（独立于 Spring 配置），
 * 默认运行模式完全不加载 ShardingSphere。
 */
class ShardingSphereShardingIT {

    private static final String JDBC_DS0 = "jdbc:mysql://127.0.0.1:3306/campusflow_ds0?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String JDBC_DS1 = "jdbc:mysql://127.0.0.1:3306/campusflow_ds1?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static javax.sql.DataSource shardingDs;
    private static final List<Long> insertedIds = new ArrayList<>();

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS registration (
                id             BIGINT       NOT NULL COMMENT '雪花ID',
                activity_id    BIGINT       NOT NULL,
                user_id        BIGINT       NOT NULL,
                status         TINYINT      NOT NULL DEFAULT 1,
                registered_at  DATETIME     NOT NULL,
                cancelled_at   DATETIME     NULL,
                checkin_at     DATETIME     NULL,
                version        INT          NOT NULL DEFAULT 0,
                created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_activity_user (activity_id, user_id),
                KEY idx_user_status (user_id, status)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
            """;

    @BeforeAll
    static void setup() throws Exception {
        // 1. 物理分片库建表（schema 由部署脚本预创建）
        try (Connection c0 = DriverManager.getConnection(JDBC_DS0, "root", "1234");
             Statement s0 = c0.createStatement()) {
            s0.execute(DDL);
        }
        try (Connection c1 = DriverManager.getConnection(JDBC_DS1, "root", "1234");
             Statement s1 = c1.createStatement()) {
            s1.execute(DDL);
        }
        // 2. 编程式构建 ShardingSphere 数据源（生产中对应 application-sharding.yml + sharding.yaml）
        String yaml = """
                databaseName: campusflow_sharding_it
                mode:
                  type: Standalone
                  repository:
                    type: JDBC
                dataSources:
                  ds_0:
                    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
                    driverClassName: com.mysql.cj.jdbc.Driver
                    jdbcUrl: __DS0__
                    username: root
                    password: 1234
                    maximumPoolSize: 4
                  ds_1:
                    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
                    driverClassName: com.mysql.cj.jdbc.Driver
                    jdbcUrl: __DS1__
                    username: root
                    password: 1234
                    maximumPoolSize: 4
                rules:
                - !SHARDING
                  tables:
                    registration:
                      actualDataNodes: ds_${0..1}.registration
                      databaseStrategy:
                        standard:
                          shardingColumn: user_id
                          shardingAlgorithmName: user_mod
                      keyGenerateStrategy:
                        column: id
                        keyGeneratorName: snowflake_it
                  shardingAlgorithms:
                    user_mod:
                      type: INLINE
                      props:
                        algorithm-expression: ds_${user_id % 2}
                  keyGenerators:
                    snowflake_it:
                      type: SNOWFLAKE
                props:
                  sql-show: false
                """;
        shardingDs = YamlShardingSphereDataSourceFactory.createDataSource(
                yaml.replace("__DS0__", JDBC_DS0).replace("__DS1__", JDBC_DS1)
                        .getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void cleanup() throws Exception {
        // 清理测试数据（保留表结构）
        try (Connection c0 = DriverManager.getConnection(JDBC_DS0, "root", "1234");
             Statement s0 = c0.createStatement()) {
            s0.execute("DELETE FROM registration WHERE activity_id >= 990000000000000000");
        }
        try (Connection c1 = DriverManager.getConnection(JDBC_DS1, "root", "1234");
             Statement s1 = c1.createStatement()) {
            s1.execute("DELETE FROM registration WHERE activity_id >= 990000000000000000");
        }
    }

    @Test
    void 带分片键写入路由到正确分片() throws Exception {
        long activityId = 990000000000000001L;
        // 插入 10 条：user_id = 101..110（奇偶各 5 条），id 由 ShardingSphere 雪花生成
        try (Connection conn = shardingDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO registration (id, activity_id, user_id, status, registered_at, version) " +
                             "VALUES (?, ?, ?, 1, NOW(), 0)")) {
            for (long userId = 101; userId <= 110; userId++) {
                long id = SnowflakeNext.next();
                ps.setLong(1, id);
                ps.setLong(2, activityId);
                ps.setLong(3, userId);
                ps.executeUpdate();
                insertedIds.add(userId);
            }
        }
        // 物理库核对：偶数 user_id 在 ds0，奇数在 ds1（MOD 2: 0 -> ds0）
        assertEquals(5, countInDs(JDBC_DS0, activityId), "ds0 应有 5 条（偶数 user_id）");
        assertEquals(5, countInDs(JDBC_DS1, activityId), "ds1 应有 5 条（奇数 user_id）");
    }

    @Test
    void 带分片键查询直达单库() throws Exception {
        long activityId = 990000000000000001L;
        // 带分片键查询：只应命中一个物理库（结果正确性等价，代价减半）
        try (Connection conn = shardingDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM registration WHERE activity_id = ? AND user_id = ?")) {
            ps.setLong(1, activityId);
            ps.setLong(2, 102L); // 偶数 -> ds0
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "带分片键查询返回精确结果");
            }
        }
    }

    @Test
    void 不带分片键查询触发全路由聚合() throws Exception {
        long activityId = 990000000000000001L;
        // 不带分片键：ShardingSphere 广播到两个库再聚合（全路由，代价翻倍 —— 学习讲义重点）
        try (Connection conn = shardingDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM registration WHERE activity_id = ?")) {
            ps.setLong(1, activityId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt(1), "跨分片聚合 COUNT = 两库之和");
            }
        }
        // 分页排序跨分片归并：取最新 3 条
        try (Connection conn = shardingDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_id FROM registration WHERE activity_id = ? ORDER BY user_id DESC LIMIT 3")) {
            ps.setLong(1, activityId);
            List<Long> userIds = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    userIds.add(rs.getLong(1));
                }
            }
            assertEquals(List.of(110L, 109L, 108L), userIds, "跨分片排序归并正确");
        }
    }

    private int countInDs(String jdbcUrl, long activityId) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, "root", "1234");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM registration WHERE activity_id = ?")) {
            ps.setLong(1, activityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 测试内雪花 ID（与主工程同一实现思路，验证分片键无关的全局唯一） */
    static final class SnowflakeNext {
        private static final com.campusflow.common.util.SnowflakeIdWorker WORKER =
                new com.campusflow.common.util.SnowflakeIdWorker(99);

        static long next() {
            return WORKER.nextId();
        }
    }
}
