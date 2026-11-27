# Spring Security 入门教程（Spring Boot 3 + JWT 无状态版）

> 面向对象：刚写完手写登录接口、准备重构成 Spring Security 的 Spring Boot 新手
> 目标：看完能独立完成「登录接口 → Spring Security + JWT」的重构，并且知道每一行配置在干什么

---

## 目录

- [0. 先消除焦虑：这个世界没你想的那么复杂](#0-先消除焦虑这个世界没你想的那么复杂)
- [1. 心智模型：Spring Security 到底在做什么](#1-心智模型spring-security-到底在做什么)
- [2. 核心概念速查表](#2-核心概念速查表)
- [3. 第一步：引入依赖，先让它跑起来](#3-第一步引入依赖先让它跑起来)
- [4. 第二步：PasswordEncoder —— 密码不能存明文](#4-第二步passwordencoder--密码不能存明文)
- [5. 第三步：UserDetailsService —— 告诉 Security 用户从哪来](#5-第三步userdetailsservice--告诉-security-用户从哪来)
- [6. 第四步：SecurityFilterChain —— 声明"谁能访问什么"](#6-第四步securityfilterchain--声明谁能访问什么)
- [7. 第五步：改造登录接口（用 AuthenticationManager 认证）](#7-第五步改造登录接口用-authenticationmanager-认证)
- [8. 第六步：JWT 的生成与解析](#8-第六步jwt-的生成与解析)
- [9. 第七步：JWT 过滤器 —— 每个请求怎么"认人"](#9-第七步jwt-过滤器--每个请求怎么认人)
- [10. 第八步：异常处理（401 和 403 不是一回事）](#10-第八步异常处理401-和-403-不是一回事)
- [11. 第九步：授权 —— URL 级和方法级](#11-第九步授权--url-级和方法级)
- [12. 第十步：CORS、CSRF、无状态收尾](#12-第十步corscsrf无状态收尾)
- [13. 调试与测试](#13-调试与测试)
- [14. 常见坑清单（新手必踩）](#14-常见坑清单新手必踩)
- [15. 迁移对照表：你原来的写法 vs 现在的写法](#15-迁移对照表你原来的写法-vs-现在的写法)
- [16. 可以先跳过的东西 & 自测题](#16-可以先跳过的东西--自测题)
- [附录：完整文件清单](#附录完整文件清单)

---

## 0. 先消除焦虑：这个世界没你想的那么复杂

你之所以觉得复杂，通常是因为网上的资料把**三个层次**的东西混在一起讲：

| 层次 | 内容 | 你需要现在就懂吗 |
| --- | --- | --- |
| ① 框架设计 | 过滤器链、认证管理器、SecurityContext、Provider 体系 | **要懂**，这是理解一切的根 |
| ② 使用方式 | 写几个 Bean、改写登录接口、加一个过滤器 | **要会**，代码量其实很少 |
| ③ 生态扩展 | OAuth2、SAML、CAS、ACL、RememberMe、方法级权限的复杂表达式 | **先跳过**，用到再学 |

绝大多数新手被劝退，是因为一上来就在看 ③，或者在看 Spring Security 5 的旧教程（`WebSecurityConfigurerAdapter` 那套），结果和你项目里的 Spring Boot 3 完全对不上。

**本教程只讲 ① 和 ②，并且全部基于 Spring Boot 3.x / Spring Security 6.x 的新写法。**

还有一件事要提前说清楚：

> **Spring Security 不是在"帮你写登录接口"，它是"在你所有接口前面站了一排保安"。**
>
> 你要做的不是让它帮你登录，而是**告诉它：① 用户是谁（UserDetailsService）、② 密码怎么校验（PasswordEncoder）、③ 哪些门能进（SecurityFilterChain）**。剩下的拦截、上下文保存、权限判断，它全包了。

---

## 1. 心智模型：Spring Security 到底在做什么

### 1.1 一句话版本

> 每个 HTTP 请求进来，Spring Security 都会用**一串过滤器（Filter Chain）**把它过一遍：
> 先尝试从请求里认出"你是谁"（认证 Authentication），再看"你能不能访问这个地址"（授权 Authorization）。

### 1.2 过滤器链：最重要的那张图

```
HTTP 请求
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ DelegatingFilterProxy（Servlet 容器里的总入口）            │
│      │                                                   │
│      ▼                                                   │
│ FilterChainProxy（Security 自己的调度器）                  │
│      │                                                   │
│      ▼                                                   │
│ SecurityFilterChain（一串按顺序排列的 Security 过滤器）     │
│  ┌───────────────────────────────────────────────────┐   │
│  │ SecurityContextHolderFilter  ← 准备"当前用户"容器     │   │
│  │ ...                                                │   │
│  │ 你的 JwtAuthenticationFilter ← ★ 你写的，放这里      │   │
│  │ UsernamePasswordAuthenticationFilter ← 表单登录用    │   │
│  │ ...                                                │   │
│  │ ExceptionTranslationFilter ← 把认证/授权异常翻译成 401/403 │
│  │ AuthorizationFilter        ← 最后一道：判断能不能进   │   │
│  └───────────────────────────────────────────────────┘   │
│      │                                                   │
│      ▼                                                   │
│  你的 Controller                                          │
└─────────────────────────────────────────────────────────┘
```

记住三个关键点：

1. **过滤器是顺序执行的**，每个过滤器都可以决定：继续往下走（`chain.doFilter`）、直接返回、或者抛异常。
2. **你只需要往这条链里插一个自定义过滤器**（JWT 场景），用来把 Token 解析成"当前用户"。
3. **`SecurityContextHolder` 就是"当前用户"的存放点**，本质是一个 ThreadLocal。谁往里放东西，后面的代码就认为"当前登录的是这个人"。

### 1.3 认证流程：一次登录请求发生了什么

以你重构后的登录接口为例：

```
POST /api/auth/login  {username, password}
        │
        ▼
你的 Controller
        │  调用 authenticationManager.authenticate(...)
        ▼
ProviderManager（认证调度器）
        │
        ▼
DaoAuthenticationProvider
        │  ① 调用 UserDetailsService.loadUserByUsername(username) 查用户
        │  ② 用 PasswordEncoder.matches(明文, 密文) 比对密码
        ▼
认证成功 → 返回 Authentication 对象（含用户名和权限）
认证失败 → 抛 BadCredentialsException
```

### 1.4 认证之后的每个请求发生了什么（JWT 场景）

```
GET /api/user/profile   Authorization: Bearer eyJhbGciOi...
        │
        ▼
JwtAuthenticationFilter（你写的）
        │  ① 从 Header 取 token
        │  ② 解析出 username
        │  ③ loadUserByUsername 查用户
        │  ④ 校验 token 有效性
        │  ⑤ SecurityContextHolder.getContext().setAuthentication(...)
        ▼
AuthorizationFilter
        │  根据 SecurityFilterChain 的规则判断：这个请求有没有权限？
        ▼
你的 Controller（此时可以用 @AuthenticationPrincipal 拿到当前用户）
```

**这两条链路，就是 Spring Security 的 90%。剩下的都是细节。**

---

## 2. 核心概念速查表

| 概念 | 一句话解释 | 你什么时候会碰到 |
| --- | --- | --- |
| `SecurityFilterChain` | 一串过滤器 + 访问规则，用 Bean 的方式声明 | 写配置类时 |
| `Authentication` | "当前是谁 + 有什么权限"的凭证对象 | 登录后、过滤器里 |
| `AuthenticationManager` | 认证调度器，`authenticate()` 一下完成"查用户 + 验密码" | 登录接口里 |
| `UserDetails` | Spring Security 眼中的"用户"（用户名/密码/权限/是否锁定） | 实现 UserDetailsService 时 |
| `UserDetailsService` | 一个方法：`loadUserByUsername(String)`，你负责从数据库查 | 必写，就一个方法 |
| `PasswordEncoder` | 密码加密器，注册后自动用于登录校验 | 必写，一个 Bean |
| `SecurityContextHolder` | 存放当前用户 Authentication 的 ThreadLocal 容器 | 过滤器、业务代码里 |
| `GrantedAuthority` | 权限字符串，如 `ROLE_ADMIN`、`user:add` | 配置授权规则时 |
| `AuthenticationEntryPoint` | 未登录时怎么响应（返回 401） | 自定义 JSON 响应时 |
| `AccessDeniedHandler` | 已登录但没权限时怎么响应（返回 403） | 自定义 JSON 响应时 |
| `OncePerRequestFilter` | 保证一个请求只执行一次的过滤器基类 | 写 JWT 过滤器时 |
| `@EnableMethodSecurity` | 开启 `@PreAuthorize` 方法级权限 | 需要细粒度权限时 |

**记住这张表的顺序：`UserDetailsService` → `PasswordEncoder` → `SecurityFilterChain` → `AuthenticationManager` → JWT 过滤器。这是重构的施工顺序，不要跳。**

---

## 3. 第一步：引入依赖，先让它跑起来

### 3.1 pom.xml

```xml
<!-- Spring Security 核心 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWT：jjwt 0.12.x 需要同时引入这三个 -->
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

> 如果你是 Gradle，等价的 `implementation` / `runtimeOnly` 即可。

### 3.2 第一次启动你会看到什么

**只加依赖、不写任何配置**，启动应用后：

- 所有接口突然都 401 了；
- 控制台会打印一段 `Using generated security password: xxxxxxxx`。

这说明 Spring Security **默认就拦截所有请求**，并给你生成了一个临时用户 `user`。

**这是正常的，不是你的代码坏了。** 下一步我们逐步接管这些默认行为。

### 3.3 一个提醒

> Spring Security 的配置是**覆盖式**的。你写了一个 `SecurityFilterChain` Bean，默认那套（表单登录页、随机密码）就会让位。所以你会看到：配置写对之前，各种"奇怪的默认行为"；写对之后，一切安静。

---

## 4. 第二步：PasswordEncoder —— 密码不能存明文

### 4.1 为什么必须先做这个

你原来手写的登录接口，大概率是直接比较：

```java
// ❌ 原来可能是这样
if (!user.getPassword().equals(dto.getPassword())) {
    throw new BusinessException("密码错误");
}
```

这有两个问题：数据库泄露即密码泄露；而且 Spring Security 在认证时会**强制**要求一个 `PasswordEncoder`，没有它你连登录都走不通。

### 4.2 配置 Bean

```java
package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### 4.3 BCrypt 的两个特性（新手必知）

1. **不可逆**：`encode()` 之后无法 `decode()`，只能 `matches(明文, 密文)` 比对。
2. **每次加密结果都不同**（内部自带随机盐），但 `matches` 仍能判断正确性。

```java
String hash = encoder.encode("123456");
// $2a$10$N9qo8uLOickgx2ZMRZoMye...
String hash2 = encoder.encode("123456");
// $2a$10$7EqJtq98hPqEX7fNZaFWoO...  ← 不一样，但 matches 都是 true

encoder.matches("123456", hash); // true
```

### 4.4 现有数据的迁移（很关键）

你数据库里现在大概率是明文密码。**不能直接把明文丢给 BCrypt 比对，会永远失败。**

三个选择，从易到难：

| 方案 | 做法 | 适用 |
| --- | --- | --- |
| A. 重置密码 | 给测试用户统一重置为 BCrypt 密文 | 学习/开发环境，**推荐** |
| B. 兼容旧数据 | 自定义 `PasswordEncoder`，检测到非 BCrypt 前缀就按明文比 | 生产灰度 |
| C. 登录时自动升级 | 登录成功后如果是旧格式，重新 `encode` 并存回 | 生产平滑迁移 |

学习阶段直接用 A：写个一次性接口或单元测试，把测试用户的密码 `encoder.encode("123456")` 后更新进库。

```java
// 临时用一下，用完删掉
@Test
void printPassword() {
    System.out.println(new BCryptPasswordEncoder().encode("123456"));
}
```

---

## 5. 第三步：UserDetailsService —— 告诉 Security 用户从哪来

### 5.1 它只有一个方法

```java
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}
```

**你的任务：把数据库里的 User 实体，转换成 Spring Security 认识的 `UserDetails`。**

### 5.2 实现

```java
package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            // 注意：这里故意不区分"用户不存在"和"密码错误"，
            // 避免给攻击者提供"这个用户名存在"的信息
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())          // 必须是数据库里的 BCrypt 密文
                .authorities(buildAuthorities(user))   // 权限
                .accountLocked(user.getLocked() == 1)  // 可选
                .disabled(user.getStatus() == 0)       // 可选
                .build();
    }

    private String[] buildAuthorities(User user) {
        // 角色统一加 ROLE_ 前缀，原因见 11.2
        return user.getRoles().stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .toArray(String[]::new);
    }
}
```

### 5.3 两种"用户对象"的区别

| 类型 | 用途 |
| --- | --- |
| 你的 `User` 实体 | 数据库表映射，字段你说了算 |
| `org.springframework.security.core.userdetails.User` | Security 内部使用的标准用户 |
| 自定义 `implements UserDetails` | 想在 Controller 里直接拿业务字段时用 |

新手阶段用内置 `User` 就够了。如果后面你想在 Controller 里直接拿到 `userId`、`nickname`，再改成自定义 `UserDetails`（附录里给了示例）。

### 5.4 为什么 `UsernameNotFoundException` 也被统一成"用户名或密码错误"

`DaoAuthenticationProvider` 内部会把 `UsernameNotFoundException` 统一转换成 `BadCredentialsException`，防止账号枚举攻击。所以业务上你不用特别处理。

---

## 6. 第四步：SecurityFilterChain —— 声明"谁能访问什么"

这是整个配置的核心文件。

```java
package com.example.demo.config;

import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.RestAccessDeniedHandler;
import com.example.demo.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity          // 开启 @PreAuthorize，见第 11 节
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ① 无状态 + 无 Cookie，CSRF 攻击面不存在，可以关闭
            .csrf(AbstractHttpConfigurer::disable)

            // ② 不要创建 Session：每次请求都靠 JWT 证明身份
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ③ 访问规则：从上到下匹配，第一个命中的生效
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/public/**").permitAll()
                    .anyRequest().authenticated())   // 其他一律要登录

            // ④ 把异常翻译成 JSON（见第 10 节）
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(authenticationEntryPoint)  // 401
                    .accessDeniedHandler(accessDeniedHandler))           // 403

            // ⑤ 把自定义 JWT 过滤器插到"用户名密码认证"之前
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 暴露 AuthenticationManager 给登录接口使用。
     * Spring Boot 检测到 UserDetailsService + PasswordEncoder 两个 Bean 后，
     * 会自动装配 DaoAuthenticationProvider，这里只是把它取出来。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }
}
```

### 6.1 这段配置逐行在说什么

- **`requestMatchers(...)` 的顺序很重要**：从上往下，第一个匹配的规则生效。所以放行规则要写在 `anyRequest().authenticated()` 之前。
- **`permitAll()`**：谁都能访问。
- **`authenticated()`**：必须已登录。
- **`hasRole("ADMIN")`**：要求权限是 `ROLE_ADMIN`（会自动补 `ROLE_` 前缀）。
- **`hasAuthority("user:add")`**：要求权限字符串**精确等于** `user:add`（不补前缀）。

### 6.2 静态资源 / Knife4j / Swagger

如果这些路径被拦了，补上放行：

```java
.requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**", "/favicon.ico").permitAll()
```

### 6.3 一个常见误解

> "我配置了 `permitAll`，为什么还是 401？"

大概率是你的 JWT 过滤器在解析一个**非法 Token** 时抛了异常，直接中断了请求，根本没走到授权阶段。处理方式见第 9.3 节。

---

## 7. 第五步：改造登录接口（用 AuthenticationManager 认证）

### 7.1 你原来的登录接口大致长这样

```java
// ❌ 原来：自己查库、自己比密码、自己生成 token
@PostMapping("/login")
public Result<LoginVO> login(@RequestBody LoginDTO dto) {
    User user = userMapper.selectByUsername(dto.getUsername());
    if (user == null || !user.getPassword().equals(dto.getPassword())) {
        throw new BusinessException("用户名或密码错误");
    }
    String token = jwtUtil.generate(user);
    return Result.success(new LoginVO(token));
}
```

问题：校验逻辑散落、没有统一异常、没有权限体系、以后加"账号锁定""密码过期"都得改这里。

### 7.2 现在改成这样

```java
package com.example.demo.controller;

import com.example.demo.dto.LoginDTO;
import com.example.demo.security.JwtService;
import com.example.demo.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        // ① 交给 Security 去"查用户 + 验密码"
        //    失败会抛 BadCredentialsException / DisabledException 等
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword())
        );

        // ② 认证成功后，principal 就是 UserDetailsServiceImpl 返回的对象
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        // ③ 签发 JWT
        String token = jwtService.generateToken(userDetails);
        return Result.success(new LoginVO(token, "Bearer", jwtService.getExpiration()));
    }
}
```

### 7.3 对比一下你少写了什么

| 原来 | 现在 |
| --- | --- |
| 手动查用户 | `UserDetailsService` 统一负责 |
| 手动 `equals` 比密码 | `PasswordEncoder.matches` 自动比对 |
| 手动判断账号状态 | `UserDetails` 的 `disabled/locked` 自动生效 |
| 各处 `throw new BusinessException` | 统一抛 `AuthenticationException` 家族 |
| 权限自己判断 | 交给 Security |

### 7.4 登录失败怎么返回友好提示

`authenticationManager.authenticate()` 抛出的异常发生在 **Controller 里**，所以能被 `@RestControllerAdvice` 正常捕获：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public Result<Void> handleAuthException(AuthenticationException e) {
        return Result.error(401, "用户名或密码错误");
    }

    @ExceptionHandler(DisabledException.class)
    public Result<Void> handleDisabled(DisabledException e) {
        return Result.error(403, "账号已被禁用");
    }

    @ExceptionHandler(LockedException.class)
    public Result<Void> handleLocked(LockedException e) {
        return Result.error(403, "账号已被锁定");
    }
}
```

> **注意区分**：这里的异常来自 Controller 内部调用，能被全局异常处理器捕获；而过滤器链里的异常**不会**，必须交给第 10 节的 `EntryPoint` / `AccessDeniedHandler`。这是新手最容易混淆的一点。

---

## 8. 第六步：JWT 的生成与解析

### 8.1 先理解 JWT 是什么

JWT = `Header.Payload.Signature` 三段 Base64：

```
eyJhbGciOiJIUzI1NiJ9  .  eyJzdWIiOiJhZG1pbiIsImV4cCI6...}  .  签名
       头（算法）              载荷（用户名、过期时间、自定义字段）        防篡改
```

- **载荷是明文可读的**（Base64 不是加密），**绝对不要放密码**。
- 服务端不存储 Token，只靠签名验证它没被改过、没过期 —— 这就是"无状态"。
- 缺点：**无法主动失效**（签发后到期前一直有效）。需要"退出登录立即失效"时，得配 Redis 黑名单（进阶再说）。

### 8.2 application.yml

```yaml
jwt:
  # 至少 32 个字符（HS256 要求 >= 256 bit），生产环境放环境变量
  secret: my-super-secret-key-change-it-in-production-123456
  # 过期时间：毫秒
  expiration: 86400000   # 24 小时
```

### 8.3 JwtService

```java
package com.example.demo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Getter
    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 Token */
    public String generateToken(UserDetails userDetails) {
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Jwts.builder()
                .subject(userDetails.getUsername())      // 标准字段
                .claim("authorities", authorities)       // 自定义字段
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())               // 0.12.x：算法自动根据 key 推断
                .compact();
    }

    /** 取出用户名（顺带完成签名与过期校验，非法会抛 JwtException） */
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /** 校验 token 是否属于该用户且未过期 */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        Claims claims = parseClaims(token);
        return claims.getSubject().equals(userDetails.getUsername())
                && claims.getExpiration().after(new Date());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

### 8.4 版本提醒

网上大量教程用的是 `jjwt 0.11.x` 的旧 API：

```java
// 旧写法（0.11.x），新版本已废弃
Jwts.builder().setSubject(...).signWith(key, SignatureAlgorithm.HS256)
Jwts.parserBuilder().setSigningKey(key).build()
```

本教程用的是 **0.12.x**：`subject()` / `claim()` / `parseSignedClaims()`。如果你的依赖版本不同，别混用，会编译不过。

---

## 9. 第七步：JWT 过滤器 —— 每个请求怎么"认人"

### 9.1 代码

```java
package com.example.demo.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);

        // 没有 Token → 直接放行，最后会被 anyRequest().authenticated() 拦下
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length());

        try {
            String username = jwtService.extractUsername(token);

            // 只有当前还没有认证信息时才去认证，避免重复覆盖
            if (username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (jwtService.isTokenValid(token, userDetails)) {
                    // ★ 关键一行：把"当前用户"放进 SecurityContext
                    var authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            // Token 伪造/过期/格式错误：不设置认证，继续放行
            // 后续 AuthorizationFilter 会因为"未认证"而拒绝，由 EntryPoint 返回 401
            log.debug("无效 JWT: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
```

### 9.2 为什么是 `OncePerRequestFilter`

Servlet 的请求转发（forward）可能让过滤器执行多次，这个基类通过一个 request attribute 保证**一次请求只执行一次**。写自定义过滤器，无脑继承它。

### 9.3 为什么捕获异常后"继续放行"而不是直接 return 401

因为过滤器链里的异常**不会**经过 `ExceptionTranslationFilter`（它在那条链的靠后位置），直接抛出去只会变成一个难看的 500。

正确姿势：**吞掉异常、不设置认证、继续往下走**，让最后的 `AuthorizationFilter` 判定"未认证"，再由 `ExceptionTranslationFilter` 交给 `EntryPoint` 输出 401。这样 401 的格式和逻辑只在一个地方维护。

### 9.4 SecurityContext 的"有效期"

`SecurityContextHolder` 默认是 ThreadLocal，**只在当前请求线程内有效**。请求结束就清空。

这意味着：

```java
// ✅ 在 Controller 里可以拿到
Authentication auth = SecurityContextHolder.getContext().getAuthentication();

// ❌ 丢到线程池/异步任务里就没了
CompletableFuture.runAsync(() -> {
    SecurityContextHolder.getContext().getAuthentication(); // null
});
```

需要跨线程传递时，用 Spring Security 提供的包装器：

```java
executor.submit(new DelegatingSecurityContextRunnable(() -> {
    // 这里能拿到当前用户
}));
```

### 9.5 更优雅的取值方式

```java
// 直接拿 UserDetails
@GetMapping("/me")
public Result<?> me(@AuthenticationPrincipal UserDetails userDetails) {
    return Result.success(userDetails.getUsername());
}
```

前提是这个接口已经被认证（`anyRequest().authenticated()` 已经保证）。

---

## 10. 第八步：异常处理（401 和 403 不是一回事）

### 10.1 一句话区分

| 状态码 | 含义 | 触发场景 | 处理组件 |
| --- | --- | --- | --- |
| **401 Unauthorized** | **你是谁我不知道** | 没带 Token / Token 过期 / Token 非法 | `AuthenticationEntryPoint` |
| **403 Forbidden** | **我知道你是谁，但你没资格** | 普通用户访问 `/api/admin/**` | `AccessDeniedHandler` |

新手最常犯的错：把 401 和 403 混用，前端无法区分"请去登录"和"弹无权限提示"。

### 10.2 RestAuthenticationEntryPoint

```java
package com.example.demo.security;

import com.example.demo.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.error(401, "未登录或登录已过期，请重新登录"));
    }
}
```

### 10.3 RestAccessDeniedHandler

```java
package com.example.demo.security;

import com.example.demo.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.error(403, "权限不足，禁止访问"));
    }
}
```

### 10.4 特殊场景：匿名用户被拒绝时为什么是 403？

如果开启 `anonymous`（默认开启），未登录用户会被当成 `anonymousUser`。某些情况下 `AccessDeniedHandler` 会发现当前是匿名身份，然后**转交** `EntryPoint` 返回 401 —— 这是框架内部的智能处理，理解这一点就不会被"明明没改代码，401/403 变了"搞晕。

---

## 11. 第九步：授权 —— URL 级和方法级

### 11.1 URL 级（在 SecurityFilterChain 里）

适合粗粒度：某个路径需要某种角色。

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/admin/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.POST, "/api/article/**").hasAnyRole("ADMIN", "EDITOR")
    .requestMatchers("/api/user/**").authenticated()
    .anyRequest().permitAll())
```

### 11.2 `hasRole` vs `hasAuthority`（必踩坑）

| 写法 | 实际匹配的权限字符串 |
| --- | --- |
| `hasRole("ADMIN")` | `ROLE_ADMIN` |
| `hasAuthority("ROLE_ADMIN")` | `ROLE_ADMIN` |
| `hasAuthority("admin")` | `admin`（**不会**补前缀） |

**结论**：数据库里存 `ADMIN`，用 `hasRole("ADMIN")`；存 `ROLE_ADMIN`，两个都行，但保持一致。混用是 403 的头号原因。

### 11.3 方法级（`@PreAuthorize`）

先在配置类加 `@EnableMethodSecurity`，然后：

```java
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/user/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        ...
    }

    // 细粒度权限：适合"菜单/按钮级"权限
    @PreAuthorize("hasAuthority('user:add')")
    @PostMapping("/user")
    public Result<Void> addUser(@RequestBody UserDTO dto) {
        ...
    }

    // 只能操作自己的数据
    @PreAuthorize("#userId == authentication.principal.username")
    @GetMapping("/profile/{userId}")
    public Result<?> profile(@PathVariable String userId) {
        ...
    }
}
```

> 方法级权限抛出的 `AccessDeniedException` 会由 `@RestControllerAdvice` 捕获（因为发生在 Controller 方法调用时），**不会**走 `AccessDeniedHandler`。两边都要有处理，才不会出现"有的 403 是 JSON，有的 403 是 HTML"。

```java
@ExceptionHandler(AccessDeniedException.class)
public Result<Void> handleAccessDenied(AccessDeniedException e) {
    return Result.error(403, "权限不足");
}
```

### 11.4 自定义权限注解（如果你之前写过 AOP 权限注解）

Spring Security 的 `@PreAuthorize` 已经覆盖了绝大多数场景。如果团队已有自定义注解，可以在注解内部改用 `SecurityContextHolder` 取用户，或者直接用 `@PreAuthorize("@permissionChecker.has('user:add')")` 调用自定义 Bean —— 后者比手写 AOP 更标准。

---

## 12. 第十步：CORS、CSRF、无状态收尾

### 12.1 CORS（前后端分离必配）

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(List.of("*"));   // 生产环境写具体域名
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

然后在 `SecurityFilterChain` 里加：

```java
.cors(Customizer.withDefaults())
```

> ⚠️ `allowCredentials(true)` 时不能用 `setAllowedOrigins("*")`，必须用 `setAllowedOriginPatterns("*")` —— 这是 Spring 的硬性限制，报错信息非常迷惑。

### 12.2 CSRF 为什么可以关

CSRF 攻击依赖**浏览器自动携带 Cookie**。JWT 放在 `Authorization` 头里，浏览器不会自动带，所以天然免疫。

但如果你把 Token 存在 Cookie 里（不推荐），就必须开启 CSRF 防护。

### 12.3 无状态为什么重要

`SessionCreationPolicy.STATELESS` 让每次请求都不创建 Session，意味着：

- 服务器可以随便横向扩容，不需要 Session 共享；
- 登录状态完全由 Token 承载；
- **代价**：无法服务端主动踢人（除非加黑名单/版本号）。

---

## 13. 调试与测试

### 13.1 用 curl 快速验证

```bash
# 1. 登录拿 Token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'

# 2. 带 Token 访问受保护接口
curl http://localhost:8080/api/user/profile \
  -H "Authorization: Bearer eyJhbGciOi..."

# 3. 不带 Token，应该返回 401 JSON
curl -i http://localhost:8080/api/user/profile

# 4. 普通用户访问 admin 接口，应该返回 403 JSON
```

### 13.2 打开 Security 调试日志

```yaml
logging:
  level:
    org.springframework.security: DEBUG
```

启动后你会看到请求依次经过了哪些过滤器、授权决策是什么 —— **排查问题的第一手段**。想看更细的过滤器链，把 `DEBUG` 改成 `TRACE`。

### 13.3 单元测试：MockMvc + @WithMockUser

```java
@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCanDelete() throws Exception {
        mockMvc.perform(delete("/api/admin/user/1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    void normalUserForbidden() throws Exception {
        mockMvc.perform(delete("/api/admin/user/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/admin/user/1"))
                .andExpect(status().isUnauthorized());
    }
}
```

> `@WithMockUser` 会跳过你的 JWT 过滤器直接伪造认证信息，专注测授权逻辑，非常方便。

---

## 14. 常见坑清单（新手必踩）

| # | 现象 | 原因 | 解决 |
| --- | --- | --- | --- |
| 1 | 所有接口 401 | 只加了依赖没写配置 | 写 `SecurityFilterChain` |
| 2 | 登录永远失败 | 数据库存的是明文密码 | 用 BCrypt 重新编码存储 |
| 3 | 报 `There is no PasswordEncoder mapped for the id "null"` | 没配 `PasswordEncoder` Bean | 第 4 节 |
| 4 | 配置了 `permitAll` 还是 401 | 自定义过滤器抛异常中断了请求 | 过滤器内 try-catch，放行让 EntryPoint 处理 |
| 5 | 有权限却 403 | `hasRole("ADMIN")` 但库里存的是 `ADMIN` 而 `UserDetails` 没加前缀 | 第 11.2 节，统一前缀 |
| 6 | 401/403 返回 HTML | 没配 `EntryPoint` / `AccessDeniedHandler` | 第 10 节 |
| 7 | 异步/线程池里拿不到用户 | `SecurityContextHolder` 是 ThreadLocal | `DelegatingSecurityContextRunnable` |
| 8 | 跨域报错且带 `allowCredentials(true)` | 用了 `allowedOrigins("*")` | 改 `allowedOriginPatterns("*")` |
| 9 | Swagger 打不开 | 路径被拦截 | 加入 `permitAll` 白名单 |
| 10 | 升级 Spring Boot 3 后编译不过 | 还在用 `WebSecurityConfigurerAdapter` / `antMatchers` | 全部改成 `SecurityFilterChain` / `requestMatchers` |
| 11 | JWT 编译报错 | 用的 0.12.x 依赖却抄了 0.11.x API | 第 8.4 节 |
| 12 | Token 明明有效却认证不上 | `OncePerRequestFilter` 里没有 `chain.doFilter` | 所有分支都要放行 |
| 13 | 退出登录后 Token 还能用 | JWT 无状态 | 加 Redis 黑名单或短期 Token + Refresh Token |
| 14 | 密码校验 `matches` 永远 false | 比对时传反了参数 | `matches(明文, 密文)` |

---

## 15. 迁移对照表：你原来的写法 vs 现在的写法

| 能力 | 你原来手写 | Spring Security 重构后 |
| --- | --- | --- |
| 查用户 | `userMapper.selectByUsername()` 写在登录接口里 | `UserDetailsServiceImpl` 统一实现 |
| 比对密码 | `equals()` | `PasswordEncoder.matches()` |
| 登录 | Controller 里手动判断 | `authenticationManager.authenticate()` |
| 登录态 | 自己发 token / 存 Session | JWT + `JwtAuthenticationFilter` |
| 拦截接口 | 自己写拦截器 + 注解 | `SecurityFilterChain` 声明式配置 |
| 权限判断 | `if (user.getRole()...)` | `hasRole()` / `@PreAuthorize` |
| 当前用户 | `ThreadLocal` 自己维护 | `SecurityContextHolder` / `@AuthenticationPrincipal` |
| 未登录响应 | 自己写 | `AuthenticationEntryPoint` |
| 无权限响应 | 自己写 | `AccessDeniedHandler` |
| 账号锁定/禁用 | 自己判断 | `UserDetails` 的 `locked/disabled` |

**你删掉的代码会比你新写的多得多。** 这就是框架的意义。

---

## 16. 可以先跳过的东西 & 自测题

### 16.1 现在完全不用管

- OAuth2 / 第三方登录（`spring-boot-starter-oauth2-client`）
- SAML / CAS 单点登录
- ACL（对象级权限）
- RememberMe
- Session 并发控制、集群 Session 共享
- CSRF Token 的具体机制（你用 JWT，用不到）
- `AuthenticationProvider` 自定义实现（99% 的项目用 DaoAuthenticationProvider 就够）

### 16.2 自测题（能答上来就算入门）

1. 一个请求进来，`SecurityContextHolder` 里的内容是谁放进去的？
2. `UserDetailsService` 和 `UserDetails` 分别负责什么？
3. 401 和 403 分别由哪个组件产生？
4. `hasRole("ADMIN")` 实际匹配的权限字符串是什么？
5. 为什么 JWT 过滤器里的异常不能直接往外抛？
6. `permitAll` 的接口还需要 JWT 过滤器处理吗？为什么？
7. 用户退出登录后，旧的 JWT 为什么仍然有效？怎么解决？
8. `@PreAuthorize` 抛出的异常为什么走 `@RestControllerAdvice` 而不是 `AccessDeniedHandler`？

<details>
<summary>参考答案</summary>

1. `JwtAuthenticationFilter`（或任何认证过滤器）在认证成功后调用 `setAuthentication()` 放进去。
2. `UserDetailsService` 负责"怎么查用户"；`UserDetails` 是查出来后的"用户模型"（用户名/密码/权限/状态）。
3. 401 → `AuthenticationEntryPoint`；403 → `AccessDeniedHandler`（方法级权限的 403 则走全局异常处理器）。
4. `ROLE_ADMIN`。
5. 过滤器链中的自定义过滤器位于 `ExceptionTranslationFilter` 之前，异常不会被它捕获，会变成 500。应捕获后放行，交给后续授权阶段处理。
6. 需要经过（它在链上），但过滤器发现没有 Token 会直接放行。
7. 因为 JWT 无状态，服务端不存会话。解决：Redis 黑名单、Token 版本号、或缩短有效期 + Refresh Token。
8. 方法级权限在 Controller 方法调用时触发，异常发生在 Spring MVC 的调用栈内，能被 `@RestControllerAdvice` 捕获。
</details>

---

## 附录：完整文件清单

```
src/main/java/com/example/demo/
├── config/
│   ├── PasswordConfig.java          # PasswordEncoder Bean
│   ├── SecurityConfig.java          # SecurityFilterChain + AuthenticationManager
│   └── CorsConfig.java              # 可选
├── security/
│   ├── UserDetailsServiceImpl.java  # 查用户
│   ├── JwtService.java              # 生成/解析 JWT
│   ├── JwtAuthenticationFilter.java # 每请求认证
│   ├── RestAuthenticationEntryPoint.java  # 401
│   └── RestAccessDeniedHandler.java       # 403
├── controller/
│   └── AuthController.java          # 登录接口
├── common/
│   ├── Result.java                  # 统一响应
│   └── GlobalExceptionHandler.java  # 全局异常（AuthenticationException / AccessDeniedException）
└── resources/
    └── application.yml              # jwt.secret / jwt.expiration
```

### 实施顺序（建议按这个顺序写，每步都能启动验证）

1. 加依赖 → 启动，观察默认拦截（3.2）
2. 写 `PasswordEncoder`（第 4 节）
3. 写 `UserDetailsServiceImpl`（第 5 节）
4. 写最小 `SecurityFilterChain`，先只放行登录接口（第 6 节）
5. 改造登录接口，改用 `AuthenticationManager`，**此时不带 JWT 也能登录**（第 7 节）
6. 加 `JwtService`，登录成功后返回 Token（第 8 节）
7. 加 `JwtAuthenticationFilter` 并注册到链上（第 9 节）
8. 加 `EntryPoint` / `AccessDeniedHandler`（第 10 节）
9. 加方法级权限和测试（第 11、13 节）

**第 5 步做完就能跑通登录**，这是最好的阶段性里程碑 —— 先让认证工作，再让授权工作，最后美化错误响应。不要一次性全写完再启动，出了问题很难定位。

---

> 📌 一句话总结全文：
> **Spring Security = 一条过滤器链 + 一个 SecurityContext。**
> 你要做的只有四件事：**查用户（UserDetailsService）、比密码（PasswordEncoder）、放 Token 进上下文（JwtAuthenticationFilter）、声明谁能进哪扇门（SecurityFilterChain）。**
