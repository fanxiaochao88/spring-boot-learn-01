# 第 01 章：账号体系与 JWT 登录

> 配套：[00-后续章节规划与上下文.md](00-后续章节规划与上下文.md)
> 预计用时：4～6 小时，建议拆成两次。
> 前置：原教程第 0～7 阶段已完成；`product` 商品接口能正常增删改查。
> 本章产出：`sys_user`、`sys_login_log`、后台登录/登出/查询当前用户、JWT 签发与校验、请求上下文、登录日志，以及现有商品接口的路径迁移。

---

## 0. 本章目标与前置检查

### 0.1 你会做出什么

做完本章，你会有一个真正"需要登录才能访问"的后台：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/admin/auth/login` | 用户名 + 密码登录，返回 JWT |
| POST | `/api/admin/auth/logout` | 登出，旧 token 立即失效 |
| GET | `/api/admin/auth/me` | 查询当前登录用户 |
| POST | `/api/admin/products` | 新增商品（需登录） |
| GET | `/api/admin/products/{id}` | 商品详情（需登录） |
| GET | `/api/admin/products` | 分页（需登录） |
| PUT | `/api/admin/products/{id}` | 修改（需登录） |
| DELETE | `/api/admin/products/{id}` | 删除（需登录） |
| POST | `/api/admin/products/{id}/stock/decrease` | 扣库存（需登录） |

同时你会亲手实现：

- BCrypt 密码加密与校验；
- JWT 的签发、解析、过期判断；
- `HandlerInterceptor` 做登录校验；
- `ThreadLocal` 保存当前请求的用户上下文；
- 登录成功/失败日志；
- 401 与 400 的区别。

> 本章只做**认证**（你是谁），不做**授权**（你能干什么）。角色和权限是第 02 章的事。这样拆分，是为了让你先看清"登录凭证"这条链路，再叠加权限。

### 0.2 前置检查

在项目根目录执行：

```bash
java -version
# 应该看到 17.x

mysql -uroot -p -e "USE mall_product; SHOW TABLES;"
# 应该能看到 product、product_stock_log
```

打开项目，确认以下文件存在：

```text
src/main/java/com/keywin/mall/MallProductApplication.java
src/main/java/com/keywin/mall/common/api/Result.java
src/main/java/com/keywin/mall/common/api/ResultCode.java
src/main/java/com/keywin/mall/common/exception/GlobalExceptionHandler.java
src/main/java/com/keywin/mall/modules/product/controller/ProductController.java
src/main/resources/application.yml
src/main/resources/application-dev.yml
```

再确认项目能启动，健康检查正常：

```bash
curl -i http://localhost:8089/actuator/health
```

> 顺带记录一个现有行为：`GlobalExceptionHandler` 对 `NoResourceFoundException` 和 `Exception` 没有加 `@ResponseStatus`，所以这两类实际返回 HTTP 200。本章不改它们，但认证异常会显式返回 401。如果你希望 404/500 也返回正确的 HTTP 状态，可以在这两个处理器上补 `@ResponseStatus`。

### 0.3 补齐 TraceIdFilter（如果还没有）

你的 `common/log/` 目前是空的，`logback-spring.xml` 里已经预留了 `%X{traceId}`。如果直接开始写认证，登录失败时日志没有链路 ID，排错会很痛苦。先花 10 分钟补上。

新建 `src/main/java/com/keywin/mall/common/log/TraceIdFilter.java`：

```java
package com.keywin.mall.common.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** 只接受安全字符，防止把换行等注入日志 */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[a-zA-Z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (!StringUtils.hasText(traceId) || !VALID_TRACE_ID.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程池会复用线程，必须清理，否则下一个请求会拿到旧 traceId
            MDC.remove(TRACE_ID);
        }
    }
}
```

顺手修一个已经埋下的坑：`logback-spring.xml` 里的 logger 包名还是教程里的 `com.example.mall`，你的项目是 `com.keywin.mall`。改成：

```xml
<logger name="com.keywin.mall" level="DEBUG"/>
```

验证：

```bash
curl -i -H "X-Trace-Id: chapter01-check" http://localhost:8089/actuator/health
```

响应头里应该有 `X-Trace-Id: chapter01-check`，日志里也应该能看到这个值。

### 0.4 三个学习约定

1. **先提交一次 git。** 本章会改路径、加拦截器，一旦写乱可以回滚。
   ```bash
   git add .
   git commit -m "chore: 开始第 01 章前的基线"
   ```
2. **每段代码自己敲，不要整段复制。** 尤其是 JWT 和拦截器，抄错一个 `Bearer ` 前缀就会卡半天。
3. **至少故意制造 3 个错误。** 本章末尾会给出清单。

---

## 1. 先想清楚：认证、会话与 JWT

### 1.1 三个问题

在写代码前，先回答三个问题：

1. **用户登录成功后，服务端怎么记住他？**
2. **下一次请求，服务端怎么知道"这是刚才那个人"？**
3. **用户登出、改密码、被禁用后，怎么让旧凭证立刻失效？**

第 1、2 个问题有两种主流答案：Session 和 JWT。第 3 个问题是 JWT 的难点，本章用 `tokenVersion` 解决。

### 1.2 Session vs JWT

| 维度 | Session | JWT |
|---|---|---|
| 状态存哪 | 服务端内存/Redis | 客户端保存，服务端不存 |
| 传输方式 | Cookie `JSESSIONID` | `Authorization: Bearer <token>` |
| 扩容 | 多实例要共享 Session（Redis） | 天然无状态，多实例直接可用 |
| 失效 | 服务端删 Session 即可 | 默认无法主动失效，要额外设计 |
| 适合 | 传统后台、同域 Web | 前后端分离、移动端、微服务/网关 |
| 风险 | CSRF | XSS 拿到 token、token 泄露、无法撤销 |

本项目的目标是为微服务做铺垫，选 **JWT**。但要记住：**JWT 不是"更安全"，它只是"更适合无状态调用"**。它的代价是撤销麻烦，所以后面要加 `tokenVersion`。

### 1.3 JWT 长什么样

JWT 是三段 Base64URL 字符串，用 `.` 分隔：

```text
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiJ9 . 4m5r...
      header                    payload                         signature
```

- **header**：算法和类型，例如 `{"alg":"HS256","typ":"JWT"}`。
- **payload**：claims，例如 `sub`（用户 ID）、`username`、`userType`、`tokenVersion`、`iat`、`exp`、`iss`。
- **signature**：用密钥对 `header.payload` 做 HMAC，防篡改。

三个必须记住的结论：

1. **payload 只是 Base64URL 编码，不是加密。** 任何人都能解开看内容。所以不要放密码、身份证、手机号。
2. **没有密钥无法伪造签名。** 改了 payload，签名就对不上。
3. **服务端不保存 token。** 校验靠"签名 + 过期时间 + 数据库里的 `tokenVersion`"。

### 1.4 本项目的设计

| 项 | 选择 | 原因 |
|---|---|---|
| 算法 | HS256 | 单体项目够用，配置简单 |
| 密钥 | `${JWT_SECRET}`，dev 给默认值 | 生产必须从环境变量注入 |
| 有效期 | 120 分钟 | 练习方便；生产可缩短 |
| 存放 | `Authorization: Bearer <token>` | 不放 URL，避免日志泄露 |
| claims | `sub`=userId、`username`、`userType`、`tokenVersion` | 权限不放 token，避免权限变更后旧 token 仍生效 |
| 撤销 | `sys_user.token_version + 1` | 登出/改密/禁用后旧 token 立即失效 |

---

## 2. 建表与种子数据

### 2.1 `sys_user`

登录 MySQL：

```bash
mysql -uroot -p
```

```sql
USE mall_product;

CREATE TABLE IF NOT EXISTS sys_user (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  username        VARCHAR(64)     NOT NULL                COMMENT '登录名',
  password_hash   VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 密文',
  nickname        VARCHAR(64)     DEFAULT NULL            COMMENT '昵称',
  phone           VARCHAR(20)     DEFAULT NULL            COMMENT '手机号',
  email           VARCHAR(128)    DEFAULT NULL            COMMENT '邮箱',
  status          TINYINT         NOT NULL DEFAULT 1      COMMENT '1启用 0禁用',
  token_version   INT             NOT NULL DEFAULT 0      COMMENT 'token版本，+1使旧token失效',
  last_login_time DATETIME        DEFAULT NULL            COMMENT '最后登录时间',
  last_login_ip   VARCHAR(64)     DEFAULT NULL            COMMENT '最后登录IP',
  version         INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  deleted         TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0否 1是',
  create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '后台用户';
```

### 2.2 `sys_login_log`

```sql
CREATE TABLE IF NOT EXISTS sys_login_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT UNSIGNED DEFAULT NULL            COMMENT '用户ID，未知用户为NULL',
  username    VARCHAR(64)     NOT NULL                COMMENT '登录名快照',
  ip          VARCHAR(64)     DEFAULT NULL            COMMENT '客户端IP',
  user_agent  VARCHAR(500)    DEFAULT NULL            COMMENT 'User-Agent',
  success     TINYINT         NOT NULL                COMMENT '1成功 0失败',
  fail_reason VARCHAR(100)    DEFAULT NULL            COMMENT '失败原因',
  login_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_username_time (username, login_time),
  KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录日志';
```

> 登录日志是**追加表**，不更新、不逻辑删除，所以没有 `version/deleted/update_time`。不是所有表都要套同一套字段，按语义来。

### 2.3 种子管理员

密码明文是 `admin123`，下面是 BCrypt 密文（strength=10，`$2a$` 前缀）：

```sql
INSERT INTO sys_user (username, password_hash, nickname, phone, status, token_version)
VALUES (
  'admin',
  '$2a$10$2e0uPVYasYyy8qIcgA1D5OYGrdvUTM.AuZxazF9q/J4v.IsE5UDkO',
  '超级管理员',
  '13800138000',
  1,
  0
);
```

> 这个密文是用 BCrypt 对 `admin123` 加盐后生成的。每次生成的结果都不一样，但都能校验通过。**不要把明文密码写进数据库，也不要把密文当成"加密后可逆"的东西**——BCrypt 是单向哈希，只能 `matches`，不能解密。

### 2.4 验证

```sql
SELECT id, username, nickname, status, token_version, deleted FROM sys_user;
```

应该看到一行 `admin`，`status=1`，`token_version=0`，`deleted=0`。

---

## 3. 依赖与配置

### 3.1 `pom.xml`

本章需要两组依赖：

- **BCrypt**：`spring-security-crypto`。它只提供密码编码器，不会引入 Spring Security 的过滤器链。这样第 01 章可以"手写认证"，第 02 章再上完整的 Spring Security。
- **JWT**：`jjwt` 0.12.5，三个 artifact。

在 `<properties>` 里加：

```xml
<jjwt.version>0.12.5</jjwt.version>
```

在 `<dependencies>` 里加：

```xml
<!-- 只用 BCrypt，不引入 Spring Security 过滤器链 -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>

<!-- JWT：0.12.x 的 API 与 0.9/0.11 不同，不要照抄老教程 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>${jjwt.version}</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>${jjwt.version}</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>${jjwt.version}</version>
    <scope>runtime</scope>
</dependency>
```

> `spring-security-crypto` 的版本由 Spring Boot 的依赖管理决定，不要自己写版本号。第 02 章引入 `spring-boot-starter-security` 后，这个依赖会被间接包含，到时可以删掉这一行。

刷新 Maven，确认没有报错。

### 3.2 `application.yml`

在现有 `mall:` 节点下追加：

```yaml
mall:
  product:
    page-size-limit: 100
    low-stock-threshold: 10
  security:
    jwt:
      # 生产环境必须用环境变量 JWT_SECRET 覆盖，不要提交真实密钥
      secret: ${JWT_SECRET:mall-product-dev-secret-key-please-change-32bytes}
      issuer: mall-product
      expire-minutes: 120
```

> 密钥长度必须 ≥ 32 字节（256 位），否则 HS256 会报 `WeakKeyException`。默认值只是本地练习用，生产必须换。

### 3.3 `JwtProperties`

新建 `src/main/java/com/keywin/mall/config/JwtProperties.java`：

```java
package com.keywin.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mall.security.jwt")
public class JwtProperties {

    /** HS256 密钥，至少 32 字节 */
    private String secret;

    /** 签发者，解析时会校验 */
    private String issuer = "mall-product";

    /** 过期时间（分钟） */
    private long expireMinutes = 120;
}
```

### 3.4 `PasswordConfig`

新建 `src/main/java/com/keywin/mall/config/PasswordConfig.java`：

```java
package com.keywin.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 默认 strength=10，本地练习够用；生产可根据机器性能调到 12
        return new BCryptPasswordEncoder();
    }
}
```

> 第 02 章引入 Spring Security 后，这个 Bean 仍然保留，`DaoAuthenticationProvider` 会复用它。**不要在两个地方各定义一个 `PasswordEncoder`**，否则 Spring 会报 Bean 冲突。

---

## 4. 实体、Mapper、DTO/VO

### 4.1 `SysUser`

新建 `src/main/java/com/keywin/mall/modules/system/entity/SysUser.java`：

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
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文，禁止明文 */
    private String passwordHash;

    private String nickname;

    private String phone;

    private String email;

    /** 1 启用，0 禁用 */
    private Integer status;

    /** token 版本，+1 可使该用户所有旧 token 失效 */
    private Integer tokenVersion;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

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

> 实体用 `@Getter/@Setter`，不用 `@Data`：避免 `equals/hashCode` 把 `passwordHash`、`tokenVersion`、`deleted` 都算进去。

### 4.2 `SysLoginLog`

新建 `src/main/java/com/keywin/mall/modules/system/entity/SysLoginLog.java`：

```java
package com.keywin.mall.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    private String ip;

    private String userAgent;

    /** 1 成功，0 失败 */
    private Integer success;

    private String failReason;

    private LocalDateTime loginTime;
}
```

### 4.3 Mapper

新建 `src/main/java/com/keywin/mall/modules/system/mapper/SysUserMapper.java`：

```java
package com.keywin.mall.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.mall.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

新建 `src/main/java/com/keywin/mall/modules/system/mapper/SysLoginLogMapper.java`：

```java
package com.keywin.mall.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.mall.modules.system.entity.SysLoginLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {
}
```

> 项目现在用 `@Mapper` 逐个标记，没有 `@MapperScan`。保持现状即可；如果 Mapper 多到十几个，再考虑在启动类加 `@MapperScan("com.keywin.mall.modules.**.mapper")`。

### 4.4 `AdminLoginDTO`

新建 `src/main/java/com/keywin/mall/modules/system/dto/AdminLoginDTO.java`：

```java
package com.keywin.mall.modules.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class AdminLoginDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名不能超过 64 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度必须在 6~64 之间")
    @ToString.Exclude
    private String password;
}
```

> `@ToString.Exclude` 不是可有可无的：`@Data` 会生成 `toString()`，如果有人 `log.info("dto={}", dto)`，密码就会原样进日志。加上这个注解，`toString` 里就不会出现密码。

### 4.5 `AdminLoginVO` 与 `AdminUserVO`

新建 `src/main/java/com/keywin/mall/modules/system/vo/AdminLoginVO.java`：

```java
package com.keywin.mall.modules.system.vo;

import lombok.Data;

@Data
public class AdminLoginVO {

    /** JWT */
    private String token;

    /** 固定 Bearer，方便前端拼接 */
    private String tokenType = "Bearer";

    /** 有效期（秒） */
    private long expiresIn;

    /** 当前用户信息 */
    private AdminUserVO user;
}
```

新建 `src/main/java/com/keywin/mall/modules/system/vo/AdminUserVO.java`：

```java
package com.keywin.mall.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String phone;

    private Integer status;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;
}
```

> VO 里**没有** `passwordHash`、`tokenVersion`、`version`、`deleted`。这不是"顺手少写几个字段"，而是安全边界：密码哈希和内部版本号永远不应该出现在响应里。

---

## 5. 密码：为什么必须用 BCrypt

### 5.1 三种错误做法

| 做法 | 问题 |
|---|---|
| 明文存 | 数据库一泄露，所有密码直接暴露 |
| MD5/SHA-256 | 太快，攻击者每秒能试几亿次；彩虹表可反查 |
| 固定盐 + SHA | 盐泄露后仍可批量破解；GPU 暴力破解依然快 |

BCrypt 的三个关键点：

1. **自带随机盐**：同一个密码每次哈希结果不同，彩虹表失效。
2. **故意慢**：`strength=10` 意味着 2^10 轮，单次校验约几十毫秒，暴力破解成本急剧上升。
3. **自适应**：机器变快后，可以调高 strength，旧密码仍然兼容。

### 5.2 校验方式

BCrypt 不能"解密"，只能：

```java
boolean matches = passwordEncoder.matches(rawPassword, storedHash);
```

`storedHash` 里已经包含算法版本、strength、盐和密文，所以不需要你单独存盐。

### 5.3 本章的登录校验顺序

1. 按用户名查用户。
2. 用户不存在 **或** 密码不匹配 → 统一返回"用户名或密码错误"（HTTP 401，code 40101）。
3. 密码正确但 `status=0` → 返回"账号已被禁用"（HTTP 401，code 40105）。
4. 都通过 → 更新最后登录时间、写成功日志、签发 token。

> 第 2 步把"用户不存在"和"密码错误"合并成同一个提示，是为了防止**用户名枚举**：攻击者不能通过错误信息判断某个用户名是否存在。

---

## 6. JWT 工具类

新建 `src/main/java/com/keywin/mall/common/security/JwtTokenProvider.java`：

```java
package com.keywin.mall.common.security;

import com.keywin.mall.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret 至少需要 32 字节（256 位）");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 签发 token。
     * 注意：不要往 claims 里放权限列表，权限变更后旧 token 会继续生效。
     */
    public String generate(Long userId, String username, String userType, Integer tokenVersion) {
        Instant now = Instant.now();
        Instant expiration = now.plus(jwtProperties.getExpireMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("userType", userType)
                .claim("tokenVersion", tokenVersion == null ? 0 : tokenVersion)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验签名、issuer、过期时间。
     * 过期会抛 ExpiredJwtException，签名错误会抛 JwtException。
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpireSeconds() {
        return jwtProperties.getExpireMinutes() * 60;
    }
}
```

### 6.1 这段代码的三个关键点

1. **`Keys.hmacShaKeyFor`**：把字符串密钥转成 `SecretKey`。密钥 < 32 字节直接抛异常，避免弱密钥。
2. **`signWith(secretKey)`**：jjwt 会根据密钥长度自动选择 HS256/HS384/HS512。32 字节对应 HS256。
3. **`verifyWith(...).requireIssuer(...)`**：解析时同时校验签名和 issuer。不要只 `parseClaimsJwt`（那是未签名 token 的解析，不能用于生产）。

### 6.2 坑：不要照抄 0.9 / 0.11 的教程

| 老写法（0.9/0.11） | 0.12.5 写法 |
|---|---|
| `Jwts.parser().setSigningKey(key)` | `Jwts.parser().verifyWith(secretKey)` |
| `Jwts.parserBuilder()` | `Jwts.parser()` 直接返回 builder |
| `signWith(SignatureAlgorithm.HS256, key)` | `signWith(secretKey)` |
| `setSubject` / `setExpiration` | `subject` / `expiration` |
| `parseClaimsJws(token).getBody()` | `parseSignedClaims(token).getPayload()` |

用错 API 的典型报错：`NoSuchMethodError`、`Cannot resolve method 'setSigningKey'`、`parseClaimsJws` 找不到。

---

## 7. 请求上下文：ThreadLocal

### 7.1 为什么需要它

登录校验通过后，Controller/Service 经常需要知道"当前用户是谁"：

- 查"我的订单"；
- 记录操作日志；
- 给商品设置创建人。

如果每个方法都加一个 `Long userId` 参数，会污染所有方法签名。`ThreadLocal` 可以让同一个请求线程在任何地方拿到当前用户。

### 7.2 `UserContext`

新建 `src/main/java/com/keywin/mall/common/security/UserContext.java`：

```java
package com.keywin.mall.common.security;

/**
 * 一次请求内的登录用户快照。
 * 第 02 章会在这里增加 roles / permissions。
 */
public record UserContext(
        Long userId,
        String username,
        String userType,
        Integer tokenVersion
) {
}
```

### 7.3 `UserContextHolder`

新建 `src/main/java/com/keywin/mall/common/security/UserContextHolder.java`：

```java
package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;

public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext context) {
        HOLDER.set(context);
    }

    public static UserContext get() {
        UserContext context = HOLDER.get();
        if (context == null) {
            throw new UnauthorizedException(ResultCode.TOKEN_MISSING);
        }
        return context;
    }

    public static UserContext getOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
```

### 7.4 为什么必须 `clear()`

Tomcat 用线程池处理请求。请求结束后线程不会销毁，而是还给池子。如果 `ThreadLocal` 没有清理：

- 下一个请求可能拿到上一个用户的 `UserContext`；
- 轻则数据串号，重则越权（A 用户看到 B 用户的数据）。

所以拦截器的 `afterCompletion` 里必须 `clear()`。这是 Java Web 最经典的坑之一。

> 第 02 章换成 Spring Security 后，`SecurityContextHolder` 内部也是 `ThreadLocal`，框架会在请求结束时清理。但如果你自己再存一份 `UserContext`，仍然要自己清。

---

## 8. 登录接口

### 8.1 异常与错误码

先补两个文件。

新建 `src/main/java/com/keywin/mall/common/exception/UnauthorizedException.java`：

```java
package com.keywin.mall.common.exception;

import com.keywin.mall.common.api.ResultCode;
import lombok.Getter;

@Getter
public class UnauthorizedException extends RuntimeException {

    private final int code;

    public UnauthorizedException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public UnauthorizedException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}
```

修改 `ResultCode`，在 `UNAUTHORIZED` 后面追加：

```java
LOGIN_FAILED(40101, "用户名或密码错误"),
TOKEN_MISSING(40102, "未携带登录凭证"),
TOKEN_INVALID(40103, "登录凭证无效或已失效"),
TOKEN_EXPIRED(40104, "登录已过期，请重新登录"),
ACCOUNT_DISABLED(40105, "账号已被禁用"),

// 系统/账号模块业务码
USER_NOT_FOUND(20001, "用户不存在"),
```

修改 `GlobalExceptionHandler`，加一个处理器：

```java
import com.keywin.mall.common.exception.UnauthorizedException;
import org.springframework.http.HttpStatus;

@ExceptionHandler(UnauthorizedException.class)
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public Result<Void> handleUnauthorized(UnauthorizedException e) {
    log.warn("认证失败: code={}, message={}", e.getCode(), e.getMessage());
    return Result.fail(e.getCode(), e.getMessage());
}
```

> **为什么认证异常不继承 `BusinessException`？**
> `BusinessException` 的处理器固定返回 HTTP 400。认证失败应该是 401，授权失败应该是 403。用独立的异常类，HTTP 状态码才不会被压成 400。
>
> 这里也顺便建立了全项目的约定：
> - 业务规则失败 → `BusinessException` → 400；
> - 未认证 → `UnauthorizedException` → 401；
> - 已认证但无权限 → `ForbiddenException` → 403（第 02 章加）。

### 8.2 `SysLoginLogService`

新建 `src/main/java/com/keywin/mall/modules/system/service/SysLoginLogService.java`：

```java
package com.keywin.mall.modules.system.service;

import com.keywin.mall.modules.system.entity.SysLoginLog;
import com.keywin.mall.modules.system.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysLoginLogService {

    private final SysLoginLogMapper sysLoginLogMapper;

    /**
     * 写登录日志。
     * 日志失败不能影响登录主流程，所以这里自己 try-catch。
     */
    public void save(Long userId, String username, String ip, String userAgent,
                     boolean success, String failReason) {
        try {
            SysLoginLog loginLog = new SysLoginLog();
            loginLog.setUserId(userId);
            loginLog.setUsername(username);
            loginLog.setIp(ip);
            loginLog.setUserAgent(truncate(userAgent, 500));
            loginLog.setSuccess(success ? 1 : 0);
            loginLog.setFailReason(failReason);
            loginLog.setLoginTime(LocalDateTime.now());
            sysLoginLogMapper.insert(loginLog);
        } catch (Exception e) {
            log.error("写入登录日志失败: username={}", username, e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
```

> 注意变量名用 `loginLog`，不要用 `log`。类上有 `@Slf4j` 生成的 `log` 字段，局部变量再叫 `log` 会遮蔽 Logger，后面的 `log.error` 就编译不过了。

### 8.3 `IpUtils`

新建 `src/main/java/com/keywin/mall/common/util/IpUtils.java`：

```java
package com.keywin.mall.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

public final class IpUtils {

    private IpUtils() {
    }

    /**
     * 获取客户端 IP。
     * 注意：X-Forwarded-For 可以被客户端伪造，只有在可信代理（Nginx/Gateway）后面才应该信任。
     */
    public static String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !"unknown".equalsIgnoreCase(forwarded)) {
            return forwarded.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}
```

### 8.4 `AuthService`

新建 `src/main/java/com/keywin/mall/modules/system/service/AuthService.java`：

```java
package com.keywin.mall.modules.system.service;

import com.keywin.mall.modules.system.dto.AdminLoginDTO;
import com.keywin.mall.modules.system.vo.AdminLoginVO;
import com.keywin.mall.modules.system.vo.AdminUserVO;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {

    AdminLoginVO login(AdminLoginDTO dto, HttpServletRequest request);

    void logout();

    AdminUserVO me();
}
```

### 8.5 `AuthServiceImpl`

新建 `src/main/java/com/keywin/mall/modules/system/service/impl/AuthServiceImpl.java`：

```java
package com.keywin.mall.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;
import com.keywin.mall.common.security.JwtTokenProvider;
import com.keywin.mall.common.security.UserContext;
import com.keywin.mall.common.security.UserContextHolder;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysLoginLogService sysLoginLogService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public AdminLoginVO login(AdminLoginDTO dto, HttpServletRequest request) {
        String ip = IpUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, dto.getUsername()));

        // 用户不存在或密码错误，统一提示，防止用户名枚举
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            sysLoginLogService.save(
                    user == null ? null : user.getId(),
                    dto.getUsername(), ip, userAgent, false, "用户名或密码错误");
            throw new UnauthorizedException(ResultCode.LOGIN_FAILED);
        }

        // 密码正确后才提示禁用，避免暴露账号是否存在
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            sysLoginLogService.save(user.getId(), user.getUsername(), ip, userAgent, false, "账号已禁用");
            throw new UnauthorizedException(ResultCode.ACCOUNT_DISABLED);
        }

        // 更新最后登录信息。
        // 这里不用 updateById：SysUser 有 @Version，并发登录时可能因为版本冲突更新 0 行。
        // 最后登录时间不是关键业务，用 UpdateWrapper 直接覆盖即可。
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getLastLoginTime, LocalDateTime.now())
                .set(SysUser::getLastLoginIp, ip));

        String token = jwtTokenProvider.generate(
                user.getId(), user.getUsername(), "ADMIN", user.getTokenVersion());

        sysLoginLogService.save(user.getId(), user.getUsername(), ip, userAgent, true, null);
        log.info("管理员登录成功: userId={}, username={}, ip={}", user.getId(), user.getUsername(), ip);

        AdminLoginVO vo = new AdminLoginVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresIn(jwtTokenProvider.getExpireSeconds());
        vo.setUser(toUserVO(user));
        return vo;
    }

    @Override
    public void logout() {
        UserContext context = UserContextHolder.get();

        // token_version + 1：该用户所有旧 token 立即失效
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, context.userId())
                .setSql("token_version = token_version + 1"));

        log.info("管理员退出登录: userId={}", context.userId());
    }

    @Override
    public AdminUserVO me() {
        UserContext context = UserContextHolder.get();
        SysUser user = sysUserMapper.selectById(context.userId());
        if (user == null) {
            throw new UnauthorizedException(ResultCode.USER_NOT_FOUND);
        }
        return toUserVO(user);
    }

    private AdminUserVO toUserVO(SysUser user) {
        AdminUserVO vo = new AdminUserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
```

### 8.6 `AdminAuthController`

新建 `src/main/java/com/keywin/mall/modules/system/controller/AdminAuthController.java`：

```java
package com.keywin.mall.modules.system.controller;

import com.keywin.mall.common.api.Result;
import com.keywin.mall.modules.system.dto.AdminLoginDTO;
import com.keywin.mall.modules.system.service.AuthService;
import com.keywin.mall.modules.system.vo.AdminLoginVO;
import com.keywin.mall.modules.system.vo.AdminUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody AdminLoginDTO dto,
                                      HttpServletRequest request) {
        return Result.success(authService.login(dto, request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<AdminUserVO> me() {
        return Result.success(authService.me());
    }
}
```

---

## 9. 拦截器：把"没登录"挡在 Controller 之前

### 9.1 `JwtAuthInterceptor`

新建 `src/main/java/com/keywin/mall/common/security/JwtAuthInterceptor.java`：

```java
package com.keywin.mall.common.security;

import com.keywin.mall.common.api.ResultCode;
import com.keywin.mall.common.exception.UnauthorizedException;
import com.keywin.mall.modules.system.entity.SysUser;
import com.keywin.mall.modules.system.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserMapper sysUserMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 浏览器跨域预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            throw new UnauthorizedException(ResultCode.TOKEN_MISSING);
        }

        String token = header.substring(7).trim();

        Claims claims;
        try {
            claims = jwtTokenProvider.parse(token);
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new UnauthorizedException(ResultCode.ACCOUNT_DISABLED);
        }

        Object versionClaim = claims.get("tokenVersion");
        int tokenVersion = versionClaim instanceof Number number ? number.intValue() : -1;
        if (!Objects.equals(Integer.valueOf(tokenVersion), user.getTokenVersion())) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        // 第 02 章之后这里还会增加权限加载；第 04 章会区分 ADMIN / APP
        if (!"ADMIN".equals(claims.get("userType", String.class))) {
            throw new UnauthorizedException(ResultCode.TOKEN_INVALID);
        }

        UserContextHolder.set(new UserContext(
                user.getId(), user.getUsername(), "ADMIN", user.getTokenVersion()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理，否则线程池复用线程时会串用户
        UserContextHolder.clear();
    }
}
```

### 9.2 `WebMvcConfig`

新建 `src/main/java/com/keywin/mall/config/WebMvcConfig.java`：

```java
package com.keywin.mall.config;

import com.keywin.mall.common.security.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/auth/login");
    }
}
```

### 9.3 为什么登录接口要排除

登录时用户还没有 token，如果不排除，`/api/admin/auth/login` 会被拦截器要求带 token，形成死循环。

需要排除的路径：

- `/api/admin/auth/login`：登录；
- 以后如果有注册、验证码、找回密码，也要排除；
- `/actuator/**`、`/api/lab/**` 不在 `/api/admin/**` 下，天然不会被拦截。

### 9.4 路径迁移：`/api/product` → `/api/admin/products`

打开 `ProductController`，把：

```java
@RequestMapping("api/product")
```

改成：

```java
@RequestMapping("/api/admin/products")
```

其他方法不用动。改完后，所有商品接口都必须带 token。

> **为什么不保留旧路径做兼容？**
> 因为旧路径不在 `/api/admin/**` 下，拦截器不会保护它。保留它等于留了一个"免登录后门"。安全边界比兼容性重要，旧路径直接删除。你现有的 Vue 验收台会因此失效，这是第二季不做前端的已知代价。

### 9.5 拦截器抛出的异常会被谁处理

`HandlerInterceptor.preHandle` 里抛出的异常，会由 `DispatcherServlet` 交给 `HandlerExceptionResolver`，最终走到 `@RestControllerAdvice`。所以 `UnauthorizedException` 会被 `GlobalExceptionHandler` 捕获，返回 HTTP 401 + `Result` JSON。

如果你发现拦截器抛异常后返回的是 500，检查：

1. `GlobalExceptionHandler` 是否在 `com.keywin.mall` 子包下；
2. `UnauthorizedException` 的处理器是否加了 `@ResponseStatus(HttpStatus.UNAUTHORIZED)`；
3. 是否又写了 `response.getWriter().write(...)`，导致响应已经提交。

---

## 10. 验证

### 10.1 启动

IDEA 里运行 `MallProductApplication`，或项目根目录：

```bash
mvn spring-boot:run
```

看到 `Started MallProductApplication` 后继续。

### 10.2 登录成功

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期：

- HTTP 200；
- `code=0`；
- `data.token` 是三段以 `.` 分隔的字符串；
- `data.user.username=admin`；
- `data.user` 里没有 `passwordHash`、`tokenVersion`、`deleted`。

响应示例：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwi...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "id": 1,
      "username": "admin",
      "nickname": "超级管理员",
      "phone": "13800138000",
      "status": 1,
      "lastLoginTime": null,
      "lastLoginIp": null
    }
  },
  "timestamp": "2026-11-12T18:30:00"
}
```

把 token 存到变量里。Windows Git Bash 没有 `jq` 也能用 Python：

```bash
TOKEN=$(curl -s -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

echo "$TOKEN"
```

### 10.3 带 token 访问

```bash
# 当前用户
curl -i http://localhost:8089/api/admin/auth/me \
  -H "Authorization: Bearer $TOKEN"

# 商品分页
curl -i "http://localhost:8089/api/admin/products?pageNum=1&pageSize=2" \
  -H "Authorization: Bearer $TOKEN"

# 商品详情
curl -i http://localhost:8089/api/admin/products/1 \
  -H "Authorization: Bearer $TOKEN"
```

预期都是 HTTP 200 + `code=0`。

### 10.4 异常路径

**1）不带 token**

```bash
curl -i http://localhost:8089/api/admin/products
```

预期：HTTP 401，`code=40102`，`message=未携带登录凭证`。

**2）token 格式错误**

```bash
curl -i http://localhost:8089/api/admin/products \
  -H "Authorization: Bearer abc.def.ghi"
```

预期：HTTP 401，`code=40103`。

**3）密码错误**

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong-password"}'
```

预期：HTTP 401，`code=40101`，`message=用户名或密码错误`。

**4）用户不存在**

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"nobody","password":"whatever"}'
```

预期：和密码错误完全一样的 401 / 40101，不暴露用户是否存在。

**5）过期 token**

把 `application.yml` 里的 `expire-minutes` 临时改成 `1`，重启，重新登录，等待 61 秒：

```bash
sleep 61
curl -i http://localhost:8089/api/admin/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

预期：HTTP 401，`code=40104`，`message=登录已过期，请重新登录`。

测试完把 `expire-minutes` 改回 `120`，重启。

**6）禁用账号**

```sql
UPDATE sys_user SET status = 0 WHERE username = 'admin';
```

用新密码登录：

```bash
curl -i -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期：HTTP 401，`code=40105`，`message=账号已被禁用`。

恢复：

```sql
UPDATE sys_user SET status = 1 WHERE username = 'admin';
```

### 10.5 登出：旧 token 立即失效

```bash
# 1. 登录拿 token
TOKEN=$(curl -s -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 2. 确认 token 可用
curl -i http://localhost:8089/api/admin/auth/me -H "Authorization: Bearer $TOKEN"

# 3. 登出
curl -i -X POST http://localhost:8089/api/admin/auth/logout -H "Authorization: Bearer $TOKEN"

# 4. 再用旧 token
curl -i http://localhost:8089/api/admin/auth/me -H "Authorization: Bearer $TOKEN"
```

预期：第 2 步 200；第 3 步 200；第 4 步 401，`code=40103`。

数据库验证：

```sql
SELECT username, token_version FROM sys_user WHERE username = 'admin';
```

`token_version` 应该从 0 变成 1。再登录一次，新 token 的 `tokenVersion` 是 1，可以正常使用。

### 10.6 登录日志

```sql
SELECT id, user_id, username, ip, success, fail_reason, login_time
FROM sys_login_log
ORDER BY id DESC
LIMIT 10;
```

你应该能看到刚才的成功和失败记录。检查：

- 失败记录 `success=0`，`fail_reason` 是"用户名或密码错误"或"账号已禁用"；
- 没有任何一行包含明文密码或完整 token。

### 10.7 traceId

```bash
curl -i -H "X-Trace-Id: login-demo-001" \
  -X POST http://localhost:8089/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期：

- 响应头有 `X-Trace-Id: login-demo-001`；
- 控制台和 `logs/mall-product.log` 的登录日志行里都有 `[login-demo-001]`。

### 10.8 解析 token（看清 payload）

不要用 jwt.io 粘贴真实生产 token。本地可以这样看：

```bash
python -c "
import base64, json, sys
p = sys.argv[1].split('.')[1]
p += '=' * (-len(p) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(p)), ensure_ascii=False, indent=2))
" "$TOKEN"
```

预期能看到：

```json
{
  "sub": "1",
  "username": "admin",
  "userType": "ADMIN",
  "tokenVersion": 1,
  "iss": "mall-product",
  "iat": 1762933800,
  "exp": 1762941000
}
```

注意：payload 里没有密码，也没有权限列表。这是故意的。

---

## 11. 本阶段坑

### 坑 1：JWT 依赖版本与 API 对不上

**症状**：`NoSuchMethodError`、`Cannot resolve method 'setSigningKey'`、`parseClaimsJws` 找不到。

**原因**：照抄了 jjwt 0.9 或 0.11 的教程，但依赖是 0.12.5。

**解决**：按本章 6.2 的对照表改；不要混用 `jjwt`、`java-jwt`、`nimbus-jose-jwt` 三套 API。

### 坑 2：`WeakKeyException: The specified key byte array is 0 bits`

**原因**：密钥为空或少于 32 字节。

**解决**：检查 `mall.security.jwt.secret` 是否被正确读取；环境变量 `JWT_SECRET` 是否为空；默认值是否被误删。

### 坑 3：401 变成了 500

**原因**：

- `UnauthorizedException` 没有处理器，被 `Exception.class` 兜底成 500；
- 处理器没有 `@ResponseStatus(HttpStatus.UNAUTHORIZED)`，HTTP 状态仍是 200；
- 拦截器里既 `throw` 又 `response.getWriter().write(...)`，响应重复提交。

**解决**：保留本章的 `GlobalExceptionHandler` 写法，只抛异常，不手动写响应。

### 坑 4：`Authorization` 头少了 `Bearer `

**症状**：明明登录成功，访问接口还是 40102。

**原因**：`header.startsWith("Bearer ")` 对大小写和空格敏感。

**解决**：用 `Bearer <token>`，`Bearer` 后必须有一个空格；不要用 `bearer`、`BEARER` 或 `Bearer:`。

### 坑 5：ThreadLocal 串用户

**症状**：A 用户偶尔看到 B 用户的数据；或登出后仍能拿到旧上下文。

**原因**：`afterCompletion` 没 `clear()`；或用了异步线程，`ThreadLocal` 没有传递。

**解决**：`afterCompletion` 必须 `clear()`；异步任务不要直接依赖 `UserContextHolder`，要把用户信息作为参数传进去（第 03 章操作日志会讲）。

### 坑 6：登录接口被拦截器拦住

**症状**：登录接口返回 40102。

**原因**：`excludePathPatterns` 没写，或路径写错（例如写成 `/api/admin/auth/login/`，多了斜杠）。

**解决**：确认 `WebMvcConfig` 里排除了 `/api/admin/auth/login`。

### 坑 7：`sys_user` 的 `password_hash` 被返回给前端

**症状**：登录响应或用户详情里出现 `passwordHash`。

**原因**：直接返回了 `SysUser` 实体，或 VO 里加了同名字段。

**解决**：只返回 `AdminUserVO`；VO 不包含 `passwordHash`；不要用 `BeanUtils.copyProperties` 把实体直接拷进一个"什么字段都有"的 VO。

### 坑 8：把密码写进日志

**症状**：日志里出现 `password=admin123`。

**原因**：`log.info("login dto={}", dto)`；或 DTO 的 `toString()` 包含密码。

**解决**：DTO 密码字段加 `@ToString.Exclude`；日志只打 `username`、`userId`、`ip`，不打整个 DTO。

### 坑 9：`updateById` 更新最后登录时间失败

**症状**：登录成功，但 `last_login_time` 没变；或并发登录时偶发更新 0 行。

**原因**：`SysUser` 有 `@Version`，`updateById` 会带上旧版本号；并发时版本冲突。

**解决**：最后登录时间不是关键业务，用 `LambdaUpdateWrapper` 直接 `set`，不参与乐观锁；关键业务更新才用 `updateById`。

### 坑 10：旧路径还能免登录访问

**症状**：`/api/product` 仍然能增删改查。

**原因**：只加了新路径，没有删旧路径；或 `@RequestMapping` 同时写了两个路径。

**解决**：`ProductController` 只保留 `/api/admin/products`；全局搜索 `api/product`，清理旧引用。

### 坑 11：`logback-spring.xml` 的包名还是 `com.example.mall`

**症状**：`com.keywin.mall` 的 DEBUG 日志不输出。

**原因**：logger 配置的是教程里的包名。

**解决**：改成 `<logger name="com.keywin.mall" level="DEBUG"/>`。

### 坑 12：token 过期时间测试后忘了改回来

**症状**：所有 token 1 分钟就过期，接口频繁 401。

**解决**：测试完把 `expire-minutes` 改回 `120`，重启；或者用单独的 `application-local.yml` 做短过期测试。

---

## 12. 完成标准

- [ ] `mall_product` 库里有 `sys_user`、`sys_login_log` 两张表。
- [ ] `sys_user` 有一条 `admin` 种子数据，`password_hash` 是 BCrypt 密文。
- [ ] 登录成功返回三段式 JWT，响应里没有 `passwordHash`、`tokenVersion`、`deleted`。
- [ ] 带 token 能访问 `/api/admin/auth/me` 和 `/api/admin/products`。
- [ ] 不带 token 返回 HTTP 401 / `code=40102`。
- [ ] 伪造 token 返回 HTTP 401 / `code=40103`。
- [ ] 过期 token 返回 HTTP 401 / `code=40104`。
- [ ] 密码错误和用户不存在都返回 401 / `code=40101`，提示一致。
- [ ] 禁用账号返回 401 / `code=40105`。
- [ ] 登出后旧 token 立即失效（401 / 40103），`token_version` 增加 1。
- [ ] `sys_login_log` 能记录成功/失败、IP、UA、失败原因。
- [ ] 日志和响应头里有 traceId。
- [ ] `/api/product` 旧路径已删除，商品接口只能通过 `/api/admin/products` 访问。
- [ ] 你能用自己的话解释：JWT 三段是什么、为什么 payload 不能放密码、为什么需要 `tokenVersion`、401 和 403 的区别。

---

## 13. 思考题

1. 如果不用 `tokenVersion`，用户登出后旧 token 还能用多久？有什么办法让它立即失效？各自代价是什么？
2. 为什么 JWT 的 payload 不能放密码、身份证、完整手机号？Base64URL 和加密有什么区别？
3. 为什么登录失败时，"用户不存在"和"密码错误"要返回同一个提示？这会带来什么用户体验上的取舍？
4. 如果把权限列表放进 JWT，管理员撤销某个权限后，用户还能继续用旧权限多久？怎么解决？
5. `HandlerInterceptor` 和 `Filter` 都能做登录校验，为什么本章用拦截器？第 02 章为什么又换成 Filter？
6. `ThreadLocal` 在异步线程里为什么会丢？如果第 03 章要用 `@Async` 写操作日志，应该怎么把当前用户传过去？
7. 为什么 `tokenVersion` 用 `UPDATE ... SET token_version = token_version + 1`，而不是先查出来再 `+1`？
8. 把 `expire-minutes` 设为 1 分钟，登录后立刻连续发 10 个请求，观察哪些成功、哪些失败，解释原因。

---

## 14. 复盘模板

做完本章，花 10 分钟写下：

```markdown
## 我做了什么
- sys_user / sys_login_log
- BCrypt、JWT、拦截器、ThreadLocal、登录日志
- 商品接口迁移

## 我踩过的坑
- （例如）jjwt 用了 0.11 的 API，报 NoSuchMethodError
- （例如）忘记清 ThreadLocal，第二个请求拿到了上一个用户

## 我怎么验证
- 登录成功 / 无 token / 伪造 token / 过期 token / 禁用 / 登出
- 登录日志 SQL
- token payload 解析

## 我还想优化
- 登录失败次数限制
- 刷新 token
- 把 token 存到 Redis 做黑名单
```

下一章：[02-RBAC权限与SpringSecurity重写.md](02-RBAC权限与SpringSecurity重写.md)
