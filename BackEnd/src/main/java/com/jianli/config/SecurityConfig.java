package com.jianli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianli.common.ApiResponse;
import com.jianli.common.ErrorCode;
import com.jianli.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  安全总配置。决定三件事：
 *      1. 哪些接口需要登录、哪些公开
 *      2. 密码怎么加密
 *      3. JWT 过滤器插在哪一环、认证失败返回什么
 *
 *  ★ 这是步骤 3 对整个系统的"安全总开关"，
 *    也是把步骤 1 的临时全放行版本替换掉的地方。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一个请求经过这里的完整过程】
 *
 *      请求进入
 *         ↓
 *      TraceIdFilter（贴 traceId）
 *         ↓
 *      CorsFilter（处理跨域预检）
 *         ↓
 *      JwtAuthenticationFilter（★ 本类注册的）
 *         ├─ 有 token 且有效 → 设置认证信息
 *         └─ 没 token / 无效 → 保持未登录（不拦，继续往下）
 *         ↓
 *      FilterSecurityInterceptor / AuthorizationFilter
 *         └─ 按本类的 authorizeHttpRequests 规则判断：
 *              公开接口             → 直接放行
 *              需要登录 且 已认证   → 放行
 *              需要登录 但 未认证   → 调用 authenticationEntryPoint → 401
 *              已认证 但 权限不足   → 调用 accessDeniedHandler → 403
 *         ↓
 *      DispatcherServlet → Controller
 *         ↓
 *      如果抛异常 → GlobalExceptionHandler → 统一响应
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【@EnableWebSecurity 是什么】
 *  开启 Spring Security 的 Web 安全功能。
 *
 * ★ 严格说 Spring Boot 自动配置已经帮我们开了（引了 starter 就有）。
 *   显式写出来是为了"意图明确" —— 看到这个注解就知道这个项目用了 Security。
 *
 * ★ 二期做 RBAC 时，这里还要加 @EnableMethodSecurity，
 *   才能用 @PreAuthorize("hasAuthority('POST:HIDE')") 这种注解式权限控制。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * JWT 过滤器。由 Spring 注入（它自己也是个 @Component）。
     *
     * ★ 为什么这里要注入它、而不是在 filterChain 方法里 new 一个？
     *   因为它自己还有两个依赖（JwtTokenProvider、TokenService），
     *   手动 new 就得自己处理依赖，很麻烦。
     *   注入进来复用容器里那个就行了。
     */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Jackson 的对象映射器。用来把 ApiResponse 转成 JSON 字符串。
     *
     * ★ 为什么这里需要它？
     *   因为在"认证失败"的处理里，我们要手写响应体
     *   （那是 Filter 层，不是 Controller 层，用不上 HttpMessageConverter）。
     *   需要手动把对象序列化成 JSON 写进 response。
     *
     * ★ 为什么不自己 new 一个 ObjectMapper？
     *   那样会丢掉全局配置（比如 JacksonConfig 里配的时间格式）。
     *   用容器里那个，格式和正常响应一致。
     */
    private final ObjectMapper objectMapper;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【密码加密器】BCrypt
     * ═══════════════════════════════════════════════════════════════
     *
     * 定义成一个 @Bean 之后，AuthService 就可以注入它用：
     *      passwordEncoder.encode(明文)          → 哈希
     *      passwordEncoder.matches(明文, 哈希)   → true / false
     *
     * ★ 为什么用 BCrypt 而不是 MD5/SHA256？（面试必问）
     *
     *   MD5/SHA256 是"快哈希"：
     *       GPU 每秒能算几十亿次 → 8 位密码几小时就被爆破出来
     *
     *   BCrypt 是"慢哈希"：
     *       单次计算 50-100ms（可调），爆破成本高几个数量级
     *       内置随机 salt（所以不需要单独存盐字段）
     *       哈希串自带 cost 因子和 salt，验证时自己解析
     *
     * ★ 参数 10 是什么？
     *   cost factor（代价因子），表示 2^10 = 1024 轮计算。
     *      往上升一级，计算时间翻倍：
     *          10 → 约 50-100ms（本项目）
     *          12 → 约 200-400ms
     *          14 → 约 1 秒（太慢，登录体验差）
     *
     *   ★ 怎么选？
     *     目标是"单次验证 100ms 左右" —— 用户无感，攻击者很痛。
     *     硬件升级后可以调大（所以 cost 存在哈希串里，
     *     老用户的哈希用旧 cost 验，新注册的用新 cost 存，可平滑升级）。
     *
     * ★ 为什么不注入 PasswordEncoder 而是自己 new？
     *   因为这就是"定义 Bean 的地方" —— 由它来 new，其他地方注入使用。
     *   如果这里也注入，就循环依赖了。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【安全过滤器链配置】—— 本类最核心的方法
     * ═══════════════════════════════════════════════════════════════
     *
     * @param http Spring Security 提供的配置器
     * @return 构建好的过滤器链
     * @throws Exception 构建失败（配置写错时抛）
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http

                /*
                 * ═══════════════════════════════════════════════════
                 *  【1. 禁用 CSRF】
                 * ═══════════════════════════════════════════════════
                 *
                 * CSRF（跨站请求伪造）的攻击场景：
                 *     你登录了银行网站，浏览器存着 Cookie。
                 *     这时你点开一个恶意网页，那个网页偷偷向银行发转账请求 ——
                 *     浏览器会自动带上 Cookie，银行以为是本人操作。
                 *
                 * 防御方式是每个请求带一个攻击者猜不到的 token。
                 *
                 * ★ 我们为什么可以关掉？
                 *   我们的认证靠 Authorization: Bearer <token> 请求头，
                 *   【不用 Cookie】。
                 *   浏览器不会自动给任何网站带上自定义请求头 ——
                 *   它只会自动带 Cookie。
                 *   所以恶意网页没法伪造我们的请求，天然免疫 CSRF。
                 *
                 * ★ AbstractHttpConfigurer::disable 是什么写法？
                 *   方法引用，等价于：
                 *       csrf -> csrf.disable()
                 *   只是更短。能这么写是因为 csrf(方法) 接受的参数
                 *   正好是一个"接收配置器并调用 disable"的函数。
                 *
                 * ★ 如果以后改成 Cookie 认证，这行【必须删掉】，
                 *   否则就是 CSRF 漏洞。
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /*
                 * ═══════════════════════════════════════════════════
                 *  【2. 跨域配置】
                 * ═══════════════════════════════════════════════════
                 *
                 * ★ 为什么跨域要配在 Security 里，而不是 WebMvcConfigurer 里？
                 *   因为 Security 的过滤器链比 Spring MVC【更早执行】。
                 *   如果只在 MVC 里配，当请求被 Security 拒绝（401）时，
                 *   响应里不会带跨域头 —— 浏览器会报 CORS 错误而不是 401，
                 *   让你误判问题原因。
                 *
                 * ★ 实际开发中会触发跨域吗？
                 *   开发时：前端走 Vite 代理（5173 → 8080），浏览器看到的是同源，不跨域
                 *   生产时：前端走 Nginx 反代（同域），也不跨域
                 *   所以这段配置主要是为了 Postman / 其他前端联调时能用。
                 */
                .cors(c -> c.configurationSource(corsSource()))

                /*
                 * ═══════════════════════════════════════════════════
                 *  【3. 无状态会话】★ 关键
                 * ═══════════════════════════════════════════════════
                 *
                 * STATELESS 的意思是："不要创建 HttpSession，不要从 Session 里读认证信息"。
                 *
                 * ★ 为什么？
                 *   我们用 JWT，认证信息在 token 里，不需要服务端会话。
                 *   如果不禁用 Session：
                 *       - Spring 会创建一个 JSESSIONID Cookie（多余）
                 *       - 每个请求都会去检查 Session（浪费）
                 *       - 多实例部署时会话不同步（麻烦）
                 *
                 * ★ 设成 STATELESS 后：
                 *   SecurityContext 只存在于【当前请求的 ThreadLocal】里，
                 *   请求结束就没了 —— 下一个请求必须重新验 token。
                 *   这既是无状态的体现，也是安全的保证
                 *   （token 被拉黑后，下一个请求立刻失效）。
                 */
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /*
                 * ═══════════════════════════════════════════════════
                 *  【4. ★★ 授权规则 —— 本类最重要的一段】
                 * ═══════════════════════════════════════════════════
                 *
                 * 规则是【从上往下匹配，第一个匹配上的生效】。
                 * 所以顺序很重要：越具体的放前面。
                 */
                .authorizeHttpRequests(a -> a

                        /*
                         * 【4.1 放行所有 OPTIONS 请求】
                         *
                         * ★ 为什么要单独放行？
                         *   浏览器发"跨域且带自定义头"的请求前，会先发一个
                         *   OPTIONS 预检请求（preflight），问服务端"我能发吗"。
                         *   预检请求【不带 Authorization 头】。
                         *
                         *   如果预检被 401 拦了，浏览器就不会发真正的请求，
                         *   你会看到"请求根本没到后端"的诡异现象。
                         *
                         * ★ HttpMethod.OPTIONS 加 "/**" 表示所有路径的 OPTIONS 都放行。
                         */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        /*
                         * 【4.2 公开接口】
                         *
                         * ★ 这一组是最需要想清楚的 —— 放错了就是安全漏洞。
                         *
                         * 逐个说明为什么必须公开：
                         *
                         *   /api/auth/register  → 还没账号，哪来的 token？
                         *   /api/auth/login     → 要登录才能调登录接口？死锁
                         *   /api/auth/refresh   → access 过期才调它，此时没有效 access
                         *   /api/auth/logout    → token 可能已过期，也应该允许"退出"
                         *                          （否则用户永远退不出去，体验很差）
                         *
                         *   /api/meta/**        → 功能开关，前端启动时就要读
                         *                          （比如 AI 关闭时隐藏面试模块入口）
                         *
                         *   /v3/api-docs/**     → Swagger 的接口描述 JSON
                         *   /swagger-ui/**      → Swagger 页面本身
                         *   /swagger-ui.html    → 旧版路径（重定向）
                         *   ★ 生产环境建议关掉 Swagger，或者加访问限制 ——
                         *     因为它会把所有接口、参数结构都暴露出来。
                         *
                         *   /actuator/health    → 健康检查，Docker healthcheck 要用。
                         *                          ★ 只放行 health，不放行 actuator/** ——
                         *                          actuator 还有其他端点（env、beans 等）
                         *                          会泄露配置和内部结构。
                         */
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/meta/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health"
                        ).permitAll()

                        /*
                         * 【4.3 社区内容公开可读】
                         *
                         * ★ 为什么要公开 GET 请求？
                         *   社区的价值在于"没登录的人也能看" ——
                         *   能搜到、能分享给朋友、能被搜索引擎收录。
                         *
                         * ★ 为什么只放 GET？
                         *   POST（发帖）、DELETE（删帖）当然要登录。
                         *   用 HttpMethod.GET 限定，比写 "/api/posts/**" 更精确。
                         *
                         * ★ Controller 里怎么处理"未登录"的情况？
                         *   用 @CurrentUserId(required = false) Long userId
                         *   → 未登录时 userId 为 null → Service 跳过"我的投票"查询
                         *   详见 13 步骤的 PostController。
                         */
                        .requestMatchers(HttpMethod.GET, "/api/posts/**").permitAll()

                        /*
                         * 【4.4 兜底：其他全部需要登录】
                         *
                         * ★ 这是"默认拒绝"原则（deny by default）：
                         *   新加的接口如果忘了配置规则，默认是【受保护】的，
                         *   而不是默认公开。
                         *
                         *   ★ 安全设计里这条极其重要：
                         *     宁可让某个该公开的接口意外要求登录（用户反馈后很快能发现），
                         *     也不要让某个该保护的接口意外公开（可能几个月都发现不了）。
                         *
                         * ★ 对比一下"默认放行"的写法：
                         *     .anyRequest().permitAll()
                         *   如果哪天新增了 DELETE /api/users/{id} 但忘了配规则，
                         *   任何人不登录就能删用户 —— 灾难。
                         */
                        .anyRequest().authenticated())

                /*
                 * ═══════════════════════════════════════════════════
                 *  【5. ★ 认证失败的处理（两个 handler）】
                 * ═══════════════════════════════════════════════════
                 *
                 * ★ 为什么必须自定义？
                 *   Spring Security 默认返回的是一个【空响应体】的 401/403。
                 *   前端拦截器拿到空响应体，无法解析 code，只能显示"未知错误"。
                 *
                 *   自定义之后，401 也会返回：
                 *       {"code":10001,"message":"请先登录","traceId":"...",...}
                 *   前端就能统一处理。
                 */
                .exceptionHandling(e -> e

                        /*
                         * 【5.1 未登录 / token 无效】→ 401
                         *
                         * ★ 什么时候触发？
                         *   请求打到了一个"需要登录"的接口，但 SecurityContext 里没有认证信息。
                         *   原因可能是：没带 token、token 过期、token 被拉黑、token 类型不对。
                         *
                         * ★ 注意：这是【最后一道判断】。
                         *   JwtAuthenticationFilter 只是"尝试认人"，认不出来也不拦；
                         *   真正决定"拦不拦"的是这里的授权规则。
                         */
                        .authenticationEntryPoint((req, res, ex) ->
                                writeError(res, ErrorCode.UNAUTHORIZED))

                        /*
                         * 【5.2 已登录但没权限】→ 403
                         *
                         * ★ 什么时候触发？
                         *   现在还用不到（因为还没有权限体系，登录了就是 ROLE_USER）。
                         *   二期加了 RBAC 之后，@PreAuthorize 失败会走到这里。
                         *
                         * ★ 401 和 403 的区别（面试常问）：
                         *   401 Unauthorized = "我不认识你"       （没登录 / 凭证无效）
                         *   403 Forbidden    = "我认识你，但你不能干这个"（权限不足）
                         */
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(res, ErrorCode.FORBIDDEN)))

                /*
                 * ═══════════════════════════════════════════════════
                 *  【6. ★ 把 JWT 过滤器插入过滤器链】
                 * ═══════════════════════════════════════════════════
                 *
                 * addFilterBefore(要插入的过滤器, 插在谁前面)
                 *
                 * ★ 为什么插在 UsernamePasswordAuthenticationFilter 前面？
                 *   那是"用户名密码认证"的过滤器，是传统表单登录用的。
                 *   我们要在 Spring Security 做任何认证判断【之前】
                 *   就把 JWT 解析好、把认证信息设置好，
                 *   这样后面的授权判断才能读到"已登录"这个状态。
                 *
                 *   ★ 顺序错了会怎样？
                 *     如果插在后面，授权判断时 SecurityContext 还是空的，
                 *     所有需要登录的接口都会返回 401 —— 即使带了合法 token。
                 */
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        /*
         * build() 生成最终的过滤器链。
         * ★ 前面所有配置都只是"设置"，这一行才真正生效。
         *   忘了 return 或者忘了 build，配置就完全不工作。
         */
        return http.build();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【认证失败时手写 JSON 响应】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么要手写，不能像 Controller 那样 return ApiResponse？
     *   因为这里在【Filter 层】，不在 Controller 层。
     *   Filter 拿到的只有 HttpServletResponse（原生的 Servlet 对象），
     *   没有 Spring MVC 那套"返回值自动转 JSON"的机制。
     *   所以必须自己：设置状态码 → 设置 Content-Type → 写 JSON 字符串。
     *
     * ★ 为什么需要用 ObjectMapper 序列化？
     *   因为要保证输出格式和正常响应完全一致（同样的字段、同样的时间格式）。
     *   用容器里的那个 ObjectMapper（本类注入了）就能保证一致。
     *
     * ★ 为什么方法签名写 throws IOException？
     *   因为 getWriter().write() 可能抛 IOException（客户端断开连接等）。
     *   这个异常会被 Tomcat 记录，不需要特别处理 ——
     *   反正响应也发不出去了。
     *
     * @param res 响应对象
     * @param ec  要返回的错误码（决定状态码和业务码）
     */
    private void writeError(HttpServletResponse res, ErrorCode ec) throws IOException {
        /*
         * 【设置 HTTP 状态码】
         * 401 或 403，来自 ErrorCode 里定义的值
         */
        res.setStatus(ec.getHttpStatus());

        /*
         * 【设置 Content-Type】
         *
         * ★ 这一行必须写，否则浏览器不知道这是 JSON，
         *   前端 res.json() 会失败。
         *
         * ★ 为什么加 charset=UTF-8？
         *   因为响应体里有中文（"请先登录"）。
         *   不指定字符集时，某些浏览器会用 ISO-8859-1 解析，中文变乱码。
         */
        res.setContentType("application/json;charset=UTF-8");

        /*
         * 【写响应体】
         *
         * ApiResponse.fail(ec, null) 会构造：
         *     {code:10001, message:"请先登录", data:null, traceId:"...", timestamp:...}
         *
         * ★ traceId 能取到吗？
         *   能 —— 因为 TraceIdFilter 排在最前面，此时 MDC 里已经有值了。
         *   所以前端收到的 401 响应里也带 traceId，排查一样方便。
         *
         * ★ 为什么 data 传 null？
         *   认证失败没有额外数据要给前端。
         *   前端只需要知道 code 和 message。
         */
        res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(ec, null)));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【跨域配置】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 逐项解释为什么要这么配：
     *
     * setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"))
     *      允许哪些来源访问。
     *      ★ 为什么用 Pattern 而不是 setAllowedOrigins？
     *        setAllowedOrigins 不支持通配符，只能写完整域名（http://localhost:5173）。
     *        用 Pattern 可以匹配任意端口，本地开发时前端换端口不用改配置。
     *      ★ 生产环境应该写具体域名（https://yourdomain.com），
     *        绝对不能用 "*"（那样任何网站都能调你的接口）。
     *
     * setAllowedMethods(...)
     *      允许哪些 HTTP 方法。
     *      ★ 为什么没有 PATCH？因为项目里不用 PATCH。
     *        用不到的方法不开放，减少攻击面。
     *
     * setAllowedHeaders(List.of("*"))
     *      允许请求带哪些头。
     *      ★ 用 "*" 是因为要允许 Authorization（自定义头）。
     *        精确写法是 List.of("Authorization", "Content-Type", "X-Trace-Id")，
     *        但用 "*" 更省事，且风险不大（头是客户端主动设的，骗不了服务端）。
     *
     * setAllowCredentials(false)
     *      ★ 关键：不使用 Cookie，所以设 false。
     *      如果设成 true，浏览器就允许跨域请求带 Cookie，
     *      那就要重新启用 CSRF 防护了。
     *      而且 allowCredentials=true 时，allowedOrigins 不能用通配符 ——
     *      两条规则冲突，这也是我们选 false 的原因之一。
     *
     * ★ 最后返回一个 CorsConfigurationSource：
     *   这就是 Spring Security 需要的类型。registerCorsConfiguration("/**", cfg)
     *   表示"所有路径都用这套配置"。
     */
    private CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 这个类的设计要点（面试可讲）】
     *
     *  1. 无状态会话（STATELESS）：不建 Session，认证信息只活在请求内
     *  2. 默认拒绝（anyRequest().authenticated()）：新接口默认受保护
     *  3. 公开接口列清楚，每个都能说出"为什么必须公开"
     *  4. 自定义 401/403 响应体，让前端能统一处理
     *  5. JWT 过滤器插在 UsernamePasswordAuthenticationFilter 之前
     *  6. CSRF 可以关（无 Cookie），跨域配在 Security 里（执行顺序更早）
     *
     *  ★ 如果被问"你的接口安全怎么保证"，就按这 6 条答。
     * ═══════════════════════════════════════════════════════════════
     */
}
