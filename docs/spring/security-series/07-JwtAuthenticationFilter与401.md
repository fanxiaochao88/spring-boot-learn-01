# 第 07 章 JwtAuthenticationFilter 与 401

> 本章目标：每个请求到达 Controller 之前，Security 自动从 `Authorization: Bearer <token>` 里认出当前用户。
> 没带 Token、Token 非法、Token 过期，统一返回 401 JSON。

---

## 7.1 前置检查

第 06 章完成：

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

返回里有 `data.token`。

现在访问受保护接口：

```bash
curl -i http://localhost:8080/api/users
```

因为第 03 章的 `anyRequest().permitAll()` 还在，所以现在**仍然能匿名访问**。本章会把它收回。

---

## 7.2 本章要做的三件事

1. 写 `JwtAuthenticationFilter`：从请求头解析 Token，把 `LoginUser` 放进 `SecurityContext`。
2. 写 `RestAuthenticationEntryPoint`：未登录时返回 401 JSON，而不是默认的 HTML/空响应。
3. 改 `SecurityConfig`：登录和 ping 放行，其他接口必须认证；把过滤器插到链上。

---

## 7.3 先跑通：JwtAuthenticationFilter

`src/main/java/com/keywin/security/security/JwtAuthenticationFilter.java`：

```java
package com.keywin.security.security;

import io.jsonwebtoken.Claims;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
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

        // 没带 Token：直接放行，后面 AuthorizationFilter 会拦住未认证请求
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length());

        try {
            // 一次解析，完成签名、过期、issuer 校验
            Claims claims = jwtService.parse(token);
            String username = claims.get("username", String.class);
            Long userId = Long.valueOf(claims.getSubject());

            Object versionValue = claims.get("tokenVersion");
            Integer tokenVersion = versionValue instanceof Number number
                    ? number.intValue()
                    : 0;

            // 只有当前还没有认证信息时才去认证，避免覆盖已有认证
            if (username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (userDetails instanceof LoginUser loginUser
                        && loginUser.getUserId().equals(userId)
                        && loginUser.getTokenVersion().equals(tokenVersion)
                        && loginUser.isEnabled()) {

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    loginUser, null, loginUser.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    // ★ 关键：把“当前用户”放进 SecurityContext
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            // Token 伪造/过期/格式错误，或者用户已被删除：不设置认证，继续放行
            // 后面的 AuthorizationFilter 会判定“未认证”，由 EntryPoint 返回 401
            log.debug("无效 JWT: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        // 所有分支都必须放行，否则请求会卡在过滤器里
        filterChain.doFilter(request, response);
    }
}
```

### 逐行解释

#### 为什么继承 `OncePerRequestFilter`

Servlet 的 forward / include 可能让过滤器执行多次。`OncePerRequestFilter` 用一个 request attribute 保证**一次请求只执行一次**。写自定义过滤器，无脑继承它。

#### 为什么没有 Token 就直接放行

因为公开接口（登录、ping）也需要经过这条过滤器。如果没 Token 就返回 401，登录接口就永远进不去。

正确做法：没 Token → 放行 → 后面的 `AuthorizationFilter` 根据规则判断。如果是公开接口，放行；如果是受保护接口，交给 `ExceptionTranslationFilter` → `AuthenticationEntryPoint` → 401。

#### 为什么先判断 `getAuthentication() == null`

如果前面的过滤器（比如测试里的 `@WithMockUser`）已经设置了认证信息，这里不要覆盖。

#### 为什么解析一次 Claims，而不是调用三个 extract 方法

`JwtService` 的 `extractUserId`、`extractUsername`、`extractTokenVersion` 每个都会 `parse` 一次，等于验签三次。过滤器里直接 `parse` 一次，再从 `Claims` 取字段，更高效。

#### 为什么要校验 `userId` 和 `tokenVersion`

- `userId`：防止 Token 里的 username 和 subject 被拼错。
- `tokenVersion`：数据库里的版本号和 Token 里的版本号必须一致。退出登录时数据库 +1，旧 Token 就会在这里被拒绝。这是第 11 章的基础。
- `isEnabled()`：账号被禁用后，即使旧 Token 还没过期，也不能继续访问。

#### 为什么异常要吞掉并继续放行

过滤器链里的异常**不会**经过 `ExceptionTranslationFilter`，因为 `ExceptionTranslationFilter` 在我们后面。直接抛出去只会变成一个难看的 500。

正确姿势：

1. 捕获异常。
2. 清空 `SecurityContext`。
3. 继续 `filterChain.doFilter`。
4. 让最后的 `AuthorizationFilter` 判定“未认证”。
5. `ExceptionTranslationFilter` 捕获 `AccessDeniedException` / `AuthenticationException`，交给 `EntryPoint` 输出 401。

这样 401 的格式只在一个地方维护。

---

## 7.4 为什么不在过滤器上加 `@Component`

很多教程会给 `JwtAuthenticationFilter` 加 `@Component`，然后在 `SecurityConfig` 里 `addFilterBefore`。这在某些情况下能跑，但有一个隐患：

- Spring Boot 会把容器里所有 `Filter` Bean 自动注册到 Servlet 容器。
- 你的过滤器可能被执行两次：一次在 Security 链里，一次作为普通 Servlet 过滤器。
- `OncePerRequestFilter` 能挡住第二次，但第一次可能在 `SecurityContextHolderFilter` 之前执行，导致上下文被后续过滤器清掉。

本系列的做法：

- **不给过滤器加 `@Component`**。
- 在 `SecurityConfig` 里 `new JwtAuthenticationFilter(jwtService, userDetailsService)`，只把它加进 Security 链。

这样它只是一个普通对象，不会被 Servlet 容器自动注册。

---

## 7.5 先跑通：RestAuthenticationEntryPoint

`src/main/java/com/keywin/security/security/RestAuthenticationEntryPoint.java`：

```java
package com.keywin.security.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keywin.security.common.api.Result;
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
        objectMapper.writeValue(
                response.getWriter(),
                Result.error(401, "未登录或登录已过期，请重新登录")
        );
    }
}
```

它负责一件事：**未认证时怎么响应**。

- 状态码 401
- Content-Type JSON
- 响应体 `{"code":401,"message":"未登录或登录已过期，请重新登录","data":null}`

第 08 章会再加一个 `RestAccessDeniedHandler` 负责 403。

---

## 7.6 修改 SecurityConfig

`src/main/java/com/keywin/security/config/SecurityConfig.java` **完整替换为**：

```java
package com.keywin.security.config;

import com.keywin.security.security.JwtAuthenticationFilter;
import com.keywin.security.security.JwtService;
import com.keywin.security.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 手动 new，避免 Spring Boot 把它自动注册成 Servlet 过滤器
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtService, userDetailsService);

        http
                // 无状态 + JWT 放 Header，CSRF 攻击面不存在
                .csrf(AbstractHttpConfigurer::disable)

                // 不创建 Session
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 访问规则：从上到下匹配，第一个命中的生效
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/ping").permitAll()
                        .anyRequest().authenticated())

                // 未认证时返回 401 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint))

                // 把 JWT 过滤器插到用户名密码认证之前
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
```

### 关键点

- **`requestMatchers("/api/auth/login", "/api/ping").permitAll()`**：登录和 ping 必须能匿名访问。
- **`anyRequest().authenticated()`**：其他一律要登录。
- **`SessionCreationPolicy.STATELESS`**：不创建 Session，每次请求靠 Token。
- **`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`**：把 JWT 过滤器放在表单登录过滤器之前。虽然我们不用表单登录，但这个位置合适。
- **`@RequiredArgsConstructor` + final 字段**：注入 `JwtService`、`UserDetailsService`、`EntryPoint`。

---

## 7.7 增加 `/api/auth/me`

为了验证“当前用户是谁”，加一个接口。

### 7.7.1 修改 `AuthService`

`src/main/java/com/keywin/security/modules/auth/service/AuthService.java` **完整替换为**：

```java
package com.keywin.security.modules.auth.service;

import com.keywin.security.modules.auth.dto.AuthLoginDTO;
import com.keywin.security.modules.auth.vo.AuthLoginVO;
import com.keywin.security.security.LoginUser;

public interface AuthService {

    AuthLoginVO login(AuthLoginDTO dto);

    AuthLoginVO me(LoginUser loginUser);
}
```

### 7.7.2 修改 `AuthServiceImpl`

在 `AuthServiceImpl` 里增加 `me` 方法。完整文件如下：

```java
package com.keywin.security.modules.auth.service.impl;

import com.keywin.security.modules.auth.dto.AuthLoginDTO;
import com.keywin.security.modules.auth.service.AuthService;
import com.keywin.security.modules.auth.vo.AuthLoginVO;
import com.keywin.security.security.JwtService;
import com.keywin.security.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Override
    public AuthLoginVO login(AuthLoginDTO dto) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword())
        );

        LoginUser loginUser = (LoginUser) authentication.getPrincipal();

        AuthLoginVO vo = new AuthLoginVO();
        fillUserInfo(vo, loginUser);
        vo.setToken(jwtService.generateToken(loginUser));
        vo.setTokenType("Bearer");
        vo.setExpiresIn(jwtService.getExpirationSeconds());
        return vo;
    }

    @Override
    public AuthLoginVO me(LoginUser loginUser) {
        AuthLoginVO vo = new AuthLoginVO();
        fillUserInfo(vo, loginUser);
        return vo;
    }

    private void fillUserInfo(AuthLoginVO vo, LoginUser loginUser) {
        vo.setUserId(loginUser.getUserId());
        vo.setUsername(loginUser.getUsername());
        vo.setNickname(loginUser.getNickname());
        vo.setRoles(loginUser.getRoles());
        vo.setPermissions(loginUser.getPermissions());
    }
}
```

### 7.7.3 修改 `AuthController`

`src/main/java/com/keywin/security/modules/auth/controller/AuthController.java` **完整替换为**：

```java
package com.keywin.security.modules.auth.controller;

import com.keywin.security.common.api.Result;
import com.keywin.security.modules.auth.dto.AuthLoginDTO;
import com.keywin.security.modules.auth.service.AuthService;
import com.keywin.security.modules.auth.vo.AuthLoginVO;
import com.keywin.security.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<AuthLoginVO> login(@Valid @RequestBody AuthLoginDTO dto) {
        return Result.success(authService.login(dto));
    }

    @GetMapping("/me")
    public Result<AuthLoginVO> me(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(authService.me(loginUser));
    }
}
```

`@AuthenticationPrincipal` 会从 `SecurityContext` 里取出 `Authentication.getPrincipal()`，并尝试转换成 `LoginUser`。

---

## 7.8 验证

### 7.8.1 重启应用

```bash
mvn spring-boot:run
```

### 7.8.2 公开接口仍然可用

```bash
curl -i http://localhost:8080/api/ping
```

预期 200。

### 7.8.3 不带 Token 访问受保护接口

```bash
curl -i http://localhost:8080/api/auth/me
```

预期：

```text
HTTP/1.1 401
Content-Type: application/json;charset=UTF-8

{"code":401,"message":"未登录或登录已过期，请重新登录","data":null}
```

### 7.8.4 登录拿 Token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import sys, json; print(json.load(sys.stdin)['data']['token'])")

echo "$TOKEN"
```

如果没有 Python，就手动复制 `data.token`。

### 7.8.5 带 Token 访问

```bash
curl -i http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

预期：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": 1,
    "username": "admin",
    "nickname": "超级管理员",
    "roles": ["SUPER_ADMIN"],
    "permissions": [
      "user:list", "user:add", "user:update", "user:delete",
      "role:list", "role:add", "role:update", "role:delete",
      "permission:list", "permission:add", "permission:update", "permission:delete"
    ]
  }
}
```

### 7.8.6 Token 被篡改

把 Token 最后一位改掉，再访问：

```bash
curl -i http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer ${TOKEN}x"
```

预期 401 JSON。

### 7.8.7 受保护的业务接口

```bash
curl -i http://localhost:8080/api/users \
  -H "Authorization: Bearer $TOKEN"
```

第 07 章还没有配置权限，所以只要登录就能访问，预期 200。

### 7.8.8 不带 Token 访问业务接口

```bash
curl -i http://localhost:8080/api/users
```

预期 401。

---

## 7.9 常见报错

### 1. 所有受保护接口一直 401

排查顺序：

1. 请求头是不是 `Authorization: Bearer xxx`，注意 `Bearer` 后面有一个空格。
2. `SecurityConfig` 里有没有 `addFilterBefore`。
3. 过滤器里有没有 `SecurityContextHolder.getContext().setAuthentication(...)`。
4. Token 是否过期，重新登录。
5. 打开 `logging.level.org.springframework.security: DEBUG`，看过滤器链日志。

### 2. 过滤器里抛异常，接口返回 500

原因：异常没有捕获，直接抛出过滤器链。

解决：确认 `catch (JwtException | IllegalArgumentException | UsernameNotFoundException e)`，并且捕获后仍然 `filterChain.doFilter`。

### 3. 公开接口也 401

原因：过滤器在解析一个非法 Token 时抛了异常，或者 `permitAll` 的路径写错。

解决：

- 确认请求头没有携带过期 Token。
- 确认 `requestMatchers("/api/auth/login", "/api/ping").permitAll()` 在最前面。

### 4. 过滤器执行了两次

原因：给过滤器加了 `@Component`，Spring Boot 又把它注册成了 Servlet 过滤器。

解决：去掉 `@Component`，在 `SecurityConfig` 里手动 `new`。

### 5. `ClassCastException` / `@AuthenticationPrincipal` 为 null

原因：`UserDetailsService` 返回的不是 `LoginUser`。

解决：确认 `UserDetailsServiceImpl` 返回 `new LoginUser(...)`；确认 `Authentication` 的 principal 是 `LoginUser`。

### 6. 登录接口 401

原因：登录路径没放行，或者 `AuthController` 的路径和 `requestMatchers` 不一致。

解决：确认 `@RequestMapping("/api/auth")` + `@PostMapping("/login")`，配置里是 `/api/auth/login`。

### 7. Token 过期后报 500

原因：`ExpiredJwtException` 是 `JwtException` 的子类，应该被捕获。如果报 500，检查 catch 是否写错。

---

## 7.10 本章小结

- `JwtAuthenticationFilter` 从 `Authorization: Bearer` 解析 Token，加载 `LoginUser`，放进 `SecurityContext`。
- 没 Token、Token 非法、用户被删除/禁用，都不设置认证，继续放行。
- `RestAuthenticationEntryPoint` 统一返回 401 JSON。
- `SecurityConfig` 收回 `permitAll`，只放行登录和 ping。
- 过滤器不要加 `@Component`，在配置类里手动 `new`，避免被 Servlet 容器重复注册。

### 自测题

1. 为什么没有 Token 时过滤器要放行，而不是直接返回 401？
2. 过滤器里捕获异常后，为什么还要 `filterChain.doFilter`？
3. `SecurityContextHolder` 里的认证信息是谁放进去的？
4. 为什么 `JwtAuthenticationFilter` 不加 `@Component`？
5. `tokenVersion` 在过滤器里起什么作用？

<details>
<summary>参考答案</summary>

1. 公开接口也要经过过滤器；直接 401 会让登录接口也进不去。放行后由 `AuthorizationFilter` 根据规则判断。
2. 不继续放行，请求会卡在过滤器里；放行后由后续授权阶段统一返回 401。
3. `JwtAuthenticationFilter` 在认证成功后调用 `setAuthentication()` 放进去。
4. 避免 Spring Boot 把它自动注册成 Servlet 过滤器，导致重复执行或执行顺序错误。
5. 比对数据库里的版本号，退出登录后旧 Token 版本不匹配，认证不通过。

</details>

---

下一章：[08-URL级授权与403](08-URL级授权与403.md)
