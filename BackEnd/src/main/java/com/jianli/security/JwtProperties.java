package com.jianli.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  JWT 相关配置的"容器"。把 application.yml 里 app.jwt.* 的值读进来，
 *  变成一个可以注入使用的对象。
 *
 *  yml 里的配置：
 *      app:
 *        jwt:
 *          secret: dev-only-change-me-please-32bytes-min!!
 *          access-ttl: PT2H        ← 2 小时
 *          refresh-ttl: P7D        ← 7 天
 *          issuer: resume-ai
 *
 *  这个类读完之后：
 *      props.secret()      → "dev-only-change-me-please-32bytes-min!!"
 *      props.accessTtl()   → Duration.ofHours(2)
 *      props.refreshTtl()  → Duration.ofDays(7)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：它被谁用】
 *
 *      application.yml
 *         │  ← Spring Boot 启动时自动读取并绑定
 *         ↓
 *      JwtProperties（本类，一个 Bean）
 *         ├─ JwtTokenProvider  → 拿 secret 签名、拿 ttl 算过期时间
 *         └─ AuthService       → 拿 refreshTtl 决定 refresh token 在 Redis 存多久
 *         ↓
 *      最终影响：用户登录后 token 的有效期
 *
 *  ★ 注意它只是个"数据搬运工"，不产生任何逻辑。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【为什么写这一堆注解】
 *
 *  @ConfigurationProperties(prefix = "app.jwt")
 *      告诉 Spring："把 yml 里 app.jwt 下面那一坨，自动映射到本类的字段上"。
 *
 *      映射规则（叫"宽松绑定"）：
 *          yml 里的 access-ttl   →  Java 字段 accessTtl     （短横线 → 驼峰，自动转）
 *          yml 里的 secret       →  Java 字段 secret
 *      所以你不用逐行写 @Value("${app.jwt.secret}")。
 *
 *      ★ 相比 @Value 的好处：
 *          1. 一处声明，多个字段一起绑
 *          2. 支持嵌套对象和 Map
 *          3. 可以做校验（见下）
 *          4. IDEA 里有自动补全提示（Spring Boot 配置处理器生成的）
 *
 *  @Validated
 *      开启对这个类的校验。有了它，下面的 @NotBlank / @NotNull 才会生效。
 *      ★ 没有这个注解，那些约束注解【完全不起作用】—— 这是个隐蔽的坑。
 *
 *  @NotBlank / @NotNull
 *      校验规则：
 *          @NotBlank → 不能为 null，且至少有一个非空白字符
 *          @NotNull  → 不能为 null（但可以是空对象）
 *
 *      ★ 为什么要校验？校验失败会怎样？
 *        如果 secret 没配（yml 漏了或者环境变量没传），启动时会直接抛出：
 *            Binding to target ... failed: Property: app.jwt.secret
 *            Reason: 不能为空
 *        应用【根本起不来】。
 *
 *        这听起来很严厉，但正是想要的：宁可启动时失败，
 *        也不要启动成功后每次登录都报一个莫名其妙的 NPE。
 *        这叫 fail-fast（快速失败）。
 *
 *
 *  【★ 为什么用 record 而不是普通的 class？】
 *
 *  1. record 天生不可变 —— 配置一旦读进来就不该被改，
 *     否则某处代码偷偷改了 secret，会导致已签发的 token 全部失效。
 *
 *  2. record 的构造器就是"全参构造器"，Spring Boot 会直接调用它，
 *     把 yml 里的值按【构造器参数名】匹配进去。
 *     所以参数名必须和配置项名字对得上（accessTtl ↔ access-ttl）。
 *
 *  3. 不需要 getter —— record 自动生成 secret()、accessTtl() 这样的访问器。
 *     ★ 注意调用时是 props.secret()，不是 props.getSecret()。
 *
 *  ★ record 做配置类有一个限制：不能有 setter，所以是"只读"的。
 *    这对配置类来说正好 —— 本来就不该在运行时改配置。
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(

        /**
         * 签名密钥。
         *
         * ★ 它是干什么的？
         *   JWT 是"签名"出来的：服务端用这个 secret 对 token 内容加签，
         *   验签时用同一个 secret 验证。攻击者不知道 secret，
         *   就无法伪造出一个能通过验签的 token。
         *
         * ★ 为什么要求至少 32 字节？
         *   我们用的是 HS256 算法（HMAC-SHA256）。按 RFC 规定，
         *   密钥长度至少要等于哈希输出长度，也就是 256 位 = 32 字节。
         *   短于 32 字节时 jjwt 会抛 WeakKeyException，启动直接失败。
         *
         *   ★★ 这条是安全红线：
         *   现在 yml 里的默认值只适合本地开发。
         *   上线前必须换成随机串，生成方式：
         *       openssl rand -base64 32
         *   否则任何看过你代码的人都能伪造任意用户的 token。
         */
        @NotBlank String secret,

        /**
         * access token 的有效期。
         *
         * yml 里写的是 PT2H，这是 ISO-8601 的时长格式：
         *      P     = Period 的前缀
         *      2H    = 2 小时
         * 其他例子：
         *      PT30M = 30 分钟（T 是"时间部分"的开始，必须写在时分秒前面）
         *      P7D   = 7 天（D 是"天"，属于日期部分，所以不加 T）
         *
         * Spring 会自动把这种字符串转成 Duration 对象，不用你手动 parse。
         *
         * ★ 为什么是 2 小时？
         *   access token 一旦签发，在过期前无法撤销（除非进黑名单）。
         *   时间越短，泄露的窗口越小。但太短会导致用户频繁刷新。
         *   2 小时是"安全"和"体验"的平衡点 —— 也是业界常见值。
         */
        @NotNull Duration accessTtl,

        /**
         * refresh token 的有效期。
         *
         * ★ 为什么比 access 长这么多？
         *   access 过期后，前端用 refresh 换一个新的 access，
         *   用户就不用重新输密码。refresh 管得久，用户就能"七天不用登录"。
         *
         * ★ refresh 这么长会不会不安全？
         *   会，所以有两道保险：
         *     1. refresh token 存在 Redis 白名单里，服务端可随时删除（登出、改密、禁用）
         *     2. 每次刷新都会"轮换"：旧的立刻作废，发新的。
         *        即使被偷，攻击者也只能用一次。
         */
        @NotNull Duration refreshTtl,

        /**
         * 签发者标识，写进 token 的 iss 字段。
         *
         * ★ 有什么用？
         *   如果将来有多个服务共用同一套 secret（不推荐，但可能出现），
         *   可以通过 iss 判断"这个 token 是哪个服务签的"。
         *   现在只有一个服务，纯粹是个规范字段，填什么都行。
         */
        String issuer
) {}
