package com.jianli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  整个后端程序的"启动入口"，相当于一个普通 Java 程序的 main 方法。
 *  你双击 start-backend.bat，本质上就是让 JVM 执行这里的 main()。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【@SpringBootApplication 其实等于三个注解】
 *    1. @SpringBootConfiguration → 声明"我是配置类"
 *    2. @EnableAutoConfiguration → 自动配置：Spring 检查你 pom.xml 引了哪些依赖，
 *                                  就自动创建对应的对象。
 *                                  比如引了 spring-boot-starter-web，
 *                                  它就自动把内嵌 Tomcat 起起来、注册好 DispatcherServlet。
 *    3. @ComponentScan           → 从 com.jianli 包开始向下扫描所有子包，
 *                                  把带 @Component / @Service / @RestController / @Configuration
 *                                  的类都实例化成对象（叫 "Bean"）放进 Spring 容器。
 *
 *  ⚠️ 记住第 3 点：它只扫描【本类所在包及其子包】，即 com.jianli.**。
 *     所以本类必须放在最外层 com.jianli 下，否则你写的 Controller 根本不会被发现 ——
 *     这是初学者最常见的坑之一。
 */

/*
 * 【为什么启动类上要加下面这两个注解】
 *
 * @EnableAsync → 开启"异步方法"能力。加了之后，方法上写 @Async 就会被扔到另一个线程执行，
 *                调用方不用等它跑完。我们要用它跑 AI 分析（一次 5-10 秒），不能让用户干等。
 *                ⚠️ 坑：@Async 方法如果在【同一个类里被自己调用】不会生效（没走 Spring 代理），
 *                   所以异步方法必须放独立 Bean 里 —— 步骤 08 会专门讲。
 *
 * @EnableScheduling → 开启"定时任务"能力。加了之后，方法上写 @Scheduled(fixedDelay=15000)
 *                     就能每 15 秒自动执行一次。用来做 SSE 心跳、热度重算、浏览数回写。
 *
 * 【现在还用不到，为什么提前加？】
 * 不加不会报错，只会让 @Async "静默失效" —— 你以为异步了，实际是同步。
 * 这种 bug 极难排查，所以宁可提前加。
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling

/*
 * 【@ConfigurationPropertiesScan】步骤 3 新增
 *
 * 作用：扫描项目里所有标了 @ConfigurationProperties 的类，
 *      把它们注册成 Bean。
 *
 * ★ 不加会怎样？
 *   JwtProperties 上有 @ConfigurationProperties(prefix="app.jwt")，
 *   但如果没有这个注解（也没在别处加 @EnableConfigurationProperties），
 *   它根本不会被创建 —— 启动时报：
 *       No qualifying bean of type 'com.jianli.security.JwtProperties'
 *
 * ★ 两种做法的区别：
 *   方式一（本处）：在启动类上加 @ConfigurationPropertiesScan，全局扫描 —— 省事
 *   方式二：在 @Configuration 类上加
 *          @EnableConfigurationProperties(JwtProperties.class) —— 精确但要逐个列出
 *
 *   项目里配置类会有好几个（AiProperties、ScoringProperties、PrivacyProperties...），
 *   用扫描方式更省事。
 */
@ConfigurationPropertiesScan
public class ResumeAiApplication {

    /**
     * 【main 方法】Java 程序的入口，JVM 从这里开始执行。
     *
     * SpringApplication.run(...) 这一行按顺序做了这些事：
     *   1. 创建 Spring 容器（ApplicationContext，你可以理解成一个"对象仓库"）
     *   2. 扫描 com.jianli 包，把所有带注解的类创建成 Bean 放进仓库
     *   3. 启动内嵌 Tomcat，监听 8080 端口
     *   4. 控制台打印 "Started ResumeAiApplication in X seconds"
     *
     * 如果第 4 步没打印出来，说明启动失败 —— 往上翻日志，找第一个 ERROR 行。
     *
     * @param args 命令行参数。比如想临时换端口可以写 --server.port=9090，一般用不到
     */
    public static void main(String[] args) {
        SpringApplication.run(ResumeAiApplication.class, args);
    }
}
