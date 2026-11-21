# 简评 · AI 简历分析与社区互评平台

```
E:\简历评价项目\
├── BackEnd\              后端（Spring Boot 3.5 + JDK17，包名 com.jianli）
├── Front\                前端（Vue 3 + Vite + Element Plus，JavaScript）
├── 文档\                 技术文档 01~16 + 附录
├── tools\                项目自带工具（不进 Git）
│   ├── apache-maven-3.9.16\   Maven 离线备份（日常用 BackEnd\mvnw.cmd）
│   └── Redis-x64-5.0.14.1\    本地 Redis
├── start-backend.bat     启动后端（自动设 JAVA_HOME = JDK17）
├── start-front.bat       启动前端
├── start-redis.bat       启动本地 Redis
└── init-db.bat           重建数据库（会清空数据，慎用）
```

## 启动（三步，缺一不可）

```
1. MySQL   → 常驻服务，任务管理器看 MySQL80
2. Redis   → 双击 start-redis.bat  ★ 最容易忘，忘了后端起不来
3. 后端    → 双击 start-backend.bat → http://localhost:8080/swagger-ui/index.html
4. 前端    → 双击 start-front.bat  → http://localhost:5173
```

## 当前进度

| 模块 | 状态 | 说明 |
|---|---|---|
| 01 工程骨架 | ✅ | 统一响应、错误码、全局异常、traceId、Swagger |
| 02 数据库 | ✅ | 13 张表（Flyway V1-V3） |
| 03 注册登录 JWT | ✅ | BCrypt、token 轮换、Redis 黑白名单 |
| 04 前端登录 | ✅ | 路由守卫、Axios 拦截器、401 自动刷新 |
| 05 简历与版本 | ✅ | 多版本、内容不可变、按岗位归档 |
| 07 JD 管理 | ✅ | 规则解析：技能/年限/学历（不花钱、毫秒级） |
| 08-09 分析评分 | ✅ | 六维加权评分 + 关键词三态 + 改进建议 |
| **报告页 + PDF 导出** | ⬜ | 报告页已完成，PDF 待做 |
| 06 版本对比 | ⬜ | LCS 行级 diff |
| 11 SSE 进度 | ⬜ | 接 AI 后才需要 |
| 12-14 社区 | ⬜ | 个人信息审核、发帖、评论、采纳 |
| 15 面试预演题 | ⬜ | 需 AI |
| 16 Docker 部署 | ⬜ | |

**主链路已打通**：注册 → 建简历 → 粘 JD → 分析 → 看报告。

## 当前能演示的功能

| 操作 | 结果 |
|---|---|
| 注册/登录/退出 | JWT + 登出后 token 立即失效 |
| 新建简历（选目标岗位） | 生成 v1，列表显示「标题 + 岗位」 |
| 编辑简历 → 保存新版本 | v2/v3，内容没变会被拒绝 |
| 粘贴 JD | 自动抽出硬技能（带重要度）、加分项、年限、学历 |
| 发起分析 | 返回总分、六维分数、关键词命中/缺失、改进建议 |
| 简历列表 | 显示最新匹配分（带颜色分档） |

## 技术栈

| 层 | 版本 |
|---|---|
| JDK | 17（`D:\7.Environment\jdk-17.0.12`） |
| Spring Boot | 3.5.10 |
| MySQL | 8.0（root / 123456，库 `resume_ai`） |
| Redis | 5.0.14.1（本地解压版，端口 6379） |
| Vue / Vite / Element Plus | 3.5 / 5.4 / 2.14 |

**Maven 用 `BackEnd/mvnw.cmd`**，不要用系统的 3.6.1（版本太旧，编译不了）。

## 设计原则

1. **分数由程序算，AI 只做抽取** —— 可复现、可解释、可测试
2. **AI 可关闭** —— 关掉后规则引擎跑通全链路
3. **Redis 只存可重建数据** —— 主数据只在 MySQL
4. **发布前强制 PII 审核** —— 规则为主，AI 辅助可关

详见 `文档/README.md`。
