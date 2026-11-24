# Spring Security 教程：从手写登录 / RBAC 到框架化

> 面向已经手写完成「登录 + Token + 拦截器 + RBAC + AOP 注解鉴权」的同学。
> 目标不是背 API，而是搞清楚：**你现在写的每一段代码，Spring Security 对应的是哪一块？它为什么这么设计？换过去之后哪些代码可以删，哪些还得自己写？**
>
> 示例代码以 **Spring Boot 3.x + Spring Security 6.x** 为准（这是当前主流版本，与网上大量 Spring Boot 2.x / Security 5.x 的教程写法有明显差异，注意区分）。

---

## 目录

1. [先复盘：你手写的那套，本质是什么](#1-先复盘你手写的那套本质是什么)
2. [Spring Security 到底替你做了什么](#2-spring-security-到底替你做了什么)
3. [最关键的概念差异：拦截器 vs 过滤器链](#3-最关键的概念差异拦截器-vs-过滤器链)
4. [核心概念地图（必须先建立的 7 个概念）](#4-核心概念地图必须先建立的-7-个概念)
5. [一次带 Token 的请求，完整走一遍](#5-一次带-token-的请求完整走一遍)
6. [认证详解：AuthenticationManager 与 UserDetailsService](#6-认证详解authenticationmanager-与-userdetailsservice)
7. [授权详解：URL 级与方法级](#7-授权详解url-级与方法级)
8. [完整落地代码：无状态 JWT + RBAC](#8-完整落地代码无状态-jwt--rbac)
9. [对照表：你的实现 → Spring Security 写法](#9-对照表你的实现--spring-security-写法)
10. [迁移路线（分阶段，能随时回退）](#10-迁移路线分阶段能随时回退)
11. [Spring Security 不替你做的事](#11-spring-security-不替你做的事)
12. [常见坑清单](#12-常见坑清单)
13. [调试与源码阅读方法](#13-调试与源码阅读方法)
14. [附录：动态权限与数据权限思路](#14-附录动态权限与数据权限思路)

---

## 1. 先复盘：你手写的那套，本质是什么

你现在的链路大概是这样的：

```
HTTP 请求
   │
   ▼
[拦截器 Interceptor]  ── 从 Header 取 token ──> 校验签名/过期
   │                                           │
   │                                    解析出 userId
   │                                           │
   │                                    查库拿权限
   │                                           │
   │                                    塞进 ThreadLocal
   ▼
DispatcherServlet
   │
   ▼
[Controller]
   │
   ▼
[AOP 切面]  ── 读方法上的自定义注解 @RequirePermission("user:add")
   │            └─ 从 ThreadLocal 取当前用户权限，比对
   ▼
Service / 业务逻辑
```

拆开看，你其实实现了这几件事：

| 你写的组件 | 职责 | 用到的底层技术 |
|---|---|---|
| 登录接口 | 校对用户名密码 | 手动 `userMapper.findByUsername` + 密文比对 |
| Token 生成 / 校验 | 证明「你是谁」 | JWT / 随机串 + Redis |
| 拦截器 | 每个请求提取并校验身份 | `HandlerInterceptor` |
| ThreadLocal 工具类 | 在本次请求内传递当前用户 | `ThreadLocal` |
| 自定义注解 + AOP | 判断「你能不能做这件事」 | `@Aspect` + 反射 |
| 全局异常处理 | 401 / 403 统一返回 | `@RestControllerAdvice` |
| 密码加密 | 存储安全 | MD5 / BCrypt |

**结论：你手写的是一个「认证（Authentication）+ 授权（Authorization）」的最小闭环，而且思路完全正确。**
Spring Security 不是另一套哲学，它就是把上面这堆东西**标准化、参数化、并补上了大量边界情况**。

两个术语先钉死，后面反复用到：

- **认证（Authentication）**：你是谁？—— 登录、验 token。
- **授权（Authorization）**：你能干什么？—— 权限判断、角色判断。

---

## 2. Spring Security 到底替你做了什么

一句话：**Spring Security 提供了一条可配置的过滤器链，把「认证 → 建立安全上下文 → 授权 → 异常处理」拆成职责单一的组件，你只需要补充「业务数据从哪里来」和「规则是什么」。**

具体来说，它替你做了这些：

| 能力 | 手写方案 | Spring Security |
|---|---|---|
| 密码编码 | 自己选算法、自己写盐 | `PasswordEncoder`，默认推荐 BCrypt，支持升级/兼容旧格式 |
| 认证流程编排 | 登录接口里手写 | `AuthenticationManager` 统一入口，失败原因细分 |
| 当前用户传递 | 自己写 ThreadLocal + 清理 | `SecurityContextHolder`（本身就是 ThreadLocal，且自动清理） |
| 请求级拦截 | 自己写 Interceptor | 内置过滤器链（Bearer 解析、匿名用户、异常翻译等） |
| 权限判断 | 自己写 AOP + 注解 | `@PreAuthorize` / `@PostAuthorize` 表达式，支持 SpEL |
| 401 / 403 | 全局异常处理 | `AuthenticationEntryPoint` / `AccessDeniedHandler`，自动区分 |
| CSRF / CORS / 会话固定 | 基本没做 | 默认开启/可配置 |
| 安全响应头 | 没做 | `HeaderWriterFilter` 自动加 `X-Content-Type-Options` 等 |
| 匿名用户 | 没做（通常直接抛异常） | `AnonymousAuthenticationFilter`，方便「可选登录」场景 |
| 方法级鉴权 | 自己写切面 | 内部也是 AOP，但顺序、代理、异常都已处理好 |

**但注意**：Spring Security 不是"零配置魔法"。它默认那套（表单登录 + Session）和你现在「无状态 Token」的架构完全不同，所以**不能只加个依赖就完事**，必须显式告诉它："我是一个前后端分离的 API 服务，别给我搞 Session 和登录页。"

---

## 3. 最关键的概念差异：拦截器 vs 过滤器链

这是新手最容易懵的一点。你说"思路接近"，**接近但不完全相同**，核心差别就在这：

### 3.1 请求经过的顺序

```
Tomcat
  │
  ▼
Filter 链（Servlet 规范）        ← Spring Security 工作在这一层
  │  Filter 1
  │  Filter 2  ... 
  │  Filter N
  ▼
DispatcherServlet                ← Spring MVC 入口
  │
  ▼
HandlerInterceptor.preHandle     ← 你手写的拦截器在这一层
  │
  ▼
Controller / AOP 切面
  │
  ▼
HandlerInterceptor.postHandle
  │
  ▼
视图 / JSON 序列化
  │
  ▼
Filter 链返回
```

几个直接后果：

1. **Filter 比 Interceptor 更靠外**。Filter 在 Spring MVC 之前执行，甚至能拦下根本不会进入 Controller 的请求（比如静态资源、404、OPTIONS 预检）。
2. **Filter 能改写 Request / Response**（比如包装 `HttpServletRequest` 读 body），Interceptor 只能拿到原始对象。
3. Spring Security 是**一整条 Filter 链**，不是单个 Filter。你的 Interceptor 大致对应链里的**一个** Filter。

### 3.2 Spring Security 的 Filter 链是怎么挂上去的

Spring Security 不直接往 Servlet 容器里塞一堆 Filter，而是注册**一个**代理 Filter：

```
Servlet 容器
  └── DelegatingFilterProxy("springSecurityFilterChain")
        └── FilterChainProxy          ← 真正的链容器
              ├── SecurityFilterChain (匹配 /api/**)
              │     ├── SecurityContextHolderFilter
              │     ├── CorsFilter
              │     ├── CsrfFilter
              │     ├── LogoutFilter
              │     ├── UsernamePasswordAuthenticationFilter
              │     ├── ...（你自定义的 JwtFilter 也可以插进来）
              │     ├── AnonymousAuthenticationFilter
              │     ├── ExceptionTranslationFilter
              │     └── AuthorizationFilter        ← URL 级授权发生在这里
              │
              └── SecurityFilterChain (匹配 /admin/**)   ← 可以有多个链，按顺序匹配
```

`FilterChainProxy` 内部**默认过滤器顺序**（记住这个顺序，调试时非常有用）：

| 顺序 | 过滤器 | 作用 |
|---|---|---|
| 1 | `DisableEncodeUrlFilter` | 禁用 URL 重写带 sessionId |
| 2 | `WebAsyncManagerIntegrationFilter` | 把 SecurityContext 传给异步请求 |
| 3 | `SecurityContextHolderFilter` | 加载/清理 SecurityContext |
| 4 | `HeaderWriterFilter` | 写安全响应头 |
| 5 | `CorsFilter` | 跨域 |
| 6 | `CsrfFilter` | CSRF 防护 |
| 7 | `LogoutFilter` | 登出 |
| 8 | `UsernamePasswordAuthenticationFilter` | 表单登录（你自定义的 JWT Filter 常插在它前面） |
| 9 | `DefaultLoginPageGeneratingFilter` | 默认登录页（API 项目通常关掉） |
| 10 | `BasicAuthenticationFilter` | HTTP Basic |
| 11 | `RequestCacheAwareFilter` | 记住原请求，登录后跳回 |
| 12 | `SecurityContextHolderAwareRequestFilter` | 包装 request 的 isUserInRole 等 |
| 13 | `AnonymousAuthenticationFilter` | 未认证时放一个匿名身份 |
| 14 | `ExceptionTranslationFilter` | 捕获认证/授权异常，转 401 / 403 |
| 15 | `AuthorizationFilter` | **URL 级授权判断（最后一道关）** |

> 你的 Interceptor 相当于第 8、15 两件事的合体：先认证（解析 token），再授权（判断路径）。
> Spring Security 把它拆成两段，**认证在前，授权在后**，中间隔着一堆通用过滤器。

### 3.3 记住一句话

> **Filter 决定"这个请求能不能继续往下走"，Interceptor 决定"这个请求能不能进入某个 Controller 方法"。**
> Spring Security 选择了 Filter，因为认证失败时它希望请求**根本不要进入 Spring MVC**，也更便于统一处理异常和响应。

---

## 4. 核心概念地图（必须先建立的 7 个概念）

### 4.1 SecurityContextHolder —— 你的 ThreadLocal

```java
// 你的写法
UserContext.set(userId, permissions);

// Spring Security
SecurityContext context = SecurityContextHolder.getContext();
Authentication auth = context.getAuthentication();
String username = auth.getName();
Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
```

`SecurityContextHolder` 默认策略是 `ThreadLocal`（准确说是 `ThreadLocalSecurityContextHolderStrategy`），和你的工具类一模一样。好处也一样：同请求内任何地方都能取；**坑也一样**：异步线程、线程池里取不到，需要用 `DelegatingSecurityContextExecutor` 包装。

注意：**在无状态场景里，它是"每请求建一次、请求结束清掉"**，不是全局共享。

### 4.2 Authentication —— 一个"凭证袋"

`Authentication` 是所有身份信息的统一抽象：

```java
public interface Authentication extends Principal, Serializable {
    Object getPrincipal();        // 通常就是 UserDetails，代表"用户"
    Object getCredentials();      // 密码或 token，认证后通常清空
    Collection<? extends GrantedAuthority> getAuthorities();  // 权限
    boolean isAuthenticated();    // 是否已认证
    Object getDetails();          // 附加信息，如请求 IP
}
```

最常见的实现是 `UsernamePasswordAuthenticationToken`：

```java
// 未认证（登录时）
new UsernamePasswordAuthenticationToken(username, rawPassword);

// 已认证（校验通过 / 解析 token 后）
UsernamePasswordAuthenticationToken.authenticated(userDetails, null, userDetails.getAuthorities());
```

> 旧教程常见的 `new UsernamePasswordAuthenticationToken(principal, credentials, authorities)`（三参构造）现在仍可用，但 Spring Security 5.7+ 推荐用 `authenticated(...)` / `unauthenticated(...)` 静态工厂，语义更清楚。

### 4.3 AuthenticationManager —— 认证的总入口

```java
public interface AuthenticationManager {
    Authentication authenticate(Authentication authentication) throws AuthenticationException;
}
```

你登录接口里手写的「查用户 → 比密码 → 抛异常」，在 Spring Security 里就是一次：

```java
Authentication result = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(username, rawPassword));
```

认证失败它会抛具体异常：`BadCredentialsException`（密码错）、`UsernameNotFoundException`（用户不存在，通常被包装成前者以防止用户名枚举）、`DisabledException`、`LockedException`、`AccountExpiredException`……

### 4.4 ProviderManager / AuthenticationProvider —— 具体怎么认证

`AuthenticationManager` 最常见实现是 `ProviderManager`，它持有一组 `AuthenticationProvider`，挨个尝试，谁能处理 `supports()` 就用谁。

对账号密码登录，用的是 `DaoAuthenticationProvider`，它的内部逻辑正好对应你手写的：

```
DaoAuthenticationProvider.authenticate()
  ├── userDetailsService.loadUserByUsername(username)   ← 你查库
  ├── passwordEncoder.matches(rawPassword, userDetails.getPassword())  ← 你比密码
  ├── userDetails.isEnabled() / isAccountNonLocked() ... ← 你校验状态
  └── 成功：返回带 authorities 的已认证 Authentication
```

**所以你要写的只有 `UserDetailsService`**（"按用户名查人"）和 `PasswordEncoder`（"怎么比密码"），其余编排框架包了。

### 4.5 UserDetails / UserDetailsService —— 你的 User 对象 + 查库逻辑

```java
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}
```

`UserDetails` 是框架眼里的用户，必须提供：用户名、密码、权限集合、账号是否可用/未锁定/未过期。

最简单的实现是直接用框架自带的 `User` 类：

```java
User.withUsername("alice")
    .password("$2a$10$...")           // 必须是编码后的
    .authorities("ROLE_ADMIN", "user:add", "user:delete")
    .accountLocked(false)
    .disabled(false)
    .build();
```

> 你的 `SysUser` 不需要实现 `UserDetails`。更推荐写一个**适配器**把 `SysUser` 转成 `User` 或自定义 `UserDetails`，避免 entity 背框架接口。

### 4.6 GrantedAuthority / Role —— 权限的两种写法

**Role 本质就是一种 Authority**，只是约定带 `ROLE_` 前缀：

| 写法 | 存的值 | 判断方式 |
|---|---|---|
| 角色 | `ROLE_ADMIN` | `hasRole("ADMIN")`（框架自动补 `ROLE_`） |
| 权限 | `user:add` | `hasAuthority("user:add")` |
| 权限（带前缀） | `ROLE_ADMIN` | `hasAuthority("ROLE_ADMIN")` |

⚠️ **最常见的坑**：数据库存了 `ADMIN`，代码写 `hasRole("ADMIN")` —— 框架会去找 `ROLE_ADMIN`，结果永远 403。
**规则：`hasRole` 会自动加 `ROLE_`，`hasAuthority` 不会加。**

RBAC 项目里推荐：**角色和权限都塞进 authorities**，角色用 `ROLE_` 前缀，细粒度权限直接用字符串：

```java
.authorities("ROLE_ADMIN", "user:add", "user:list", "order:export")
```

### 4.7 PasswordEncoder —— 别再用 MD5 了

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

- BCrypt 自带随机盐、可调 cost，同一密码每次加密结果都不同。
- 校验用 `matches(raw, encoded)`，**不是**把明文再加密一次做字符串比较。
- 老项目已有 MD5 数据？用 `DelegatingPasswordEncoder` 做平滑迁移（见第 10 节）。

---

## 5. 一次带 Token 的请求，完整走一遍

假设登录已完成，客户端带着 `Authorization: Bearer xxx` 发起请求。

```
① 请求进入 FilterChainProxy
        │
② SecurityContextHolderFilter
   → 由于是 STATELESS，不读 Session，上下文为空
        │
③ 你的 JwtAuthenticationFilter（插在 UsernamePasswordAuthenticationFilter 之前）
   ├── 取 Header: Authorization: Bearer xxx
   ├── 没有 → 直接放行（后面会被 AnonymousAuthenticationFilter 置为匿名）
   ├── 解析签名/过期时间
   ├── 成功 → loadUserByUsername（或直接从 token claim 取权限）
   └── 构造已认证 Authentication，塞进 SecurityContextHolder
        │
④ AnonymousAuthenticationFilter
   → 如果此时还没认证，放一个 AnonymousAuthenticationToken（权限 ROLE_ANONYMOUS）
        │
⑤ ExceptionTranslationFilter
   → 只是"包"着后面：后面抛 AuthenticationException → 401
                       抛 AccessDeniedException    → 403（已认证）或 401（匿名）
        │
⑥ AuthorizationFilter
   → 读取 authorizeHttpRequests 的规则，拿 SecurityContext 里的 Authentication 比对
   → 不通过：抛 AccessDeniedException / AuthenticationException，交给 ⑤ 处理
        │
⑦ 进入 DispatcherServlet → Controller
   → 如果开启方法级安全，再经过 AuthorizationManagerBeforeMethodInterceptor
     做 @PreAuthorize 判断
        │
⑧ 请求结束，SecurityContextHolderFilter 清理 ThreadLocal
```

**对比你的流程**：②+③ ≈ 你的拦截器；⑥ ≈ 你的拦截器白名单 + 权限判断；⑦ ≈ 你的 AOP；⑤ ≈ 你的全局异常处理。

---

## 6. 认证详解：AuthenticationManager 与 UserDetailsService

### 6.1 登录接口怎么写

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        // 1. 交给 Security 认证：用户名不存在 / 密码错 / 被禁用 都会抛对应异常
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));

        // 2. 认证通过，principal 就是你 UserDetailsService 返回的对象
        UserDetails user = (UserDetails) authentication.getPrincipal();

        // 3. 自己签发 token（Spring Security 不负责发 token，见第 11 节）
        String token = jwtUtil.generate(user.getUsername(), user.getAuthorities());

        // 4. 无状态模式下可以先塞进上下文，方便本请求后续使用；请求结束会自动清理
        SecurityContextHolder.getContext().setAuthentication(authentication);

        return new LoginResponse(token);
    }
}
```

### 6.2 UserDetailsService 怎么写

```java
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final SysUserMapper userMapper;
    private final SysPermissionMapper permissionMapper;

    public DbUserDetailsService(SysUserMapper userMapper, SysPermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.findByUsername(username);
        if (user == null) {
            // 不要提示"用户不存在"，统一抛 UsernameNotFoundException，
            // DaoAuthenticationProvider 会把它转成 BadCredentialsException，防止用户名枚举
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        // RBAC：把角色和权限都查出来，角色加 ROLE_ 前缀
        List<String> authorities = permissionMapper.selectAuthoritiesByUserId(user.getId());
        // 例：["ROLE_ADMIN", "user:add", "user:delete", "order:list"]

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities.toArray(String[]::new))
                .disabled(user.getStatus() == 0)
                .accountLocked(user.getLocked() == 1)
                .build();
    }
}
```

### 6.3 AuthenticationManager 从哪来

只要容器里有 **`UserDetailsService` Bean + `PasswordEncoder` Bean**，Spring Boot 的 `InitializeUserDetailsBeanManagerConfigurer` 会自动装配一个 `DaoAuthenticationProvider`。你只需暴露 `AuthenticationManager`：

```java
@Bean
public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
}
```

> 不需要手动 `new ProviderManager(...)`。如果以后有多种认证方式（用户名密码、短信、扫码），再考虑自定义 `AuthenticationProvider`。

### 6.4 认证失败的异常怎么接到统一返回

`AuthenticationManager.authenticate` 抛出的 `AuthenticationException` 在**登录接口里**会直接冒泡到你的 `@RestControllerAdvice`，这里正常处理即可：

```java
@ExceptionHandler(AuthenticationException.class)
public ResponseEntity<?> handleAuth(AuthenticationException ex) {
    return ResponseEntity.status(401).body(ApiResult.fail(401, "用户名或密码错误"));
}
```

而**过滤器链里**（比如 JWT 过期）抛出的异常不会经过 Controller，需要 `AuthenticationEntryPoint` 处理（见第 8.5 节）。这是两个不同位置，新手常混淆。

---

## 7. 授权详解：URL 级与方法级

Spring Security 的授权分两层，**两层的写法、作用域、配置开关都不同**：

| 层次 | 配置位置 | 作用 | 何时用 |
|---|---|---|---|
| URL 级 | `SecurityFilterChain` 的 `authorizeHttpRequests` | 按路径/HTTP 方法控制访问 | 粗粒度：模块入口、公开接口 |
| 方法级 | `@PreAuthorize` 等注解 | 按 Service/Controller 方法控制 | 细粒度：`user:add` 这类权限点 |

### 7.1 URL 级授权

```java
http.authorizeHttpRequests(auth -> auth
    // 1. 顺序很重要：从上到下匹配，第一个命中的规则生效
    .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()          // CORS 预检
    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
    .requestMatchers("/actuator/health").permitAll()

    // 2. 角色
    .requestMatchers("/api/admin/**").hasRole("ADMIN")                // 需要 ROLE_ADMIN
    .requestMatchers("/api/report/**").hasAnyRole("ADMIN", "AUDITOR")

    // 3. 细粒度权限
    .requestMatchers(HttpMethod.POST, "/api/users").hasAuthority("user:add")
    .requestMatchers(HttpMethod.DELETE, "/api/users/**").hasAuthority("user:delete")

    // 4. 兜底
    .anyRequest().authenticated()
);
```

常用方法：

| 方法 | 含义 |
|---|---|
| `permitAll()` | 放行 |
| `denyAll()` | 一律拒绝 |
| `authenticated()` | 已认证即可 |
| `hasRole("ADMIN")` | 有 `ROLE_ADMIN` |
| `hasAnyRole("ADMIN","USER")` | 有其中之一 |
| `hasAuthority("user:add")` | 精确匹配 authority |
| `hasAnyAuthority(...)` | 有其中之一 |
| `access(String)` | 写 SpEL，如 `"hasRole('ADMIN') and hasIpAddress('10.0.0.0/8')"` |
| `access(AuthorizationManager)` | 交给自定义授权管理器（动态权限） |

> `anyRequest()` **必须放在最后**，否则后面所有规则都不会生效。

### 7.2 方法级授权

第一步：开启（Spring Security 6 的统一开关）。

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 替代旧的 @EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig { }
```

第二步：在方法上写表达式。

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping
    @PreAuthorize("hasAuthority('user:list')")
    public List<UserVO> list() { ... }

    @PostMapping
    @PreAuthorize("hasAuthority('user:add')")
    public UserVO create(@RequestBody UserCreateReq req) { ... }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete') and #id != 1L")   // 可以用方法参数！
    public void delete(@PathVariable Long id) { ... }

    // 只能看"自己"的数据，用 principal 判断
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:query') or #id == authentication.principal.id")
    public UserVO detail(@PathVariable Long id) { ... }
}
```

常用注解：

| 注解 | 时机 | 典型用法 |
|---|---|---|
| `@PreAuthorize` | 调用前 | `@PreAuthorize("hasAuthority('user:add')")` |
| `@PostAuthorize` | 调用后，基于返回值 | `@PostAuthorize("returnObject.ownerId == authentication.name")` |
| `@PreFilter` | 入参集合过滤 | `@PreFilter("filterObject.ownerId == authentication.name")` |
| `@PostFilter` | 返回值集合过滤 | `@PostFilter("filterObject.ownerId == authentication.name")` |
| `@Secured` | 调用前，只能写角色 | 需 `@EnableMethodSecurity(securedEnabled = true)` |
| `@RolesAllowed` | JSR-250 | 需 `jsr250Enabled = true` |

**底层就是 AOP**：`@EnableMethodSecurity` 会注册 `AuthorizationManagerBeforeMethodInterceptor`（一个 Advisor），原理和你的 `@Around` 切面一致。区别在于它用 SpEL 表达式、自动处理匿名/未认证、可组合 URL 层结果。

### 7.3 自定义注解：完全对应你的 `@RequirePermission`

你原来写的：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
// + @Aspect 切面里反射读取 value() 并判断
```

Spring Security 下的三种做法，**从简单到灵活**：

**做法 A：直接用 `@PreAuthorize`（推荐 90% 的场景）**

```java
@PreAuthorize("hasAuthority('user:add')")
public void addUser(...) { }
```

这已经等价于你的自定义注解，只是把"注解参数"换成了"表达式字符串"。

**做法 B：自定义注解 + 自定义 `AuthorizationManager`（保留注解写法）**

如果团队就喜欢 `@RequirePermission("user:add")` 这种写法，可以让方法级安全支持它：

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String[] value();
}
```

```java
public class RequirePermissionAuthorizationManager
        implements AuthorizationManager<MethodInvocation> {

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       MethodInvocation invocation) {
        RequirePermission annotation = AnnotationUtils.findAnnotation(
                invocation.getMethod(), RequirePermission.class);
        if (annotation == null) {
            return new AuthorizationDecision(true);   // 没标注解不拦截
        }

        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }

        Set<String> owned = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        boolean passed = Arrays.stream(annotation.value()).anyMatch(owned::contains);
        return new AuthorizationDecision(passed);
    }
}
```

注册成一个基础设施 Advisor（注意 `static` + `ROLE_INFRASTRUCTURE`，避免被当普通 Bean 代理）：

```java
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requirePermissionAdvisor() {
        Pointcut pointcut = AnnotationMatchingPointcut.forMethodAnnotation(RequirePermission.class);
        return new AuthorizationManagerBeforeMethodInterceptor(
                pointcut, new RequirePermissionAuthorizationManager());
    }
}
```

> 构造函数签名在 6.x 不同小版本略有差异。如果编译报错，改成 `new AuthorizationManagerBeforeMethodInterceptor(manager, pointcut)` 或查当前版本文档确认参数顺序。

**做法 C：动态权限表（权限点存在数据库、可运行时改）**

见第 14 节。

### 7.4 在业务代码里取当前用户

```java
// 方式 1：任意位置
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String username = auth.getName();

// 方式 2：Controller 方法参数注入（推荐）
@GetMapping("/me")
public MeVO me(@AuthenticationPrincipal UserDetails user) {
    return new MeVO(user.getUsername(), user.getAuthorities());
}

// 自定义了 UserDetails 实现的话
@GetMapping("/me2")
public MeVO me2(@AuthenticationPrincipal LoginUser user) {
    return new MeVO(user.getId(), user.getUsername());
}

// 方式 3：只要用户名
@GetMapping("/me3")
public String me3(@AuthenticationPrincipal(expression = "username") String username) {
    return username;
}
```

> 你的 `UserContext.getUserId()` 可以保留，但内部直接委托给 `SecurityContextHolder`，这样业务代码改动最小。

---

## 8. 完整落地代码：无状态 JWT + RBAC

以下是一套可以直接对照改写的骨架。

### 8.1 依赖

```xml
<!-- Spring Boot 3.x -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- JWT：使用 jjwt 0.12.x -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### 8.2 JwtUtil（签发与解析）

```java
@Component
public class JwtUtil {

    // HS256 要求密钥至少 32 字节（256 bit）
    private final SecretKey key = Keys.hmacShaKeyFor(
            "your-256-bit-secret-your-256-bit-secret".getBytes(StandardCharsets.UTF_8));
    private final long ttlMillis = 2 * 60 * 60 * 1000L;   // 2 小时

    public String generate(String username,
                           Collection<? extends GrantedAuthority> authorities) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("authorities", authorities.stream()
                        .map(GrantedAuthority::getAuthority).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

> 生产环境密钥不要硬编码，放配置中心/环境变量；长度不足 32 字节会抛 `WeakKeyException`。
> 权限要不要放进 token，是个权衡：放进去省一次查库，但"改权限后旧 token 不失效"；不放则每次请求查库。多数后台系统选择**不放权限、每次查库**（或用 Redis 缓存）。

### 8.3 JwtAuthenticationFilter（你的拦截器替代品）

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 没有 token 就放行，交给后面的 AnonymousAuthenticationFilter / AuthorizationFilter 决定
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            String username = jwtUtil.parse(token).getSubject();
            if (username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication =
                        UsernamePasswordAuthenticationToken.authenticated(
                                userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (JwtException | IllegalArgumentException e) {
            // token 非法/过期：不在这里直接写响应，清空上下文后放行，
            // 交给后面的 ExceptionTranslationFilter / AuthorizationFilter 统一处理
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}
```

**为什么不在 filter 里直接 return 401？**
可以，但那样每个 filter 都要重复写响应格式。更推荐：清空上下文后放行，让 `AuthorizationFilter` 判定"未认证"，再由 `ExceptionTranslationFilter` 调用 `AuthenticationEntryPoint` 统一输出 401。这样 401 的格式只有一处。

**⚠️ 重要坑：不要给这个 Filter 加 `@Component` 后就算了。**
Spring Boot 会把**所有 `Filter` 类型的 Bean 自动注册到 Servlet 容器**，而你又用 `addFilterBefore` 把它加进了 Security 链，结果它会执行**两次**。两种正确做法：

```java
// 做法 1：不用 @Component，在配置类里 new 出来
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(JwtUtil jwtUtil,
                                                       UserDetailsService uds) {
    return new JwtAuthenticationFilter(jwtUtil, uds);
}
// 但这个 Bean 仍会被 Boot 自动注册，所以还需要做法 2 的禁用登记… 
```

```java
// 做法 2（标准）：禁用 Boot 的自动注册
@Bean
public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
        JwtAuthenticationFilter filter) {
    FilterRegistrationBean<JwtAuthenticationFilter> registration =
            new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);   // 只由 Security 链管理
    return registration;
}
```

### 8.4 SecurityConfig（核心配置）

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint entryPoint;
    private final RestAccessDeniedHandler deniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint entryPoint,
                          RestAccessDeniedHandler deniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.entryPoint = entryPoint;
        this.deniedHandler = deniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 前后端分离 + token：关掉 CSRF（没有 Cookie 会话，CSRF 攻击面不成立）
            .csrf(AbstractHttpConfigurer::disable)

            // 启用 CORS，使用容器里的 CorsConfigurationSource Bean
            .cors(Customizer.withDefaults())

            // 无状态：不创建、不使用 HttpSession
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 关闭默认的表单登录 / HTTP Basic / 默认登录页
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)

            // 授权规则（顺序敏感）
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .anyRequest().authenticated())

            // 401 / 403 统一处理
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler))

            // 把 JWT 过滤器插到用户名密码过滤器之前
            .addFilterBefore(jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));   // 生产环境请写具体域名
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### 8.5 401 与 403 的统一响应

```java
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResult.fail(401, "未登录或登录已过期"));
    }
}
```

```java
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResult.fail(403, "没有权限执行该操作"));
    }
}
```

**401 与 403 的判定逻辑（`ExceptionTranslationFilter` 内部）**：

```
发生异常
 ├── AuthenticationException（如 token 无效） → AuthenticationEntryPoint → 401
 └── AccessDeniedException
       ├── 当前是匿名用户 → 视为"没登录" → AuthenticationEntryPoint → 401
       └── 当前已认证     → AccessDeniedHandler → 403
```

这也解释了一个常见疑惑：**已登录但权限不足才是 403；没登录访问受保护接口是 401。** 你手写时如果只抛一种异常，常常会出现"该 401 的返回了 403"。

---

## 9. 对照表：你的实现 → Spring Security 写法

| 你手写的 | Spring Security 对应 | 备注 |
|---|---|---|
| 登录接口手动查库比密码 | `authenticationManager.authenticate(...)` | 异常类型更细 |
| 密码加盐加密 / MD5 | `PasswordEncoder`（BCrypt） | 内置 |
| 查用户、查权限 | `UserDetailsService.loadUserByUsername` | 核心扩展点 |
| 自定义 `UserContext` + ThreadLocal | `SecurityContextHolder` | 用法几乎一样 |
| Token 生成 | **仍要自己写**（JWT 工具类） | 或用 OAuth2 Resource Server |
| 拦截器校验 token | 自定义 `OncePerRequestFilter` + `addFilterBefore` | 注意重复注册坑 |
| 拦截器白名单 | `requestMatchers(...).permitAll()` | 顺序敏感 |
| 拦截器里判断路径权限 | `authorizeHttpRequests(...)` | URL 级 |
| 自定义注解 + AOP | `@PreAuthorize` / 自定义 `AuthorizationManager` | 方法级 |
| 权限字符串比对 | `hasAuthority("user:add")` | SpEL |
| 角色判断 | `hasRole("ADMIN")` | 自动补 `ROLE_` |
| 全局异常返回 401/403 | `AuthenticationEntryPoint` / `AccessDeniedHandler` | 自动区分 |
| 手动清理 ThreadLocal | 框架自动清理 | `SecurityContextHolderFilter` |
| 手动处理 CORS | `.cors()` + `CorsConfigurationSource` | 在授权之前执行 |
| （没做）CSRF | `CsrfFilter` 默认开启 | API 项目可关 |
| （没做）安全响应头 | `HeaderWriterFilter` | 默认开启 |
| （没做）匿名用户 | `AnonymousAuthenticationFilter` | 便于"可选登录" |

---

## 10. 迁移路线（分阶段，能随时回退）

**不要一次性替换**，你的手写代码其实可以和新框架并存。建议按下面顺序，每步都能跑通再进下一步。

### 阶段 0：备份 + 分支

```bash
git checkout -b feature/spring-security
```

### 阶段 1：引入依赖，先让项目"什么都不拦"

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
}
```

这一步只是把 Security 挂上去，验证不影响启动、Swagger、静态资源。**此时你的旧拦截器仍在工作。**

### 阶段 2：接入 PasswordEncoder，迁移密码存储

- 新用户注册/改密码用 BCrypt。
- 老数据兼容：用 `DelegatingPasswordEncoder`，识别 `{md5}`、`{bcrypt}` 前缀，登录成功后自动升级。

```java
@Bean
public PasswordEncoder passwordEncoder() {
    String idForEncode = "bcrypt";
    Map<String, PasswordEncoder> encoders = new HashMap<>();
    encoders.put("bcrypt", new BCryptPasswordEncoder());
    encoders.put("md5", new MessageDigestPasswordEncoder("MD5")); // 仅为兼容旧数据
    return new DelegatingPasswordEncoder(idForEncode, encoders);
}
```

> `MessageDigestPasswordEncoder("MD5")` 在新版本可能被移除/弃用，实际可用 `PasswordEncoder` 自行实现一个只做 `matches` 的适配器。核心思想：**新老格式共存，登录成功时顺手重加密升级**。

### 阶段 3：实现 UserDetailsService

把"查用户 + 查权限"的逻辑搬过去（第 6.2 节）。

### 阶段 4：登录接口改用 AuthenticationManager

保留旧登录接口路径不变，内部换成 `authenticationManager.authenticate`。客户端无感。

### 阶段 5：写 JWT Filter，移除旧拦截器

- 新增 `JwtAuthenticationFilter`（第 8.3 节）。
- 在 `SecurityConfig` 里 `addFilterBefore`。
- **移除旧的 `HandlerInterceptor` 注册**（`WebMvcConfigurer.addInterceptors`）。
- 此时认证已完全由 Security 接管。

### 阶段 6：打开 URL 级授权

把旧拦截器里的白名单和路径权限，翻译成 `authorizeHttpRequests`。先只对少数模块生效，验证 401/403 行为。

### 阶段 7：方法级授权替换自定义 AOP

- 加 `@EnableMethodSecurity`。
- 业务方法上用 `@PreAuthorize`（或阶段 7b：自定义注解 + `AuthorizationManager`）。
- 删除自定义 `@Aspect` 切面和注解。
- 保留 `UserContext` 的话，让它内部委托 `SecurityContextHolder`，业务代码不用改。

### 阶段 8：统一 401/403 + 清理

- 删除旧拦截器、旧 ThreadLocal、旧切面、旧登录校验代码。
- 用第 12 节清单自查。

---

## 11. Spring Security 不替你做的事

这部分很重要，避免产生"上了框架就万事大吉"的错觉：

| 事情 | 说明 |
|---|---|
| **发 Token** | Spring Security 核心库不管 token 签发。只管"从请求里取出凭证并认证"。JWT 要你自己签，或者用 `spring-security-oauth2-resource-server` 只做**校验**（签发仍在外部的授权服务器）。 |
| **Token 注销 / 黑名单** | 无状态 JWT 天然无法主动失效。要退出/踢人，必须自己加 Redis 黑名单或 token 版本号。 |
| **刷新 Token** | 自己设计 refresh token 流程。 |
| **验证码、短信登录、扫码登录** | 自己实现，通常包装成自定义 `AuthenticationProvider` 或 `AuthenticationFilter`。 |
| **数据权限（只能看本部门数据）** | 方法级 `@PreAuthorize` 只能判断"能不能调用"，具体 SQL 的 where 条件还得自己拼（MyBatis 拦截器 / 数据权限框架）。 |
| **操作日志** | 自己写 AOP 记录。 |
| **登录日志、失败次数锁定** | 需要自己记录，或基于 Spring Security 事件/`UserDetails` 状态字段。 |
| **业务状态机权限** | 例如"审批中的单据不能删"，属于业务规则，不是安全框架范畴。 |

一句话：**Spring Security 管的是"身份与访问控制"，不是"业务权限和 token 生命周期管理"。**

---

## 12. 常见坑清单

按踩坑频率排序：

1. **`hasRole` vs `hasAuthority` 前缀**
   `hasRole("ADMIN")` 找的是 `ROLE_ADMIN`。数据库存 `ADMIN` 就会一直 403。

2. **规则顺序**
   `anyRequest()` 必须在最后；`permitAll()` 写在具体规则之后会被前面的 `authenticated()` 截胡。

3. **自定义 Filter 被注册两次**
   `@Component` 的 Filter 会被 Boot 自动注册 + 又被 `addFilterBefore` 加入安全链。用 `FilterRegistrationBean.setEnabled(false)`。

4. **`/api/**` 和 `context-path`**
   如果配置了 `server.servlet.context-path=/app`，`requestMatchers` 匹配的是**去掉 context-path 之后**的路径（`/api/...`），不要写成 `/app/api/...`。

5. **OPTIONS 预检被拦**
   没登录时浏览器先发 OPTIONS，如果被 `authenticated()` 拦掉，前端会看到 CORS 错误而非 401。要 `requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()` 并正确配置 CORS。

6. **方法级注解不生效**
   - 忘了 `@EnableMethodSecurity`；
   - 同类内部 `this.method()` 自调用不走代理；
   - 类/方法被 `final` 修饰；
   - 注解加在接口上但实现类没继承（取决于代理方式）。

7. **`@PreAuthorize` 读不到参数名**
   表达式里用 `#id` 需要参数名信息。Spring Boot 默认编译参数名保留（`-parameters`），若用 Maven 手动编译可能没有，可显式写 `@PreAuthorize("...#p0")` 或用 `@P("id")`。

8. **401 变成 403**
   前后端分离时如果没有配置 `AuthenticationEntryPoint`，匿名访问受保护资源默认可能返回 403。配置 entry point 后才是标准 401。

9. **登录后仍然 401**
   - token 过滤器没加进链；
   - Header 前缀解析错（有的写 `Bearer` 有的写 `bearer`）；
   - `SecurityContextHolder.getContext().getAuthentication()` 已有匿名认证，`if (auth == null)` 判断通过不了（应判断 `instanceof AnonymousAuthenticationToken` 或 `!isAuthenticated()`）。

10. **Session 没关干净**
    只写了 `STATELESS` 但还有别的地方用 `request.getSession()`，或用了 `SecurityContextRepository` 默认的 HttpSession 实现。无状态项目要确认不往 session 写东西。

11. **CSRF 关了但用了 Cookie 认证**
    如果你的 token 存在 Cookie 而不是 Header，关掉 CSRF 就有风险。用 Header 传 token 才可以安全地关 CSRF。

12. **密码明文比对**
    自定义 `UserDetailsService` 返回的 password 没编码，或者用 `equals` 比较。BCrypt 必须用 `matches`。

13. **密码编码器 Bean 冲突**
    容器里有多个 `PasswordEncoder` 时，`DaoAuthenticationProvider` 可能选错或启动报错。明确指定一个 `@Primary`。

14. **JWT 密钥太短**
    HS256 要求 ≥ 256 bit（32 字节），否则 `WeakKeyException`。

15. **异步/线程池里取不到用户**
    `SecurityContextHolder` 是 ThreadLocal。`@Async`、`CompletableFuture`、自定义线程池里要用 `DelegatingSecurityContextExecutor` 包装。

16. **`@PostAuthorize` 返回 500 而不是 403**
    表达式写错（如返回对象字段拼错）会抛 `SpelEvaluationException`，看起来像 500。用 DEBUG 日志排查。

17. **异常处理顺序**
    全局 `@RestControllerAdvice` 捕获不到过滤器链里的异常（因为还没进 Controller）。必须用 entry point / denied handler。

18. **`permitAll` 的静态资源被 `AuthorizationFilter` 拦**
    用 `WebSecurityCustomizer` 把静态资源彻底移出安全链更彻底（性能也更好）：
    ```java
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/favicon.ico", "/static/**");
    }
    ```

19. **多个 `SecurityFilterChain` 匹配混乱**
    声明多个链时用 `@Order` + `securityMatcher`，第一个匹配的链生效。顺序错了会导致规则不生效。

20. **升级到 Security 6 后旧教程代码编译不过**
    `WebSecurityConfigurerAdapter` 已在 5.7 弃用、6.0 移除；`authorizeRequests()` 改成 `authorizeHttpRequests()`；`antMatchers` 改成 `requestMatchers`。网上大量老教程要打折看。

---

## 13. 调试与源码阅读方法

### 13.1 打开 DEBUG 日志

```yaml
logging:
  level:
    org.springframework.security: DEBUG
```

你会看到每个请求命中了哪条 `authorizeHttpRequests` 规则、哪个过滤器链、认证结果是什么。**排查 401/403 的第一手段。**

需要更细时：

```yaml
logging:
  level:
    org.springframework.security.web.FilterChainProxy: TRACE
```

### 13.2 断点位置（按调用顺序）

| 想搞清楚 | 断点 |
|---|---|
| 请求走的是哪条安全链 | `FilterChainProxy.doFilter` |
| 上下文何时加载/清理 | `SecurityContextHolderFilter.doFilter` |
| 你的 filter 有没有执行 | 自己的 `doFilterInternal` |
| 认证内部细节 | `ProviderManager.authenticate` → `DaoAuthenticationProvider.authenticate` |
| 授权判定 | `AuthorizationFilter.doFilter` / `AuthorizationManager` 的 `check` |
| 401/403 是谁抛的 | `ExceptionTranslationFilter.doFilter` |
| 方法级授权 | `AuthorizationManagerBeforeMethodInterceptor.invoke` |

### 13.3 推荐源码阅读顺序

1. `FilterChainProxy` —— 理解"链的链"。
2. `SecurityContextHolderFilter` —— 理解上下文生命周期。
3. `AbstractAuthenticationProcessingFilter` —— 理解认证过滤器的模板方法。
4. `ProviderManager` / `DaoAuthenticationProvider` —— 理解认证编排。
5. `AuthorizationFilter` —— 理解 URL 授权。
6. `ExceptionTranslationFilter` —— 理解 401/403 转换。

读的时候对照第 5 节的流程图，一次就通了。

### 13.4 官方文档

- 参考手册：<https://docs.spring.io/spring-security/reference/>
- 迁移指南（5 → 6）：<https://docs.spring.io/spring-security/reference/migration/index.html>
- 建议看 **Servlet Applications → Authentication / Authorization** 两章，不要从头到尾读。

---

## 14. 附录：动态权限与数据权限思路

### 14.1 权限点存在数据库（可运行时修改）

`UserDetailsService` 每次登录/每次请求从库里查 authorities，天然支持动态修改——**改完库，下次请求就生效**（前提是 token 里不缓存权限，或不缓存用户信息）。

URL 级如果也想动态化，用自定义 `AuthorizationManager<RequestAuthorizationContext>`：

```java
@Component
public class DynamicUrlAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final PermissionService permissionService;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }

        String method = context.getRequest().getMethod();
        String uri = context.getRequest().getRequestURI();
        Set<String> required = permissionService.findRequiredAuthorities(method, uri);
        if (required.isEmpty()) {
            return new AuthorizationDecision(true);   // 该接口未配置权限，默认放行（按需改为 false）
        }

        Set<String> owned = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return new AuthorizationDecision(owned.containsAll(required));
    }
}
```

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()
        .anyRequest().access(dynamicUrlAuthorizationManager));
```

注意：
- 每次请求查权限表对性能有影响，务必加缓存（Caffeine / Redis）；
- "未配置的接口默认放行还是默认拒绝"是个安全策略选择，生产环境建议**默认拒绝**并使用显式白名单。

### 14.2 数据权限（只能看自己/本部门的数据）

`@PreAuthorize` 解决不了 SQL 层面的过滤。常见做法：

1. **MyBatis 拦截器**：解析 SQL，自动追加 `AND dept_id IN (...)`。
2. **在 Service 层显式传条件**：最直白，但容易漏。
3. **数据权限框架**：如 MyBatis-Plus 的数据权限插件。
4. **注解 + AOP 拼条件**：你自己设计一个 `@DataScope`，切面里把部门条件塞进查询参数。

安全框架只负责"你能不能进这个接口"，"你能看到哪些行"是**业务层**的职责。这一点在面试和架构评审里经常被追问。

### 14.3 RBAC 数据模型对齐

典型五张表：

```
sys_user          (id, username, password, status, ...)
sys_role          (id, code, name)                 -- code 如 ADMIN
sys_permission    (id, code, name, type, url, method)  -- code 如 user:add
sys_user_role     (user_id, role_id)
sys_role_permission(role_id, permission_id)
```

`UserDetailsService` 里改成一条联表查询：

```sql
SELECT CONCAT('ROLE_', r.code) AS authority
FROM sys_role r
JOIN sys_user_role ur ON ur.role_id = r.id
WHERE ur.user_id = #{userId}
UNION
SELECT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur ON ur.role_id = rp.role_id
WHERE ur.user_id = #{userId};
```

结果直接作为 `authorities`，`hasRole("ADMIN")` 和 `hasAuthority("user:add")` 都能用。

---

## 结语

你手写的这套方案，说明你已经理解了认证与授权的主干。Spring Security 的价值不在于"它有多神秘"，而在于：

1. **标准化的扩展点**（`UserDetailsService`、`PasswordEncoder`、`OncePerRequestFilter`、`AuthorizationManager`）；
2. **处理好了那些你没想到的边界**（401/403 转换、匿名用户、上下文清理、并发、异常翻译）；
3. **配置即声明**，安全规则集中可见，便于审计和代码审查。

迁移时不要推倒重来，按第 10 节分阶段替换，每一步都保持可运行、可回退。这样你既理解了原理，也能享受框架带来的工程收益。
