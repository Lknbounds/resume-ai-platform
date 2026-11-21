package com.jianli.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  Swagger（接口文档）的配置。给你一个能点、能试的接口页面：
 *      http://localhost:8080/swagger-ui/index.html
 *
 *  另外它还暴露一份机器可读的接口描述：
 *      http://localhost:8080/v3/api-docs
 *  （前端可以用它自动生成 API 调用代码，或者导入 Postman）
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【为什么要配这个类？不配会怎样？】
 *  不配也能打开 Swagger —— 只是页面上没有标题、没有说明，
 *  而且不会出现那个 Authorize 按钮。
 *
 *  没有 Authorize 按钮意味着：步骤 3 加了 JWT 之后，
 *  你在 Swagger 里点"试试"会被 401 拦下来（因为没有 token），
 *  只能去 curl 或者 Postman 手动带 header。配置这 20 行的收益就是省下这些麻烦。
 *
 *
 *  【@Configuration 是什么？】
 *  告诉 Spring："这是一个配置类，里面可能有 @Bean 方法要执行"。
 *  配置类本身也会被注册成 Bean，但它主要的作用是"生产别的 Bean"。
 *
 *  @Configuration 和 @Component 的区别：
 *      @Component    → 我自己就是一个 Bean
 *      @Configuration → 我是生产 Bean 的工厂
 *  实际上 @Configuration 内部就带了 @Component 的语义，所以也会被扫描到。
 *
 *
 *  【@Bean 是什么？】
 *  标在方法上，意思是：
 *      "调用这个方法，把它的返回值注册进 Spring 容器，方法名就是 Bean 的名字"。
 *
 *  什么时候用 @Bean 而不是在类上加 @Component？
 *  当你需要【手动构造】一个对象时。比如 OpenAPI 这个类来自第三方库，
 *  你没法在它的源码上加 @Component 注解，那就只能用 @Bean 方法自己 new 出来。
 *
 *  什么时候用 @Component？
 *  你自己写的类，加个注解就行，不用写工厂方法。
 *
 *
 *  【这个 OpenAPI 对象是谁在用？】
 *  springdoc 这个库在启动时会扫描所有 Controller，读取 @GetMapping、@RequestBody 等注解，
 *  结合你这里配置的 OpenAPI 对象，动态生成接口文档。
 *  所以你在这里加的东西会出现在页面顶部（标题、版本、说明）。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 安全方案的"名字"。这个名字会出现在 OpenAPI 描述里，
     * 后续如果想给某个接口单独标注"需要这个认证"，就用这个名字引用。
     * 定义成常量，避免两处写不一样的字符串。
     */
    private static final String BEARER = "bearerAuth";

    /**
     * 生产并注册一个 OpenAPI 配置对象。
     *
     * 这个名字 openAPI 会成为 Bean 名，但你在别处一般不会注入它 ——
     * 它是给 springdoc 用的，不是给业务代码用的。
     *
     * 方法体就是"链式构造"：new 一个对象，一层层往里塞配置。
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()

                /*
                 * 【info()】页面顶部展示的信息
                 * 这部分纯粹是给人看的，不影响功能。
                 */
                .info(new Info()
                        .title("AI 简历分析与社区互评平台")   // 大标题
                        .version("v1")                        // 版本号
                        .description("粘贴简历 + JD → 结构化解析 → ATS 评分 → 社区互评"))

                /*
                 * 【components()】定义"可复用的组成部分"，这里只定义认证方案
                 *
                 * addSecuritySchemes(名字, 方案定义)
                 *   → 相当于在文档里注册一个叫 "bearerAuth" 的方案：
                 *     "这是个 HTTP 认证，协议是 bearer，令牌格式是 JWT"
                 *
                 * Springdoc 认得这个标准名称，会自动在页面右上角
                 * 生成一个 Authorize 按钮，点了可以粘贴 token。
                 *
                 * ★ 粘贴进去之后，之后每个"试试看"的请求都会自动带上
                 *   Authorization: Bearer <你的token> 这个请求头。
                 *   这就是为什么步骤 3 之后调试特别方便。
                 */
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)   // 认证类型：HTTP 认证
                                .scheme("bearer")                 // 方案名：bearer
                                .bearerFormat("JWT")));           // 令牌格式（纯展示用）
    }
}
