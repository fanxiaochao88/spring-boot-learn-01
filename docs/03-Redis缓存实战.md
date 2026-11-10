# 第 03 章 Redis 缓存实战

> 预计用时：8 小时
> 缓存是后端性能优化的第一把刀，也是最容易出事故的地方。本章既讲怎么用，也讲怎么不把系统搞挂。

---

## 3.1 学习目标

读完并动手后，你能：

1. 说清楚 Redis 五种基本数据类型及典型使用场景。
2. 在 Spring Boot 中正确集成 Redis，处理序列化问题。
3. 理解并实现 Cache Aside 缓存模式。
4. 应对缓存穿透、击穿、雪崩三大问题。
5. 处理缓存与数据库的一致性问题。
6. 用 Redisson 实现可靠的分布式锁。

---

## 3.2 为什么需要 Redis

第 02 章讲过，MySQL 再优化也有上限：

- 磁盘 IO 比内存慢几个数量级；
- 复杂查询、聚合、排序消耗 CPU；
- 连接数有限，高并发下容易打满。

商品详情页每秒 5 万次查询，每次都打 MySQL，数据库扛不住。而这类数据的特点是：

- **读多写少**；
- **允许短暂不一致**（价格晚 1 秒更新，用户无感）；
- **数据量可控**（热点数据只占一小部分）。

所以把它放进内存。Redis 单机可达 10 万级 QPS，天然适合这个场景。

> 缓存的本质是**用空间换时间**，同时接受**短暂的数据不一致**。

---

## 3.3 Redis 数据类型与场景

| 类型 | 结构 | 典型场景 | 常用命令 |
|---|---|---|---|
| **String** | key-value | 缓存对象、计数器、分布式锁 | `SET`、`GET`、`INCR`、`SETNX`、`EXPIRE` |
| **Hash** | key-field-value | 存储对象的部分字段、购物车 | `HSET`、`HGET`、`HGETALL`、`HINCRBY` |
| **List** | 双向链表 | 消息队列（简单版）、最新列表 | `LPUSH`、`RPOP`、`LRANGE` |
| **Set** | 无序集合 | 去重、共同好友、标签 | `SADD`、`SISMEMBER`、`SINTER` |
| **ZSet** | 有序集合（带 score） | 排行榜、延迟队列 | `ZADD`、`ZRANGE`、`ZINCRBY` |
| **Bitmap** | 位图 | 签到、在线状态 | `SETBIT`、`GETBIT`、`BITCOUNT` |
| **HyperLogLog** | 基数估算 | UV 统计（有误差） | `PFADD`、`PFCOUNT` |
| **Stream** | 消息流 | 轻量消息队列 | `XADD`、`XREAD`、`XGROUP` |

### 3.3.1 Key 命名规范

```text
业务:模块:标识
product:detail:1001
user:token:abc123
order:lock:202611090001
```

- 用冒号分隔层级；
- 不要用中文、空格、特殊字符；
- 控制长度（key 也占内存）；
- **必须设置过期时间**，除非明确是持久数据。

### 3.3.2 过期策略与内存淘汰

Redis 不会精确地在过期时删除 key，而是：

- **惰性删除**：访问时发现过期才删；
- **定期删除**：每秒随机抽查一部分。

内存满了之后按 `maxmemory-policy` 淘汰：

| 策略 | 含义 |
|---|---|
| `noeviction` | 不淘汰，写入报错（默认） |
| `allkeys-lru` | 所有 key 中淘汰最久未使用的（**缓存场景推荐**） |
| `allkeys-lfu` | 淘汰访问频率最低的 |
| `volatile-lru` | 只淘汰设置了过期时间的 |
| `allkeys-random` | 随机淘汰 |

生产环境一定要配置 `maxmemory` 和淘汰策略，否则 Redis 可能把机器内存吃光。

---

## 3.4 Spring Boot 集成 Redis

### 3.4.1 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- 连接池 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

### 3.4.2 配置

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:            # 没有就留空
      database: 0
      timeout: 3s
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 2
          max-wait: 2s
```

> Spring Boot 3 的配置前缀是 `spring.data.redis`，不是旧版的 `spring.redis`。

### 3.4.3 序列化配置（必须改）

默认的 `JdkSerializationRedisSerializer` 会把 key 和 value 序列化成二进制，`redis-cli` 里看到乱码。改成 JSON：

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
```

**为什么要 `activateDefaultTyping`？** 反序列化时 JSON 里没有类型信息，取出来会是 `LinkedHashMap` 而不是 `Product`。加上类型信息后能正确还原。

> 安全提示：`activateDefaultTyping` 有反序列化漏洞风险，务必配合白名单（`PolymorphicTypeValidator`）。生产环境更推荐针对具体类型使用 `Jackson2JsonRedisSerializer<Product>`，或者直接用 `StringRedisTemplate` 存 JSON 字符串，手动 `ObjectMapper` 转换。

### 3.4.4 简单用法

```java
@Service
@RequiredArgsConstructor
public class RedisDemoService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    public void demo() {
        // String
        stringRedisTemplate.opsForValue().set("demo:name", "键盘", Duration.ofMinutes(10));
        String name = stringRedisTemplate.opsForValue().get("demo:name");

        // Hash
        stringRedisTemplate.opsForHash().put("demo:cart:1", "1001", "2");
        Object count = stringRedisTemplate.opsForHash().get("demo:cart:1", "1001");

        // 计数器
        Long views = stringRedisTemplate.opsForValue().increment("demo:views:1001");

        // 对象（JSON）
        redisTemplate.opsForValue().set("demo:product:1001", new ProductVO(1001L, "键盘"), Duration.ofMinutes(30));
        ProductVO product = (ProductVO) redisTemplate.opsForValue().get("demo:product:1001");
    }
}
```

---

## 3.5 缓存模式：Cache Aside

最常见的模式，也叫"旁路缓存"。

**读流程：**

```text
读缓存 → 命中 → 返回
       → 未命中 → 查数据库 → 写缓存 → 返回
```

**写流程：**

```text
更新数据库 → 删除缓存
```

### 3.5.1 完整代码

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductCacheService {

    private static final String KEY_PREFIX = "product:detail:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProductVO getById(Long id) {
        String key = KEY_PREFIX + id;

        // 1. 读缓存
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("缓存命中, id={}", id);
            return readJson(cached);
        }

        // 2. 未命中，查数据库（加分布式锁防击穿，见 3.7）
        Product product = productMapper.selectById(id);
        if (product == null) {
            // 防穿透：缓存空值，短 TTL
            redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(2));
            return null;
        }

        // 3. 写缓存
        ProductVO vo = toVO(product);
        redisTemplate.opsForValue().set(key, writeJson(vo), TTL);
        return vo;
    }

    public void update(ProductUpdateDTO dto) {
        // 先更新数据库
        productMapper.updateById(toEntity(dto));
        // 再删除缓存
        redisTemplate.delete(KEY_PREFIX + dto.getId());
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private ProductVO readJson(String json) {
        if ("NULL".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ProductVO.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }
}
```

### 3.5.2 为什么是"删除缓存"而不是"更新缓存"

- **更新缓存**：如果并发写，后写的可能先执行，缓存里留下旧值；而且很多写操作最终并不会被读到，白算一次。
- **删除缓存**：下次读时按需重建，逻辑简单，最终一致。

### 3.5.3 先删缓存还是先更新数据库

| 顺序 | 问题 |
|---|---|
| 先删缓存，再更新数据库 | 删除后、更新前，另一个读请求把**旧值**重新加载进缓存，之后数据库更新了，缓存还是旧的 |
| **先更新数据库，再删缓存**（推荐） | 极端情况下（读请求在更新前查库、在删除后写缓存）仍可能不一致，但概率极低 |

**延迟双删**（进一步降低概率）：

```java
public void update(ProductUpdateDTO dto) {
    redisTemplate.delete(key);              // 先删一次
    productMapper.updateById(entity);       // 更新数据库
    // 延迟一段时间再删一次，覆盖"读请求回填旧值"的窗口
    executor.schedule(() -> redisTemplate.delete(key), 500, TimeUnit.MILLISECONDS);
}
```

> 强一致场景不要依赖缓存一致性方案，直接读库或用分布式锁。大多数业务接受最终一致。

---

## 3.6 三大缓存问题

### 3.6.1 缓存穿透

**问题**：查询一个**数据库里也不存在**的数据，缓存永远不命中，每次请求都打到数据库。恶意攻击者可以用不存在的 ID 刷接口。

**方案：**

1. **缓存空值**（简单有效）：

```java
if (product == null) {
    redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(2));
    return null;
}
```

2. **布隆过滤器**（数据量大时）：

```java
// 启动时把所有存在的 id 放进布隆过滤器
if (!bloomFilter.mightContain(id)) {
    return null;  // 一定不存在
}
```

布隆过滤器说"不存在"一定不存在；说"存在"可能误判。适合"先挡掉绝大多数非法请求"。

3. **参数校验**：ID 必须为正数、格式合法。

### 3.6.2 缓存击穿

**问题**：某个**热点 key 过期**的瞬间，大量请求同时未命中，全部打到数据库。

**方案一：互斥锁（只让一个线程重建）**

```java
public ProductVO getByIdWithMutex(Long id) {
    String key = KEY_PREFIX + id;
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) {
        return readJson(cached);
    }

    String lockKey = "lock:product:" + id;
    // 尝试获取锁，10 秒过期
    Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

    if (Boolean.TRUE.equals(locked)) {
        try {
            // 双重检查：可能别的线程已经重建好了
            cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return readJson(cached);
            }
            Product product = productMapper.selectById(id);
            if (product == null) {
                redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(2));
                return null;
            }
            ProductVO vo = toVO(product);
            redisTemplate.opsForValue().set(key, writeJson(vo), TTL);
            return vo;
        } finally {
            // 释放锁（生产环境用 Lua 保证原子性，见 3.8）
            redisTemplate.delete(lockKey);
        }
    }

    // 没抢到锁：短暂休眠后重试读缓存
    try {
        Thread.sleep(50);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    return getByIdWithMutex(id);
}
```

**方案二：逻辑过期（不设置物理过期，异步重建）**

缓存 value 里带一个 `expireAt` 字段：

```json
{ "data": {...}, "expireAt": 1730000000000 }
```

读到时判断逻辑过期，如果过期，返回旧数据，同时异步开线程重建。**保证接口永远不阻塞**，代价是可能短暂返回旧数据。

| 方案 | 优点 | 缺点 |
|---|---|---|
| 互斥锁 | 数据一致性好 | 未抢到锁的请求要等待 |
| 逻辑过期 | 响应快、不阻塞 | 可能返回旧数据，实现复杂 |

### 3.6.3 缓存雪崩

**问题**：**大量 key 同时过期**，或 Redis 整个挂掉，请求全部打到数据库，数据库被打死。

**方案：**

1. **过期时间加随机值**：

```java
Duration ttl = Duration.ofMinutes(30 + ThreadLocalRandom.current().nextInt(10));
```

2. **多级缓存**：本地缓存（Caffeine）+ Redis + MySQL。
3. **Redis 高可用**：主从 + 哨兵 / Cluster。
4. **限流降级**：数据库前面有限流，Redis 挂掉时返回兜底数据（第 05 章 Sentinel）。
5. **缓存预热**：启动时加载热点数据。

---

## 3.7 分布式锁

### 3.7.1 为什么需要

单机 `synchronized` 只能锁住一个 JVM。服务部署 3 个实例时，三个线程在不同 JVM 里，`synchronized` 完全无效。

```java
// 错误：多实例下无效
synchronized (this) {
    int stock = product.getStock();
    if (stock > 0) {
        product.setStock(stock - 1);
    }
}
```

### 3.7.2 最简实现：SETNX + 过期时间

```java
public boolean tryLock(String key, String value, Duration ttl) {
    Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
    return Boolean.TRUE.equals(ok);
}

public void unlock(String key, String value) {
    // 必须用 Lua 保证"判断 value + 删除"的原子性
    String script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;
    redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(key), value);
}
```

**几个必须注意的点：**

1. **加过期时间**，否则持锁进程崩溃后锁永远不释放；
2. **value 用唯一值**（UUID），防止误删别人的锁；
3. **释放锁必须用 Lua**，保证"比较 + 删除"原子；
4. **过期时间要大于业务执行时间**，否则业务没执行完锁就没了；
5. **业务执行超过过期时间**仍会出问题——这就是 Redisson 看门狗要解决的。

### 3.7.3 生产方案：Redisson

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

```java
@Service
@RequiredArgsConstructor
public class StockService {

    private final RedissonClient redissonClient;
    private final ProductMapper productMapper;

    public void decreaseStock(Long productId, int count) {
        RLock lock = redissonClient.getLock("lock:stock:" + productId);
        boolean locked = false;
        try {
            // 最多等 3 秒，锁 10 秒自动释放（看门狗会自动续期）
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException(ResultCode.CONFLICT, "系统繁忙，请稍后重试");
            }

            Product product = productMapper.selectById(productId);
            if (product.getStock() < count) {
                throw new BusinessException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH);
            }
            productMapper.decreaseStock(productId, count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR);
        } finally {
            // 只释放自己持有的锁
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

**Redisson 的优势：**

- **看门狗**：默认锁 30 秒，业务没执行完会自动续期，避免锁提前释放；
- **可重入**：同一线程可以重复获取；
- **释放安全**：内部用 Lua 校验持有者；
- **支持公平锁、读写锁、红锁**。

> 看门狗只在**不指定 leaseTime** 时生效。`tryLock(3, 10, SECONDS)` 指定了 10 秒，看门狗不会续期。想用看门狗就用 `tryLock(3, TimeUnit.SECONDS)`。

### 3.7.4 分布式锁的边界

- Redis 主从架构下，主节点加锁后未同步就宕机，从节点升主，锁可能丢失。极端一致性要求用 **RedLock** 或 ZooKeeper。
- 分布式锁只能保证"同一时刻只有一个执行"，不能保证业务正确性。库存扣减还需要数据库的 `stock >= count` 条件更新兜底。

```sql
-- 最终防线：数据库乐观更新
UPDATE product SET stock = stock - #{count}
WHERE id = #{id} AND stock >= #{count};
```

受影响行数为 0 就说明库存不足。

---

## 3.8 缓存使用 checklist

上线前逐条检查：

- [ ] 所有 key 都设置了过期时间（或明确持久化）
- [ ] 序列化方式统一，`redis-cli` 可读
- [ ] 缓存空值或布隆过滤器防穿透
- [ ] 热点 key 有防击穿措施
- [ ] TTL 加了随机值
- [ ] 缓存更新使用"先更库、再删缓存"
- [ ] 分布式锁有唯一 value + Lua 释放 + 合理过期时间
- [ ] 大 key / 热 key 有监控
- [ ] Redis 有密码、禁止公网访问、绑定内网
- [ ] 配置了 `maxmemory` 和淘汰策略

---

## 3.9 常见坑

1. **序列化不一致**：A 服务用 JDK 序列化写入，B 服务用 JSON 读取，直接报错。
2. **`RedisTemplate` 和 `StringRedisTemplate` 混用**：前者可能带类型信息，后者是纯字符串，取出来类型不对。
3. **忘记设过期时间**：内存持续增长，最后 OOM。
4. **大 key**：一个 key 存几 MB 的 List/JSON，删除和读取都会阻塞 Redis。拆分成多个小 key。
5. **热 key**：某个 key 每秒几十万访问，单节点打满。用本地缓存、key 散列成多个副本。
6. **`KEYS *`**：生产环境禁用，会阻塞 Redis。用 `SCAN` 渐进遍历。
7. **缓存和数据库双写不一致**：见 3.5.3，接受最终一致或加锁。
8. **锁过期时间小于业务时间**：业务还在执行，锁已释放，另一个线程进入。
9. **`unlock` 没用 Lua**：判断和删除之间锁过期，可能删掉别人的锁。
10. **把 Redis 当数据库**：Redis 持久化不是强保证，重要数据必须落 MySQL。

---

## 3.10 动手实验

### 实验 1：启动 Redis 并观察序列化

```bash
docker exec -it roadmap-redis redis-cli
```

```redis
SET demo:key hello
GET demo:key
TTL demo:key        # -1 表示永不过期（危险）
SET demo:key2 hello EX 60
TTL demo:key2       # 60

HSET demo:cart:1 1001 2
HGETALL demo:cart:1

ZADD demo:rank 100 user1 200 user2
ZREVRANGE demo:rank 0 -1 WITHSCORES
```

### 实验 2：给商品接口加缓存

把第 01 章的商品查询接口改造成 Cache Aside 模式，用 `wrk` 或 JMeter 压测改造前后 QPS 差异。

### 实验 3：复现缓存穿透

```java
// 查询一个不存在的 id，观察数据库日志是否每次都执行
productCacheService.getById(999999L);
```

先不加空值缓存，循环 100 次，看 SQL 日志；再加空值缓存，观察第二次之后是否还查库。

### 实验 4：Redisson 分布式锁

启动两个服务实例（端口 8080、8081），同时请求扣库存接口，观察是否只有一个成功。

---

## 3.11 练习题

1. 用 String 实现一个接口访问计数器，每分钟自动过期。
2. 用 ZSet 实现"今日热搜商品 Top 10"。
3. 给商品详情接口实现"互斥锁防击穿"，并用 JMeter 验证只有一个请求打到数据库。
4. 写一个 Redis 分布式锁，要求：唯一 value、Lua 释放、可重入（提示：Hash 存 `线程ID -> 重入次数`）。
5. **思考题**：为什么"先更新数据库、再删缓存"比"先删缓存、再更新数据库"更好？举一个后者出问题的时序。
6. **思考题**：Redis 主从切换时，分布式锁可能丢失，业务上如何兜底？

---

## 3.12 本章小结

1. **缓存用空间换时间，接受短暂不一致**；绝大部分读多写少的场景都值得加。
2. **Cache Aside 是主流模式**：读时回填、写时删缓存；先更库再删缓存。
3. **穿透、击穿、雪崩是三种不同问题**：穿透查不存在的数据，击穿是热点 key 过期，雪崩是大量 key 同时失效。
4. **分布式锁必须有过期时间、唯一 value、Lua 释放**；生产环境用 Redisson。
5. **缓存不是万能的**，最终一致性和数据库兜底条件更新必须考虑。

---

下一章：[第 04 章 微服务拆分与 Nacos](04-微服务拆分与Nacos.md)
