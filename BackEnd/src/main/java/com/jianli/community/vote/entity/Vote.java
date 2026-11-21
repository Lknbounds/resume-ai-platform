package com.jianli.community.vote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  投票。★ 一张表同时管【帖子】和【评论】两种目标（多态）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么用一张表而不是两张（post_vote / comment_vote）？】
 *
 *  1. 逻辑完全一样（用户 + 目标 + 类型），只是目标表不同
 *  2. 统计"我的所有投票"时，一张表一次查完
 *  3. 加新的可投票对象（比如将来投"建议")只需加一个 targetType 枚举值
 *
 *  ★ 代价：数据库层无法建外键（target_id 指向哪张表不确定）。
 *    所以这一列【没有外键约束】—— 由 Service 层保证目标存在。
 *
 *  【★ 唯一键 uk_vote 是干什么的？】
 *
 *      UNIQUE (user_id, target_type, target_id, vote_type)
 *
 *  ★ 它保证"同一个人对同一个目标、同一种票只能投一次"。
 *    这是数据库层面的最后防线 ——
 *    即使 Service 层有并发 bug，也不会出现重复投票。
 *
 *  ★ 注意它【包含 vote_type】：
 *    这意味着同一用户可以对同一条评论同时投 UP（赞）和 USEFUL（有用）——
 *    这是设计允许的（两个维度独立）。
 */
@Entity
@Table(name = "vote",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_vote",
                columnNames = {"user_id", "target_type", "target_id", "vote_type"}))
@Getter
@Setter
@NoArgsConstructor
public class Vote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** POST / COMMENT */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    /** ★ 目标 id。没有外键 —— 多态表的固有限制 */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /**
     * 票类型。
     *
     * 帖子：UP / DOWN（互斥）
     * 评论：UP（赞）/ USEFUL（有用）（可同时存在）
     */
    @Column(name = "vote_type", nullable = false, length = 16)
    private String voteType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
