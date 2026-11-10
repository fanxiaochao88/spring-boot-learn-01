# 第 08 章 Docker 与部署

> 预计用时：8 小时
> 前面几章的环境如果能一键启动，会省掉大量"装环境"的时间。这一章把 Docker 变成你的日常工具，而不只是"听说过"。

---

## 8.1 学习目标

读完并动手后，你能：

1. 说清楚镜像、容器、仓库、数据卷、网络的关系。
2. 写出可用的 Spring Boot `Dockerfile`（多阶段构建）。
3. 用 Docker Compose 一键启动 MySQL、Redis、Nacos、Kafka、Sentinel。
4. 掌握 20 个高频 Docker / Linux 命令。
5. 用 Nginx 做反向代理。
6. 排查端口、时区、内存、网络等部署问题。

---

## 8.2 为什么用 Docker

### 8.2.1 没有 Docker 时

"在我电脑上能跑"的经典原因：

- JDK 版本不同（8 vs 17）；
- MySQL 版本不同（5.7 vs 8.0）；
- 环境变量、时区、字符集不同；
- 依赖的中间件没装；
- 配置文件路径不同。

### 8.2.2 Docker 解决什么

Docker 把**应用 + 依赖 + 运行环境**打包成一个镜像。任何装了 Docker 的机器，`docker run` 就能得到完全一致的环境。

| 概念 | 类比 | 说明 |
|---|---|---|
| **镜像 Image** | 安装光盘 | 只读模板，包含文件系统和启动命令 |
| **容器 Container** | 运行中的程序 | 镜像的运行实例，可启动/停止/删除 |
| **仓库 Registry** | 应用商店 | 存放镜像，如 Docker Hub、阿里云 ACR |
| **数据卷 Volume** | 外接硬盘 | 容器删除后数据仍在 |
| **网络 Network** | 局域网 | 容器之间通过服务名互相访问 |
| **Dockerfile** | 安装脚本 | 描述如何构建镜像 |

### 8.2.3 和虚拟机的区别

| 维度 | 虚拟机 | Docker |
|---|---|---|
| 隔离级别 | 硬件级，完整 OS | 进程级，共享宿主内核 |
| 启动速度 | 分钟级 | 秒级 |
| 资源占用 | 大（每个 VM 一个 OS） | 小 |
| 隔离性 | 强 | 较弱（内核共享） |
| 适用 | 强隔离、多 OS | 应用打包、微服务 |

---

## 8.3 常用命令

### 8.3.1 镜像

```bash
docker images                          # 列出本地镜像
docker pull mysql:8.0                  # 拉取
docker rmi mysql:8.0                   # 删除
docker build -t mall-product:1.0 .     # 构建（Dockerfile 在当前目录）
docker tag mall-product:1.0 registry.example.com/mall/product:1.0
docker push registry.example.com/mall/product:1.0
```

### 8.3.2 容器

```bash
docker ps                              # 运行中的容器
docker ps -a                           # 所有容器（包括已停止）
docker run -d --name app -p 8080:8080 mall-product:1.0
docker stop app                        # 停止
docker start app                       # 启动
docker restart app                     # 重启
docker rm -f app                       # 强制删除
docker logs -f --tail 100 app          # 实时查看日志
docker exec -it app sh                 # 进入容器
docker inspect app                     # 查看详细信息（IP、挂载、环境变量）
docker stats                           # 实时资源占用
```

### 8.3.3 Compose

```bash
docker compose up -d                   # 后台启动所有服务
docker compose ps                      # 查看状态
docker compose logs -f mysql           # 查看某个服务日志
docker compose restart nacos           # 重启某个服务
docker compose down                    # 停止并删除容器（保留数据卷）
docker compose down -v                 # 连同数据卷一起删除（谨慎）
docker compose pull                    # 拉取最新镜像
```

### 8.3.4 数据卷与网络

```bash
docker volume ls
docker volume inspect roadmap_mysql-data
docker network ls
docker network inspect roadmap_default
```

---

## 8.4 本教程的 Compose 环境

文件在 `docker/docker-compose.yml`，核心服务：

| 服务 | 端口 | 账号/密码 | 用途 |
|---|---|---|---|
| MySQL | 3306 | root / 123456 | 业务数据库 |
| Redis | 6379 | 无 | 缓存、分布式锁 |
| Nacos | 8848、9848 | nacos / nacos | 注册中心 + 配置中心 |
| Kafka | 29092（宿主机） | 无 | 消息队列 |
| Sentinel | 8858 | sentinel / sentinel | 流控控制台 |

### 8.4.1 启动

```bash
cd docker
docker compose up -d
docker compose ps
```

首次启动会：

1. 拉取镜像（几分钟）；
2. 初始化 MySQL，执行 `mysql/init.sql`；
3. 创建数据库 `roadmap_db` 和示例表。

### 8.4.2 验证

```bash
# MySQL
docker exec -it roadmap-mysql mysql -uroot -p123456 -e "use roadmap_db; show tables;"

# Redis
docker exec -it roadmap-redis redis-cli ping

# Nacos
curl http://localhost:8848/nacos/v1/console/health/readiness

# Kafka
docker exec -it roadmap-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

# Sentinel
curl http://localhost:8858
```

### 8.4.3 各服务的关键配置解释

**MySQL：**

```yaml
environment:
  MYSQL_ROOT_PASSWORD: "123456"
  MYSQL_DATABASE: "roadmap_db"
  TZ: Asia/Shanghai
command:
  - --character-set-server=utf8mb4
  - --collation-server=utf8mb4_unicode_ci
  - --default-time-zone=+08:00
volumes:
  - mysql-data:/var/lib/mysql
  - ./mysql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

- `init.sql` **只在数据目录为空时执行**。改了脚本要 `down -v` 重新初始化，**这会清空数据**。
- 时区必须设，否则 `create_time` 会比北京时间少 8 小时。

**Nacos：**

```yaml
ports:
  - "8848:8848"   # HTTP
  - "9848:9848"   # gRPC
environment:
  MODE: standalone
  NACOS_AUTH_ENABLE: "false"   # 仅本地学习
```

> 生产必须开启鉴权、配置数据库（MySQL 持久化）、集群部署。

**Kafka：**

```yaml
KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:29092
```

- 容器内服务用 `kafka:9092`；
- 宿主机上的 Spring Boot 用 `localhost:29092`；
- 配错会导致"能建立连接，但拿到的地址连不上"。

### 8.4.4 常见操作

```bash
# 只看某个服务日志
docker compose logs -f --tail 200 mysql

# 进入 MySQL 容器
docker exec -it roadmap-mysql bash

# 备份数据库
docker exec roadmap-mysql mysqldump -uroot -p123456 roadmap_db > backup.sql

# 恢复数据库
docker exec -i roadmap-mysql mysql -uroot -p123456 roadmap_db < backup.sql

# 重置所有数据（会删除全部数据！）
docker compose down -v
docker compose up -d
```

---

## 8.5 给 Spring Boot 写 Dockerfile

### 8.5.1 多阶段构建（推荐）

```dockerfile
# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 非 root 用户运行
RUN groupadd -r app && useradd -r -g app app
COPY --from=builder /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

**为什么多阶段：**

- 构建阶段有 Maven + JDK + 源码，体积大；
- 运行阶段只保留 JRE + jar，镜像小很多（500MB → 200MB）；
- 不把源码和构建工具带进生产镜像，更安全。

**JVM 参数建议：**

```text
-XX:MaxRAMPercentage=75.0     # 堆最多占容器内存 75%
-XX:InitialRAMPercentage=50.0
-XX:+UseG1GC
```

> 不要写死 `-Xmx512m`，容器内存调整后容易 OOM。用百分比感知容器限制。

### 8.5.2 单个 jar 的简单 Dockerfile

如果没有 Maven 环境，先在宿主机打包：

```dockerfile
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY target/mall-product-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
mvn clean package -DskipTests
docker build -t mall-product:1.0 .
docker run -d --name mall-product -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=dev \
  mall-product:1.0
```

### 8.5.3 `.dockerignore`

```text
target/
.idea/
*.iml
.git/
logs/
*.log
```

避免把无用文件打进构建上下文，加快构建。

### 8.5.4 容器内如何访问宿主机的中间件

- **Spring Boot 跑在宿主机**：用 `localhost:3306`、`localhost:8848`、`localhost:29092`。
- **Spring Boot 也跑在 Docker Compose 里**：用服务名 `mysql:3306`、`nacos:8848`、`kafka:9092`。

把应用也加入 compose：

```yaml
services:
  product-service:
    build: ../mall-product-service
    container_name: mall-product
    ports:
      - "8081:8081"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_CLOUD_NACOS_SERVER_ADDR: nacos:8848
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/roadmap_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
      SPRING_DATA_REDIS_HOST: redis
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
```

> 用环境变量覆盖配置，比在镜像里改 `application.yml` 更灵活。Spring Boot 的 relaxed binding 支持 `SPRING_DATASOURCE_URL` 映射到 `spring.datasource.url`。

---

## 8.6 Linux 常用命令

部署到服务器后，至少掌握这些：

### 8.6.1 文件与目录

```bash
pwd                     # 当前目录
ls -lah                 # 列出文件（含隐藏、大小、权限）
cd /opt/app             # 切换目录
mkdir -p /opt/app/logs  # 创建多级目录
cp -r src dst           # 复制目录
mv old new              # 移动/重命名
rm -rf dir              # 递归删除（危险，确认路径）
find /opt -name "*.log" -mtime +7   # 查找 7 天前的日志
du -sh /opt/app         # 目录大小
df -h                   # 磁盘使用
```

### 8.6.2 进程与端口

```bash
ps -ef | grep java                  # 查 Java 进程
top -p <pid>                        # 看进程资源
kill -15 <pid>                      # 优雅停止
kill -9 <pid>                       # 强制杀死（尽量不用）
netstat -tlnp | grep 8080           # 查端口占用（或 ss -tlnp）
lsof -i:8080                        # 查端口被谁占用
```

### 8.6.3 日志

```bash
tail -f app.log                     # 实时跟踪
tail -n 200 app.log                 # 最后 200 行
grep -n "ERROR" app.log             # 搜索错误
grep -A 5 -B 5 "Exception" app.log  # 上下文
less app.log                        # 分页查看，/ 搜索
```

### 8.6.4 权限

```bash
chmod +x start.sh                   # 加执行权限
chmod 755 file                      # rwxr-xr-x
chown -R app:app /opt/app           # 改所有者
```

### 8.6.5 网络

```bash
curl -v http://localhost:8080/actuator/health
curl -X POST -H "Content-Type: application/json" -d '{"name":"test"}' http://localhost:8080/api/products
ping host
telnet host 3306                    # 测端口（可能未安装）
```

---

## 8.7 Nginx 反向代理

### 8.7.1 最小配置

```nginx
server {
    listen 80;
    server_name mall.example.com;

    # 前端静态资源
    location / {
        root /opt/mall/web;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    # API 转发到网关
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 3s;
        proxy_read_timeout 10s;
    }

    # 上传大小
    client_max_body_size 20m;

    # gzip
    gzip on;
    gzip_types text/plain text/css application/json application/javascript;
}
```

### 8.7.2 HTTPS

```nginx
server {
    listen 443 ssl;
    server_name mall.example.com;

    ssl_certificate     /etc/nginx/ssl/mall.crt;
    ssl_certificate_key /etc/nginx/ssl/mall.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
    }
}

server {
    listen 80;
    server_name mall.example.com;
    return 301 https://$host$request_uri;
}
```

### 8.7.3 负载均衡

```nginx
upstream gateway {
    server 192.168.1.10:8080 weight=1;
    server 192.168.1.11:8080 weight=1;
    keepalive 32;
}

server {
    location /api/ {
        proxy_pass http://gateway;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
    }
}
```

> 微服务架构下，Nginx 通常只代理网关，不直接代理各个业务服务。业务路由交给 Gateway。

---

## 8.8 部署流程

```text
1. 本地/CI 打包
   mvn clean package -DskipTests

2. 构建镜像
   docker build -t registry.example.com/mall/product:1.0.0 .

3. 推送镜像
   docker push registry.example.com/mall/product:1.0.0

4. 服务器拉取
   docker pull registry.example.com/mall/product:1.0.0

5. 启动/滚动更新
   docker compose up -d product-service

6. 验证
   curl http://localhost:8081/actuator/health
   docker logs -f --tail 100 mall-product

7. 观察
   看日志、监控、错误率；异常则回滚到上一个 tag
```

### 8.8.1 优雅发布

- 先启动新实例，健康检查通过后再摘掉旧实例（滚动发布）；
- 使用 `server.shutdown=graceful`；
- 数据库变更要**向后兼容**：先加字段，再发代码，最后删旧字段；
- 保留上一个版本的镜像，随时回滚。

### 8.8.2 生产 checklist

- [ ] 镜像 tag 不用 `latest`，用版本号或 git commit
- [ ] 时区 `Asia/Shanghai`
- [ ] JVM 内存用百分比，不写死
- [ ] 非 root 用户运行
- [ ] 日志输出到 stdout，由 Docker/日志系统收集
- [ ] 健康检查 `/actuator/health`
- [ ] 敏感配置用环境变量或配置中心，不打进镜像
- [ ] 数据卷挂载，容器删除数据不丢
- [ ] 资源限制 `--memory`、`--cpus`
- [ ] 重启策略 `restart: unless-stopped`

---

## 8.9 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| 容器启动就退出 | 启动命令错误、端口冲突、配置缺失 | `docker logs` 看日志 |
| 宿主机连不上容器服务 | 端口没映射 / 只监听 127.0.0.1 | `-p` 映射，应用监听 `0.0.0.0` |
| MySQL 时间少 8 小时 | 容器时区 UTC | 设 `TZ=Asia/Shanghai`，JDBC 加 `serverTimezone` |
| Nacos 控制台能开但注册不上 | 没映射 9848 | 暴露 9848 |
| Kafka 宿主机连不上 | `advertised.listeners` 是容器地址 | 加 EXTERNAL 监听 |
| 容器内访问 `localhost:3306` 失败 | `localhost` 指容器自己 | 用服务名 `mysql:3306` |
| 镜像巨大 | 用了完整 JDK、没多阶段 | 多阶段 + JRE + `.dockerignore` |
| 容器 OOM | JVM 没感知容器内存 | `-XX:MaxRAMPercentage` |
| 数据丢失 | 没挂载 volume | 挂载数据卷 |
| `down -v` 后数据没了 | `-v` 删除数据卷 | 确认后再执行 |
| 中文乱码 | 字符集不是 utf8mb4 | MySQL 启动参数 + JDBC 参数 |
| 构建慢 | 每次全量下载依赖 | 先 COPY pom.xml 再 `dependency:go-offline`，利用层缓存 |

---

## 8.10 动手实验

### 实验 1：一键启动环境

```bash
cd docker
docker compose up -d
docker compose ps
```

逐个验证 8.4.2 的命令，记录每个服务的状态。

### 实验 2：给商品服务写 Dockerfile

1. 在 `mall-product-service` 下创建 `Dockerfile`；
2. 构建镜像 `docker build -t mall-product:1.0 .`；
3. 运行：

```bash
docker run -d --name mall-product -p 8081:8081 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3306/roadmap_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
  -e SPRING_CLOUD_NACOS_SERVER_ADDR=host.docker.internal:8848 \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:29092 \
  mall-product:1.0
```

> Windows/Mac 的 Docker Desktop 支持 `host.docker.internal` 访问宿主机。Linux 需要加 `--add-host=host.docker.internal:host-gateway`。

4. 访问 `http://localhost:8081/actuator/health`。

### 实验 3：加入 Compose 网络

1. 把商品服务写进 `docker-compose.yml`；
2. 配置里用 `mysql:3306`、`nacos:8848`、`kafka:9092`；
3. `docker compose up -d`，验证服务间互通。

### 实验 4：Nginx 反向代理

1. 用 Docker 启动 Nginx：

```bash
docker run -d --name roadmap-nginx -p 80:80 \
  -v $(pwd)/nginx.conf:/etc/nginx/conf.d/default.conf:ro \
  nginx:1.25
```

2. 配置 `location /api/ { proxy_pass http://host.docker.internal:8080; }`；
3. 访问 `http://localhost/api/products/1`，验证经过网关。

### 实验 5：资源限制与 OOM 观察

```bash
docker run -d --name mall-product-limit --memory=256m -p 8082:8081 mall-product:1.0
docker stats
```

观察 JVM 是否因容器内存限制而调整堆大小。

---

## 8.11 练习题

1. 写一个多阶段 `Dockerfile`，把商品服务镜像控制在 250MB 以内。
2. 用 `docker compose` 同时启动应用和所有中间件，一条命令跑通。
3. 配置 Nginx，把 `/api` 转发到网关，`/` 指向静态页面。
4. 模拟 MySQL 容器被删除，验证数据卷挂载后数据仍在。
5. 给容器加 `--memory=300m`，观察应用是否 OOM，如何调整 JVM 参数。
6. **思考题**：为什么生产环境不建议用 `latest` tag？
7. **思考题**：应用容器里应该把日志写文件还是输出到控制台？为什么？

---

## 8.12 本章小结

1. **Docker 解决"环境一致性"**，镜像是模板，容器是实例，数据卷保数据，网络让容器互相访问。
2. **多阶段构建**能显著减小镜像体积；运行阶段用 JRE，不用 JDK。
3. **Docker Compose 是本地开发的最佳搭档**，一条命令拉起全套中间件。
4. **容器间用服务名通信**，宿主机用映射端口；Kafka 的 `advertised.listeners` 是高频坑。
5. **部署要关注时区、内存、健康检查、日志、数据持久化和回滚**。

---

下一章：[第 09 章 可观测性](09-可观测性.md)
