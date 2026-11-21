package com.jianli.infra.redis;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  Redis key 的"集中命名中心"。所有 key 都从这里生成，不允许各处手写字符串。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么要把 key 集中管理？】
 *
 *  假设你到处手写：
 *      redis.get("refresh:" + userId + ":" + jti);
 *      redis.delete("refresh:" + userId + ":" + jti);       ← 少写个冒号？
 *      redis.delete("auth:refresh:" + userId + ":" + jti);  ← 多写个前缀？
 *
 *  这三处只要有一处拼错，就会出现"存进去了但删不掉"的 bug，
 *  而且是【静默的】—— 不报错，只是莫名其妙地登出不了。
 *
 *  集中之后：
 *      存：keys.refresh(userId, jti)
 *      删：keys.refresh(userId, jti)      ← 永远是同一个字符串
 *  从根上杜绝了拼写不一致。
 *
 *  【★ 集中管理还有这些好处】
 *      1. 排查时能一眼看出"这个前缀是谁写的、TTL 多久"（注释里写着）
 *      2. 想改命名规范只改一个文件
 *      3. 不会出现两个功能用同一个 key 互相覆盖
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 数据流向：这些 key 在哪儿被读写】
 *
 *      AuthService.login()
 *         └─ TokenService.saveRefresh(userId, jti, ttl)
 *               └─ redis SET auth:refresh:{userId}:{jti} "1" EX 7d
 *
 *      JwtAuthenticationFilter 每个请求都执行
 *         └─ TokenService.isBlacklisted(jti)       ← 一次 Redis GET，约 1-2ms
 *
 *      AuthService.logout()
 *         ├─ TokenService.blacklistAccess(jti, 剩余有效期)
 *         └─ TokenService.revokeRefresh(userId, jti)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【为什么是 final class + 私有构造器 + 全 static 方法？】
 *
 *  这是一个"工具类"，不装任何状态，只有一堆拼字符串的方法。
 *  这种类不应该被 new 出来用（new 出来也没有意义，因为每条 new 出来的实例都一样）。
 *
 *      final class      → 禁止别人继承它（继承工具类没有意义，只会带来麻烦）
 *      私有构造器       → 禁止别人 new 它
 *      static 方法      → 直接用类名调：RedisKeys.refresh(...)
 *
 *  ★ 私有构造器写个空方法体就行，作用是"堵住 new 的路"。
 *    不加的话，Java 会默认给一个 public 的无参构造器，别人就能 new。
 */
public final class RedisKeys {

    /**
     * 私有构造器。
     * 里面写注释说明"这是故意的，不是忘了写代码"，
     * 否则下一个看到的人会以为是个未完成的空类。
     */
    private RedisKeys() {
        // 工具类，禁止实例化
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【key 命名规范】
     *
     *  格式：业务域:类型:标识
     *      auth:refresh:1:abc-123
     *      │    │       │  │
     *      │    │       │  └─ jti（token 的唯一 id）
     *      │    │       └──── userId
     *      │    └──────────── 存的是什么
     *      └───────────────── 哪个模块
     *
     *  ★ 为什么用冒号分隔而不是下划线？
     *  这是 Redis 社区惯例。Redis Desktop Manager、RedisInsight 这类工具
     *  会把冒号分隔的 key 显示成【树状目录】，一层层展开看很清晰：
     *
     *      auth
     *        └─ refresh
     *             ├─ 1
     *             │   └─ jti-a
     *             └─ 7
     *                 └─ jti-b
     *
     *  用下划线的话就是一长串平铺，几十个 key 根本看不清。
     * ═══════════════════════════════════════════════════════════════
     */

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【refresh token 白名单】
     *
     *  实际 key：  auth:refresh:{userId}:{jti}
     *  存入的值：  "1"（值本身没意义，只关心 key 在不在）
     *  过期时间：  7 天（和 refresh token 的有效期一致）
     * ═══════════════════════════════════════════════════════════════
     *
     *  【★ 为什么需要"白名单"？JWT 不是号称无状态吗？】
     *
     *  这是个很好的矛盾点，面试常问。
     *
     *  JWT 的设计初衷是无状态：服务端不存任何东西，只验签就能知道你是谁。
     *  但这样有个致命问题：签发之后无法撤销。
     *      - 用户点了"退出登录" → token 还能用
     *      - 用户改了密码       → 旧 token 还能用
     *      - 账号被封禁         → token 还能用
     *  在过期之前（2 小时），这些都拦不住。
     *
     *  所以我们的做法是：JWT 管"身份"（无状态），Redis 管"撤销"（有状态）。
     *  两个配合，既有 JWT 的简洁，又有会话的可控。
     *
     *  【★ 那为什么叫"白名单"？和黑名单有什么区别？】
     *
     *  白名单：只承认登记过的 token，没登记的一律不认（默认拒绝）
     *  黑名单：默认都认，只有登记过的才不认（默认放行）
     *
     *  我们两个都用，各管一种 token：
     *
     *      refresh token 用【白名单】：
     *          有效期 7 天太长，必须严格管控。
     *          登录时登记，刷新时轮换，登出时删除。
     *          Redis 里没有 = 作废，哪怕它签名是对的。
     *
     *      access token 用【黑名单】：
     *          有效期只有 2 小时，风险窗口小。
     *          不做严格管控（否则每个请求都要查 Redis，2 小时就要存一次）。
     *          只在用户主动登出时拉黑那一个 jti。
     *
     *  【★ 为什么 key 里要带 userId？】
     *  为了能"批量删除某个用户的全部 refresh token"。
     *  改密码、禁用账号时，要把这个人在所有设备上的登录都踢掉，
     *  此时用 pattern 匹配：auth:refresh:{userId}:*
     *
     *  如果只按 jti 存（不带 userId），你根本不知道某个人的 token 是哪几个。
     *
     *  @param userId 用户 id
     *  @param jti    token 的唯一 id（JWT 标准字段，见 JwtTokenProvider 里的 .id(...)）
     *  @return 完整的 Redis key
     */
    public static String refresh(Long userId, String jti) {
        return "auth:refresh:" + userId + ":" + jti;
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【access token 黑名单】
     *
     *  实际 key：  auth:blacklist:{jti}
     *  存入的值：  "1"
     *  过期时间：  【该 token 的剩余有效期】
     * ═══════════════════════════════════════════════════════════════
     *
     *  【★ 关键设计：TTL 为什么是"剩余有效期"而不是固定值？】
     *
     *  因为 token 一旦自然过期，黑名单里再存它就没有意义了 ——
     *  验签那一步就会失败，根本走不到查黑名单。
     *
     *  举例：
     *      用户在 10:00 登录，拿到 access token（12:00 过期）
     *      用户在 10:30 点退出登录
     *      → 这个 token 还剩 1.5 小时
     *      → 黑名单 TTL 设 1.5 小时
     *      → 12:00 之后这条记录自动消失（此时 token 本身也失效了）
     *
     *  ★ 如果 TTL 写死成 7 天呢？
     *  黑名单会无限膨胀 —— 每次登出都留一条记录，存到 Redis 里，
     *  而且那条记录在 token 过期后 6 天半都是纯垃圾。
     *
     *  ★ 这就是"让 TTL 和数据生命周期对齐"的典型做法：
     *  不需要额外的清理任务，Redis 自动回收。
     *
     *  【★ 为什么 key 里只有 jti，没有 userId？】
     *  因为判断"这个 token 是否被拉黑"时，只有 token 本身，拿不到 userId
     *  （要先验签才知道是谁，而验签后 jti 已经有了）。
     *  而且登出只影响这一个 token，不需要批量操作。
     *
     *  @param jti token 的唯一 id
     *  @return 完整的 Redis key
     */
    public static String blacklist(String jti) {
        return "auth:blacklist:" + jti;
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【后面步骤会往这里加什么】
     *
     *  步骤 7/10 - AI 缓存：
     *      cache:jdparse:{hash}          JD 解析结果，TTL 7 天
     *      cache:resumeparse:{hash}      简历解析结果，TTL 30 天
     *      cache:embedding:{hash}        向量，TTL 30 天
     *
     *  步骤 8 - 分析：
     *      cache:analysis:{cacheKey}     完整报告，TTL 24 小时
     *      lock:analysis:{cacheKey}      并发去重锁，TTL 120 秒
     *
     *  步骤 13 - 社区：
     *      zset:post:hot                 热度榜（ZSET，无 TTL，定时重建）
     *      stats:post:view               浏览数缓冲（HASH，定时回写）
     *
     *  限流：
     *      rate:login:{ip}               登录限流
     *      rate:post:{userId}            发帖限流
     *
     *  ★ 到时候照这里的格式加方法即可，保持"key 只从本类生成"的规矩。
     * ═══════════════════════════════════════════════════════════════
     */

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【帖子热度榜】ZSET
     * ═══════════════════════════════════════════════════════════════
     *
     * 类型：ZSET（有序集合）
     * member：postId（字符串）
     * score： 热度分（double）
     *
     * 【★ 为什么用 ZSET 而不是 List 或普通 Set？】
     *
     *  ZSET 天生就是"按分数排序的集合"：
     *      ZADD key score member   → 写入/更新，O(log N)
     *      ZREVRANGE key 0 9       → 取分数最高的前 10 个，O(log N + M)
     *
     *  ★ 对比其他方案：
     *     List  → 要自己维护顺序，每次插入都要重新排序
     *     Set   → 无序，取"前 10"要全量取出再排序
     *     MySQL → 带筛选条件时可以用，但"全站热门"这种无筛选查询
     *             走 ZSET 比 ORDER BY 快得多（且不占数据库连接）
     *
     * 【★ TTL 怎么设？】
     *  ★ 不设 TTL（永久）。
     *    因为热度是"持续维护"的 —— 投票、评论会更新分数，
     *    定时任务会重算衰减。如果设 TTL，榜单会周期性消失，
     *    用户会看到"热门榜空了"。
     *
     *  ★ 不设 TTL 的风险是 ZSET 无限增长（每个帖子一个 member）。
     *    本项目用定时任务清理 30 天前的帖子，见 HotScoreRebuildJob。
     */
    public static String postHotZset() {
        return "zset:post:hot";
    }

    /**
     * 【帖子浏览计数缓冲】HASH
     *
     * field：postId
     * value：待回写的增量（如 "37" 表示这段时间内被浏览 37 次）
     *
     * 【★ 为什么要缓冲而不是每次直接 UPDATE？】
     *
     *  详情页每打开一次就 `UPDATE post SET view_count = view_count + 1`，
     *  在热门帖上会造成大量行锁竞争（同一行的并发更新要排队）。
     *
     *  ★ 缓冲方案：
     *      每次浏览 → Redis HINCRBY（原子自增，无锁竞争）
     *      每 5 分钟 → 批量回写 MySQL（一次 UPDATE 处理所有帖子）
     *
     *  ★ 代价：浏览数最多延迟 5 分钟。
     *    浏览数是展示性指标，这个延迟完全可以接受。
     */
    public static String postViewBuffer() {
        return "stats:post:view";
    }

    /**
     * 【单个帖子的热度重算锁】SET NX EX
     *
     * ★ 为什么需要它？
     *   投票时先改数据库计数，再调"重算热度 → 写 ZSET"。
     *   如果两个用户同时投票，两个请求都会重算 —— 结果一样（幂等），
     *   但会多一次 Redis 写入。
     *
     *  ★ 这个锁是可选的优化，不是正确性保障。
     *    本项目【不加锁】——因为重算是幂等的，多算一次无害。
     *      *     加锁反而增加复杂度和失败点。
     *
     *  ★ 这里保留方法只是为了让"Redis key 集中管理"的规矩完整，
     *    实际代码没有使用。如果将来热度计算变复杂（比如要查多次库），
     *    再用它。
     */
    public static String postHotLock(Long postId) {
        return "lock:post:hot:" + postId;
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么"全站热度"用 ZSET，"带筛选的热度"用 MySQL？】
     *
     *  场景 A：社区首页按热度看全部帖子
     *       ZREVRANGE zset:post:hot 0 9
     *       → 一次 Redis 调用，O(log N + 10)，不碰数据库
     *
     *  场景 B：筛选"Java 后端 + 3-5 年"再按热度排
     *       ★ ZSET 里只有 postId 和分数，没有岗位/年限信息，
     *         无法在 Redis 侧做筛选。
     *       → 只能用 MySQL：WHERE position=? AND exp BETWEEN ? AND ?
     *                       ORDER BY hot_score DESC
     *       → 所以 post 表冗余了 hot_score 列，并且投票时会同步更新。
     *
     *  ★ 一句话：ZSET 服务"无筛选的排行榜"，MySQL 列服务"带筛选的排序"。
     *    两者数据来源相同（同一套热度公式），只是存储位置不同。
     * ═══════════════════════════════════════════════════════════════
     */
}
