package com.jianli.community.hot;

import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  热度榜的定时重建任务。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么需要定时重建？三个理由】
 *
 *  ① 时间衰减必须周期性重算
 *     hot = base / (hours + 2)^1.5
 *     ★ hours 随时间增长，所以【即使没有任何互动，热度也会变化】。
 *       不重算的话，一个昨天发的帖今天还是昨天的分数，
 *       永远压着新帖 —— 衰减就失效了。
 *
 *  ② Redis 重启/清空后要能自愈
 *     ZSET 在 Redis 里，如果 Redis 被清空、重启（没开持久化），
 *     榜单就空了。定时重建能从 MySQL 恢复。
 *
 *  ③ 清理已删除/过期的帖子
 *     帖子软删除后还在 ZSET 里；
 *     30 天前的帖子也不该再占榜单。
 *
 *  【★★ 重建策略：为什么要"限制数量"？】
 *
 *  ❌ 全量重建：把所有帖子都重算一遍
 *     如果有 10 万帖子，每 10 分钟跑一次太浪费。
 *
 *  ✅ 只重建"最近 30 天 + 有互动"的帖子：
 *      - 30 天前的帖子，即使有 1000 赞，
 *        base=1000 除以 (720+2)^1.5 ≈ 1000/19000 ≈ 0.05
 *        —— 早已沉底，不重建也无所谓
 *      - 并发帖后在 updateZset 时就已经写了，
 *        定时任务只是"补漏 + 衰减"
 *
 *  ★ 上限 2000 个帖子，防止极端情况拖慢任务。
 *    超出部分只记录 WARN，不静默截断（"no silent caps" 原则）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotScoreRebuildJob {

    /** 只重建最近多少天内创建的帖子 */
    private static final int REBUILD_DAYS = 30;

    /** 单次重建的最大帖子数（防止极端情况拖慢） */
    private static final int MAX_REBUILD = 2000;

    private final PostRepository postRepository;
    private final HotScoreService hotScoreService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【定时重建】每 10 分钟
     * ═══════════════════════════════════════════════════════════════
     *
     * cron 表达式 "0 星号/10 星号 星号 星号 星号" 的含义（把星号换成 *）：
     *
     *      秒  分     时   日   月   周
     *      0   ÷10    all  all  all  all
     *      │    │
     *      │    └─ 每 10 分钟（0, 10, 20, 30, 40, 50 分）
     *      └────── 在第 0 秒触发
     *
     * ★ 为什么是 10 分钟而不是 1 分钟？
     *   热度榜的"新鲜度"不需要秒级。
     *   10 分钟内的排序变化用户感知不到，
     *   但 1 分钟跑一次会给数据库带来 10 倍的查询压力。
     *
     * ★ 为什么不是每小时？
     *   时间衰减比较快（1.5 次方），1 小时的间隔会让榜单"卡住"太久。
     */
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void rebuild() {
        long start = System.currentTimeMillis();

        try {
            // ───── ① 查出要重建的帖子 ─────
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(REBUILD_DAYS);

            List<Post> posts = postRepository
                    .findByStatusAndCreatedAtAfterOrderByCreatedAtDesc("PUBLISHED", since);

            if (posts.isEmpty()) {
                log.debug("热度重建：没有需要重建的帖子");
                return;
            }

            /*
             * ★ 超出上限时记录 WARN，而不是静默截断。
             *
             *   "no silent caps" 原则：
             *   如果代码做了数量限制，必须让运维知道"这次没处理完"。
             *   否则哪天榜单出问题，你会以为"全量都重建了"。
             */
            if (posts.size() > MAX_REBUILD) {
                log.warn("热度重建：符合条件的帖子 {} 个，超过上限 {}，本次只重建最近的 {} 个",
                        posts.size(), MAX_REBUILD, MAX_REBUILD);
                posts = posts.subList(0, MAX_REBUILD);
            }

            // ───── ② 逐个重算并写回 ─────
            int updated = 0;
            Set<Long> validIds = posts.stream()
                    .map(Post::getId).collect(Collectors.toSet());

            for (Post post : posts) {
                double hot = hotScoreService.compute(post);

                /*
                 * ★ 只在变化超过阈值时更新，减少无谓的 Redis 写入。
                 *
                 *   热度是浮点数，每 10 分钟必定有微小变化（时间衰减）。
                 *   如果每次都写，就是纯浪费。
                 *
                 *   ★ 阈值 0.001：
                 *     变化小于千分之一对排序没有实质影响。
                 */
                if (Math.abs(hot - post.getHotScore()) > 0.001) {
                    post.setHotScore(hot);
                    hotScoreService.updateZset(post.getId(), hot);
                    updated++;
                }
            }

            // ───── ③ 清理无效的 ZSET 成员 ─────
            cleanupInvalidMembers(validIds);

            long cost = System.currentTimeMillis() - start;
            log.info("热度重建完成：扫描 {} 个帖子，更新 {} 个，耗时 {}ms",
                    posts.size(), updated, cost);

        } catch (Exception e) {
            /*
             * ★ 定时任务抛异常会被 Spring 记录，但不会中断后续调度。
             *   这里显式 catch 是为了让日志更清晰。
             *
             *   ★ 绝对不能让它抛出 —— 否则可能被调度器禁用后续执行。
             */
            log.error("热度重建失败", e);
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【清理 ZSET 里的无效成员】
     * ═══════════════════════════════════════════════════════════════
     *
     * 【★ 什么算"无效"？】
     *
     *  1. 帖子已删除（status='DELETED'）但仍留在 ZSET
     *  2. 帖子超过 30 天（不参与榜单）
     *  3. 帖子在数据库里根本不存在（数据不一致）
     *
     * 【★ 为什么不能在 rebuild 里直接扫 ZSET？】
     *
     *  ZSET 可能有几千个成员，逐个查数据库太慢。
     *  ★ 这里的策略是"反向"的：
     *     已知"有效 id 集合"（最近 30 天的帖子），
     *     取出 ZSET 全部成员，把不在有效集合里的删掉。
     *
     *  ★ 这样只需要 1 次 ZSET 读取 + 1 次批量删除，不查数据库。
     */
    private void cleanupInvalidMembers(Set<Long> validIds) {
        try {
            // 取 ZSET 全部成员（用 RedisSafe 的 execute 包装降级）
            List<Long> allMembers = hotScoreService.topIds(0, Integer.MAX_VALUE);

            List<Long> invalid = new ArrayList<>();
            for (Long id : allMembers) {
                if (!validIds.contains(id)) {
                    invalid.add(id);
                }
            }

            if (!invalid.isEmpty()) {
                hotScoreService.removeAll(invalid);
                log.info("热度榜清理：移除 {} 个无效帖子", invalid.size());
            }
        } catch (Exception e) {
            // ★ 清理失败不影响主流程，下次再清
            log.warn("热度榜清理失败: {}", e.getMessage());
        }
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 为什么用 @Scheduled 而不是"每次请求时检查"？】
     *
     *  1. 定时任务的时间可控（每 10 分钟，与请求量无关）
     *  2. 请求时检查意味着"没有流量就不重建" ——
     *     小众社区可能几小时没人访问，榜单就冻住了
     *  3. 定时任务失败可以重试（下次调度），
     *     请求时失败会影响用户
     *
     *  ★ 代价：榜单最多有 10 分钟延迟。
     *    对"热门榜"这个场景完全可接受 ——
     *    反正投票后 updateZset 会立即更新单个帖子的分数，
     *    定时任务只是"补全 + 衰减"。
     * ═══════════════════════════════════════════════════════════════
     */
}
