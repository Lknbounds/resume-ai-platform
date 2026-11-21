# 附A：错误码与接口总表

> 写 Controller / 前端拦截器时查这里。**错误码只追加，不改号**（前端按数字判断）。

---

## A.1 错误码

| code | HTTP | 常量 | 提示 | 什么时候返回 |
|---|---|---|---|---|
| 0 | 200 | `OK` | ok | 成功 |
| 10000 | 400 | `PARAM_INVALID` | 参数校验失败 | `@Valid` 失败；业务参数不合理 |
| 10001 | 401 | `UNAUTHORIZED` | 请先登录 | 无 token / token 无效 |
| 10002 | 403 | `FORBIDDEN` | 无权操作该资源 | 越权 |
| 10003 | 404 | `NOT_FOUND` | 资源不存在 | 路径不存在 |
| 10004 | 429 | `TOO_MANY_REQUESTS` | 操作过于频繁 | 限流 / 分析队列满 |
| 10005 | 413 | `PAYLOAD_TOO_LARGE` | 提交内容过大 | 请求体超限 |
| 20001 | 409 | `USERNAME_EXISTS` | 用户名已被占用 | 注册 |
| 20002 | 401 | `BAD_CREDENTIALS` | 用户名或密码错误 | 登录失败（不区分原因） |
| 20003 | 401 | `TOKEN_INVALID` | 登录状态失效 | token 解析失败 / refresh 作废 |
| 20004 | 401 | `TOKEN_EXPIRED` | 登录已过期 | access 过期 |
| 20005 | 404 | `USER_NOT_FOUND` | 用户不存在 | |
| 30001 | 409 | `AI_DISABLED` | AI 功能已关闭 | 调用了仅 AI 支持的能力 |
| 30002 | 502 | `AI_CALL_FAILED` | AI 服务暂时不可用 | 模型调用失败且无法降级 |
| 30003 | 504 | `AI_TIMEOUT` | AI 响应超时 | 同上（超时） |
| 30004 | 502 | `AI_OUTPUT_INVALID` | AI 返回格式异常 | 校验两次后仍不合法 |
| 30005 | 502 | `AI_QUOTA_EXCEEDED` | AI 调用额度不足 | 429/402 |
| 40001 | 404 | `RESUME_NOT_FOUND` | 简历不存在 | 含"不属于我" |
| 40002 | 404 | `VERSION_NOT_FOUND` | 简历版本不存在 | |
| 40003 | 400 | `VERSION_MISMATCH` | 版本不属于该简历 | 对比接口 |
| 40004 | 400 | `RESUME_CONTENT_INVALID` | 简历内容过短（至少 50 字） | |
| 41001 | 404 | `JD_NOT_FOUND` | 岗位描述不存在 | |
| 41002 | 400 | `JD_CONTENT_INVALID` | JD 内容过短（至少 30 字） | |
| 42001 | 404 | `ANALYSIS_NOT_FOUND` | 分析报告不存在 | |
| 42002 | 409 | `ANALYSIS_RUNNING` | 该分析正在进行中 | 并发重复提交 |
| 42003 | 500 | `ANALYSIS_FAILED` | 分析失败，请重试 | 异步任务异常 |
| 42004 | 400 | `ANALYSIS_NOT_READY` | 分析尚未完成 | |
| **43001** | **400** | **`POST_CREATE_FAILED`** | **发布失败，请稍后重试** | **发布失败·非审核原因** |
| **43002** | **422** | **`POST_PRIVACY_REJECTED`** | **内容包含个人信息，请抹去后重新发布** | **个人信息审核不通过（专用）** |
| 43003 | 409 | `POST_DUPLICATED` | 该分析结果已发布过帖子 | |
| 43004 | 404 | `POST_NOT_VISIBLE` | 帖子不存在或已删除 | |
| 43005 | 400 | `COMMENT_DEPTH_EXCEEDED` | 仅支持两级回复 | |
| 43006 | 404 | `COMMENT_NOT_FOUND` | 评论不存在 | |
| 43007 | 400 | `SELF_VOTE_FORBIDDEN` | 不能给自己的内容投票 | |
| 43008 | 403 | `ADOPT_FORBIDDEN` | 只有帖子作者可以采纳建议 | 含"采纳自己的评论" |
| 43009 | 400 | `REPORT_NOT_OWNED` | 报告不属于当前用户 | |
| 44001 | 409 | `INTERVIEW_DISABLED` | 面试预演功能未开启 | AI 关闭 |
| 44002 | 502 | `INTERVIEW_GENERATION_FAILED` | 面试题生成失败 | |
| 50000 | 500 | `INTERNAL_ERROR` | 系统繁忙，请稍后再试 | 兜底 |

**为什么 43002 单独一个 422**：前端要据此弹"命中项 + 高亮定位"的对话框，而不是一句通用报错；422 语义上正是"格式合法但内容不可处理"。

---

## A.2 接口总表

`/api` 前缀。**公开** = 无需 token。

### 认证 / 用户

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/auth/register` | 公开 | 注册 |
| POST | `/auth/login` | 公开 | 登录 |
| POST | `/auth/refresh` | 公开 | 刷新（轮换） |
| POST | `/auth/logout` | 需要 | 登出（黑名单 + 删 refresh） |
| GET | `/users/me` | 需要 | 当前用户 |
| PUT | `/users/me` | 需要 | 改资料 |
| PUT | `/users/me/password` | 需要 | 改密码（踢出所有设备） |
| GET | `/users/me/posts` | 需要 | 我的帖子 |
| GET | `/meta/features` | 公开 | 功能开关 |

### 简历

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/resumes` | 需要 | 新建（含 v1） |
| GET | `/resumes` | 需要 | 列表（分页） |
| GET | `/resumes/{id}` | 需要 | 详情 + 版本列表 |
| PUT | `/resumes/{id}` | 需要 | 改标题 |
| DELETE | `/resumes/{id}` | 需要 | 删除（级联版本/报告） |
| POST | `/resumes/{id}/versions` | 需要 | 新增版本 |
| GET | `/resumes/{id}/versions` | 需要 | 版本列表 |
| GET | `/resumes/versions/{versionId}` | 需要 | 版本详情（全文） |
| GET | `/resumes/{id}/compare` | 需要 | 版本对比 |

### JD

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/jds` | 需要 | 新建（可 `parseNow`） |
| GET | `/jds` | 需要 | 列表 |
| GET | `/jds/{id}` | 需要 | 详情（含解析） |
| PUT | `/jds/{id}` | 需要 | 更新（内容变则清解析） |
| DELETE | `/jds/{id}` | 需要 | 删除 |
| POST | `/jds/{id}/parse` | 需要 | 手动解析（`?force=`） |

### 分析

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/analyses` | 需要 | 发起（异步，202） |
| GET | `/analyses` | 需要 | 历史 |
| GET | `/analyses/{id}` | 需要 | 报告详情 |
| GET | `/analyses/{id}/stream` | 需要 | **SSE** 进度 |
| DELETE | `/analyses/{id}` | 需要 | 删除 |
| POST | `/analyses/{id}/optimize` | 需要 | 生成改写条目（AI） |
| GET | `/analyses/{id}/optimize` | 需要 | 查询改写条目 |
| POST | `/analyses/{id}/optimize/apply` | 需要 | 采纳 → 生成新版本 |

### 隐私 / 社区

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/privacy/precheck` | 需要 | 发布前预检（**不落库**，200 + `passed`） |
| POST | `/posts` | 需要 | 发布（**强制审核**） |
| GET | `/posts` | 公开 | 列表（筛选/排序/分页） |
| GET | `/posts/{id}` | 公开 | 详情（含快照） |
| DELETE | `/posts/{id}` | 需要 | 软删除（作者） |
| POST | `/posts/{id}/votes` | 需要 | 赞/踩 |
| GET | `/posts/{id}/comments` | 公开 | 两级评论树 |
| POST | `/posts/{id}/comments` | 需要 | 发表（审核） |
| DELETE | `/comments/{id}` | 需要 | 删除 |
| POST | `/comments/{id}/votes` | 需要 | 点赞/有用 |
| POST | `/comments/{id}/adopt` | 需要 | 采纳（可生成版本） |

### 面试题

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/interview-sets` | 需要 | 生成（需 AI） |
| GET | `/interview-sets/{id}` | 需要 | 批次 + 题目 |
| GET | `/interview-sets` | 需要 | 历史（按版本） |
| DELETE | `/interview-sets/{id}` | 需要 | 删除 |

---

## A.3 统一响应格式

```json
{ "code": 0, "message": "ok", "data": {}, "traceId": "0f3c9a1e8b7d4f52", "timestamp": 1763500000000 }
```

分页：

```json
{ "records": [], "total": 42, "page": 1, "size": 10, "pages": 5 }
```

---

## A.4 关键契约速查

| 概念 | 取值 |
|---|---|
| 报告状态 | `PENDING` `RUNNING` `SUCCESS` `PARTIAL` `FAILED` |
| 等级 | `EXCELLENT`(≥85) `GOOD`(≥70) `FAIR`(≥55) `WEAK`(<55) |
| 板块 | `SUMMARY` `SKILLS` `EXPERIENCE` `PROJECT` `EDUCATION` `KEYWORDS` `OTHER` |
| 版本来源 | `PASTE` `EDIT` `ADOPT_COMMENT` |
| 匹配方式 | `EXACT` `ALIAS` `SEMANTIC` `SEMANTIC_PARTIAL` `LEXICAL` `MISSING` |
| 投票类型 | 帖子：`UP` `DOWN`；评论：`UP` `USEFUL` |
| 敏感类型 | `PHONE` `ID_CARD` `EMAIL` `ADDRESS` `BANK_CARD` `SOCIAL_ACCOUNT` |
| 题目类别 | `TECHNICAL` `PROJECT` `BEHAVIORAL` `SCENARIO` |
