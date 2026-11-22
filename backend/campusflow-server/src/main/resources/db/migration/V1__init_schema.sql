-- =====================================================================
-- CampusFlow 数据库初始化 V1
-- 说明：本脚本由 Flyway 在目标 schema 内执行（连接串决定库），不显式 USE
-- 引擎：InnoDB  字符集：utf8mb4（连接串 characterEncoding=UTF-8）
-- 主键策略：雪花算法应用层生成（BIGINT），避免自增 ID 在分库分表与多实例下冲突
-- 乐观锁：需要状态竞争的表带 version 字段
-- 外键策略：不使用物理外键（高并发写入场景外键校验开销大、且不利于分库分表），
--          数据一致性由业务层（唯一约束 + 状态机 + 对账补偿）保证
-- =====================================================================

-- ---------------------------------------------------------------------
-- 用户表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    username    VARCHAR(64)  NOT NULL COMMENT '登录名（学号/工号风格）',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
    nickname    VARCHAR(64)  NOT NULL COMMENT '昵称',
    email       VARCHAR(128) NULL COMMENT '邮箱',
    phone       VARCHAR(20)  NULL COMMENT '手机号',
    avatar      VARCHAR(255) NULL COMMENT '头像URL',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0正常 1禁用',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '用户表';

-- ---------------------------------------------------------------------
-- 角色表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_role (
    id         BIGINT      NOT NULL COMMENT '雪花ID',
    code       VARCHAR(32) NOT NULL COMMENT '角色编码：STUDENT/ORGANIZER/ADMIN',
    name       VARCHAR(64) NOT NULL COMMENT '角色名称',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT '角色表';

-- ---------------------------------------------------------------------
-- 用户角色关联表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user_role (
    id         BIGINT   NOT NULL COMMENT '雪花ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    role_id    BIGINT   NOT NULL COMMENT '角色ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB COMMENT '用户角色关联表';

-- ---------------------------------------------------------------------
-- 活动表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS activity (
    id                BIGINT       NOT NULL COMMENT '雪花ID',
    title             VARCHAR(128) NOT NULL COMMENT '活动标题',
    description       TEXT         NULL COMMENT '活动描述（富文本/Markdown）',
    category          TINYINT      NOT NULL DEFAULT 0 COMMENT '分类：0学术 1技术 2比赛 3就业 4社团 5志愿 6招聘',
    cover_url         VARCHAR(255) NULL COMMENT '封面图URL',
    location          VARCHAR(128) NULL COMMENT '活动地点',
    start_time        DATETIME     NOT NULL COMMENT '活动开始时间',
    end_time          DATETIME     NOT NULL COMMENT '活动结束时间',
    signup_start_time DATETIME     NOT NULL COMMENT '报名开始时间',
    signup_end_time   DATETIME     NOT NULL COMMENT '报名截止时间',
    capacity          INT          NOT NULL COMMENT '名额上限',
    confirmed_count   INT          NOT NULL DEFAULT 0 COMMENT '已确认报名人数（MQ消费端维护，用于DB侧对账）',
    checked_in_count  INT          NOT NULL DEFAULT 0 COMMENT '已签到人数（签到事务维护）',
    status            TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0草稿 1待审核 2未通过 3已发布 4进行中 5已结束 6已取消',
    review_remark     VARCHAR(255) NULL COMMENT '审核备注（拒绝原因）',
    organizer_id      BIGINT       NOT NULL COMMENT '组织者用户ID',
    version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_organizer (organizer_id, status),
    KEY idx_status_start (status, start_time),
    KEY idx_category_status (category, status),
    KEY idx_signup_window (signup_start_time, signup_end_time)
) ENGINE = InnoDB COMMENT '校园活动表';

-- ---------------------------------------------------------------------
-- 报名表（一人一单最终防线：uk_activity_user 唯一约束）
-- 重新报名复用同一行：取消后再报名走状态回迁（CANCELLED -> RESERVED），保证唯一索引不冲突
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registration (
    id             BIGINT   NOT NULL COMMENT '雪花ID',
    activity_id    BIGINT   NOT NULL COMMENT '活动ID',
    user_id        BIGINT   NOT NULL COMMENT '报名用户ID',
    status         TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0确认中 1已报名 2已签到 3已取消 4确认失败',
    reservation_id BIGINT   NULL COMMENT '预留记录ID（追踪异步确认链路）',
    registered_at  DATETIME NOT NULL COMMENT '报名时间',
    cancelled_at   DATETIME NULL COMMENT '取消时间',
    checkin_at     DATETIME NULL COMMENT '签到时间',
    version        INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_user (activity_id, user_id),
    KEY idx_user_status (user_id, status),
    KEY idx_activity_status (activity_id, status),
    KEY idx_registered_at (registered_at)
) ENGINE = InnoDB COMMENT '活动报名表';

-- ---------------------------------------------------------------------
-- 报名状态变更记录表（状态机审计轨迹）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registration_state_log (
    id              BIGINT      NOT NULL COMMENT '雪花ID',
    registration_id BIGINT      NOT NULL COMMENT '报名ID',
    activity_id     BIGINT      NOT NULL COMMENT '活动ID',
    user_id         BIGINT      NOT NULL COMMENT '用户ID',
    from_status     TINYINT     NULL COMMENT '原状态（新建为NULL）',
    to_status       TINYINT     NOT NULL COMMENT '目标状态',
    reason          VARCHAR(255) NULL COMMENT '变更原因',
    operator_id     BIGINT      NULL COMMENT '操作人（系统补偿为NULL）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_registration (registration_id),
    KEY idx_activity_user (activity_id, user_id)
) ENGINE = InnoDB COMMENT '报名状态变更记录表';

-- ---------------------------------------------------------------------
-- 报名预留记录表（待处理预留：Lua 预留成功后先落此表，再发 MQ；补偿扫描依据）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registration_reservation (
    id           BIGINT      NOT NULL COMMENT '预留记录ID（雪花ID）',
    activity_id  BIGINT      NOT NULL COMMENT '活动ID',
    user_id      BIGINT      NOT NULL COMMENT '用户ID',
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0预留待确认 1已确认 2补偿中 3已释放',
    message_key  VARCHAR(64) NOT NULL COMMENT '消息业务键（追踪与幂等）',
    reserved_at  DATETIME    NOT NULL COMMENT '预留时间',
    expire_at    DATETIME    NOT NULL COMMENT '预留过期时间（超时进入补偿）',
    confirmed_at DATETIME    NULL COMMENT '确认时间',
    released_at  DATETIME    NULL COMMENT '释放时间',
    retry_count  INT         NOT NULL DEFAULT 0 COMMENT '补偿重试次数',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_key (message_key),
    KEY idx_status_expire (status, expire_at),
    KEY idx_activity_user (activity_id, user_id)
) ENGINE = InnoDB COMMENT '报名预留记录表';

-- ---------------------------------------------------------------------
-- 候补队列表（Redis ZSet 为实时队列，此表为持久化依据与审计；queue_no 按真实排队顺序）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS waitlist (
    id              BIGINT   NOT NULL COMMENT '雪花ID',
    activity_id     BIGINT   NOT NULL COMMENT '活动ID',
    user_id         BIGINT   NOT NULL COMMENT '用户ID',
    queue_no        INT      NOT NULL COMMENT '排队号（活动内递增，体现真实顺序）',
    status          TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0候补中 1已晋级待确认 2候补转正 3晋级超时 4已取消',
    join_time       DATETIME NOT NULL COMMENT '入队时间',
    promoted_at     DATETIME NULL COMMENT '晋级时间',
    confirm_deadline DATETIME NULL COMMENT '晋级后确认截止时间',
    confirmed_at    DATETIME NULL COMMENT '转正时间',
    closed_at       DATETIME NULL COMMENT '退出/超时/转正后关闭时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_user (activity_id, user_id),
    UNIQUE KEY uk_activity_queue (activity_id, queue_no),
    KEY idx_status_deadline (status, confirm_deadline),
    KEY idx_activity_status (activity_id, status)
) ENGINE = InnoDB COMMENT '候补队列表';

-- ---------------------------------------------------------------------
-- 签到凭证表（token 仅存 SHA-256 摘要，凭证本体不落库，防伪造）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS checkin_token (
    id              BIGINT    NOT NULL COMMENT '雪花ID',
    registration_id BIGINT    NOT NULL COMMENT '报名ID',
    activity_id     BIGINT    NOT NULL COMMENT '活动ID',
    user_id         BIGINT    NOT NULL COMMENT '用户ID',
    token_hash      CHAR(64)  NOT NULL COMMENT '凭证SHA-256摘要（明文token不下发数据库）',
    status          TINYINT   NOT NULL DEFAULT 0 COMMENT '状态：0有效 1已使用 2已过期',
    issued_at       DATETIME  NOT NULL COMMENT '签发时间',
    expire_at       DATETIME  NOT NULL COMMENT '过期时间（活动结束）',
    used_at         DATETIME  NULL COMMENT '核销时间',
    created_at      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration (registration_id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_activity (activity_id)
) ENGINE = InnoDB COMMENT '签到凭证表';

-- ---------------------------------------------------------------------
-- 签到记录表（uk_registration 保证一凭证只成功核销一次）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS checkin_record (
    id              BIGINT    NOT NULL COMMENT '雪花ID',
    activity_id     BIGINT    NOT NULL COMMENT '活动ID',
    registration_id BIGINT    NOT NULL COMMENT '报名ID',
    user_id         BIGINT    NOT NULL COMMENT '被签到用户ID',
    operator_id     BIGINT    NOT NULL COMMENT '核销操作人ID（组织者/管理员）',
    token_hash      CHAR(64)  NOT NULL COMMENT '核销时使用的凭证摘要',
    checkin_at      DATETIME  NOT NULL COMMENT '签到时间',
    created_at      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_registration (registration_id),
    KEY idx_activity_time (activity_id, checkin_at)
) ENGINE = InnoDB COMMENT '签到记录表';

-- ---------------------------------------------------------------------
-- 站内通知表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notify_message (
    id         BIGINT       NOT NULL COMMENT '雪花ID',
    user_id    BIGINT       NOT NULL COMMENT '接收用户ID',
    title      VARCHAR(128) NOT NULL COMMENT '标题',
    content    VARCHAR(512) NOT NULL COMMENT '内容',
    type       TINYINT      NOT NULL COMMENT '类型：0报名成功 1确认失败 2取消成功 3候补排队 4候补晋级 5晋级超时 6签到成功 7活动变更 8活动取消 9审核通过 10审核未通过',
    biz_ref    VARCHAR(64)  NULL COMMENT '关联业务ID',
    is_read    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0未读 1已读',
    read_at    DATETIME     NULL COMMENT '已读时间',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, is_read, created_at)
) ENGINE = InnoDB COMMENT '站内通知表';

-- ---------------------------------------------------------------------
-- 库存变更审计表（biz_key 唯一：幂等防线，防止补偿重复加回名额）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_audit_log (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    activity_id BIGINT       NOT NULL COMMENT '活动ID',
    delta       INT          NOT NULL COMMENT '库存变动量（正增负减）',
    after_stock INT          NULL COMMENT '变更后Redis库存（可NULL）',
    source      VARCHAR(32)  NOT NULL COMMENT '来源：RESERVE/CANCEL/WAITLIST_SERVE/WAITLIST_TIMEOUT/COMPENSATE/RECONCILE',
    biz_key     VARCHAR(64)  NOT NULL COMMENT '业务键（幂等：预留ID/取消注册ID/补偿任务键）',
    operator_id BIGINT       NULL COMMENT '操作人（系统操作为NULL）',
    remark      VARCHAR(255) NULL COMMENT '备注',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_activity_source (activity_id, source)
) ENGINE = InnoDB COMMENT '库存变更审计表';

-- ---------------------------------------------------------------------
-- 系统操作审计表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    user_id     BIGINT       NULL COMMENT '操作用户ID',
    username    VARCHAR(64)  NULL COMMENT '操作用户名',
    action      VARCHAR(64)  NOT NULL COMMENT '动作：LOGIN/REGISTER/CREATE_ACTIVITY/PUBLISH/REVIEW/CHECKIN等',
    resource    VARCHAR(64)  NULL COMMENT '资源类型',
    resource_id VARCHAR(64)  NULL COMMENT '资源ID',
    detail      VARCHAR(512) NULL COMMENT '详情',
    ip          VARCHAR(45)  NULL COMMENT '客户端IP',
    trace_id    VARCHAR(32)  NULL COMMENT '链路追踪ID',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_action_time (action, created_at)
) ENGINE = InnoDB COMMENT '系统操作审计表';

-- ---------------------------------------------------------------------
-- 本地消息表（事务性发件箱：业务事务内写入，扫描任务负责投递与确认追踪）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_deliver (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    msg_key        VARCHAR(64)  NOT NULL COMMENT '消息业务键（全局唯一，消费幂等依据）',
    topic          VARCHAR(128) NOT NULL COMMENT '目标Topic',
    tag            VARCHAR(64)  NULL COMMENT 'Tag',
    biz_type       VARCHAR(32)  NOT NULL COMMENT '业务类型：REGISTRATION/CANCEL/WAITLIST/NOTIFY/CHECKIN',
    body           TEXT         NOT NULL COMMENT '消息体JSON',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待发送 1已发送 2消费方已确认 3发送失败',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME    NOT NULL COMMENT '下次重试时间',
    sent_at        DATETIME     NULL COMMENT '发送成功时间',
    confirmed_at   DATETIME     NULL COMMENT '消费确认时间',
    last_error     VARCHAR(255) NULL COMMENT '最近错误',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_key (msg_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表（可靠投递）';

-- ---------------------------------------------------------------------
-- 消息消费幂等表（uk_msg_group 唯一约束：重复投递的最终防线）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_consume_log (
    id             BIGINT      NOT NULL COMMENT '雪花ID',
    msg_key        VARCHAR(64) NOT NULL COMMENT '消息业务键',
    consumer_group VARCHAR(64) NOT NULL COMMENT '消费组',
    status         TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1处理成功 2处理失败',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_group (msg_key, consumer_group)
) ENGINE = InnoDB COMMENT '消息消费幂等记录表';

-- ---------------------------------------------------------------------
-- 定时补偿任务记录表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compensation_task (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    biz_type       VARCHAR(32)  NOT NULL COMMENT '任务类型：RESERVATION_TIMEOUT/STOCK_RECONCILE/MESSAGE_RETRY等',
    biz_key        VARCHAR(64)  NOT NULL COMMENT '业务键（唯一，防止重复创建）',
    payload        TEXT         NULL COMMENT '任务载荷JSON',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待处理 1处理中 2成功 3失败可重试 4死信',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    max_retry      INT          NOT NULL DEFAULT 5 COMMENT '最大重试次数',
    next_retry_time DATETIME    NOT NULL COMMENT '下次执行时间',
    last_error     VARCHAR(255) NULL COMMENT '最近错误',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '定时补偿任务记录表';

-- ---------------------------------------------------------------------
-- 导出任务表（异步CSV导出，有界线程池执行）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS export_task (
    id         BIGINT       NOT NULL COMMENT '雪花ID',
    user_id    BIGINT       NOT NULL COMMENT '发起导出的用户ID',
    activity_id BIGINT      NULL COMMENT '活动ID（按活动导出时）',
    task_type  VARCHAR(32)  NOT NULL COMMENT '导出类型：REGISTRATIONS',
    status     TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0排队 1执行中 2成功 3失败',
    file_path  VARCHAR(255) NULL COMMENT '生成文件路径',
    row_count  INT          NULL COMMENT '导出行数',
    error      VARCHAR(255) NULL COMMENT '失败原因',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_activity (activity_id)
) ENGINE = InnoDB COMMENT '导出任务表';
