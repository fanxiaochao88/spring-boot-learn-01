# 第 06 章 JWT 生成与解析

> 本章目标：登录成功后返回真正的 JWT；能解析出 Header 和 Payload；理解为什么权限不放进 JWT。

---

## 6.1 前置检查

第 05 章完成：

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

能返回用户信息、角色和权限。

---

## 6.2 JWT 是什么

JWT = `Header.Payload.Signature`，三段 Base64URL：

```text
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiJ9 . 签名
         Header                        Payload                        Signature
```

- **Header**：算法和类型，例如 `{"alg":"HS256","typ":"JWT"}`
- **Payload**：载荷，例如 `sub`、`username`、`tokenVersion`、`iat`、`exp`
- **Signature**：用服务端密钥对前两段签名，防止篡改

三个必须记住的点：

1. **Payload 是 Base64，不是加密。** 任何人拿到 Token 都能解码看到内容，所以**绝对不要放密码**。
2. **签名只能防篡改，不能防读取。** 改了 Payload，签名就对不上，服务端会拒绝。
3. **服务端不存 Token。** 只靠签名和过期时间验证，这就是“无状态”。

---

## 6.3 为什么权限不放进 JWT

很多教程会把角色/权限塞进 Payload：

```json
{ "sub": "1", "roles": ["SUPER_ADMIN"], "permissions": ["user:add"] }
```

看起来方便，但有个大问题：**权限改了，旧 Token 里的权限不会变。**

比如：

1. viewer 登录，Token 里写着 `user:list`。
2. 管理员把 viewer 的角色改成 `USER_ADMIN`。
3. viewer 不重新登录，继续用旧 Token，权限还是旧的。

本系列的选择：

- **JWT 只放身份**：`userId`、`username`、`tokenVersion`。
- **每次请求从数据库加载角色和权限**。
- 好处：权限变更立即生效；退出登录可以通过 `tokenVersion` 让旧 Token 失效。
- 代价：每个请求多两次数据库查询（角色、权限）。学习阶段完全够用，生产可以加缓存。

这个取舍会在第 10 章用实验验证。

---

## 6.4 加依赖

`pom.xml` 的 `<properties>` 加：

```xml
<jjwt.version>0.12.6</jjwt.version>
```

`<dependencies>` 加三个：

```xml
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

为什么是三个？

- `jjwt-api`：编译期用的接口
- `jjwt-impl`：运行时实现
- `jjwt-jackson`：用 Jackson 序列化 Claims

只加 `jjwt-api` 会编译通过，但运行时找不到实现，报 `Unable to find an implementation`。

---

## 6.5 配置

`application.yml` 增加：

```yaml
security:
  jwt:
    # 至少 32 字节（256 位）。生产环境用环境变量 JWT_SECRET 覆盖。
    secret: ${JWT_SECRET:security-lab-dev-secret-key-please-change-32bytes}
    issuer: security-lab
    expiration-minutes: 120
```

注意 `security` 下面已经有别的配置时，不要重复写两个 `security:` 根节点。合并到同一个 `security:` 下即可。

---

## 6.6 完整代码：JwtProperties

`src/main/java/com/keywin/security/security/JwtProperties.java`：

```java
package com.keywin.security.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /**
     * 签名密钥，至少 32 字节。
     */
    private String secret;

    /**
     * 签发者，解析时会校验。
     */
    private String issuer;

    /**
     * 过期时间，单位分钟。
     */
    private long expirationMinutes = 120;
}
```

`@ConfigurationProperties(prefix = "security.jwt")` 会把 `application.yml` 里 `security.jwt.*` 的值绑定到这些字段。

---

## 6.7 完整代码：JwtService

`src/main/java/com/keywin/security/security/JwtService.java`：

```java
package com.keywin.security.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtService {

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
     * 生成 Token。
     * 只放身份信息，不放角色/权限。
     */
    public String generateToken(LoginUser loginUser) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + jwtProperties.getExpirationMinutes() * 60 * 1000);

        return Jwts.builder()
                .subject(String.valueOf(loginUser.getUserId()))
                .claim("username", loginUser.getUsername())
                .claim("tokenVersion", loginUser.getTokenVersion())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验签名、过期时间、issuer。
     * 任何不合法都会抛 JwtException。
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    public String extractUsername(String token) {
        return parse(token).get("username", String.class);
    }

    public Integer extractTokenVersion(String token) {
        Object value = parse(token).get("tokenVersion");
        return value instanceof Number number ? number.intValue() : 0;
    }

    public long getExpirationSeconds() {
        return jwtProperties.getExpirationMinutes() * 60;
    }
}
```

### 逐行解释

- `@PostConstruct`：Bean 创建后执行一次，把字符串密钥转成 `SecretKey`。
- `Keys.hmacShaKeyFor(keyBytes)`：根据字节长度自动选择 HS256/HS384/HS512。32 字节对应 HS256。
- `.subject(String.valueOf(userId))`：标准字段 `sub`，我们放用户 ID。
- `.claim("username", ...)`：自定义字段。
- `.claim("tokenVersion", ...)`：退出登录时用它判断 Token 是否过期。
- `.issuer(...)`：签发者，解析时 `requireIssuer` 会校验。
- `.issuedAt(now)` / `.expiration(...)`：签发时间和过期时间。
- `.signWith(secretKey)`：0.12.x 会根据 key 自动推断算法，不需要再传 `SignatureAlgorithm.HS256`。
- `.compact()`：生成最终字符串。
- `Jwts.parser().verifyWith(secretKey)`：0.12.x 的解析写法。
- `.parseSignedClaims(token).getPayload()`：返回 `Claims`，同时完成签名和过期校验。

---

## 6.8 修改 AuthLoginVO

`src/main/java/com/keywin/security/modules/auth/vo/AuthLoginVO.java` **完整替换为**：

```java
package com.keywin.security.modules.auth.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class AuthLoginVO {

    private Long userId;

    private String username;

    private String nickname;

    private Set<String> roles;

    private Set<String> permissions;

    /**
     * JWT 字符串。
     */
    private String token;

    /**
     * 固定为 Bearer，前端拼 Authorization 头时用。
     */
    private String tokenType;

    /**
     * 有效期，单位秒。
     */
    private Long expiresIn;
}
```

---

## 6.9 修改 AuthServiceImpl

`src/main/java/com/keywin/security/modules/auth/service/impl/AuthServiceImpl.java` **完整替换为**：

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
        vo.setUserId(loginUser.getUserId());
        vo.setUsername(loginUser.getUsername());
        vo.setNickname(loginUser.getNickname());
        vo.setRoles(loginUser.getRoles());
        vo.setPermissions(loginUser.getPermissions());
        vo.setToken(jwtService.generateToken(loginUser));
        vo.setTokenType("Bearer");
        vo.setExpiresIn(jwtService.getExpirationSeconds());
        return vo;
    }
}
```

`AuthController` 不需要改。

---

## 6.10 jjwt 0.12.x 与旧版 API 对照

网上大量教程是 0.11.x 或更早，直接抄会编译不过：

| 操作 | 0.11.x 旧写法 | 0.12.x 新写法 |
| --- | --- | --- |
| 设置 subject | `setSubject(...)` | `subject(...)` |
| 设置签发时间 | `setIssuedAt(...)` | `issuedAt(...)` |
| 设置过期 | `setExpiration(...)` | `expiration(...)` |
| 设置签发者 | `setIssuer(...)` | `issuer(...)` |
| 签名 | `signWith(key, SignatureAlgorithm.HS256)` | `signWith(key)` |
| 解析 | `Jwts.parserBuilder().setSigningKey(key).build()` | `Jwts.parser().verifyWith(key).build()` |
| 解析结果 | `parseClaimsJws(token).getBody()` | `parseSignedClaims(token).getPayload()` |

本系列统一用右列。看到 `setSubject`、`parserBuilder` 就知道是旧代码。

---

## 6.11 验证

### 6.11.1 登录拿 Token

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
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
    ],
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
    "tokenType": "Bearer",
    "expiresIn": 7200
  }
}
```

### 6.11.2 解析 Payload

把 Token 复制出来，在 Git Bash 里执行：

```bash
TOKEN="粘贴你的token"

PAYLOAD=$(echo "$TOKEN" | cut -d. -f2)
PAD=$(( (4 - ${#PAYLOAD} % 4) % 4 ))
printf '%s' "$PAYLOAD$(printf '=%.0s' $(seq 1 $PAD))" | tr '_-' '/+' | base64 -d
```

预期输出类似：

```json
{"sub":"1","username":"admin","tokenVersion":0,"iss":"security-lab","iat":1730000000,"exp":1730007200}
```

你可以看到：

- `sub` 是用户 ID
- `username` 是登录名
- `tokenVersion` 是令牌版本
- `iss` 是 `security-lab`
- `iat` / `exp` 是时间戳（秒）

**没有角色和权限**，这是故意的。

### 6.11.3 用 jwt.io 查看（可选）

也可以把 Token 粘贴到 <https://jwt.io> 的 Debugger。

> ⚠️ 只粘贴本地学习用的 Token。不要把生产环境的 Token 粘贴到任何第三方网站。

### 6.11.4 篡改 Token

把 Token 最后一位改掉，例如把 `A` 改成 `B`，然后访问任意接口。本章还没有 JWT 过滤器，所以暂时看不出效果；第 07 章加过滤器后，这种 Token 会返回 401。

---

## 6.12 常见报错

### 1. `WeakKeyException: The signing key's size is ... bits which is not secure enough`

原因：`secret` 少于 32 字节。

解决：换一个至少 32 字符的密钥，或者设置环境变量 `JWT_SECRET`。

### 2. `SignatureException: JWT signature does not match locally computed signature`

原因：

- 签发和解析用的不是同一个密钥
- Token 被篡改

解决：确认 `JWT_SECRET` 没有变；重启后旧 Token 会失效（因为密钥变了）。

### 3. `ExpiredJwtException`

原因：Token 过期。

解决：重新登录。学习阶段可以把 `expiration-minutes` 调大，但不要在生产这么做。

### 4. `Unable to find an implementation for ...`

原因：只加了 `jjwt-api`，没加 `jjwt-impl`。

解决：补上 `jjwt-impl` 和 `jjwt-jackson`，注意 `scope` 是 `runtime`。

### 5. 编译报 `setSubject` / `parserBuilder` 找不到

原因：抄了 0.11.x 的旧 API。

解决：对照第 6.10 节改成新 API。

### 6. `NoClassDefFoundError: com/fasterxml/jackson/databind/...`

原因：缺少 Jackson。`spring-boot-starter-web` 已经带了；如果你裁掉了 Web 依赖，需要补 `jackson-databind`。

---

## 6.13 本章小结

- JWT = Header.Payload.Signature，Payload 是 Base64 可读的。
- 本系列 JWT 只放 `userId`、`username`、`tokenVersion`，不放权限。
- `JwtService` 负责生成和解析，使用 jjwt 0.12.x 新 API。
- 登录返回 `token`、`tokenType`、`expiresIn`。
- 每次请求从数据库加载权限，保证权限变更立即生效。

### 自测题

1. 为什么 JWT 的 Payload 不能放密码？
2. 为什么本系列不把角色/权限放进 JWT？
3. `tokenVersion` 放在 JWT 里有什么用？
4. jjwt 0.12.x 解析 Token 用哪两个关键方法？

<details>
<summary>参考答案</summary>

1. Payload 只是 Base64 编码，不是加密，任何人都能解码。
2. 权限变更后旧 Token 不会更新；放在数据库里每次加载才能立即生效。
3. 退出登录时数据库 `token_version + 1`，过滤器比对不上就让旧 Token 失效。
4. `Jwts.parser().verifyWith(key).build()` 和 `parseSignedClaims(token).getPayload()`。

</details>

---

下一章：[07-JwtAuthenticationFilter与401](07-JwtAuthenticationFilter与401.md)
