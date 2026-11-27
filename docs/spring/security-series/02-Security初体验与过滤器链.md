# 第 02 章 Security 初体验与过滤器链

> 本章目标：只加一个依赖，亲眼看到“所有接口突然 401”，然后搞明白这不是 bug，而是 Spring Security 的默认行为。

---

## 2.1 前置检查

第 01 章完成：

```bash
curl -i http://localhost:8080/api/ping
```

应该返回：

```text
HTTP/1.1 200
{"status":"ok"}
```

如果这里就不通，先回到第 01 章。

---

## 2.2 加依赖

打开 `pom.xml`，在 `<dependencies>` 里加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

**不要写版本号。** Spring Boot 父 POM 会管理 Spring Security 的版本，当前 Boot 3.2.4 对应 Security 6.2.x。

加完后，IDEA 右上角刷新 Maven，或者命令行：

```bash
mvn -q dependency:resolve
```

---

## 2.3 重启，观察三个现象

重启应用，然后执行：

```bash
curl -i http://localhost:8080/api/ping
```

你会看到类似：

```text
HTTP/1.1 401
WWW-Authenticate: Basic realm="Realm"
Content-Type: application/json

{"timestamp":"2026-11-27T10:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/ping"}
```

三个现象：

1. **所有接口都 401**，包括刚才能访问的 `/api/ping`。
2. 响应头多了 `WWW-Authenticate: Basic realm="Realm"`。
3. 控制台多了一行：

```text
Using generated security password: 4f8c2c1e-9a7b-4d3e-8f21-2b6a1c9d0e77
```

这不是你的代码坏了。这是 Spring Security 在说：

> “我接管了所有请求。你还没告诉我谁能进，所以我先全部拦下。同时我给你生成了一个临时用户，用户名 `user`，密码就是控制台这串。”

---

## 2.4 用默认用户验证

把控制台那串密码复制下来（每次重启都会变），执行：

```bash
curl -i -u user:4f8c2c1e-9a7b-4d3e-8f21-2b6a1c9d0e77 http://localhost:8080/api/ping
```

预期：

```text
HTTP/1.1 200
{"status":"ok"}
```

再试一个错误密码：

```bash
curl -i -u user:wrong http://localhost:8080/api/ping
```

预期：

```text
HTTP/1.1 401
WWW-Authenticate: Basic realm="Realm"
```

这说明：**接口没坏，是 Security 在拦。认证通过就放行。**

### 浏览器里会看到登录页

用浏览器打开 <http://localhost:8080/api/ping>，你会被重定向到 `/login`，看到一个 Spring Security 自带的登录页。

为什么 curl 是 401，浏览器是登录页？

- 浏览器请求头带 `Accept: text/html`，Security 认为你想要页面，于是走表单登录，302 到 `/login`。
- curl 默认不带 HTML 的 `Accept`，Security 认为你要 API 响应，于是走 Basic 认证，返回 401。

这背后是 `ExceptionTranslationFilter` 和 `AuthenticationEntryPoint` 在选择“未登录时怎么响应”。第 07 章我们会把它换成统一的 JSON 401。

---

## 2.5 过滤器链：Security 到底在哪一层

一个 HTTP 请求进入 Spring Boot 后，大概经过：

```text
HTTP 请求
   │
   ▼
┌─────────────────────────────────────────────────────────────┐
│ Servlet 容器（Tomcat）                                        │
│   │                                                         │
│   ▼                                                         │
│ DelegatingFilterProxy  ← Servlet 过滤器，交给 Spring 管理      │
│   │                                                         │
│   ▼                                                         │
│ FilterChainProxy       ← Security 的总调度器                  │
│   │                                                         │
│   ▼                                                         │
│ SecurityFilterChain    ← 一串按顺序执行的 Security 过滤器       │
│   ┌───────────────────────────────────────────────────────┐ │
│   │ SecurityContextHolderFilter  准备“当前用户”容器          │ │
│   │ HeaderWriterFilter           写安全响应头                │ │
│   │ CsrfFilter                    CSRF 防护                  │ │
│   │ LogoutFilter                  处理 /logout               │ │
│   │ UsernamePasswordAuthenticationFilter  表单登录           │ │
│   │ BasicAuthenticationFilter     Basic 认证                 │ │
│   │ AnonymousAuthenticationFilter 匿名用户                   │ │
│   │ ExceptionTranslationFilter    把异常翻译成 401/403       │ │
│   │ AuthorizationFilter           最后一道：判断能不能进       │ │
│   └───────────────────────────────────────────────────────┘ │
│   │                                                         │
│   ▼                                                         │
│ DispatcherServlet → 你的 Controller                          │
└─────────────────────────────────────────────────────────────┘
```

记住三句话：

1. **过滤器是按顺序执行的。** 每个过滤器都可以决定：继续往下走（`chain.doFilter`）、直接返回、或者抛异常。
2. **`SecurityContextHolder` 是“当前用户”的存放点。** 本质是一个 `ThreadLocal`。谁往里面放 `Authentication`，后面的代码就认为“当前登录的是这个人”。
3. **`AuthorizationFilter` 是最后一道门。** 它根据你的 `SecurityFilterChain` 规则判断：这个请求有没有权限。没权限就抛 `AccessDeniedException`，由前面的 `ExceptionTranslationFilter` 翻译成 403。

第 07 章我们会往这条链里插一个自己的 `JwtAuthenticationFilter`，位置在 `UsernamePasswordAuthenticationFilter` 之前。

---

## 2.6 为什么默认是表单登录 + Basic

你只加了依赖，没写任何配置，为什么就出现了登录页和 Basic 认证？

因为 Spring Boot 的自动配置：

- `SecurityAutoConfiguration` 检测到 classpath 有 Spring Security
- 你没有自定义 `SecurityFilterChain` Bean
- 于是 `SpringBootWebSecurityConfiguration` 提供一个默认链：
  - 所有请求都需要认证
  - 开启表单登录（`formLogin`）
  - 开启 Basic 认证（`httpBasic`）
  - 生成一个内存用户 `user` 和随机密码

一旦你自己写了一个 `SecurityFilterChain` Bean，这套默认行为就会让位。第 03 章我们会写第一个自定义配置。

---

## 2.7 本章为什么不写配置

因为你需要先看见“默认行为长什么样”。很多新手一上来就抄一大段 `SecurityConfig`，结果：

- 不知道哪一行在起作用
- 不知道删掉哪一行会怎样
- 出问题时不知道该看哪个过滤器

先看见 401，再一步步接管，后面每一行配置你都能对上一个具体现象。

---

## 2.8 验证清单

| 验证 | 命令 | 预期 |
| --- | --- | --- |
| 未认证访问 | `curl -i http://localhost:8080/api/ping` | 401 + `WWW-Authenticate` |
| 默认用户正确密码 | `curl -i -u user:控制台密码 http://localhost:8080/api/ping` | 200 |
| 默认用户错误密码 | `curl -i -u user:wrong http://localhost:8080/api/ping` | 401 |
| 浏览器访问 | 打开 `http://localhost:8080/api/ping` | 跳转 `/login` |
| 控制台 | 看启动日志 | `Using generated security password: ...` |

---

## 2.9 常见误解

### 误解一：“401 是我的接口报错”

不是。看响应体：如果是 `{"timestamp":...,"status":401,"error":"Unauthorized","path":"/api/ping"}`，这是 Spring Boot 的默认错误响应，说明请求根本没进你的 Controller。

### 误解二：“随机密码是 bug，应该关掉”

不是。它是默认内存用户的密码，方便你快速验证。等你自己配置了 `UserDetailsService`，它就不会再出现。

### 误解三：“加了 Security 就必须马上写一大段配置”

不必。你可以先让它保持默认，观察行为，再逐步接管。本教程就是这么做的。

### 误解四：“`WWW-Authenticate` 是业务返回的”

不是。它是 `BasicAuthenticationEntryPoint` 写的响应头，告诉客户端“请用 Basic 认证”。第 07 章换成 JSON 401 后，这个头就没了。

---

## 2.10 本章小结

- 加 `spring-boot-starter-security` 后，默认拦截所有请求。
- 默认生成用户 `user` 和随机密码，支持表单登录和 Basic 认证。
- 请求会经过 `DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain → Controller`。
- `SecurityContextHolder` 是当前用户的 ThreadLocal 容器。
- 本章不写配置，是为了先看清默认行为。

### 自测题

1. 加了 Security 依赖后，为什么 `/api/ping` 返回 401？
2. curl 得到 401，浏览器却跳登录页，原因是什么？
3. `SecurityContextHolder` 的本质是什么？请求结束后还在吗？
4. 哪一行日志说明默认用户被创建了？

<details>
<summary>参考答案</summary>

1. 因为默认 `SecurityFilterChain` 要求所有请求都认证，而请求没有携带任何凭证。
2. 浏览器 `Accept` 包含 `text/html`，走表单登录入口，302 到 `/login`；curl 默认不是 HTML，走 Basic 认证入口，返回 401。
3. 本质是 `ThreadLocal`，只在当前请求线程内有效，请求结束会被清理。
4. `Using generated security password: ...`。

</details>

---

下一章：[03-统一响应与用户模块CRUD](03-统一响应与用户模块CRUD.md)
