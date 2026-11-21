package com.jianli.community.vote.service;

import com.jianli.common.BizException;
import com.jianli.common.ErrorCode;
import com.jianli.community.post.entity.Post;
import com.jianli.community.post.repository.PostRepository;
import com.jianli.community.vote.entity.Vote;
import com.jianli.community.vote.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  投票业务。★ 核心是"改票"这个三态逻辑。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 投票的三种情况（这是本类最需要理解的地方）】
 *
 *  ┌─────────────┬──────────────────────────────────────┐
 *  │ 没投过      │ 新增一条，计数 +1                     │
 *  │ 投了同类型  │ 删除这条，计数 -1  ← 再点一次=取消     │
 *  │ 投了相反类型│ 改票：旧类型 -1，新类型 +1            │
 *  └─────────────┴──────────────────────────────────────┘
 *
 *  ★ 为什么"再点一次就取消"而不是"报错说已经投过了"？
 *    这是投票类产品的通行交互 —— 用户点错了能撤销。
 *
 *  ★ 为什么赞和踩互斥？
 *    你不可能同时觉得"好"和"差"。所以改票要同时调整两个计数。
 */
@Service
@RequiredArgsConstructor
public class VoteService {

    private final VoteRepository voteRepository;
    private final PostRepository postRepository;
    private final com.jianli.community.hot.HotScoreService hotScoreService;

    /**
     * ═══════════════════════════════════════════════════════════════
     *  【给帖子投票】
     * ═══════════════════════════════════════════════════════════════
     *
     * @param userId   投票人
     * @param postId   帖子
     * @param voteType UP / DOWN
     * @return 投票后的状态
     * @throws BizException SELF_VOTE_FORBIDDEN —— 给自己的帖子投票
     */
    @Transactional
    public VoteResult votePost(Long userId, Long postId, String voteType) {

        Post post = postRepository.findByIdAndStatus(postId, "PUBLISHED")
                .orElseThrow(() -> new BizException(ErrorCode.POST_NOT_VISIBLE));

        /*
         * ★ 不能给自己投票。
         *
         * 理由：允许自赞会让热度榜失去意义（发完自己点 10 个赞），
         * 对其他用户不公平。规则简单、成本低，直接禁止。
         */
        if (post.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.SELF_VOTE_FORBIDDEN);
        }

        String type = normalizeVoteType(voteType, List.of("UP", "DOWN"));

        var existing = voteRepository.findFirstByUserIdAndTargetTypeAndTargetId(
                userId, "POST", postId);

        String finalVote;

        if (existing.isPresent()) {
            Vote v = existing.get();

            if (v.getVoteType().equals(type)) {
                // ───── 情况 2：投了同类型 → 取消 ─────
                voteRepository.delete(v);
                adjustCount(post, type, -1);
                finalVote = null;

            } else {
                // ───── 情况 3：改票 ─────
                /*
                 * ★ 注意顺序：先把旧票的计数减掉，再改类型，再给新票加。
                 *
                 * ★ 为什么不删了重插？
                 *   直接在实体上改 vote_type，Hibernate 会发一条 UPDATE。
                 *   删+插是两条 SQL，而且可能撞唯一键（同一事务内删了还没提交）。
                 *   改字段最简单。
                 */
                adjustCount(post, v.getVoteType(), -1);
                v.setVoteType(type);
                adjustCount(post, type, +1);
                finalVote = type;
            }
        } else {
            // ───── 情况 1：没投过 → 新增 ─────
            Vote v = new Vote();
            v.setUserId(userId);
            v.setTargetType("POST");
            v.setTargetId(postId);
            v.setVoteType(type);
            voteRepository.save(v);
            adjustCount(post, type, +1);
            finalVote = type;
        }

        // ★ 计数改了要保存（post 是托管状态，其实会自动更新，显式 save 更清晰）
        postRepository.save(post);

        /*
         * ═══════════════════════════════════════════════════════
         *  【★ 投票后重算热度】
         * ═══════════════════════════════════════════════════════
         *
         * ★ 两处都要更新：
         *     post.hotScore  → MySQL 列，服务"带筛选的排序"
         *     Redis ZSET     → 服务"全站热门榜"
         *
         * ★ 为什么用同一个 compute()？
         *   两处分数必须一致，否则会出现
         *   "热度榜第 1 名在筛选列表里排第 5"的怪事。
         *
         * ★ 为什么放在 save 之后？
         *   compute() 要读 post 的最新计数（upCount 刚变过）。
         */
        double hot = hotScoreService.compute(post);
        post.setHotScore(hot);
        postRepository.save(post);
        hotScoreService.updateZset(post.getId(), hot);

        return new VoteResult(postId, post.getUpCount(), post.getDownCount(), finalVote);
    }

    /**
     * 调整帖子的赞/踩计数。
     *
     * ★ 为什么用 Math.max(0, ...) 兜底？
     *   理论上计数不会变负（有票才减票）。
     *   但如果历史数据有问题（比如手工改过库），
     *   减到 -1 会让前端显示负数，很难看。
     *   兜底成 0 更安全。
     */
    private void adjustCount(Post post, String type, int delta) {
        if ("UP".equals(type)) {
            post.setUpCount(Math.max(0, post.getUpCount() + delta));
        } else {
            post.setDownCount(Math.max(0, post.getDownCount() + delta));
        }
    }

    /**
     * 校验并规范化票类型。
     *
     * ★ 为什么要在服务端校验？
     *   前端可能传 "up"、"赞"、"UP "（带空格）等任意值。
     *   不校验的话，脏数据会进库，统计就乱了。
     */
    private String normalizeVoteType(String type, List<String> allowed) {
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!allowed.contains(t)) {
            throw new BizException(ErrorCode.PARAM_INVALID, null, "投票类型不合法");
        }
        return t;
    }

    /**
     * 投票结果。
     *
     * ★ 为什么返回最新计数而不是 void？
     *   前端要立刻更新界面上的数字。
     *   让前端自己 +1/-1 容易和后端不一致（比如并发时）。
     *   直接返回服务端的权威结果最可靠。
     *
     * 【★★ 为什么要加 @JsonInclude(ALWAYS)？】
     *
     *  application.yml 里配了全局的：
     *      spring.jackson.default-property-inclusion: non_null
     *  它会【过滤掉值为 null 的字段】。
     *
     *  于是"取消投票"时 myVote = null，这个字段直接从 JSON 里消失了。
     *  前端拿到的对象里没有 myVote 这个 key，
     *  写 d.myVote !== 'UP' 判断没问题，但写 d.myVote === null 就会漏。
     *
     *  ★ 对有"明确 null 语义"的字段（"我当前没投票"），
     *    必须让它显式出现在 JSON 里 = null，
     *    而不是"字段不存在"。
     *
     *  ★ 全局非空是好的默认（省流量），但要为需要的类型单独覆盖。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record VoteResult(Long postId, Integer upCount, Integer downCount, String myVote) {}
}
