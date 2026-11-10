# 第 06 章 Kafka 消息队列

> 预计用时：10 小时
> 下单后加积分、发短信、写日志，这些事不该让用户等。消息队列就是"先把活接下来，稍后慢慢干"的基础设施。

---

## 6.1 学习目标

读完并动手后，你能：

1. 说清楚消息队列的三大用途：解耦、异步、削峰。
2. 理解 Kafka 的核心模型：Broker、Topic、Partition、Replica、Offset、Consumer Group。
3. 在 Spring Boot 中完成生产者和消费者开发。
4. 处理消息可靠性：不丢、不重复、顺序。
5. 配置重试和死信队列。
6. 排查消费者不消费、重复消费、消息积压等问题。

---

## 6.2 为什么需要消息队列

以"下单"为例，同步流程：

```text
用户点击下单
  → 校验库存
  → 创建订单
  → 扣库存
  → 加积分          （200ms）
  → 发优惠券        （300ms）
  → 发短信          （500ms）
  → 写操作日志      （100ms）
  → 返回成功
```

用户等了 **1.1 秒以上**，其中真正重要的只有前 3 步。

改成异步：

```text
用户点击下单
  → 校验库存
  → 创建订单
  → 扣库存
  → 发送"订单已创建"消息到 Kafka
  → 立即返回（200ms）

积分服务、优惠券服务、短信服务、日志服务各自订阅消息，异步处理
```

### 6.2.1 三大用途

| 用途 | 说明 | 例子 |
|---|---|---|
| **异步** | 非核心流程异步执行，缩短响应时间 | 下单后发短信、加积分 |
| **解耦** | 生产者不需要知道有多少消费者 | 订单服务不用改代码就能接入新的积分服务 |
| **削峰** | 突发流量先积压在队列，消费者按能力处理 | 秒杀请求先入队，后端匀速消费 |

### 6.2.2 引入 MQ 的代价

- **系统复杂度上升**：多一个中间件要运维；
- **数据一致性变弱**：消息可能丢失、重复、乱序；
- **排查变难**：请求链路跨了 MQ，日志不连续；
- **消息积压**：消费者挂了，消息堆积，恢复后要处理很久。

> 所以：**核心同步流程不要盲目异步化**。订单创建、扣库存这种必须强一致的，不要拆到 MQ 两边。

---

## 6.3 Kafka 核心模型

```text
                    ┌─────────────────────────────────────┐
                    │              Kafka Cluster           │
                    │                                     │
  Producer ────────►│  Topic: order-created               │
                    │   ┌──────────┬──────────┬────────┐  │
                    │   │Partition0│Partition1│Partition2│ │
                    │   │ [0,1,2..]│ [0,1,2..]│ [0,1,2..]│ │
                    │   └──────────┴──────────┴────────┘  │
                    │        ▲          ▲          ▲      │
                    │        │ 副本同步  │          │      │
                    │   ┌────┴────┐ ┌───┴─────┐ ┌──┴───┐  │
                    │   │Broker 1 │ │Broker 2 │ │Broker3│ │
                    │   └─────────┘ └─────────┘ └───────┘  │
                    └─────────────────────────────────────┘
                              ▲
                              │ 拉取
                    ┌─────────┴─────────┐
                    │  Consumer Group   │
                    │  consumer1 ─ P0   │
                    │  consumer2 ─ P1,P2│
                    └───────────────────┘
```

### 6.3.1 核心概念

| 概念 | 含义 | 类比 |
|---|---|---|
| **Broker** | 一个 Kafka 服务节点 | 一台邮局 |
| **Topic** | 消息的逻辑分类 | 一个频道/公告栏 |
| **Partition** | Topic 的分区，是并行和顺序的基本单位 | 公告栏的一列 |
| **Replica** | 分区的副本，保证高可用 | 每列公告拍照备份 |
| **Leader / Follower** | 读写走 Leader，Follower 同步 | 主备份 |
| **Offset** | 消息在分区内的唯一序号 | 公告的编号 |
| **Consumer Group** | 消费者组，组内分摊分区 | 一组人分工读公告 |
| **Rebalance** | 消费者增减时重新分配分区 | 重新分工 |

### 6.3.2 几个关键理解

1. **Topic 只是逻辑概念**，消息实际存在 Partition 里。
2. **Partition 内有序，Partition 间无序**。要保证同一订单的消息顺序，就把同一个 `orderId` 发到同一个分区（用 key）。
3. **一个 Partition 同一时刻只能被同组的一个消费者消费**。消费者数超过分区数，多出来的会空闲。
4. **不同 Consumer Group 互不影响**，各自维护自己的 offset。订单服务消费了，积分服务还能再消费一遍。
5. **Kafka 的消息不会因为被消费就删除**，而是按保留时间/大小删除（默认 7 天）。这是它和传统队列最大的区别之一。

### 6.3.3 为什么要分区

- **水平扩展**：一个 Topic 的吞吐量受分区数限制，多分区可以分散到多台 Broker。
- **并行消费**：多个消费者同时消费不同分区。
- **顺序保证**：分区内有序。

分区数不是越多越好：分区多会增加文件句柄、选举时间、端到端延迟。一般按"目标吞吐 / 单分区吞吐"估算，常见 3-12 个。

---

## 6.4 启动 Kafka（KRaft 模式）

Kafka 3.3+ 可以用 KRaft 模式，不再需要 ZooKeeper，部署更简单。

`docker-compose.yml` 片段：

```yaml
services:
  kafka:
    image: bitnami/kafka:3.7
    container_name: roadmap-kafka
    ports:
      - "29092:29092"
    environment:
      - KAFKA_CFG_NODE_ID=1
      - KAFKA_CFG_PROCESS_ROLES=broker,controller
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=1@kafka:9093
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:29092
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092,EXTERNAL://localhost:29092
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_CFG_INTER_BROKER_LISTENER_NAME=PLAINTEXT
      - ALLOW_PLAINTEXT_LISTENER=yes
    volumes:
      - kafka-data:/bitnami/kafka

volumes:
  kafka-data:
```

**关键配置解释：**

- `LISTENERS`：Broker 监听哪些地址；
- `ADVERTISED_LISTENERS`：**告诉客户端"你应该用这个地址连我"**。容器内服务用 `kafka:9092`，宿主机用 `localhost:29092`；
- 如果配错 `advertised.listeners`，宿主机能连上，但拿到的是容器内 IP，后续连接失败——这是 Kafka 最高频的坑。

验证：

```bash
# 进入容器
docker exec -it roadmap-kafka bash

# 创建 topic
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic order-created --partitions 3 --replication-factor 1

# 查看 topic
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-created

# 控制台生产
kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-created

# 另开终端消费
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic order-created --from-beginning
```

> 单机学习用 `replication-factor 1` 即可；生产至少 3。

---

## 6.5 Spring Kafka 集成

### 6.5.1 依赖

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

### 6.5.2 配置

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:29092

    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        linger.ms: 10
        batch.size: 16384

    consumer:
      group-id: order-points-group
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.example.mall.*"
        max.poll.records: 100

    listener:
      ack-mode: manual_immediate
      concurrency: 3
```

**逐项解释：**

| 配置 | 作用 | 建议 |
|---|---|---|
| `acks=all` | Leader 和所有 ISR 副本都确认才算成功 | 可靠性优先 |
| `retries` | 发送失败重试次数 | 配合幂等 |
| `enable.idempotence` | 生产者幂等，避免重试导致重复 | 开启 |
| `linger.ms` | 攒批等待时间 | 10-50ms，吞吐与延迟权衡 |
| `enable-auto-commit=false` | 关闭自动提交 offset | **必须**，否则处理失败也提交了 |
| `auto-offset-reset=earliest` | 新消费者组从最早消息开始 | 学习用；生产按业务 |
| `spring.json.trusted.packages` | JSON 反序列化白名单 | 必须配，否则报错 |
| `concurrency` | 每个监听器的并发消费者数 | 不超过分区数 |
| `max.poll.records` | 一次拉取多少条 | 处理慢就调小 |

### 6.5.3 定义事件对象

```java
package com.example.mall.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private Long orderId;
    private Long userId;
    private Long productId;
    private Integer count;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
```

> 事件对象要**向后兼容**：只加字段、不删字段、不改字段含义。否则老消费者反序列化失败。

### 6.5.4 生产者

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    public static final String TOPIC_ORDER_CREATED = "order-created";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendOrderCreated(OrderCreatedEvent event) {
        // key 用 orderId，保证同一订单的消息进入同一分区、有序
        String key = String.valueOf(event.getOrderId());

        kafkaTemplate.send(TOPIC_ORDER_CREATED, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("发送订单创建消息失败, orderId={}", event.getOrderId(), ex);
                        // 生产环境：落本地消息表，定时补偿重发
                    } else {
                        log.info("发送订单创建消息成功, orderId={}, partition={}, offset={}",
                                event.getOrderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
```

### 6.5.5 消费者

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PointsConsumer {

    private final PointsService pointsService;

    @KafkaListener(
            topics = OrderEventProducer.TOPIC_ORDER_CREATED,
            groupId = "points-group"
    )
    public void onOrderCreated(OrderCreatedEvent event, Acknowledgment ack) {
        log.info("收到订单创建消息, orderId={}", event.getOrderId());
        try {
            // 幂等：同一订单只加一次积分
            pointsService.addPointsIdempotent(event.getUserId(), event.getOrderId(), event.getAmount());
            ack.acknowledge();   // 处理成功后才提交 offset
        } catch (Exception e) {
            log.error("处理订单积分失败, orderId={}", event.getOrderId(), e);
            // 不 ack，抛异常交给 ErrorHandler 重试
            throw e;
        }
    }
}
```

### 6.5.6 手动提交 offset 的几种模式

| ack-mode | 行为 |
|---|---|
| `RECORD` | 每条处理完提交 |
| `BATCH`（默认） | 一批处理完提交 |
| `MANUAL` | 调用 `acknowledge()` 后，在下一次 poll 时提交 |
| `MANUAL_IMMEDIATE` | 调用 `acknowledge()` 立即提交 |
| `TIME` | 按时间提交 |

> 推荐 `MANUAL_IMMEDIATE`：处理成功才提交，失败不提交，重启后能重新消费。代价是可能重复消费（至少一次语义）。

### 6.5.7 重试与死信队列

消费者处理失败时，不能无限重试（毒消息会阻塞分区）。Spring Kafka 提供 `DefaultErrorHandler`：

```java
@Configuration
public class KafkaErrorConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // 重试 3 次，间隔 1 秒
        FixedBackOff backOff = new FixedBackOff(1000L, 3);

        // 重试仍失败，发送到死信 Topic：order-created.DLT
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 某些异常不重试，直接进死信
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
```

死信消费者：

```java
@KafkaListener(topics = "order-created.DLT", groupId = "order-dlt-group")
public void onDeadLetter(OrderCreatedEvent event, Acknowledgment ack) {
    log.error("进入死信队列, orderId={}", event.getOrderId());
    // 告警、人工处理、落库待补偿
    ack.acknowledge();
}
```

> 死信队列不是"垃圾桶"，必须有告警和人工处理流程，否则消息等于丢了。

---

## 6.6 消息可靠性：不丢、不重、有序

### 6.6.1 不丢消息

消息可能在三个阶段丢失：

| 阶段 | 丢失原因 | 措施 |
|---|---|---|
| **生产** | 网络问题、Broker 未确认 | `acks=all`、`retries`、`enable.idempotence`、发送失败落库补偿 |
| **存储** | Broker 宕机、副本未同步 | `replication.factor >= 3`、`min.insync.replicas=2`、`unclean.leader.election=false` |
| **消费** | 自动提交 offset，但业务没处理完 | 手动提交、处理成功后再 ack |

**生产端最稳的做法：本地消息表**

```text
1. 在同一个数据库事务里：写业务表 + 写消息表（status=待发送）
2. 事务提交后，异步发送 Kafka
3. 发送成功：更新消息表 status=已发送
4. 定时任务扫描 status=待发送 且超时的记录，重发
```

这样即使服务在"业务提交后、消息发送前"崩溃，定时任务也能补发。

### 6.6.2 不重复消费：幂等

Kafka 默认是 **at-least-once**（至少一次），网络抖动、消费者重启、重试都会导致重复。**消费者必须做幂等**。

| 方案 | 实现 | 适用 |
|---|---|---|
| **数据库唯一索引** | 消息 ID / 业务 ID 建唯一键，重复插入报错忽略 | 最可靠 |
| **去重表** | 消费前查/插一条去重记录 | 通用 |
| **状态机** | 订单只能从"待支付"变"已支付"，重复消息不满足前置状态 | 有状态业务 |
| **Redis SETNX** | `SET msgId 1 NX EX 86400`，失败说明处理过 | 高性能、允许极端丢失 |

```java
@Transactional
public void addPointsIdempotent(Long userId, Long orderId, BigDecimal amount) {
    // 唯一索引：uk_order_id(order_id)
    try {
        pointsRecordMapper.insert(new PointsRecord(userId, orderId, amount));
    } catch (DuplicateKeyException e) {
        log.info("订单积分已处理，忽略重复消息, orderId={}", orderId);
        return;
    }
    pointsMapper.addPoints(userId, amount.intValue());
}
```

> "先查再插"在并发下不安全，两个线程可能都查到不存在。**依赖数据库唯一索引**才是最终防线。

### 6.6.3 顺序消息

Kafka 只保证**分区内有序**。

- 同一订单的多条消息：`key = orderId` → 同一分区 → 有序；
- 全局有序：只能用一个分区，牺牲吞吐，通常不需要；
- 消费端多线程处理会破坏顺序：如果监听器内部再开线程池，要保证同一 key 路由到同一线程。

### 6.6.4 消息积压怎么办

**先定位原因：**

```bash
# 查看消费者组积压
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group points-group
```

看 `LAG` 列（未消费消息数）。

**常见原因：**

1. 消费者处理慢（数据库慢、远程调用慢）；
2. 消费者数量少于分区数；
3. 消费者频繁 rebalance（处理时间超过 `max.poll.interval.ms`）；
4. 某条毒消息一直重试。

**处理：**

- 临时扩容消费者到分区数；
- 优化消费逻辑，批量处理；
- 把消息转到临时 Topic，用更多分区并行处理（紧急方案）；
- 调大 `max.poll.interval.ms`，减小 `max.poll.records`；
- 毒消息进死信队列。

---

## 6.7 Kafka vs RocketMQ vs RabbitMQ

| 维度 | Kafka | RocketMQ | RabbitMQ |
|---|---|---|---|
| 定位 | 高吞吐日志/流处理 | 金融级业务消息 | 灵活路由、低延迟 |
| 吞吐 | 极高（百万级/s） | 高（十万级/s） | 中（万级/s） |
| 延迟 | 毫秒级 | 毫秒级 | 微秒级 |
| 顺序消息 | 分区内有序 | 支持严格顺序 | 较弱 |
| 延迟消息 | 不支持（需自研） | 支持 | 支持（插件） |
| 事务消息 | 支持（较复杂） | 支持完善 | 不支持 |
| 消息回溯 | 支持（按 offset/时间） | 支持 | 不支持 |
| 运维 | 较复杂 | 中等 | 简单 |
| 典型场景 | 日志、埋点、大数据 | 电商交易、订单 | 业务解耦、任务队列 |

> 公司用什么就学什么，但**核心概念是相通的**：Topic、分区、消费者组、offset、ACK。学会 Kafka 再看 RocketMQ，一天就能上手。

---

## 6.8 动手实验

### 实验 1：控制台收发

```bash
docker exec -it roadmap-kafka bash
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic test-topic --partitions 3 --replication-factor 1
kafka-console-producer.sh --bootstrap-server localhost:9092 --topic test-topic
```

另开终端：

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic test-topic --from-beginning
```

输入消息，观察消费。

### 实验 2：Spring 生产者 + 消费者

1. 订单服务发送 `OrderCreatedEvent`；
2. 积分服务消费并打印；
3. 观察日志里的 partition 和 offset；
4. 用相同 `orderId` 发多条，验证进入同一分区。

### 实验 3：消费者组

启动两个积分服务实例（不同端口，**相同 groupId**），发送 10 条消息，观察两个实例分摊消费。

再把其中一个实例的 `groupId` 改成别的，观察两个组各自收到全部消息。

### 实验 4：模拟重复消费

在消费者里故意抛异常，观察重试 3 次后进入死信队列；再检查积分是否只加了一次（幂等验证）。

### 实验 5：查看积压

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group points-group
```

停掉消费者，发 1000 条消息，观察 `LAG` 增长；重启消费者，观察 `LAG` 下降。

---

## 6.9 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| 宿主机连不上，容器内正常 | `advertised.listeners` 配的是容器内地址 | 增加 EXTERNAL 监听并配置宿主机地址 |
| 消费者收不到消息 | groupId 不同、`auto-offset-reset` 配置、topic 拼写 | 检查配置和控制台 |
| 反序列化失败 | 类型信息缺失、`trusted.packages` 没配 | 配白名单，统一序列化格式 |
| 重复消费 | at-least-once 语义 + 未做幂等 | 唯一索引/去重表 |
| 消费者一直 rebalance | 处理时间 > `max.poll.interval.ms` | 调大或减少 `max.poll.records` |
| 消息顺序错乱 | 多分区 + key 不一致 | 用业务 ID 做 key |
| 消息丢失 | 自动提交 offset、`acks=1`、副本不足 | 手动提交、`acks=all`、多副本 |
| 启动报 topic 不存在 | 未自动创建且没手动创建 | 关闭自动创建，启动时用 `NewTopic` 声明 |
| 发送很慢 | `linger.ms` 太大或网络问题 | 调整攒批参数 |
| 消费者线程数超过分区数 | 多余消费者空闲 | 分区数 >= 消费者数 |

---

## 6.10 练习题

1. 实现"下单成功 → 发消息 → 积分服务加积分 → 短信服务发通知"的完整链路。
2. 用唯一索引实现积分消费的幂等，并用重复消息验证。
3. 配置重试 3 次 + 死信队列，观察毒消息进入 DLT。
4. 用同一个 `orderId` 发送 5 条消息，验证消费顺序。
5. 模拟消息积压：停消费者、发 1 万条、观察 LAG，再恢复并优化消费速度。
6. **思考题**：为什么 Kafka 不保证全局有序？如果业务真的需要全局有序，有什么代价？
7. **思考题**：本地消息表方案中，如果"更新消息表 status=已发送"失败，会发生什么？会不会重复发送？

---

## 6.11 本章小结

1. **MQ 解决异步、解耦、削峰**，但引入一致性、重复消费、积压等新问题。
2. **Kafka 的核心是分区**：分区内有序、分区是并行单位、消费者数不超过分区数。
3. **可靠性要三段一起做**：生产端 `acks=all` + 重试 + 幂等，存储端多副本，消费端手动提交。
4. **at-least-once 是默认语义**，消费者必须幂等，最终防线是数据库唯一索引。
5. **死信队列必须有人管**，否则等于消息丢失。

---

下一章：[第 07 章 分布式难题与方案](07-分布式难题与方案.md)
