# 第 05 章 Gateway 与 Sentinel

> 预计用时：8 小时
> 服务拆开后，客户端难道要记住每个服务的地址？某个服务被打垮了怎么办？本章解决"统一入口"和"流量保护"两个问题。

---

## 5.1 学习目标

读完并动手后，你能：

1. 说清楚 API 网关的作用，以及它和 Nginx 的区别。
2. 用 Spring Cloud Gateway 配置路由、断言、过滤器。
3. 在网关实现统一鉴权和 CORS。
4. 用 Sentinel 做限流、熔断、降级，并理解背后的算法。
5. 给 Feign 调用配置 fallback，下游挂掉时返回兜底数据。
6. 排查 Gateway 和 Sentinel 的常见配置问题。

---

## 5.2 为什么需要网关

没有网关时：

```text
浏览器 → 用户服务 8081
       → 商品服务 8082
       → 订单服务 8083
       → 库存服务 8084
```

问题：

1. **客户端要知道所有服务地址**，服务扩容、换 IP 就要改客户端。
2. **每个服务都要做鉴权、限流、日志、CORS**，重复代码。
3. **跨域配置分散**，容易漏。
4. **无法统一做灰度、灰度发布、协议转换**。

加网关后：

```text
浏览器 ──► Gateway :8080 ──► 用户服务
                        ├──► 商品服务
                        └──► 订单服务
```

网关的职责：

| 职责 | 说明 |
|---|---|
| **路由** | `/api/users/**` → 用户服务，`/api/products/**` → 商品服务 |
| **鉴权** | 在网关统一校验 JWT，非法请求直接拦截 |
| **限流** | 每个 IP、每个用户、每个接口的 QPS 限制 |
| **熔断降级** | 下游服务异常时快速失败，返回兜底 |
| **日志/监控** | 统一记录访问日志、耗时、traceId |
| **协议转换** | 外部 HTTP → 内部 gRPC（进阶） |
| **灰度发布** | 按 header、用户 ID 路由到不同版本 |

### 网关 vs Nginx

| 维度 | Nginx | Spring Cloud Gateway |
|---|---|---|
| 定位 | 通用反向代理、静态资源、负载均衡 | 微服务 API 网关 |
| 配置 | 配置文件，改完 reload | 代码 + 配置，可动态 |
| 服务发现 | 需要手动配置 upstream 或 Lua | 原生集成 Nacos，`lb://service-name` |
| 鉴权 | 需要 Lua/OpenResty | Java 代码，灵活 |
| 性能 | 极高 | 较高（WebFlux 非阻塞） |
| 典型位置 | 最外层 | Nginx 之后、微服务之前 |

> 生产常见架构：`CDN → Nginx（HTTPS/静态资源）→ Gateway（鉴权/路由/限流）→ 微服务`。

---

## 5.3 Spring Cloud Gateway 快速上手

### 5.3.1 新建网关模块

`pom.xml`：

```xml
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>mall-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>mall-gateway</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
        </dependency>
    </dependencies>
</project>
```

> **绝对不要**在 Gateway 模块引入 `spring-boot-starter-web`。Gateway 基于 Spring WebFlux（响应式），引入 WebMVC 会导致启动报错 `Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway`。

### 5.3.2 启动类

```java
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

### 5.3.3 最简路由配置

```yaml
server:
  port: 8080

spring:
  application:
    name: mall-gateway
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    gateway:
      routes:
        - id: product-service
          uri: lb://product-service
          predicates:
            - Path=/api/products/**
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/users/**
```

访问 `http://localhost:8080/api/products/1001`，网关会：

1. 匹配 `Path=/api/products/**`；
2. 把 `lb://product-service` 解析成 Nacos 里的实例（负载均衡）；
3. 转发请求。

### 5.3.4 核心概念

| 概念 | 作用 | 示例 |
|---|---|---|
| **Route** | 一条路由规则 | id + uri + predicates + filters |
| **Predicate** | 断言，决定是否匹配 | Path、Method、Header、Query、Host、时间 |
| **Filter** | 过滤器，请求前后做处理 | 添加头、限流、重写路径、熔断 |

### 5.3.5 常用断言

```yaml
predicates:
  - Path=/api/products/**
  - Method=GET,POST
  - Header=X-Request-From, app
  - Query=debug, true
  - Host=**.example.com
  - After=2026-01-01T00:00:00+08:00[Asia/Shanghai]
  - Before=2027-01-01T00:00:00+08:00[Asia/Shanghai]
  - Between=2026-01-01T00:00:00+08:00[Asia/Shanghai],2027-01-01T00:00:00+08:00[Asia/Shanghai]
```

多个断言是 **AND** 关系。

### 5.3.6 常用过滤器

```yaml
filters:
  - StripPrefix=1                 # 去掉路径第一段：/api/products → /products
  - PrefixPath=/api               # 加前缀
  - AddRequestHeader=X-Gateway, mall
  - AddResponseHeader=X-Response-Time, 100ms
  - RewritePath=/api/(?<segment>.*), /$\{segment}
  - RequestRateLimiter=...        # 限流
  - CircuitBreaker=...            # 熔断
```

> `StripPrefix=1` 很常用：外部路径是 `/api/products/1`，内部服务接口是 `/products/1`，去掉 `/api`。

### 5.3.7 全局过滤器：统一鉴权

```java
@Component
@Slf4j
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITE_LIST = List.of(
            "/api/users/login",
            "/api/users/register",
            "/actuator/health"
    );

    private final JwtUtils jwtUtils;

    public AuthGlobalFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单直接放行
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            return unauthorized(exchange, "未登录");
        }

        try {
            Claims claims = jwtUtils.parse(token.substring(7));
            // 把用户信息放进请求头，传给下游
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Name", claims.get("name", String.class))
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        } catch (Exception e) {
            log.warn("token 校验失败: {}", e.getMessage());
            return unauthorized(exchange, "登录已过期");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"code":40100,"message":"%s","data":null}
                """.formatted(message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;  // 越小越先执行
    }
}
```

**要点：**

- 网关是 **WebFlux**，用 `ServerWebExchange`、`Mono`，不是 `HttpServletRequest`；
- 鉴权失败直接写响应，不要 `throw`（除非有全局异常处理）；
- 用户信息通过**请求头**传给下游，下游不用再解析 token；
- 下游服务不能直接信任外部传入的 `X-User-Id`，要确保网关是唯一入口（内网隔离）。

### 5.3.8 CORS 跨域

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins:
              - "http://localhost:3000"
              - "https://mall.example.com"
            allowed-methods:
              - GET
              - POST
              - PUT
              - DELETE
              - OPTIONS
            allowed-headers: "*"
            allow-credentials: true
            max-age: 3600
```

> 如果前面还有 Nginx，跨域也可以在 Nginx 处理，**不要两处都配**，否则响应头重复，浏览器报错。

### 5.3.9 路由配置放 Nacos（进阶）

路由变更不想重启网关，可以把路由配置放到 Nacos，监听配置变化动态刷新。Spring Cloud Gateway 支持 `RouteDefinitionLocator`，社区也有 `spring-cloud-gateway-nacos` 方案。新手先用 `application.yml`，第 10 章再考虑动态路由。

---

## 5.4 限流、熔断、降级：概念先分清

| 概念 | 目的 | 触发条件 | 结果 |
|---|---|---|---|
| **限流** | 控制进入系统的流量 | QPS/并发数超过阈值 | 拒绝请求（429） |
| **熔断** | 下游故障时快速失败，避免雪崩 | 错误率/慢调用比例超阈值 | 一段时间内直接失败 |
| **降级** | 整体资源不足时，牺牲非核心功能 | 手动/自动触发 | 返回兜底数据或简化功能 |

打个比方：

- 限流 = 餐厅门口排队，超过容量就不让进；
- 熔断 = 厨房着火了，暂时停止接单，而不是让客人一直等；
- 降级 = 忙不过来时，只做招牌菜，不做复杂菜。

### 5.4.1 常见限流算法

| 算法 | 原理 | 特点 |
|---|---|---|
| **固定窗口** | 每个时间窗口计数，超过阈值拒绝 | 简单，但窗口边界可能放过 2 倍流量 |
| **滑动窗口** | 把窗口切成小格，滚动统计 | 平滑，Sentinel 默认 |
| **漏桶** | 请求进桶，匀速流出 | 强制匀速，适合保护下游 |
| **令牌桶** | 匀速生成令牌，请求拿令牌 | 允许突发流量，常用 |

### 5.4.2 限流维度

- **QPS**：每秒请求数
- **并发线程数**：同时处理的请求数
- **调用关系**：限制 A 调用 B 的频率
- **热点参数**：同一个商品 ID 的请求限制

---

## 5.5 Sentinel 实战

### 5.5.1 启动控制台

```bash
docker run -d \
  --name roadmap-sentinel \
  -p 8858:8858 \
  bladex/sentinel-dashboard:1.8.7
```

访问 `http://localhost:8858`，默认账号密码都是 `sentinel`。

> 控制台的规则默认存在内存里，重启丢失，也不会自动同步到集群。生产环境需要接 Nacos 持久化规则（见 5.5.7）。

### 5.5.2 服务接入

业务服务加依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

配置：

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8858
        port: 8719          # 客户端与控制台通信端口，多个服务会自动 +1
      eager: true           # 启动即连接控制台，不等第一次请求
```

启动服务后，在 Sentinel 控制台左侧能看到服务名。

### 5.5.3 流控规则

控制台操作：簇点链路 → 找到资源 → `+流控`。

- 资源名：`GET:/api/products/{id}`（Spring MVC 接口）
- 阈值类型：QPS
- 单机阈值：10
- 流控模式：直接
- 流控效果：快速失败

也可以用代码初始化（适合本地实验）：

```java
@Configuration
public class SentinelRuleConfig {

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        FlowRule productQuery = new FlowRule();
        productQuery.setResource("GET:/api/products/{id}");
        productQuery.setGrade(RuleConstant.FLOW_GRADE_QPS);
        productQuery.setCount(10);              // 每秒 10 次
        productQuery.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        rules.add(productQuery);

        FlowRuleManager.loadRules(rules);
    }
}
```

### 5.5.4 自定义兜底返回

方式一：`@SentinelResource` + blockHandler

```java
@Service
public class ProductQueryService {

    @SentinelResource(value = "queryProduct", blockHandler = "queryBlocked")
    public ProductVO query(Long id) {
        Product product = productMapper.selectById(id);
        return product == null ? null : toVO(product);
    }

    public ProductVO queryBlocked(Long id, BlockException ex) {
        log.warn("商品查询被限流, id={}", id);
        throw new BusinessException(ResultCode.CONFLICT, "当前访问人数过多，请稍后再试");
    }
}
```

方式二：全局 `BlockExceptionHandler`

```java
@Component
public class SentinelBlockHandler implements BlockExceptionHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       BlockException e) throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"code":42900,"message":"请求过于频繁，请稍后再试","data":null}
                """);
    }
}
```

> 接口被限流应返回 **HTTP 429**，业务码自定。不要让用户看到默认的 `Blocked by Sentinel (flow limiting)` 纯文本。

### 5.5.5 熔断降级规则

控制台：簇点链路 → `+熔断`。

| 策略 | 含义 | 适用 |
|---|---|---|
| **慢调用比例** | 慢调用占比超过阈值 | 下游变慢 |
| **异常比例** | 异常占比超过阈值 | 下游频繁报错 |
| **异常数** | 异常数超过阈值 | 流量小、异常绝对值有意义的场景 |

代码方式：

```java
DegradeRule rule = new DegradeRule("GET:/api/products/{id}")
        .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
        .setCount(0.5)          // 错误率 50%
        .setTimeWindow(10)      // 熔断 10 秒
        .setMinRequestAmount(10) // 至少 10 个请求才统计
        .setStatIntervalMs(10000);
DegradeRuleManager.loadRules(List.of(rule));
```

**熔断器的三个状态：**

```text
CLOSED（正常放行）
   │ 错误率超过阈值
   ▼
OPEN（直接拒绝，不再调用下游）
   │ 等待 timeWindow
   ▼
HALF_OPEN（放行少量请求试探）
   │ 成功 → CLOSED
   │ 失败 → OPEN
```

### 5.5.6 Feign 整合 Sentinel：fallback

1. 开启 Feign 与 Sentinel 的整合：

```yaml
feign:
  sentinel:
    enabled: true
```

2. 定义 fallback：

```java
@FeignClient(
        name = "product-service",
        path = "/api/products",
        fallbackFactory = ProductClientFallbackFactory.class
)
public interface ProductClient {
    @GetMapping("/{id}")
    Result<ProductVO> getById(@PathVariable("id") Long id);
}
```

```java
@Component
@Slf4j
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    @Override
    public ProductClient create(Throwable cause) {
        log.error("调用商品服务失败: {}", cause.getMessage());
        return new ProductClient() {
            @Override
            public Result<ProductVO> getById(Long id) {
                return Result.fail(ResultCode.INTERNAL_ERROR, "商品服务暂不可用");
            }
        };
    }
}
```

> 为什么用 `fallbackFactory` 而不是 `fallback`？`fallbackFactory` 能拿到异常原因，方便记录和区分超时、熔断、连接失败。

3. 验证：停掉商品服务，调用订单接口，应该看到 fallback 日志和兜底返回，而不是 500 错误。

### 5.5.7 规则持久化到 Nacos（进阶）

默认规则存在 Sentinel 控制台内存，重启丢失。生产做法：

1. 在 Nacos 创建配置 `sentinel-flow-rules`；
2. 引入 `sentinel-datasource-nacos`；
3. 配置数据源：

```yaml
spring:
  cloud:
    sentinel:
      datasource:
        flow:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: sentinel-flow-rules
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: flow
```

Nacos 里的规则 JSON：

```json
[
  {
    "resource": "GET:/api/products/{id}",
    "limitApp": "default",
    "grade": 1,
    "count": 10,
    "strategy": 0,
    "controlBehavior": 0
  }
]
```

> 注意：开源版 Sentinel 控制台修改规则默认不会写回 Nacos，需要改造控制台或使用云上版本。生产中常见做法是**在 Nacos 里维护规则**，控制台只用于观察。

---

## 5.6 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| Gateway 启动报 `Spring MVC found on classpath` | 引入了 `spring-boot-starter-web` | 移除，只保留 `spring-cloud-starter-gateway` |
| 网关 404 | 路由路径与服务路径拼接错误 | 用 `StripPrefix` 或核对 `Path` |
| `lb://` 解析失败 | 缺 `spring-cloud-starter-loadbalancer` | 加依赖 |
| 跨域报错头重复 | Nginx 和 Gateway 都配了 CORS | 只保留一处 |
| Sentinel 控制台看不到服务 | `eager=false` 且没发请求 | 设 `eager: true` 或先调用一次接口 |
| Sentinel 客户端连不上控制台 | 8719 端口被占/防火墙 | 检查端口，多个服务会自动递增 |
| 限流规则不生效 | 资源名不匹配 | 在"簇点链路"里看准确的资源名 |
| fallback 不生效 | 没开 `feign.sentinel.enabled` 或 fallback 不是 Bean | 检查配置和 `@Component` |
| 网关里用 `HttpServletRequest` 报错 | WebFlux 没有 Servlet API | 用 `ServerWebExchange` |
| 全局过滤器里 `Thread.sleep` | 阻塞事件循环，性能骤降 | 用异步非阻塞 API |

---

## 5.7 动手实验

### 实验 1：网关路由

1. 启动 Nacos、商品服务、订单服务、网关；
2. 直接访问 `http://localhost:8081/api/products/1001`；
3. 通过网关访问 `http://localhost:8080/api/products/1001`；
4. 观察网关日志和目标服务日志。

### 实验 2：统一鉴权

1. 在用户服务实现 `/api/users/login`，返回 JWT；
2. 网关白名单放行登录接口；
3. 不带 token 访问商品接口，应返回 401；
4. 带 token 访问，应成功，且下游能拿到 `X-User-Id`。

### 实验 3：限流

1. 给商品查询接口配置 QPS=5；
2. 用 `wrk` 或 JMeter 以 20 QPS 压测；
3. 观察部分请求返回 429；
4. 在 Sentinel 控制台查看实时 QPS 曲线。

```bash
# 简单压测（Windows 可用 Git Bash + curl 循环，或安装 hey/wrk）
for i in $(seq 1 100); do curl -s http://localhost:8080/api/products/1001 -o /dev/null & done; wait
```

### 实验 4：熔断降级

1. 给 Feign 调用配置 fallback；
2. 停掉商品服务；
3. 调用下单接口，观察 fallback 返回而不是超时报错；
4. 重启商品服务，等待熔断器恢复（HALF_OPEN → CLOSED）。

### 实验 5：观察慢调用熔断

在商品服务里随机 `sleep(1000)`，配置"慢调用比例 > 50%，RT > 500ms，熔断 10 秒"，观察熔断触发。

---

## 5.8 练习题

1. 给网关加一个全局过滤器，记录每个请求的耗时并打印日志。
2. 实现基于用户 ID 的限流：同一用户每秒最多 10 次请求（提示：Sentinel 热点参数限流）。
3. 配置 CORS，只允许 `http://localhost:3000` 跨域访问。
4. 给订单服务的 Feign 调用配置 fallbackFactory，超时和熔断返回不同提示。
5. **思考题**：为什么网关要做鉴权，而不是每个微服务各自做一遍？各自做有什么问题？
6. **思考题**：限流阈值设为 100 QPS，但机器只有 2 核，会发生什么？限流能解决容量问题吗？

---

## 5.9 本章小结

1. **网关是微服务的统一入口**，负责路由、鉴权、限流、日志；它和 Nginx 是互补关系，不是替代关系。
2. **Gateway 基于 WebFlux**，不能用 Servlet API，不能引入 Spring MVC。
3. **Sentinel 提供限流、熔断、降级**；限流保护系统不被流量打垮，熔断保护调用方不被下游拖死。
4. **Feign + Sentinel fallback** 是微服务容错的标配。
5. **规则要持久化**，控制台内存规则重启就丢；生产用 Nacos 数据源。

---

下一章：[第 06 章 Kafka 消息队列](06-Kafka消息队列.md)
