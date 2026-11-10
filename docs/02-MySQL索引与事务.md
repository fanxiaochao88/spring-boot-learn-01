# 第 02 章 MySQL 进阶：索引与事务

> 预计用时：8 小时
> 你学过 SQL 语法，但公司里的 SQL 优化、索引设计、事务隔离，往往才是后端开发的日常。

---

## 2.1 学习目标

读完并动手后，你能：

1. 说清楚聚簇索引、二级索引、联合索引、覆盖索引的区别。
2. 用 `EXPLAIN` 分析一条 SQL 为什么慢。
3. 背下并理解索引失效的常见场景。
4. 说清楚事务的 ACID、四种隔离级别、MVCC 基本思路。
5. 识别死锁和大事务，知道怎么优化。
6. 会做深分页优化和基础的表设计。

---

## 2.2 InnoDB 的数据是怎么存的

MySQL 的 InnoDB 以**页（Page）**为单位读写磁盘，默认一页 16KB。一页里放很多行数据。

如果数据是乱序存放的，查一行就要全表扫描。为了让查找快，InnoDB 用 **B+ 树**组织数据。

### 2.2.1 为什么是 B+ 树，不是二叉树 / 红黑树 / B 树

| 结构 | 问题 |
|---|---|
| 二叉搜索树 | 数据有序插入会退化成链表，O(n) |
| 红黑树 | 树高约 2log n，100 万数据约 40 层，每层一次磁盘 IO，太慢 |
| B 树 | 每个节点存数据，节点能放的 key 少，树更高；范围查询要中序遍历，跨层跳转 |
| **B+ 树** | 非叶子节点只存 key，一页能放更多 key，树更矮（3-4 层可存千万级）；叶子节点用链表连接，范围查询高效 |

**直觉**：B+ 树是"矮胖"的，3 层就能覆盖千万级数据，查一次最多 3 次磁盘 IO。

### 2.2.2 聚簇索引（Clustered Index）

InnoDB 的主键索引就是聚簇索引：**叶子节点直接存整行数据**。

```text
             [ 10 | 20 | 30 ]            ← 非叶子节点只存 key
            /       |       \
     [1..10]      [11..20]      [21..30]  ← 叶子节点存完整行，且用链表相连
```

- 一张表只有一个聚簇索引；
- 如果没有主键，InnoDB 会用第一个非空唯一索引；都没有就生成隐藏的 `row_id`；
- **所以每张表都应该有主键**，而且最好是自增的（顺序插入，减少页分裂）。

### 2.2.3 二级索引（Secondary Index）

普通索引的叶子节点存的是 **索引列 + 主键值**，不是整行。

```sql
CREATE INDEX idx_name ON product(name);
```

查 `SELECT * FROM product WHERE name = '键盘'`：

1. 在 `idx_name` 里找到 `name='键盘'`，拿到主键 `id=5`；
2. 再用 `id=5` 去聚簇索引查整行。

第 2 步叫 **回表**。回表是额外的一次 B+ 树查找。

### 2.2.4 覆盖索引

如果查询需要的列都在索引里，就不用回表：

```sql
-- 有索引 idx_name(name)
SELECT id, name FROM product WHERE name = '键盘';  -- 覆盖索引，不回表
SELECT * FROM product WHERE name = '键盘';          -- 需要回表
```

`EXPLAIN` 的 `Extra` 列出现 `Using index` 就表示用了覆盖索引。

> 优化技巧：把常用查询的列放进联合索引，尽量让查询走覆盖索引。

### 2.2.5 联合索引与最左前缀

```sql
CREATE INDEX idx_abc ON t(a, b, c);
```

这个索引等价于按 `(a, b, c)` 排序。能用的查询：

| 查询条件 | 是否走索引 | 说明 |
|---|---|---|
| `WHERE a = 1` | ✅ | 用 a |
| `WHERE a = 1 AND b = 2` | ✅ | 用 a、b |
| `WHERE a = 1 AND b = 2 AND c = 3` | ✅ | 全用 |
| `WHERE b = 2` | ❌ | 缺少最左列 a |
| `WHERE a = 1 AND c = 3` | ⚠️ | 只用 a，c 无法用于定位（但可能用于覆盖） |
| `WHERE a = 1 AND b > 2 AND c = 3` | ⚠️ | a、b 用于范围，c 无法继续定位 |

**记忆方法**：联合索引像电话簿先按姓、再按名排序。你只知道"名"是找不到的。

### 2.2.6 索引下推（Index Condition Pushdown, ICP）

MySQL 5.6+ 支持。对于 `WHERE a = 1 AND b LIKE '%x%'`，在存储引擎层就用 `a` 过滤，减少回表次数。`EXPLAIN` 的 `Extra` 显示 `Using index condition`。

---

## 2.3 索引失效的六个常见场景

```sql
-- 假设有索引 idx_name(name), idx_age(age)
```

### 1. 对索引列做函数运算

```sql
-- ❌ 失效
SELECT * FROM user WHERE DATE(create_time) = '2026-11-09';
-- ✅ 改成范围查询
SELECT * FROM user WHERE create_time >= '2026-11-09 00:00:00'
                      AND create_time <  '2026-11-10 00:00:00';
```

### 2. 隐式类型转换

```sql
-- phone 是 varchar，传数字会触发全表转换
-- ❌ 失效
SELECT * FROM user WHERE phone = 13800138000;
-- ✅
SELECT * FROM user WHERE phone = '13800138000';
```

### 3. 左模糊 / 前后模糊

```sql
-- ❌ 失效
SELECT * FROM product WHERE name LIKE '%键盘';
-- ✅ 只有右模糊能用索引
SELECT * FROM product WHERE name LIKE '机械%';
```

> 真正的前后模糊搜索应该用 Elasticsearch，不是 MySQL。

### 4. `OR` 连接非索引列

```sql
-- 如果 description 没有索引，整个查询可能全表扫描
SELECT * FROM product WHERE name = '键盘' OR description = 'xxx';
```

### 5. 不符合最左前缀

见 2.2.5。

### 6. 不等于、`NOT IN`、`IS NOT NULL`（视情况）

```sql
SELECT * FROM user WHERE status != 1;  -- 通常全表扫描
```

优化器判断"走索引再回表"比"全表扫描"更贵时，会放弃索引。**是否失效最终看 `EXPLAIN`，不要死记。**

### 补充：索引列参与计算、排序方向不一致

```sql
-- 排序时索引列顺序要与索引一致
ORDER BY a ASC, b DESC  -- 与索引 (a ASC, b ASC) 不完全匹配
```

MySQL 8.0 支持降序索引，可以显式创建 `(a ASC, b DESC)`。

---

## 2.4 用 EXPLAIN 看懂执行计划

```sql
EXPLAIN SELECT * FROM product WHERE name = '键盘';
```

| 列 | 含义 | 重点关注 |
|---|---|---|
| `id` | 查询序号 | 越大越先执行；相同则从上到下 |
| `select_type` | 查询类型 | `SIMPLE`、`PRIMARY`、`SUBQUERY`、`DERIVED` |
| `table` | 表名 | |
| `partitions` | 分区 | |
| `type` | 访问类型 | **最重要**，见下表 |
| `possible_keys` | 可能用的索引 | |
| `key` | 实际用的索引 | 为 `NULL` 说明没走索引 |
| `key_len` | 索引使用长度 | 判断联合索引用了几列 |
| `ref` | 索引比较的列 | |
| `rows` | 预估扫描行数 | 越小越好 |
| `filtered` | 过滤后剩余百分比 | |
| `Extra` | 额外信息 | `Using index`、`Using filesort`、`Using temporary` |

### type 的性能排序（从好到差）

```text
system > const > eq_ref > ref > range > index > ALL
```

| type | 含义 | 例子 |
|---|---|---|
| `const` | 主键/唯一索引等值查，最多一行 | `WHERE id = 1` |
| `eq_ref` | 联表时被驱动表用主键/唯一索引 | `JOIN ... ON a.id = b.user_id` |
| `ref` | 普通索引等值查 | `WHERE name = 'x'` |
| `range` | 索引范围查 | `WHERE id > 10`、`BETWEEN` |
| `index` | 扫描整个索引树 | 比 ALL 好一点，仍要遍历 |
| `ALL` | 全表扫描 | 需要优化 |

> 目标：至少 `range`，核心查询争取 `ref` 或 `const`。

### `Extra` 里的危险信号

- `Using filesort`：排序没走索引，需要额外排序。数据量大时很慢。
- `Using temporary`：用了临时表，常见于 `GROUP BY`、`DISTINCT`、`UNION`。
- `Using join buffer`：联表没走索引。
- `Using index`：好事，覆盖索引。
- `Using index condition`：索引下推，好事。

### 实战：从慢到快

```sql
-- 慢：type=ALL, rows=1000000
EXPLAIN SELECT * FROM orders WHERE user_id = 100 AND status = 1 ORDER BY create_time DESC;

-- 建联合索引，注意顺序：等值条件在前，范围/排序在后
CREATE INDEX idx_user_status_time ON orders(user_id, status, create_time);

-- 再看：type=ref, key=idx_user_status_time, Extra 里没有 filesort
EXPLAIN SELECT * FROM orders WHERE user_id = 100 AND status = 1 ORDER BY create_time DESC;
```

**联合索引列顺序原则**（经验，不是绝对）：

1. 等值查询的列放前面；
2. 区分度高的列放前面；
3. 排序/范围查询的列放后面；
4. 考虑覆盖索引，把 `SELECT` 常用列带上。

---

## 2.5 事务与隔离级别

### 2.5.1 ACID

| 特性 | 含义 | InnoDB 靠什么实现 |
|---|---|---|
| **A** 原子性 | 要么全成功，要么全失败 | undo log |
| **C** 一致性 | 从一个合法状态到另一个合法状态 | 由 A、I、D 共同保证 |
| **I** 隔离性 | 并发事务互不干扰 | 锁 + MVCC |
| **D** 持久性 | 提交后不丢 | redo log |

### 2.5.2 并发事务的三种问题

| 问题 | 描述 | 例子 |
|---|---|---|
| **脏读** | 读到别的事务未提交的数据 | A 改了余额没提交，B 读到了 |
| **不可重复读** | 同一事务内两次读同一行，结果不同 | B 第一次读余额 100，A 提交改成 200，B 再读变成 200 |
| **幻读** | 同一事务内两次范围查询，行数不同 | B 查 `age > 20` 有 10 行，A 插入一行，B 再查变 11 行 |

### 2.5.3 四种隔离级别

| 级别 | 脏读 | 不可重复读 | 幻读 | 说明 |
|---|---|---|---|---|
| `READ UNCOMMITTED` | ✅ 可能 | ✅ | ✅ | 几乎不用 |
| `READ COMMITTED` | ❌ | ✅ | ✅ | Oracle 默认；很多互联网公司用 |
| `REPEATABLE READ` | ❌ | ❌ | ⚠️ 基本解决 | **MySQL 默认** |
| `SERIALIZABLE` | ❌ | ❌ | ❌ | 串行执行，性能最差 |

查看和设置：

```sql
SELECT @@transaction_isolation;

-- 当前会话
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- 全局（需谨慎）
SET GLOBAL TRANSACTION ISOLATION LEVEL READ COMMITTED;
```

> MySQL 的 `REPEATABLE READ` 通过 **MVCC + 间隙锁** 在大多数场景下避免了幻读，但并非绝对。面试常问"RR 能解决幻读吗"，标准回答是"快照读靠 MVCC，当前读靠 Next-Key Lock，基本解决但特殊场景仍可能出现"。

### 2.5.4 MVCC 一句话理解

每行数据有两个隐藏字段：

- `DB_TRX_ID`：最后修改它的事务 ID；
- `DB_ROLL_PTR`：指向 undo log 的指针。

事务开始时生成一个 **Read View**（记录当前活跃事务 ID 列表）。读取时沿着 undo log 往回找，找到第一个"对当前事务可见"的版本。

- `READ COMMITTED`：**每次查询**都生成新的 Read View；
- `REPEATABLE READ`：**事务第一次查询时**生成，之后复用。

这就是"可重复读"的实现原理。

---

## 2.6 锁：行锁、间隙锁、死锁

### 2.6.1 锁的分类

| 分类 | 类型 | 说明 |
|---|---|---|
| 粒度 | 表锁 / 行锁 | InnoDB 默认行锁，锁的是索引项 |
| 模式 | 共享锁 S / 排他锁 X | `SELECT ... LOCK IN SHARE MODE` / `FOR UPDATE` |
| 意向 | 意向共享 IS / 意向排他 IX | 表级，表示"打算给某些行加锁" |
| 范围 | 记录锁 Record / 间隙锁 Gap / Next-Key | Next-Key = 记录锁 + 前面的间隙锁 |

**重要**：InnoDB 的行锁是加在**索引**上的。如果 `WHERE` 条件没有索引，会退化成锁全表（准确说是锁所有行）。

```sql
-- id 有索引：只锁 id=1 这一行
SELECT * FROM user WHERE id = 1 FOR UPDATE;

-- name 没索引：锁住所有行，其他事务全被阻塞
SELECT * FROM user WHERE name = '张三' FOR UPDATE;
```

### 2.6.2 死锁

```sql
-- 事务 A
BEGIN;
UPDATE account SET balance = balance - 100 WHERE id = 1;
-- 此时事务 B 执行：
-- UPDATE account SET balance = balance - 100 WHERE id = 2;
UPDATE account SET balance = balance + 100 WHERE id = 2;

-- 事务 B
BEGIN;
UPDATE account SET balance = balance - 100 WHERE id = 2;
-- 等事务 A 释放 id=2
UPDATE account SET balance = balance + 100 WHERE id = 1;
-- 等事务 A 释放 id=1 → 死锁
```

**排查：**

```sql
SHOW ENGINE INNODB STATUS;
-- 查看 LATEST DETECTED DEADLOCK 部分
```

**预防：**

1. **按固定顺序加锁**：所有事务都先锁 id 小的，再锁 id 大的。
2. 缩短事务，减少持锁时间。
3. 给 `WHERE` 条件加索引，避免锁范围过大。
4. 必要时降低隔离级别到 `READ COMMITTED`（减少间隙锁）。
5. 捕获死锁异常后重试（应用层）。

---

## 2.7 慢查询与优化

### 2.7.1 开启慢查询日志

```sql
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 1;          -- 超过 1 秒记录
SET GLOBAL slow_query_log_file = '/var/log/mysql/slow.log';
```

查看：

```sql
SHOW VARIABLES LIKE 'slow_query%';
SHOW VARIABLES LIKE 'long_query_time';
```

### 2.7.2 用 `mysqldumpslow` 或 `pt-query-digest` 分析

```bash
mysqldumpslow -s t -t 10 /var/log/mysql/slow.log
```

### 2.7.3 深分页优化

```sql
-- 慢：要扫描并丢弃前 100 万行
SELECT * FROM orders ORDER BY id LIMIT 1000000, 20;
```

方案一：**游标分页**（推荐）

```sql
-- 记住上一页最后一条的 id
SELECT * FROM orders WHERE id > 1000000 ORDER BY id LIMIT 20;
```

方案二：**延迟关联**

```sql
SELECT o.*
FROM orders o
JOIN (SELECT id FROM orders ORDER BY id LIMIT 1000000, 20) t ON o.id = t.id;
```

方案三：业务上限制最大页数（如最多 100 页）。

### 2.7.4 其他常见优化

| 问题 | 优化 |
|---|---|
| `COUNT(*)` 慢 | 用近似值、缓存计数、单独计数表 |
| 大批量 `IN` | 控制在 1000 以内，分批 |
| 循环单条插入 | 批量插入 `INSERT INTO ... VALUES (...), (...)` |
| 大字段 `TEXT` 影响查询 | 拆表，按需查询 |
| 频繁更新的表建太多索引 | 索引维护有成本，控制在 5 个以内 |

---

## 2.8 表设计规范

```sql
CREATE TABLE `product` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '商品名称',
  `price`       DECIMAL(10, 2)  NOT NULL                COMMENT '价格',
  `stock`       INT             NOT NULL DEFAULT 0      COMMENT '库存',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态 1上架 0下架',
  `version`     INT             NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除 0否 1是',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_name` (`name`),
  KEY `idx_status_price` (`status`, `price`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';
```

### 规范要点

1. **必须主键**，优先 `BIGINT UNSIGNED AUTO_INCREMENT`。
2. **金额用 `DECIMAL`，不要用 `FLOAT/DOUBLE`**（浮点精度问题）。
3. **字符集用 `utf8mb4`**，支持 emoji。
4. **布尔/状态用 `TINYINT`**，避免 `VARCHAR`。
5. **每列都写 `COMMENT`**。
6. **必须有时间字段**：`create_time`、`update_time`。
7. **逻辑删除字段** `deleted`，但唯一索引要把 `deleted` 纳进去。
8. **不要用 `SELECT *`**，只查需要的列。
9. **表名、列名小写下划线**，避免 MySQL 大小写敏感问题。
10. **单表数据量控制在 2000 万以内**（经验值，不是硬限制），超过考虑归档或分表。

### 唯一索引 + 逻辑删除的正确姿势

```sql
-- 错误：删除后无法再插入同名商品
UNIQUE KEY uk_name (name);

-- 正确：把 deleted 带上；但 deleted 只有 0/1，多删几次仍冲突
UNIQUE KEY uk_name_deleted (name, deleted);

-- 更稳妥：deleted 存删除时间戳，0 表示未删除
UNIQUE KEY uk_name_deleted (name, deleted);
```

---

## 2.9 分库分表（概念）

当单表数据量过亿、单库连接数打满时，才考虑分库分表。

| 方式 | 说明 | 问题 |
|---|---|---|
| **垂直分库** | 按业务拆：用户库、订单库、商品库 | 跨库 JOIN 困难 |
| **垂直分表** | 大表拆成常用列 + 不常用列 | 需要多次查询 |
| **水平分表** | 同一张表按规则拆成 `order_0`、`order_1` | 分页、排序、聚合复杂 |
| **水平分库** | 分表 + 分散到不同数据库实例 | 分布式事务 |

分片键选择：

- 用户维度：`user_id`（查用户订单快，查商家订单要广播）
- 订单维度：`order_id`
- 时间维度：按月分表（便于归档，但跨月查询麻烦）

常见中间件：**ShardingSphere**、MyCat。

> 新手不要过早分库分表。先做索引优化、缓存、读写分离；这些手段能撑很久。分库分表会显著增加复杂度，是最后的手段。

---

## 2.10 动手实验

### 实验 1：造数据并对比索引效果

```sql
CREATE DATABASE IF NOT EXISTS roadmap_db DEFAULT CHARSET utf8mb4;
USE roadmap_db;

CREATE TABLE t_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  age INT NOT NULL,
  city VARCHAR(32) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

-- 用存储过程插入 10 万条
DELIMITER $$
CREATE PROCEDURE batch_insert()
BEGIN
  DECLARE i INT DEFAULT 0;
  WHILE i < 100000 DO
    INSERT INTO t_user(name, age, city)
    VALUES (CONCAT('user_', i), FLOOR(RAND() * 60) + 18, ELT(FLOOR(RAND() * 5) + 1, '北京', '上海', '广州', '深圳', '杭州'));
    SET i = i + 1;
  END WHILE;
END$$
DELIMITER ;

CALL batch_insert();
```

### 实验 2：观察类型和 rows

```sql
EXPLAIN SELECT * FROM t_user WHERE age = 30;
-- type=ALL, rows≈100000

CREATE INDEX idx_age ON t_user(age);
EXPLAIN SELECT * FROM t_user WHERE age = 30;
-- type=ref, rows 明显变小
```

### 实验 3：验证最左前缀

```sql
CREATE INDEX idx_city_age ON t_user(city, age);

EXPLAIN SELECT * FROM t_user WHERE city = '北京' AND age = 30;   -- 走索引
EXPLAIN SELECT * FROM t_user WHERE age = 30;                     -- 不走（缺 city）
EXPLAIN SELECT * FROM t_user WHERE city = '北京';                -- 走
```

### 实验 4：观察索引失效

```sql
EXPLAIN SELECT * FROM t_user WHERE DATE(create_time) = '2026-11-09';  -- ALL
EXPLAIN SELECT * FROM t_user WHERE name LIKE '%user%';                -- ALL
EXPLAIN SELECT * FROM t_user WHERE age + 1 = 31;                      -- ALL
```

### 实验 5：事务隔离

开两个终端（两个会话）：

```sql
-- 会话 A
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
BEGIN;
SELECT age FROM t_user WHERE id = 1;

-- 会话 B
UPDATE t_user SET age = 99 WHERE id = 1;
COMMIT;

-- 会话 A 再查，仍是旧值（可重复读）
SELECT age FROM t_user WHERE id = 1;
COMMIT;

-- 会话 A 提交后再查，变成 99
```

---

## 2.11 常见坑

1. **以为建了索引就一定走**：数据量小、区分度低、`OR`、函数运算都可能导致全表扫描，必须看 `EXPLAIN`。
2. **联合索引顺序随便定**：等值在前、范围在后，否则后面的列用不上。
3. **索引越多越好**：每个索引都增加写入成本和存储，更新频繁的表要克制。
4. **用 `SELECT *`**：增加回表、网络传输、覆盖索引失效概率。
5. **金额用 `DOUBLE`**：`0.1 + 0.2 != 0.3`，必须 `DECIMAL`。
6. **大事务**：一次更新 10 万行，锁表 + binlog 暴涨 + 主从延迟。
7. **在事务里调远程接口**：数据库连接被长时间占用。
8. **逻辑删除 + 唯一索引**：没有把 `deleted` 放进唯一键。
9. **`COUNT(*)` 和 `COUNT(1)` 纠结**：InnoDB 两者都差不多，别信"`COUNT(1)` 更快"的谣言。
10. **`ORDER BY RAND()`**：全表扫描 + 临时表排序，数据量大直接爆炸。

---

## 2.12 练习题

1. 给 `t_user` 建一个联合索引 `(city, age, name)`，写出 5 条能走索引和 3 条不能走索引的 SQL，并用 `EXPLAIN` 验证。
2. 有一条慢 SQL：`SELECT * FROM orders WHERE user_id = ? AND status = ? ORDER BY create_time DESC LIMIT 20`，请设计索引并解释为什么这样设计。
3. 模拟一个死锁：两个会话按相反顺序更新两行，用 `SHOW ENGINE INNODB STATUS` 找到死锁日志。
4. 把 `SELECT * FROM orders ORDER BY id LIMIT 500000, 20` 改造成游标分页和延迟关联两种写法。
5. **思考题**：为什么 InnoDB 的行锁在没有索引时会退化成锁全表？从 B+ 树的角度解释。
6. **思考题**：`READ COMMITTED` 下，同一个事务两次查询结果不同，这是 bug 吗？

---

## 2.13 本章小结

1. **InnoDB 用 B+ 树组织数据**：聚簇索引存整行，二级索引存索引列 + 主键，查询可能需要回表。
2. **索引设计的核心是联合索引和最左前缀**，优化效果用 `EXPLAIN` 的 `type`、`key`、`rows`、`Extra` 验证。
3. **事务隔离靠 MVCC + 锁**，RR 是 MySQL 默认，但互联网公司常用 RC 减少间隙锁。
4. **行锁锁的是索引**，没索引就锁全表；死锁靠固定加锁顺序和短事务预防。
5. **先优化 SQL 和索引，再考虑缓存，最后才是分库分表。**

---

下一章：[第 03 章 Redis 缓存实战](03-Redis缓存实战.md)
