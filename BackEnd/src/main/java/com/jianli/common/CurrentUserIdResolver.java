package com.jianli.common;

import com.jianli.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  "@CurrentUserId 注解的翻译官"。
 *
 *  Spring 看到参数上有这个注解时，会把"填参数"这件事交给本类处理，
 *  本类负责从 SecurityContext 里把 userId 取出来。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一次参数绑定的完整过程】
 *
 *      DispatcherServlet 准备调用 Controller 方法
 *         │  遍历每个参数，问"谁能解析它？"
 *         ↓
 *      HandlerMethodArgumentResolverComposite
 *         │  依次问每个 resolver 的 supportsParameter(...)
 *         ├─ RequestParamMethodArgumentResolver → false（不是 @RequestParam）
 *         ├─ PathVariableMethodArgumentResolver → false（不是 @PathVariable）
 *         ├─ RequestResponseBodyMethodProcessor → false（不是 @RequestBody）
 *         └─ 【CurrentUserIdResolver】        → true  ★ 命中
 *              ↓
 *         resolveArgument(...) 被调用
 *              ├─ 从 SecurityContextHolder 取出 LoginUser
 *              └─ 返回 loginUser.id()（一个 Long）
 *              ↓
 *         这个 Long 被塞进方法参数
 *              ↓
 *         你的 Controller 方法执行
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【HandlerMethodArgumentResolver 是什么】
 *
 *  这是 Spring MVC 的一个扩展点。Spring 内置了十几个实现，负责处理
 *  @RequestParam、@PathVariable、@RequestBody、@RequestHeader 等等。
 *
 *  ★ 换句话说：你平时用的那些注解，背后都是这个接口的实现类在工作。
 *    自己实现一个，就等于"给 Spring 加了一种新的参数类型"。
 *
 *  接口只有两个方法：
 *      supportsParameter(param) → "这个参数归我管吗？"
 *      resolveArgument(...)     → "那我来给你算出值"
 *
 *  ★ 这是 Spring 里最典型的"策略模式"应用：
 *    Spring 不知道每个参数该怎么填，就把这个问题丢给一堆"策略"，
 *    哪个策略说"我管"，就用哪个。加新类型只需要加新策略，不改框架代码。
 *
 *
 *  【★ 为什么要 @Component + 在 WebConfig 里注册？】
 *
 *  只加 @Component 是不够的 —— Spring 不会自动把容器里的
 *  HandlerMethodArgumentResolver 加到 MVC 的参数解析器列表里。
 *  必须显式注册（见 WebConfig）。
 *
 *  ★ 这和 Filter 不一样：
 *      Filter 加了 @Component 会自动生效
 *      ArgumentResolver 必须手动挂到 WebMvcConfigurer 上
 *    这是 Spring 内部的两套机制，容易混淆。
 *
 *
 *  【@RequiredArgsConstructor 为什么加在这里但没用字段？】
 *  ★ 其实本类没有 final 字段，所以这个注解是多余的。
 *    加上它只是保持和其他 Bean 一致的风格。删掉也完全没问题。
 *    （写代码时如果发现某个注解没实际作用，删掉更干净 ——
 *      这也是代码 review 该注意的点。）
 */
@Component
@RequiredArgsConstructor
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【Spring 问："这个参数归你管吗？"】
     * ═══════════════════════════════════════════════════════════════
     *
     * 当两个条件【同时】满足时才返回 true：
     *
     * ① 参数上有 @CurrentUserId 注解
     *    p.hasParameterAnnotation(CurrentUserId.class)
     *    这是最主要的判断，防止把其他参数也认领了。
     *
     * ② 参数类型是 Long（或它的包装类）
     *    Long.class.isAssignableFrom(p.getParameterType())
     *
     *    ★ 为什么要判断类型？
     *      如果某个方法写成 @CurrentUserId String userId，
     *      本类却返回了一个 Long，运行时就会抛 ClassCastException。
     *      提前判断类型，让这种错误变成"没有解析器能处理这个参数"，
     *      在启动或首次调用时就报错，而不是运行时诡异崩溃。
     *
     *    ★ isAssignableFrom 的意思：
     *      判断"左边是不是右边的父类型/同类"。
     *      Long.class.isAssignableFrom(Long.class) → true
     *      这里用它而不是 == Long.class，是为了兼容未来可能的子类
     *      （虽然 Long 是 final 的没有子类，但这是更通用的写法）。
     *
     * ★ 返回 false 时会发生什么？
     *   Spring 继续问下一个 resolver。如果所有 resolver 都说 false，
     *   并且参数有注解，Spring 会抛异常：
     *       "No argument resolver found for parameter ..."
     *   这通常意味着 WebConfig 忘了注册本类。
     *
     * @param parameter 被检查的方法参数（含它的注解和类型信息）
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【Spring 问："那你说这个参数该填什么？"】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param parameter 方法参数信息（能从中读到 @CurrentUserId 的 required 属性）
     * @param mavContainer 模型视图容器，本项目用不到（纯 JSON 接口）
     * @param webRequest 当前请求的包装，本项目用不到
     * @param binderFactory 数据绑定工厂，本项目用不到
     * @return 要填进参数的值（这里是 Long 类型的 userId，或者 null）
     */
    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        /*
         * 【第一步：从 SecurityContext 里取出认证信息】
         *
         * ★ 这个信息是谁放进去的？
         *   JwtAuthenticationFilter。它在验证 token 通过后调用
         *   SecurityContextHolder.getContext().setAuthentication(...)。
         *
         * ★ 没登录时是什么？
         *   可能返回 null，也可能返回一个特殊的 "anonymous" 认证对象
         *   （取决于匿名认证是否启用）。所以下面要判断 principal 的类型。
         */
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        /*
         * 【第二步：判断是不是真正的登录用户】
         *
         * ★ 为什么用 instanceof LoginUser 而不是判 null？
         *   因为未登录时 Spring Security 可能塞进一个
         *   String 类型的 principal（内容是 "anonymousUser"），
         *   它不为 null，但显然不是登录用户。
         *   用 instanceof 同时挡住了 null 和"匿名用户"两种情况。
         *
         * ★ 这是 Java 16+ 的模式匹配写法：
         *      auth.getPrincipal() instanceof LoginUser user
         *   匹配成功后，变量 user 直接可用，不用再强转。
         *   老写法是：instanceof 判断一次，再 (LoginUser) 强转一次 —— 冗余。
         *
         * ★ 如果这里拿不到，说明什么？
         *   1. 请求没带 token
         *   2. token 过期了
         *   3. token 被拉黑了
         *   4. token 是 refresh 类型（不是 access）
         *   具体是哪种，日志里能看到。但这里统一当作"未登录"处理。
         */
        if (auth != null && auth.getPrincipal() instanceof LoginUser user) {
            return user.id();
        }

        /*
         * 【第三步：没登录时的处理】
         *
         * 从参数上的注解读出 required 属性（默认 true）。
         *    required = true  → 抛异常，返回 401
         *    required = false → 返回 null，让业务代码处理
         *
         * ★ 抛 BizException 会被 GlobalExceptionHandler.handleBiz 接住，
         *   返回 HTTP 401 + {"code":10001,"message":"请先登录"}。
         *
         * ★ 为什么这里可以抛异常？
         *   因为 resolveArgument 是在 DispatcherServlet 调 Controller 期间执行的，
         *   抛出的异常会被正常的异常处理流程捕获 ——
         *   和 Service 里抛 BizException 走的是同一条路。
         */
        boolean required = parameter.getParameterAnnotation(CurrentUserId.class).required();

        if (required) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        return null;
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么这个类不写成静态工具类？】
     *
     *  因为 Spring 需要【实例】来调用它的两个方法。
     *  而且它要实现 HandlerMethodArgumentResolver 接口，
     *  只能是实例。
     *
     *  ★ 和 PrivacyDetector、ScoreCalculator 那类"Domen Service"的区别：
     *    那种类可以直接 new 出来测（纯计算，无框架依赖）；
     *    本类必须在 Spring MVC 的流程里才能工作，测起来要起容器或者 mock 一堆东西。
     *    所以本类一般不单独写单测，靠集成测试覆盖。
     * ═══════════════════════════════════════════════════════════════
     */
}
