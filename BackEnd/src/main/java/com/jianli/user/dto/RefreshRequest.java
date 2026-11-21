package com.jianli.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  刷新 token 接口的请求体。只有一个字段：refreshToken。
 *
 *      POST /api/auth/refresh
 *      {"refreshToken":"eyJhbGciOiJIUzI1NiJ9.eyJ0eXBlIjoicmVmcmVzaCJ9.xxx"}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么 refreshToken 放在请求体里，不放 URL 参数或请求头？】
 *
 *  三种做法对比：
 *
 *  ┌──────────────┬──────────────────────────────────────────────┐
 *  │ 放 URL 参数  │ GET /refresh?token=xxx                       │
 *  │              │ ❌ token 会进 Nginx access log、浏览器历史、  │
 *  │              │    Referer 头 —— 等于泄露                    │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ 放请求头     │ 需要自定义一个 header，前端多写代码；          │
 *  │              │ 而且标准做法里 refresh 一般走 body           │
 *  ├──────────────┼──────────────────────────────────────────────┤
 *  │ 放请求体     │ ✅ POST body 不进日志、不进历史，最安全       │
 *  └──────────────┴──────────────────────────────────────────────┘
 *
 *  ★ 同理，access token 必须放 Authorization 头（这个无法避免），
 *    所以生产环境的 Nginx 日志【不能记录请求头】—— 这也是为什么
 *    SSE 我们不用原生 EventSource（它只能把 token 放 URL）。
 *
 *  【★ 为什么这个接口是 POST 而不是 GET？】
 *  因为 GET 的请求体是没意义的（会被某些代理丢掉），
 *  而且 GET 语义上是"读取资源"，而刷新 token 会【改变服务端状态】
 *  （旧的 refresh 作废、新的写入 Redis），属于"创建"操作，应该是 POST。
 *
 *  【★ 这个类为什么单独存在，不复用 TokenResponse？】
 *  因为 TokenResponse 里有两个 token，而这里只需要 refresh 一个。
 *  混用的话前端不知道该传哪个，也容易把 access 误传进来。
 *  职责单一，各管各的。
 */
public record RefreshRequest(

        /**
         * 刷新令牌。
         *
         * ★ 为什么要 @NotBlank？
         *   没传的话，AuthService.refresh(null) 里 tokenProvider.parse(null)
         *   会抛 IllegalArgumentException，然后被包成 TOKEN_INVALID 返回。
         *   结果是对的，但错误发生得晚。
         *   在参数校验阶段就挡住，错误信息更清楚，也少一次异常构造。
         *
         * ★ 这里不做格式校验（比如"必须是三段点分隔"）。
         *   因为非法格式在 parse 时自然会失败，没必要重复校验。
         *   而且格式校验如果写得和 JWT 规范不完全一致，反而会误杀合法 token。
         */
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken
) {}
