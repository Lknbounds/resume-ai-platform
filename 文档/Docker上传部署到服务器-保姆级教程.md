# Docker 上传并部署到服务器：保姆级教程

> 适用项目：`E:\简历评价项目`  
> 本地环境：Windows  
> 服务器建议：Ubuntu 22.04 / 24.04，2 核 4GB 以上  
> 部署结果：浏览器访问 `http://服务器IP`，前端、后端、MySQL、Redis 全部在 Docker 中运行。

---

## 一、先理解部署架构

```text
用户浏览器
    │
    ▼
Front 容器（Nginx，80 端口）
    │ /api 请求
    ▼
BackEnd 容器（Spring Boot，8080 端口）
    ├── MySQL 容器
    └── Redis 容器
```

推荐使用 **Docker Compose**：

- 不用在服务器手动安装 JDK、Maven、Node、MySQL、Redis；
- 一条命令启动全部服务；
- 以后更新代码只需拉取代码并重新构建；
- MySQL 数据保存在 Docker Volume 中，容器删除也不会丢。

---

## 二、部署前准备

### 2.1 本地准备

确认本地项目已经能运行：

- MySQL 能启动；
- Redis 能启动；
- 后端 `http://localhost:8080` 能启动；
- 前端 `http://localhost:5173` 能打开；
- 登录、简历上传、分析等主要功能已经跑通。

安装：

- Git
- Docker Desktop（只用于本地测试时需要）

### 2.2 服务器准备

准备一台 Linux 服务器，并记录：

```text
服务器公网 IP：________________
SSH 用户名：________________
SSH 端口：默认 22
```

推荐安全组/防火墙只开放：

```text
22    SSH
80    HTTP
443   HTTPS（配置域名后）
```

**不要开放：**

```text
3306  MySQL
6379  Redis
8080  Spring Boot
```

这些端口只在 Docker 内部网络中通信。

---

## 三、给项目补齐 Docker 文件

当前项目已有：

```text
Front/Dockerfile
Front/nginx.conf
```

还需要补齐：

```text
BackEnd/Dockerfile
BackEnd/.dockerignore
docker-compose.yml
.env.example
```

---

## 四、后端 Dockerfile

创建文件：

```text
BackEnd/Dockerfile
```

内容：

```dockerfile
# ---------- 第一阶段：编译 Java 项目 ----------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

# 先复制依赖文件，利用 Docker 缓存
COPY pom.xml ./

# 预下载依赖。第一次构建慢，以后改代码时会快很多。
RUN mvn -B -q dependency:go-offline || true

# 再复制源代码
COPY src ./src

# 构建 jar，跳过测试
RUN mvn -B -q clean package -DskipTests

# ---------- 第二阶段：只保留运行环境 ----------
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# wget 用于 Docker 健康检查
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/*.jar /app/app.jar

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

创建文件：

```text
BackEnd/.dockerignore
```

内容：

```text
target/
.idea/
*.iml
*.log
src/test/
.mvn/wrapper/maven-wrapper.jar
```

---

## 五、Docker Compose 文件

在项目根目录创建：

```text
docker-compose.yml
```

内容：

```yaml
services:

  mysql:
    image: mysql:8.0
    container_name: jianli-mysql
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${DB_NAME:-resume_ai}
      MYSQL_USER: ${DB_USER:-resume_user}
      MYSQL_PASSWORD: ${DB_PASSWORD}
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
      - --default-time-zone=+08:00
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h localhost -uroot -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 10s
      timeout: 5s
      retries: 12
    networks:
      - jianli-network

  redis:
    image: redis:7-alpine
    container_name: jianli-redis
    restart: unless-stopped
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 12
    networks:
      - jianli-network

  backend:
    build:
      context: ./BackEnd
      dockerfile: Dockerfile
    image: jianli-backend:latest
    container_name: jianli-backend
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      DB_HOST: mysql
      DB_PORT: 3306
      DB_NAME: ${DB_NAME:-resume_ai}
      DB_USER: ${DB_USER:-resume_user}
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      JWT_SECRET: ${JWT_SECRET}
      CORS_ORIGINS: ${CORS_ORIGINS}
      AI_ENABLED: ${AI_ENABLED:-false}
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:-}
      RESUME_STORAGE_DIR: /app/data/uploads/resumes
    volumes:
      - resume-files:/app/data/uploads/resumes
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q UP"]
      interval: 15s
      timeout: 5s
      retries: 20
      start_period: 60s
    networks:
      - jianli-network

  frontend:
    build:
      context: ./Front
      dockerfile: Dockerfile
    image: jianli-frontend:latest
    container_name: jianli-frontend
    restart: unless-stopped
    ports:
      - "80:80"
    depends_on:
      backend:
        condition: service_healthy
    networks:
      - jianli-network

volumes:
  mysql-data:
  redis-data:
  resume-files:

networks:
  jianli-network:
    driver: bridge
```

---

## 六、配置环境变量

在项目根目录创建：

```text
.env.example
```

内容：

```env
# MySQL
MYSQL_ROOT_PASSWORD=请替换成强密码
DB_NAME=resume_ai
DB_USER=resume_user
DB_PASSWORD=请替换成强密码

# JWT
JWT_SECRET=请替换成至少32字节的随机字符串

# 前端允许访问后端的地址
CORS_ORIGINS=http://你的服务器IP

# AI 暂时关闭
AI_ENABLED=false
DASHSCOPE_API_KEY=
```

生成随机密钥：

### Windows PowerShell

```powershell
-join ((48..57) + (65..90) + (97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
```

### Linux / macOS

```bash
openssl rand -base64 48
```

把生成的字符串填入 `JWT_SECRET`。

服务器上复制配置：

```bash
cp .env.example .env
nano .env
chmod 600 .env
```

> `.env` 不能上传到 GitHub。确认 `.gitignore` 里包含 `.env`。

---

## 七、方式一：通过 Git 上传代码并部署（推荐）

这是最推荐的方式。以后更新代码最方便。

### 7.1 本地上传到 GitHub / Gitee

如果项目还不是 Git 仓库：

```powershell
cd E:\简历评价项目
git init
git add .
git commit -m "first docker deployment"
```

在 GitHub 或 Gitee 创建一个**私有仓库**，然后：

```powershell
git remote add origin 你的仓库地址
git branch -M main
git push -u origin main
```

### 7.2 服务器安装 Docker

SSH 登录服务器：

```bash
ssh root@你的服务器IP
```

安装 Docker：

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
docker --version
docker compose version
```

如果 `docker compose` 不存在，安装 Docker Compose v2 插件。

### 7.3 下载项目

```bash
mkdir -p /opt
cd /opt
git clone 你的仓库地址 jianli
cd jianli
```

### 7.4 创建配置文件

```bash
cp .env.example .env
nano .env
chmod 600 .env
```

必须修改：

```text
MYSQL_ROOT_PASSWORD
DB_PASSWORD
JWT_SECRET
CORS_ORIGINS
```

如果使用 IP 访问：

```env
CORS_ORIGINS=http://你的服务器IP
```

如果已经配置域名：

```env
CORS_ORIGINS=https://你的域名
```

### 7.5 构建并启动

```bash
docker compose up -d --build
```

第一次构建可能需要 5-15 分钟。

### 7.6 查看运行状态

```bash
docker compose ps
```

期望看到：

```text
jianli-mysql      healthy
jianli-redis      healthy
jianli-backend    healthy
jianli-frontend   running
```

查看后端日志：

```bash
docker compose logs -f backend
```

看到类似内容表示启动成功：

```text
Started ResumeAiApplication in ...
```

### 7.7 浏览器测试

访问：

```text
http://你的服务器IP
```

测试：

1. 注册账号；
2. 登录；
3. 新建简历；
4. 上传 PDF 或 DOCX；
5. 创建 JD；
6. 发起分析；
7. 查看分析报告；
8. 打开面试预演题；
9. 发布社区帖子。

检查接口：

```bash
curl http://localhost/api/meta/features
```

检查健康状态：

```bash
curl http://localhost/actuator/health
```

---

## 八、方式二：本地构建 Docker 镜像后上传

如果你不想在服务器上编译代码，可以在本地构建镜像，然后上传镜像。

### 8.1 本地构建镜像

```powershell
cd E:\简历评价项目
docker compose build backend frontend
```

查看镜像：

```powershell
docker images
```

应该看到：

```text
jianli-backend
jianli-frontend
```

### 8.2 打包镜像

```powershell
docker save -o jianli-docker-images.tar jianli-backend:latest jianli-frontend:latest
```

### 8.3 上传到服务器

Windows PowerShell：

```powershell
scp .\jianli-docker-images.tar root@你的服务器IP:/opt/
```

Windows 也可以使用 WinSCP 上传。

### 8.4 服务器加载镜像

```bash
cd /opt
docker load -i jianli-docker-images.tar
docker images
```

### 8.5 服务器启动

把项目中的 `docker-compose.yml`、`.env.example`、`Front/nginx.conf` 等部署文件上传到服务器，然后：

```bash
cp .env.example .env
nano .env
docker compose up -d --no-build
```

> `--no-build` 表示直接使用已经上传的镜像。

---

## 九、以后如何更新代码

### 方式一：Git 更新

```bash
cd /opt/jianli
git pull

docker compose up -d --build backend frontend
```

只重新构建后端和前端，不需要重建 MySQL。

### 方式二：镜像更新

本地：

```powershell
docker compose build backend frontend
docker save -o jianli-docker-images.tar jianli-backend:latest jianli-frontend:latest
scp .\jianli-docker-images.tar root@你的服务器IP:/opt/
```

服务器：

```bash
cd /opt
docker load -i jianli-docker-images.tar
cd /opt/jianli
docker compose up -d --no-build backend frontend
```

---

## 十、常用运维命令

```bash
# 查看所有容器
docker compose ps

# 查看实时日志
docker compose logs -f backend

# 查看最近 200 行日志
docker compose logs --tail=200 backend

# 重启某个容器
docker compose restart backend

# 停止所有容器
docker compose down

# 重新构建并启动
docker compose up -d --build

# 进入后端容器
docker compose exec backend sh

# 进入 MySQL
docker compose exec mysql mysql -uroot -p
```

---

## 十一、备份数据库

每天或每周备份一次：

```bash
mkdir -p /opt/backup

docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  > /opt/backup/resume_ai-$(date +%F).sql
```

恢复数据库：

```bash
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < /opt/backup/resume_ai-2026-11-21.sql
```

查看备份：

```bash
ls -lh /opt/backup
```

> 可以在服务器配置 Cron 定时备份。

---

## 十二、服务器安全检查

上线前逐项确认：

| 项目 | 要求 |
|---|---|
| JWT_SECRET | 不是默认值，至少 32 字节随机字符串 |
| MySQL 密码 | 不是 `123456` |
| Redis | 不向公网暴露 6379 |
| MySQL | 不向公网暴露 3306 |
| 后端 | 不向公网暴露 8080 |
| .env | 权限是 600，不能提交到 Git |
| CORS_ORIGINS | 填真实域名或服务器 IP |
| AI_ENABLED | 暂时保持 false |
| 数据库 | 已配置定时备份 |
| Docker | `restart: unless-stopped` |

---

## 十三、常见问题

### 1. `port is already allocated`

80 端口被占用。

查看占用：

```bash
sudo lsof -i:80
```

停止占用 80 端口的服务，或者修改 `docker-compose.yml`：

```yaml
ports:
  - "8088:80"
```

然后访问：

```text
http://服务器IP:8088
```

### 2. 后端一直启动失败

查看日志：

```bash
docker compose logs backend
```

常见原因：

- 数据库密码错误；
- JWT_SECRET 为空；
- MySQL 尚未就绪；
- Flyway 迁移失败；
- 8080 端口被占用。

### 3. 前端可以打开，但接口 502

等待后端启动：

```bash
docker compose ps
docker compose logs -f backend
```

确认后端状态是 `healthy`。

### 4. 数据库中文乱码

确认 compose 中 MySQL 启动参数包含：

```text
--character-set-server=utf8mb4
--collation-server=utf8mb4_0900_ai_ci
```

### 5. 修改代码后没有生效

强制重建：

```bash
docker compose build --no-cache backend frontend
docker compose up -d backend frontend
```

### 6. 容器删除后数据还在吗

数据卷删除前一直存在：

```text
mysql-data
redis-data
resume-files
```

以下命令会删除数据卷，慎用：

```bash
docker compose down -v
```

---

## 十四、推荐部署流程总结

第一次：

```bash
git clone ...
cd jianli
cp .env.example .env
nano .env
docker compose up -d --build
docker compose ps
```

以后更新：

```bash
git pull
docker compose up -d --build backend frontend
docker compose ps
```

备份：

```bash
docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  > /opt/backup/resume_ai-$(date +%F).sql
```