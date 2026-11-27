# Spring Security 用户/角色/权限系列教程 —— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本计划产出的是**文档**，不是可运行工程。每个 Task 的“验证”是结构检查 + 与全系列契约比对；教程内部会给用户可执行的 curl / Maven 验证命令。

**Goal:** 在 `docs/spring/security-series/` 下写出 14 章增量式教程，带用户从空工程搭出 `security-lab` 的用户/角色/权限模块，并完整使用 Spring Boot 3 + Spring Security 6 + JWT。

**Architecture:** 每章只增加一个能力，先给完整代码跑通，再逐行解释。全系列共享一套固定契约（包名、表名、接口路径、权限编码、响应格式），避免章节间互相矛盾。

**Tech Stack:** Spring Boot 3.2.4、Java 17、Spring Security 6.2.x、MySQL 8、MyBatis-Plus 3.5.5、jjwt 0.12.6、Lombok、Maven。

---

## 全系列契约（所有章节必须遵守）

### 工程坐标

| 项 | 值 |
| --- | --- |
| groupId | `com.keywin` |
| artifactId | `security-lab` |
| 主类 | `com.keywin.security.SecurityLabApplication` |
| 端口 | `8080` |
| 数据库 | `security_lab` |
| MySQL 账号 | `root` / `${MYSQL_PASSWORD:123456}` |
| jwt 配置前缀 | `security.jwt` |

### 固定账号与密码

| 用户名 | 密码 | BCrypt 哈希（已验证可匹配） | 角色 |
| --- | --- | --- | --- |
| `admin` | `admin123` | `$2a$10$G4w4ZjRmRERBL.oPFRmj0Od4kYQE/hrcxCMFl8DP2dMJRr8fG1N3i` | `SUPER_ADMIN` |
| `user_admin` | `user123` | `$2a$10$A78MHIfWmqMxHiVp3CRbuOSWq.KcGRqnL.aLTVOXXSwXhBpkxajk.` | `USER_ADMIN` |
| `viewer` | `viewer123` | `$2a$10$SdDaE8MyqoPSBTKN96MlU.X.NQIpxqEQvW1mU/GdTnSl1hvfwD2SO` | `VIEWER` |

### 权限编码（12 个）

`user:list`、`user:add`、`user:update`、`user:delete`
`role:list`、`role:add`、`role:update`、`role:delete`
`permission:list`、`permission:add`、`permission:update`、`permission:delete`

角色与权限分配：

| 角色 | 权限 |
| --- | --- |
| `SUPER_ADMIN` | 全部 12 个 |
| `USER_ADMIN` | `user:list`、`user:add`、`user:update`、`role:list`、`permission:list` |
| `VIEWER` | `user:list`、`role:list`、`permission:list` |

### 权限字符串约定

- 角色编码 `SUPER_ADMIN` 放入 authorities 时加前缀：`ROLE_SUPER_ADMIN`
- 权限编码原样放入 authorities：`user:list`
- 代码中：`hasRole("SUPER_ADMIN")`、`hasAuthority("user:list")`

### 接口契约

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/ping` | 健康检查 | 公开 |
| POST | `/api/auth/login` | 登录 | 公开 |
| POST | `/api/auth/logout` | 退出登录 | 已登录 |
| GET | `/api/auth/me` | 当前用户 | 已登录 |
| GET | `/api/users` | 用户分页 | `user:list` |
| GET | `/api/users/{id}` | 用户详情 | `user:list` |
| POST | `/api/users` | 新增用户 | `user:add` |
| PUT | `/api/users/{id}` | 修改用户 | `user:update` |
| DELETE | `/api/users/{id}` | 删除用户 | `user:delete` |
| GET | `/api/users/{id}/roles` | 查询用户角色 ID | `user:list` |
| PUT | `/api/users/{id}/roles` | 分配角色 | `user:update` |
| GET | `/api/roles` | 角色列表 | `role:list` |
| POST | `/api/roles` | 新增角色 | `role:add` |
| PUT | `/api/roles/{id}` | 修改角色 | `role:update` |
| DELETE | `/api/roles/{id}` | 删除角色 | `role:delete` |
| GET | `/api/roles/{id}/permissions` | 查询角色权限 ID | `role:list` |
| PUT | `/api/roles/{id}/permissions` | 分配权限 | `role:update` |
| GET | `/api/permissions` | 权限列表 | `permission:list` |
| POST | `/api/permissions` | 新增权限 | `permission:add` |
| PUT | `/api/permissions/{id}` | 修改权限 | `permission:update` |
| DELETE | `/api/permissions/{id}` | 删除权限 | `permission:delete` |
| GET | `/api/demo/public` | 公开示例 | 公开 |
| GET | `/api/demo/me` | 当前用户示例 | 已登录 |
| GET | `/api/admin/demo` | 管理员示例 | `SUPER_ADMIN` |

### 统一响应

```json
{ "code": 200, "message": "success", "data": {} }
```

- 成功：HTTP 200，`code=200`
- 参数错误：HTTP 400，`code=400`
- 未登录/Token 无效：HTTP 401，`code=401`
- 已登录但无权限：HTTP 403，`code=403`
- 资源不存在：HTTP 404，`code=404`
- 业务冲突（用户名/角色编码重复）：HTTP 409，`code=409`
- 服务器异常：HTTP 500，`code=500`

### JWT 约定

- Header：`Authorization: Bearer <token>`
- Claims：`sub = userId`、`username`、`tokenVersion`、`iss = security-lab`、`iat`、`exp`
- 有效期：120 分钟
- **JWT 不放角色/权限**；每次请求从数据库加载
- 退出登录：`token_version + 1`，旧 Token 因版本不匹配失效

### 包结构（最终形态）

```text
com.keywin.security
├── SecurityLabApplication.java
├── common
│   ├── api/{Result, ResultCode, PageResult}.java
│   └── exception/{BusinessException, GlobalExceptionHandler}.java
├── config
│   ├── MybatisPlusConfig.java
│   ├── MyMetaObjectHandler.java
│   ├── PasswordConfig.java
│   └── SecurityConfig.java
├── security
│   ├── LoginUser.java
│   ├── UserDetailsServiceImpl.java
│   ├── JwtProperties.java
│   ├── JwtService.java
│   ├── JwtAuthenticationFilter.java
│   ├── RestAuthenticationEntryPoint.java
│   └── RestAccessDeniedHandler.java
└── modules
    ├── auth/{controller, dto, service, service/impl, vo}
    ├── user/{controller, dto, entity, mapper, service, service/impl, vo}
    ├── role/{controller, dto, entity, mapper, service, service/impl, vo}
    ├── permission/{controller, dto, entity, mapper, service, service/impl, vo}
    ├── log/{entity, mapper, service, service/impl}
    └── demo/{DemoController, AdminDemoController}.java
```

### 文档写作硬性要求

- 每个 Java 文件都给**完整代码**：`package`、全部 `import`、类体，不写 `...`
- 每个 SQL / yml / pom 片段都可直接复制执行
- 每章末尾必须有：验证命令、预期输出、本章常见报错
- 不出现 `TODO`、`TBD`、`待补充`、`略`、`同上`
- 中文正文，代码/类名/路径用英文
- 代码块语言标注准确：`java`、`sql`、`yaml`、`xml`、`bash`、`json`、`http`

---

## Task 1：系列索引 README

**Files:**
- Create: `docs/spring/security-series/README.md`

- [ ] **Step 1：写 README**

必须包含：

1. 标题与一句话定位：从零搭 `security-lab`，学会 Spring Security 6 + JWT
2. 适合谁：会 Spring Boot 基础、写过手写登录、准备重构的人
3. 学完能得到什么（能力清单）：
   - 用户/角色/权限 CRUD
   - JWT 登录、每请求认证
   - URL 级 + 方法级授权
   - 401/403 统一 JSON
   - 退出登录 Token 失效
   - 登录日志
   - MockMvc 安全测试
4. 章节表：00~13，每章一行，列“章名 / 目标 / 预计时间”，链接到具体文件
5. 技术版本表：Spring Boot 3.2.4、Java 17、Spring Security 6.2.x、MySQL 8、MyBatis-Plus 3.5.5、jjwt 0.12.6
6. 三个固定账号表（用户名/密码/角色/能做什么）
7. 学习方式：按顺序、每章先跑通再解释、验证不通过不要往下走
8. 配套文件说明：`sql/`、`http/`
9. 与旧教程的区别：不先讲原理、每章完整代码、只讲现代 API

- [ ] **Step 2：结构自检**

Run:
```bash
cd docs/spring/security-series
grep -n "TODO\|TBD\|待补充" README.md
grep -c '^```' README.md
```
Expected：第一条无输出；第二条为偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/README.md
git commit -m "docs(security-series): 添加系列索引 README"
```

---

## Task 2：SQL 与 HTTP 配套文件

**Files:**
- Create: `docs/spring/security-series/sql/01-schema.sql`
- Create: `docs/spring/security-series/sql/02-data.sql`
- Create: `docs/spring/security-series/http/api.http`

- [ ] **Step 1：写 `01-schema.sql`**

要求：

- `CREATE DATABASE IF NOT EXISTS security_lab DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
- `USE security_lab;`
- 按外键依赖倒序 `DROP TABLE IF EXISTS`
- 六张表：`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`sys_login_log`
- 字段与契约一致：
  - `sys_user`：`id BIGINT AUTO_INCREMENT PK`、`username VARCHAR(50) NOT NULL UNIQUE`、`password_hash VARCHAR(100) NOT NULL`、`nickname VARCHAR(50)`、`phone VARCHAR(20)`、`email VARCHAR(100)`、`status TINYINT NOT NULL DEFAULT 1`、`token_version INT NOT NULL DEFAULT 0`、`last_login_time DATETIME`、`last_login_ip VARCHAR(64)`、`create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`、`update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`
  - `sys_role`：`id`、`code VARCHAR(50) NOT NULL UNIQUE`、`name VARCHAR(50) NOT NULL`、`description VARCHAR(255)`、`status TINYINT NOT NULL DEFAULT 1`、时间字段
  - `sys_permission`：`id`、`code VARCHAR(100) NOT NULL UNIQUE`、`name VARCHAR(50) NOT NULL`、`module VARCHAR(50)`、`description VARCHAR(255)`、`status`、时间字段
  - `sys_user_role`：`user_id`、`role_id`，联合主键，双外键 `ON DELETE CASCADE`
  - `sys_role_permission`：`role_id`、`permission_id`，联合主键，双外键 `ON DELETE CASCADE`
  - `sys_login_log`：`id`、`user_id BIGINT NULL`、`username VARCHAR(50)`、`ip VARCHAR(64)`、`user_agent VARCHAR(255)`、`success TINYINT NOT NULL`、`message VARCHAR(255)`、`create_time`
- 每个字段加 `COMMENT`，每张表加 `COMMENT`
- 为 `sys_user.username`、`sys_role.code`、`sys_permission.code` 建唯一索引；为 `sys_login_log.user_id`、`sys_login_log.create_time` 建普通索引

- [ ] **Step 2：写 `02-data.sql`**

要求：

- `USE security_lab;`
- 插入 3 个用户，密码哈希**逐字使用契约表中的值**
- 插入 3 个角色：`SUPER_ADMIN`、`USER_ADMIN`、`VIEWER`
- 插入 12 个权限，`module` 分别为 `user`、`role`、`permission`
- 插入 `sys_user_role`：admin→1、user_admin→2、viewer→3
- 插入 `sys_role_permission`：
  - 角色 1 → 12 个权限全部
  - 角色 2 → `user:list`、`user:add`、`user:update`、`role:list`、`permission:list`
  - 角色 3 → `user:list`、`role:list`、`permission:list`
- 文件头注释说明：哈希仅用于本地学习，生产环境必须重新生成

- [ ] **Step 3：写 `http/api.http`**

要求：

- 顶部变量 `@host = http://localhost:8080`
- 登录请求：admin / user_admin / viewer 三组，使用 IDEA HTTP Client 语法自动保存 token：
  ```http
  # @name loginAdmin
  POST {{host}}/api/auth/login
  Content-Type: application/json

  { "username": "admin", "password": "admin123" }

  > {% client.global.set("adminToken", response.body.data.token); %}
  ```
- 后续请求使用 `{{adminToken}}`、`{{userAdminToken}}`、`{{viewerToken}}`
- 覆盖：ping、login、me、logout、用户 CRUD、分配角色、角色 CRUD、分配权限、权限 CRUD、demo public/me/admin
- 每个请求上方用 `### 说明` 写清预期结果
- 文件头注释说明：VS Code REST Client 用户需手动复制 token 到变量

- [ ] **Step 4：验证 SQL 与契约一致**

Run:
```bash
cd docs/spring/security-series
grep -c "INSERT INTO sys_permission" sql/02-data.sql
grep -n "user:delete\|role:delete\|permission:delete" sql/02-data.sql
grep -n "G4w4ZjRmRERBL\|A78MHIfWmqMxHiVp3CRbuOSWq\|SdDaE8MyqoPSBTKN96MlU" sql/02-data.sql
```
Expected：权限插入语句可读；三个哈希各出现一次；删除类权限存在。

- [ ] **Step 5：提交**

```bash
git add docs/spring/security-series/sql docs/spring/security-series/http
git commit -m "docs(security-series): 添加建表、测试数据与 HTTP 验收文件"
```

---

## Task 3：第 00 章 导读与环境准备

**Files:**
- Create: `docs/spring/security-series/00-导读与环境准备.md`

- [ ] **Step 1：写章节**

必须包含以下小节：

1. **这套教程怎么用**
   - 每章结构：目标 → 前置检查 → 先跑通 → 再解释 → 验证 → 小结
   - 三条规则：按顺序、代码自己敲、验证不过不往下走
2. **你会做出什么**
   - 最终系统的能力表（登录、CRUD、RBAC、401/403、退出失效、日志、测试）
   - 三个账号的能力边界
3. **学习地图**
   - 14 章分四个阶段：
     - 阶段一（01~02）：工程与 Security 初体验
     - 阶段二（03~04）：把用户/角色/权限数据管起来
     - 阶段三（05~08）：认证 + JWT + 授权
     - 阶段四（09~13）：方法级权限、分配、退出、测试、复盘
4. **环境清单与检查命令**
   - JDK 17：`java -version`
   - Maven 3.9+：`mvn -v`
   - MySQL 8：`mysql --version`
   - IDEA / VS Code
   - curl 或 HTTP Client
   - 每条命令给预期输出样式
5. **数据库准备**
   - 执行 `sql/01-schema.sql`、`sql/02-data.sql` 的两种方式：命令行、IDEA Database
   - 验证：`SELECT username FROM sys_user;` 返回三行
6. **本系列的约定**
   - 包名、端口、响应格式、权限编码规则（简表，指向 README）
7. **遇到问题怎么排查**
   - 看控制台第一条异常
   - 打开 `logging.level.org.springframework.security: DEBUG`
   - 用 `/api/ping` 判断应用是否启动
8. **预计时间**
   - 每章 30~60 分钟，总计约 10~14 小时

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "TODO\|TBD\|待补充" 00-导读与环境准备.md
grep -n "01-schema.sql\|02-data.sql" 00-导读与环境准备.md
grep -c '^```' 00-导读与环境准备.md
```
Expected：无占位符；两个 SQL 文件被引用；代码围栏数为偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/00-导读与环境准备.md
git commit -m "docs(security-series): 第 00 章 导读与环境准备"
```

---

## Task 4：第 01 章 工程搭建与数据库

**Files:**
- Create: `docs/spring/security-series/01-工程搭建与数据库.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：启动 `security-lab`，`GET /api/ping` 返回 `{"status":"ok"}`
2. **前置检查**：JDK/Maven/MySQL 可用；已执行两个 SQL
3. **创建工程**
   - Spring Initializr 选项：Maven、Java 17、Spring Boot 3.2.4
   - 依赖勾选：Spring Web、Validation、MySQL Driver、Lombok
   - 手动补充 MyBatis-Plus 依赖的理由（Initializr 没有）
4. **完整 `pom.xml`**
   - 包含 `spring-boot-starter-web`、`spring-boot-starter-validation`、`mybatis-plus-spring-boot3-starter:3.5.5`、`mysql-connector-j`、`lombok`、`spring-boot-starter-test`
   - 明确此章**不引入** `spring-boot-starter-security`
5. **`application.yml` 完整内容**
   - 端口 8080、数据源、MyBatis-Plus 驼峰与 SQL 日志
6. **主类 `SecurityLabApplication` 完整代码**
7. **`PingController` 完整代码**
   - `@RestController`、`@GetMapping("/api/ping")`、返回 `Map.of("status","ok")`
8. **目录树**：展示本章结束时的完整结构
9. **启动与验证**
   - `mvn spring-boot:run`
   - `curl http://localhost:8080/api/ping`
   - 预期 `{"status":"ok"}`
10. **数据库连接验证**
    - 启动日志出现 HikariPool 启动成功
    - 用 `mysql` 或 IDEA 查询 `sys_user` 三行
11. **常见报错**
    - 端口占用
    - Access denied for user root
    - Unknown database security_lab
    - MyBatis-Plus 启动报 `Invalid value type for attribute 'factoryBeanObjectType'`（版本不匹配）
12. **小结 + 自测题**（3 题）

- [ ] **Step 2：代码完整性自检**

Run:
```bash
cd docs/spring/security-series
grep -n "package com.keywin.security" 01-工程搭建与数据库.md
grep -n "spring-boot-starter-security" 01-工程搭建与数据库.md
grep -c '^```' 01-工程搭建与数据库.md
```
Expected：主类包名存在；`spring-boot-starter-security` 只出现在“本章不引入”的说明中；代码围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/01-工程搭建与数据库.md
git commit -m "docs(security-series): 第 01 章 工程搭建与数据库"
```

---

## Task 5：第 02 章 Security 初体验与过滤器链

**Files:**
- Create: `docs/spring/security-series/02-Security初体验与过滤器链.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：只加一个依赖，观察并解释“所有接口 401 + 控制台随机密码”
2. **前置检查**：第 01 章 `/api/ping` 可访问
3. **修改 `pom.xml`**
   - 加入 `spring-boot-starter-security`
   - 说明版本由 Spring Boot 父 POM 管理，不写版本号
4. **重启后的三个现象**
   - `/api/ping` 返回 401
   - 响应头有 `WWW-Authenticate: Basic realm="Realm"`
   - 控制台 `Using generated security password: xxxxxxxx`
5. **用默认用户验证**
   - `curl -u user:随机密码 http://localhost:8080/api/ping`
   - 预期 200，证明“不是接口坏了，是 Security 在拦”
6. **过滤器链详解**
   - ASCII 图：`DelegatingFilterProxy` → `FilterChainProxy` → `SecurityFilterChain` → Controller
   - 列出默认链里的关键过滤器及作用：`SecurityContextHolderFilter`、`UsernamePasswordAuthenticationFilter`、`ExceptionTranslationFilter`、`AuthorizationFilter`
   - 解释“过滤器顺序执行、可继续/可中断/可抛异常”
7. **为什么默认是表单登录 + Basic**
   - `spring-boot-starter-security` 自动配置
   - 没有自定义 `SecurityFilterChain` 时使用默认链
8. **本章不写配置的原因**
   - 先看见默认行为，第 03 章才接管
9. **验证清单**
   - 401 状态码
   - `WWW-Authenticate` 头
   - 默认密码登录成功
10. **常见误解**
    - “401 是接口报错” → 不是
    - “随机密码是 bug” → 不是
    - “加了 Security 就要马上写配置” → 不必须
11. **小结 + 自测题**（4 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "DelegatingFilterProxy\|FilterChainProxy\|SecurityFilterChain\|ExceptionTranslationFilter\|AuthorizationFilter" 02-Security初体验与过滤器链.md
grep -n "spring-boot-starter-security" 02-Security初体验与过滤器链.md
grep -c '^```' 02-Security初体验与过滤器链.md
```
Expected：关键类名齐全；依赖出现；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/02-Security初体验与过滤器链.md
git commit -m "docs(security-series): 第 02 章 Security 初体验与过滤器链"
```

---

## Task 6：第 03 章 统一响应与用户模块 CRUD

**Files:**
- Create: `docs/spring/security-series/03-统一响应与用户模块CRUD.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：用户分页/详情/新增/修改/删除可用；错误响应统一 JSON
2. **前置检查**：第 02 章完成
3. **本章的临时措施**
   - 写一个 `SecurityConfig`，`anyRequest().permitAll()`，并明确标注“第 07 章收回”
   - 解释：现在先让 CRUD 能调通，Security 配置只做最小占位
4. **完整代码文件**（每个都给完整代码）
   - `common/api/Result.java`（record，含 `success` / `error` 静态方法）
   - `common/api/ResultCode.java`（枚举：SUCCESS 200、BAD_REQUEST 400、UNAUTHORIZED 401、FORBIDDEN 403、NOT_FOUND 404、CONFLICT 409、INTERNAL_ERROR 500）
   - `common/api/PageResult.java`（record：total、page、size、records）
   - `common/exception/BusinessException.java`
   - `common/exception/GlobalExceptionHandler.java`
     - `BusinessException` → 对应 HTTP 状态
     - `MethodArgumentNotValidException` → 400 + 第一个字段错误
     - `Exception` → 500
   - `config/MybatisPlusConfig.java`（分页拦截器）
   - `config/MyMetaObjectHandler.java`（createTime/updateTime 自动填充）
   - `config/SecurityConfig.java`（临时 permitAll）
   - `modules/user/entity/SysUser.java`
   - `modules/user/mapper/SysUserMapper.java`
   - `modules/user/dto/UserCreateDTO.java`
   - `modules/user/dto/UserUpdateDTO.java`
   - `modules/user/vo/UserVO.java`
   - `modules/user/service/UserService.java`
   - `modules/user/service/impl/UserServiceImpl.java`
   - `modules/user/controller/UserController.java`
5. **关键实现说明**
   - 用户名唯一性检查，重复抛 409
   - 密码用 `new BCryptPasswordEncoder().encode(...)`，并注明“第 05 章改为注入 PasswordEncoder Bean”
   - 更新时密码为空则不修改
   - 分页使用 MyBatis-Plus `Page` + `LambdaQueryWrapper`
   - `UserVO` 绝不返回 `passwordHash`
6. **验证**
   - `curl -X POST .../api/users` 新增
   - `curl .../api/users?page=1&size=10` 分页
   - `curl -X PUT .../api/users/4` 修改
   - `curl -X DELETE .../api/users/4` 删除
   - 重复用户名 → 409 JSON
   - 参数校验失败 → 400 JSON
   - 每步给预期输出
7. **常见报错**
   - 400：DTO 校验失败，看 `message`
   - 409：用户名重复
   - 500：`user` 表字段与实体不一致
8. **小结 + 自测题**（3 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class UserController\|class UserServiceImpl\|class GlobalExceptionHandler\|class SecurityConfig" 03-统一响应与用户模块CRUD.md
grep -n "passwordHash" 03-统一响应与用户模块CRUD.md
grep -c '^```' 03-统一响应与用户模块CRUD.md
```
Expected：四个类都有完整代码；`passwordHash` 出现且上下文说明不返回；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/03-统一响应与用户模块CRUD.md
git commit -m "docs(security-series): 第 03 章 统一响应与用户模块 CRUD"
```

---

## Task 7：第 04 章 角色与权限模块 CRUD

**Files:**
- Create: `docs/spring/security-series/04-角色与权限模块CRUD.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：角色、权限的增删改查可用；关联表已就绪但本章不做分配
2. **前置检查**：第 03 章用户 CRUD 可用
3. **RBAC 模型图**
   - `sys_user ──< sys_user_role >── sys_role ──< sys_role_permission >── sys_permission`
   - 解释“用户不直接挂权限，权限挂在角色上”
4. **完整代码文件**
   - `modules/role/entity/SysRole.java`
   - `modules/role/entity/SysUserRole.java`（仅作为表映射说明，可不使用 BaseMapper）
   - `modules/role/mapper/SysRoleMapper.java`
   - `modules/role/mapper/SysUserRoleMapper.java`
     - `@Select` 查询角色 ID
     - `@Delete` 按用户删除
     - `@Insert` 单条插入
   - `modules/role/dto/RoleCreateDTO.java`
   - `modules/role/dto/RoleUpdateDTO.java`
   - `modules/role/vo/RoleVO.java`
   - `modules/role/service/RoleService.java`
   - `modules/role/service/impl/RoleServiceImpl.java`
   - `modules/role/controller/RoleController.java`
   - `modules/permission/entity/SysPermission.java`
   - `modules/permission/entity/SysRolePermission.java`
   - `modules/permission/mapper/SysPermissionMapper.java`
   - `modules/permission/mapper/SysRolePermissionMapper.java`
   - `modules/permission/dto/PermissionCreateDTO.java`
   - `modules/permission/dto/PermissionUpdateDTO.java`
   - `modules/permission/vo/PermissionVO.java`
   - `modules/permission/service/PermissionService.java`
   - `modules/permission/service/impl/PermissionServiceImpl.java`
   - `modules/permission/controller/PermissionController.java`
5. **关键实现说明**
   - 角色 `code`、权限 `code` 唯一，重复抛 409
   - 删除角色/权限依赖数据库外键 `ON DELETE CASCADE` 清理关联表
   - 列表按 `id` 升序
6. **验证**
   - 创建角色 `TEST_ROLE`
   - 创建权限 `test:demo`
   - 修改、删除
   - 重复 code → 409
   - 每步给预期输出
7. **常见报错**
   - 外键约束失败：先删关联数据或确认 CASCADE
   - code 重复：409
8. **小结 + 自测题**（3 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class RoleController\|class PermissionController\|class RoleServiceImpl\|class PermissionServiceImpl" 04-角色与权限模块CRUD.md
grep -n "sys_user_role\|sys_role_permission" 04-角色与权限模块CRUD.md
grep -c '^```' 04-角色与权限模块CRUD.md
```
Expected：四个类完整；两张关联表被解释；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/04-角色与权限模块CRUD.md
git commit -m "docs(security-series): 第 04 章 角色与权限模块 CRUD"
```

---

## Task 8：第 05 章 PasswordEncoder 与 AuthenticationManager 登录

**Files:**
- Create: `docs/spring/security-series/05-PasswordEncoder与登录认证.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：`POST /api/auth/login` 用 `AuthenticationManager` 校验 admin/admin123，成功返回用户信息（本章还没有 JWT）
2. **前置检查**：第 04 章 CRUD 可用；数据库三个账号存在
3. **为什么不能自己 `equals` 密码**
   - 明文存储风险
   - Security 认证链强制要求 `PasswordEncoder`
4. **完整代码文件**
   - `config/PasswordConfig.java`（`BCryptPasswordEncoder` Bean）
   - 修改 `UserServiceImpl`：注入 `PasswordEncoder`，替换第 03 章的 `new BCryptPasswordEncoder()`
   - `security/LoginUser.java`：`implements UserDetails`
     - 字段：`userId`、`username`、`password`、`nickname`、`status`、`tokenVersion`、`roles`、`permissions`、`authorities`
     - `getAuthorities()` 返回角色 `ROLE_` 前缀 + 权限原样
     - `isEnabled()` 返回 `status == 1`
     - 其余 `isAccountNonExpired` 等返回 true
     - 提供 `getUserId()`、`getNickname()`、`getTokenVersion()`、`getRoles()`、`getPermissions()`
   - `modules/role/mapper/SysRoleMapper.java` 增加：
     ```java
     @Select("""
         SELECT r.code FROM sys_role r
         JOIN sys_user_role ur ON ur.role_id = r.id
         WHERE ur.user_id = #{userId} AND r.status = 1
         """)
     List<String> selectRoleCodesByUserId(Long userId);
     ```
   - `modules/permission/mapper/SysPermissionMapper.java` 增加：
     ```java
     @Select("""
         SELECT DISTINCT p.code FROM sys_permission p
         JOIN sys_role_permission rp ON rp.permission_id = p.id
         JOIN sys_user_role ur ON ur.role_id = rp.role_id
         WHERE ur.user_id = #{userId} AND p.status = 1
         """)
     List<String> selectCodesByUserId(Long userId);
     ```
   - `security/UserDetailsServiceImpl.java`
     - 按用户名查 `SysUser`
     - 查不到抛 `UsernameNotFoundException("用户名或密码错误")`
     - 查角色 code、权限 code
     - 构造 `LoginUser`
   - `modules/auth/dto/AuthLoginDTO.java`
   - `modules/auth/vo/AuthLoginVO.java`（本章字段：`user`，其中包含 id/username/nickname/roles/permissions；第 06 章再加 token 字段）
   - `modules/auth/service/AuthService.java`
   - `modules/auth/service/impl/AuthServiceImpl.java`
     - 调用 `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password))`
     - `principal` 强转 `LoginUser`
     - 组装 `AuthLoginVO`
   - `modules/auth/controller/AuthController.java`
   - `config/SecurityConfig.java` 增加 `AuthenticationManager` Bean
   - `common/exception/GlobalExceptionHandler.java` 增加：
     - `AuthenticationException` → 401 “用户名或密码错误”
     - `DisabledException` → 403 “账号已被禁用”
     - `LockedException` → 403 “账号已被锁定”
5. **认证流程详解**
   - Controller → `AuthenticationManager` → `DaoAuthenticationProvider` → `UserDetailsService` + `PasswordEncoder` → `Authentication`
   - 解释 `UsernamePasswordAuthenticationToken` 的两种用途：登录入参、认证结果
   - 解释为什么 `UsernameNotFoundException` 最终表现为 `BadCredentialsException`
6. **验证**
   - 正确密码 → 200 + 用户信息
   - 错误密码 → 401 JSON
   - 不存在用户 → 401 JSON（与错误密码一致，防枚举）
   - 禁用账号（手动把 status 改 0）→ 403
   - 每步给预期输出
7. **常见报错**
   - `There is no PasswordEncoder mapped for the id "null"` → 没配 Bean
   - `Bad credentials` → 数据库密码不是 BCrypt 或哈希不匹配
   - `authenticationManager` 注入失败 → 没暴露 Bean
8. **小结 + 自测题**（4 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class LoginUser\|class UserDetailsServiceImpl\|class AuthController\|class AuthServiceImpl" 05-PasswordEncoder与登录认证.md
grep -n "selectRoleCodesByUserId\|selectCodesByUserId" 05-PasswordEncoder与登录认证.md
grep -n "ROLE_" 05-PasswordEncoder与登录认证.md
grep -c '^```' 05-PasswordEncoder与登录认证.md
```
Expected：四个类完整；两个查询方法存在；`ROLE_` 前缀说明存在；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/05-PasswordEncoder与登录认证.md
git commit -m "docs(security-series): 第 05 章 PasswordEncoder 与登录认证"
```

---

## Task 9：第 06 章 JWT 生成与解析

**Files:**
- Create: `docs/spring/security-series/06-JWT生成与解析.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：登录成功返回真实 JWT；能用命令解析出头和载荷
2. **前置检查**：第 05 章登录成功；`jjwt 0.12.6` 三件套已加入
3. **修改 `pom.xml`**
   - `jjwt-api`、`jjwt-impl`（runtime）、`jjwt-jackson`（runtime），版本 `0.12.6`
4. **JWT 结构讲解**
   - `Header.Payload.Signature`
   - 载荷是 Base64 可读，不放密码
   - 签名防篡改，不加密
5. **`application.yml` 增加**
   ```yaml
   security:
     jwt:
       secret: ${JWT_SECRET:security-lab-dev-secret-key-please-change-32bytes}
       issuer: security-lab
       expiration-minutes: 120
   ```
6. **完整代码文件**
   - `security/JwtProperties.java`
     - `@Component` + `@ConfigurationProperties(prefix = "security.jwt")`
     - `secret`、`issuer`、`expirationMinutes`
   - `security/JwtService.java`
     - `@PostConstruct` 校验 secret >= 32 字节
     - `generateToken(LoginUser)`：`subject(userId)`、`claim("username")`、`claim("tokenVersion")`、`issuer`、`issuedAt`、`expiration`、`signWith(key)`
     - `parse(String)`：`Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).getPayload()`
     - `extractUserId`、`extractUsername`、`extractTokenVersion`
     - `getExpirationSeconds()`
   - 修改 `modules/auth/vo/AuthLoginVO.java`：增加 `token`、`tokenType`、`expiresIn`
   - 修改 `AuthServiceImpl`：认证成功后 `jwtService.generateToken(loginUser)`
7. **jjwt 0.12.x 与旧版对比**
   - 旧：`setSubject`、`parserBuilder`、`setSigningKey`
   - 新：`subject`、`parser().verifyWith`、`parseSignedClaims`
   - 明确不要混用 0.11.x 教程
8. **安全说明**
   - secret 至少 32 字节
   - 生产放环境变量
   - Token 有效期、无状态取舍
9. **验证**
   - 登录拿 token
   - 用 `cut -d. -f2` + `base64 -d` 解码载荷（给出兼容 Git Bash 的命令和预期字段）
   - 修改 payload 最后一位再请求，后续章节会 401；本章只解释签名
10. **常见报错**
    - `WeakKeyException` → secret 太短
    - `SignatureException` → 密钥不一致或 token 被改
    - `ExpiredJwtException` → 过期
    - 编译报 `setSubject` 找不到 → 抄了旧 API
11. **小结 + 自测题**（4 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class JwtService\|class JwtProperties" 06-JWT生成与解析.md
grep -n "parseSignedClaims\|verifyWith" 06-JWT生成与解析.md
grep -n "jjwt-api\|jjwt-impl\|jjwt-jackson" 06-JWT生成与解析.md
grep -c '^```' 06-JWT生成与解析.md
```
Expected：两个类完整；新 API 存在；三个依赖齐全；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/06-JWT生成与解析.md
git commit -m "docs(security-series): 第 06 章 JWT 生成与解析"
```

---

## Task 10：第 07 章 JwtAuthenticationFilter 与 401

**Files:**
- Create: `docs/spring/security-series/07-JwtAuthenticationFilter与401.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：受保护接口必须带 Token；不带/带错返回 401 JSON；带对返回 200
2. **前置检查**：第 06 章能拿到 token
3. **完整代码文件**
   - `security/JwtAuthenticationFilter.java`
     - `extends OncePerRequestFilter`
     - 读 `Authorization`，必须以 `Bearer ` 开头，否则放行
     - `try` 解析 token，取 `userId`、`username`、`tokenVersion`
     - 当前无认证时才 `loadUserByUsername`
     - 校验 `loginUser.getUserId()` 与 token 的 userId 一致
     - 校验 `loginUser.getTokenVersion()` 与 token 的 tokenVersion 一致（为第 11 章退出登录铺路）
     - 构造 `UsernamePasswordAuthenticationToken(loginUser, null, authorities)`
     - `setDetails(new WebAuthenticationDetailsSource().buildDetails(request))`
     - `SecurityContextHolder.getContext().setAuthentication(...)`
     - 捕获 `JwtException | IllegalArgumentException`：记录 debug 日志、`clearContext()`、继续放行
     - 所有分支最后都 `filterChain.doFilter`
   - `security/RestAuthenticationEntryPoint.java`
     - 设置 401、`application/json;charset=UTF-8`
     - 用 `ObjectMapper` 写 `Result.error(401, "未登录或登录已过期，请重新登录")`
   - 修改 `config/SecurityConfig.java`
     - `csrf(AbstractHttpConfigurer::disable)`
     - `sessionManagement(STATELESS)`
     - `authorizeHttpRequests`: `/api/auth/login`、`/api/ping` permitAll，其余 authenticated
     - `exceptionHandling(authenticationEntryPoint)`
     - `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
     - 删除第 03 章的 `anyRequest().permitAll()`
   - 修改 `AuthService` / `AuthController`：增加 `GET /api/auth/me`，用 `@AuthenticationPrincipal LoginUser` 返回当前用户
4. **逐行解释**
   - 为什么继承 `OncePerRequestFilter`
   - 为什么异常要吞掉并继续放行，而不是直接 401
   - `SecurityContextHolder` 是 ThreadLocal，请求结束即清空
   - 为什么用 `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`
   - 为什么先判断 `getAuthentication() == null`
5. **验证**
   - 不带 token 访问 `/api/auth/me` → 401 JSON
   - 带正确 token → 200 + 用户信息
   - token 改一个字符 → 401 JSON
   - `/api/ping` 仍可匿名访问
   - 每步给预期输出
6. **常见报错**
   - 一直 401：过滤器没放行 / token 没带 `Bearer `
   - 一直 403：见第 08 章
   - 500：过滤器抛异常没捕获
   - 认证不生效：忘了 `setAuthentication`
7. **小结 + 自测题**（4 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class JwtAuthenticationFilter\|class RestAuthenticationEntryPoint" 07-JwtAuthenticationFilter与401.md
grep -n "OncePerRequestFilter\|SecurityContextHolder\|addFilterBefore" 07-JwtAuthenticationFilter与401.md
grep -n "anyRequest().permitAll" 07-JwtAuthenticationFilter与401.md
grep -c '^```' 07-JwtAuthenticationFilter与401.md
```
Expected：两个类完整；关键 API 存在；`anyRequest().permitAll` 只出现在“删除”说明里；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/07-JwtAuthenticationFilter与401.md
git commit -m "docs(security-series): 第 07 章 JWT 过滤器与 401"
```

---

## Task 11：第 08 章 URL 级授权与 403

**Files:**
- Create: `docs/spring/security-series/08-URL级授权与403.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：三个账号访问同一批接口，得到明确的 200/403 矩阵
2. **前置检查**：第 07 章 token 认证可用
3. **401 与 403 的区别**
   - 表格：含义、触发场景、处理组件
   - 401 `AuthenticationEntryPoint`；403 `AccessDeniedHandler`
4. **完整代码文件**
   - `security/RestAccessDeniedHandler.java`
     - 403 + JSON `Result.error(403, "权限不足，禁止访问")`
   - `modules/demo/DemoController.java`
     - `GET /api/demo/public` 返回 `"public ok"`
     - `GET /api/demo/me` 返回当前用户名
   - `modules/demo/AdminDemoController.java`
     - `GET /api/admin/demo` 返回 `"admin ok"`
   - 修改 `SecurityConfig`
     - 增加 `accessDeniedHandler`
     - URL 规则按顺序：
       ```java
       .requestMatchers("/api/auth/login", "/api/ping", "/api/demo/public").permitAll()
       .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
       .requestMatchers(HttpMethod.GET, "/api/users/**").hasAuthority("user:list")
       .requestMatchers(HttpMethod.POST, "/api/users/**").hasAuthority("user:add")
       .requestMatchers(HttpMethod.PUT, "/api/users/**").hasAuthority("user:update")
       .requestMatchers(HttpMethod.DELETE, "/api/users/**").hasAuthority("user:delete")
       .requestMatchers(HttpMethod.GET, "/api/roles/**").hasAuthority("role:list")
       .requestMatchers(HttpMethod.POST, "/api/roles/**").hasAuthority("role:add")
       .requestMatchers(HttpMethod.PUT, "/api/roles/**").hasAuthority("role:update")
       .requestMatchers(HttpMethod.DELETE, "/api/roles/**").hasAuthority("role:delete")
       .requestMatchers(HttpMethod.GET, "/api/permissions/**").hasAuthority("permission:list")
       .requestMatchers(HttpMethod.POST, "/api/permissions/**").hasAuthority("permission:add")
       .requestMatchers(HttpMethod.PUT, "/api/permissions/**").hasAuthority("permission:update")
       .requestMatchers(HttpMethod.DELETE, "/api/permissions/**").hasAuthority("permission:delete")
       .anyRequest().authenticated()
       ```
5. **`hasRole` vs `hasAuthority` 详解**
   - `hasRole("SUPER_ADMIN")` → `ROLE_SUPER_ADMIN`
   - `hasAuthority("ROLE_SUPER_ADMIN")` → `ROLE_SUPER_ADMIN`
   - `hasAuthority("user:list")` → `user:list`
   - 角色/权限在 `LoginUser` 里如何组装
6. **验证矩阵**
   - 三个账号分别调用：
     - `GET /api/users`
     - `POST /api/users`
     - `DELETE /api/users/999`
     - `GET /api/admin/demo`
   - 预期：admin 全 200/404；user_admin GET/POST 200、DELETE 403、admin 403；viewer GET 200、POST/DELETE/admin 403
   - 不带 token：全部 401
7. **为什么匿名用户有时 403**
   - `AnonymousAuthenticationFilter` 与 `ExceptionTranslationFilter` 的转交
8. **常见报错**
   - 有角色仍 403：前缀重复或权限未加载
   - 公开接口仍 401：过滤器抛异常
   - 403 返回 HTML：没配 `AccessDeniedHandler`
9. **小结 + 自测题**（4 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class RestAccessDeniedHandler\|class DemoController\|class AdminDemoController" 08-URL级授权与403.md
grep -n "hasRole(\"SUPER_ADMIN\")\|hasAuthority(\"user:list\")" 08-URL级授权与403.md
grep -n "401\|403" 08-URL级授权与403.md | head
grep -c '^```' 08-URL级授权与403.md
```
Expected：三个类完整；两种授权写法都存在；401/403 对比存在；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/08-URL级授权与403.md
git commit -m "docs(security-series): 第 08 章 URL 级授权与 403"
```

---

## Task 12：第 09 章 方法级授权

**Files:**
- Create: `docs/spring/security-series/09-方法级授权.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：用 `@PreAuthorize` 把权限校验写在方法上，并用 `@AuthenticationPrincipal` 拿当前用户
2. **前置检查**：第 08 章 URL 规则生效
3. **为什么需要方法级**
   - URL 规则按路径，复杂条件表达不了
   - 方法级离业务更近，适合按钮级权限
4. **开启方法级安全**
   - `SecurityConfig` 加 `@EnableMethodSecurity`
   - 解释 `prePostEnabled` 默认 true
5. **完整代码修改**
   - `UserController` 每个方法加：
     - 查询：`@PreAuthorize("hasAuthority('user:list')")`
     - 新增：`@PreAuthorize("hasAuthority('user:add')")`
     - 修改：`@PreAuthorize("hasAuthority('user:update')")`
     - 删除：`@PreAuthorize("hasAuthority('user:delete')")`
   - `RoleController`、`PermissionController` 同理
   - `DemoController.me` 改为：
     ```java
     @GetMapping("/api/demo/me")
     public Result<Map<String, Object>> me(@AuthenticationPrincipal LoginUser loginUser) {
         return Result.success(Map.of(
             "userId", loginUser.getUserId(),
             "username", loginUser.getUsername(),
             "roles", loginUser.getRoles(),
             "permissions", loginUser.getPermissions()
         ));
     }
     ```
   - `GlobalExceptionHandler` 增加 `@ExceptionHandler(AccessDeniedException.class)` → 403 JSON
6. **两种写法怎么选**
   - URL 级：粗粒度、统一入口、适合 `/api/admin/**`
   - 方法级：细粒度、按钮权限、适合 `user:delete`
   - 可以同时存在，先 URL 后方法
7. **为什么方法级 403 走 `@RestControllerAdvice`**
   - `@PreAuthorize` 在 Controller 方法调用时抛 `AccessDeniedException`
   - 发生在 MVC 调用栈内，能被全局异常处理器捕获
   - 过滤器链里的 403 才走 `AccessDeniedHandler`
   - 两边都要处理
8. **自定义权限校验 Bean（可选小节）**
   - `@Component("permissionChecker")` + `boolean has(String code)`
   - `@PreAuthorize("@permissionChecker.has('user:add')")`
   - 说明适合复杂逻辑，简单场景直接用 `hasAuthority`
9. **验证**
   - 重复第 08 章矩阵，确认方法级也生效
   - `GET /api/demo/me` 返回当前用户角色/权限
   - 删除 viewer 的 `user:list` 权限后，同一 token 再查 → 403（说明权限每次从库加载）
10. **常见报错**
    - 注解不生效：忘了 `@EnableMethodSecurity`
    - `@PreAuthorize` 里写 `hasRole('ROLE_ADMIN')` → 双前缀
    - 403 仍返回 HTML：全局异常处理器没加 `AccessDeniedException`
11. **小结 + 自测题**（4 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "@EnableMethodSecurity\|@PreAuthorize\|@AuthenticationPrincipal" 09-方法级授权.md
grep -n "AccessDeniedException" 09-方法级授权.md
grep -c '^```' 09-方法级授权.md
```
Expected：三个注解都有完整示例；`AccessDeniedException` 处理存在；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/09-方法级授权.md
git commit -m "docs(security-series): 第 09 章 方法级授权"
```

---

## Task 13：第 10 章 分配角色与分配权限

**Files:**
- Create: `docs/spring/security-series/10-分配角色与分配权限.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：能通过接口给用户分配角色、给角色分配权限；已有 Token 立即反映新权限
2. **前置检查**：第 09 章方法级授权生效
3. **完整代码文件**
   - `modules/user/dto/AssignRolesDTO.java`
     - `List<Long> roleIds`，`@NotNull`
   - `modules/role/dto/AssignPermissionsDTO.java`
     - `List<Long> permissionIds`，`@NotNull`
   - 修改 `UserService` / `UserServiceImpl`
     - `List<Long> getRoleIds(Long userId)`
     - `void assignRoles(Long userId, List<Long> roleIds)`
     - `@Transactional`
     - 校验用户存在、角色 ID 都存在
     - 先 `deleteByUserId`，再循环 `insert`
   - 修改 `RoleService` / `RoleServiceImpl`
     - `List<Long> getPermissionIds(Long roleId)`
     - `void assignPermissions(Long roleId, List<Long> permissionIds)`
     - `@Transactional`
     - 校验角色、权限存在
     - 先 `deleteByRoleId`，再循环 `insert`
   - 修改 `UserController`
     - `GET /api/users/{id}/roles`
     - `PUT /api/users/{id}/roles`
   - 修改 `RoleController`
     - `GET /api/roles/{id}/permissions`
     - `PUT /api/roles/{id}/permissions`
4. **事务与幂等**
   - 为什么用 `@Transactional`
   - “先删后插”在并发下的风险和教学简化说明
   - 空列表表示清空
5. **权限热更新验证（本章核心）**
   - 用 viewer 登录拿 token
   - `POST /api/users` → 403
   - admin 调 `PUT /api/users/3/roles`，给 viewer 加 `USER_ADMIN`
   - **不重新登录**，用原 token 再 `POST /api/users` → 200
   - 解释：JWT 没放权限，过滤器每次从库加载，所以立即生效
6. **反向验证**
   - 把 viewer 的角色改回 `VIEWER`
   - 原 token 再 `POST /api/users` → 403
7. **常见报错**
   - 分配后没变化：权限放进了 JWT
   - 外键失败：roleId/permissionId 不存在
   - 事务没生效：同类内部调用 `this.xxx()`
8. **小结 + 自测题**（3 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "AssignRolesDTO\|AssignPermissionsDTO\|assignRoles\|assignPermissions" 10-分配角色与分配权限.md
grep -n "@Transactional" 10-分配角色与分配权限.md
grep -n "不重新登录\|立即生效" 10-分配角色与分配权限.md
grep -c '^```' 10-分配角色与分配权限.md
```
Expected：DTO/方法齐全；事务注解存在；热更新验证存在；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/10-分配角色与分配权限.md
git commit -m "docs(security-series): 第 10 章 分配角色与分配权限"
```

---

## Task 14：第 11 章 退出登录与登录日志

**Files:**
- Create: `docs/spring/security-series/11-退出登录与登录日志.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：退出登录后旧 Token 立即 401；登录成功/失败都有日志
2. **前置检查**：第 10 章分配功能可用
3. **无状态 Token 的失效难题**
   - JWT 签发后到期前天然有效
   - 方案对比：`token_version`、Redis 黑名单、短 TTL + Refresh Token
   - 本系列选 `token_version`，因为不引入 Redis
4. **完整代码文件**
   - `modules/log/entity/SysLoginLog.java`
   - `modules/log/mapper/SysLoginLogMapper.java`
   - `modules/log/service/SysLoginLogService.java`
   - `modules/log/service/impl/SysLoginLogServiceImpl.java`
     - `record(Long userId, String username, String ip, String userAgent, boolean success, String message)`
   - 修改 `SysUserMapper`：
     ```java
     @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{userId}")
     int incrementTokenVersion(Long userId);
     ```
   - 修改 `AuthServiceImpl`
     - 登录成功：更新 `last_login_time`、`last_login_ip`，写成功日志
     - 登录失败：写失败日志，再抛出原异常
     - 用 `try/catch (AuthenticationException e)` 包住 `authenticate`
     - 获取 IP 的工具方法（`X-Forwarded-For` 优先，其次 `getRemoteAddr`）
     - 退出登录：`incrementTokenVersion(loginUser.getUserId())`
   - 修改 `AuthController`：`POST /api/auth/logout`，参数 `@AuthenticationPrincipal LoginUser`
   - 修改 `JwtAuthenticationFilter`：确认 tokenVersion 不一致时**不设置认证**
5. **验证**
   - 登录 → `GET /api/auth/me` 200
   - `POST /api/auth/logout` 200
   - 用旧 token 再 `GET /api/auth/me` → 401
   - 重新登录拿新 token → 200
   - 错误密码登录 → 401，且 `sys_login_log` 多一条失败记录
   - 成功登录 → `sys_login_log` 多一条成功记录
6. **常见报错**
   - 退出后旧 token 仍可用：过滤器没校验 tokenVersion
   - 日志没写：异常在写日志前抛出
   - `last_login_time` 不更新：更新语句没执行
7. **安全说明**
   - tokenVersion 是“全端下线”，不能让单个设备失效
   - Redis 黑名单能做到单 Token 失效，但引入额外依赖
8. **小结 + 自测题**（3 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "class SysLoginLog\|class SysLoginLogServiceImpl\|incrementTokenVersion\|tokenVersion" 11-退出登录与登录日志.md
grep -n "sys_login_log" 11-退出登录与登录日志.md
grep -c '^```' 11-退出登录与登录日志.md
```
Expected：日志类完整；版本号递增方法存在；日志表被说明；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/11-退出登录与登录日志.md
git commit -m "docs(security-series): 第 11 章 退出登录与登录日志"
```

---

## Task 15：第 12 章 测试与验收

**Files:**
- Create: `docs/spring/security-series/12-测试与验收.md`

- [ ] **Step 1：写章节**

必须包含：

1. **本章目标**：`mvn test` 通过；能用 MockMvc 和真实 Token 两种方式验证权限
2. **前置检查**：第 11 章全部功能可用；数据库种子数据存在
3. **测试依赖**
   - `spring-boot-starter-test`
   - `spring-security-test`
4. **测试一：JwtService 单元测试**
   - `src/test/java/com/keywin/security/security/JwtServiceTest.java`
   - 不启动 Spring 上下文，手动构造 `JwtProperties` 和 `JwtService`
   - 测试：生成 → 解析 userId/username/tokenVersion；过期时间在未来
5. **测试二：安全集成测试**
   - `src/test/java/com/keywin/security/SecurityIntegrationTest.java`
   - `@SpringBootTest` + `@AutoConfigureMockMvc`
   - 使用真实数据库 `security_lab`，依赖种子数据
   - 辅助方法 `login(String username, String password)`：POST 登录，从 JSON 取 `data.token`
   - 测试用例：
     - `pingIsPublic`：无 token → 200
     - `requestWithoutTokenReturns401`：`GET /api/users` → 401
     - `viewerCanListUsers`：viewer token → 200
     - `viewerCannotCreateUser`：viewer token POST → 403
     - `adminCanCreateUser`：admin token POST 随机用户名 → 200/201，并在 `@AfterEach` 删除
     - `logoutInvalidatesOldToken`：登录 → 退出 → 旧 token 访问 `/api/auth/me` → 401
     - `adminCanAccessAdminDemo`：admin → 200
     - `viewerCannotAccessAdminDemo`：viewer → 403
6. **测试三：`@WithMockUser` 方法级测试**
   - `src/test/java/com/keywin/security/UserControllerMethodSecurityTest.java`
   - `@SpringBootTest` + `@AutoConfigureMockMvc`
   - `@WithMockUser(authorities = "user:add")` 调 POST → 非 403
   - `@WithMockUser(authorities = "user:list")` 调 POST → 403
   - 说明：`@WithMockUser` 绕过 JWT 过滤器，专注测授权规则
7. **测试注意事项**
   - 集成测试会改数据库（创建用户、递增 tokenVersion），用随机用户名并在 `@AfterEach` 清理
   - 测试和手动 curl 共用同一套种子账号
   - 为什么不用 H2：SQL、外键、大小写行为与 MySQL 有差异，学习阶段直接用 MySQL 更真实
8. **`http/api.http` 验收**
   - 按顺序执行：ping → login → me → CRUD → 分配 → 退出
   - 每一步预期状态码
9. **常见报错**
   - 401：测试没带 token 或 token 过期
   - 403：权限编码不匹配
   - `Table 'security_lab.sys_user' doesn't exist`：没执行 SQL
   - 测试顺序互相影响：tokenVersion 被改，重新登录即可
10. **小结 + 自测题**（3 题）

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "JwtServiceTest\|SecurityIntegrationTest\|UserControllerMethodSecurityTest" 12-测试与验收.md
grep -n "@WithMockUser\|@AutoConfigureMockMvc" 12-测试与验收.md
grep -n "mvn test" 12-测试与验收.md
grep -c '^```' 12-测试与验收.md
```
Expected：三个测试类完整；注解存在；`mvn test` 命令存在；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/12-测试与验收.md
git commit -m "docs(security-series): 第 12 章 测试与验收"
```

---

## Task 16：第 13 章 复盘与迁移

**Files:**
- Create: `docs/spring/security-series/13-复盘与迁移.md`

- [ ] **Step 1：写章节**

必须包含：

1. **最终能力清单**
   - 对照第 00 章的目标逐项打勾
2. **完整文件树**
   - 与契约中的包结构完全一致
   - 标注每个文件属于哪一章创建
3. **Security 知识地图**
   - 认证：`UserDetailsService`、`PasswordEncoder`、`AuthenticationManager`、`Authentication`
   - 授权：`SecurityFilterChain`、`AuthorityAuthorizationManager`、`@PreAuthorize`
   - 上下文：`SecurityContextHolder`、`@AuthenticationPrincipal`
   - 异常：`AuthenticationEntryPoint`、`AccessDeniedHandler`、`AuthenticationException`、`AccessDeniedException`
   - 过滤器：`OncePerRequestFilter`、`addFilterBefore`
4. **常见坑清单（至少 18 条）**
   - 每条：现象 / 原因 / 解决
   - 覆盖：默认全 401、明文密码、缺 PasswordEncoder、permitAll 仍 401、ROLE_ 前缀、401/403 HTML、ThreadLocal 跨线程、CORS credentials、Swagger 拦截、Boot 3 旧 API、jjwt 0.11 vs 0.12、忘写 `chain.doFilter`、退出 Token 仍有效、`matches` 参数反了、`@PreAuthorize` 没开、`hasRole("ROLE_ADMIN")` 双前缀、secret 太短、权限放 JWT 导致不刷新
5. **迁回 `mall-product` 对照表**
   - `JwtAuthInterceptor` → `JwtAuthenticationFilter`
   - `UserContextHolder` → `SecurityContextHolder`
   - `UserContext` → `LoginUser`
   - `RequirePermission` + `PermissionAspect` → `@PreAuthorize`
   - `ForbiddenException` → `AccessDeniedException` / `AccessDeniedHandler`
   - `UnauthorizedException` → `AuthenticationEntryPoint`
   - `AuthServiceImpl` 手动查用户/比密码 → `AuthenticationManager`
   - `tokenVersion` 保留
   - 迁移顺序：先加依赖 → 再换登录 → 再换过滤器 → 最后删 AOP
6. **自测题（10 题 + 参考答案）**
   - 覆盖认证、授权、JWT、过滤器、异常、方法级权限、Token 失效
7. **下一步学什么**
   - Refresh Token
   - Redis 黑名单
   - 菜单/按钮树
   - 数据权限
   - OAuth2 / 单点登录
   - 安全审计与限流
8. **结语**
   - 一句话总结：Security = 过滤器链 + SecurityContext + 授权规则

- [ ] **Step 2：自检**

Run:
```bash
cd docs/spring/security-series
grep -n "JwtAuthInterceptor\|UserContextHolder\|RequirePermission\|PermissionAspect" 13-复盘与迁移.md
grep -c "^| " 13-复盘与迁移.md
grep -c '^```' 13-复盘与迁移.md
```
Expected：旧类名都出现在迁移表；表格行数足够；围栏偶数。

- [ ] **Step 3：提交**

```bash
git add docs/spring/security-series/13-复盘与迁移.md
git commit -m "docs(security-series): 第 13 章 复盘与迁移"
```

---

## Task 17：全系列一致性检查

**Files:**
- Modify: 任意存在不一致的章节

- [ ] **Step 1：检查章节文件齐全**

Run:
```bash
cd docs/spring/security-series
ls -1
```
Expected：`README.md`、`00-...md` 到 `13-...md`、`sql/`、`http/` 全部存在。

- [ ] **Step 2：检查占位符**

Run:
```bash
cd docs/spring/security-series
grep -rn "TODO\|TBD\|待补充\|省略\|同上\|\.\.\." --include="*.md" .
```
Expected：无输出。若 `...` 出现在正文叙述中，确认不是代码省略；代码块内不允许。

- [ ] **Step 3：检查代码围栏成对**

Run:
```bash
cd docs/spring/security-series
for f in *.md; do n=$(grep -c '^```' "$f"); if [ $((n % 2)) -ne 0 ]; then echo "ODD: $f ($n)"; fi; done
```
Expected：无输出。

- [ ] **Step 4：检查关键名称一致**

Run:
```bash
cd docs/spring/security-series
grep -rn "com\.keywin\.security" --include="*.md" . | wc -l
grep -rn "com\.example\|com\.keywin\.mall" --include="*.md" .
grep -rn "8089" --include="*.md" .
```
Expected：包名统一为 `com.keywin.security`；不出现 `com.example` 或 `com.keywin.mall`（迁移对照表除外，需人工确认）；端口统一 8080，不出现 8089。

- [ ] **Step 5：检查接口与权限编码一致**

Run:
```bash
cd docs/spring/security-series
grep -rn "user:list\|user:add\|user:update\|user:delete" --include="*.md" . | wc -l
grep -rn "role:list\|permission:list" --include="*.md" . | wc -l
grep -rn "/api/auth/login\|/api/users\|/api/roles\|/api/permissions" --include="*.md" . | wc -l
```
Expected：三类编码都出现；接口路径在 README、05~12 章一致。

- [ ] **Step 6：修复不一致并提交**

```bash
git add docs/spring/security-series
git commit -m "docs(security-series): 全系列一致性检查与修订"
```

---

## Self-Review（计划自检）

**1. Spec coverage**

- 只写文档：Task 1~16 全部是 `.md` / `.sql` / `.http` 文件
- 目录：`docs/spring/security-series/`：Task 1 起
- 技术栈：契约中锁定
- 范围：用户/角色/权限 CRUD（Task 6、7）、登录（Task 8）、JWT（Task 9）、过滤器（Task 10）、URL 授权（Task 11）、方法级（Task 12）、分配（Task 13）、tokenVersion + 日志（Task 14）、测试（Task 15）、复盘（Task 16）
- 14 章：Task 3~16
- 增量式：每章前置检查 + 单章目标
- 两个卡点：每章“先跑通 + 完整代码 + 逐行解释 + 验证 + 报错”
- 非目标：未安排刷新令牌、Redis、菜单、数据权限、前端

**2. Placeholder scan**

- 计划中无 TBD/TODO
- 每章内容清单具体到类名、方法名、接口路径、预期结果
- 未使用“类似 Task N”省略代码

**3. Type consistency**

- 统一使用 `LoginUser`、`JwtService`、`JwtProperties`、`SecurityConfig`
- 统一权限编码与包名
- 统一响应字段 `code/message/data`
- 统一接口路径
- 统一三个账号与哈希

---

## 执行方式

计划已保存到 `docs/superpowers/plans/2026-11-27-spring-security-series.md`。

两种执行方式：

1. **Subagent-Driven（推荐）**：每个 Task 派一个独立 subagent 写，我在 Task 之间做一致性审查。
2. **Inline Execution**：在当前会话里按 Task 批量写，阶段性停下来给你检查。

你选哪种？
