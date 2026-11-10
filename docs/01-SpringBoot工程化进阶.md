# 第 01 章 Spring Boot 工程化进阶

> 预计用时：6 小时
> 本章目标不是学新框架，而是把"能跑的 CRUD"升级成"像公司项目一样可维护的代码"。

---

## 1.1 学习目标

读完并动手后，你能：

1. 搭出一套规范的分层项目结构，知道每个包该放什么。
2. 写出统一的返回格式和全局异常处理，接口不再到处 `try-catch`。
3. 用 Jakarta Validation 做参数校验，不再手写一堆 `if (name == null)`。
4. 理解 `@Transactional` 的原理和 **6 种失效场景**。
5. 用 MyBatis-Plus 完成分页、自动填充、逻辑删除。
6. 用日志和 Actuator 观察应用状态。

---

## 1.2 为什么公司代码看起来"复杂"

你写的 CRUD 可能是这样的：

```java
@PostMapping("/product")
public Product add(@RequestBody Product product) {
    if (product.getName() == null || product.getName().isBlank()) {
        throw new RuntimeException("名称不能为空");
    }
    return productService.save(product);
}
```

能跑，但有几个问题：

- 返回格式不统一：有时返回对象，有时返回字符串，前端要写多套解析。
- 异常处理分散：每个接口都在校验、都在 `try-catch`。
- 错误信息不可控：直接把 `RuntimeException` 的堆栈返回给前端，既不安全也不友好。
- 分层不清：`Product` 实体类同时被 Controller、Service、Mapper、前端复用，字段一改全崩。
- 没有日志：出问题只能靠猜。

公司项目之所以看起来"多了一层又一层"，是因为它在解决这些问题。**先理解问题，再接受复杂度。**

---

## 1.3 推荐的项目结构

```text
mall-product
└── src/main/java/com/example/mall
    ├── MallProductApplication.java
    ├── common
    │   ├── api
    │   │   ├── Result.java              # 统一返回
    │   │   ├── ResultCode.java          # 错误码枚举
    │   │   └── PageResult.java          # 分页返回
    │   ├── exception
    │   │   ├── BusinessException.java   # 业务异常
    │   │   └── GlobalExceptionHandler.java
    │   └── util
    ├── config
    │   ├── MybatisPlusConfig.java
    │   └── WebConfig.java
    └── modules
        └── product
            ├── controller/ProductController.java
            ├── service/ProductService.java
            ├── service/impl/ProductServiceImpl.java
            ├── mapper/ProductMapper.java
            ├── entity/Product.java       # 数据库实体
            ├── dto/ProductCreateDTO.java # 入参
            ├── dto/ProductQueryDTO.java  # 查询条件
            └── vo/ProductVO.java         # 出参
```

### 为什么要区分 Entity / DTO / VO

| 类型 | 用途 | 例子 |
|---|---|---|
| **Entity** | 与数据库表一一对应 | `Product`，字段和表列一致 |
| **DTO** | 接收前端参数 | `ProductCreateDTO`，只包含允许前端传的字段 |
| **VO** | 返回给前端 | `ProductVO`，可能拼接了库存、分类名等 |
| **Query** | 查询条件 | `ProductQueryDTO`，包含分页、排序、模糊搜索 |

如果全用 Entity：

- 前端能传 `id`、`createTime`、`deleted` 等不该由它控制的字段（**安全漏洞**）；
- 数据库字段改名，前端接口跟着坏；
- 返回了密码、内部状态等敏感字段。

> 小项目可以适度简化，但**至少入参和出参要分开**。

---

## 1.4 统一返回格式

### 1.4.1 错误码枚举

```java
package com.example.mall.common.api;

import lombok.Getter;

@Getter
public enum ResultCode {

    SUCCESS(0, "成功"),
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "没有权限"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "数据冲突"),
    INTERNAL_ERROR(50000, "系统内部错误"),

    // 业务错误码：1xxxx 表示业务模块
    PRODUCT_NOT_FOUND(10001, "商品不存在"),
    PRODUCT_STOCK_NOT_ENOUGH(10002, "商品库存不足"),
    ORDER_NOT_FOUND(20001, "订单不存在");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
```

> 错误码设计没有绝对标准，关键是**全局唯一、分段管理、前端可判断**。HTTP 状态码表达"协议层"结果，业务码表达"业务层"结果。

### 1.4.2 统一返回体

```java
package com.example.mall.common.api;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }
}
```

返回示例：

```json
{
  "code": 0,
  "message": "成功",
  "data": { "id": 1, "name": "机械键盘" },
  "timestamp": "2026-11-09T10:30:00"
}
```

### 1.4.3 分页返回

```java
package com.example.mall.common.api;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private long total;
    private long pageNum;
    private long pageSize;
    private List<T> records;

    public static <T> PageResult<T> of(long total, long pageNum, long pageSize, List<T> records) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setRecords(records);
        return result;
    }
}
```

---

## 1.5 全局异常处理

### 1.5.1 业务异常

```java
package com.example.mall.common.exception;

import com.example.mall.common.api.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

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

使用：

```java
Product product = productMapper.selectById(id);
if (product == null) {
    throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
}
```

### 1.5.2 全局异常处理器

```java
package com.example.mall.common.exception;

import com.example.mall.common.api.Result;
import com.example.mall.common.api.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可预期，记录 warn 即可 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }

    /** @RequestBody 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    /** 表单/Query 参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    /** 单参数校验失败，如 @RequestParam @Min(1) */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ResultCode.BAD_REQUEST, "缺少参数: " + e.getParameterName());
    }

    /** 兜底：未预期异常，必须记录完整堆栈 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }
}
```

**关键点：**

- 业务异常（用户输错、库存不足）用 `warn`，不打堆栈；
- 系统异常（空指针、数据库连不上）用 `error`，**必须打堆栈**；
- 兜底异常不能把 `e.getMessage()` 直接返回给前端，可能泄露 SQL、路径等信息。

---

## 1.6 参数校验

Spring Boot 3 使用 `jakarta.validation`（注意不是 `javax`）。

### 1.6.1 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### 1.6.2 DTO 上写规则

```java
package com.example.mall.modules.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductCreateDTO {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 64, message = "商品名称不能超过 64 个字符")
    private String name;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "价格最多 10 位整数、2 位小数")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;
}
```

### 1.6.3 Controller 上加 `@Valid`

```java
@PostMapping
public Result<ProductVO> create(@Valid @RequestBody ProductCreateDTO dto) {
    return Result.success(productService.create(dto));
}
```

失败时会被 `GlobalExceptionHandler` 捕获，返回：

```json
{
  "code": 40000,
  "message": "name: 商品名称不能为空; price: 价格必须大于 0",
  "data": null
}
```

### 1.6.4 分组校验（了解）

新增时 `id` 必须为空，修改时 `id` 必须不为空：

```java
public interface CreateGroup {}
public interface UpdateGroup {}

@Data
public class ProductDTO {
    @Null(groups = CreateGroup.class, message = "新增时不能传 id")
    @NotNull(groups = UpdateGroup.class, message = "修改时 id 不能为空")
    private Long id;
}

// Controller
public Result<?> create(@Validated(CreateGroup.class) @RequestBody ProductDTO dto) {}
public Result<?> update(@Validated(UpdateGroup.class) @RequestBody ProductDTO dto) {}
```

### 1.6.5 常用校验注解

| 注解 | 作用 |
|---|---|
| `@NotNull` | 不能为 null |
| `@NotBlank` | 字符串不能为 null、空串、纯空格 |
| `@NotEmpty` | 集合/字符串不能为空 |
| `@Size(min,max)` | 长度/大小范围 |
| `@Min` / `@Max` | 数值范围 |
| `@DecimalMin` / `@DecimalMax` | 小数范围 |
| `@Digits` | 整数位和小数位 |
| `@Email` | 邮箱格式 |
| `@Pattern(regexp)` | 正则 |
| `@Past` / `@Future` | 日期过去/未来 |

> 校验只做**格式和基本范围**。业务规则（库存是否足够、用户是否有权限）放在 Service 里，用 `BusinessException` 表达。

---

## 1.7 日志规范

### 1.7.1 用 SLF4J，不要用 `System.out`

```java
@Slf4j  // Lombok，等价于 private static final Logger log = LoggerFactory.getLogger(Xxx.class);
@Service
public class ProductServiceImpl implements ProductService {

    public ProductVO getById(Long id) {
        log.info("查询商品, id={}", id);
        // ...
    }
}
```

**为什么用 `{}` 占位符而不是字符串拼接？**

- 日志级别未开启时不会执行字符串拼接，节省性能；
- 避免 `"id=" + id` 产生大量临时字符串。

### 1.7.2 日志级别

| 级别 | 使用场景 |
|---|---|
| `ERROR` | 系统异常、需要人介入：数据库连不上、消息发送失败 |
| `WARN` | 业务异常、可恢复问题：库存不足、参数错误 |
| `INFO` | 关键流程节点：请求开始/结束、订单创建成功 |
| `DEBUG` | 开发调试细节，生产通常关闭 |
| `TRACE` | 极细粒度，几乎不用 |

### 1.7.3 `logback-spring.xml`

放在 `src/main/resources` 下：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <property name="LOG_HOME" value="./logs"/>
    <property name="APP_NAME" value="mall-product"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_HOME}/${APP_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/${APP_NAME}-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>15</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>

    <!-- 自己的包可以开 DEBUG -->
    <logger name="com.example.mall" level="DEBUG"/>
</configuration>
```

`%X{traceId}` 是 MDC 里的链路 ID，第 09 章会实现它。现在先留好位置。

### 1.7.4 日志里不要打印这些

- 密码、身份证、手机号完整值（要脱敏）；
- 完整请求体里的敏感字段；
- 大对象（几 MB 的 JSON）；
- 循环里逐条打印（用汇总日志）。

---

## 1.8 事务：`@Transactional` 的六个失效场景

这是面试和实际开发的高频坑。

### 1.8.1 基本用法

```java
@Service
public class OrderServiceImpl implements OrderService {

    @Transactional(rollbackFor = Exception.class)
    public void createOrder(OrderCreateDTO dto) {
        orderMapper.insert(order);
        stockMapper.decrease(dto.getProductId(), dto.getCount());
    }
}
```

`rollbackFor = Exception.class` 必须写。默认只回滚 `RuntimeException` 和 `Error`，受检异常不会回滚。

### 1.8.2 失效场景清单

| 场景 | 为什么不生效 | 正确做法 |
|---|---|---|
| **同类内自调用** | 事务基于 AOP 代理，`this.method()` 不走代理 | 拆到另一个 Bean；或注入自己；或用 `AopContext.currentProxy()` |
| **方法是 private / final / static** | CGLIB 无法代理 private/final | 改成 `public` |
| **异常被自己 catch 掉** | 事务管理器感知不到异常 | 捕获后重新抛出，或 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` |
| **抛的是受检异常** | 默认只回滚 RuntimeException | `rollbackFor = Exception.class` |
| **类没有被 Spring 管理** | `new` 出来的对象没有代理 | 交给容器管理，加 `@Service` |
| **多线程** | 事务绑定在 ThreadLocal，子线程不在同一事务 | 子线程单独开事务，或改用消息/补偿 |
| **数据库引擎不支持事务** | MyISAM 不支持 | 用 InnoDB |

**自调用示例（最常考）：**

```java
@Service
public class OrderService {

    public void outer() {
        this.inner();  // 事务不生效！this 是原始对象，不是代理
    }

    @Transactional
    public void inner() { ... }
}
```

### 1.8.3 传播行为

| 传播行为 | 含义 | 典型场景 |
|---|---|---|
| `REQUIRED`（默认） | 有事务就加入，没有就新建 | 大多数业务 |
| `REQUIRES_NEW` | 总是新建事务，挂起当前事务 | 记录日志、发通知，不希望随主事务回滚 |
| `NESTED` | 嵌套事务，用保存点 | 部分失败不影响外层（少数场景） |
| `SUPPORTS` | 有就加入，没有就非事务运行 | 查询 |
| `NOT_SUPPORTED` | 非事务运行，挂起当前事务 | 耗时操作 |
| `MANDATORY` | 必须在事务中，否则报错 | 强制约束 |
| `NEVER` | 不能在事务中 | 强制约束 |

### 1.8.4 大事务问题

一个事务里做了：

1. 查数据库；
2. 调远程接口（500ms）；
3. 发短信（1s）；
4. 写数据库。

**后果**：数据库连接被占用 1.5 秒以上，高并发时连接池耗尽；锁持有时间长，其他事务等待。

**原则**：事务里只放数据库操作，远程调用、消息发送、文件 IO 放到事务外，用最终一致性处理（第 07 章）。

---

## 1.9 MyBatis-Plus 实用配置

### 1.9.1 依赖

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.5</version>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

> Spring Boot 3 必须用 `mybatis-plus-spring-boot3-starter`，用错 starter 会启动失败。

### 1.9.2 分页插件

```java
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
```

使用：

```java
Page<Product> page = new Page<>(pageNum, pageSize);
LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
        .like(StringUtils.hasText(keyword), Product::getName, keyword)
        .eq(Product::getStatus, 1)
        .orderByDesc(Product::getCreateTime);
Page<Product> result = productMapper.selectPage(page, wrapper);
```

### 1.9.3 自动填充时间字段

```java
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
```

实体上：

```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createTime;

@TableField(fill = FieldFill.INSERT_UPDATE)
private LocalDateTime updateTime;
```

### 1.9.4 逻辑删除

```java
@TableLogic
private Integer deleted;
```

配置：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

之后 `deleteById` 变成 `UPDATE ... SET deleted = 1`，查询自动加 `deleted = 0`。

> 逻辑删除的坑：唯一索引要把 `deleted` 加进去，否则删除后无法再插入同名记录。

---

## 1.10 配置管理

### 1.10.1 多环境配置

```text
src/main/resources/
├── application.yml          # 公共配置
├── application-dev.yml      # 开发环境
├── application-test.yml     # 测试环境
└── application-prod.yml     # 生产环境
```

`application.yml`：

```yaml
spring:
  profiles:
    active: dev
```

启动时也可以覆盖：

```bash
java -jar app.jar --spring.profiles.active=prod
```

### 1.10.2 类型安全配置

```java
@Data
@Component
@ConfigurationProperties(prefix = "mall.order")
public class OrderProperties {
    private int payTimeoutMinutes = 30;
    private int maxItemCount = 100;
}
```

```yaml
mall:
  order:
    pay-timeout-minutes: 30
    max-item-count: 100
```

比到处写 `@Value("${...}")` 更好维护。

### 1.10.3 敏感配置

数据库密码、第三方密钥**不要提交到 Git**。可以：

- 本地放 `application-local.yml` 并加入 `.gitignore`；
- 生产用环境变量；
- 使用 Nacos 配置中心（第 04 章）。

---

## 1.11 Actuator：应用的体检报告

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,env
  endpoint:
    health:
      show-details: always
```

访问：

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

生产环境注意：`env`、`heapdump` 等端点可能泄露信息，只暴露必要的。

### 优雅停机

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

服务停止时先拒绝新请求，处理完手头请求再退出，避免调用方收到大量 502。

---

## 1.12 完整接口示例

### 实体

```java
@Data
@TableName("product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
```

### Controller

```java
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public Result<ProductVO> create(@Valid @RequestBody ProductCreateDTO dto) {
        return Result.success(productService.create(dto));
    }

    @GetMapping("/{id}")
    public Result<ProductVO> get(@PathVariable Long id) {
        return Result.success(productService.getById(id));
    }

    @GetMapping
    public Result<PageResult<ProductVO>> page(ProductQueryDTO query) {
        return Result.success(productService.page(query));
    }

    @PutMapping("/{id}")
    public Result<ProductVO> update(@PathVariable Long id,
                                    @Valid @RequestBody ProductUpdateDTO dto) {
        return Result.success(productService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success();
    }
}
```

### Service

```java
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;

    @Override
    public ProductVO create(ProductCreateDTO dto) {
        Product product = new Product();
        BeanUtils.copyProperties(dto, product);
        product.setStatus(1);
        productMapper.insert(product);
        return toVO(product);
    }

    @Override
    public ProductVO getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return toVO(product);
    }

    @Override
    public PageResult<ProductVO> page(ProductQueryDTO query) {
        Page<Product> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .like(StringUtils.hasText(query.getKeyword()), Product::getName, query.getKeyword())
                .orderByDesc(Product::getCreateTime);
        Page<Product> result = productMapper.selectPage(page, wrapper);
        List<ProductVO> vos = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), vos);
    }

    private ProductVO toVO(Product product) {
        ProductVO vo = new ProductVO();
        BeanUtils.copyProperties(product, vo);
        return vo;
    }
}
```

> `BeanUtils.copyProperties` 来自 Spring，注意参数顺序是 `(源, 目标)`；Apache Commons 的同名方法顺序相反，容易踩坑。

---

## 1.13 常见坑

1. **`@Transactional` 自调用失效**：调试半天发现事务没回滚，这是最高频问题。
2. **`@Valid` 不生效**：忘了加 `spring-boot-starter-validation`，或加在 Service 上而不是 Controller 参数上。
3. **全局异常处理把 404 也吞了**：`@ExceptionHandler(Exception.class)` 可能拦截 `NoHandlerFoundException`，需要单独处理或配置。
4. **Lombok `@Data` 在实体上生成 `equals/hashCode`**：包含所有字段，集合操作可能出问题；可以用 `@EqualsAndHashCode(callSuper = false)` 或只加 `@Getter/@Setter`。
5. **MyBatis-Plus 逻辑删除后唯一索引冲突**：见 1.9.4。
6. **日志打印了敏感信息**：上线前用工具扫描一遍。
7. **`spring.profiles.active` 写死在生产配置里**：部署时无法覆盖，用环境变量。
8. **接口返回 Entity 导致敏感字段泄露**：至少用 VO 或 `@JsonIgnore`。

---

## 1.14 练习题

1. 把你自己写过的一个 CRUD 项目改造成本章的结构：统一返回、全局异常、参数校验、DTO/VO 分离。
2. 故意写一个"同类内自调用 + `@Transactional`"的例子，观察事务是否回滚；再改成独立 Bean，验证生效。
3. 给商品列表接口加分页、关键字模糊查询、按价格区间过滤。
4. 写一个自定义校验注解 `@Phone`，校验手机号格式。
5. 配置 `logback-spring.xml`，让日志同时输出到控制台和按天滚动的文件。
6. **思考题**：为什么业务异常用 `warn` 而不是 `error`？如果所有异常都打 `error`，会有什么问题？

---

## 1.15 本章小结

1. **工程化的本质是"把横切关注点收拢"**：统一返回、全局异常、参数校验、日志，避免每个接口重复。
2. **Entity / DTO / VO 分离是安全和解耦的需要**，不是形式主义。
3. **`@Transactional` 有明确的失效边界**，记住自调用、异常被吞、非 public、多线程四类。
4. **MyBatis-Plus 能省掉大量样板代码**，但逻辑删除、自动填充、唯一索引有坑。

---

下一章：[第 02 章 MySQL 进阶：索引与事务](02-MySQL索引与事务.md)
