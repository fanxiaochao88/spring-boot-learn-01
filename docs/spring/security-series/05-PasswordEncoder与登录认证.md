# 第 05 章 PasswordEncoder 与登录认证

> 本章目标：把第 03 章手写的“查用户 + 比密码”换成 Spring Security 的 `AuthenticationManager`。
> 登录成功后返回用户信息、角色和权限。**本章还没有 JWT**，Token 在第 06 章加。

---

## 5.1 前置检查

第 04 章完成：

```bash
curl -s http://localhost:8080/api/roles
curl -s http://localhost:8080/api/permissions
```

都能返回数据，说明角色/权限模块正常。

数据库里三个账号的密码哈希必须是 `sql/02-data.sql` 里的值。如果你改过，先重新执行一次：

```sql
USE security_lab;
SELECT username, LEFT(password_hash, 7) AS hash_prefix FROM sys_user;
```

预期三行都以 `$2a$10$` 开头。

---

## 5.2 为什么不能自己 `equals` 比密码

你原来的登录可能长这样：

```java
SysUser user = userMapper.selectByUsername(dto.getUsername());
if (user == null || !user.getPasswordHash().equals(dto.getPassword())) {
    throw new BusinessException("用户名或密码错误");
}
```

两个致命问题：

1. **数据库存明文**：一旦泄露，所有密码直接暴露。
2. **绕过了 Spring Security**：账号禁用、锁定、密码过期、权限加载，全都得自己写；后面加功能会越写越乱。

正确做法：

- 数据库只存 BCrypt 哈希。
- 登录交给 `AuthenticationManager`，它内部会调用 `UserDetailsService` 查用户、用 `PasswordEncoder.matches` 比密码。

---

## 5.3 认证三件套

| 组件 | 作用 | 本章你要写的 |
| --- | --- | --- |
| `PasswordEncoder` | 加密和比对密码 | `BCryptPasswordEncoder` Bean |
| `UserDetailsService` | 按用户名查用户，返回 `UserDetails` | `UserDetailsServiceImpl` |
| `AuthenticationManager` | 认证调度器，`authenticate()` 一下完成查用户 + 验密码 | 从 `AuthenticationConfiguration` 取出 Bean |

再加两个数据模型：

| 类型 | 作用 |
| --- | --- |
| `UserDetails` | Spring Security 眼中的用户（用户名、密码、权限、是否禁用） |
| `LoginUser` | 我们自己的 `UserDetails` 实现，额外带 `userId`、`nickname`、`tokenVersion` |

---

## 5.4 完整目录结构（本章新增/修改）

```text
src/main/java/com/keywin/security/
├── common/exception/GlobalExceptionHandler.java      # 修改：加认证异常处理
├── config/
│   ├── PasswordConfig.java                           # 新增
│   └── SecurityConfig.java                           # 修改：加 AuthenticationManager
├── security/
│   ├── LoginUser.java                                # 新增
│   └── UserDetailsServiceImpl.java                   # 新增
└── modules/
    ├── auth/
    │   ├── controller/AuthController.java             # 新增
    │   ├── dto/AuthLoginDTO.java                      # 新增
    │   ├── service/AuthService.java                   # 新增
    │   ├── service/impl/AuthServiceImpl.java          # 新增
    │   └── vo/AuthLoginVO.java                        # 新增
    ├── role/mapper/SysRoleMapper.java                 # 修改：加按用户查角色编码
    ├── permission/mapper/SysPermissionMapper.java     # 修改：加按用户查权限编码
    └── user/service/impl/UserServiceImpl.java         # 修改：注入 PasswordEncoder
```

---

## 5.5 先跑通：PasswordEncoder

`src/main/java/com/keywin/security/config/PasswordConfig.java`：

```java
package com.keywin.security.config;

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

### BCrypt 的两个特性

1. **不可逆**：`encode()` 之后不能 `decode()`，只能 `matches(raw, encoded)`。
2. **每次结果不同**：内部自带随机盐，同一个密码两次 `encode` 结果不一样，但 `matches` 都是 `true`。

```java
PasswordEncoder encoder = new BCryptPasswordEncoder();

String hash1 = encoder.encode("123456");
String hash2 = encoder.encode("123456");
// hash1 != hash2

encoder.matches("123456", hash1); // true
encoder.matches("123456", hash2); // true
```

所以不要用 `equals` 比较哈希，必须用 `matches`。

---

## 5.6 修改 UserServiceImpl：注入 PasswordEncoder

第 03 章里我们临时 `new BCryptPasswordEncoder()`。现在改成注入 Bean。

`src/main/java/com/keywin/security/modules/user/service/impl/UserServiceImpl.java` **完整替换为**：

```java
package com.keywin.security.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.keywin.security.common.api.PageResult;
import com.keywin.security.common.api.ResultCode;
import com.keywin.security.common.exception.BusinessException;
import com.keywin.security.modules.user.dto.UserCreateDTO;
import com.keywin.security.modules.user.dto.UserUpdateDTO;
import com.keywin.security.modules.user.entity.SysUser;
import com.keywin.security.modules.user.mapper.SysUserMapper;
import com.keywin.security.modules.user.service.UserService;
import com.keywin.security.modules.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;

    /**
     * 第 05 章起改为注入。
     * Spring 容器里只有一个 PasswordEncoder Bean（BCryptPasswordEncoder）。
     */
    private final PasswordEncoder passwordEncoder;

    @Override
    public PageResult<UserVO> page(long page, long size, String keyword) {
        Page<SysUser> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or()
                    .like(SysUser::getNickname, keyword));
        }
        wrapper.orderByAsc(SysUser::getId);

        Page<SysUser> result = sysUserMapper.selectPage(pageParam, wrapper);
        List<UserVO> records = result.getRecords().stream()
                .map(this::toVO)
                .toList();

        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    @Override
    public UserVO getById(Long id) {
        return toVO(getUserOrThrow(id));
    }

    @Override
    @Transactional
    public Long create(UserCreateDTO dto) {
        Long count = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        user.setTokenVersion(0);

        sysUserMapper.insert(user);
        return user.getId();
    }

    @Override
    @Transactional
    public void update(Long id, UserUpdateDTO dto) {
        SysUser user = getUserOrThrow(id);

        if (StringUtils.hasText(dto.getPassword())) {
            user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        if (dto.getNickname() != null) {
            user.setNickname(dto.getNickname());
        }
        if (dto.getPhone() != null) {
            user.setPhone(dto.getPhone());
        }
        if (dto.getEmail() != null) {
            user.setEmail(dto.getEmail());
        }
        if (dto.getStatus() != null) {
            user.setStatus(dto.getStatus());
        }

        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getUserOrThrow(id);
        sysUserMapper.deleteById(id);
    }

    private SysUser getUserOrThrow(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
```

---

## 5.7 先跑通：LoginUser

`src/main/java/com/keywin/security/security/LoginUser.java`：

```java
package com.keywin.security.security;

import com.keywin.security.modules.user.entity.SysUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Getter
public class LoginUser implements UserDetails {

    private final Long userId;

    private final String username;

    private final String password;

    private final String nickname;

    private final Integer status;

    private final Integer tokenVersion;

    /**
     * 角色编码，不带 ROLE_ 前缀，例如 SUPER_ADMIN。
     */
    private final Set<String> roles;

    /**
     * 权限编码，例如 user:list。
     */
    private final Set<String> permissions;

    /**
     * 真正交给 Spring Security 的权限集合：
     * 角色 -> ROLE_SUPER_ADMIN，权限 -> user:list。
     */
    private final List<GrantedAuthority> authorities;

    public LoginUser(SysUser user, Set<String> roles, Set<String> permissions) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.nickname = user.getNickname();
        this.status = user.getStatus();
        this.tokenVersion = user.getTokenVersion();
        this.roles = Set.copyOf(roles);
        this.permissions = Set.copyOf(permissions);

        Set<GrantedAuthority> granted = new LinkedHashSet<>();
        roles.forEach(role -> granted.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission -> granted.add(new SimpleGrantedAuthority(permission)));
        this.authorities = List.copyOf(granted);
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

    /**
     * status = 1 才启用。
     * 返回 false 时，DaoAuthenticationProvider 会抛 DisabledException。
     */
    @Override
    public boolean isEnabled() {
        return Integer.valueOf(1).equals(status);
    }
}
```

### 为什么角色要加 `ROLE_`，权限不加

Spring Security 的 `hasRole("ADMIN")` 会自动补 `ROLE_` 前缀，实际匹配 `ROLE_ADMIN`。

- 数据库角色编码：`SUPER_ADMIN`
- 放进 authorities：`ROLE_SUPER_ADMIN`
- 配置写法：`hasRole("SUPER_ADMIN")`

权限编码本来就没有前缀：

- 数据库权限编码：`user:list`
- 放进 authorities：`user:list`
- 配置写法：`hasAuthority("user:list")`

如果你把角色写成 `ROLE_SUPER_ADMIN` 再调用 `hasRole("ROLE_SUPER_ADMIN")`，会变成 `ROLE_ROLE_SUPER_ADMIN`，永远匹配不上。这是 403 的头号原因。

---

## 5.8 修改 Mapper：按用户查角色和权限

### 5.8.1 `SysRoleMapper`

`src/main/java/com/keywin/security/modules/role/mapper/SysRoleMapper.java` **完整替换为**：

```java
package com.keywin.security.modules.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.security.modules.role.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询用户拥有的角色编码。
     * 只查启用状态的角色。
     */
    @Select("""
            SELECT r.code
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 1
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
```

### 5.8.2 `SysPermissionMapper`

`src/main/java/com/keywin/security/modules/permission/mapper/SysPermissionMapper.java` **完整替换为**：

```java
package com.keywin.security.modules.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.security.modules.permission.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询用户通过角色间接拥有的权限编码。
     * DISTINCT 防止一个用户有多个角色、多个角色有同一权限时重复。
     */
    @Select("""
            SELECT DISTINCT p.code
            FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId} AND p.status = 1
            """)
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
```

这两条 SQL 是 RBAC 的核心：

```text
用户 -> sys_user_role -> 角色
用户 -> sys_user_role -> sys_role_permission -> 权限
```

---

## 5.9 先跑通：UserDetailsServiceImpl

`src/main/java/com/keywin/security/security/UserDetailsServiceImpl.java`：

```java
package com.keywin.security.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.keywin.security.modules.permission.mapper.SysPermissionMapper;
import com.keywin.security.modules.role.mapper.SysRoleMapper;
import com.keywin.security.modules.user.entity.SysUser;
import com.keywin.security.modules.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));

        if (user == null) {
            // 故意不区分“用户不存在”和“密码错误”，避免账号枚举
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        List<String> roleCodes = sysRoleMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissionCodes = sysPermissionMapper.selectCodesByUserId(user.getId());

        return new LoginUser(
                user,
                new LinkedHashSet<>(roleCodes),
                new LinkedHashSet<>(permissionCodes)
        );
    }
}
```

为什么查询结果用 `LinkedHashSet`？

- 去重：一个用户可能有多个角色，权限 SQL 已经 `DISTINCT`，但角色编码理论上也可能重复。
- 保持顺序：调试时输出稳定，便于对照。

---

## 5.10 先跑通：登录接口

### 5.10.1 `AuthLoginDTO`

`src/main/java/com/keywin/security/modules/auth/dto/AuthLoginDTO.java`：

```java
package com.keywin.security.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthLoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
```

### 5.10.2 `AuthLoginVO`

`src/main/java/com/keywin/security/modules/auth/vo/AuthLoginVO.java`：

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
}
```

第 06 章会在这个类里加 `token`、`tokenType`、`expiresIn`。

### 5.10.3 `AuthService`

`src/main/java/com/keywin/security/modules/auth/service/AuthService.java`：

```java
package com.keywin.security.modules.auth.service;

import com.keywin.security.modules.auth.dto.AuthLoginDTO;
import com.keywin.security.modules.auth.vo.AuthLoginVO;

public interface AuthService {

    AuthLoginVO login(AuthLoginDTO dto);
}
```

### 5.10.4 `AuthServiceImpl`

`src/main/java/com/keywin/security/modules/auth/service/impl/AuthServiceImpl.java`：

```java
package com.keywin.security.modules.auth.service.impl;

import com.keywin.security.modules.auth.dto.AuthLoginDTO;
import com.keywin.security.modules.auth.service.AuthService;
import com.keywin.security.modules.auth.vo.AuthLoginVO;
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

    @Override
    public AuthLoginVO login(AuthLoginDTO dto) {
        // ① 把用户名密码交给 Security，失败会抛 AuthenticationException
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword())
        );

        // ② 认证成功后，principal 就是 UserDetailsServiceImpl 返回的 LoginUser
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();

        // ③ 组装返回
        AuthLoginVO vo = new AuthLoginVO();
        vo.setUserId(loginUser.getUserId());
        vo.setUsername(loginUser.getUsername());
        vo.setNickname(loginUser.getNickname());
        vo.setRoles(loginUser.getRoles());
        vo.setPermissions(loginUser.getPermissions());
        return vo;
    }
}
```

### 5.10.5 `AuthController`

`src/main/java/com/keywin/security/modules/auth/controller/AuthController.java`：

```java
package com.keywin.security.modules.auth.controller;

import com.keywin.security.common.api.Result;
import com.keywin.security.modules.auth.dto.AuthLoginDTO;
import com.keywin.security.modules.auth.service.AuthService;
import com.keywin.security.modules.auth.vo.AuthLoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
}
```

---

## 5.11 修改 SecurityConfig：暴露 AuthenticationManager

`src/main/java/com/keywin/security/config/SecurityConfig.java` **完整替换为**：

```java
package com.keywin.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // 仍然是临时放行，第 07 章收回
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * AuthenticationManager 由 Spring Security 自动装配。
     * 我们只是把它取出来，暴露成 Bean 给 AuthService 用。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
```

### 为什么能自动装配

Spring Boot 检测到：

- 有一个 `UserDetailsService` Bean（`UserDetailsServiceImpl`）
- 有一个 `PasswordEncoder` Bean（`PasswordConfig`）

于是自动创建 `DaoAuthenticationProvider`，并把它注册到 `ProviderManager`。`AuthenticationConfiguration.getAuthenticationManager()` 返回的就是这个 `ProviderManager`。

---

## 5.12 修改 GlobalExceptionHandler：处理认证异常

`src/main/java/com/keywin/security/common/exception/GlobalExceptionHandler.java` **完整替换为**：

```java
package com.keywin.security.common.exception;

import com.keywin.security.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? "请求参数错误"
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return ResponseEntity.badRequest()
                .body(Result.error(400, message));
    }

    /**
     * 请求体不是合法 JSON，或者字段类型对不上。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "请求体格式错误"));
    }

    /**
     * 账号被禁用。注意：要写在 AuthenticationException 前面（更具体）。
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Result<Void>> handleDisabled(DisabledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(403, "账号已被禁用"));
    }

    /**
     * 账号被锁定。
     */
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Result<Void>> handleLocked(LockedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(403, "账号已被锁定"));
    }

    /**
     * 其他认证失败：用户名不存在、密码错误、凭证过期等。
     * 统一提示“用户名或密码错误”，不泄露用户是否存在。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthentication(AuthenticationException e) {
        log.debug("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(401, "用户名或密码错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误"));
    }
}
```

> Spring 选择异常处理器时会选**最具体**的类型，所以 `DisabledException`、`LockedException` 会优先于 `AuthenticationException`，不会都被 401 吃掉。

---

## 5.13 认证流程：一次登录到底发生了什么

```text
POST /api/auth/login  {username, password}
        │
        ▼
AuthController
        │  new UsernamePasswordAuthenticationToken(username, password)
        ▼
AuthenticationManager（实际是 ProviderManager）
        │
        ▼
DaoAuthenticationProvider
        │  ① retrieveUser -> UserDetailsService.loadUserByUsername(username)
        │  ② preAuthenticationChecks -> 检查 enabled / locked / expired
        │  ③ additionalAuthenticationChecks -> PasswordEncoder.matches(raw, encoded)
        ▼
成功：返回 authenticated=true 的 Authentication
失败：抛 BadCredentialsException / DisabledException / LockedException
```

### 5.13.1 两个 `UsernamePasswordAuthenticationToken`

```java
// 登录入参：未认证
new UsernamePasswordAuthenticationToken(username, password)

// 认证结果：已认证（第三个参数是权限）
new UsernamePasswordAuthenticationToken(principal, credentials, authorities)
```

第一个构造器创建的对象 `isAuthenticated()` 是 `false`，只能作为“待认证凭证”。

第二个构造器创建的对象 `isAuthenticated()` 是 `true`，是认证成功后的结果。

### 5.13.2 为什么 `UsernameNotFoundException` 最后变成 `BadCredentialsException`

`DaoAuthenticationProvider` 默认 `hideUserNotFoundExceptions = true`，会把“用户不存在”统一转换成“密码错误”。这样攻击者无法通过响应判断某个用户名是否存在。

所以你的 `GlobalExceptionHandler` 不需要单独处理 `UsernameNotFoundException`，它会被 `AuthenticationException` 捕获。

### 5.13.3 为什么 `LoginUser.isEnabled()` 能控制禁用

`DaoAuthenticationProvider` 在比密码之前会调用 `preAuthenticationChecks`：

- `isEnabled()` 返回 false → `DisabledException`
- `isAccountNonLocked()` 返回 false → `LockedException`
- `isAccountNonExpired()` / `isCredentialsNonExpired()` 同理

所以账号禁用不需要在登录接口里写 `if (status == 0)`。

---

## 5.14 验证

### 5.14.1 重启应用

```bash
mvn spring-boot:run
```

### 5.14.2 正确密码登录

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期：

```text
HTTP/1.1 200
Content-Type: application/json

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

`permissions` 的顺序可能不同，因为用的是 `Set`；内容应该是 12 个权限。

### 5.14.3 错误密码

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong"}'
```

预期：

```text
HTTP/1.1 401
{"code":401,"message":"用户名或密码错误","data":null}
```

### 5.14.4 不存在的用户

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"nobody","password":"whatever"}'
```

预期同样是 401，提示也是“用户名或密码错误”。**不要**返回“用户不存在”。

### 5.14.5 禁用账号

先禁用 viewer：

```sql
USE security_lab;
UPDATE sys_user SET status = 0 WHERE username = 'viewer';
```

然后登录：

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"viewer","password":"viewer123"}'
```

预期：

```text
HTTP/1.1 403
{"code":403,"message":"账号已被禁用","data":null}
```

验证完恢复：

```sql
UPDATE sys_user SET status = 1 WHERE username = 'viewer';
```

### 5.14.6 参数校验

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"","password":""}'
```

预期 HTTP 400。

---

## 5.15 常见报错

### 1. `NoSuchBeanDefinitionException: AuthenticationManager`

原因：没有暴露 `AuthenticationManager` Bean。

解决：确认 `SecurityConfig` 里有 `authenticationManager(AuthenticationConfiguration)` 方法。

### 2. `There is no PasswordEncoder mapped for the id "null"`

原因：没有 `PasswordEncoder` Bean，或者数据库密码不是 BCrypt 格式。

解决：

- 确认 `PasswordConfig` 存在。
- 确认数据库里是 `$2a$10$...` 开头。
- 如果之前用明文存过密码，重新执行 `sql/02-data.sql`。

### 3. `Bad credentials`

原因：密码不匹配。常见于：

- 数据库里是明文密码
- 哈希复制时少了字符
- `matches` 参数传反了（我们是在 Security 内部调用，不用手写）

解决：

```sql
SELECT username, password_hash FROM sys_user WHERE username = 'admin';
```

确认哈希是 `$2a$10$G4w4ZjRmRERBL.oPFRmj0Od4kYQE/hrcxCMFl8DP2dMJRr8fG1N3i`。

### 4. `ClassCastException: ... cannot be cast to LoginUser`

原因：`Authentication.getPrincipal()` 返回的不是你的 `LoginUser`。

常见原因：

- `UserDetailsService` 返回了 Spring 内置的 `User`，而不是 `LoginUser`
- 同时存在多个 `UserDetailsService` Bean

解决：确认只保留一个 `UserDetailsService` 实现，并且返回 `new LoginUser(...)`。

### 5. 登录失败返回 500，不是 401

原因：`GlobalExceptionHandler` 没有处理 `AuthenticationException`，被兜底的 `Exception` 处理器接住了。

解决：确认有 `@ExceptionHandler(AuthenticationException.class)`。

### 6. 禁用账号登录返回 401，不是 403

原因：`DisabledException` 被更宽的 `AuthenticationException` 处理器接住了。

解决：确认 `DisabledException` 处理器存在，并且 Spring 会选择最具体的类型。一般只要两个方法都在同一个 `@RestControllerAdvice` 里就没问题。

### 7. 启动报 `Found multiple @Bean methods ... PasswordEncoder`

原因：你既写了 `PasswordConfig`，又在别处 `new BCryptPasswordEncoder()` 注册了 Bean。

解决：只保留一个 `PasswordEncoder` Bean；业务代码注入接口，不要自己 new。

---

## 5.16 本章小结

- `PasswordEncoder`：BCrypt，`encode` 存库，`matches` 比对。
- `UserDetailsService`：按用户名查用户 + 角色 + 权限，返回 `LoginUser`。
- `LoginUser`：实现 `UserDetails`，角色加 `ROLE_` 前缀，权限原样。
- `AuthenticationManager`：登录入口，内部完成查用户、验密码、状态检查。
- 登录失败：`AuthenticationException` → 401；禁用/锁定 → 403。
- 本章登录成功只返回用户信息，还没有 Token。

### 自测题

1. 为什么 `PasswordEncoder.matches(raw, encoded)` 不能用 `equals` 替代？
2. `LoginUser` 里角色和权限分别怎么放进 authorities？
3. `AuthenticationManager` 是谁创建的？为什么我们只写了一个 `@Bean` 就能拿到？
4. 为什么“用户不存在”和“密码错误”要返回同样的提示？
5. `LoginUser.isEnabled()` 返回 false 时，登录会抛什么异常？

<details>
<summary>参考答案</summary>

1. BCrypt 每次加密带随机盐，同一个密码两次哈希不同；`equals` 永远不相等，必须用 `matches` 做哈希校验。
2. 角色加 `ROLE_` 前缀，如 `ROLE_SUPER_ADMIN`；权限原样，如 `user:list`。
3. Spring Security 的 `AuthenticationConfiguration` 自动创建 `ProviderManager` 和 `DaoAuthenticationProvider`；我们只是把 `AuthenticationManager` 暴露成 Bean。
4. 防止账号枚举：攻击者不能通过响应判断某个用户名是否存在。
5. `DisabledException`，由 `GlobalExceptionHandler` 转成 403。

</details>

---

下一章：[06-JWT生成与解析](06-JWT生成与解析.md)
