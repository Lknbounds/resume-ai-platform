package com.jianli.community.hot;

import com.jianli.community.post.entity.Post;
import com.jianli.infra.redis.RedisKeys;
import com.jianli.infra.redis.RedisSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  帖子热度计算 + Redis ZSET 维护。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 热度公式（面试必问）】
 *
 *      base = max(0, 赞 - 踩) × 1.0
 *           + 评论数 × 2.0
 *           + log10(浏览数 + 1) × 3.0
 *
 *      hot = base / (发布至今的小时数 + 2) ^ 1.5
 *
 *  【★ 为什么这样设计？逐个因子拆解】
 *
 *  ① (赞 - 踩)
 *     核心质量信号。用差值而不是只看赞 —— 踩也是有效信息。
 *     max(0, ...) 兜底：踩多于赞时不让它变负数（会被时间衰减"反向放大"）。
 *
 *  ② 评论 × 2（权重是赞的 2 倍）
 *     ★ 写评论的成本远高于点赞。
 *       点赞是一次点击，评论要思考 + 打字。
 *       "愿意花时间写建议"是比"点个赞"更强的质量信号。
 *
 *  ③ log10(浏览 + 1) × 3
 *     ★ 为什么用 log 而不是直接加？
 *       浏览数可能是点赞的 100 倍（1000 浏览 vs 10 赞）。
 *       直接加会让"浏览量"完全主导热度，等于鼓励标题党。
 *       log10 压缩量级：100 浏览 → 2，1000 浏览 → 3，只差 1。
 *
 *       ★ +1 是为了避免 log10(0) = -∞（没有浏览时）。
 *
 *  ④ 分母 (hours + 2) ^ 1.5 —— ★ 时间衰减
 *
 *     ★ 为什么必须衰减？
 *       不衰减的话，三年前的热帖永远压着新帖，社区会"死掉"。
 *
 *     ★ 为什么是 1.5 次方？
 *       线性衰减（^1）太慢：老帖沉不下去。
 *       平方衰减（^2）太快：昨天发的帖今天就没人看了。
 *       1.5 是社区产品常用的平衡点（Hacker News 用类似的曲线）。
 *
 *     ★ 为什么 +2？
 *       1. 避免刚发布时 hours≈0，分母≈0，分数爆炸
 *       2. 给新帖一点冷启动优势（分母小 → 分数高），
 *          让刚发的好内容有机会被看到
 *
 *  【★★ 数值举例（让你直观感受）】
 *
 *  帖子 A：刚发 1 小时，10 赞，5 评论，100 浏览
 *      base = 10 + 10 + log10(101)×3 = 10 + 10 + 6 = 26
 *      hot  = 26 / (1+2)^1.5 = 26 / 5.2 = 5.0
 *
 *  帖子 B：发了 3 天（72 小时），50 赞，20 评论，2000 浏览
 *      base = 50 + 40 + log10(2001)×3 = 50 + 40 + 10 = 100
 *      hot  = 100 / (72+2)^1.5 = 100 / 637 = 0.157
 *
 *  ★ 结论：刚发 1 小时的小帖（5.0）远高于 3 天前的大热帖（0.157）。
 *    这正是想要的 —— 让新鲜内容有机会和数据积累的老帖竞争。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotScoreService {

    /** 赞的权重 */
    private static final double W_UP = 1.0;

    /** 评论的权重（是赞的 2 倍 —— 评论成本更高） */
    private static final double W_COMMENT = 2.0;

    /** 浏览的权重（配合 log10 压平） */
    private static final double W_VIEW = 3.0;

    /** 时间衰减指数 */
    private static final double DECAY_POWER = 1.5;

    /** 分母的保底值（避免刚发布时除零） */
    private static final double HOURS_OFFSET = 2.0;

    private final StringRedisTemplate redis;
    private final RedisSafe redisSafe;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【计算热度分】★ 纯函数，可单测
     * ═══════════════════════════════════════════════════════════════
     *
     * @param post 帖子（需要 upCount / downCount / commentCount / viewCount / createdAt）
     * @return 热度分
     */
    public double compute(Post post) {
        // ───── ① 基础分 ─────
        int netVotes = Math.max(0, post.getUpCount() - post.getDownCount());

        double base = netVotes * W_UP
                + post.getCommentCount() * W_COMMENT
                + Math.log10(post.getViewCount() + 1) * W_VIEW;

        // ───── ② 时间衰减 ─────
        double hours = hoursSince(post.getCreatedAt());

        return base / Math.pow(hours + HOURS_OFFSET, DECAY_POWER);
    }

    /**
     * 发布至今的小时数。
     *
     * ★ 为什么要 clamp 到 0？
     *   如果服务器时间和数据库时间有微小偏差，
     *   createdAt 可能"在未来"几毫秒 → hours 是负数 →
     *   分母 < HOURS_OFFSET，分数会被放大。
     *   兜底成 0 更安全。
     */
    private double hoursSince(LocalDateTime createdAt) {
        if (createdAt == null) return 0;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long seconds = java.time.Duration.between(createdAt, now).getSeconds();

        return Math.max(0, seconds / 3600.0);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【更新热度】写 ZSET
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 什么时候调？
     *   1. 发帖后（新帖初始热度）
     *   2. 投票后（赞/踩变化）
     *   3. 评论后（评论数变化）
     *   4. 定时任务（时间衰减需要周期性重算）
     *
     * ★ 注意：这个方法【不修改 post.hotScore 字段】——
     *   字段的更新由调用方负责（在同一事务里）。
     *   本方法只负责 Redis 侧。
     *
     *   ★ 为什么不在这里一起改数据库？
     *     职责单一：Redis 的事归 Redis，数据库的事归数据库。
     *     而且 ZSET 更新失败不该导致数据库事务回滚。
     */
    public void updateZset(Long postId, double hotScore) {
        redisSafe.execute("更新热度榜",
                () -> {
                    redis.opsForZSet().add(
                            RedisKeys.postHotZset(),
                            String.valueOf(postId),
                            hotScore);
                    return null;
                },
                () -> null);
    }

    /**
     * 计算 + 写 ZSET + 返回热度分。
     *
     * ★ 这是给调用方用的便捷方法：
     *   调用方拿到返回值后自己 set 到实体上。
     */
    public double refresh(Post post) {
        double hot = compute(post);
        updateZset(post.getId(), hot);
        return hot;
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【取热度榜前 N 个帖子 id】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param offset 跳过前多少个（分页用）
     * @param limit  取多少个
     * @return postId 列表，按热度从高到低
     *
     * ★ 返回空列表有几种可能：
     *   1. Redis 里还没有数据（刚部署、ZSET 还没建立）
     *   2. Redis 挂了（降级）
     *   3. 确实没有帖子
     *   ★ 调用方不用区分 —— 统一回退到 MySQL 排序即可。
     */
    public List<Long> topIds(int offset, int limit) {
        return redisSafe.execute("读热度榜",
                () -> {
                    var set = redis.opsForZSet().reverseRange(
                            RedisKeys.postHotZset(), offset, offset + limit - 1L);

                    if (set == null || set.isEmpty()) return new ArrayList<Long>();

                    List<Long> ids = new ArrayList<>(set.size());
                    for (String s : set) {
                        try {
                            ids.add(Long.valueOf(s));
                        } catch (NumberFormatException ignored) {
                            // ★ 脏数据：ZSET 里混进了非数字 member
                            //   跳过即可，不影响其他数据
                        }
                    }
                    return ids;
                },
                ArrayList::new);
    }

    /**
     * 取某个帖子的热度排名（从 0 开始）。
     *
     * ★ 用在哪：帖子详情页显示"热度第 3 名"。
     *   ★ 本项目暂未使用，但接口预留 ——
     *     排名比分数更直观（"第 3 名" 比 "5.0 分" 好懂）。
     *
     * @return 排名，不存在或失败返回 -1
     */
    public long rank(Long postId) {
        Long r = redisSafe.execute("查热度排名",
                () -> redis.opsForZSet().reverseRank(
                        RedisKeys.postHotZset(), String.valueOf(postId)),
                () -> null);

        return r == null ? -1 : r;
    }

    /**
     * 从 ZSET 移除（帖子删除时）。
     */
    public void remove(Long postId) {
        redisSafe.execute("移除热度榜",
                () -> redis.opsForZSet().remove(
                        RedisKeys.postHotZset(), String.valueOf(postId)),
                () -> null);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【清理 30 天前的帖子】定时任务调
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★ 为什么必须清理？】
     *
     *  ZSET 没设 TTL（因为设了榜单会周期性消失）。
     *  如果不清理，帖子删了、过期了，member 还留在 ZSET 里：
     *      1. 内存无限增长
     *      2. 取榜单时可能取到已删除的帖子 id
     *
     * ★ 清理策略：
     *   遍历 ZSET 里的所有 member，逐个查数据库的创建时间 ——
     *   ★ 但这太慢（N 次查询）。
     *
     *   ★ 更好的做法：由调用方传入"要保留的 id 集合"，
     *     这里只做 ZSET 侧的删除。
     *     见 HotScoreRebuildJob.rebuild()。
     */
    public void removeAll(java.util.Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return;

        redisSafe.execute("批量移除热度榜",
                () -> {
                    Object[] members = postIds.stream()
                            .map(String::valueOf).toArray();
                    redis.opsForZSet().remove(RedisKeys.postHotZset(), members);
                    return null;
                },
                () -> null);
    }

    /**
     * ZSET 里的成员总数。
     * ★ 用于监控/日志（"榜单里有 1250 个帖子"）。
     */
    public long size() {
        Long n = redisSafe.execute("查热度榜大小",
                () -> redis.opsForZSet().zCard(RedisKeys.postHotZset()),
                () -> 0L);
        return n == null ? 0 : n;
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么热度要"两个地方都存"？再强调一次】
     *
     *  Redis ZSET          → 服务"全站热门榜"（无筛选）
     *  MySQL hot_score 列  → 服务"带筛选的排序"（岗位/年限）
     *
     *  ★ 两处的分数必须【一致】—— 用同一个 compute() 计算。
     *    不一致会导致"热度榜第 1 名在筛选列表里排第 5"这种怪事。
     *
     *  ★ 实现上：每次调用 compute() 后，
     *    既写 ZSET 也写数据库列（见 PostService / VoteService）。
     * ═══════════════════════════════════════════════════════════════
     */
}
