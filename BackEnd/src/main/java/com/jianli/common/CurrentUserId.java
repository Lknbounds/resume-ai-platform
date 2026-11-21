package com.jianli.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一个自定义注解。写在 Controller 方法的参数上，就能直接拿到"当前登录用户的 id"。
 *
 *  用法：
 *      @GetMapping("/me")
 *      public ApiResponse<UserVO> me(@CurrentUserId Long userId) { ... }
 *
 *  没有它的话，每个接口都得这么写：
 *      public ApiResponse<UserVO> me(Authentication auth) {
 *          LoginUser user = (LoginUser) auth.getPrincipal();
 *          Long userId = user.id();
 *          ...
 *      }
 *  10 个接口就重复 10 遍。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：一个注解是怎么变成参数值的】
 *
 *      JwtAuthenticationFilter
 *         └─ SecurityContextHolder.setAuthentication(LoginUser)
 *              ↓  （放在当前请求的 ThreadLocal 里）
 *
 *      DispatcherServlet 调 Controller 前，发现方法有个参数标了 @CurrentUserId
 *         └─ 查"参数解析器"列表，找到 CurrentUserIdResolver（它 supports 返回 true）
 *              ↓
 *      CurrentUserIdResolver.resolveArgument(...)
 *         ├─ 从 SecurityContextHolder 取出 LoginUser
 *         ├─ 返回 loginUser.id()
 *         └─ Spring 把这个 Long 值塞进方法参数
 *              ↓
 *      你的 Controller 方法拿到 userId，直接用
 *
 *  ★ 整个过程没有任何"手动传参"，是框架自动做的。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【为什么要自己写一个注解，不用 @AuthenticationPrincipal？】
 *
 *  Spring Security 自带一个：
 *      public ApiResponse<UserVO> me(@AuthenticationPrincipal LoginUser user) {
 *          Long userId = user.id();
 *          ...
 *      }
 *
 *  也能用，但有两个小问题：
 *  1. 每个方法都要判空（未登录时 user 是 null），多一行 if
 *  2. Service 层只关心 Long，却被迫依赖了 LoginUser 这个 Security 类型
 *
 *  自己写一个之后：
 *      me(@CurrentUserId Long userId)     ← 干净、类型简单、不用判空
 *
 *  ★ 这是"把框架细节挡在 Controller 之外"的思路 ——
 *    业务代码只看到 Long，看不到 Security 的任何东西。
 *
 *
 *  【下面这四行元注解，每一个都必须有】
 *
 *  @Target(ElementType.PARAMETER)
 *      规定这个注解只能写在【方法参数】上。
 *      如果写错地方（比如标在类上），编译直接报错。
 *      ★ 这就是注解的"自我约束"—— 写错位置编译期就发现，不用等运行时。
 *
 *  @Retention(RetentionPolicy.RUNTIME)
 *      规定这个注解要保留到【运行时】。
 *      ★ 这一行最关键：
 *          SOURCE   → 只在源码里，编译后就丢了（比如 @Override）→ 解析器读不到
 *          CLASS    → 保留到 .class 文件，但 JVM 加载后丢掉
 *          RUNTIME  → 一直保留，可以通过反射读取  ← 我们需要这个
 *      因为 Spring 是运行时通过反射查"这个参数有没有 @CurrentUserId"，
 *      写成 SOURCE 或 CLASS 的话，注解根本不存在，参数永远是 null。
 *
 *  @Documented
 *      生成 javadoc 时把这个注解也写进去，纯文档作用，不影响功能。
 *
 *  @interface
 *      Java 里定义注解的关键字（不是 interface，是 @interface）。
 *      ★ 注解本质上也是一个接口，只是语法特殊。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【是否必须有登录态】默认 true
     * ═══════════════════════════════════════════════════════════════
     *
     *  ★ 这个属性解决什么问题？
     *
     *  有些接口是【公开的】，但登录后体验更好。比如帖子详情：
     *      - 未登录也能看内容
     *      - 登录后要额外返回"我有没有给它投过票"
     *
     *  这时方法想拿到 userId，但未登录时不能报错（否则公开接口就废了）：
     *
     *      @GetMapping("/posts/{id}")
     *      public ApiResponse<PostDetailVO> detail(
     *              @PathVariable Long id,
     *              @CurrentUserId(required = false) Long userId) {   ← 未登录时 userId = null
     *          return ApiResponse.ok(postService.detail(id, userId));
     *      }
     *
     *  Service 里判断 userId 是否为 null，决定要不要查"我的投票记录"。
     *
     *  ★ 当 required = true（默认）时：
     *    未登录会抛 BizException(UNAUTHORIZED)，返回 401。
     *    适用于所有"必须登录才能用"的接口。
     *
     *  ★ 为什么默认是 true 而不是 false？
     *    因为安全默认值应该是"严格"的。
     *    默认 false 的话，忘记写 required=true 就会让接口意外变成公开的 ——
     *    这是个安全漏洞，而且是静默的。
     *
     *  ★ 注解属性的语法：
     *    default true  → 调用时可以不写，就是 true
     *    类型只能是基本类型、String、Class、枚举、注解、以及它们的数组
     *    （不能是任意对象，这是 Java 对注解的硬性限制）
     */
    boolean required() default true;
}
