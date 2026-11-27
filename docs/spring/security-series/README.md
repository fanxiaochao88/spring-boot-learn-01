# Spring Security 6 + JWT 从零实战系列

> 从空工程开始，一步一步搭出 `security-lab`：用户、角色、权限模块 + Spring Security 6 + JWT。
> 每章只加一个能力，先给完整代码跑通，再逐行解释。

---

## 这套教程适合谁

- 会 Spring Boot 基础：能写 Controller、Service、Mapper，能连 MySQL
- 写过手写登录：自己查用户、自己比密码、自己发 Token
- 准备把项目重构成 Spring Security，但一看过滤器链、`UserDetailsService`、`SecurityContext` 就头晕
- 受够了“只给片段、不给完整文件”的教程

如果你还没写过登录接口，建议先补一个最简登录 Demo，再回来。

---

## 学完你能做到

- 用 Spring Boot 3 + Spring Security 6 搭建无状态 JWT 认证
- 设计并实现 RBAC：用户 → 角色 → 权限
- 用 `AuthenticationManager` 替换手写密码校验
- 写 `JwtAuthenticationFilter`，让每个请求自动认出当前用户
- 配置 URL 级授权和方法级 `@PreAuthorize`
- 统一返回 401 / 403 JSON，前端能区分“请登录”和“没权限”
- 退出登录后让旧 Token 立即失效
- 记录登录成功/失败日志
- 用 MockMvc 和真实 Token 两种方式测试权限

---

## 章节列表

| 章 | 标题 | 你会做出什么 | 预计 |
| --- | --- | --- | --- |
| 00 | [导读与环境准备](00-导读与环境准备.md) | 环境就绪，知道整套路怎么走 | 20 分钟 |
| 01 | [工程搭建与数据库](01-工程搭建与数据库.md) | 项目能启动，`/api/ping` 返回 ok | 40 分钟 |
| 02 | [Security 初体验与过滤器链](02-Security初体验与过滤器链.md) | 亲眼看到“所有接口 401”，理解过滤器链 | 40 分钟 |
| 03 | [统一响应与用户模块 CRUD](03-统一响应与用户模块CRUD.md) | 用户增删改查 + 统一 JSON | 60 分钟 |
| 04 | [角色与权限模块 CRUD](04-角色与权限模块CRUD.md) | 角色、权限增删改查 | 50 分钟 |
| 05 | [PasswordEncoder 与登录认证](05-PasswordEncoder与登录认证.md) | 用 `AuthenticationManager` 登录成功 | 60 分钟 |
| 06 | [JWT 生成与解析](06-JWT生成与解析.md) | 登录返回真 Token，能解析载荷 | 50 分钟 |
| 07 | [JwtAuthenticationFilter 与 401](07-JwtAuthenticationFilter与401.md) | 每个请求自动认人，未登录返回 401 | 60 分钟 |
| 08 | [URL 级授权与 403](08-URL级授权与403.md) | 三个账号的 200/403 矩阵 | 50 分钟 |
| 09 | [方法级授权](09-方法级授权.md) | `@PreAuthorize` + `@AuthenticationPrincipal` | 45 分钟 |
| 10 | [分配角色与分配权限](10-分配角色与分配权限.md) | 分配后旧 Token 立即生效 | 50 分钟 |
| 11 | [退出登录与登录日志](11-退出登录与登录日志.md) | 退出后旧 Token 立即失效 | 50 分钟 |
| 12 | [测试与验收](12-测试与验收.md) | `mvn test` 通过，接口验收脚本 | 60 分钟 |
| 13 | [复盘与迁移](13-复盘与迁移.md) | 知识地图、坑清单、迁回旧项目 | 40 分钟 |

总计约 10~14 小时。建议一天 1~2 章，不要赶。

---

## 技术版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17 | Spring Boot 3 最低要求 |
| Spring Boot | 3.2.4 | 与现有学习项目保持一致 |
| Spring Security | 6.2.x | 由 Spring Boot 父 POM 管理 |
| MyBatis-Plus | 3.5.5 | 必须用 `mybatis-plus-spring-boot3-starter` |
| MySQL | 8.x | utf8mb4 |
| jjwt | 0.12.6 | 注意不是 0.11.x 的旧 API |
| Maven | 3.9+ | |

---

## 三个固定账号

| 用户名 | 密码 | 角色 | 能做什么 |
| --- | --- | --- | --- |
| `admin` | `admin123` | `SUPER_ADMIN` | 全部接口 |
| `user_admin` | `user123` | `USER_ADMIN` | 用户查询/新增/修改，角色和权限只读 |
| `viewer` | `viewer123` | `VIEWER` | 用户、角色、权限都只读 |

密码在数据库里存的是 BCrypt 哈希，明文只用于登录。哈希已在 `sql/02-data.sql` 中准备好。

---

## 学习方式（很重要）

1. **按顺序读**。第 07 章依赖第 05、06 章的类，跳章会编译不过。
2. **代码自己敲**。每章的 Java 文件都是完整的，包含 `package` 和 `import`，可以直接复制，但建议至少手敲关键类。
3. **每章先跑通，再看解释**。验证命令不通过，不要往下走。
4. **不要一次写完再启动**。每章结束都应该是一个可运行状态。
5. **报错先看本章“常见报错”**，再看控制台第一条异常。

每章固定结构：

```text
本章目标 → 前置检查 → 先跑通（完整代码）→ 再解释 → 验证 → 常见报错 → 小结 + 自测
```

---

## 配套文件

```text
security-series/
├── README.md
├── 00-导读与环境准备.md
├── 01-工程搭建与数据库.md
├── ...
├── 13-复盘与迁移.md
├── sql/
│   ├── 01-schema.sql      # 建库建表
│   └── 02-data.sql        # 三个账号、角色、权限、关联数据
└── http/
    └── api.http           # IDEA HTTP Client 验收脚本
```

`sql/` 下的文件请在第 00 章先执行。`http/api.http` 可以直接用 IDEA 打开，按请求顺序点。

---

## 和旧教程的区别

| 旧教程的问题 | 这套教程的做法 |
| --- | --- |
| 先讲一大段过滤器链原理 | 先加依赖看现象，用到哪个讲哪个 |
| 代码只给片段 | 每个文件完整代码，含包名和 import |
| 新旧 API 混着写 | 只用 Spring Boot 3 / Security 6 / jjwt 0.12.x |
| 不知道自己做对没有 | 每章有 curl 命令、预期输出、报错对照 |
| 讲完不知道怎么迁移 | 第 13 章给旧项目逐类对照表 |

---

## 一句话

> Spring Security = 一条过滤器链 + 一个 `SecurityContext` + 一组授权规则。
> 你要写的只有：查用户、比密码、把 Token 解析成当前用户、声明谁能访问什么。
