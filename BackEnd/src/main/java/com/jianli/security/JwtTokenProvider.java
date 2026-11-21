package com.jianli.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  负责"生成 token"和"解析 token"。整个项目只有这一个类碰 JWT 的底层细节。
 *
 *  你可以把它理解成"盖章机"：
 *      createAccess()  → 写一张凭证，盖上防伪章
 *      parse()         → 验章，验过了就把凭证内容读出来
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：token 的一生】
 *
 *      ① 用户登录，AuthService 调 createAccess(userId, username)
 *            ↓ 用 secret 签名，生成一串 "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.xxx"
 *            ↓
 *      ② 返回给前端，前端存进 localStorage
 *            ↓
 *      ③ 之后每个请求都带上：Authorization: Bearer eyJhbGci...
 *            ↓
 *      ④ JwtAuthenticationFilter 拦截请求，调 parse(token)
 *            ↓ 验签名、验过期
 *            ↓ 通过就拿到底部 Claims（里面装着 userId、jti、type）
 *            ↓
 *      ⑤ 把 userId 放进 SecurityContext，Controller 就能拿到"当前用户是谁"
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ JWT 到底长什么样？】
 *
 *  它是由"点"分隔的三段 base64 文本：
 *
 *      eyJhbGciOiJIUzI1NiJ9  .  eyJzdWIiOiIxIiwiZXhwIjoxNzY1fQ  .  4kX9p2mQ...
 *      └───── 头部 ─────┘     └──────── 载荷 ────────┘     └── 签名 ──┘
 *
 *  头部（Header）：算法信息，比如 {"alg":"HS256"}
 *  载荷（Payload）：真正的内容，比如 {"sub":"1","username":"alice","jti":"xxx","exp":1765000000}
 *  签名（Signature）：用 secret 对前两段算出来的 HMAC
 *
 *  ★★ 最关键的一点：载荷是【明文】的，只是 base64 编码，不是加密！
 *      任何人拿到 token 都能解开看到里面的 userId 和 username。
 *      打开 jwt.io 粘贴进去就能看到。
 *
 *      所以：绝对不能把密码、身份证、手机号之类的敏感信息放进 token。
 *      至于"为什么没人能伪造"，靠的是第三段签名 ——
 *      改了前两段的任何内容，签名就对不上，服务端验签会失败。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【@Component 是什么】
 *  注册成 Spring Bean，这样 AuthService、JwtAuthenticationFilter
 *  就能通过构造器把它注入进去用。
 *
 *  为什么不加 @Service？
 *  @Service 和 @Component 功能上完全一样，只是语义不同：
 *      @Service      → "我是业务逻辑"
 *      @Component    → "我是个通用组件"
 *  JWT 工具不属于业务逻辑，用 @Component 更准确。
 */
@Component
public class JwtTokenProvider {

    /**
     * token 类型的两个取值。
     *
     * ★ 为什么要区分 type？
     *   我们签发两种 token：access 和 refresh。它们的"能力"完全不同：
     *      access  → 可以用来访问接口（2 小时）
     *      refresh → 只能用来换新的 access（7 天）
     *
     *   如果不区分，用户可以拿 refresh token 直接访问接口 ——
     *   而 refresh 有 7 天有效期，等于变相延长了 access 的生命期，
     *   短有效期的安全设计就白做了。
     *
     *   ★ 所以 JwtAuthenticationFilter 里会检查：
     *       if (!TYPE_ACCESS.equals(type)) { 不认 }
     *
     * 定义成 public static final 常量，其他地方引用它就不会拼错字符串。
     */
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;

    /**
     * 用于签名的密钥对象。
     *
     * ★ 为什么在构造器里算一次，而不是每次签名时再算？
     *   因为 Keys.hmacShaKeyFor(...) 内部会做校验（长度够不够 256 位），
     *   而且创建对象本身有开销。签名和验签是高频操作（每个请求一次），
     *   所以只建一次，之后复用。这也是它被声明成 final 的原因。
     */
    private final SecretKey key;

    /**
     * 构造器注入。
     *
     * ★ Spring 是怎么调用的？
     *   本类只有一个构造器，Spring 会自动用容器里的 JwtProperties bean 来调它。
     *   这就是"构造器注入" —— 比字段上加 @Autowired 更好，因为：
     *     1. 字段可以声明成 final（不可变，线程安全）
     *     2. 依赖关系一眼可见（看构造器就知道需要什么）
     *     3. 测试时可以直接 new JwtTokenProvider(mockProps)
     *
     * ★ Keys.hmacShaKeyFor(byte[]) 做了什么？
     *   把任意字节数组包装成一个 HMAC-SHA 密钥对象。
     *   如果长度不足 256 位（32 字节），它会抛 WeakKeyException ——
     *   这正好帮我们兜住了"secret 配得太短"的问题。
     */
    public JwtTokenProvider(JwtProperties props) {
        this.props = props;
        // getBytes(UTF_8) 很重要：不指定字符集的话，中文会按平台默认编码转换，
        // 导致不同机器算出的密钥不一样，token 就互相验不过了
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【生成 access token】有效期 2 小时，可用于访问接口
     * ═══════════════════════════════════════════════════════════════
     *
     * @param userId   用户 id
     * @param username 用户名（放进 token 是为了方便日志排查，
     *                 真正判断身份用的是 userId）
     * @return 形如 "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.xxx" 的字符串
     */
    public String createAccess(Long userId, String username) {
        return build(userId, username, TYPE_ACCESS, props.accessTtl());
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【生成 refresh token】有效期 7 天，只能用来换新 token
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 除了 ttl 和 type，其他和 access 完全一样。
     *   所以抽象的 build() 方法把公共部分抽出来了。
     */
    public String createRefresh(Long userId, String username) {
        return build(userId, username, TYPE_REFRESH, props.refreshTtl());
    }

    /**
     * 两种 token 的共同构造逻辑。
     *
     * ★ 为什么抽成私有方法？
     *   如果 createAccess 和 createRefresh 各写一遍，
     *   哪天要加一个 claim（比如设备 id），就得改两处，容易漏。
     *   抽出来之后只改一处。
     */
    private String build(Long userId, String username, String type, Duration ttl) {
        Instant now = Instant.now();

        return Jwts.builder()

                /*
                 * 【.id(...)】设置 jti（JWT ID），一个唯一标识符。
                 *
                 * ★★ 这是整个撤销机制的基石，务必理解：
                 *
                 *   为什么不能直接用 token 字符串本身做黑名单的 key？
                 *     1. token 有几百个字符，做 Redis key 太长，浪费内存
                 *     2. 每次查询都要拿完整 token 去匹配，不方便
                 *
                 *   用 jti 就解决了：一个 36 字符的 UUID，短且唯一。
                 *   服务端只需要记"jti=xxx 已作废"，
                 *   验 token 时取出它的 jti 去查一下就知道有没有被撤销。
                 *
                 * ★ UUID.randomUUID() 保证每次生成都不一样，
                 *   所以同一用户连续登录两次，拿到的是两个不同的 token，
                 *   互不影响（可以分别登出）。
                 */
                .id(UUID.randomUUID().toString())

                /*
                 * 【.subject(...)】设置 sub（Subject），JWT 标准字段，
                 * 表示"这个 token 是谁的"。
                 *
                 * ★ 为什么存 userId 而不是 username？
                 *   userId 是数字主键，永不变更；username 理论上可以改
                 *   （虽然本项目不支持，但设计上更稳妥）。
                 *   而且 userId 更短。
                 *
                 * ★ 为什么 String.valueOf(userId)？
                 *   因为 JWT 规范要求 sub 是字符串类型。
                 */
                .subject(String.valueOf(userId))

                /*
                 * 【.claim(...)】自定义字段，随便加。
                 *
                 * username：方便日志排查（看到日志里能直接知道是谁）
                 * type    ：区分 access / refresh（见上面 TYPE_ACCESS 的说明）
                 *
                 * ★ 注意：这些都明文可见！不要放敏感信息。
                 */
                .claim("username", username)
                .claim("type", type)

                /* 签发者标识，来自配置 */
                .issuer(props.issuer())

                /*
                 * 【.issuedAt(...)】签发时间（iat 字段）。
                 * 一般是 base64 解码后能看到的数字时间戳。
                 */
                .issuedAt(Date.from(now))

                /*
                 * 【.expiration(...)】过期时间（exp 字段）。
                 *
                 * ★ 这是 JWT 自带的过期机制：
                 *   验签时 jjwt 会自动检查 exp，过期了直接抛 ExpiredJwtException。
                 *   我们不需要自己写"判断是否过期"的代码。
                 *
                 * ★ 但要注意：过期检查和签名检查是【同时】做的，
                 *   所以过期 token 和伪造 token 抛的是不同异常类型 ——
                 *   这一点在 JwtAuthenticationFilter 里可以区分处理。
                 */
                .expiration(Date.from(now.plus(ttl)))

                /*
                 * 【.signWith(key)】签名。
                 *
                 * ★ 这是 JWT 的灵魂：用密钥对"头部+载荷"算一个 HMAC-SHA256，
                 *   拼成第三段。
                 *
                 *   验证方用同一个密钥重算一遍，对得上就说明内容没被篡改过。
                 *   攻击者不知道密钥，改了内容就必然算不出正确的签名。
                 *
                 * ★ 这一步之后，token 就不能再修改了 ——
                 *   哪怕改一个字，签名都会对不上。
                 */
                .signWith(key)

                /*
                 * 【.compact()】把三部分拼成一个 "a.b.c" 的字符串。
                 * 前面所有配置都只是在构建一个对象，这一步才真正产出 token。
                 */
                .compact();
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【解析并验证 token】
     * ═══════════════════════════════════════════════════════════════
     *
     * 一次调用做了三件事：
     *   1. 用密钥验证签名（内容有没有被篡改）
     *   2. 检查过期时间（有没有超时）
     *   3. 检查格式（是不是合法的 JWT）
     *
     * 全部通过 → 返回 Claims（token 里的内容）
     * 任何一项失败 → 抛异常
     *
     * ★ 抛出的异常类型（调用方可以据此区分原因）：
     *      ExpiredJwtException     → 过期了
     *      SignatureException      → 签名不对（伪造或密钥换了）
     *      MalformedJwtException   → 格式不是 JWT
     *      IllegalArgumentException → 空字符串等
     *   它们都继承自 JwtException，所以调用方一般统一 catch JwtException 即可。
     *
     * ★ 为什么是 parseSignedClaims 而不是旧版的 parseClaimsJws？
     *   jjwt 0.12 版本重命名了 API。网上大量旧教程还是 0.9 的写法
     *   （setSigningKey / parseClaimsJws），照抄会编译不过。
     *   我们已经把 jjwt 锁在 0.12.6，请用这里的新 API。
     *
     * @param token 完整的 token 字符串
     * @return Claims，可以通过它取 sub / jti / claim 等
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)          // 指定验签用的密钥
                .build()
                .parseSignedClaims(token) // 解析 + 验签 + 验过期，一步到位
                .getPayload();            // 取出载荷部分（0.12 里叫 payload，旧版叫 body）
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【下面几个是"读取 Claims 内容"的便捷方法】
     *
     *  为什么单独封装，而不是调用方自己写 claims.getSubject()？
     *    1. 把"字段名 → 类型"的转换收在一处。
     *       sub 存的是字符串 "1"，但业务要的是 Long 1。
     *       每个调用方都写 Long.valueOf(claims.getSubject()) 太啰嗦也容易忘。
     *    2. 以后如果换了字段名（比如改成从自定义 claim 里读 id），
     *       只改这里，不用改十几个调用点。
     * ═══════════════════════════════════════════════════════════════
     */

    /** 取出用户 id。sub 存的是字符串，这里转成 Long 给业务用 */
    public Long userId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    /** 取出 jti（token 唯一 id）。黑名单和白名单都靠它 */
    public String jti(Claims claims) {
        return claims.getId();
    }

    /** 取出 token 类型（access / refresh） */
    public String type(Claims claims) {
        return claims.get("type", String.class);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【计算剩余有效期】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 用在哪：用户登出时，要把 access token 拉黑。
     *   黑名单的 TTL 应该等于"这个 token 还剩多久过期"，
     *   因为过期之后它自然就无效了，黑名单记录也没用了（详见 RedisKeys.blacklist 的说明）。
     *
     * ★ 计算方式：
     *      exp（过期时刻，毫秒） - 当前时刻（毫秒） = 剩余毫秒
     *      再除以 1000 得到秒数（Redis 的 TTL 单位是秒）
     *
     * ★ 为什么要 Math.max(seconds, 1)？
     *   边界情况：如果 token 刚好在这一毫秒过期，算出来可能是 0 或负数。
     *   Redis 的 EX 参数不能是 0 或负数（会直接报错），所以兜底成 1 秒。
     *   反正它马上就过期了，存 1 秒和存 0 秒效果一样。
     */
    public Duration remainingTtl(Claims claims) {
        long seconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        return Duration.ofSeconds(Math.max(seconds, 1));
    }
}
