package com.jianli.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  Jackson 的配置。作用是：把时间字段统一成 "2026-11-18 20:30:00" 这种格式，
 *  而不是默认的 "2026-11-18T20:30:00"。
 *
 *  只影响"对象 → JSON"和"JSON → 对象"这两步，不影响数据库里怎么存。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它卡在哪一环】
 *
 *      Service 返回 UserVO（里面有 LocalDateTime createdAt）
 *         ↓
 *      【本类配置的 Jackson 格式器】   ← ★ 在这一步起作用
 *         ↓
 *      JSON: {"createdAt":"2026-11-18 20:30:00"}    而不是  "2026-11-18T20:30:00"
 *         ↓
 *      前端直接显示，不需要自己处理那个 T
 *
 *  ★ 它不参与任何业务逻辑，只影响序列化格式。改错了只会导致"时间显示不好看"
 *    或者"前端传回来的时间解析失败"，不会影响数据本身的正确性。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *
 *  【★ 为什么 application.yml 里配了 date-format 还不够？】
 *
 *  application.yml 里这行：
 *      spring.jackson.date-format: yyyy-MM-dd HH:mm:ss
 *
 *  ★ 它【只对 java.util.Date 生效】，对 LocalDateTime 完全无效。
 *    因为 LocalDateTime 属于 Java 8 的新时间 API（java.time 包），
 *    Jackson 用另一套序列化器处理它，默认行为是输出 ISO-8601 格式：
 *        "2026-11-18T20:30:00"
 *                     ↑ 中间那个 T 是 ISO 标准的分隔符
 *
 *  这个 T 对机器友好，但对用户不友好。而且很多前端日期组件不认这个格式。
 *  所以我们得单独给 LocalDateTime 注册格式器 —— 就是本类干的事。
 *
 *  ★ 这是新手最常踩的坑之一：
 *    在 yml 里配了 date-format，然后发现时间还是带 T，怎么改都没用。
 *    原因就是配错了地方。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *
 *  【为什么用 Jackson2ObjectMapperBuilderCustomizer，而不是自己 new ObjectMapper？】
 *
 *  ★ 关键区别：Spring Boot 已经帮你创建了一个配置好的 ObjectMapper
 *    （里面注册了各种模块、处理了各种 Spring 相关的序列化规则）。
 *
 *    如果你自己 new 一个 ObjectMapper 注册成 Bean：
 *        - 会覆盖掉 Spring 自动配置的那个
 *        - 导致很多默认行为丢失（比如 @JsonIgnore 的处理、各种模块的注册）
 *        - 一些依赖 ObjectMapper 的组件（比如 Redis 序列化）会跟着出问题
 *
 *    Customizer 的思路是【在 Spring 那个 ObjectMapper 的基础上定制】：
 *        "帮我加两个序列化器，其他都保持原样"
 *
 *    ★ 这是 Spring Boot 里"定制而不覆盖"的标准做法，
 *      后面配置 Redis、MVC 等也都是这个模式。
 * ═══════════════════════════════════════════════════════════════════════
 */
@Configuration
public class JacksonConfig {

    /**
     * 时间格式模板。
     *
     * ★ 为什么定义成 static final 常量？
     *   1. DateTimeFormatter 的构造开销不小，只建一次复用即可
     *   2. 序列化（Java→JSON）和反序列化（JSON→Java）必须用【同一个】格式，
     *      定义成常量就不可能写成两个不一样的
     *
     * 各字段含义：
     *      yyyy → 四位年份
     *      MM   → 两位月份（注意是大写 M，小写 m 是"分钟"）
     *      dd   → 两位日期（小写 d 是"一年中的第几天"）
     *      HH   → 24 小时制（小写 h 是 12 小时制）
     *      mm   → 分钟
     *      ss   → 秒
     *
     * ★ 为什么格式里用空格而不是 T？
     *   纯粹是为了好看和前端友好。"2026-11-18 20:30:00" 比
     *   "2026-11-18T20:30:00" 更像人话，Element Plus 的日期组件也直接认。
     *
     * ★ 为什么没有毫秒？
     *   数据库里是 DATETIME(3) 存了毫秒，但展示给用户时毫秒没意义。
     *   如果哪天需要（比如做性能分析），改成 "yyyy-MM-dd HH:mm:ss.SSS" 即可。
     */
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 注册自定义的 LocalDateTime 格式器。
     *
     * 方法名的 localDateTimeCustomizer 会成为 Bean 名（虽然一般用不到这个名字）。
     *
     * 返回值类型 Jackson2ObjectMapperBuilderCustomizer 是 Spring Boot 提供的
     * 一个函数式接口，只有一个方法 customizer(builder)。
     * Spring Boot 在构建 ObjectMapper 时会依次调用所有这类 Bean。
     *
     * 方法体是一个 lambda：接收 builder，往里加两个序列化器。
     *      .serializers(...)    → 控制【Java 对象 → JSON】时怎么输出时间
     *      .deserializers(...)  → 控制【JSON → Java 对象】时怎么解析时间
     *
     * ★ 为什么两个都要注册？
     *   只注册 serializers：返回给前端的时间好看了，
     *                       但前端传回来的 "2026-11-18 20:30:00" 解析不了（它期待带 T 的）
     *   只注册 deserializers：前端传的能解析了，但返回的还是带 T
     *   两个都注册才对称。
     *
     * ★ 现在用得到吗？
     *   本步骤还用不到（UserVO 里有 LocalDateTime，但还没接口返回它）。
     *   步骤 3 做完登录之后，你会在响应里看到 "createdAt":"2026-11-18 20:30:00"。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(FMT))
                .deserializers(new LocalDateTimeDeserializer(FMT));
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【时区在哪里配的？为什么这里不管时区？】
     *
     *  application.yml 里有：
     *      spring.jackson.time-zone: GMT+8
     *
     *  ★ 但注意：time-zone 只影响 java.util.Date 和 Instant 这类
     *    "带时区信息的类型"。
     *
     *  LocalDateTime 本身【不带时区】—— 它就是个"墙上时钟的时间"，
     *  我们约定它永远表示 UTC 时间（见 AppUser 的 @PrePersist）。
     *
     *  所以这里只负责"格式"，不负责"时区换算"。
     *  真正的时区处理在三个地方，必须一致：
     *      1. 存储：LocalDateTime.now(ZoneOffset.UTC)     ← AppUser
     *      2. 连接：connectionTimeZone=UTC                ← application.yml 的 JDBC URL
     *      3. 展示：需要转东八区时，前端或后端自己加 8 小时
     *
     *  ★ 本项目为了简单，展示时【不转时区】，直接显示 UTC 时间。
     *    对个人项目、面试演示完全够用。
     *    真实产品如果要显示"北京时间"，应该在 VO 里转，或者干脆存成
     *    带时区的类型（OffsetDateTime）。这是一个已知的简化，面试时可以主动说。
     * ═══════════════════════════════════════════════════════════════
     */
}
