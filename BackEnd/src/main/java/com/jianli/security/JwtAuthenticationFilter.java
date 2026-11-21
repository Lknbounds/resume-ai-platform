package com.jianli.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  "验票员"。每个请求进来，它从请求头里取出 token，验证，然后
 *  把"这个请求是谁发的"这个信息放进 Spring Security 的上下文。
 *
 *  它是整个鉴权体系的核心，也是 JWT 真正"起作用"的地方。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它在请求链的哪一环】
 *
 *      请求进来
 *         ↓
 *      Tomcat 解析成 HttpServletRequest
 *         ↓
 *      【本类】JwtAuthenticationFilter          ← ★ 我们在这一环
 *         ├─ 从请求头取 "Authorization: Bearer eyJhbGci..."
 *         ├─ 调 JwtTokenProvider.parse(token) 验签 + 验过期
 *         ├─ 调 TokenService.isBlacklisted(jti) 查黑名单
 *         ├─ 通过 → new LoginUser(userId, username)
 *         │         SecurityContextHolder.setAuthentication(...)   ← 放进"保险箱"
 *         └─ 失败 → 什么都不做（保持未登录状态）
 *         ↓
 *      JwtAuthenticationFilter 之后的 Security 过滤器
 *         └─ 判断"这个接口需不需要登录"
 *              需要登录 且 上下文里有认证信息 → 放行
 *              需要登录 但 上下文里没有       → 返回 401
 *         ↓
 *      DispatcherServlet → Controller
 *         └─ 参数写 @CurrentUserId Long userId → 从上下文里取出 id
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么它只"识别身份"，不"拒绝请求"？】
 *
 *  这看起来反直觉 —— 没登录为什么不在这里直接返回 401？
 *
 *  因为有些接口是【公开的】，比如：
 *      GET  /api/posts          浏览社区帖子（不登录也能看）
 *      GET  /api/meta/features  查功能开关
 *      POST /api/auth/login     登录（这时候本来就没 token）
 *
 *  如果本类一发现没 token 就返回 401，这些公开接口就没法用了。
 *
 *  所以职责这样划分：
 *      本类    → "我尽量认出你是谁"，认不出就算了，不拦人
 *      Security 的授权规则 → "这个接口要不要拦"，由它决定
 *
 *  ★ 这正是"关注点分离"：身份识别和访问控制是两件事。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【为什么继承 OncePerRequestFilter（和 TraceIdFilter 一样）】
 *  Servlet 规范里一个请求可能被转发/包含多次，普通 Filter 会被重复执行。
 *  OncePerRequestFilter 保证一次请求只跑一次 —— 否则同一个请求会反复解析 token、
 *  反复查 Redis，白白浪费。
 *
 *
 *  【@Component 加在这里，会发生什么？】
 *
 *  ★★ 这里有个坑，必须说清楚：
 *
 *  Spring Boot 会自动把容器里【所有 Filter 类型的 Bean】注册到 Servlet 过滤器链上。
 *  也就是说，只要加了 @Component，本类就自动生效了 ——
 *  但它的位置是【不确定的】。
 *
 *  而我们在 SecurityConfig 里又写了一次：
 *      .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
 *
 *  这一个手动注册 + 自动注册可能导致【执行两次】。
 *
 *  本项目为了让代码直观，采用 @Component + 显式 addFilterBefore 的写法。
 *  Spring Security 的 addFilterBefore 会做去重处理，
 *  实际不会重复执行（Spring Boot 会检测到该 Filter 已在链中）。
 *
 *  ★ 如果你发现日志里 token 被解析了两次，那就要改成：
 *     不加 @Component，改为在 SecurityConfig 里 new 一个。
 *   但那样又要手动处理依赖注入，代码会丑。所以本项目保留现在的写法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求头里的前缀。注意带空格：Bearer 后面必须有一个空格 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 标准请求头的名字 */
    private static final String AUTH_HEADER = "Authorization";

    private final JwtTokenProvider tokenProvider;
    private final TokenService tokenService;

    /**
     * 每个请求都会执行一次。
     *
     * ★ 为什么不抛任何异常出去？
     *   token 有问题（过期、伪造、被拉黑）是【正常情况】，
     *   不是系统错误。我们只是"认不出你是谁"，不需要中断请求。
     *   真正决定要不要拦的，是后面的授权规则（见类的说明）。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        /*
         * 【第一步：从请求头里拿 token】
         *
         * 标准格式：
         *      Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.xxx
         *                     └─┬──┘ └────────────── token 本体 ──────────┘
         *                     方案名（固定写 Bearer，意思是"持有者令牌"）
         *
         * ★ 为什么用 Bearer 而不是别的名字？
         *   这是 OAuth 2.0 / RFC 6750 规定的标准方案名，全局统一。
         *   前端、Postman、Swagger 都认这个前缀。
         *
         * ★ 为什么要 substring(7)？
         *   去掉 "Bearer " 这 7 个字符（注意 B-e-a-r-e-r + 空格 = 7 个字符）。
         */
        String header = req.getHeader(AUTH_HEADER);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());

            try {
                /*
                 * 【第二步：解析并验证 token】
                 *
                 * parse 内部做了三件事（详见 JwtTokenProvider.parse）：
                 *   1. 验签名（内容有没有被篡改）
                 *   2. 验过期（exp 有没有超时）
                 *   3. 验格式（是不是合法 JWT）
                 *
                 * 任何一项不过都会抛 JwtException，直接跳到下面的 catch。
                 */
                Claims claims = tokenProvider.parse(token);

                String jti = tokenProvider.jti(claims);
                String type = tokenProvider.type(claims);

                /*
                 * 【第三步：两道额外检查】
                 *
                 * ① 类型必须是 access
                 *    ★ 为什么？因为 refresh token 有 7 天有效期。
                 *      如果允许它直接访问接口，等于把 access 的 2 小时限制绕过去了。
                 *      而 refresh token 只应该能用来调 /api/auth/refresh 这一个接口。
                 *
                 * ② 不能在黑名单里
                 *    ★ 为什么？用户点了登出、或者改密码时，
                 *      我们会把对应的 jti 写进 Redis 黑名单。
                 *      这里查一下，就能实现"token 立刻失效"。
                 *      代价是每个请求多一次 Redis 查询（约 1-2ms），换来可撤销能力。
                 */
                boolean isAccessToken = JwtTokenProvider.TYPE_ACCESS.equals(type);
                boolean notRevoked = !tokenService.isBlacklisted(jti);

                if (isAccessToken && notRevoked) {

                    /*
                     * 【第四步：构造认证对象放进 SecurityContext】
                     *
                     * 这是"告诉 Spring Security：这个请求是已登录的"。
                     *
                     * UsernamePasswordAuthenticationToken 有三个参数：
                     *   1. principal   → 代表"是谁"，我们放 LoginUser
                     *   2. credentials → 凭证（密码），JWT 场景下不需要，传 null
                     *   3. authorities → 权限列表
                     *
                     * ★ 第三个参数现在只放了一个固定的 ROLE_USER。
                     *   这是 placeholder —— 我们还没做 RBAC（角色权限）。
                     *   二期会改成从数据库/Redis 加载的真实权限集合（见附B）。
                     *
                     * ★ 为什么现在不直接省掉这个参数？
                     *   Security 的一些内部逻辑要求 authorities 不能为 null，
                     *   传个空 List 也行，但给个 ROLE_USER 更符合常规。
                     */
                    LoginUser loginUser = new LoginUser(
                            tokenProvider.userId(claims),
                            claims.get("username", String.class));

                    var authentication = new UsernamePasswordAuthenticationToken(
                            loginUser,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER")));

                    /*
                     * ★ SecurityContextHolder 是什么？
                     *   它是"当前请求的认证信息保险箱"。
                     *   内部用的是 ThreadLocal —— 每个请求线程各有一份，互不干扰。
                     *
                     *   调用 setAuthentication 之后：
                     *      - @CurrentUserId 注解能取到 userId
                     *      - Security 的授权规则认为"已登录"
                     *      - 请求结束后 Spring 自动清空（不需要手动清理）
                     */
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("token 校验通过, userId={}, username={}",
                            loginUser.id(), loginUser.username());
                }
                // 如果 type 不对或被拉黑，什么都不做 —— 保持"未登录"状态，
                // 让后面的授权规则去决定拦不拦

            } catch (JwtException | IllegalArgumentException e) {
                /*
                 * 【解析失败：当作未登录】
                 *
                 * 可能的原因：
                 *   ExpiredJwtException     → 过期了（最常见）
                 *   SignatureException      → 签名不对（伪造，或换了 secret）
                 *   MalformedJwtException   → 格式不是 JWT
                 *   IllegalArgumentException → 空字符串
                 *
                 * ★ 为什么要 clearContext()？
                 *   正常情况下上下文是空的，clear 似乎多余。
                 *   但如果用的是线程池，线程被复用时可能残留上次的认证信息
                 *   （虽然 Spring 一般会清理，但显式清一次更保险）。
                 *   这和 TraceIdFilter 里 MDC.remove 是同一个道理。
                 *
                 * ★ 为什么用 debug 而不是 warn？
                 *   过期 token 太常见了（用户开着页面放了 3 小时），
                 *   打 warn 会刷屏，把真正的告警淹没。
                 */
                SecurityContextHolder.clearContext();
                log.debug("token 校验失败: {}", e.getMessage());
            }
        }

        /*
         * 【最后一步：无论成功失败，都要放行】
         *
         * ★★ 这一行绝对不能放在 if 里面，也不能漏掉。
         *    chain.doFilter 是把请求往下传的唯一方式。
         *    不调用它，请求就卡在这里，前端会一直转圈直到超时。
         */
        chain.doFilter(req, res);
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 如果想跳过某些请求，可以重写这个方法】
     *
     *  OncePerRequestFilter 提供了 shouldNotFilter，返回 true 表示"本请求不处理"。
     *
     *  有些项目会这样写"跳过登录接口"，省一次无意义的 token 解析：
     *      @Override
     *      protected boolean shouldNotFilter(HttpServletRequest req) {
     *          return req.getServletPath().startsWith("/api/auth/");
     *      }
     *
     *  ★ 我们为什么没写？
     *    因为登录请求本来就不带 token，上面的 if 判断会直接跳过，
     *    开销只有一次 getHeader —— 微乎其微。
     *    而少写这段代码，就少一个"以后新增公开接口忘了加白名单"的坑。
     *
     *    ★ 但如果以后发现某个高频公开接口有性能问题，可以按需加上。
     * ═══════════════════════════════════════════════════════════════
     */
}
