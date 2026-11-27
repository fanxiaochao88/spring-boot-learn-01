-- ============================================================
-- security-lab 建库建表脚本
-- 适用：MySQL 8.x
-- 执行方式：
--   mysql -u root -p < 01-schema.sql
-- 或者在 IDEA 的 Database 工具里整段执行
-- ============================================================

CREATE DATABASE IF NOT EXISTS security_lab
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE security_lab;

-- 先关外键检查，避免删表顺序问题
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS sys_login_log;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;

SET FOREIGN_KEY_CHECKS = 1;

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username        VARCHAR(50)  NOT NULL                COMMENT '登录名，唯一',
    password_hash   VARCHAR(100) NOT NULL                COMMENT 'BCrypt 密码哈希，禁止存明文',
    nickname        VARCHAR(50)  DEFAULT NULL            COMMENT '昵称',
    phone           VARCHAR(20)  DEFAULT NULL            COMMENT '手机号',
    email           VARCHAR(100) DEFAULT NULL            COMMENT '邮箱',
    status          TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1 正常，0 禁用',
    token_version   INT          NOT NULL DEFAULT 0      COMMENT '令牌版本号，退出登录时 +1',
    last_login_time DATETIME     DEFAULT NULL            COMMENT '最后登录时间',
    last_login_ip   VARCHAR(64)  DEFAULT NULL            COMMENT '最后登录 IP',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户表';

-- ------------------------------------------------------------
-- 角色表
-- ------------------------------------------------------------
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(50)  NOT NULL                COMMENT '角色编码，如 SUPER_ADMIN，唯一',
    name        VARCHAR(50)  NOT NULL                COMMENT '角色名称',
    description VARCHAR(255) DEFAULT NULL            COMMENT '描述',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1 启用，0 禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '角色表';

-- ------------------------------------------------------------
-- 权限表
-- ------------------------------------------------------------
CREATE TABLE sys_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(100) NOT NULL                COMMENT '权限编码，如 user:add，唯一',
    name        VARCHAR(50)  NOT NULL                COMMENT '权限名称',
    module      VARCHAR(50)  DEFAULT NULL            COMMENT '所属模块：user / role / permission',
    description VARCHAR(255) DEFAULT NULL            COMMENT '描述',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1 启用，0 禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '权限表';

-- ------------------------------------------------------------
-- 用户-角色关联表
-- ------------------------------------------------------------
CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role_id (role_id),
    CONSTRAINT fk_sys_user_role_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_role_role
        FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户-角色关联表';

-- ------------------------------------------------------------
-- 角色-权限关联表
-- ------------------------------------------------------------
CREATE TABLE sys_role_permission (
    role_id       BIGINT NOT NULL COMMENT '角色 ID',
    permission_id BIGINT NOT NULL COMMENT '权限 ID',
    PRIMARY KEY (role_id, permission_id),
    KEY idx_sys_role_permission_permission_id (permission_id),
    CONSTRAINT fk_sys_role_permission_role
        FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_role_permission_permission
        FOREIGN KEY (permission_id) REFERENCES sys_permission (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '角色-权限关联表';

-- ------------------------------------------------------------
-- 登录日志表
-- ------------------------------------------------------------
CREATE TABLE sys_login_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       DEFAULT NULL            COMMENT '用户 ID，用户名不存在时为空',
    username    VARCHAR(50)  NOT NULL                COMMENT '登录名',
    ip          VARCHAR(64)  DEFAULT NULL            COMMENT '客户端 IP',
    user_agent  VARCHAR(255) DEFAULT NULL            COMMENT 'User-Agent',
    success     TINYINT      NOT NULL                COMMENT '是否成功：1 成功，0 失败',
    message     VARCHAR(255) DEFAULT NULL            COMMENT '失败原因或备注',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_sys_login_log_user_id (user_id),
    KEY idx_sys_login_log_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '登录日志表';
