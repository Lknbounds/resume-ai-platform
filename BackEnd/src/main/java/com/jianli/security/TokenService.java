package com.jianli.security;

import com.jianli.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  管理 "哪些 token 还有效" 的账本。它不碰 JWT 本身，只管 Redis 里那几条记录。
 *
 *  两个账本：
 *      白名单（refresh）→ 登记过的 refresh token 才算数
 *      黑名单（access） → 登记过的 access token 立刻作废
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：三个场景，看清楚谁调谁】
 *
 *  ── 场景一：登录 ──
 *      AuthService.login()
 *         └─ tokenService.saveRefresh(userId, jti, 7天)
 *               └─ Redis SET auth:refresh:{userId}:{jti} "1" EX 604800
 *         （同时把新 token 返回给前端）
 *
 *  ── 场景二：每个请求 ──
 *      JwtAuthenticationFilter
 *         └─ tokenService.isBlacklisted(jti)
 *               └─ Redis EXISTS auth:blacklist:{jti}
 *               → true  → 认定 token 已作废，不设置认证信息
 *               → false → 正常放行
 *
 *  ── 场景三：登出 ──
 *      AuthService.logout(accessToken, refreshToken)
 *         ├─ tokenService.blacklistAccess(jti, 剩余有效期1.5小时)
 *         │     └─ Redis SET auth:blacklist:{jti} "1" EX 5400
 *         └─ tokenService.revokeRefresh(userId, jti)
 *               └─ Redis DEL auth:refresh:{userId}:{jti}
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么用 StringRedisTemplate 而不是 RedisTemplate？】
 *
 *  RedisTemplate（不带 String）默认用 JDK 序列化 —— key 和 value 会被序列化成一堆
 *  二进制字节，你在 redis-cli 里看到的是：
 *      "\xac\xed\x00\x05t\x00\rauth:refresh:1"
 *  完全没法读，也没法手动排查。
 *
 *  StringRedisTemplate 是它的子类，指定了"key 和 value 都按字符串处理"，所以：
 *      redis-cli> GET auth:refresh:1:abc-123
 *      "1"                                  ← 干净可读
 *
 *  ★ 我们存的都是字符串（"1"、id 等），所以用 StringRedisTemplate 最合适。
 *    如果要存对象（比如把报告 JSON 存 Redis），也是先自己转成 JSON 字符串再存，
 *    依然用 StringRedisTemplate。
 *
 *
 *  【@Service 和 @Slf4j 是什么】
 *  @Service  → 注册成 Bean（语义上表示"这是个业务服务"）
 *  @Slf4j    → Lombok 自动生成 log 对象，可以直接 log.warn(...)
 *
 *  【@RequiredArgsConstructor 是什么】
 *  Lombok 为所有 final 字段生成一个构造器。
 *  因为只有一个构造器，Spring 会自动用它做"构造器注入"。
 *
 *  ★ 这比在字段上写 @Autowired 更好：
 *      1. 字段可以是 final（不可变）
 *      2. 依赖关系在构造器签名上一目了然
 *      3. 写测试时可以直接 new TokenService(mockRedis)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    /**
     * Spring 自动注入的 Redis 客户端。
     *
     * ★ 它从哪来？spring-boot-starter-data-redis 这个依赖会自动配置一个
     *   StringRedisTemplate bean，连的是 application.yml 里配的
     *   spring.data.redis.host/port（默认 localhost:6379）。
     *
     * ★ 所以本类能工作的前提是：Redis 必须在跑。
     *   没启动的话，调用任何方法都会抛 RedisConnectionFailureException。
     *   启动方式：双击 start-redis.bat
     */
    private final StringRedisTemplate redis;

    // ═══════════════════════════════════════════════════════════════
    //  【一、refresh token 白名单】
    // ═══════════════════════════════════════════════════════════════

    /**
     * 登录/刷新成功后，把 refresh token 登记进白名单。
     *
     * ★ 为什么登录时一定要存？
     *   因为后面的 isRefreshValid 会检查"这个 token 在不在名单里"。
     *   不存的话，用户拿回来的 refresh token 永远刷不出新 token。
     *
     * ★ TTL 为什么必须设？
     *   Redis 的 key 如果不设过期时间，会永久留存在内存里。
     *   用户量大了之后，几万个永远不会被访问的 key 会撑爆内存。
     *   设成和 token 一样的 7 天，token 失效时记录也自动消失。
     *
     * @param userId 用户 id
     * @param jti    token 唯一 id
     * @param ttl    存活时间（= refresh token 的有效期）
     */
    public void saveRefresh(Long userId, String jti, Duration ttl) {
        /*
         * opsForValue() → 操作"字符串类型"的数据（Redis 最基础的类型）
         * set(key, value, duration) → SET key value EX seconds
         *
         * ★ 值为什么是 "1"？
         *   我们只关心"这个 key 存不存在"，不关心它存了什么。
         *   存 "1" 最省内存（其实存空字符串也行，但有些客户端对空值处理不一致，
         *   存个固定值最保险）。
         */
        redis.opsForValue().set(RedisKeys.refresh(userId, jti), "1", ttl);
        log.debug("refresh token 已登记, userId={}, jti={}", userId, jti);
    }

    /**
     * 检查 refresh token 是否还在白名单里。
     *
     * ★ 什么时候返回 false？
     *   1. 用户主动登出 → 我们删掉了记录
     *   2. 刷新时轮换 → 旧的被删，只有最新的有效
     *   3. 改密码/被禁用 → revokeAllRefresh 清空了这个人的所有记录
     *   4. 超过 7 天 → Redis 自动删除了这条记录
     *
     * ★ 为什么用 hasKey 而不是 get？
     *   hasKey → EXISTS key，只判断存不存在，不取值
     *   get    → GET key，把值也读出来
     *   我们不需要值，所以用更轻量的 hasKey。
     *
     * ★ 为什么写 Boolean.TRUE.equals(...)？
     *   hasKey 返回的是 Boolean（包装类型），可能是 null
     *   （虽然实际不会，但编译器的类型是 Boolean）。
     *   直接写 if (redis.hasKey(...)) 会有 NPE 风险（自动拆箱）。
     *   Boolean.TRUE.equals(x) 是安全写法：null 时返回 false，不会抛异常。
     */
    public boolean isRefreshValid(Long userId, String jti) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.refresh(userId, jti)));
    }

    /**
     * 让某个 refresh token 作废（从白名单里删掉）。
     *
     * ★ 用在哪：
     *   1. 登出时删掉当前这一个
     *   2. 刷新时"轮换" —— 删掉旧的，存新的（见 AuthService.refresh）
     *
     * ★ 删除之后，这个 token 即便签名正确、也没过期，
     *   也会因为"不在白名单里"而被拒绝。这是白名单机制的核心。
     */
    public void revokeRefresh(Long userId, String jti) {
        redis.delete(RedisKeys.refresh(userId, jti));
        log.debug("refresh token 已作废, userId={}, jti={}", userId, jti);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【踢下线：清掉这个用户的所有 refresh token】
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 用在哪：
     *   1. 改密码 → 所有设备强制重新登录（这是安全惯例：
     *      万一密码已经泄露，攻击者的会话也一并失效）
     *   2. 二期禁用账号 → 立即踢出所有在线设备
     *
     * ★ 怎么找到这个人的所有 token？
     *   用 keys("auth:refresh:7:*") 做模式匹配，匹配出所有 key 再批量删除。
     *
     * ★★ 重要提醒：keys 命令在 key 数量多时会【阻塞 Redis】！
     *
     *   Redis 是单线程的。KEYS 会遍历整个数据库，几百万个 key 时会卡住几百毫秒，
     *   期间所有其他请求都被阻塞。生产环境一般禁用 KEYS，改用 SCAN 分批遍历。
     *
     *   ★ 为什么我们这个项目可以用？
     *     本项目用户量小，key 数量在几千级别，KEYS 耗时 1ms 以内，完全可接受。
     *     而且这个操作只在"改密码"时触发，频率极低。
     *
     *   ★ 如果要改成生产级写法，用 SCAN：
     *       var cursor = ScanOptions.scanOptions().match(pattern).count(100).build();
     *       try (var it = redis.scan(cursor)) {
     *           List<String> batch = new ArrayList<>();
     *           while (it.hasNext()) { batch.add(it.next()); if (batch.size() >= 100) { redis.delete(batch); batch.clear(); } }
     *       }
     *     代码复杂不少，所以本项目保留简单的 KEYS，但在注释里说明取舍 ——
     *     面试时主动说出"我知道 KEYS 会阻塞，但当前数据量下可接受"是加分项。
     */
    public void revokeAllRefresh(Long userId) {
        // "auth:refresh:7:*" 能匹配该用户的所有 token
        Set<String> keys = redis.keys("auth:refresh:" + userId + ":*");

        // ★ 为什么要判空？
        //   keys() 没找到时返回空 Set（不是 null），但某些 Redis 客户端实现在
        //   连接异常时会返回 null。判一下更稳。
        //   而且 redis.delete(空集合) 在某些版本会报错，所以干脆不调。
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.info("已清除用户全部 refresh token, userId={}, count={}", userId, keys.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  【二、access token 黑名单】
    // ═══════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【把 access token 拉黑】登出时调用
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 为什么 access 需要黑名单，而不是像 refresh 那样用白名单？
     *
     *   如果用白名单，每个请求都要查一次"这个 access token 登记过吗"。
     *   但 access token 数量 = 用户数 × 登录次数，量非常大；
     *   而且每次登录都要写一条，写入压力也大。
     *
     *   用黑名单更划算：
     *     - 正常情况下名单是【空的】（只有登出过的 token 才在里面）
     *     - 绝大多数请求查一次 EXISTS 就返回 false，非常快
     *     - 只有主动登出才写一条
     *
     * ★ TTL 为什么必须是"剩余有效期"而不是固定值？
     *   见 RedisKeys.blacklist 的详细说明 —— 简单说：
     *   token 自然过期后就无效了，黑名单留着是垃圾。
     *
     * @param jti        token 唯一 id
     * @param remaining  这个 token 还剩多久过期（由 JwtTokenProvider.remainingTtl 算出来）
     */
    public void blacklistAccess(String jti, Duration remaining) {
        redis.opsForValue().set(RedisKeys.blacklist(jti), "1", remaining);
        log.debug("access token 已加入黑名单, jti={}, 剩余={}", jti, remaining);
    }

    /**
     * 检查 access token 是否已被拉黑。
     *
     * ★ 调用频率：每个带 token 的请求都会调一次。
     *   这是本类最热的方法，所以用最轻的 EXISTS。
     *
     * ★ Redis 挂了会怎样？
     *   抛异常 → JwtAuthenticationFilter 里 catch 后当作"未登录"处理 → 返回 401。
     *   这是"宁可拒绝也不放行"的安全选择。
     *   （另一种选择是 catch 后放行，可用性更好但安全性更差，本项目选前者。）
     */
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.blacklist(jti)));
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 一个小结：为什么"白名单 + 黑名单"要混着用？】
     *
     *  ┌──────────┬──────────┬──────────┬─────────────────────────┐
     *  │ token    │ 机制     │ 默认状态 │ 原因                     │
     *  ├──────────┼──────────┼──────────┼─────────────────────────┤
     *  │ refresh  │ 白名单   │ 拒绝     │ 有效期长(7天)，必须严格   │
     *  │ access   │ 黑名单   │ 放行     │ 有效期短(2小时)，量太大   │
     *  └──────────┴──────────┴──────────┴─────────────────────────┘
     *
     *  一句话：有效期越长、越需要严格管控的，用白名单；
     *          有效期短、量大的，用黑名单。
     *
     *  ★ 面试如果问"JWT 无状态怎么实现登出"，就答这个：
     *    "access 用 jti 黑名单 + TTL 对齐剩余有效期，refresh 用 Redis 白名单 + 轮换，
     *     这样既能实现立即失效，又不会让 Redis 无限膨胀。"
     * ═══════════════════════════════════════════════════════════════
     */
}
