package com.jianli.config;

import com.jianli.common.CurrentUserIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  Spring MVC 的配置类。目前只做一件事：把 @CurrentUserId 的解析器注册进去。
 *
 *  ★ 如果不注册会怎样？
 *    Controller 方法里写 @CurrentUserId Long userId 时，Spring 会报：
 *        No argument resolver found for parameter ...
 *    因为没有任何解析器"认得"这个注解。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：注册之后发生了什么】
 *
 *      Spring 启动
 *         │  扫描到本类实现了 WebMvcConfigurer
 *         │  调用 addArgumentResolvers(...)
 *         ↓
 *      CurrentUserIdResolver 被加入 MVC 的解析器列表
 *         ↓
 *      之后每个请求，DispatcherServlet 都会用它来解析 @CurrentUserId 参数
 *         └─ 见 CurrentUserIdResolver 的类注释
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么 @Component 不够，必须在这里注册？】
 *
 *  这是新手最容易困惑的地方，一定要分清：
 *
 *  ┌──────────────────┬────────────────────────┬────────────────────┐
 *  │ 组件             │ 加 @Component 就够了？ │ 还需要额外注册？    │
 *  ├──────────────────┼────────────────────────┼────────────────────┤
 *  │ Filter           │ ✅ 自动加入过滤器链     │ ❌ 不需要          │
 *  │ Controller       │ ✅ 自动映射路由         │ ❌ 不需要          │
 *  │ Service/Repo     │ ✅ 自动可注入           │ ❌ 不需要          │
 *  │ ArgumentResolver │ ❌ 只是进了容器         │ ✅ 必须显式注册     │
 *  │ Interceptor      │ ❌ 只是进了容器         │ ✅ 必须显式注册     │
 *  │ MessageConverter │ ❌ 只是进了容器         │ ✅ 必须显式注册     │
 *  └──────────────────┴────────────────────────┴────────────────────┘
 *
 *  ★ 为什么有区别？
 *    因为 Spring MVC 内部维护着几个【独立的列表】（解析器列表、拦截器列表、
 *    转换器列表）。加 @Component 只是把对象放进 Spring 容器，
 *    并不会自动塞进 MVC 的那些列表里 —— 必须通过 WebMvcConfigurer 手动加。
 *
 *  ★ 而 Filter 不同：Spring Boot 有专门的机制扫描容器里所有 Filter 类型的 Bean
 *    并自动注册到 Servlet 容器。这是 Spring Boot 做的额外工作，不是 Spring MVC。
 *
 *
 *  【WebMvcConfigurer 是什么】
 *
 *  一个接口，里面全是 default 方法（空实现）。你只需要重写关心的那几个。
 *
 *  ★ 为什么设计成"全是 default 方法"？
 *    因为可配置的东西太多了（拦截器、跨域、消息转换器、视图解析器、
 *    静态资源、路径匹配策略...）。如果都是抽象方法，
 *    你只想配一个拦截器，也得把这十几个方法全实现一遍。
 *
 *    设计成 default 空实现后，你想改哪个就重写哪个 —— 这叫"接口适配"。
 *
 *  ★ 常见的重写方法：
 *      addArgumentResolvers   ← 本类用的
 *      addInterceptors        加拦截器（登录校验、权限校验）
 *      addCorsMappings        配置跨域
 *      addFormatters          自定义类型转换（比如 String → 枚举）
 *      addResourceHandlers    静态资源映射
 *
 *  【@RequiredArgsConstructor 在这里做什么】
 *  给 final 字段生成构造器，Spring 用它做构造器注入。
 *  所以下面可以直接用 currentUserIdResolver 字段。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /**
     * 通过构造器注入 CurrentUserIdResolver。
     *
     * ★ 它为什么能被注入？
     *   因为 CurrentUserIdResolver 类上加了 @Component，
     *   Spring 已经把它实例化放进容器了。
     *
     * ★ 为什么字段是 final？
     *   加上 @RequiredArgsConstructor 后，final 字段必须通过构造器赋值 ——
     *   这正是构造器注入需要的。final 保证它不会被中途替换。
     */
    private final CurrentUserIdResolver currentUserIdResolver;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【注册参数解析器】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param resolvers Spring MVC 的解析器列表。往里面 add 即可。
     *
     * ★ 为什么是 List 参数而不是返回值？
     *   这是"把自己加到已有列表里"的模式。
     *   Spring 传给你它维护的那个 List，你往里加，加完就生效了。
     *   不需要 return 什么。
     *
     * ★ 加进去之后，顺序重要吗？
     *   重要的。Spring 是按列表顺序依次问 supportsParameter，
     *   【第一个说 true 的】会被采用。
     *
     *   我们 add 在列表【末尾】，意味着：
     *   如果某个参数同时符合"内置解析器的条件"和"@CurrentUserId 的条件"，
     *   会优先用内置的。
     *
     *   ★ 实际不会冲突，因为 @CurrentUserId 是自定义注解，
     *     内置解析器都不认识它。所以放在末尾是安全的。
     *
     *     但如果哪天发现解析行为不对，先怀疑顺序问题 ——
     *     可以改用 resolvers.add(0, ...) 插到最前面。
     *
     * ★ 后面可能还会往这里加什么？
     *   如果以后要支持"分页参数自动解析"（比如 @PageableDefault），
     *   或者自定义的参数转换，都在这个方法里继续 add。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdResolver);
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么跨域配置没写在这里？】
     *
     *  跨域（CORS）我们配在了 SecurityConfig 里：
     *      .cors(c -> c.configurationSource(corsSource()))
     *
     *  ★ 原因：Spring Security 的过滤器链【比 Spring MVC 更早执行】。
     *    如果只在 WebMvcConfigurer 里配跨域，那么当请求被 Security 拒绝（401）时，
     *    跨域响应头不会被加上 —— 浏览器会报一个 CORS 错误而不是 401，
     *    让你误以为是跨域问题，实际是没登录。
     *
     *  ★ 所以规矩是：项目用了 Spring Security，跨域就配在 Security 里。
     *    没用的项目，配在 WebMvcConfigurer 里即可。
     *
     *  ★ 另外：本项目开发时前端走 Vite 代理（同源，不跨域），
     *    生产时走 Nginx 反向代理（也同源）。所以跨域配置实际上很少被触发，
     *    但配上是以防有人直接用 Postman 或其他前端联调。
     * ═══════════════════════════════════════════════════════════════
     */
}
