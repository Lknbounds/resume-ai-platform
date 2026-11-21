package com.jianli.user.dto;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  登录/刷新成功后返回给前端的东西。前端拿到后存起来，之后的请求都要用。
 *
 *  返回的 JSON：
 *      {
 *        "accessToken":  "eyJhbGciOiJIUzI1NiJ9.xxx",
 *        "refreshToken": "eyJhbGciOiJIUzI1NiJ9.yyy",
 *        "expiresIn":    7200,
 *        "user": { "id":1, "username":"alice", "nickname":"Alice", ... }
 *      }
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：四个字段各自被谁用】
 *
 *      AuthService.issueTokens(user)
 *         └─ new TokenResponse(access, refresh, expiresIn, UserVO.of(user))
 *              ↓
 *          Jackson 序列化成 JSON → 前端
 *              ↓
 *      前端 useUserStore：
 *         ├─ accessToken  → localStorage + 每个请求的 Authorization 头
 *         ├─ refreshToken → localStorage + 401 时调 /auth/refresh
 *         ├─ expiresIn    → 可选：提前定时刷新，避免第一个请求踩 401
 *         └─ user         → 头像、昵称、目标岗位等展示
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么返回两个 token，而不是一个？】
 *
 *  这是一个"短有效期 + 长有效期"的设计：
 *
 *      access  有效期 2 小时  → 每个请求都带，泄露风险窗口小
 *      refresh 有效期 7 天    → 只在换 token 时用，平时不出网
 *
 *  如果只有一个长 token：
 *      - 它每个请求都带，被截获的风险高
 *      - 一旦泄露，7 天内有效，危害大
 *
 *  如果有两个：
 *      - access 泄露 → 最多 2 小时，而且可以用登出拉黑
 *      - refresh 泄露 → 只能换一次（因为轮换），下次真用户刷新会失败 → 告警信号
 *
 *  ★ 这是 JWT 实践里的标准做法，面试常问。
 *
 *  【★ 为什么 user 字段用 UserVO 而不是 AppUser？】
 *  因为 AppUser 含 passwordHash。返回它等于把密码哈希送给前端 ——
 *  哪怕前端不显示，任何人在 F12 里都能看到。
 *  ★ 这条纪律叫"绝不把 Entity 直接暴露给外部"，是全项目最重要的一条。
 */
public record TokenResponse(

        /**
         * 访问令牌。
         *
         * ★ 前端怎么用：
         *      fetch('/api/resumes', {
         *        headers: { Authorization: `Bearer ${accessToken}` }
         *      })
         *
         * ★ 为什么每次请求都带？JWT 是无状态的，
         *   服务端不存会话，全靠这个 token 里的内容（+ 签名）来认人。
         */
        String accessToken,

        /**
         * 刷新令牌。
         *
         * ★ 前端什么时候用：
         *   收到 401 时（access 过期），调 POST /auth/refresh 换新的，
         *   然后用新的 access 重放刚才失败的请求。
         *   用户全程无感知（见 04 文档的 http.js 拦截器）。
         *
         * ★ 安全建议：refresh 只应该发给 /auth/refresh 这一个接口，
         *   绝不能跟着普通请求走。虽然服务端也会校验 type，
         *   但前端少发一次，就少一次被截获的机会。
         */
        String refreshToken,

        /**
         * access token 的有效期，单位【秒】。
         *
         * ★ 为什么返回这个数字，前端不是可以从 token 里解出来吗？
         *   可以（base64 解开 payload 就能看到 exp），但：
         *   1. 前端解析 token 需要额外代码和依赖
         *   2. 前端拿到 exp 还要和本地时间做比较，容易算错
         *   直接给一个"还有多少秒"最省事。
         *
         * ★ 前端可以用它做"提前刷新"：
         *       setTimeout(() => refresh(), (expiresIn - 60) * 1000)
         *   在过期前 60 秒主动刷新，避免用户在操作时突然 401。
         *   本项目没做这个优化（靠拦截器兜底），但字段留着。
         *
         * ★ 为什么是秒不是毫秒？
         *   这是 OAuth 2.0 规范的习惯（expires_in 字段就是秒）。
         *   前端用的时候记得乘 1000。
         */
        long expiresIn,

        /**
         * 当前登录用户的基本信息。
         *
         * ★ 为什么登录时就返回用户信息，而不是让前端再调一次 /users/me？
         *   省一次请求。登录成功后前端立刻要显示"欢迎，xxx"，
         *   再发一次请求会有明显延迟。
         *
         * ★ 为什么不包含 passwordHash？
         *   见类说明 —— UserVO 本来就是"能对外暴露的字段子集"。
         */
        UserVO user
) {}
