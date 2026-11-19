# 补充 02A：多表查询、JOIN 与别名完全指南

> 配套：[02-RBAC权限与SpringSecurity重写.md](02-RBAC权限与SpringSecurity重写.md)
> 适用：目前只写过 `SELECT * FROM sys_user;`，一看到多表 JOIN 就发懵的你
> 预计用时：3～4 小时（一定要边看边在 MySQL 客户端里敲）
> 数据基线：`mall_product` 库第 02 章的种子数据
> 本篇目标：让你能**独立读懂、独立写出**项目里那几条用户-角色-权限查询，并且知道什么时候该用 INNER、什么时候该用 LEFT、别名到底该不该起。

---

## 0. 先记住五句话

如果今天只记五句话，记这五句：

1. **JOIN 的本质**：先把两张表的所有行两两组合（笛卡尔积），再用 `ON` 把配对成功的行留下。
2. **INNER JOIN**：两边都配对成功才留下；**LEFT JOIN**：左表每一行都必须留下，右表配不上就补 `NULL`。
3. **表别名不是 JOIN 的必要条件**，它只是"给表起个简称"；但多表查询、同名列、自连接时，不起别名要么难读、要么直接报错。
4. **INNER JOIN 的书写顺序无所谓**（结果一样）；**LEFT JOIN 谁在左，谁就被完整保留**（顺序有意义）。
5. **一对多关系会让行数膨胀**：3 个用户的"用户-角色-权限"关系平铺开，是 17 行。`DISTINCT`、`GROUP BY`、MyBatis 的 `resultMap` 都是在处理这个"膨胀"。

把这五句话贴在屏幕边上，下面的内容都是它们的展开。

---

## 1. 为什么会有多表查询

### 1.1 先看一张"什么都有"的表有多糟

假设我们不拆表，把所有信息塞进一张表：

| id | username | role_code | role_name | perm_code | perm_name |
|---|---|---|---|---|---|
| 1 | admin | SUPER_ADMIN | 超级管理员 | product:read | 商品查询 |
| 1 | admin | SUPER_ADMIN | 超级管理员 | product:write | 商品新增修改 |
| 1 | admin | SUPER_ADMIN | 超级管理员 | product:delete | 商品删除 |
| 1 | admin | SUPER_ADMIN | 超级管理员 | stock:decrease | 库存扣减 |
| ... | ... | ... | ... | ... | ... |

问题一眼就能看出来：

- **大量重复**：`admin`、`SUPER_ADMIN`、`超级管理员` 重复了 11 次。
- **改一处要改很多行**：角色改名，11 行都得改，漏一行就不一致。
- **没法表达"这个角色暂时没有权限"**：没有权限就没有行，连角色本身都消失了。
- **关联关系混乱**：这个用户到底有几个角色？得靠 `DISTINCT` 猜。

于是我们把数据拆开：**一种东西一张表，关系用"关联表"表达**。

### 1.2 拆表之后，关系靠"值"表达

数据库没有 Java 对象里的 `List<Role> roles` 这种字段。两张表怎么"认识"？

> **靠一张表里存另一张表的主键值。**

例如 `sys_user_role` 表里存了 `user_id = 1, role_id = 1`，就表示"1 号用户拥有 1 号角色"。

```
sys_user 1 ──── N sys_user_role N ──── 1 sys_role 1 ──── N sys_role_permission N ──── 1 sys_permission
```

读法：

- 一个用户，可以出现在多条 `sys_user_role` 里（拥有多个角色）；
- 一个角色，也可以出现在多条 `sys_user_role` 里（被多个用户拥有）→ 用户和角色是**多对多**；
- 一个角色，可以出现在多条 `sys_role_permission` 里（拥有多个权限）；
- 一个权限，也可以被多个角色拥有 → 角色和权限也是**多对多**。

`sys_user_role`、`sys_role_permission` 这种**只用来存两边 id 的表，叫关联表（中间表、junction table）**。它自己不是"业务实体"，只是为了把多对多拆成两个一对多。

### 1.3 本项目的五张表

```text
sys_user                用户
sys_role                角色
sys_permission          权限
sys_user_role           用户-角色关联（user_id, role_id）
sys_role_permission     角色-权限关联（role_id, permission_id）
```

字段（只列 JOIN 会用到的）：

| 表 | 关键字段 | 说明 |
|---|---|---|
| `sys_user` | `id`, `username`, `status`, `deleted` | 后台账号 |
| `sys_role` | `id`, `code`, `name`, `status`, `deleted` | 角色 |
| `sys_permission` | `id`, `code`, `name`, `module`, `status`, `deleted` | 权限码 |
| `sys_user_role` | `id`, `user_id`, `role_id` | 用户拥有哪些角色 |
| `sys_role_permission` | `id`, `role_id`, `permission_id` | 角色拥有哪些权限 |

> 注意关联表**没有** `deleted`、`version`。解除关联直接删行，这是第 02 章的设计决定。

### 1.4 种子数据（本篇所有例子的基准）

`sys_user`：

| id | username | nickname | status | deleted |
|---|---|---|---|---|
| 1 | admin | 超级管理员 | 1 | 0 |
| 2 | product_user | 商品管理员 | 1 | 0 |
| 3 | order_user | 订单管理员 | 1 | 0 |

`sys_role`：

| id | code | name | status | deleted |
|---|---|---|---|---|
| 1 | SUPER_ADMIN | 超级管理员 | 1 | 0 |
| 2 | PRODUCT_ADMIN | 商品管理员 | 1 | 0 |
| 3 | ORDER_ADMIN | 订单管理员 | 1 | 0 |

`sys_user_role`：

| user_id | role_id | 含义 |
|---|---|---|
| 1 | 1 | admin → 超级管理员 |
| 2 | 2 | product_user → 商品管理员 |
| 3 | 3 | order_user → 订单管理员 |

`sys_permission`（11 条）：

| id | code | module |
|---|---|---|
| 1 | product:read | product |
| 2 | product:write | product |
| 3 | product:delete | product |
| 4 | stock:decrease | product |
| 5 | order:read | order |
| 6 | order:ship | order |
| 7 | user:read | system |
| 8 | user:write | system |
| 9 | role:read | system |
| 10 | role:write | system |
| 11 | log:read | system |

`sys_role_permission`：

| 角色 | 权限 | 条数 |
|---|---|---|
| SUPER_ADMIN (1) | 全部 11 条 | 11 |
| PRODUCT_ADMIN (2) | `product:read`、`product:write`、`product:delete`、`stock:decrease` | 4 |
| ORDER_ADMIN (3) | `order:read`、`order:ship` | 2 |

**合计 17 条角色-权限关系。** 记住这个 17，后面会一直出现。

先验证一下你的库和这份数据一致：

```sql
USE mall_product;

SELECT COUNT(*) AS users      FROM sys_user;             -- 3
SELECT COUNT(*) AS roles      FROM sys_role;             -- 3
SELECT COUNT(*) AS perms      FROM sys_permission;       -- 11
SELECT COUNT(*) AS user_roles FROM sys_user_role;        -- 3
SELECT COUNT(*) AS role_perms FROM sys_role_permission;  -- 17
```

> 如果你的库多了实验数据，行数会不同。本篇结论按上面的基准数据描述。

---

## 2. JOIN 的地基：笛卡尔积 + ON 筛选

很多人 JOIN 看不懂，是因为跳过了这一节。**JOIN 不是"按顺序把表接起来"，而是"先全组合，再筛选"。**

### 2.1 两张小表

表 A（用户）：

| id | username |
|---|---|
| 1 | admin |
| 2 | product_user |
| 3 | order_user |

表 B（角色）：

| id | code |
|---|---|
| 1 | SUPER_ADMIN |
| 2 | PRODUCT_ADMIN |
| 3 | ORDER_ADMIN |

### 2.2 不做任何筛选：3 × 3 = 9 行

```sql
SELECT u.username, r.code
FROM sys_user u
JOIN sys_role r;          -- 故意不写 ON
```

结果（顺序可能不同）：

| username | code |
|---|---|
| admin | SUPER_ADMIN |
| admin | PRODUCT_ADMIN |
| admin | ORDER_ADMIN |
| product_user | SUPER_ADMIN |
| product_user | PRODUCT_ADMIN |
| product_user | ORDER_ADMIN |
| order_user | SUPER_ADMIN |
| order_user | PRODUCT_ADMIN |
| order_user | ORDER_ADMIN |

这就是**笛卡尔积**：左表 3 行 × 右表 3 行 = 9 行。

验证一下：

```sql
SELECT COUNT(*) FROM sys_user u JOIN sys_role r;   -- 9
```

> **MySQL 允许 `JOIN` 不写 `ON`**，此时它就是 `CROSS JOIN`（交叉连接）。
> 标准 SQL 更推荐写成 `CROSS JOIN`，但在 MySQL 里 `JOIN` 不写 ON 也能跑。

### 2.3 ON 是"配对规则"

上面的 9 行里，只有"用户的 id 和关联表的 user_id 对得上"的行才是有意义的。

```sql
SELECT u.username, r.code
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r        ON r.id = ur.role_id;
```

逻辑过程（**只是帮助你理解，数据库不一定真按这个顺序执行**）：

1. `sys_user` 和 `sys_user_role` 先做笛卡尔积：3 × 3 = 9 行；
2. `ON ur.user_id = u.id` 只留下 3 行（1-1、2-2、3-3）；
3. 再和 `sys_role` 做笛卡尔积：3 × 3 = 9 行；
4. `ON r.id = ur.role_id` 只留下 3 行；
5. 最后 `SELECT` 挑出 `username`、`code` 两列。

结果：

| username | code |
|---|---|
| admin | SUPER_ADMIN |
| product_user | PRODUCT_ADMIN |
| order_user | ORDER_ADMIN |

**所以 JOIN 的核心永远是两件事：**

- **连接键**：`ON` 后面的等式，告诉数据库"这两张表的行怎么算配对成功"；
- **筛选**：`WHERE` 或 `ON` 里的其他条件，决定哪些行最终留下。

### 2.4 一条 SQL 的逻辑执行顺序

这个顺序非常重要，它解释了后面"别名为什么不能乱用"：

| 顺序 | 阶段 | 作用 | 别名可见性 |
|---|---|---|---|
| 1 | `FROM` / `JOIN` | 确定表，定义**表别名** | 表别名从这里开始生效 |
| 2 | `ON` | 表之间如何配对 | 可以用已出现的表别名 |
| 3 | `WHERE` | 过滤行 | 可以用表别名，**不能用 SELECT 的列别名** |
| 4 | `GROUP BY` | 分组 | 可以用表别名 |
| 5 | `HAVING` | 过滤分组 | MySQL 里可以用列别名 |
| 6 | `SELECT` | 选列、计算、定义**列别名** | 列别名从这里才诞生 |
| 7 | `DISTINCT` | 去重 | — |
| 8 | `ORDER BY` | 排序 | 可以用列别名 |
| 9 | `LIMIT` | 截断 | — |

一句话：**`WHERE` 执行时，`SELECT` 还没执行，所以 `WHERE` 看不到列别名；`ORDER BY` 在 `SELECT` 之后，所以能看到。**

---

## 3. 别名：什么时候起，什么时候不起

这是你困惑的第二个来源：有的 SQL 满屏 `u`、`r`、`p`、`ur`、`rp`，有的 SQL 一个别名都没有。其实规则很简单。

### 3.1 表别名是什么

```sql
FROM sys_user u
-- 等价于
FROM sys_user AS u
```

`u` 是这张表在这条 SQL 里的**临时简称**。它只在**这一条 SQL**里有效，换一条语句就没了。

起别名的两个理由：

1. **少打字、好读**：`p.code` 比 `sys_permission.code` 短。
2. **消除歧义**：多张表可能有同名列（比如每张表都有 `id`），不起别名你根本说不清要哪个。

### 3.2 表别名什么时候可以不起

| 场景 | 要不要起 | 说明 |
|---|---|---|
| 单表简单查询 | 可以不起 | `SELECT id, username FROM sys_user WHERE id = 1;` 没有任何歧义 |
| 多表查询 | **强烈建议起** | 不然 SQL 又长又容易看错 |
| 两张表有同名列 | **必须起** | 不起就报 `Column 'id' in field list is ambiguous` |
| 自连接（同一张表出现两次） | **必须起** | 不起别名无法区分"用户表"和"上级用户表" |
| 子查询 | 建议起 | 可读性 |

**单表不起别名没有问题**，这就是你以前写 `SELECT * FROM user` 能跑的原因。**但一旦表多了，别名就从"可选"变成"刚需"。**

### 3.3 同名列：不起别名直接报错

执行：

```sql
SELECT id, username
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;
```

报错：

```text
ERROR 1052 (23000): Column 'id' in field list is ambiguous
```

原因：`sys_user.id` 和 `sys_user_role.id` **都叫 `id`**，数据库不知道你要哪一个。

改法一：加表别名

```sql
SELECT u.id, u.username, ur.role_id
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;
```

改法二：给列起别名（顺便解决结果列重名）

```sql
SELECT u.id AS user_id, ur.id AS user_role_id, u.username
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;
```

### 3.4 表别名和列别名的区别

这是两个完全不同的东西，不要混：

| 类型 | 写法 | 作用 | 生效范围 |
|---|---|---|---|
| 表别名 | `FROM sys_user u` | 给表起简称 | 整条 SQL 的 `ON`/`WHERE`/`SELECT`/`ORDER BY` 等 |
| 列别名 | `SELECT p.code AS perm_code` | 给结果列起名字 | `ORDER BY`、`HAVING`，以及给 MyBatis 映射用 |

### 3.5 列别名：多表查询的刚需

单表查询时，列名本来就唯一，起不起列别名都行。多表查询时，列别名有两个硬用途：

**用途一：解决结果列重名。**

```sql
SELECT
  r.id          AS role_id,
  r.code        AS role_code,
  p.id          AS perm_id,
  p.code        AS perm_code
FROM sys_role r
JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p       ON p.id = rp.permission_id;
```

如果不加 `AS`，结果里会出现两列都叫 `id`、两列都叫 `code`。在 MySQL 客户端里看着难受，在 MyBatis 里会**互相覆盖**（哪个在后哪个赢，这种 bug 极难查）。

**用途二：让 MyBatis 的 `resultMap` 能对上号。**

你的 `sysPermissionMapper.xml` 里：

```xml
<resultMap id="rolePermissionMap" type="...SysRoleVO">
    <id     property="id"   column="role_id"/>
    <result property="name" column="role_name"/>
    <collection property="permissionList" ofType="...SysPermissionVO">
        <id     property="id"   column="perm_id"/>
        <result property="name" column="perm_name"/>
    </collection>
</resultMap>
```

这里的 `column="role_id"`、`column="perm_id"` **必须**和 `SELECT` 里的列别名一模一样。你改了一边没改另一边，映射出来就是 `null`。

### 3.6 列别名不能用在 WHERE

因为 `WHERE` 在 `SELECT` 之前执行。

```sql
-- 报错：Unknown column 'login_name' in 'where clause'
SELECT username AS login_name
FROM sys_user
WHERE login_name = 'admin';
```

正确写法：

```sql
-- 写法一：WHERE 里用原列名
SELECT username AS login_name
FROM sys_user
WHERE username = 'admin';

-- 写法二：ORDER BY 可以用列别名（因为 ORDER BY 在 SELECT 之后）
SELECT username AS login_name
FROM sys_user
ORDER BY login_name;
```

### 3.7 别名命名约定

你的项目里用的是行业最常见的缩写：

| 表 | 别名 | 记忆 |
|---|---|---|
| `sys_user` | `u` | **u**ser |
| `sys_role` | `r` | **r**ole |
| `sys_permission` | `p` | **p**ermission |
| `sys_user_role` | `ur` | **u**ser_**r**ole |
| `sys_role_permission` | `rp` | **r**ole_**p**ermission |

别名随便起（`a`、`b`、`t1` 也能跑），但**表意清晰**最重要。一个人写 `u/r/p`，另一个人写 `a/b/c`，代码就没法读了。

### 3.8 一个容易忽略的点：`SELECT u.username` 的结果列名

`SELECT u.username FROM sys_user u` 的结果列名是 **`username`**，不是 `u.username`。表别名只用于"限定是哪张表的列"，不会改变输出列名。

只有列别名才会改变输出列名：

```sql
SELECT u.username AS name FROM sys_user u;   -- 输出列名叫 name
```

### 3.9 项目里为什么有的 SQL 没有 ON

第 02 章建表和种子数据里有很多这种：

```sql
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p
WHERE r.code = 'SUPER_ADMIN';
```

这里其实**有别名**，只是没有 `ON`。而且这条 SQL 的"没有 ON"是**故意的**，下一节就讲。

---

## 4. INNER JOIN：两边都配对成功才留下

`JOIN` 默认就是 `INNER JOIN`，写 `JOIN` 和写 `INNER JOIN` 完全等价。

### 4.1 语义

```text
A INNER JOIN B
= 只保留 A、B 中"能配上对"的行
= 配不上的行直接丢弃
```

用集合图表示：

```text
      A          B
   ┌─────┐   ┌─────┐
   │  ●  │   │  ●  │
   │   ┌─┼───┼─┐   │
   │   │ │███│ │   │   ← 只保留交集
   │   └─┼───┼─┘   │
   │  ●  │   │  ●  │
   └─────┘   └─────┘
```

### 4.2 两表：用户 + 用户角色

```sql
SELECT u.id, u.username, ur.role_id
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;
```

| id | username | role_id |
|---|---|---|
| 1 | admin | 1 |
| 2 | product_user | 2 |
| 3 | order_user | 3 |

如果有一个用户**没有任何角色**，他不会出现在结果里。这是 INNER JOIN 最重要的后果，后面 LEFT JOIN 就是来解决它的。

### 4.3 三表：用户 + 用户角色 + 角色

```sql
SELECT u.username, r.code AS role_code, r.name AS role_name
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r        ON r.id = ur.role_id;
```

| username | role_code | role_name |
|---|---|---|
| admin | SUPER_ADMIN | 超级管理员 |
| product_user | PRODUCT_ADMIN | 商品管理员 |
| order_user | ORDER_ADMIN | 订单管理员 |

注意第二个 `ON`：`r.id = ur.role_id`。**每一段 JOIN 都要有一个 ON，把新表接到已经出现的表上。**

### 4.4 五表：用户 → 角色 → 权限

```sql
SELECT
  u.username,
  r.code AS role_code,
  p.code AS perm_code
FROM sys_user u
JOIN sys_user_role ur        ON ur.user_id = u.id
JOIN sys_role r              ON r.id = ur.role_id
JOIN sys_role_permission rp  ON rp.role_id = r.id
JOIN sys_permission p        ON p.id = rp.permission_id
ORDER BY u.id, r.id, p.id;
```

结果共 **17 行**（admin 11 + product_user 4 + order_user 2）：

| username | role_code | perm_code |
|---|---|---|
| admin | SUPER_ADMIN | product:read |
| admin | SUPER_ADMIN | product:write |
| ... | ... | ... |
| product_user | PRODUCT_ADMIN | product:read |
| ... | ... | ... |
| order_user | ORDER_ADMIN | order:read |
| order_user | ORDER_ADMIN | order:ship |

**这就是"行数膨胀"**：3 个用户最后变成 17 行。因为一个用户对应多个权限，每个"用户-权限"组合占一行。

验证：

```sql
SELECT COUNT(*)
FROM sys_user u
JOIN sys_user_role ur       ON ur.user_id = u.id
JOIN sys_role r             ON r.id = ur.role_id
JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p       ON p.id = rp.permission_id;
-- 17
```

### 4.5 INNER JOIN 的书写顺序真的无所谓

下面是两条**结果完全相同**的 SQL。

写法一：从权限表出发（你项目里的 `selectPermissionCodeByUserId`）：

```sql
SELECT DISTINCT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur       ON ur.role_id = rp.role_id
JOIN sys_role r             ON r.id = ur.role_id
WHERE ur.user_id = 1;
```

写法二：从用户表出发：

```sql
SELECT DISTINCT p.code
FROM sys_user u
JOIN sys_user_role ur       ON ur.user_id = u.id
JOIN sys_role r             ON r.id = ur.role_id
JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p       ON p.id = rp.permission_id
WHERE u.id = 1;
```

两条都返回 admin 的 11 个权限码。为什么？

> **INNER JOIN 是"找所有表都能连通的组合"，和你在 SQL 里先写哪张表没有关系。**
> 就像你要从 A 走到 E，可以 A→B→C→D→E，也可以 E→D→C→B→A，只要路径连得上，走过的点是一样的。

数据库的**优化器**会自动决定先读哪张表、用哪个索引，你写的顺序只影响**人的阅读**，不影响 INNER JOIN 的逻辑结果。

但要注意两个前提：

1. **所有表必须通过 ON 连成一张"连通图"**。少一个 ON，就会出现笛卡尔积。
2. **LEFT JOIN 不适用这条规则**。左连接谁在左边，谁被完整保留，顺序/方向是有语义的。

### 4.6 没有 ON 的 JOIN：不是漏写，可能是故意的

看第 02 章给 SUPER_ADMIN 分配权限的 SQL：

```sql
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p
WHERE r.code = 'SUPER_ADMIN';
```

这里没有 `ON`。它在干什么？

1. `sys_role`（3 行）× `sys_permission`（11 行）= 33 行；
2. `WHERE r.code = 'SUPER_ADMIN'` 只剩"超级管理员 × 11 个权限" = 11 行；
3. 把这 11 行插进关联表。

**"先造笛卡尔积，再用 WHERE 筛出想要的那一块"**——这是 `JOIN ... WHERE` 的常见用法。

同样的逻辑也出现在给用户分配角色的 SQL：

```sql
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r
WHERE u.username = 'admin' AND r.code = 'SUPER_ADMIN';
```

3 × 3 = 9 行，WHERE 筛到 1 行。

> ⚠️ **警告**：这种写法的安全性完全依赖 `WHERE`。一旦 WHERE 写错或漏掉，就会插入一大堆错误的关联行。
> 平时写查询时，**看到 JOIN 没有 ON，第一反应应该是"这是不是漏了"**，确认是故意的交叉连接后再放过。

等价的"老式写法"是用逗号：

```sql
SELECT u.username, ur.role_id
FROM sys_user u, sys_user_role ur
WHERE ur.user_id = u.id;
```

这叫"隐式 JOIN"，和 `JOIN ... ON` 等价。但**不推荐**：

- 连接条件和过滤条件混在 `WHERE` 里，分不清；
- 表一多，漏一个条件就变笛卡尔积；
- 和 `LEFT JOIN` 混写时优先级容易出错。

**统一用显式 `JOIN ... ON`。**

### 4.7 读一条多表 SQL 的 5 个步骤

拿到任何一条多表 SQL，按这个顺序拆：

1. **找 FROM 的主表**：第一张表是谁？
2. **逐个看 JOIN 的表和 ON**：每张新表通过哪个字段接进来？
3. **画链路**：把 `A — B — C` 画出来，看有没有断点。
4. **看 WHERE 过滤谁**：过滤的是主表、关联表还是结果？
5. **看 SELECT 输出什么**：有哪些列、哪些别名、要不要去重/分组？

拿你项目里这条来练：

```sql
SELECT DISTINCT p.code AS permissionCode
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur       ON ur.role_id = rp.role_id
JOIN sys_role r             ON r.id = ur.role_id
WHERE ur.user_id = #{userId}
  AND r.status = 1 AND r.deleted = 0
  AND p.status = 1 AND p.deleted = 0;
```

拆解：

1. 主表：`sys_permission p`（要的是权限码，所以从权限出发）。
2. 链路：`p — rp — ur — r`。`p.id = rp.permission_id`，`rp.role_id = ur.role_id`，`ur.role_id = r.id`。
3. 画图：

```text
sys_permission p
      │ p.id = rp.permission_id
sys_role_permission rp
      │ rp.role_id = ur.role_id
sys_user_role ur
      │ ur.role_id = r.id
sys_role r
```

4. WHERE：`ur.user_id` 限定某个用户；`r`、`p` 限定启用且未删除。
5. SELECT：只要 `p.code`，用 `DISTINCT` 去重。

**为什么这里要 JOIN `sys_role r`？** 只是为了用 `r.status`、`r.deleted` 过滤掉禁用/删除的角色。如果不需要这两个过滤条件，这张表完全可以不 JOIN。

这就回答了你"为什么一会儿这样连、一会儿那样连"：**每张被 JOIN 的表，要么提供你要输出的列，要么提供你要过滤的条件，否则它就不该出现在 SQL 里。**

---

## 5. LEFT JOIN：左表全部保留

### 5.1 语义

```text
A LEFT JOIN B
= A 的每一行都必须出现
= 在 B 中能找到配对的，正常拼上
= 找不到配对的，B 的列全部补 NULL
```

```text
      A          B
   ┌─────┐   ┌─────┐
   │  ●──┼───┼──●  │
   │  ●──┼───┼──●  │
   │  ●  │   │  ●  │   ← 左表 ● 全部保留
   └─────┘   └─────┘
       ↑ 找不到配对的，右表补 NULL
```

一句话：**LEFT JOIN = "左表必须全在，右表有就配上，没有就空着"。**

### 5.2 需求：列出所有用户，包括没角色的

INNER JOIN 会丢掉没有角色的用户。如果后台要"用户列表 + 角色"，没角色的用户也得显示，就必须用 LEFT JOIN。

```sql
SELECT u.id, u.username, r.code AS role_code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r        ON r.id = ur.role_id
ORDER BY u.id;
```

以当前数据（3 个用户都有角色）结果和 INNER JOIN 一样：

| id | username | role_code |
|---|---|---|
| 1 | admin | SUPER_ADMIN |
| 2 | product_user | PRODUCT_ADMIN |
| 3 | order_user | ORDER_ADMIN |

**差别要在"有用户没角色"时才看得出来。** 做下面这个实验（在事务里插入，最后回滚，不会污染数据）：

```sql
START TRANSACTION;

INSERT INTO sys_user (username, password_hash, nickname, status, token_version)
VALUES ('no_role_user', 'dummy', '没有角色的用户', 1, 0);

SELECT u.id, u.username, r.code AS role_code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r        ON r.id = ur.role_id
ORDER BY u.id;

ROLLBACK;
```

结果多出一行：

| id | username | role_code |
|---|---|---|
| 1 | admin | SUPER_ADMIN |
| 2 | product_user | PRODUCT_ADMIN |
| 3 | order_user | ORDER_ADMIN |
| 4 | no_role_user | **NULL** |

如果换成 `JOIN`（INNER），`no_role_user` 就直接消失了。

> **LEFT JOIN 的价值就在这个 NULL 行**：它告诉你"这个用户存在，但没有任何角色"。业务上这往往是最需要关注的数据。

### 5.3 最容易错的地方：右表条件放 ON 还是 WHERE

这是 LEFT JOIN 的第一大坑，也是面试常考点。

**需求**：列出所有用户，并标记谁拥有 `ORDER_ADMIN` 角色。没有这个角色的用户也要出现。

**正确写法：条件放在 `ON`**

```sql
SELECT u.id, u.username, r.code AS role_code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r
       ON r.id = ur.role_id
      AND r.code = 'ORDER_ADMIN'        -- ← 放在 ON
ORDER BY u.id;
```

结果（3 行，所有用户都在）：

| id | username | role_code |
|---|---|---|
| 1 | admin | **NULL** |
| 2 | product_user | **NULL** |
| 3 | order_user | ORDER_ADMIN |

`admin` 和 `product_user` 不满足 `r.code = 'ORDER_ADMIN'`，但**用户本身被保留了**，右表列补 NULL。

**错误写法：条件放在 `WHERE`**

```sql
SELECT u.id, u.username, r.code AS role_code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r       ON r.id = ur.role_id
WHERE r.code = 'ORDER_ADMIN'           -- ← 放在 WHERE
ORDER BY u.id;
```

结果只剩 **1 行**：

| id | username | role_code |
|---|---|---|
| 3 | order_user | ORDER_ADMIN |

`admin` 和 `product_user` 不见了。

为什么？逻辑执行顺序是：

1. LEFT JOIN 先完成：3 个用户都保留，`admin`/`product_user` 的 `r.code` 是 NULL；
2. WHERE 再过滤：`NULL = 'ORDER_ADMIN'` 的结果不是 true，这两行被丢掉。

**结论：**

> 对 LEFT JOIN 的右表做过滤时：
> - 你想表达"**匹配时只匹配满足条件的**，左表仍然全保留" → 条件放 `ON`；
> - 你想表达"**最终结果里必须满足条件的**，不满足就整行不要" → 条件放 `WHERE`。
>
> 放错位置，LEFT JOIN 就悄悄退化成了 INNER JOIN。

> **如果右表是一对多，条件放 `ON` 也不是"一个用户一行"。**
> 例如把 `p.module = 'product'` 放在最后一个权限表的 `ON` 里，3 个用户会得到 **17 行**：admin 的 11 条角色-权限关系里，4 条匹配到商品权限，另外 7 条各产生一行 NULL；`order_user` 的 2 条关系也各产生一行 NULL。合计 8 行有权限值、9 行是 NULL。
> 想得到"一个用户一行"，还要配合 `DISTINCT`、`GROUP BY`，或先在子查询里把权限聚合好再连接。这就是 7.4 节"行数膨胀"的另一种表现。

### 5.4 多级 LEFT JOIN：中间不能混 INNER

LEFT JOIN 的"保留"是靠链路一路传下去的。中间只要插入一个 INNER JOIN，前面的保留就断了。

**错误示例**：

```sql
SELECT u.username, p.code
FROM sys_user u
LEFT JOIN sys_user_role ur       ON ur.user_id = u.id
JOIN      sys_role r             ON r.id = ur.role_id      -- ← 这里用了 INNER
LEFT JOIN sys_role_permission rp ON rp.role_id = r.id
LEFT JOIN sys_permission p       ON p.id = rp.permission_id;
```

假设库里有一个没有角色的用户（比如 5.2 节实验里临时插入的 `no_role_user`），它在 `ur` 阶段是 NULL；到了 `JOIN sys_role r ON r.id = ur.role_id`，`NULL = r.id` 永远不成立，这一行被 INNER JOIN 丢掉。**前面的 LEFT 白写了。**

规则：

> 链路上要"一路 LEFT 到底"。如果某张关联表确实必须存在，那用 INNER 也没问题，但要清楚它会终止"保留"。

### 5.5 计数：`COUNT(*)` 和 `COUNT(右表列)` 不一样

```sql
SELECT
  COUNT(*)      AS row_count,
  COUNT(r.id)   AS role_count
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r        ON r.id = ur.role_id;
```

- `COUNT(*)` 数的是**行数**，NULL 行也算 → 在 5.2 节那个含 `no_role_user` 的事务里是 4；
- `COUNT(r.id)` 数的是**非 NULL 的 `r.id`** → 3。

> 在你的基准数据上（3 个用户都有角色），两个值都是 3，看不出差别。所以这个实验要配合 5.2 节的临时用户一起做。

**要统计"每个用户有几个角色"，角色数可能为 0 时，必须用 `COUNT(r.id)` 或 `COUNT(ur.role_id)`，不能用 `COUNT(*)`。**

```sql
SELECT
  u.username,
  COUNT(r.id) AS role_count
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r        ON r.id = ur.role_id
GROUP BY u.id, u.username
ORDER BY u.id;
```

### 5.6 LEFT JOIN 在 RBAC 里的真实用例

**用例一：角色列表 + 权限，允许"角色暂时没有权限"**

```sql
SELECT r.id, r.code AS role_code, p.code AS perm_code
FROM sys_role r
LEFT JOIN sys_role_permission rp ON rp.role_id = r.id
LEFT JOIN sys_permission p
       ON p.id = rp.permission_id
      AND p.status = 1 AND p.deleted = 0
WHERE r.deleted = 0
ORDER BY r.id, p.id;
```

如果用 INNER JOIN，一个还没配权限的新角色会从角色列表里消失，管理员就永远没法给它配权限了。

**用例二：权限管理页面——列出所有权限，并标记当前角色是否已拥有**

```sql
SELECT
  p.id,
  p.code,
  p.name,
  rp.role_id IS NOT NULL AS assigned
FROM sys_permission p
LEFT JOIN sys_role_permission rp
       ON rp.permission_id = p.id
      AND rp.role_id = 2                 -- 假设编辑 PRODUCT_ADMIN
WHERE p.status = 1 AND p.deleted = 0
ORDER BY p.id;
```

结果里 11 个权限全部出现，`assigned` 为 1 表示该角色已有此权限，为 0 表示未分配。

这就是后台"勾选框"页面的典型查询：**左边的候选列表必须完整，右边只是"是否匹配"的信息**——正是 LEFT JOIN 的语义。

**用例三：用户列表 + 每个用户的角色数量（含 0）**

就是 5.5 节的 `COUNT(r.id)` 写法。

---

## 6. RIGHT JOIN：把 LEFT JOIN 反过来

### 6.1 语义

```text
A RIGHT JOIN B
= B 的每一行都必须出现
= A 中找不到配对的，A 的列补 NULL
= 等价于 B LEFT JOIN A（把两张表位置互换）
```

```sql
SELECT u.username, r.code AS role_code
FROM sys_user u
RIGHT JOIN sys_user_role ur ON ur.user_id = u.id
RIGHT JOIN sys_role r       ON r.id = ur.role_id;
```

这条的意思是：**保留所有角色**，即使没有用户拥有它。注意方向——现在被保留的是 `sys_role`。

### 6.2 总是可以改写成 LEFT JOIN

```sql
-- RIGHT JOIN 版：保留所有角色
SELECT ur.user_id, r.code
FROM sys_user_role ur
RIGHT JOIN sys_role r ON r.id = ur.role_id
ORDER BY r.id;

-- 等价的 LEFT JOIN 版（两张表位置互换）
SELECT ur.user_id, r.code
FROM sys_role r
LEFT JOIN sys_user_role ur ON ur.role_id = r.id
ORDER BY r.id;
```

两条结果完全一样。

### 6.3 为什么项目里几乎看不到 RIGHT JOIN

1. **阅读方向反直觉**：人习惯"从主体出发，向右扩展"。`FROM 角色 LEFT JOIN 用户角色` 比 `FROM 用户角色 RIGHT JOIN 角色` 好懂。
2. **链式 RIGHT JOIN 很容易写错**：多个 RIGHT JOIN 连在一起时，到底保留谁，几乎没人能一眼看对。
3. **一条 SQL 里混用 LEFT 和 RIGHT** 可读性极差。

> **工程约定：统一用 LEFT JOIN。需要 RIGHT JOIN 时，把两张表调换位置改写成 LEFT JOIN。**
> 这不是语法限制，是团队可维护性的选择。

### 6.4 MySQL 没有 FULL OUTER JOIN

标准 SQL 的 `FULL OUTER JOIN`（两边都保留）MySQL 不支持。需要时用 `UNION` 模拟：

```sql
SELECT u.username, r.code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r        ON r.id = ur.role_id

UNION

SELECT u.username, r.code
FROM sys_user u
RIGHT JOIN sys_user_role ur ON ur.user_id = u.id
RIGHT JOIN sys_role r        ON r.id = ur.role_id;
```

`UNION` 会自动去重（`UNION ALL` 不去重）。实际项目里很少需要"两边都保留"，知道有这么回事即可。

---

## 7. 一对多带来的行数膨胀：DISTINCT、GROUP BY、resultMap

这一节解释你项目里那些 `DISTINCT` 和 `resultMap` 为什么存在。

### 7.1 一个用户对应 11 个权限

```sql
SELECT u.username, p.code
FROM sys_user u
JOIN sys_user_role ur       ON ur.user_id = u.id
JOIN sys_role_permission rp ON rp.role_id = ur.role_id
JOIN sys_permission p       ON p.id = rp.permission_id
WHERE u.id = 1;
```

结果 11 行：

| username | code |
|---|---|
| admin | product:read |
| admin | product:write |
| ... | ... |
| admin | log:read |

`admin` 出现了 11 次——**这不是重复数据，是关系本身的形状**。一个用户有 11 个权限，平铺开就是 11 行。

### 7.2 什么情况下真的会产生重复

如果两个角色拥有同一个权限，同一个用户的同一个权限就会出现两次。

以 `product_user` 为例，它现有 4 个权限。再给它一个"临时审计"角色，也包含 `product:read`：

```sql
START TRANSACTION;

INSERT INTO sys_role (code, name, status)
VALUES ('TEMP_AUDITOR', '临时审计', 1);
SET @rid = LAST_INSERT_ID();

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT @rid, id FROM sys_permission
WHERE code IN ('product:read', 'user:read');

INSERT INTO sys_user_role (user_id, role_id)
VALUES (2, @rid);

-- 不去重：product:read 出现两次
SELECT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur       ON ur.role_id = rp.role_id
WHERE ur.user_id = 2
ORDER BY p.code;

ROLLBACK;
```

不去重的结果是 6 行，`product:read` 出现两次。

加 `DISTINCT` 后是 5 行：

```sql
SELECT DISTINCT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur       ON ur.role_id = rp.role_id
WHERE ur.user_id = 2
ORDER BY p.code;
```

> 这就是你项目里 `selectPermissionCodeByUserId` 写 `SELECT DISTINCT p.code` 的原因：
> **用户可能通过多个角色重复获得同一个权限，但权限码集合里只需要出现一次。**

注意 `DISTINCT` 作用于**整个 SELECT 列表**，不是某一个字段：

```sql
-- 去重的是 (p.code, r.code) 这个组合，不是单独的 p.code
SELECT DISTINCT p.code, r.code
FROM ...
```

### 7.3 用 GROUP_CONCAT 把多行压成一行

如果只是想"看一眼每个用户有哪些权限"，可以：

```sql
SELECT
  u.username,
  GROUP_CONCAT(DISTINCT p.code ORDER BY p.code SEPARATOR ', ') AS permissions
FROM sys_user u
JOIN sys_user_role ur       ON ur.user_id = u.id
JOIN sys_role_permission rp ON rp.role_id = ur.role_id
JOIN sys_permission p       ON p.id = rp.permission_id
GROUP BY u.id, u.username
ORDER BY u.id;
```

结果：

| username | permissions |
|---|---|
| admin | log:read, order:read, order:ship, product:delete, ... |
| product_user | product:delete, product:read, product:write, stock:decrease |
| order_user | order:read, order:ship |

`GROUP_CONCAT` 是 MySQL 的聚合函数，适合调试、导出报表，**不适合给程序做结构化解析**。

### 7.4 每个 JOIN 之后行数怎么变

以"用户 → 角色 → 权限"为例：

| 步骤 | SQL 片段 | 行数 | 说明 |
|---|---|---|---|
| 1 | `FROM sys_user` | 3 | 3 个用户 |
| 2 | `JOIN sys_user_role` | 3 | 每人 1 个角色 |
| 3 | `JOIN sys_role` | 3 | 角色信息拼上 |
| 4 | `JOIN sys_role_permission` | **17** | admin 11 + product 4 + order 2 |
| 5 | `JOIN sys_permission` | 17 | 权限信息拼上（一对一） |
| 6 | `WHERE u.id = 1` | 11 | 只看 admin |
| 7 | `SELECT DISTINCT p.code` | 11 | 去重 |

**行数在哪一步膨胀，一目了然。** 以后查慢 SQL、查重复数据，先看这个表。

### 7.5 MyBatis 的 resultMap：把扁平的行折叠成嵌套对象

`selectRolePermissionByUserId` 是**按用户**查询的。以 admin（id=1）为例，返回 11 行扁平数据：

```text
role_id | role_name | perm_id | perm_code
1       | 超级管理员 | 1       | product:read
1       | 超级管理员 | 2       | product:write
1       | 超级管理员 | 3       | product:delete
...
1       | 超级管理员 | 11      | log:read
```

但接口要的是嵌套结构：

```java
List<SysRoleVO>              // admin 只有 1 个角色；多角色用户会有多个
  └── List<SysPermissionVO>  // 该角色下的 11 个权限
```

> 如果把 `WHERE ur.user_id = ?` 去掉、查所有用户，才是 17 行折叠成 3 个角色对象。
> 按单个用户查时，行数取决于"这个用户有几个角色、这些角色一共配了多少权限"。

`resultMap` 负责这个"折叠"：

```xml
<resultMap id="rolePermissionMap" type="com.keywin.mall.modules.system.vo.SysRoleVO">
    <!-- 这一行怎么判断"属于同一个角色"？看 role_id -->
    <id     property="id"   column="role_id"/>
    <result property="name" column="role_name"/>

    <!-- 同一角色下的每一行，往 permissionList 里塞一个权限对象 -->
    <collection property="permissionList" ofType="...SysPermissionVO">
        <id     property="id"   column="perm_id"/>
        <result property="name" column="perm_name"/>
    </collection>
</resultMap>
```

关键点：

1. **`<id column="role_id">` 是分组的依据**。MyBatis 用每一行的 `role_id` 判断它属于哪个 `SysRoleVO`，相同的聚合到同一个对象里。
2. **`<collection>` 表示一对多**：每个角色下挂一个 `List<SysPermissionVO>`。
3. **`column` 必须等于 SELECT 的列别名**。所以 SQL 里写 `r.id AS role_id`、`p.id AS perm_id`，否则两个 `id` 会打架。
4. **`ORDER BY r.id, p.id` 不是装饰**：它保证同一个角色的行按顺序处理，权限列表的顺序也稳定，嵌套映射结果不会因为行顺序变化而乱。

用错别名会怎样？例如把 SQL 改成：

```sql
SELECT r.id AS id, p.id AS id, ...   -- 两个列都叫 id
```

MyBatis 会看到两个 `id` 列，后一个覆盖前一个，映射结果可能变成：**角色 id = 权限 id**。这种 bug 不会报错，只会数据错，非常难查。

### 7.6 `resultType` 和 `resultMap` 怎么选

| 场景 | 用法 | 例子 |
|---|---|---|
| 只查一列 | `resultType="String"` / `Long` 等 | `SELECT p.code FROM ...` |
| 扁平 VO，列名和属性名能对上 | `resultType="XxxVO"` | `SELECT u.id, u.username FROM sys_user u`（映射到 `UserVO`） |
| 有嵌套对象/集合 | `resultMap` + `<association>` / `<collection>` | 角色 + 权限列表 |

你项目里的三种都出现了：

```xml
<!-- 一列，返回 List<String>；别名 permissionCode 不影响映射 -->
<select id="selectPermissionCodeByUserId" resultType="String">
    select distinct p.code as permissionCode ...
</select>

<!-- 扁平角色 VO；但这里用了 resultMap，因为还有权限集合 -->
<select id="selectRolePermissionByUserId" resultMap="rolePermissionMap">
    ...
</select>
```

> 补充：`resultType="String"` 时，MyBatis 只取唯一一列的值，列名叫 `code` 还是 `permissionCode` 都无所谓。
> 但**扁平 VO** 和 **resultMap** 就非常依赖列名/别名。

---

## 8. 你项目里的四条 SQL，逐条拆解

现在把 `sysPermissionMapper.xml` 里的四条查询放在一起看。你会发现"为什么这条连 2 张表、那条连 4 张表"其实很有规律。

### 8.1 总览

| 方法 | 要回答的问题 | FROM 起点 | 链路 | 关键过滤 | 特殊处理 |
|---|---|---|---|---|---|
| `selectPermissionCodeByUserId` | 这个用户有哪些权限码 | `sys_permission` | p → rp → ur → r | `ur.user_id`、r/p 的 status/deleted | `DISTINCT` |
| `selectRoleCodeByUserId` | 这个用户有哪些角色码 | `sys_role` | r → ur | `ur.user_id`、r 的 status/deleted | `DISTINCT` |
| `selectPermissionCodeByRoleId` | 这个角色有哪些权限码 | `sys_permission` | p → rp | `rp.role_id`、p 的 status/deleted | `DISTINCT` |
| `selectRolePermissionByUserId` | 这个用户每个角色下有哪些权限 | `sys_role` | r → ur → rp → p | `ur.user_id`、r.deleted | `resultMap` 嵌套 |

规律：

- **要输出哪张表的字段，FROM 就从哪张表出发**（或至少把它 JOIN 进来）。
- **过滤条件依赖谁，就把谁 JOIN 进来**。
- **关联表（ur/rp）只在需要"穿过关系"时出现**。
- 第一条和第四条很像，区别是：第一条只要"去重后的权限码集合"，第四条要"按角色分组后的完整对象"。

### 8.2 第一条：用户 → 权限码

```sql
select distinct p.code as permissionCode
from sys_permission p
join sys_role_permission rp on rp.permission_id = p.id
join sys_user_role ur       on ur.role_id = rp.role_id
join sys_role r             on r.id = ur.role_id
where ur.user_id = #{userId}
  and r.status = 1 and r.deleted = 0
  and p.status = 1 and p.deleted = 0
```

逐行：

- `from sys_permission p`：最终要 `p.code`。
- `join ... rp on rp.permission_id = p.id`：权限 → 哪些角色拥有它。
- `join ... ur on ur.role_id = rp.role_id`：这些角色 → 哪些用户拥有。
- `join sys_role r on r.id = ur.role_id`：**只为过滤**角色是否启用、是否删除。
- `where ur.user_id = ?`：锁定用户。
- `distinct`：多个角色可能有同一权限，去重。

> 为什么 `sys_role` 的 JOIN 条件写成 `r.id = ur.role_id` 而不是 `r.id = rp.role_id`？
> 两者都对（`ur.role_id` 和 `rp.role_id` 在这一刻都等于角色 id）。它只是把 `r` 接到链路上的任意一个等价位置。
> 这也是 INNER JOIN 顺序无关的又一个例子。

### 8.3 第二条：用户 → 角色码

```sql
select distinct r.code as roleCode
from sys_role r
join sys_user_role ur on ur.role_id = r.id
where ur.user_id = #{userId}
  and r.status = 1 and r.deleted = 0
```

- 只要角色码，FROM `sys_role`。
- 需要通过 `sys_user_role` 知道"这个用户有没有这个角色"。
- 一张关联表就够，不需要权限表。

### 8.4 第三条：角色 → 权限码

```sql
select distinct p.code as permissionCode
from sys_permission p
join sys_role_permission rp on rp.permission_id = p.id
where rp.role_id = #{roleId}
  and p.status = 1 and p.deleted = 0
```

- 只要权限码，FROM `sys_permission`。
- 通过 `sys_role_permission` 锁定角色。
- **比第一条少了 `ur` 和 `r`**：因为不需要"从用户穿过去"，也不需要过滤角色状态（调用方已经知道角色 id）。

### 8.5 第四条：用户 → 角色 + 权限（嵌套）

```sql
SELECT
  r.id AS role_id, r.name AS role_name, r.code AS role_code, ...
  p.id AS perm_id, p.name AS perm_name, p.code AS perm_code, ...
FROM sys_role r
JOIN sys_user_role ur       ON ur.role_id = r.id
JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p       ON p.id = rp.permission_id
WHERE ur.user_id = #{userId} AND r.deleted = 0
ORDER BY r.id, p.id
```

- FROM `sys_role`：因为最外层结果按角色组织。
- `ur`：锁定用户。
- `rp`：角色 → 权限。
- `p`：输出权限字段。
- 扁平结果 → `resultMap` 按 `role_id` 折叠：admin 的 11 行变成 1 个角色对象；用户有多个角色时，就有多个角色对象。
- **注意**：这条用的是 INNER JOIN，所以"没有任何权限的角色"不会出现。如果产品要求显示空角色，要改成从 `sys_role` LEFT JOIN `rp` 再 LEFT JOIN `p`。

### 8.6 四条 SQL 的链路画在一起

```text
                  ┌──────────────┐
                  │  sys_user u  │
                  └──────┬───────┘
                         │ u.id = ur.user_id
                  ┌──────▼───────────┐
                  │ sys_user_role ur │
                  └──────┬───────────┘
                         │ ur.role_id = r.id
                  ┌──────▼───────┐
       ┌──────────┤  sys_role r  ├──────────┐
       │          └──────┬───────┘          │
       │                 │ r.id = rp.role_id│
       │          ┌──────▼────────────────┐ │
       │          │ sys_role_permission rp│ │
       │          └──────┬────────────────┘ │
       │                 │ rp.permission_id = p.id
       │          ┌──────▼──────────┐       │
       └──────────► sys_permission p ◄──────┘
                  └─────────────────┘
```

记住这张图，四条 SQL 都是在图上"截取不同的路径"：

- 用户→角色：走左边两段；
- 角色→权限：走右边两段；
- 用户→权限：走完整条。

---

## 9. 实验课：亲手把疑惑跑出来

> 下面所有带 `START TRANSACTION` 的实验，最后都有 `ROLLBACK`，不会污染你的库。
> 建议每做完一个实验，先自己猜结果，再执行。

### 实验 1：笛卡尔积

```sql
SELECT COUNT(*) FROM sys_user u JOIN sys_role r;              -- 9
SELECT COUNT(*) FROM sys_user u JOIN sys_permission p;        -- 33
SELECT COUNT(*) FROM sys_user u
JOIN sys_role r
JOIN sys_permission p;                                        -- 99
```

**结论**：每多一张没有连接条件的表，行数就乘以它的行数。

### 实验 2：加一个 ON，行数立刻降下来

```sql
SELECT COUNT(*)
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;    -- 3

SELECT COUNT(*)
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r       ON r.id = ur.role_id;    -- 3
```

### 实验 3：INNER vs LEFT，制造一个没角色的用户

```sql
START TRANSACTION;

INSERT INTO sys_user (username, password_hash, nickname, status, token_version)
VALUES ('no_role_user', 'dummy', '没有角色的用户', 1, 0);

-- INNER：3 行，no_role_user 消失
SELECT u.username, r.code
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r       ON r.id = ur.role_id
ORDER BY u.id;

-- LEFT：4 行，no_role_user 的 code 是 NULL
SELECT u.username, r.code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r       ON r.id = ur.role_id
ORDER BY u.id;

ROLLBACK;
```

### 实验 4：ON 和 WHERE 的位置差异

```sql
-- ON 过滤：3 行，admin / product_user 保留，role_code 为 NULL
SELECT u.username, r.code AS role_code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r
       ON r.id = ur.role_id AND r.code = 'ORDER_ADMIN'
ORDER BY u.id;

-- WHERE 过滤：1 行，只剩 order_user
SELECT u.username, r.code AS role_code
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r       ON r.id = ur.role_id
WHERE r.code = 'ORDER_ADMIN'
ORDER BY u.id;
```

再做一个进阶观察：右表是一对多时，`ON` 过滤会留下多条 NULL 行。

```sql
SELECT COUNT(*)
FROM sys_user u
LEFT JOIN sys_user_role ur       ON ur.user_id = u.id
LEFT JOIN sys_role_permission rp ON rp.role_id = ur.role_id
LEFT JOIN sys_permission p
       ON p.id = rp.permission_id AND p.module = 'product';
-- 17 行（不是 3 行），其中 9 行 p.code 为 NULL
```

### 实验 5：RIGHT JOIN 改写

```sql
-- 两边结果一样：都保留全部角色
SELECT u.username, r.code
FROM sys_user u
RIGHT JOIN sys_user_role ur ON ur.user_id = u.id
RIGHT JOIN sys_role r       ON r.id = ur.role_id
ORDER BY r.id;

SELECT u.username, r.code
FROM sys_role r
LEFT JOIN sys_user_role ur ON ur.role_id = r.id
LEFT JOIN sys_user u       ON u.id = ur.user_id
ORDER BY r.id;
```

### 实验 6：多角色重复权限 + DISTINCT

用 7.2 节的事务实验。观察：

- 不加 `DISTINCT`：6 行，`product:read` 两次；
- 加 `DISTINCT`：5 行。

### 实验 7：同名列报错

```sql
-- 报错：Column 'id' in field list is ambiguous
SELECT id, username
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;

-- 正确
SELECT u.id AS user_id, ur.id AS user_role_id, u.username
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id;
```

### 实验 8：列别名不能在 WHERE 用

```sql
-- 报错：Unknown column 'login_name' in 'where clause'
SELECT username AS login_name FROM sys_user WHERE login_name = 'admin';

-- 正确：WHERE 用原列名
SELECT username AS login_name FROM sys_user WHERE username = 'admin';

-- 正确：ORDER BY 可以用列别名
SELECT username AS login_name FROM sys_user ORDER BY login_name;
```

### 实验 9：COUNT(*) vs COUNT(r.id)

```sql
START TRANSACTION;

INSERT INTO sys_user (username, password_hash, nickname, status, token_version)
VALUES ('no_role_user', 'dummy', '没有角色的用户', 1, 0);

SELECT
  COUNT(*)    AS row_count,    -- 4
  COUNT(r.id) AS role_count    -- 3
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
LEFT JOIN sys_role r       ON r.id = ur.role_id;

ROLLBACK;
```

### 实验 10：用 EXPLAIN 看数据库怎么连表

```sql
EXPLAIN
SELECT p.code
FROM sys_permission p
JOIN sys_role_permission rp ON rp.permission_id = p.id
JOIN sys_user_role ur       ON ur.role_id = rp.role_id
WHERE ur.user_id = 1;
```

观察执行计划里的 `key` 列（MySQL 8.0 的表格形式），或者树形输出里的 `using ...`（MySQL 8.0.18+ / 9.x 可能默认显示树形）：

- `sys_user_role` 应该用到 `uk_user_role`（`user_id, role_id` 联合索引，`user_id` 在最左）；
- `sys_role_permission` 应该用到 `uk_role_permission`（`role_id, permission_id`）或 `idx_permission_id`；
- `sys_permission` 用主键 `PRIMARY`。

**这就是为什么关联表要建联合索引**：JOIN 的 `ON` 条件能不能走索引，直接决定查询快慢。第 02 章的建表 SQL 已经把这些索引建好了。

---

## 10. 常见坑清单

### 坑 1：忘写 ON，结果行数爆炸

**症状**：3 个用户查出 99 行；接口返回重复数据；分页总数不对。

**原因**：多表 JOIN 少了一个连接条件，产生笛卡尔积。

**解决**：`JOIN` 的每一张新表都必须有 `ON`；看到 `JOIN` 后面直接跟 `WHERE`，先怀疑漏了 ON。用 `COUNT(*)` 验证：3 个用户 × 3 个角色 × 11 个权限 = 99，就是典型的爆炸。

### 坑 2：LEFT JOIN 的右表条件写进 WHERE

**症状**：明明写了 LEFT JOIN，结果却是 INNER JOIN；"没有关联数据的左表行"就是不出现。

**原因**：WHERE 在连接之后执行，`NULL = 某值` 为 false，把补 NULL 的行过滤掉了。

**解决**：想保留左表全部行，右表的匹配条件放 `ON`；确实要过滤最终结果，才放 `WHERE`。

### 坑 3：多级 LEFT JOIN 中间混入 INNER JOIN

**症状**：第一个 LEFT JOIN 有效，后面的数据一加进来，前面的 NULL 行又没了。

**原因**：INNER JOIN 会把上一阶段补的 NULL 行丢掉，保留链断裂。

**解决**：一路 LEFT 到底，或者改变起点，从"必须存在的表"开始写。

### 坑 4：`SELECT *` 或 `SELECT id` 直接报错/串值

**症状**：`Column 'id' in field list is ambiguous`；或者 MyBatis 映射出来 id 不对。

**原因**：多张表都有 `id`，结果列重名。

**解决**：多表查询**不要 `SELECT *`**，显式列出需要的列，并给同名列起不同的列别名。

### 坑 5：列别名写在 WHERE

**症状**：`Unknown column 'xxx' in 'where clause'`，但 SELECT 里明明起了这个别名。

**原因**：执行顺序 `WHERE` 在 `SELECT` 之前。

**解决**：WHERE 用原列名/表达式；或者把条件放到 `HAVING`（仅分组场景）。

### 坑 6：resultMap 的 column 和 SQL 别名对不上

**症状**：查到了数据，但 VO 字段全是 `null`；或者角色 id 变成了权限 id。

**原因**：`<result column="xxx">` 写的是 A，SQL 里别名是 B；或者两个同名列没有区分。

**解决**：`resultMap` 的 `column` 必须和 SELECT 别名逐字一致；多表同名列必须用 `role_id`、`perm_id` 这种后缀区分。

### 坑 7：一对多 JOIN 后直接分页 / 计数

**症状**：分页每页 10 条，实际只看到 2 个用户；总数远大于用户数。

**原因**：JOIN 后是"用户 × 权限"的行数，不是用户数。对膨胀后的行分页/计数，语义就错了。

**解决**：分页/计数只针对主表；需要子数据时再单独查（或先分页再批量查子表）。这也是第 02 章 `SysRoleService.list()` 用"角色查询 + 每角色权限查询"而不是一条大 JOIN 的原因之一（虽然那里还有 N+1 的问题，后续章节会优化）。

### 坑 8：`DISTINCT` 用错位置

**症状**：以为按权限码去重了，结果还是重复。

**原因**：`DISTINCT` 作用于整个 SELECT 列表。`SELECT DISTINCT p.code, r.code` 去重的是"权限+角色"组合，不是单独权限码。

**解决**：只选需要的列；确实要按某列去重，用 `GROUP BY` 或调整 SELECT 列表。

### 坑 9：忘了逻辑删除条件

**症状**：删除的角色/权限还在权限列表里；用户还能访问已删除角色。

**原因**：MyBatis-Plus 的 `@TableLogic` 只对 `BaseMapper` 生成的单表 SQL 自动追加 `deleted = 0`；**手写 JOIN SQL 不会自动加**。

**解决**：手写多表 SQL 时，每张可能被软删的表都要显式写 `xxx.deleted = 0`。你项目里的两条按用户查权限/角色的 SQL 都写了；第四条 `selectRolePermissionByUserId` 只过滤了 `r.deleted = 0`，没过滤 `p.deleted/status`，这是一个值得留意的差异。

### 坑 10：状态过滤放错层

**症状**：禁用的角色/权限仍然出现在结果里；或者"所有用户"少了一批。

**原因**：过滤条件放 `ON` 还是 `WHERE` 语义不同（见 5.3 节）。

**解决**：先明确"过滤的是匹配关系，还是最终结果"，再决定位置。

### 坑 11：`ON` 里混用 `OR` 没加括号

```sql
-- 危险：AND 优先级高于 OR，条件可能不是你想要的
LEFT JOIN sys_permission p
  ON p.id = rp.permission_id AND p.status = 1 OR p.deleted = 0
```

**解决**：用括号明确优先级：

```sql
ON p.id = rp.permission_id AND (p.status = 1 OR p.deleted = 0)
```

### 坑 12：NULL 比较

**症状**：`WHERE p.module != 'product'` 查不出 NULL 行。

**原因**：任何值和 NULL 比较，结果都是 UNKNOWN，不是 true。

**解决**：用 `IS NULL` / `IS NOT NULL`；或 `COALESCE(col, '')`。

### 坑 13：JOIN 字段类型不一致 / 没索引

**症状**：数据量一大就慢；`EXPLAIN` 显示 `type=ALL`。

**原因**：`user_id` 是 `BIGINT`，传参是字符串；或者关联字段没有索引。

**解决**：类型一致；关联字段建索引。你项目的关联表已经有 `UNIQUE (user_id, role_id)`、`KEY (role_id)` 等索引。

### 坑 14：以为 LEFT JOIN 是"先执行左表"

**症状**：以为 LEFT JOIN 只是"执行顺序"，用它来控制性能。

**原因**：LEFT JOIN 的核心是**结果保留语义**，不是执行顺序。执行顺序由优化器决定。

**解决**：用 `EXPLAIN` 看实际执行计划；控制顺序用 `STRAIGHT_JOIN`（了解即可，不要滥用）。

---

## 11. 速查表

### 11.1 JOIN 类型

| 写法 | 保留规则 | 一句话 |
|---|---|---|
| `A JOIN B ON ...` | 两边都匹配才保留 | 只要交集 |
| `A INNER JOIN B ON ...` | 同上 | `JOIN` = `INNER JOIN` |
| `A LEFT JOIN B ON ...` | A 全保留，B 配不上补 NULL | 左表优先 |
| `A RIGHT JOIN B ON ...` | B 全保留，A 配不上补 NULL | 右表优先（建议改写成 LEFT） |
| `A CROSS JOIN B` | 全部组合 | 笛卡尔积 |
| `FULL OUTER JOIN` | 两边都保留 | MySQL 不支持，用 UNION 模拟 |

### 11.2 选哪种 JOIN：三问

1. **我最终要保留哪张表的全部行？**
   - 不需要保留任何一边的"孤儿行" → INNER；
   - 要保留某张表的全部行 → 它放左边，用 LEFT JOIN。
2. **右表的条件是在描述"怎么配对"，还是在描述"最终结果必须满足"？**
   - 配对 → `ON`；
   - 最终结果 → `WHERE`。
3. **这条链路上有没有 INNER JOIN 混进来？**
   - 有，就要检查它会不会把前面的 NULL 行干掉。

### 11.3 别名检查清单

多表 SQL 写完，过一遍：

- [ ] 每张表都有短别名了吗？
- [ ] 每个列都带了表别名吗？（`u.id` 而不是 `id`）
- [ ] 同名列起不同的列别名了吗？（`role_id` / `perm_id`）
- [ ] `WHERE` 里没有用列别名吧？
- [ ] MyBatis `resultMap` 的 `column` 和 SELECT 别名一致吗？
- [ ] 自连接的两张"同一张表"起了不同别名吗？

### 11.4 读多表 SQL 的 5 步

```text
1. FROM 主表是谁？
2. 每个 JOIN 的 ON 把谁接到谁？
3. 画出链路，检查有没有断点/少 ON。
4. WHERE / ON 各过滤什么？
5. SELECT 输出什么、要不要 DISTINCT / GROUP BY / resultMap？
```

### 11.5 执行顺序

```text
FROM / JOIN → ON → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT
```

---

## 12. 思考题

先自己回答，再对照下面的参考答案。

1. 为什么 `SELECT id FROM sys_user JOIN sys_user_role ...` 会报错，而 `SELECT u.id FROM ...` 不会？
2. INNER JOIN 把表的书写顺序换一下，结果会变吗？LEFT JOIN 换一下呢？
3. 同样是查"用户的权限"，为什么项目里第一条 SQL 要 JOIN `sys_role`，而按角色查权限的那条不用？
4. LEFT JOIN 后面写 `WHERE p.module = 'product'` 和写在 `ON` 里，分别是什么语义？
5. 一个用户有 2 个角色，两个角色都有 `product:read`，`SELECT p.code` 会得到几行？加 `DISTINCT` 呢？
6. 为什么 `resultMap` 里要给角色和权限的 id 分别起 `role_id`、`perm_id` 别名？
7. `COUNT(*)` 和 `COUNT(r.id)` 在 LEFT JOIN 下有什么区别？统计"每个用户有几个角色"该用哪个？
8. 第 02 章给 SUPER_ADMIN 分配权限的 SQL 为什么没有 `ON`？如果漏了 `WHERE r.code = 'SUPER_ADMIN'` 会发生什么？
9. 如果一条 SQL 里 `JOIN` 了 5 张表却查出天文数字，第一步应该检查什么？
10. 什么时候你会主动选择 LEFT JOIN 而不是 INNER JOIN？举一个 RBAC 里的例子。

### 参考答案（先自己想）

1. 两张表都有 `id` 列，不加表别名无法消歧；`u.id` 明确指定了 `sys_user.id`。
2. INNER JOIN 不变（只要连接关系不变）；LEFT JOIN 会变，因为"谁在左边谁被保留"。
3. 第一条要过滤"角色是否启用/删除"，所以必须 JOIN `sys_role`；按角色查权限时已经知道角色 id，不需要角色表的状态过滤。
4. 放 `ON`：所有用户保留，匹配不到 `ORDER_ADMIN` 的用户该列补 NULL；放 `WHERE`：最终结果必须满足，没这个角色的用户整行被过滤掉（退化成 INNER JOIN）。注意：右表是一对多时，`ON` 过滤仍可能留下多条 NULL 行，要靠 `DISTINCT`/`GROUP BY` 收敛。
5. 不去重时 `product:read` 出现 2 次（每个角色一行）；加 `DISTINCT` 后 1 次。总行数还要加上其他权限。
6. 因为 `sys_role.id` 和 `sys_permission.id` 列名相同，MyBatis 按列名映射时后一个会覆盖前一个；起不同别名才能让 `resultMap` 分别映射到角色的 id 和权限的 id。
7. `COUNT(*)` 把补 NULL 的行也算进去；`COUNT(r.id)` 忽略 NULL。统计角色数用 `COUNT(r.id)`，这样没角色的用户得到 0 而不是 1。
8. 那是有意构造的笛卡尔积：3 个角色 × 11 个权限，再用 WHERE 筛出 SUPER_ADMIN 的 11 条。漏掉 WHERE 会把 33 条关系全插进去，三个角色都会拥有全部权限。
9. 先检查 JOIN 是否漏了 `ON`，或者连接条件写错导致某张表没有被限制（笛卡尔积）。用 `COUNT(*)` 和单表行数相乘来验证。
10. 例如"角色列表 + 权限"：新建角色还没有配权限，但必须在列表里出现，否则管理员无法给它配权限；再如"所有权限 + 当前角色是否已分配"的勾选页面。这类"主体必须完整、关联可有可无"的场景就该用 LEFT JOIN。

---

## 13. 复盘模板

```markdown
## 我搞懂了什么
- JOIN 先笛卡尔积，再按 ON 筛选
- INNER / LEFT / RIGHT 的保留规则
- 表别名 vs 列别名，以及各自的生效范围
- 一对多会让行数膨胀，DISTINCT / resultMap 在处理它

## 我跑过的实验
- 笛卡尔积：3 × 3 = 9
- INNER vs LEFT：制造无角色用户
- ON vs WHERE：角色过滤
- RIGHT JOIN 改写
- 重复权限 + DISTINCT
- 同名列 / 列别名在 WHERE 报错
- COUNT(*) vs COUNT(r.id)
- EXPLAIN 看索引

## 我还容易犯的错
- （例如）多表查询还在 SELECT *
- （例如）LEFT JOIN 后把右表条件写进 WHERE
- （例如）忘了手写 SQL 不会自动加 deleted = 0

## 我想继续学
- 子查询 / EXISTS / IN 与 JOIN 的取舍
- 索引与执行计划
- 分页 + 一对多的正确做法
```

---

## 14. 一页总结

```text
JOIN 的本质：笛卡尔积 + ON 配对 + WHERE 过滤

INNER JOIN：两边都有才留
LEFT JOIN ：左表全留，右表没有补 NULL
RIGHT JOIN：右表全留（改写成 LEFT JOIN 更好读）

表别名：多表必起，同名列必起，自连接必起
列别名：结果重名要起，resultMap 映射要起，WHERE 里不能用

一对多：行数会膨胀
  去重集合  → DISTINCT
  一行展示  → GROUP_CONCAT
  嵌套对象  → resultMap + collection
  计数      → COUNT(右表主键)，不是 COUNT(*)

项目四条 SQL：
  用户→权限码：p → rp → ur → r
  用户→角色码：r → ur
  角色→权限码：p → rp
  用户→角色+权限：r → ur → rp → p
```

到这里，再回去看 `sysPermissionMapper.xml`，应该每条 SQL 都能一眼看出"从哪张表出发、经过谁、为什么连它"。

下一篇：[02-RBAC权限与SpringSecurity重写.md](02-RBAC权限与SpringSecurity重写.md)
