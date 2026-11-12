# 第 02 章：RBAC 权限与 Spring Security 重写

> 配套：[00-后续章节规划与上下文.md](00-后续章节规划与上下文.md)
> 预计用时：6～8 小时，建议拆成三次：
> 1. 建表 + 手写 RBAC；
> 2. Spring Security 认证重写；
> 3. Spring Security 授权 + 验收。
> 前置：第 01 章完成，后台登录、JWT、拦截器已经跑通。
> 本章产出：角色/权限表、手写 `@RequirePermission` + AOP、Spring Security 6 的完整认证授权、401/403 统一 JSON。

---

## 0. 本章目标与前置检查

### 0.1 你会做出什么

第 01 章解决的是**认证**：你是谁。本章解决**授权**：登录之后，你能做什么。

做完本章，你会得到三个后台账号：

| 账号 | 密码 | 角色 | 能做什么 |
|---|---|---|---|
| `admin` | `admin123` | `SUPER_ADMIN` | 全部接口 |
| `product_user` | `product123` | `PRODUCT_ADMIN` | 商品查询、新增、修改、删除、扣库存 |
| `order_user` | `order123` | `ORDER_ADMIN` | 订单查询、发货（本章先用一个 stub 接口验证） |

并且：

- `product_user` 调 `/api/admin/roles` 返回 **403**；
- `order_user` 调 `/api/admin/products` 返回 **403**；
- 不带 token 返回 **401**；
- 手写版（拦截器 + AOP）和 Spring Security 版能对照讲解。

### 0.2 前置检查

确认第 01 章的东西都在：

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

能拿到 token，并且：

```bash
curl -i http://localhost:8089/api/admin/products -H "Authorization: Bearer $TOKEN"
```

返回 200，说明第 01 章完成。

确认数据库：

```sql
USE mall_product;
SHOW TABLES;
-- 应该能看到 sys_user、sys_login_log、product、product_stock_log
```

### 0.3 本章的学习路径

```text
第 1 步：建 RBAC 三张核心表 + 两张关联表
        ↓
第 2 步：手写版
        拦截器里加载权限 → 自定义 @RequirePermission → AOP 校验
        ↓
第 3 步：Spring Security 重写认证
        SecurityFilterChain + UserDetailsService + JwtAuthenticationFilter
        ↓
第 4 步：Spring Security 重写授权
        @PreAuthorize + AuthenticationEntryPoint / AccessDeniedHandler
        ↓
第 5 步：对照两套实现，做验收
```

> **为什么要先手写一遍？**
> 如果直接上 Spring Security，你看到的是 `SecurityFilterChain`、`AuthenticationManager`、`SecurityContext` 一堆配置，很容易"会抄不会改"。先手写一遍，你会知道框架到底替你做了什么；再换框架，每个配置项都能对上手写版的某一行代码。

### 0.4 先提交一次 git

```bash
git add .
git commit -m "chore: 第 02 章开始前的基线（第 01 章已完成）"
```

第 4 步会删除手写版的拦截器和 AOP。有这次提交，你随时可以回来看旧代码。

---

## 1. 为什么需要 RBAC

### 1.1 只有"登录"不够

第 01 章的拦截器只判断"有没有登录"。这意味着：只要登录成功，`product_user` 也能删商品、改角色、看订单。

真实后台不能这样。你需要回答：

- 商品管理员能不能看订单？不能。
- 订单管理员能不能改商品价格？不能。
- 超级管理员能不能做所有事？能。

### 1.2 RBAC 的三个概念

**RBAC = Role-Based Access Control，基于角色的访问控制。**

```text
用户 ──< 用户角色 >── 角色 ──< 角色权限 >── 权限
```

- **用户**：登录的人，例如 `product_user`。
- **角色**：一组职责，例如 `PRODUCT_ADMIN`、`ORDER_ADMIN`。
- **权限**：一个具体动作，例如 `product:write`、`order:ship`。

用户不直接绑定权限，而是通过角色间接获得。这样做的好处：

1. 新增一个商品管理员，只要分配 `PRODUCT_ADMIN`，不用一条条勾权限。
2. 调整角色权限，所有拥有该角色的用户一起生效。
3. 权限码稳定，角色可以自由组合。

### 1.3 角色 vs 权限

| 概念 | 例子 | 特点 |
|---|---|---|
| 角色 | `SUPER_ADMIN`、`PRODUCT_ADMIN` | 粗粒度、稳定、给人看 |
| 权限 | `product:read`、`product:write` | 细粒度、可组合、给代码看 |

**代码里判断权限，不判断角色。** 例如：

```java
// 推荐：这个接口需要"商品写权限"
@PreAuthorize("hasAuthority('product:write')")

// 不推荐：这个接口只有 PRODUCT_ADMIN 能访问
@PreAuthorize("hasRole('PRODUCT_ADMIN')")
```

原因：以后如果新增一个 `OPERATIONS_ADMIN` 角色，也需要改商品，你只要给它分配 `product:write`，不用改代码。判断角色的写法会逼你回来改注解。

### 1.4 本项目的权限码

| 权限码 | 模块 | 说明 |
|---|---|---|
| `product:read` | 商品 | 查看商品、分页 |
| `product:write` | 商品 | 新增、修改、上下架 |
| `product:delete` | 商品 | 逻辑删除 |
| `stock:decrease` | 商品 | 扣减库存 |
| `order:read` | 订单 | 查看订单 |
| `order:ship` | 订单 | 发货 |
| `user:read` | 系统 | 查看后台用户 |
| `user:write` | 系统 | 新增、修改、禁用用户 |
| `role:read` | 系统 | 查看角色和权限 |
| `role:write` | 系统 | 新增、修改、分配权限 |
| `log:read` | 系统 | 查看登录/操作日志 |

> 权限码没有绝对标准。关键是：**全局唯一、模块前缀、动作清晰、前端/后端都能看懂**。

---

## 2. 建表与种子数据

### 2.1 `sys_role`

```sql
USE mall_product;

CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(64)     NOT NULL                COMMENT '角色编码，如 PRODUCT_ADMIN',
  name        VARCHAR(64)     NOT NULL                COMMENT '角色名称',
  description VARCHAR(255)    DEFAULT NULL            COMMENT '描述',
  status      TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0禁用',
  version     INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色';
```

### 2.2 `sys_permission`

```sql
CREATE TABLE IF NOT EXISTS sys_permission (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(64)     NOT NULL                COMMENT '权限码，如 product:read',
  name        VARCHAR(64)     NOT NULL                COMMENT '权限名称',
  module      VARCHAR(32)     NOT NULL                COMMENT '模块：product/order/system',
  description VARCHAR(255)    DEFAULT NULL            COMMENT '描述',
  status      TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0禁用',
  version     INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  KEY idx_module (module)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限';
```

### 2.3 两张关联表

```sql
CREATE TABLE IF NOT EXISTS sys_user_role (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  role_id     BIGINT UNSIGNED NOT NULL,
  create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id),
  KEY idx_role_id (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户-角色';

CREATE TABLE IF NOT EXISTS sys_role_permission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_id       BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_permission (role_id, permission_id),
  KEY idx_permission_id (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色-权限';
```

> 关联表**不做逻辑删除**，也不加 `version`。解除关联就是删行，没有"软删除"的意义。这也避免了逻辑删除 + 唯一索引的经典冲突。

### 2.4 种子角色与权限

```sql
INSERT INTO sys_role (code, name, description, status) VALUES
('SUPER_ADMIN',   '超级管理员', '拥有全部权限', 1),
('PRODUCT_ADMIN', '商品管理员', '商品与库存',   1),
('ORDER_ADMIN',   '订单管理员', '订单与发货',   1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  description = VALUES(description),
  status = VALUES(status);

INSERT INTO sys_permission (code, name, module, description, status) VALUES
('product:read',   '商品查询',     'product', '查看商品、分页', 1),
('product:write',  '商品新增修改', 'product', '新增、修改、上下架', 1),
('product:delete', '商品删除',     'product', '逻辑删除商品', 1),
('stock:decrease', '库存扣减',     'product', '扣减库存', 1),
('order:read',     '订单查询',     'order',   '查看订单', 1),
('order:ship',     '订单发货',     'order',   '订单发货', 1),
('user:read',      '用户查询',     'system',  '查看后台用户', 1),
('user:write',     '用户管理',     'system',  '新增、修改、禁用用户', 1),
('role:read',      '角色查询',     'system',  '查看角色和权限', 1),
('role:write',     '角色管理',     'system',  '新增、修改、分配权限', 1),
('log:read',       '日志查询',     'system',  '查看登录/操作日志', 1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  module = VALUES(module),
  description = VALUES(description),
  status = VALUES(status);
```

### 2.5 给角色分配权限

```sql
-- SUPER_ADMIN：全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p
WHERE r.code = 'SUPER_ADMIN';

-- PRODUCT_ADMIN：商品相关
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p
WHERE r.code = 'PRODUCT_ADMIN'
  AND p.code IN ('product:read', 'product:write', 'product:delete', 'stock:decrease');

-- ORDER_ADMIN：订单相关
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p
WHERE r.code = 'ORDER_ADMIN'
  AND p.code IN ('order:read', 'order:ship');
```

### 2.6 种子用户

`admin` 第 01 章已经有了。再补两个：

```sql
INSERT INTO sys_user (username, password_hash, nickname, status, token_version)
VALUES
('product_user',
 '$2a$10$ZqYOF6vCmij8bDDbqO.kZ.S/pGol29lyu7z4.X2SKAum7bbKPOGRW',
 '商品管理员', 1, 0),
('order_user',
 '$2a$10$gPkhjrmMjcioKZuKdIB24.Oj9tuWZ9ByR6BO0ZAQP9DbQ7M4e5dfq',
 '订单管理员', 1, 0)
ON DUPLICATE KEY UPDATE
  nickname = VALUES(nickname),
  status = VALUES(status);
```

明文密码：

| 用户名 | 明文密码 |
|---|---|
| `product_user` | `product123` |
| `order_user` | `order123` |

> 如果这两个用户之前已经存在，`ON DUPLICATE KEY UPDATE` 只会更新昵称和状态，**不会改密码**。如果登录失败，先确认密码哈希是不是本章这一份；需要强制重置时执行：
>
> ```sql
> UPDATE sys_user
> SET password_hash = '$2a$10$ZqYOF6vCmij8bDDbqO.kZ.S/pGol29lyu7z4.X2SKAum7bbKPOGRW'
> WHERE username = 'product_user';
>
> UPDATE sys_user
> SET password_hash = '$2a$10$gPkhjrmMjcioKZuKdIB24.Oj9tuWZ9ByR6BO0ZAQP9DbQ7M4e5dfq'
> WHERE username = 'order_user';
> ```

### 2.7 给用户分配角色

```sql
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r
WHERE u.username = 'admin' AND r.code = 'SUPER_ADMIN';

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r
WHERE u.username = 'product_user' AND r.code = 'PRODUCT_ADMIN';

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r
WHERE u.username = 'order_user' AND r.code = 'ORDER_ADMIN';
```

### 2.8 验证

```sql
-- 用户和角色
SELECT u.username, r.code AS role_code
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r ON r.id = ur.role_id
ORDER BY u.id;

-- 角色和权限
SELECT r.code AS role_code, p.code AS permission_code
FROM sys_role r
JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p ON p.id = rp.permission_id
ORDER BY r.id, p.code;
```

预期：

- `admin` → `SUPER_ADMIN`，有 11 条权限；
- `product_user` → `PRODUCT_ADMIN`，有 4 条商品权限；
- `order_user` → `ORDER_ADMIN`，有 2 条订单权限。

> **坑预告**：`sys_role`/`sys_permission` 有 `deleted` 字段。如果你在实验里逻辑删除了种子角色/权限，唯一索引仍然占着 `code`，`ON DUPLICATE KEY UPDATE` 不会把 `deleted` 改回 0。恢复时手动 `UPDATE ... SET deleted = 0`。

---

## 3. 手写版：注解 + AOP

这一节先不用 Spring Security，继续用第 01 章的拦截器，把权限加进去。

### 3.1 查询权限的 SQL

用户 → 角色 → 权限，是一条多表 JOIN：

```sql
SELECT DISTINCT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur ON ur.role_id = rp.role_id
JOIN sys_role r ON r.id = ur.role_id
WHERE ur.user_id = ?
  AND r.status = 1 AND r.deleted = 0
  AND p.status = 1 AND p.deleted = 0;
```

这条 SQL 会返回一个用户的全部权限码，例如：

```text
product:read
product:write
product:delete
stock:decrease
```

### 3.2 实体

新建 `src/main/java/com/keywin/mall/modules/system/entity/SysRole.java`：

```java
package com.keywin.mall.modules.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String description;

    private Integer status;

    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

新建 `src/main/java/com/keywin/mall/modules/system/entity/SysPermission.java`：

```java
package com.keywin.mall.modules.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String module;

    private String description;

    private Integer status;

    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

### 3.3 Mapper

新建 `src/main/java/com/keywin/mall/modules/system/mapper/SysRoleMapper.java`：

```java
package com.keywin.mall.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.mall.modules.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
```

新建 `src/main/java/com/keywin/mall/modules/system/mapper/SysPermissionMapper.java`：

```java
package com.keywin.mall.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.mall.modules.system.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 查询用户拥有的权限码 */
    @Select("""
            SELECT DISTINCT p.code
            FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1 AND r.deleted = 0
              AND p.status = 1 AND p.deleted = 0
            """)
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);

    /** 查询用户拥有的角色编码 */
    @Select("""
            SELECT DISTINCT r.code
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.status = 1 AND r.deleted = 0
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /** 查询某个角色拥有的权限码 */
    @Select("""
            SELECT p.code
            FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
              AND p.status = 1 AND p.deleted = 0
            ORDER BY p.code
            """)
    List<String> selectCodesByRoleId(@Param("roleId") Long roleId);
}
```

> 这三条 SQL 都用 `#{}`，没有字符串拼接。权限码来自数据库，不来自用户输入；即使用户输入了 `' OR 1=1`，也只会被当成普通字符串。

### 3.4 扩展 `UserContext`

第 01 章的 `UserContext` 只有用户信息。现在加上角色和权限：

```java
package com.keywin.mall.common.security;

import java.util.Set;

public record UserContext(
        Long userId,
        String username,
        String userType,
        Integer tokenVersion,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
```

### 3.5 修改 `JwtAuthInterceptor`

在拦截器里加载角色和权限，放进 `UserContext`。只列出改动部分，其他保持不变：

```java
import com.keywin.mall.modules.system.mapper.SysPermissionMapper;

import java.util.HashSet;
import java.util.Set;

// 构造函数多注入一个 Mapper
private final SysPermissionMapper sysPermissionMapper;

// preHandle 的最后，替换原来的 UserContextHolder.set(...)
Set<String> roles = new HashSet<>(
        sysPermissionMapper.selectRoleCodesByUserId(userId));
Set<String> permissions = new HashSet<>(
        sysPermissionMapper.selectPermissionCodesByUserId(userId));

UserContextHolder.set(new UserContext(
        user.getId(),
        user.getUsername(),
        "ADMIN",
        user.getTokenVersion(),
        roles,
        permissions));
```

改完后的完整字段：

```java
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserMapper sysUserMapper;
    private final SysPermissionMapper sysPermissionMapper;

    // ... preHandle 中间不变 ...
}
```

> 这样每个请求会多两次 SQL（角色、权限）。练习项目可以接受。生产环境通常会把权限缓存到 Redis，或者放进 `UserDetails` 的本地缓存；第 03 章会讨论"权限变更后立即生效"的取舍。

### 3.6 自定义注解 `@RequirePermission`

新建 `src/main/java/com/keywin/mall/common/security/RequirePermission.java`：

```java
package com.keywin.mall.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限码，例如 product:read */
    String[] value();

    /** 多个权限之间的关系，默认全部满足 */
    Logical logical() default Logical.AND;

    enum Logical {
        AND, OR
    }
}
```

用法：

```java
@RequirePermission("product:read")
@RequirePermission({"product:read", "product:write"})              // 两个都要有
@RequirePermission(value = {"order:read", "order:ship"}, logical = RequirePermission.Logical.OR) // 有一个即可
```

> 注解本身只是"配置单"，真正干活的是下面的切面。这和 `@Transactional`、`@Cacheable` 是同一个道理：注解负责标记，拦截器/代理负责解释。

### 3.7 权限切面

先加依赖。`@Aspect` 需要 AspectJ，`spring-boot-starter-web` 不包含它：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

新建 `src/main/java/com/keywin/mall/common/security/PermissionAspect.java`：

```java
package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@Order(1)
public class PermissionAspect {

    @Around("@annotation(com.keywin.mall.common.security.RequirePermission) "
            + "|| @within(com.keywin.mall.common.security.RequirePermission)")
    public Object check(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();

        // 先找方法上的注解，再找类上的注解
        RequirePermission annotation = AnnotatedElementUtils.findMergedAnnotation(
                signature.getMethod(), RequirePermission.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(
                    joinPoint.getTarget().getClass(), RequirePermission.class);
        }
        if (annotation == null) {
            return joinPoint.proceed();
        }

        UserContext context = UserContextHolder.get();
        boolean permitted = annotation.logical() == RequirePermission.Logical.AND
                ? Arrays.stream(annotation.value()).allMatch(context.permissions()::contains)
                : Arrays.stream(annotation.value()).anyMatch(context.permissions()::contains);

        if (!permitted) {
            throw new ForbiddenException(ResultCode.PERMISSION_DENIED);
        }
        return joinPoint.proceed();
    }
}
```

两个关键点：

1. **`@Order(1)`**：让权限检查先于 `@Transactional`（事务切面默认是最低优先级）。否则会出现"方法已经开了事务、甚至写了库，才被权限拒绝"的尴尬。
2. **`@annotation || @within`**：同时支持方法级和类级注解。如果只写 `@annotation`，类上的注解不会生效。

### 3.8 新增 `ForbiddenException`

新建 `src/main/java/com/keywin/mall/common/exception/ForbiddenException.java`：

```java
package com.keywin.mall.common.exception;

import com.keywin.mall.common.api.ResultCode;
import lombok.Getter;

@Getter
public class ForbiddenException extends RuntimeException {

    private final int code;

    public ForbiddenException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public ForbiddenException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}
```

在 `ResultCode` 里加：

```java
PERMISSION_DENIED(40301, "没有访问该资源的权限"),
```

在 `GlobalExceptionHandler` 里加：

```java
import com.keywin.mall.common.exception.ForbiddenException;

@ExceptionHandler(ForbiddenException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public Result<Void> handleForbidden(ForbiddenException e) {
    log.warn("权限不足: code={}, message={}", e.getCode(), e.getMessage());
    return Result.fail(e.getCode(), e.getMessage());
}
```

### 3.9 给商品接口加注解

修改 `ProductController`，给每个方法加 `@RequirePermission`：

```java
package com.keywin.mall.modules.product.controller;

import com.keywin.mall.common.api.PageResult;
import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.security.RequirePermission;
import com.keywin.mall.modules.product.dto.ProductCreateDTO;
import com.keywin.mall.modules.product.dto.ProductQueryDTO;
import com.keywin.mall.modules.product.dto.ProductUpdateDTO;
import com.keywin.mall.modules.product.dto.StockDecreaseDTO;
import com.keywin.mall.modules.product.service.ProductService;
import com.keywin.mall.modules.product.service.StockLogService;
import com.keywin.mall.modules.product.vo.ProductVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final StockLogService stockLogService;

    @RequirePermission("product:write")
    @PostMapping
    public Result<ProductVO> create(@Valid @RequestBody ProductCreateDTO productCreateDTO) {
        return Result.success(productService.create(productCreateDTO));
    }

    @RequirePermission("product:read")
    @GetMapping("/{id}")
    public Result<ProductVO> get(@PathVariable Long id) {
        return Result.success(productService.getById(id));
    }

    @RequirePermission("product:read")
    @GetMapping
    public Result<PageResult<ProductVO>> page(@Valid ProductQueryDTO productQueryDTO) {
        return Result.success(productService.page(productQueryDTO));
    }

    @RequirePermission("product:write")
    @PutMapping("/{id}")
    public Result<ProductVO> update(@PathVariable Long id,
                                    @Valid @RequestBody ProductUpdateDTO productUpdateDTO) {
        return Result.success(productService.update(id, productUpdateDTO));
    }

    @RequirePermission("product:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success();
    }

    @RequirePermission("stock:decrease")
    @PostMapping("/{id}/stock/decrease")
    public Result<Void> decreaseStock(@PathVariable Long id,
                                      @Valid @RequestBody StockDecreaseDTO stockDecreaseDTO) {
        stockLogService.decrease(id, stockDecreaseDTO.getCount(), stockDecreaseDTO.getReason());
        return Result.success();
    }
}
```

### 3.10 新增一个角色查询接口

为了验证"已登录但无权限"，需要一个 `product_user` 不能访问的接口。

新建 `src/main/java/com/keywin/mall/modules/system/vo/SysRoleVO.java`：

```java
package com.keywin.mall.modules.system.vo;

import lombok.Data;

import java.util.List;

@Data
public class SysRoleVO {

    private Long id;

    private String code;

    private String name;

    private String description;

    private Integer status;

    /** 该角色拥有的权限码 */
    private List<String> permissions;
}
```

新建 `src/main/java/com/keywin/mall/modules/system/service/SysRoleService.java`：

```java
package com.keywin.mall.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.keywin.mall.modules.system.entity.SysRole;
import com.keywin.mall.modules.system.mapper.SysPermissionMapper;
import com.keywin.mall.modules.system.mapper.SysRoleMapper;
import com.keywin.mall.modules.system.vo.SysRoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;

    public List<SysRoleVO> list() {
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .orderByAsc(SysRole::getId))
                .stream()
                .map(role -> {
                    SysRoleVO vo = new SysRoleVO();
                    BeanUtils.copyProperties(role, vo);
                    vo.setPermissions(sysPermissionMapper.selectCodesByRoleId(role.getId()));
                    return vo;
                })
                .toList();
    }
}
```

新建 `src/main/java/com/keywin/mall/modules/system/controller/SysRoleController.java`：

```java
package com.keywin.mall.modules.system.controller;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.security.RequirePermission;
import com.keywin.mall.modules.system.service.SysRoleService;
import com.keywin.mall.modules.system.vo.SysRoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService sysRoleService;

    @RequirePermission("role:read")
    @GetMapping
    public Result<List<SysRoleVO>> list() {
        return Result.success(sysRoleService.list());
    }
}
```

> 这里为了简单，角色列表用"一次角色查询 + 每个角色一次权限查询"（N+1）。角色数量通常很少，练习项目不必优化；第 04 章购物车会专门讲批量查询。

### 3.11 新增订单 stub 接口

订单模块第 06 章才做。现在先放一个 stub，用来验证 `order:read` 权限：

新建 `src/main/java/com/keywin/mall/modules/order/controller/AdminOrderStubController.java`：

```java
package com.keywin.mall.modules.order.controller;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderStubController {

    /** 第 06 章会替换成真实订单接口 */
    @RequirePermission("order:read")
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("order stub ready");
    }
}
```

### 3.12 验证手写版

重启应用。先登录三个账号：

```bash
login() {
  curl -s -X POST http://localhost:8089/api/admin/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])"
}

ADMIN_TOKEN=$(login admin admin123)
PRODUCT_TOKEN=$(login product_user product123)
ORDER_TOKEN=$(login order_user order123)

echo "admin:   ${ADMIN_TOKEN:0:20}..."
echo "product: ${PRODUCT_TOKEN:0:20}..."
echo "order:   ${ORDER_TOKEN:0:20}..."
```

**1）admin 能看角色**

```bash
curl -i http://localhost:8089/api/admin/roles \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

预期：200，`data` 里有 3 个角色，每个角色带 `permissions`。

**2）product_user 能看商品，不能看角色**

```bash
curl -i "http://localhost:8089/api/admin/products?pageNum=1&pageSize=2" \
  -H "Authorization: Bearer $PRODUCT_TOKEN"

curl -i http://localhost:8089/api/admin/roles \
  -H "Authorization: Bearer $PRODUCT_TOKEN"
```

预期：商品 200；角色 403，`code=40301`。

**3）order_user 能看订单 stub，不能看商品**

```bash
curl -i http://localhost:8089/api/admin/orders/ping \
  -H "Authorization: Bearer $ORDER_TOKEN"

curl -i "http://localhost:8089/api/admin/products?pageNum=1&pageSize=2" \
  -H "Authorization: Bearer $ORDER_TOKEN"
```

预期：订单 stub 200；商品 403。

**4）admin 什么都能看**

```bash
curl -i http://localhost:8089/api/admin/orders/ping -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i http://localhost:8089/api/admin/roles -H "Authorization: Bearer $ADMIN_TOKEN"
```

预期都是 200。

如果结果符合预期，手写版就通了。接下来用 Spring Security 重写。

---

## 4. Spring Security 重写

### 4.1 先看清要替换什么

手写版和 Spring Security 版的对应关系：

| 手写版 | Spring Security 版 |
|---|---|
| `JwtAuthInterceptor.preHandle` | `JwtAuthenticationFilter.doFilterInternal` |
| `UserContextHolder`（自定义 ThreadLocal） | `SecurityContextHolder` |
| 手动 `passwordEncoder.matches` | `DaoAuthenticationProvider` |
| `@RequirePermission` + `PermissionAspect` | `@PreAuthorize` |
| `UnauthorizedException` | `AuthenticationEntryPoint` |
| `ForbiddenException` | `AccessDeniedHandler` |
| `WebMvcConfig` 注册拦截器 | `SecurityFilterChain` |

**要删除的文件/代码**（先 git commit，删错了能回来）：

- `common/security/JwtAuthInterceptor.java`
- `common/security/UserContext.java`
- `common/security/UserContextHolder.java`
- `common/security/RequirePermission.java`
- `common/security/PermissionAspect.java`
- `config/WebMvcConfig.java`（如果里面只有拦截器注册）
- 各 Controller 上的 `@RequirePermission` 注解

**要保留的文件**：

- `JwtTokenProvider`
- `JwtProperties`
- `PasswordConfig`
- `AuthService` / `AuthServiceImpl`（要改内部实现）
- `SysLoginLogService`
- `SysUser`、`SysRole`、`SysPermission`、Mapper
- `UnauthorizedException`、`ForbiddenException`（保留，`AuthServiceImpl` 还会用到）

### 4.2 依赖

在 `pom.xml` 加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

同时删除第 01 章单独加的：

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

> `starter-security` 已经包含 `spring-security-crypto`。两个都留不会报错，但版本管理容易混乱，删掉更清楚。

`spring-boot-starter-aop` 可以保留（第 03 章的操作日志还会用），也可以等第 03 章再加。

### 4.3 `LoginUser`

新建 `src/main/java/com/keywin/mall/common/security/LoginUser.java`：

```java
package com.keywin.mall.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Getter
public class LoginUser implements UserDetails {

    private final Long userId;

    private final String username;

    /** BCrypt 密文，只在认证时使用，不要返回给前端 */
    private final String password;

    private final String nickname;

    private final Integer status;

    private final Integer tokenVersion;

    private final Set<String> roles;

    private final Set<String> permissions;

    private final List<SimpleGrantedAuthority> authorities;

    public LoginUser(Long userId, String username, String password, String nickname,
                     Integer status, Integer tokenVersion,
                     Set<String> roles, Set<String> permissions) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
        this.status = status;
        this.tokenVersion = tokenVersion;
        this.roles = roles;
        this.permissions = permissions;
        this.authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Integer.valueOf(1).equals(status);
    }

    @Override
    public String toString() {
        // 不打印密码
        return "LoginUser{userId=" + userId + ", username='" + username + "'}";
    }
}
```

> 注意 `authorities` 里放的是**权限码**（`product:read`），不是角色码（`PRODUCT_ADMIN`）。这决定了后面用 `hasAuthority` 而不是 `hasRole`。

### 4.4 `SysUserDetailsService`

新建 `src/main/java/com/keywin/mall/modules/system/service/SysUserDetailsService.java`：

```java
package com.keywin.mall.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.keywin.mall.common.security.LoginUser;
import com.keywin.mall.modules.system.entity.SysUser;
import com.keywin.mall.modules.system.mapper.SysPermissionMapper;
import com.keywin.mall.modules.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysUserDetailsService implements UserDetailsService {

    private final SysUserMapper sysUserMapper;
    private final SysPermissionMapper sysPermissionMapper;

    /** 登录时按用户名加载 */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return buildLoginUser(user);
    }

    /** JWT 过滤器按 userId 加载 */
    public LoginUser loadUserById(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return buildLoginUser(user);
    }

    private LoginUser buildLoginUser(SysUser user) {
        Set<String> roles = new HashSet<>(
                sysPermissionMapper.selectRoleCodesByUserId(user.getId()));
        Set<String> permissions = new HashSet<>(
                sysPermissionMapper.selectPermissionCodesByUserId(user.getId()));

        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getNickname(),
                user.getStatus(),
                user.getTokenVersion(),
                roles,
                permissions);
    }
}
```

> `UserDetailsService` 是 Spring Security 的认证入口。`DaoAuthenticationProvider` 会调用 `loadUserByUsername`，拿到 `UserDetails`，再用 `PasswordEncoder` 比对密码。
>
> `loadUserById` 不是接口方法，是给 JWT 过滤器用的：token 里只有 userId，没有用户名。

### 4.5 `SecurityUtils`

新建 `src/main/java/com/keywin/mall/common/security/SecurityUtils.java`：

```java
package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new UnauthorizedException(ResultCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    public static Long getUserId() {
        return getLoginUser().getUserId();
    }
}
```

> `SecurityContextHolder` 内部也是 `ThreadLocal`。它是 Spring Security 版的 `UserContextHolder`，但由框架负责清理，不需要你写 `afterCompletion`。

### 4.6 `JwtAuthenticationFilter`

新建 `src/main/java/com/keywin/mall/common/security/JwtAuthenticationFilter.java`：

```java
package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.modules.system.service.SysUserDetailsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证失败原因，交给 AuthenticationEntryPoint 读取 */
    public static final String AUTH_ERROR_ATTRIBUTE = "AUTH_ERROR";

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserDetailsService sysUserDetailsService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   SysUserDetailsService sysUserDetailsService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.sysUserDetailsService = sysUserDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        // 没带 token：不在这里报错，交给后面的授权规则决定
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7).trim();

        try {
            Claims claims = jwtTokenProvider.parse(token);

            // 后台过滤器只接受 ADMIN token；APP token 会在第 04 章由另一条链处理
            if (!"ADMIN".equals(claims.get("userType", String.class))) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ResultCode.TOKEN_INVALID);
                filterChain.doFilter(request, response);
                return;
            }

            Long userId = Long.valueOf(claims.getSubject());
            LoginUser loginUser = sysUserDetailsService.loadUserById(userId);

            if (!loginUser.isEnabled()) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ResultCode.ACCOUNT_DISABLED);
                filterChain.doFilter(request, response);
                return;
            }

            int tokenVersion = jwtTokenProvider.getTokenVersion(claims);
            if (!Objects.equals(tokenVersion, loginUser.getTokenVersion())) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ResultCode.TOKEN_INVALID);
                filterChain.doFilter(request, response);
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            loginUser, null, loginUser.getAuthorities());
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ResultCode.TOKEN_EXPIRED);
        } catch (UsernameNotFoundException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ResultCode.TOKEN_INVALID);
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ResultCode.TOKEN_INVALID);
        }

        filterChain.doFilter(request, response);
    }
}
```

> **为什么这个类没有 `@Component`？**
> 因为它是一个 `Filter`。如果加 `@Component`，Spring Boot 会把它注册到 Servlet 容器里，同时你又把它加进 Security 过滤器链，结果**每个请求执行两次**。本项目的做法是：不加 `@Component`，在 `SecurityConfig` 里 `new` 出来，只放进 Security 链。

在 `JwtTokenProvider` 里补一个方法：

```java
public int getTokenVersion(Claims claims) {
    Object value = claims.get("tokenVersion");
    return value instanceof Number number ? number.intValue() : -1;
}
```

### 4.7 401 / 403 统一 JSON

新建 `src/main/java/com/keywin/mall/common/security/RestAuthenticationEntryPoint.java`：

```java
package com.keywin.mall.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        ResultCode code = attribute instanceof ResultCode resultCode
                ? resultCode
                : ResultCode.UNAUTHORIZED;

        write(response, HttpStatus.UNAUTHORIZED, Result.fail(code));
    }

    private void write(HttpServletResponse response, HttpStatus status, Result<Void> body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
```

新建 `src/main/java/com/keywin/mall/common/security/RestAccessDeniedHandler.java`：

```java
package com.keywin.mall.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keywin.mall.common.api.Result;
import com.keywin.mall.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                Result.fail(ResultCode.PERMISSION_DENIED));
    }
}
```

> 401 和 403 的区别必须记牢：
> - **401 Unauthorized**：你没登录，或者 token 无效/过期。应该去登录。
> - **403 Forbidden**：你登录了，但权限不够。重新登录也没用。
>
> 很多项目把 403 也返回 401，前端会陷入"登录成功 → 又被踢回登录页"的死循环。

### 4.8 `SecurityConfig`

新建 `src/main/java/com/keywin/mall/config/SecurityConfig.java`：

```java
package com.keywin.mall.config;

import com.keywin.mall.common.security.JwtAuthenticationFilter;
import com.keywin.mall.common.security.JwtTokenProvider;
import com.keywin.mall.common.security.RestAccessDeniedHandler;
import com.keywin.mall.common.security.RestAuthenticationEntryPoint;
import com.keywin.mall.modules.system.service.SysUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * 后台链路：只匹配 /api/admin/**。
     * @Order(1) 必须比下面的公共链路小，否则所有请求会先被公共链路放行。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            JwtTokenProvider jwtTokenProvider,
            SysUserDetailsService sysUserDetailsService) throws Exception {

        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(jwtTokenProvider, sysUserDetailsService);

        http
                .securityMatcher("/api/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * 公共链路：不匹配 /api/admin/** 的其他请求。
     * 第 04 章会插入一条 /api/app/** 的链路，这条会变成 @Order(3)。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * 用 DaoAuthenticationProvider 替换第 01 章的手动密码比对。
     */
    @Bean
    public AuthenticationManager authenticationManager(
            SysUserDetailsService sysUserDetailsService,
            PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(sysUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
```

关键点：

1. **两条链的顺序**：`/api/admin/**` 必须在前，`/**` 在后。匹配到第一条就停止，不会继续走第二条。
2. **`SessionCreationPolicy.STATELESS`**：不创建 HttpSession，完全靠 token。
3. **CSRF 关闭**：没有 Cookie 会话，token 放在 Header 里，CSRF 不适用。
4. **`@EnableMethodSecurity`**：没有它，`@PreAuthorize` 会被静默忽略。
5. **`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`**：JWT 过滤器要在用户名密码过滤器之前跑。

### 4.9 改 `AuthServiceImpl`

第 01 章的 `AuthServiceImpl` 是手动查用户 + `passwordEncoder.matches`。现在改成 `AuthenticationManager`。

完整替换：

```java
package com.keywin.mall.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;
import com.keywin.mall.common.security.JwtTokenProvider;
import com.keywin.mall.common.security.LoginUser;
import com.keywin.mall.common.security.SecurityUtils;
import com.keywin.mall.common.util.IpUtils;
import com.keywin.mall.modules.system.dto.AdminLoginDTO;
import com.keywin.mall.modules.system.entity.SysUser;
import com.keywin.mall.modules.system.mapper.SysUserMapper;
import com.keywin.mall.modules.system.service.AuthService;
import com.keywin.mall.modules.system.service.SysLoginLogService;
import com.keywin.mall.modules.system.vo.AdminLoginVO;
import com.keywin.mall.modules.system.vo.AdminUserVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysLoginLogService sysLoginLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;

    @Override
    public AdminLoginVO login(AdminLoginDTO dto, HttpServletRequest request) {
        String ip = IpUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            dto.getUsername(), dto.getPassword()));

            LoginUser loginUser = (LoginUser) authentication.getPrincipal();
            SysUser user = sysUserMapper.selectById(loginUser.getUserId());
            if (user == null) {
                throw new UnauthorizedException(ResultCode.USER_NOT_FOUND);
            }

            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getLastLoginTime, LocalDateTime.now())
                    .set(SysUser::getLastLoginIp, ip));

            String token = jwtTokenProvider.generate(
                    user.getId(), user.getUsername(), "ADMIN", user.getTokenVersion());

            sysLoginLogService.save(user.getId(), user.getUsername(), ip, userAgent, true, null);
            log.info("管理员登录成功: userId={}, username={}, ip={}",
                    user.getId(), user.getUsername(), ip);

            AdminLoginVO vo = new AdminLoginVO();
            vo.setToken(token);
            vo.setTokenType("Bearer");
            vo.setExpiresIn(jwtTokenProvider.getExpireSeconds());
            vo.setUser(toUserVO(user));
            return vo;

        } catch (DisabledException e) {
            sysLoginLogService.save(findUserIdQuietly(dto.getUsername()),
                    dto.getUsername(), ip, userAgent, false, "账号已禁用");
            throw new UnauthorizedException(ResultCode.ACCOUNT_DISABLED);

        } catch (BadCredentialsException e) {
            sysLoginLogService.save(findUserIdQuietly(dto.getUsername()),
                    dto.getUsername(), ip, userAgent, false, "用户名或密码错误");
            throw new UnauthorizedException(ResultCode.LOGIN_FAILED);

        } catch (AuthenticationException e) {
            sysLoginLogService.save(findUserIdQuietly(dto.getUsername()),
                    dto.getUsername(), ip, userAgent, false, "认证失败");
            throw new UnauthorizedException(ResultCode.LOGIN_FAILED);
        }
    }

    @Override
    public void logout() {
        LoginUser loginUser = SecurityUtils.getLoginUser();

        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, loginUser.getUserId())
                .setSql("token_version = token_version + 1"));

        log.info("管理员退出登录: userId={}", loginUser.getUserId());
    }

    @Override
    public AdminUserVO me() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser user = sysUserMapper.selectById(loginUser.getUserId());
        if (user == null) {
            throw new UnauthorizedException(ResultCode.USER_NOT_FOUND);
        }
        return toUserVO(user);
    }

    private Long findUserIdQuietly(String username) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        return user == null ? null : user.getId();
    }

    private AdminUserVO toUserVO(SysUser user) {
        AdminUserVO vo = new AdminUserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
```

> `DaoAuthenticationProvider` 默认会把"用户不存在"包装成 `BadCredentialsException`，这是好事：调用方无法区分"用户不存在"和"密码错误"。所以登录失败日志统一写"用户名或密码错误"即可。

### 4.10 把 `@RequirePermission` 换成 `@PreAuthorize`

先确认 `SecurityConfig` 上有 `@EnableMethodSecurity`。

然后逐个替换：

| 文件 | 原注解 | 新注解 |
|---|---|---|
| `ProductController.create` | `@RequirePermission("product:write")` | `@PreAuthorize("hasAuthority('product:write')")` |
| `ProductController.get` | `@RequirePermission("product:read")` | `@PreAuthorize("hasAuthority('product:read')")` |
| `ProductController.page` | `@RequirePermission("product:read")` | `@PreAuthorize("hasAuthority('product:read')")` |
| `ProductController.update` | `@RequirePermission("product:write")` | `@PreAuthorize("hasAuthority('product:write')")` |
| `ProductController.delete` | `@RequirePermission("product:delete")` | `@PreAuthorize("hasAuthority('product:delete')")` |
| `ProductController.decreaseStock` | `@RequirePermission("stock:decrease")` | `@PreAuthorize("hasAuthority('stock:decrease')")` |
| `SysRoleController.list` | `@RequirePermission("role:read")` | `@PreAuthorize("hasAuthority('role:read')")` |
| `AdminOrderStubController.ping` | `@RequirePermission("order:read")` | `@PreAuthorize("hasAuthority('order:read')")` |

以 `ProductController` 为例：

```java
import org.springframework.security.access.prepost.PreAuthorize;

// ...

@PreAuthorize("hasAuthority('product:read')")
@GetMapping
public Result<PageResult<ProductVO>> page(@Valid ProductQueryDTO productQueryDTO) {
    return Result.success(productService.page(productQueryDTO));
}
```

`SysRoleController`：

```java
import org.springframework.security.access.prepost.PreAuthorize;

@PreAuthorize("hasAuthority('role:read')")
@GetMapping
public Result<List<SysRoleVO>> list() {
    return Result.success(sysRoleService.list());
}
```

`AdminOrderStubController`：

```java
import org.springframework.security.access.prepost.PreAuthorize;

@PreAuthorize("hasAuthority('order:read')")
@GetMapping("/ping")
public Result<String> ping() {
    return Result.success("order stub ready");
}
```

### 4.11 补充 `AccessDeniedException` 处理器

`@PreAuthorize` 抛出的 `AccessDeniedException`，有可能被 `@RestControllerAdvice` 先捕获。如果不处理，会被 `Exception.class` 兜底成 **500**。

在 `GlobalExceptionHandler` 加：

```java
import org.springframework.security.access.AccessDeniedException;

@ExceptionHandler(AccessDeniedException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public Result<Void> handleAccessDenied(AccessDeniedException e) {
    log.warn("权限不足: {}", e.getMessage());
    return Result.fail(ResultCode.PERMISSION_DENIED);
}
```

> 这个坑非常常见：URL 级权限由 `AccessDeniedHandler` 处理，方法级 `@PreAuthorize` 抛出的异常却可能进 `@RestControllerAdvice`。两个地方都要配，才能保证 403 不会变成 500。

### 4.12 删除手写版

确认 Spring Security 版能启动后，删除：

```text
src/main/java/com/keywin/mall/common/security/JwtAuthInterceptor.java
src/main/java/com/keywin/mall/common/security/UserContext.java
src/main/java/com/keywin/mall/common/security/UserContextHolder.java
src/main/java/com/keywin/mall/common/security/RequirePermission.java
src/main/java/com/keywin/mall/common/security/PermissionAspect.java
src/main/java/com/keywin/mall/config/WebMvcConfig.java
```

同时删除 Controller 上残留的 `@RequirePermission` import。

> 不要"两套都留着"。两套过滤器/拦截器同时存在，会导致：
> - 请求被认证两次；
> - `UserContextHolder` 和 `SecurityContextHolder` 各存一份，容易不一致；
> - 排错时不知道是哪个组件返回的 401/403。
>
> 用 git 保留历史就够了。

---

## 5. 验证 Spring Security 版

### 5.1 启动

重启应用。如果启动失败，先看第 6 节的坑。

### 5.2 登录

```bash
login() {
  curl -s -X POST http://localhost:8089/api/admin/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])"
}

ADMIN_TOKEN=$(login admin admin123)
PRODUCT_TOKEN=$(login product_user product123)
ORDER_TOKEN=$(login order_user order123)
```

如果某个变量为空，说明登录失败。单独执行登录命令看响应：

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"product_user","password":"product123"}'
```

### 5.3 正常路径

**admin 全部可访问：**

```bash
curl -i http://localhost:8089/api/admin/auth/me -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i http://localhost:8089/api/admin/products -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i http://localhost:8089/api/admin/roles -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i http://localhost:8089/api/admin/orders/ping -H "Authorization: Bearer $ADMIN_TOKEN"
```

预期都是 200。

**product_user：**

```bash
curl -i http://localhost:8089/api/admin/products -H "Authorization: Bearer $PRODUCT_TOKEN"
# 200

curl -i http://localhost:8089/api/admin/roles -H "Authorization: Bearer $PRODUCT_TOKEN"
# 403, code=40301
```

**order_user：**

```bash
curl -i http://localhost:8089/api/admin/orders/ping -H "Authorization: Bearer $ORDER_TOKEN"
# 200

curl -i http://localhost:8089/api/admin/products -H "Authorization: Bearer $ORDER_TOKEN"
# 403, code=40301
```

### 5.4 异常路径

**1）不带 token**

```bash
curl -i http://localhost:8089/api/admin/products
```

预期：HTTP 401，`code=40100`。

**2）伪造 token**

```bash
curl -i http://localhost:8089/api/admin/products \
  -H "Authorization: Bearer abc.def.ghi"
```

预期：HTTP 401，`code=40103`。

**3）过期 token**

把 `expire-minutes` 临时改成 1，重启，登录，等 61 秒，再访问：

```bash
curl -i http://localhost:8089/api/admin/products \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

预期：HTTP 401，`code=40104`。

测试完改回 120。

**4）禁用账号**

```sql
UPDATE sys_user SET status = 0 WHERE username = 'product_user';
```

用 `product_user` 的旧 token 访问：

```bash
curl -i http://localhost:8089/api/admin/products \
  -H "Authorization: Bearer $PRODUCT_TOKEN"
```

预期：HTTP 401，`code=40105`。

恢复：

```sql
UPDATE sys_user SET status = 1 WHERE username = 'product_user';
```

**5）登出后旧 token 失效**

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/logout \
  -H "Authorization: Bearer $PRODUCT_TOKEN"

curl -i http://localhost:8089/api/admin/products \
  -H "Authorization: Bearer $PRODUCT_TOKEN"
```

预期：登出 200；再用旧 token 401 / 40103。

**6）登录失败**

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"product_user","password":"wrong"}'
```

预期：HTTP 401，`code=40101`。

### 5.5 SQL 验证权限

```sql
-- 查看 product_user 的权限
SELECT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur ON ur.role_id = rp.role_id
JOIN sys_user u ON u.id = ur.user_id
WHERE u.username = 'product_user'
  AND p.status = 1 AND p.deleted = 0
ORDER BY p.code;
```

预期 4 条：`product:delete`、`product:read`、`product:write`、`stock:decrease`。

### 5.6 故意制造的错误实验

**实验 1：把 `hasAuthority` 改成 `hasRole`**

把 `ProductController.page` 改成：

```java
@PreAuthorize("hasRole('PRODUCT_ADMIN')")
```

用 `product_user` 访问商品分页：

```bash
curl -i http://localhost:8089/api/admin/products -H "Authorization: Bearer $PRODUCT_TOKEN"
```

预期：403。原因：`hasRole('PRODUCT_ADMIN')` 实际检查的是 `ROLE_PRODUCT_ADMIN`，而我们的 authorities 是权限码 `product:read`，不是角色码，也没有 `ROLE_` 前缀。

改回：

```java
@PreAuthorize("hasAuthority('product:read')")
```

**实验 2：删掉 `AccessDeniedException` 处理器**

注释掉 `GlobalExceptionHandler` 里的 `handleAccessDenied`，重启，用 `product_user` 访问 `/api/admin/roles`。

预期：403 变成 500（被 `Exception.class` 兜底）。恢复处理器。

**实验 3：把两条 `SecurityFilterChain` 的顺序反过来**

把 `publicSecurityFilterChain` 的 `@Order(1)`、`adminSecurityFilterChain` 的 `@Order(2)` 对调，重启。

预期：`/api/admin/**` 全部被公共链放行，未登录也能访问。这就是"链顺序写错"的后果。改回来。

> 这三个实验比"一次跑通"更有价值。做完你就知道 403、500、401 分别是从哪一层出来的。

---

## 6. 本阶段坑

### 坑 1：`@PreAuthorize` 完全不生效

**症状**：所有登录用户都能访问，没有任何 403。

**原因**：

- `SecurityConfig` 上没加 `@EnableMethodSecurity`；
- 用了旧版的 `@EnableGlobalMethodSecurity(prePostEnabled = true)`，但版本/配置不对；
- 注解加在接口上，实现类没走代理；
- 同类方法自调用，绕过了代理。

**解决**：加 `@EnableMethodSecurity`；确认注解在 Controller/Service 的 public 方法上；自调用问题见坑 8。

### 坑 2：`hasRole` 和 `hasAuthority` 混用

**症状**：数据库里明明有 `PRODUCT_ADMIN` 角色，`hasRole('PRODUCT_ADMIN')` 还是 403。

**原因**：`hasRole('X')` 会自动加 `ROLE_` 前缀，检查的是 `ROLE_X`。我们的 authorities 是权限码，不是角色码。

**解决**：统一用 `hasAuthority('product:read')`；如果非要用 `hasRole`，在 `LoginUser` 里额外加入 `ROLE_` 前缀的角色 authority，并保证两边命名一致。

### 坑 3：403 变成了 500

**症状**：`@PreAuthorize` 拒绝后返回 `code=50000`。

**原因**：`AccessDeniedException` 被 `GlobalExceptionHandler` 的 `Exception.class` 兜底。

**解决**：加 `@ExceptionHandler(AccessDeniedException.class)`，返回 403。URL 级权限由 `AccessDeniedHandler` 处理，方法级权限由这个处理器处理，两边都要有。

### 坑 4：401 和 403 混用

**症状**：没登录返回 403；权限不足返回 401；前端登录后又被踢回登录页。

**原因**：`AuthenticationEntryPoint` 和 `AccessDeniedHandler` 配反了，或者都返回同一个码。

**解决**：

- 未认证 / token 无效 → `AuthenticationEntryPoint` → 401；
- 已认证但无权限 → `AccessDeniedHandler` 或 `AccessDeniedException` 处理器 → 403。

### 坑 5：两条 `SecurityFilterChain` 顺序错

**症状**：`/api/admin/**` 不需要登录就能访问；或 `/api/app/**` 被后台链拦截。

**原因**：`@Order` 写反，或 `securityMatcher` 写错。

**解决**：越具体的链 `@Order` 越小；`/api/admin/**` 在 `/**` 前面。第 04 章会变成三条链：`/api/admin/**` → `/api/app/**` → `/**`。

### 坑 6：`JwtAuthenticationFilter` 被执行两次

**症状**：日志里同一个请求打印两次认证信息；或过滤器里的数据库查询执行两次。

**原因**：`JwtAuthenticationFilter` 加了 `@Component`，被 Servlet 容器自动注册了一次，又被 `addFilterBefore` 加进 Security 链一次。

**解决**：不要加 `@Component`，在 `SecurityConfig` 里 `new`；或者加 `FilterRegistrationBean` 并 `setEnabled(false)`。

### 坑 7：循环依赖

**症状**：启动报 `BeanCurrentlyInCreationException` 或循环依赖。

**原因**：`SecurityConfig` 注入 `UserDetailsService`，`UserDetailsService` 又依赖 `PasswordEncoder`，而 `PasswordEncoder` 定义在 `SecurityConfig` 里。

**解决**：把 `PasswordEncoder` 放在独立的 `PasswordConfig`（第 01 章已经这么做了）；不要在 `SecurityConfig` 里定义它。

### 坑 8：`@PreAuthorize` 在同类自调用时失效

**症状**：Controller 调 Service 的 `create` 有权限检查，但 Service 内部另一个方法直接调 `create`，权限检查没生效。

**原因**：Spring AOP 基于代理，同类内部 `this.create()` 不经过代理。

**解决**：把需要鉴权的方法放到另一个 Bean；或通过 `AopContext.currentProxy()`（不推荐）；或把权限检查放在 Controller 层。

### 坑 9：权限加载太慢 / 每次请求查库

**症状**：每个请求多两次 SQL，QPS 高了数据库压力大。

**原因**：`JwtAuthenticationFilter` 每次都调 `loadUserById`，里面又查角色和权限。

**解决**：练习项目可接受。生产可以：
- 把用户权限缓存到 Redis，权限变更时删缓存；
- 或者把权限放进 token，但接受"权限变更后旧 token 仍生效"的延迟；
- 或者用本地缓存 + 短 TTL。

本季不做 Redis，第 03 章会讨论取舍。

### 坑 10：`UserDetailsService` 返回 null

**症状**：登录报 `InternalAuthenticationServiceException` 或空指针。

**原因**：`loadUserByUsername` 找不到用户时返回了 `null`，而不是抛 `UsernameNotFoundException`。

**解决**：必须抛 `UsernameNotFoundException`；`DaoAuthenticationProvider` 会把它隐藏成 `BadCredentialsException`。

### 坑 11：CSRF 没关，POST 全部 403

**症状**：GET 正常，POST/PUT/DELETE 返回 403。

**原因**：Spring Security 默认开启 CSRF，要求请求带 CSRF token。

**解决**：无状态 token 认证下关闭 CSRF：`.csrf(AbstractHttpConfigurer::disable)`。如果你用 Cookie + Session，则不能关，要正确配置 CSRF token。

### 坑 12：`/api/admin/auth/login` 被 `anyRequest().authenticated()` 拦住

**症状**：登录接口返回 401。

**原因**：`permitAll` 的路径写错，或没放在 `anyRequest()` 前面。

**解决**：`.requestMatchers("/api/admin/auth/login").permitAll().anyRequest().authenticated()`，顺序不能反。

### 坑 13：角色/权限改了，用户还是旧权限

**症状**：给角色加了权限，用户不重新登录也能用；或删了权限，用户还能用。

**原因**：
- 权限放在 JWT 里，token 没过期就不更新；
- 或者用了本地缓存，没失效。

**解决**：本项目每次请求从数据库加载权限，所以改完立即生效。代价是每次多两次查询。如果你把权限放进 token，要接受延迟，或者在权限变更时递增 `tokenVersion` 强制重新登录。

### 坑 14：种子 SQL 重复执行导致唯一索引冲突

**症状**：第二次执行种子 SQL 报 `Duplicate entry`。

**原因**：`sys_role.code`、`sys_permission.code` 有唯一索引；`sys_user_role`、`sys_role_permission` 有联合唯一索引。

**解决**：角色/权限用 `ON DUPLICATE KEY UPDATE`；关联表用 `INSERT IGNORE`。本章的 SQL 已经这么写了。

---

## 7. 完成标准

- [ ] `sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission` 四张表已建。
- [ ] 三个角色、11 个权限、三个测试账号及角色关联都已写入。
- [ ] 手写版：`@RequirePermission` + AOP 能正确返回 403。
- [ ] Spring Security 版：`@PreAuthorize` 能正确返回 403。
- [ ] `admin` 能访问商品、角色、订单 stub。
- [ ] `product_user` 能访问商品，访问角色返回 403 / `code=40301`。
- [ ] `order_user` 能访问订单 stub，访问商品返回 403。
- [ ] 不带 token 返回 401 / `code=40100`。
- [ ] 伪造 token 返回 401 / `code=40103`。
- [ ] 过期 token 返回 401 / `code=40104`。
- [ ] 禁用账号返回 401 / `code=40105`。
- [ ] 登出后旧 token 立即失效。
- [ ] 401 和 403 的响应体都是统一 `Result` JSON，不是 HTML 错误页。
- [ ] `AccessDeniedException` 不会变成 500。
- [ ] 手写版的拦截器、AOP、`UserContext` 已删除，没有两套认证同时运行。
- [ ] 你能解释：`hasRole` 与 `hasAuthority`、`AuthenticationEntryPoint` 与 `AccessDeniedHandler`、两条 `SecurityFilterChain` 的顺序、为什么权限不放 JWT。

---

## 8. 思考题

1. 为什么代码里判断权限码，而不是判断角色？如果新增一个"运营管理员"也要改商品，需要改代码吗？
2. `hasRole('PRODUCT_ADMIN')` 实际检查的 authority 是什么？为什么我们的项目会 403？
3. 401 和 403 的区别是什么？为什么不能都返回 401？
4. `AuthenticationEntryPoint` 和 `AccessDeniedHandler` 分别在什么时机被调用？方法级 `@PreAuthorize` 的异常为什么不走它们？
5. 为什么 `JwtAuthenticationFilter` 不能加 `@Component`？加了会怎样？
6. 如果把权限列表放进 JWT，管理员撤销权限后，用户还能用多久？本项目为什么选择每次请求查库？
7. 两条 `SecurityFilterChain` 的 `@Order` 写反会发生什么？请用 `/api/admin/products` 举例说明请求走向。
8. `@EnableMethodSecurity` 没加时，`@PreAuthorize` 会报错还是静默失效？怎么快速验证？
9. 为什么关联表 `sys_user_role` 不做逻辑删除？如果做了，会有什么问题？
10. 如果第 04 章要加 `/api/app/**` 的 C 端认证，`SecurityConfig` 应该怎么改？`@Order` 怎么排？

---

## 9. 复盘模板

```markdown
## 我做了什么
- RBAC 四张表 + 种子数据
- 手写 @RequirePermission + AOP
- Spring Security 认证 + 授权重写
- 401/403 统一 JSON

## 我踩过的坑
- （例如）@PreAuthorize 不生效，忘了 @EnableMethodSecurity
- （例如）403 变成 500，没处理 AccessDeniedException
- （例如）hasRole 和 hasAuthority 混用

## 我怎么验证
- 三个账号分别访问商品/角色/订单
- 无 token / 伪造 / 过期 / 禁用 / 登出
- 三个故意制造的错误实验

## 我还想优化
- 权限缓存到 Redis
- 角色/权限管理接口（第 03 章）
- 操作日志（第 03 章）
```

下一章：[03-后台账号管理与操作审计.md](03-后台账号管理与操作审计.md)（待生成）
