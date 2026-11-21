package com.jianli.community.vote.repository;

import com.jianli.community.vote.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个接口是什么】
 *  投票的数据访问。
 * ═══════════════════════════════════════════════════════════════════════
 */
public interface VoteRepository extends JpaRepository<Vote, Long> {

    /**
     * 查"我对某个目标的投票"。
     *
     * ★ 返回 Optional 而不是 List：
     *   同一用户对同一目标，UP/DOWN 是互斥的（最多一条）。
     *   ★ 但评论可以同时有 UP 和 USEFUL 两条 ——
     *     所以这个方法只适合帖子，评论要用下面的 findOne。
     */
    Optional<Vote> findFirstByUserIdAndTargetTypeAndTargetId(
            Long userId, String targetType, Long targetId);

    /** 查"我对某个目标的、某种类型"的投票（评论用） */
    Optional<Vote> findByUserIdAndTargetTypeAndTargetIdAndVoteType(
            Long userId, String targetType, Long targetId, String voteType);

    /**
     * 批量查"我对一批目标的投票"。
     *
     * ★ 为什么需要批量？
     *   评论列表可能一次返回 20 条，如果逐条查"我点赞了吗"就是 20 次 SQL（N+1）。
     *   一次 IN 查询拿到全部，内存里做映射。
     *
     * ★ 这是列表接口避免 N+1 的标准做法。
     */
    List<Vote> findByUserIdAndTargetTypeAndTargetIdIn(
            Long userId, String targetType, Collection<Long> targetIds);

    /** 统计某个目标的某种票数（用于校正冗余计数） */
    long countByTargetTypeAndTargetIdAndVoteType(
            String targetType, Long targetId, String voteType);
}
