# 第 03 章 统一响应与用户模块 CRUD

> 本章目标：把用户模块的增删改查跑通，并且所有响应都统一成 `{"code","message","data"}`。这一章还没有真正的登录认证，我们会临时放行所有接口。

---

## 3.1 前置检查

第 02 章完成，并且你知道：

- 加 Security 后所有接口默认 401
- 默认用户 `user` + 控制台随机密码
- 请求会经过一条 Security 过滤器链

本章会写第一个 `SecurityConfig`，把默认的“全拦”改成“全放行”。**这是临时措施**，第 07 章会收回。

---

## 3.2 本章的临时措施（先看清楚）

新建 `SecurityConfig`：

```java
http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
```

意思是：**所有请求都放行，不做认证和授权**。

为什么这么做？

- 本章重点是 CRUD 和统一响应，不想让你同时调 Security。
- 如果保持第 02 章的默认配置，每个 curl 都要带 Basic 认证，会干扰学习。
- 第 07 章我们会把它改成“只有登录接口放行，其他都要 Token”。

> ⚠️ 这行配置绝对不能出现在生产环境。教程里它只活到第 06 章。

---

## 3.3 完整目录结构（本章结束时）

```text
src/main/java/com/keywin/security/
├── SecurityLabApplication.java
├── PingController.java
├── common/
│   ├── api/
│   │   ├── PageResult.java
│   │   ├── Result.java
│   │   └── ResultCode.java
│   └── exception/
│       ├── BusinessException.java
│       └── GlobalExceptionHandler.java
├── config/
│   ├── MyMetaObjectHandler.java
│   ├── MybatisPlusConfig.java
│   └── SecurityConfig.java
└── modules/
    └── user/
        ├── controller/UserController.java
        ├── dto/UserCreateDTO.java
        ├── dto/UserUpdateDTO.java
        ├── entity/SysUser.java
        ├── mapper/SysUserMapper.java
        ├── service/UserService.java
        ├── service/impl/UserServiceImpl.java
        └── vo/UserVO.java
```

---

## 3.4 先跑通：统一响应

### 3.4.1 `Result`

`src/main/java/com/keywin/security/common/api/Result.java`：

```java
package com.keywin.security.common.api;

/**
 * 统一响应体。
 * record 是 Java 16+ 的语法，自动生成构造器、getter、equals、hashCode、toString。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    public static Result<Void> success() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
```

为什么用 `record`？

- 响应体是不可变数据，`record` 最合适。
- 少写一堆 getter/setter/构造器。
- Jackson 能正常序列化 record。

如果你不熟悉 record，把它理解成“字段 + 全参构造器 + getter”的简写即可。

### 3.4.2 `ResultCode`

`src/main/java/com/keywin/security/common/api/ResultCode.java`：

```java
package com.keywin.security.common.api;

import lombok.Getter;

@Getter
public enum ResultCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "权限不足"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
```

### 3.4.3 `PageResult`

`src/main/java/com/keywin/security/common/api/PageResult.java`：

```java
package com.keywin.security.common.api;

import java.util.List;

public record PageResult<T>(long total, long page, long size, List<T> records) {
}
```

分页响应长这样：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 3,
    "page": 1,
    "size": 10,
    "records": []
  }
}
```

---

## 3.5 先跑通：异常处理

### 3.5.1 `BusinessException`

`src/main/java/com/keywin/security/common/exception/BusinessException.java`：

```java
package com.keywin.security.common.exception;

import com.keywin.security.common.api.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}
```

业务代码里这样用：

```java
throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
```

### 3.5.2 `GlobalExceptionHandler`

`src/main/java/com/keywin/security/common/exception/GlobalExceptionHandler.java`：

```java
package com.keywin.security.common.exception;

import com.keywin.security.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：HTTP 状态码和业务 code 保持一致。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * @Valid 校验失败：返回第一个字段错误。
     */
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
     * 兜底：未预料的异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误"));
    }
}
```

这里用 `ResponseEntity` 而不是直接返回 `Result`，是为了**同时控制 HTTP 状态码和响应体**。

- 业务成功：HTTP 200，body `code=200`
- 参数错误：HTTP 400，body `code=400`
- 冲突：HTTP 409，body `code=409`

前端既能看 HTTP 状态码，也能看业务 code。

---

## 3.6 先跑通：MyBatis-Plus 配置

### 3.6.1 分页插件

`src/main/java/com/keywin/security/config/MybatisPlusConfig.java`：

```java
package com.keywin.security.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

没有这个插件，`selectPage` 会查出全部数据，`total` 也不对。

### 3.6.2 时间字段自动填充

`src/main/java/com/keywin/security/config/MyMetaObjectHandler.java`：

```java
package com.keywin.security.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
```

实体上的 `@TableField(fill = FieldFill.INSERT)` 和 `FieldFill.INSERT_UPDATE` 会触发这个处理器。

---

## 3.7 先跑通：用户模块

### 3.7.1 实体 `SysUser`

`src/main/java/com/keywin/security/modules/user/entity/SysUser.java`：

```java
package com.keywin.security.modules.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

    private String passwordHash;

    private String nickname;

    private String phone;

    private String email;

    private Integer status;

    private Integer tokenVersion;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

字段与表一一对应。`passwordHash` 对应 `password_hash`，靠 `map-underscore-to-camel-case: true` 自动映射。

### 3.7.2 Mapper

`src/main/java/com/keywin/security/modules/user/mapper/SysUserMapper.java`：

```java
package com.keywin.security.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keywin.security.modules.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

`BaseMapper` 已经提供了 `insert`、`updateById`、`deleteById`、`selectById`、`selectPage`、`selectCount` 等常用方法。

### 3.7.3 DTO

`src/main/java/com/keywin/security/modules/user/dto/UserCreateDTO.java`：

```java
package com.keywin.security.modules.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserCreateDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度必须在 4~20 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度必须在 6~32 之间")
    private String password;

    @Size(max = 50, message = "昵称最长 50 个字符")
    private String nickname;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 个字符")
    private String email;

    private Integer status = 1;
}
```

`src/main/java/com/keywin/security/modules/user/dto/UserUpdateDTO.java`：

```java
package com.keywin.security.modules.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserUpdateDTO {

    /**
     * 可选：不传或传空字符串表示不修改密码。
     * 用 @Pattern 而不是 @Size，因为 @Size(min=6) 会拒绝空字符串。
     */
    @Pattern(regexp = "^$|^.{6,32}$", message = "密码长度必须在 6~32 之间")
    private String password;

    @Size(max = 50, message = "昵称最长 50 个字符")
    private String nickname;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 个字符")
    private String email;

    private Integer status;
}
```

### 3.7.4 VO

`src/main/java/com/keywin/security/modules/user/vo/UserVO.java`：

```java
package com.keywin.security.modules.user.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private String phone;

    private String email;

    private Integer status;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    private LocalDateTime createTime;
}
```

**VO 里没有 `passwordHash`。** 这不是可选项，是硬性要求：任何接口都不能把密码哈希返回给前端。

### 3.7.5 Service 接口

`src/main/java/com/keywin/security/modules/user/service/UserService.java`：

```java
package com.keywin.security.modules.user.service;

import com.keywin.security.common.api.PageResult;
import com.keywin.security.modules.user.dto.UserCreateDTO;
import com.keywin.security.modules.user.dto.UserUpdateDTO;
import com.keywin.security.modules.user.vo.UserVO;

public interface UserService {

    PageResult<UserVO> page(long page, long size, String keyword);

    UserVO getById(Long id);

    Long create(UserCreateDTO dto);

    void update(Long id, UserUpdateDTO dto);

    void delete(Long id);
}
```

### 3.7.6 Service 实现

`src/main/java/com/keywin/security/modules/user/service/impl/UserServiceImpl.java`：

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;

    /**
     * 临时写法：直接 new 一个 BCrypt。
     * 第 05 章会改成注入 PasswordEncoder Bean，这里先让 CRUD 能跑。
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
            // 密码变了，旧 Token 应该全部失效
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
        // sys_user_role 配置了 ON DELETE CASCADE，关联数据会自动清理
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

几个关键点：

- 分页的 `wrapper.and(...)` 很关键。如果不加 `and`，`keyword` 条件会和后面的排序、其他条件混在一起，容易变成 `username like ? OR nickname like ? AND ...`，逻辑就错了。
- `selectCount` 返回 `Long`，不要用 `== 0` 直接和 `int` 比。
- 删除用户依赖数据库外键的 `ON DELETE CASCADE`。如果自己建表时没加外键，要先删 `sys_user_role`。
- 修改密码时 `tokenVersion + 1`，为第 11 章的“改密码后旧 Token 失效”做铺垫。

### 3.7.7 Controller

`src/main/java/com/keywin/security/modules/user/controller/UserController.java`：

```java
package com.keywin.security.modules.user.controller;

import com.keywin.security.common.api.PageResult;
import com.keywin.security.common.api.Result;
import com.keywin.security.modules.user.dto.UserCreateDTO;
import com.keywin.security.modules.user.dto.UserUpdateDTO;
import com.keywin.security.modules.user.service.UserService;
import com.keywin.security.modules.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public Result<PageResult<UserVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(userService.page(page, size, keyword));
    }

    @GetMapping("/{id}")
    public Result<UserVO> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserCreateDTO dto) {
        return Result.success(userService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto) {
        userService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.success();
    }
}
```

---

## 3.8 先跑通：临时 SecurityConfig

`src/main/java/com/keywin/security/config/SecurityConfig.java`：

```java
package com.keywin.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                // 前后端分离 + JWT，后面会一直关掉 CSRF
                .csrf(AbstractHttpConfigurer::disable)
                // ⚠️ 临时放行：第 07 章会改成“只有登录接口放行”
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

重启后，`/api/ping` 和所有 `/api/users` 都能匿名访问。

---

## 3.9 验证

### 3.9.1 启动

```bash
mvn spring-boot:run
```

### 3.9.2 新增用户

```bash
curl -i -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"test_user","password":"test123","nickname":"测试用户","phone":"13900000000","email":"test_user@example.com","status":1}'
```

预期：

```text
HTTP/1.1 200
Content-Type: application/json

{"code":200,"message":"success","data":4}
```

`data` 是新用户 ID。因为你数据库里有 3 个种子用户，自增 ID 从 4 开始。

### 3.9.3 分页查询

```bash
curl -s "http://localhost:8080/api/users?page=1&size=10" | python -m json.tool
```

预期 `data.total` 为 4，`records` 里有 4 个用户，且**没有 passwordHash 字段**。

> 没装 Python 就直接看原始 JSON，不影响。

### 3.9.4 查询详情

```bash
curl -s http://localhost:8080/api/users/4
```

预期返回 `test_user` 的信息。

### 3.9.5 修改用户

```bash
curl -i -X PUT http://localhost:8080/api/users/4 \
  -H "Content-Type: application/json" \
  -d '{"nickname":"测试用户-已修改","phone":"13900000001","email":"test_user_updated@example.com","status":1}'
```

预期：

```json
{"code":200,"message":"success","data":null}
```

### 3.9.6 删除用户

```bash
curl -i -X DELETE http://localhost:8080/api/users/4
```

预期：

```json
{"code":200,"message":"success","data":null}
```

再查 `/api/users/4`，预期 404：

```json
{"code":404,"message":"用户不存在","data":null}
```

### 3.9.7 重复用户名

先创建一个用户，再用同样的用户名创建一次：

```bash
curl -i -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"dup_user","password":"test123","status":1}'

curl -i -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"dup_user","password":"test123","status":1}'
```

第二次预期：

```text
HTTP/1.1 409
{"code":409,"message":"用户名已存在","data":null}
```

### 3.9.8 参数校验

```bash
curl -i -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"ab","password":"123"}'
```

预期：

```text
HTTP/1.1 400
{"code":400,"message":"password 密码长度必须在 6~32 之间","data":null}
```

具体返回哪个字段错误，取决于校验顺序；只要 HTTP 400 且 message 能看懂即可。

---

## 3.10 常见报错

### 1. 启动报 `Invalid bound statement (not found)`

原因：Mapper 接口没有被扫描到。

解决：确认 `SysUserMapper` 上有 `@Mapper`，并且主类在 `com.keywin.security` 根包。

### 2. 分页返回全部数据，`total` 不对

原因：没有注册分页拦截器。

解决：确认 `MybatisPlusConfig` 里的 `PaginationInnerInterceptor` 存在。

### 3. `create_time` 为 null

原因：实体没有加 `@TableField(fill = FieldFill.INSERT)`，或者 `MyMetaObjectHandler` 没被扫描到。

解决：检查两处。

### 4. 插入报 `Unknown column 'password_hash'`

原因：数据库表字段名不一致。

解决：`SHOW COLUMNS FROM sys_user;`，确认是 `password_hash`。

### 5. 返回 500，日志里有 `SQLIntegrityConstraintViolationException`

原因：用户名唯一索引冲突，但代码里的重复检查没生效（并发或大小写问题）。

解决：确认 `username` 有唯一索引；重复创建时返回 409 而不是 500。

### 6. 所有接口 404

原因：Controller 不在主类扫描路径下。

解决：确认包名是 `com.keywin.security.modules.user.controller`。

---

## 3.11 本章小结

- 统一响应：`Result<T>` + `PageResult<T>`。
- 统一异常：`BusinessException` + `GlobalExceptionHandler`，同时控制 HTTP 状态码和业务 code。
- MyBatis-Plus：分页插件 + 时间自动填充。
- 用户 CRUD：分页、详情、新增、修改、删除。
- 密码先临时用 `new BCryptPasswordEncoder()`，第 05 章改成注入 Bean。
- `SecurityConfig` 暂时 `permitAll()`，第 07 章收回。

### 自测题

1. 为什么 `UserVO` 里不能有 `passwordHash`？
2. 为什么分页查询要用 `wrapper.and(...)` 包住 `or` 条件？
3. 为什么业务异常要同时设置 HTTP 状态码和业务 `code`？
4. 第 03 章的 `SecurityConfig` 有什么风险？什么时候收回？

<details>
<summary>参考答案</summary>

1. 密码哈希泄露后可以被离线爆破；任何接口都不应该返回它。
2. 不加 `and` 会变成 `username like ? OR nickname like ? AND ...`，`OR` 的优先级会导致条件范围错误。
3. 前端和网关通常看 HTTP 状态码；业务层需要更细的错误码。两者一致最省心。
4. 所有接口匿名可访问，等于没有安全。第 07 章改成“登录接口放行，其余需要 Token”。

</details>

---

下一章：[04-角色与权限模块CRUD](04-角色与权限模块CRUD.md)
