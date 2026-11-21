package com.jianli.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  "请求追踪号"过滤器。每个进入后端的 HTTP 请求，都会被它贴上一个 16 位的随机 id。
 *
 *  效果：
 *      响应体里会多一个字段   "traceId": "d70beb5bd41d47c1"
 *      日志每一行前面会多一段 [d70beb5bd41d47c1]
 *
 *  这样一次请求经过的所有日志都能串起来，排查问题极快。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：traceId 是怎么"跟着请求走完全程"的】
 *
 *      浏览器发送请求（可选带 X-Trace-Id 头）
 *         ↓
 *      ★ 【本类】生成/沿用 traceId
 *         ├─ MDC.put("traceId", id)        → 放进当前线程的上下文
 *         └─ res.setHeader("X-Trace-Id")   → 写进响应头
 *         ↓
 *      后续所有 Filter、Controller、Service、Repository 打的日志
 *         └─ 自动带上 [id]（因为 logback 格式里写了 %X{traceId}）
 *         ↓
 *      Controller 构造 ApiResponse 时
 *         └─ 从 MDC 里取出同一个 id，放进 JSON 的 traceId 字段
 *         ↓
 *      响应回到浏览器：响应头 + 响应体里都有同一个 traceId
 *         ↓
 *      ★ 【本类】finally 里 MDC.remove("traceId")  ← 请求结束，清理
 *
 *  ★ 这就是为什么你不需要在任何地方手动传 traceId —— 它挂在"当前线程"上，
 *    整条链路自动共享。这也是为什么 finally 里的清理绝对不能省（见下文）。
 *
 *
 *  【为什么需要它？举个真实场景】
 *
 *  用户说："我下午 3 点发布了帖子，它报错了。"
 *  你去日志里翻 —— 那个时间点可能有 200 条日志，来自 30 个不同用户，
 *  你根本不知道哪几行是这一次请求的。
 *
 *  有了 traceId：
 *      用户截图给你看 → 上面有 traceId=abc123
 *      你 grep abc123 → 这一整条链路的所有日志都出来了：
 *
 *      15:03:21.101 INFO  [abc123] JwtAuthenticationFilter - 解析 token 成功, userId=7
 *      15:03:21.150 INFO  [abc123] PostService - 开始发布, reportId=41
 *      15:03:21.203 INFO  [abc123] PrivacyDetector - 命中 2 处敏感信息
 *      15:03:21.205 WARN  [abc123] GlobalExceptionHandler - biz error code=43002
 *
 *  一眼就能看出在哪一步出的问题。
 *
 *
 *  【Filter 是什么？它站在请求链的什么位置？】
 *
 *  一个 HTTP 请求到达 Tomcat 后，会经过一条"流水线"，才最终到达你的 Controller：
 *
 *      请求进来
 *        ↓
 *      [Filter 1]  TraceIdFilter       ← 本类，最先执行
 *        ↓
 *      [Filter 2]  CorsFilter          处理跨域
 *        ↓
 *      [Filter 3]  JwtAuthenticationFilter  验 token（步骤 3 加）
 *        ↓
 *      [Filter 4]  ...其他
 *        ↓
 *      DispatcherServlet   ← Spring MVC 的核心调度器
 *        ↓
 *      Controller 方法
 *        ↓
 *      原路返回
 *
 *  Filter 在整个链的最外层，所以它在 Controller 之前执行、在 Controller 返回之后
 *  还能再干点事（本类就靠这个在 finally 里做清理）。
 *
 *
 *  【为什么继承 OncePerRequestFilter 而不是直接实现 Filter？】
 *  Servlet 规范里，一个请求在"转发"或"包含"时，Filter 可能被重复调用多次。
 *  如果每次调用都生成新的 traceId，同一个请求就会有好几个 id，追踪就断了。
 *
 *  OncePerRequestFilter 顾名思义：保证一次请求只执行一次 doFilterInternal。
 *  这是 Spring 提供的便利基类，凡是"每个请求做一次"的逻辑（追踪、日志、鉴权）都该用它。
 *
 *
 *  【@Order(Ordered.HIGHEST_PRECEDENCE) 什么意思？】
 *  多个 Filter 之间有顺序。这个注解把本类排到最前面。
 *
 *  ★ 为什么必须是第一个？
 *  因为后面的 Filter、Controller、Service 打的日志都要带上 traceId。
 *  如果本类跑到后面去了，前面那些日志就没有 id，链路会断开头。
 *  验证里"响应头里的 traceId 和日志里的一致"就是在验证这个顺序。
 *
 *
 *  【MDC 是什么？为什么它能"让日志自动带上 id"？】
 *
 *  MDC = Mapped Diagnostic Context，可以理解成"绑定在当前线程上的一个 Map"。
 *
 *      MDC.put("traceId", "abc123")   → 往当前线程的 Map 里塞一个键值对
 *      然后这个线程里任何地方打日志，logback 都能读到它
 *
 *  怎么读到的？靠 logback-spring.xml 里的这行配置：
 *      <pattern>... [%X{traceId:-}] ...</pattern>
 *                         ↑ %X{key} 就是从 MDC 里取值，取不到用 - 替代
 *
 *  所以 TracingFilter 做了两件事：往 MDC 里放 id、从 MDC 里清理 id，
 *  中间的日志就自然带上它了。
 *
 *
 *  【★ 为什么 finally 里的 MDC.remove 绝对不能省？】
 *
 *  Tomcat 用线程池处理请求，一个线程处理完 A 用户的请求，会接着处理 B 用户的请求。
 *  MDC 是绑定在线程上的，如果不清掉：
 *
 *      A 请求 → 线程 T1 → MDC.put("traceId","aaa") → 处理完 → 没清理
 *      B 请求 → 还是线程 T1 → 打日志时读 MDC → 拿到的是 "aaa"！  ← 张冠李戴
 *
 *  结果就是 B 用户的日志全部标成了 A 的 traceId，
 *  排查时你会看到"两个完全不相干的请求混在一起"，比没有 id 更糟。
 *
 *  而且这个 bug 是【间歇性】的 —— 只有线程被复用时才出现，测都测不出来。
 *  所以 finally 不是可选的，是必须的。
 *
 *
 *  【@Component 是什么？】
 *  告诉 Spring："把这个类实例化成一个 Bean，放进容器里"。
 *  因为它是 Filter，Spring 会自动把它注册到 Servlet 容器的过滤器链里，不用额外配置。
 *
 *  @Slf4j 这个类没加，因为它不需要打日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 里的 key 名。定义为常量，避免各处手写字符串打错字 */
    public static final String TRACE_ID = "traceId";

    /** HTTP 头名。前端可以主动传这个头进来（比如做链路透传时），我们就沿用它 */
    private static final String HEADER = "X-Trace-Id";

    /**
     * 每个请求都会走一次这个方法。
     *
     * 参数说明：
     *   req   → 请求对象。能拿到请求头、请求体、URL、Cookie 等一切信息
     *   res   → 响应对象。能设置响应头、状态码、写响应体
     *   chain → 过滤器链。调用 chain.doFilter(req, res) 才会把请求继续往下传。
     *           ★ 不调用它，请求就到此为止了（前端会收到一个空响应，卡住）。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        /*
         * 【第一步：决定用哪个 traceId】
         *
         * 优先用前端传进来的 X-Trace-Id（如果有）。为什么？
         *   如果以后有多个服务（比如网关+后端），网关生成一个 id 传下来，
         *   这样能跨服务追踪整条链路。现在单服务用不到，但这是标准做法。
         *
         * .filter(s -> !s.isBlank())
         *   过滤掉空字符串 —— 前端可能传了个 X-Trace-Id: （空的），
         *   这种要当"没传"处理，否则所有请求共用一个空 id，追踪就失效了。
         *
         * .orElseGet(...)  没有就用 UUID 生成一个。
         *   ★ 用 orElseGet 而不是 orElse：
         *     orElse 无论前面有没有值，都会执行括号里的代码（白生成一个 UUID，浪费）；
         *     orElseGet 只有在前面为空时才执行。这是 Java 里的小性能细节。
         */
        String traceId = Optional.ofNullable(req.getHeader(HEADER))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> {
                    // UUID 形如 3f2504e0-4f89-11d3-9a0c-0305e82c3301，有横杠且太长
                    // 去掉横杠后取前 16 位，够用且日志更紧凑
                    return UUID.randomUUID().toString()
                            .replace("-", "")
                            .substring(0, 16);
                });

        /*
         * 【第二步：把 id 放进 MDC，这样后续所有日志自动带上它】
         *
         * 注意这个 put 必须在本方法最前面，晚于任何日志就会漏掉开头的几行。
         * 我们加了 HIGHEST_PRECEDENCE，这个类是第一个 Filter，所以刚好赶得上。
         */
        MDC.put(TRACE_ID, traceId);

        /*
         * 【第三步：同时写进响应头】
         *
         * 这样做的好处：即使用户看不到响应体（比如在浏览器开发者工具的
         * Network 面板里，或者接口是由前端拦截器处理的），也能从响应头拿到 traceId。
         */
        res.setHeader(HEADER, traceId);

        try {
            /*
             * 【第四步：把请求继续往下传】
             *
             * 这一行是"放行"：请求继续走到下一个 Filter，最终到达 Controller。
             * Controller 执行完后，会返回到这一行之后继续执行（像方法调用返回一样）。
             * 所以下面的 finally 是在【整个请求处理完之后】才执行的。
             */
            chain.doFilter(req, res);
        } finally {
            /*
             * 【第五步：清理】
             *
             * 为什么放在 finally 里？
             *   不管是正常返回、还是 Controller 抛了异常（异常会先被
             *   GlobalExceptionHandler 处理，但处理完仍然会往上传），
             *   finally 都保证会执行。否则一旦某次请求抛异常，
             *   线程上的 traceId 就永远清不掉了。
             *
             * ★ 这一行的作用见上面【为什么 finally 里的 MDC.remove 绝对不能省】
             */
            MDC.remove(TRACE_ID);
        }
    }
}
