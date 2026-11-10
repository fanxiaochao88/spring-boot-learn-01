# 第 04 章 微服务拆分与 Nacos

> 预计用时：10 小时
> 本章开始真正进入微服务。你会把单体项目拆成三个服务，让它们通过 Nacos 找到彼此，并用 Feign 互相调用。

---

## 4.1 学习目标

读完并动手后，你能：

1. 说清楚什么情况下该拆服务、按什么维度拆。
2. 搭出多模块 Maven 项目：父工程 + 公共模块 + 业务服务。
3. 用 Nacos 做服务注册与发现。
4. 用 Nacos 做配置中心，实现配置集中管理和热更新。
5. 用 OpenFeign 完成服务间调用，处理超时和请求头传递。
6. 排查"服务没注册上""Feign 调不通"等高频问题。

---

## 4.2 什么时候该拆服务

### 4.2.1 拆分的收益

- **独立开发**：订单团队改订单代码，不用等商品团队合并。
- **独立部署**：商品服务发版，不影响订单服务。
- **独立扩容**：商品查询压力大，只扩商品服务。
- **故障隔离**：用户服务内存泄漏，不会拖垮下单。
- **技术异构**：推荐服务可以用 Python，核心交易用 Java。

### 4.2.2 拆分的代价

- 网络调用变多，**延迟增加、失败率增加**；
- **数据一致性**变难；
- **排查问题**要跨多个服务；
- **部署和监控**复杂度上升；
- 需要**注册中心、配置中心、网关、链路追踪**等基础设施。

### 4.2.3 拆分原则

| 原则 | 说明 |
|---|---|
| **按业务能力拆** | 用户、商品、订单、库存、支付，每个是一个业务领域 |
| **高内聚低耦合** | 一个服务内的功能强相关；服务间尽量少调用、少共享数据库 |
| **单一职责** | 一个服务只负责一件事，不要出现"公共业务服务"这种大杂烩 |
| **数据库私有** | 每个服务只访问自己的库，**不要跨库 JOIN**；需要的数据通过接口或消息获取 |
| **团队边界** | 康威定律：系统架构会趋同于组织架构。一个团队负责一组服务 |
| **演进式拆分** | 先单体跑通，遇到瓶颈再拆，不要一开始设计 20 个服务 |

> 反模式：按技术层拆（Controller 服务、Service 服务、DAO 服务），这是分布式单体，最糟糕。
> 反模式：一个服务 5 个接口，拆了 30 个服务，运维成本爆炸。

### 4.2.4 本教程的拆分

```text
mall-parent
├── mall-common               公共模块：Result、异常、工具类、Feign 接口定义
├── mall-user-service         用户服务：注册、登录、用户信息
├── mall-product-service      商品服务：商品 CRUD、库存
├── mall-order-service        订单服务：下单、查询订单
└── mall-gateway              网关（第 05 章加入）
```

第 06 章再加 Kafka，第 09 章加链路追踪，第 10 章整合成完整项目。

---

## 4.3 搭建多模块项目

### 4.3.1 父工程 `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>mall-parent</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>mall-common</module>
        <module>mall-user-service</module>
        <module>mall-product-service</module>
        <module>mall-order-service</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <spring-boot.version>3.2.4</spring-boot.version>
        <spring-cloud.version>2023.0.1</spring-cloud.version>
        <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
        <mybatis-plus.version>3.5.5</mybatis-plus.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>mall-common</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### 4.3.2 公共模块 `mall-common`

`pom.xml`：

```xml
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>mall-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>mall-common</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

> 公共模块只放**真正共享的东西**：统一返回、异常、常量、工具类、Feign 接口和 DTO。**不要**放业务逻辑，否则所有服务都被迫依赖它，改一次全量发版。

### 4.3.3 业务服务 `mall-product-service`

```xml
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>mall-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>mall-product-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>mall-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> Spring Cloud 2023 移除了 Ribbon，负载均衡由 `spring-cloud-starter-loadbalancer` 提供。**这个依赖必须显式加**，否则 Feign 调用报 `No instances available` 或找不到 `LoadBalancerClient`。

---

## 4.4 启动 Nacos

### 4.4.1 Docker 启动（单机模式）

```bash
docker run -d \
  --name roadmap-nacos \
  -e MODE=standalone \
  -e NACOS_AUTH_ENABLE=false \
  -p 8848:8848 \
  -p 9848:9848 \
  nacos/nacos-server:v2.3.2
```

> **Nacos 2.x 必须同时暴露 8848 和 9848**。8848 是 HTTP 控制台和 OpenAPI，9848 是 gRPC 通信端口（客户端注册/配置推送走 gRPC）。只映射 8848 会出现"控制台能打开，但服务注册不上"。

### 4.4.2 验证

浏览器打开 `http://localhost:8848/nacos`，默认账号密码都是 `nacos`。

```bash
curl "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=10"
```

> 如果你用的是 Nacos 2.2+ 且开启了鉴权，需要先登录拿 token。教程配置了 `NACOS_AUTH_ENABLE=false` 方便本地学习，**生产必须开启鉴权**。

---

## 4.5 服务注册与发现

### 4.5.1 配置文件

`bootstrap.yml` 在新版不是默认加载的。两种方式：

**方式 A（推荐，Spring Cloud 2021+）：`application.yml` + `spring.config.import`**

```yaml
server:
  port: 8081

spring:
  application:
    name: product-service
  profiles:
    active: dev
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      discovery:
        namespace: public
        group: DEFAULT_GROUP
      config:
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml
  config:
    import:
      - optional:nacos:product-service.yaml
      - optional:nacos:common.yaml
```

**方式 B（旧版兼容）：引入 `spring-cloud-starter-bootstrap`，用 `bootstrap.yml`**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

```yaml
# bootstrap.yml
spring:
  application:
    name: product-service
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yaml
```

> 本章统一用方式 A。看到网上教程用 `bootstrap.yml` 却怎么都不生效，先检查有没有引入 bootstrap starter。

### 4.5.2 启动类

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.example.mall")
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
```

`@EnableDiscoveryClient` 在 Spring Cloud 新版本中可以省略（自动配置），但显式写上更清晰。

### 4.5.3 验证注册成功

启动后：

1. Nacos 控制台 → 服务管理 → 服务列表，应该看到 `product-service`；
2. 点开详情，能看到实例 IP 和端口；
3. 调 OpenAPI：

```bash
curl "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=product-service"
```

### 4.5.4 临时实例与持久实例

| 类型 | 心跳 | 健康检查 | 适用场景 |
|---|---|---|---|
| **临时实例**（默认） | 客户端每 5 秒上报 | 心跳超时 15 秒标记不健康，30 秒剔除 | 普通微服务 |
| **持久实例** | 服务端主动探测 | TCP/HTTP 健康检查 | 需要保留实例、非临时任务 |

配置：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        ephemeral: false   # 改为持久实例
```

> 临时实例在服务下线时会自动删除；持久实例会保留，即使进程挂了也显示为不健康。一般用默认的临时实例。

### 4.5.5 服务发现原理（概念）

```text
商品服务启动
    │  ① 注册：HTTP/gRPC 上报 IP:PORT、服务名、元数据
    ▼
  Nacos
    │  ② 客户端定时拉取 + 服务端推送变更
    ▼
订单服务本地缓存服务列表
    │  ③ Feign 调用时从本地缓存选一个实例
    ▼
  商品服务实例
```

关键点：

- 调用方不是每次调用都问 Nacos，而是**本地缓存 + 订阅推送**；
- Nacos 挂了，已缓存的实例列表仍可继续用一段时间（但新服务无法注册）；
- 服务实例列表变化时，Nacos 主动推送给客户端，客户端更新本地缓存。

---

## 4.6 Nacos 配置中心

### 4.6.1 三个核心概念

| 概念 | 作用 | 示例 |
|---|---|---|
| **Namespace** | 环境隔离（开发/测试/生产） | `dev`、`test`、`prod` |
| **Group** | 业务分组 | `DEFAULT_GROUP`、`ORDER_GROUP` |
| **Data ID** | 配置文件名 | `product-service.yaml`、`common.yaml` |

完整定位：`namespace + group + dataId`。

### 4.6.2 在控制台创建配置

1. 配置管理 → 配置列表 → `+`
2. Data ID：`product-service.yaml`
3. Group：`DEFAULT_GROUP`
4. 格式：YAML
5. 内容：

```yaml
mall:
  product:
    max-stock: 9999
    cache-ttl-minutes: 30
  feature:
    new-homepage: false
```

### 4.6.3 读取配置

**方式一：`@Value` + `@RefreshScope`**

```java
@RestController
@RequestMapping("/api/config")
@RefreshScope
public class ConfigDemoController {

    @Value("${mall.product.max-stock:100}")
    private int maxStock;

    @GetMapping("/max-stock")
    public Result<Integer> maxStock() {
        return Result.success(maxStock);
    }
}
```

**方式二：`@ConfigurationProperties`（推荐，支持自动刷新）**

```java
@Data
@Component
@ConfigurationProperties(prefix = "mall.product")
public class ProductProperties {
    private int maxStock = 100;
    private int cacheTtlMinutes = 30;
}
```

> `@ConfigurationProperties` 在 Spring Cloud 环境下默认支持刷新，不需要 `@RefreshScope`。`@Value` 必须配合 `@RefreshScope` 才能刷新。

### 4.6.4 共享配置

多个服务共用的配置（数据库、Redis、日志）放到 `common.yaml`：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/roadmap_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: 123456
  data:
    redis:
      host: localhost
      port: 6379
```

各服务通过 `spring.config.import` 引入：

```yaml
spring:
  config:
    import:
      - optional:nacos:common.yaml
      - optional:nacos:${spring.application.name}.yaml
```

> 配置优先级：**Nacos 配置 > 本地 `application.yml` > 默认值**。同样的 key，Nacos 会覆盖本地。

### 4.6.5 热更新验证

1. 启动服务，访问 `/api/config/max-stock`，得到 `9999`；
2. 在 Nacos 控制台把 `max-stock` 改成 `8888`，发布；
3. 不重启服务，再次访问，得到 `8888`。

如果没生效：

- `@Value` 忘了加 `@RefreshScope`；
- 配置的 Data ID / Group / Namespace 对不上；
- 用了 `@ConfigurationProperties` 但没有 `@Component` 或 `@EnableConfigurationProperties`；
- 改的是本地文件而不是 Nacos。

---

## 4.7 OpenFeign：声明式远程调用

### 4.7.1 为什么叫"声明式"

不用写 `RestTemplate` 的 URL 拼接、序列化、异常处理，只写接口：

```java
@FeignClient(name = "product-service")
public interface ProductClient {
    @GetMapping("/api/products/{id}")
    Result<ProductVO> getById(@PathVariable("id") Long id);
}
```

Spring 在启动时生成代理，调用方法时自动发 HTTP 请求。

### 4.7.2 定义 Feign 接口

放在 `mall-common` 里，方便其他服务复用：

```java
package com.example.mall.common.client;

import com.example.mall.common.api.Result;
import com.example.mall.common.dto.ProductVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "product-service", path = "/api/products")
public interface ProductClient {

    @GetMapping("/{id}")
    Result<ProductVO> getById(@PathVariable("id") Long id);

    @PostMapping("/{id}/stock/decrease")
    Result<Void> decreaseStock(@PathVariable("id") Long id,
                               @RequestParam("count") Integer count);
}
```

**注意：**

- `@PathVariable("id")` 的 value **不能省略**，否则 Feign 不知道路径变量名；
- `@RequestParam("count")` 同理；
- 方法签名必须与提供方完全一致（路径、参数名、请求方式）；
- Feign 接口不要继承 Controller 实现类，容易出问题。

### 4.7.3 订单服务调用商品服务

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final ProductClient productClient;

    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(OrderCreateDTO dto) {
        // 1. 调商品服务查商品
        Result<ProductVO> productResult = productClient.getById(dto.getProductId());
        if (productResult.getCode() != 0 || productResult.getData() == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 校验库存
        ProductVO product = productResult.getData();
        if (product.getStock() < dto.getCount()) {
            throw new BusinessException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH);
        }

        // 3. 创建订单
        Order order = new Order();
        order.setUserId(dto.getUserId());
        order.setProductId(dto.getProductId());
        order.setCount(dto.getCount());
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(dto.getCount())));
        order.setStatus(OrderStatus.CREATED.getCode());
        orderMapper.insert(order);

        // 4. 扣库存（远程调用）
        Result<Void> decreaseResult = productClient.decreaseStock(dto.getProductId(), dto.getCount());
        if (decreaseResult.getCode() != 0) {
            throw new BusinessException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH);
        }

        log.info("订单创建成功, orderId={}", order.getId());
        return order.getId();
    }
}
```

> **注意**：这段代码在事务里调用了远程接口，是第 01 章说的"大事务"反模式。生产环境应该用第 07 章的本地消息表/最终一致性方案。这里先跑通链路，第 07 章再优化。

### 4.7.4 Feign 超时与重试

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 2000    # 连接超时，毫秒
            read-timeout: 5000       # 读取超时，毫秒
            logger-level: basic
          product-service:           # 针对某个服务的配置
            read-timeout: 3000
```

日志级别：

```yaml
logging:
  level:
    com.example.mall.common.client: debug
```

| logger-level | 输出内容 |
|---|---|
| `NONE` | 不输出（默认） |
| `BASIC` | 方法、URL、状态码、耗时 |
| `HEADERS` | 额外输出请求/响应头 |
| `FULL` | 额外输出 body（生产慎用，可能打印敏感信息） |

> **超时设置原则**：读超时要大于下游 P99 耗时，但不能无限大。一般 2-5 秒。超时后不要盲目重试**非幂等**接口（如扣库存），否则可能重复扣减。

### 4.7.5 传递请求头（Token）

微服务之间调用时，网关解析的登录信息、traceId 需要透传。

```java
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor authRequestInterceptor() {
        return template -> {
            // 从当前请求上下文取 token
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                String token = attributes.getRequest().getHeader("Authorization");
                if (token != null) {
                    template.header("Authorization", token);
                }
            }
            // traceId 透传（第 09 章会用到）
            String traceId = MDC.get("traceId");
            if (traceId != null) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}
```

> Feign 默认在子线程/异步场景下拿不到 `RequestContextHolder`，需要额外处理。同步调用场景上述代码够用。

### 4.7.6 负载均衡

Feign 集成 Spring Cloud LoadBalancer 后，`name = "product-service"` 会被解析成实例列表，默认轮询。

自定义策略：

```java
@Configuration
public class LoadBalancerConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            Environment environment, LoadBalancerClientFactory factory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new RandomLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }
}
```

> 自定义负载均衡配置类**不要放在 `@ComponentScan` 能扫到的包下**，否则会变成全局配置。放在独立包，用 `@LoadBalancerClients` 指定。

---

## 4.8 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| 控制台能开，服务注册不上 | 只映射了 8848，没映射 9848 | 暴露 9848 gRPC 端口 |
| `No instances available for product-service` | 服务没注册、服务名不一致、命名空间不同 | 检查 Nacos 服务列表、`spring.application.name`、namespace |
| Feign 调用 404 | `@FeignClient(path)` 与 Controller 路径拼接错误 | 核对完整路径 |
| Feign 参数为 null | `@PathVariable` / `@RequestParam` 没写 value | 显式写 `("id")` |
| Feign 启动报 `No Feign Client for loadBalancing` | 缺 `spring-cloud-starter-loadbalancer` | 加依赖 |
| 配置不生效 | `bootstrap.yml` 没被加载 | 用 `spring.config.import` 或加 bootstrap starter |
| 配置改了不刷新 | `@Value` 没加 `@RefreshScope` | 改用 `@ConfigurationProperties` |
| 注册的 IP 是容器内网 IP | 服务跑在 Docker 里 | 配置 `spring.cloud.nacos.discovery.ip` 为宿主机 IP |
| 版本冲突 | Spring Boot / Cloud / Alibaba 版本不匹配 | 严格按 README 的版本表 |
| 启动报 `No spring.config.import property has been defined` | 引入了 nacos-config 但没配 import | 加 `spring.config.import: optional:nacos:...` |

### 版本对照再强调一次

| Spring Boot | Spring Cloud | Spring Cloud Alibaba |
|---|---|---|
| 3.2.x | 2023.0.x | 2023.0.1.0 |
| 3.1.x | 2022.0.x | 2022.0.0.0 |
| 2.7.x | 2021.0.x | 2021.0.5.0 |

版本不匹配时，报错往往莫名其妙（NoSuchMethodError、ClassNotFoundException），先查版本。

---

## 4.9 动手实验

### 实验 1：注册两个服务

1. 启动 Nacos；
2. 启动 `product-service`（8081）和 `order-service`（8082）；
3. 在 Nacos 控制台确认两个服务都在；
4. 停掉 `product-service`，观察 Nacos 实例状态变成不健康，再到消失；
5. 重新启动，观察恢复。

### 实验 2：Feign 调用

```bash
curl -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":1001,"count":2}'
```

观察订单服务日志：是否有 Feign 请求日志、是否成功调用商品服务。

### 实验 3：配置中心热更新

1. 在 Nacos 创建 `product-service.yaml`，写入 `mall.product.max-stock=9999`；
2. 启动商品服务，访问配置接口；
3. 在 Nacos 改成 `8888` 并发布；
4. 不重启，再次访问，验证生效。

### 实验 4：模拟下游超时

在商品服务的查询接口里加 `Thread.sleep(6000)`，调用订单接口，观察 Feign 在 5 秒后抛出 `ReadTimeoutException`。然后调整 `read-timeout` 再试。

### 实验 5：排查服务列表

```bash
curl "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=product-service"
```

观察返回的 `hosts` 数组里 IP、端口、健康状态。

---

## 4.10 练习题

1. 把第 01 章的商品服务改造成独立微服务，注册到 Nacos。
2. 新建用户服务，实现注册/登录，订单服务下单前通过 Feign 校验用户是否存在。
3. 在 Nacos 配置中心实现"商品服务最大库存"动态配置，修改后不重启生效。
4. 给 Feign 加一个统一异常解码器：当下游返回 `code != 0` 时，抛出对应的 `BusinessException`。
5. **思考题**：为什么 Nacos 客户端要本地缓存服务列表？如果 Nacos 整体挂掉，已经运行的服务还能互相调用吗？
6. **思考题**：公共模块 `mall-common` 里放 Feign 接口有什么好处和坏处？

---

## 4.11 本章小结

1. **微服务按业务能力拆，数据库私有，先单体后拆分**；拆分的收益是独立开发和扩容，代价是分布式复杂度。
2. **Nacos 同时解决两个问题**：注册中心（服务地址动态发现）和配置中心（配置集中管理 + 热更新）。
3. **Nacos 2.x 需要 8848 + 9848 两个端口**，注册不上先查端口。
4. **OpenFeign 让远程调用像本地方法**，但要注意参数注解、超时、请求头透传和幂等。
5. **版本对齐是微服务入门的第一道坎**，严格按对照表。

---

下一章：[第 05 章 Gateway 与 Sentinel](05-Gateway与Sentinel.md)
