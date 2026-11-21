package com.jianli.community.hot;

import com.jianli.common.ApiResponse;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  热度榜的调试/运维接口。
 *
 *      GET  /api/hot/ranking        查看 Redis 里的热度榜
 *      POST /api/hot/rebuild        立即重建热度榜（不用等 10 分钟）
 *      GET  /api/hot/score/{postId} 查看某个帖子的热度明细
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么要有这个接口？】
 *
 *  定时任务是 10 分钟一次 —— 你刚插完演示数据，
 *  要等 10 分钟才能看到热度榜有内容，调试体验极差。
 *
 *  ★ 有了 rebuild 接口：
 *      插完数据 → 点一下"重建" → 立刻看到热度榜
 *
 *  【★★ 安全考虑：需不需要权限？】
 *
 *  ★ 严格说应该限制为管理员。
 *    但本项目【没有管理员后台】（明确不做），
 *    所以这里只做基本的防护：
 *      1. 需要登录（在 SecurityConfig 里是默认的 authenticated）
 *      2. 接口路径放在 /api/hot 下，不是公开路径
 *
 *  ★ 风险很低：
 *    - rebuild 是幂等的，多点几次只是多算几遍
 *    - ranking 只是读取，不泄露敏感信息（帖子 id 和分数）
 *    - 不涉及任何写操作破坏数据
 *
 *  ★ 如果要上线给真实用户用，应该：
 *    - 挪到 /api/admin/** 下
 *    - 加 ROLE_ADMIN 权限校验
 *    这个留在文档里说明。
 */
@RestController
@RequestMapping("/api/hot")
@Tag(name = "热度榜（调试）")
@RequiredArgsConstructor
public class HotScoreController {

    private final HotScoreService hotScoreService;
    private final HotScoreRebuildJob rebuildJob;
    private final PostRepository postRepository;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查看热度榜】GET /api/hot/ranking?limit=10
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应：
     *      {
     *        "zsetSize": 5,
     *        "items": [
     *          {"rank":1,"postId":2,"score":8.42,"title":"..."},
     *          ...
     *        ]
     *      }
     *
     * ★ 注意：这个接口读的是 Redis ZSET，不是 MySQL。
     *   如果 ZSET 是空的（还没重建过），items 就是空数组。
     */
    @GetMapping("/ranking")
    @Operation(summary = "查看热度榜", description = "读 Redis ZSET，需要先重建")
    public ApiResponse<Map<String, Object>> ranking(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 50);

        // 从 ZSET 按分数倒序取
        List<Map<String, Object>> items = new ArrayList<>();

        List<Long> ids = hotScoreService.topIds(0, safeLimit);

        int rank = 1;
        for (Long id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("postId", id);

            /*
             * ★ 顺便把帖子标题和分数带上 —— 只看 id 没法判断排序对不对。
             *
             * ★ 这里逐个查是为了调试方便，D 量小无所谓。
             *   生产环境应该批量查（findAllById）。
             */
            postRepository.findById(id).ifPresent(p -> {
                m.put("title", p.getTitle());
                m.put("dbHotScore", p.getHotScore());
                m.put("upCount", p.getUpCount());
                m.put("commentCount", p.getCommentCount());
                m.put("viewCount", p.getViewCount());
            });

            items.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zsetSize", hotScoreService.size());
        data.put("count", items.size());
        data.put("items", items);

        return ApiResponse.ok(data);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【立即重建】POST /api/hot/rebuild
     * ═══════════════════════════════════════════════════════════════
     *
     * ★ 调用后立刻把最近 30 天的帖子重算一遍并写入 ZSET。
     *   插完演示数据后调这个，就不用等 10 分钟。
     *
     * ★ 这个方法调的是定时任务的 rebuild() ——
     *   同一个方法，只是触发方式不同。
     *   ★ 好处是"手动"和"自动"的行为完全一致，不会出现
     *     "手动重建的数据和定时重建的不一样"。
     */
    @PostMapping("/rebuild")
    @Operation(summary = "立即重建热度榜", description = "不用等 10 分钟定时任务")
    public ApiResponse<Map<String, Object>> rebuild() {
        long start = System.currentTimeMillis();

        rebuildJob.rebuild();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zsetSize", hotScoreService.size());
        data.put("costMs", System.currentTimeMillis() - start);

        return ApiResponse.ok(data);
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【查看单个帖子的热度明细】GET /api/hot/score/{postId}
     * ═══════════════════════════════════════════════════════════════
     *
     * 响应里把公式的每一步都算出来 —— 方便验证计算是否正确：
     *
     *      {
     *        "postId": 2,
     *        "netVotes": 40,          ← 赞减踩
     *        "commentCount": 4,
     *        "viewCount": 2380,
     *        "hoursSince": 72.0,
     *        "baseScore": 100.0,      ← 40×1 + 4×2 + log10(2381)×3
     *        "decayDivisor": 637.0,   ← (72+2)^1.5
     *        "hotScore": 0.157,       ← base / decayDivisor
     *        "dbHotScore": 0.157,     ← 数据库里存的值
     *        "zsetRank": 3            ← Redis 里的排名（-1 表示不在榜上）
     *      }
     */
    @GetMapping("/score/{postId}")
    @Operation(summary = "查看热度明细", description = "展示公式每一步的计算结果")
    public ApiResponse<Map<String, Object>> score(
            @org.springframework.web.bind.annotation.PathVariable Long postId) {

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new com.jianli.common.BizException(
                        com.jianli.common.ErrorCode.POST_NOT_VISIBLE));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("postId", postId);
        data.put("title", post.getTitle());

        // ───── 输入 ─────
        int netVotes = Math.max(0, post.getUpCount() - post.getDownCount());
        data.put("upCount", post.getUpCount());
        data.put("downCount", post.getDownCount());
        data.put("netVotes", netVotes);
        data.put("commentCount", post.getCommentCount());
        data.put("viewCount", post.getViewCount());

        // ───── 中间计算 ─────
        double hours = post.getCreatedAt() == null ? 0
                : Math.max(0, java.time.Duration.between(
                        post.getCreatedAt(),
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                ).getSeconds() / 3600.0);

        double votePart = netVotes * 1.0;
        double commentPart = post.getCommentCount() * 2.0;
        double viewPart = Math.log10(post.getViewCount() + 1) * 3.0;
        double base = votePart + commentPart + viewPart;
        double divisor = Math.pow(hours + 2, 1.5);

        data.put("hoursSince", Math.round(hours * 10) / 10.0);
        data.put("votePart", Math.round(votePart * 100) / 100.0);
        data.put("commentPart", Math.round(commentPart * 100) / 100.0);
        data.put("viewPart", Math.round(viewPart * 100) / 100.0);
        data.put("baseScore", Math.round(base * 100) / 100.0);
        data.put("decayDivisor", Math.round(divisor * 100) / 100.0);

        // ───── 结果 ─────
        double computed = hotScoreService.compute(post);
        data.put("hotScore", Math.round(computed * 10000) / 10000.0);
        data.put("dbHotScore", post.getHotScore());
        data.put("zsetRank", hotScoreService.rank(postId));

        return ApiResponse.ok(data);
    }

    /*
     * ═══════════════════════════════════════════════════════════════
     *  【★ 怎么用这个接口验证热度榜是正确的？】
     *
     *  1. 插完演示数据
     *  2. POST /api/hot/rebuild
     *  3. GET /api/hot/ranking
     *     → 应看到 Bob 的帖子（5 赞 4 评论 2380 浏览）排第一
     *     → Emma 的帖子（0 互动，5 分钟前）可能排第二或第三
     *       因为时间衰减：5 分钟 = 0.08 小时，
     *       base=0 → hot=0，所以其实排最后
     *       ★ 注意：零互动的帖子即使最新，热度也是 0。
     *         这不是 bug —— 没有互动就没有热度是合理的。
     *
     *  4. GET /api/hot/score/{postId}
     *     → 对照公式手算一遍，验证每一步
     *
     *  ★ 如果 ranking 是空的：ZSET 没建立，先调 rebuild。
     * ═══════════════════════════════════════════════════════════════
     */
}
