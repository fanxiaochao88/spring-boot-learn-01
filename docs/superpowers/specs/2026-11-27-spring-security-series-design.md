# Spring Security 用户/角色/权限系列教程 —— 设计文档

- 日期：2026-11-27
- 状态：已与用户确认
- 产出类型：文档（不创建可运行工程）

## 1. 背景与问题

用户是 Spring Boot 新手，已有一个学习用 demo：手写登录、用户、角色、权限，以及商品管理模块。准备重构为 Spring Security，但此前的教程存在两个问题：

1. **概念先行**：一上来讲过滤器链、Provider、SecurityContext，用户不知道每行代码为什么这么写。
2. **代码不完整**：示例只有片段，包名、import、前后文件对不上，无法照着敲。

因此需要一套**从零、增量式、每步可运行**的系列教程，围绕用户/角色/权限模块，使用现代 Spring Security 6 + JWT，不使用旧版 API。

## 2. 已确认的决策

| 项 | 决定 |
| --- | --- |
| 产出 | 只写文档，不创建工程 |
| 文档目录 | `docs/spring/security-series/` |
| 学习工程 | `security-lab`，包名 `com.keywin.security`，数据库 `security_lab` |
| 技术栈 | Spring Boot 3.2.4、Java 17、Spring Security 6、MySQL 8、MyBatis-Plus 3.5.5、jjwt 0.12.6 |
| 认证 | JWT Bearer，无状态，不使用 Session、不使用 `WebSecurityConfigurerAdapter` |
| 范围 | 用户/角色/权限 CRUD、登录、JWT、认证、URL/方法级授权、401/403、tokenVersion 退出失效、登录日志 |
| 章节 | 14 章（00~13），每章 30~60 分钟 |
| 组织方式 | 增量式跑通：每章只加一个能力，先完整代码跑通，再逐行解释 |

## 3. 数据模型

RBAC 五张核心表 + 一张日志表：

```
sys_user ──< sys_user_role >── sys_role ──< sys_role_permission >── sys_permission
sys_login_log（登录成功/失败日志）
```

- `sys_user`：`username`、`password_hash`（BCrypt）、`nickname`、`phone`、`email`、`status`、`token_version`、`last_login_time`、`last_login_ip`
- `sys_role`：`code`（如 `SUPER_ADMIN`）、`name`、`description`、`status`
- `sys_permission`：`code`（如 `user:add`）、`name`、`module`、`description`、`status`
- `sys_user_role`：`user_id`、`role_id`
- `sys_role_permission`：`role_id`、`permission_id`
- `sys_login_log`：`user_id`、`username`、`ip`、`user_agent`、`success`、`message`

JWT 载荷只包含 `userId`、`username`、`tokenVersion`，不包含角色/权限。每次请求从数据库加载权限，保证权限变更立即生效、退出登录可失效旧 Token。

## 4. 安全设计

- `PasswordEncoder`：`BCryptPasswordEncoder`
- `UserDetailsService`：查询用户、角色、权限，组装自定义 `LoginUser implements UserDetails`
- 登录：`AuthenticationManager.authenticate()`，由 `DaoAuthenticationProvider` 完成查用户 + 验密码
- JWT：`JwtService` 负责签发/解析；`JwtAuthenticationFilter extends OncePerRequestFilter` 负责每请求认证
- 认证失败：`AuthenticationEntryPoint` 返回 401 JSON；授权失败：`AccessDeniedHandler` 返回 403 JSON
- URL 级：`SecurityFilterChain` 中声明粗粒度规则
- 方法级：`@EnableMethodSecurity` + `@PreAuthorize`，权限编码精确匹配
- 角色编码带 `ROLE_` 前缀放入 authorities；权限编码原样放入
- 退出登录：`token_version + 1`，旧 Token 解析后版本不匹配即失效

## 5. 章节清单

| 章 | 目标 | Security 重点 |
| --- | --- | --- |
| 00 | 导读、学习地图、环境准备、验收方式 | 无 |
| 01 | 建库建表 + 测试数据 + 最小 Spring Boot 工程跑通 | 无 |
| 02 | 只加 Security 依赖，观察全 401 与随机密码 | 默认行为、过滤器链 |
| 03 | 统一 `Result` / 全局异常 + 用户模块 CRUD | 无 |
| 04 | 角色、权限、关联表 CRUD | 无 |
| 05 | `PasswordEncoder` + `UserDetailsService` + `AuthenticationManager` 登录 | 认证三件套 |
| 06 | JWT 生成与解析，登录返回真 Token | JWT 结构、无状态取舍 |
| 07 | `JwtAuthenticationFilter`，每个请求认出用户 | `SecurityContext`、`OncePerRequestFilter` |
| 08 | 加载角色/权限 + URL 级授权 + 401/403 JSON | `hasRole` vs `hasAuthority`、`EntryPoint`、`AccessDeniedHandler` |
| 09 | 方法级授权 `@PreAuthorize` + `@AuthenticationPrincipal` | `@EnableMethodSecurity` |
| 10 | 给用户分配角色、给角色分配权限（事务） | 权限变更如何影响下一次请求 |
| 11 | 退出登录 `tokenVersion+1` + 登录日志 | 无状态 Token 主动失效 |
| 12 | MockMvc 测试 + 真实 Token 集成测试 + 验收脚本 | 测试中的安全上下文 |
| 13 | 完整文件清单、常见坑、迁回 `mall-product` 对照表、自测题 | 复盘 |

## 6. 每章固定结构

1. 本章目标：一个可验证的具体结果
2. 前置检查：上章验收命令
3. 先跑通：完整目录树 + 每个新增/修改文件的完整代码
4. 再解释：逐行拆解，重点解释"为什么必须这么写"
5. 验证：curl / `.http` 请求 + 预期响应 + 常见报错
6. 小结 + 自测题

## 7. 配套文件

- `README.md`：系列索引、学习路线、版本说明
- `00-导读与环境准备.md` ~ `13-复盘与迁移.md`
- `sql/01-schema.sql`、`sql/02-data.sql`
- `http/api.http`

## 8. 非目标（本期不做）

- 刷新令牌、Redis 黑名单
- 菜单/按钮树、数据权限
- OAuth2、单点登录
- 前端页面
- 逻辑删除、乐观锁（保持数据模型聚焦）

## 9. 验收标准

- 每章代码完整，可直接复制运行
- 每章末尾有可执行的验证命令与预期输出
- 14 章读完后，能独立完成 `security-lab` 并迁回 `mall-product`
- 全部使用 Spring Boot 3 / Spring Security 6 / jjwt 0.12.x 的现代 API
