-- ============================================================
-- security-lab 测试数据
-- 执行前提：已经执行过 01-schema.sql
--
-- 注意：
-- 1. 下面三个 password_hash 是 BCrypt 哈希，对应明文分别是：
--      admin   -> admin123
--      user_admin -> user123
--      viewer  -> viewer123
-- 2. 这些哈希只用于本地学习，生产环境必须重新生成，并且不要把真实密码写进 SQL。
-- 3. BCrypt 每次加密结果都不同，这里固定哈希是为了让教程数据可复现。
-- ============================================================

USE security_lab;

-- 清空旧数据，方便反复执行（顺序不能乱）
DELETE FROM sys_user_role;
DELETE FROM sys_role_permission;
DELETE FROM sys_login_log;
DELETE FROM sys_user;
DELETE FROM sys_role;
DELETE FROM sys_permission;

-- ------------------------------------------------------------
-- 用户
-- ------------------------------------------------------------
INSERT INTO sys_user (id, username, password_hash, nickname, phone, email, status, token_version)
VALUES (1, 'admin',
        '$2a$10$G4w4ZjRmRERBL.oPFRmj0Od4kYQE/hrcxCMFl8DP2dMJRr8fG1N3i',
        '超级管理员', '13800000001', 'admin@example.com', 1, 0),
       (2, 'user_admin',
        '$2a$10$A78MHIfWmqMxHiVp3CRbuOSWq.KcGRqnL.aLTVOXXSwXhBpkxajk.',
        '用户管理员', '13800000002', 'user_admin@example.com', 1, 0),
       (3, 'viewer',
        '$2a$10$SdDaE8MyqoPSBTKN96MlU.X.NQIpxqEQvW1mU/GdTnSl1hvfwD2SO',
        '只读用户', '13800000003', 'viewer@example.com', 1, 0);

-- ------------------------------------------------------------
-- 角色
-- ------------------------------------------------------------
INSERT INTO sys_role (id, code, name, description, status)
VALUES (1, 'SUPER_ADMIN', '超级管理员', '拥有全部权限', 1),
       (2, 'USER_ADMIN', '用户管理员', '可以管理用户，角色和权限只读', 1),
       (3, 'VIEWER', '只读用户', '只能查看用户、角色、权限列表', 1);

-- ------------------------------------------------------------
-- 权限
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, module, description, status)
VALUES (1, 'user:list', '查询用户', 'user', '分页查询、查看用户详情', 1),
       (2, 'user:add', '新增用户', 'user', '创建用户', 1),
       (3, 'user:update', '修改用户', 'user', '修改用户资料、分配角色', 1),
       (4, 'user:delete', '删除用户', 'user', '删除用户', 1),
       (5, 'role:list', '查询角色', 'role', '查询角色列表、角色权限', 1),
       (6, 'role:add', '新增角色', 'role', '创建角色', 1),
       (7, 'role:update', '修改角色', 'role', '修改角色、分配权限', 1),
       (8, 'role:delete', '删除角色', 'role', '删除角色', 1),
       (9, 'permission:list', '查询权限', 'permission', '查询权限列表', 1),
       (10, 'permission:add', '新增权限', 'permission', '创建权限', 1),
       (11, 'permission:update', '修改权限', 'permission', '修改权限', 1),
       (12, 'permission:delete', '删除权限', 'permission', '删除权限', 1);

-- ------------------------------------------------------------
-- 用户-角色
-- admin -> SUPER_ADMIN
-- user_admin -> USER_ADMIN
-- viewer -> VIEWER
-- ------------------------------------------------------------
INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1),
       (2, 2),
       (3, 3);

-- ------------------------------------------------------------
-- 角色-权限
-- 角色 1 SUPER_ADMIN：全部 12 个权限
-- 角色 2 USER_ADMIN：user:list、user:add、user:update、role:list、permission:list
-- 角色 3 VIEWER：user:list、role:list、permission:list
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (role_id, permission_id)
VALUES (1, 1), (1, 2), (1, 3), (1, 4),
       (1, 5), (1, 6), (1, 7), (1, 8),
       (1, 9), (1, 10), (1, 11), (1, 12),
       (2, 1), (2, 2), (2, 3), (2, 5), (2, 9),
       (3, 1), (3, 5), (3, 9);

-- ------------------------------------------------------------
-- 自检：应该分别返回 3、3、12、3、20
-- ------------------------------------------------------------
-- SELECT COUNT(*) FROM sys_user;
-- SELECT COUNT(*) FROM sys_role;
-- SELECT COUNT(*) FROM sys_permission;
-- SELECT COUNT(*) FROM sys_user_role;
-- SELECT COUNT(*) FROM sys_role_permission;
