package com.jianli.community.comment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  评论。★ 只有两级（顶层评论 + 回复）。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么只做两级，不做无限嵌套？】
 *
 *  1. UI 上三级以上极难阅读（缩进吃掉半个屏幕）
 *  2. 无限嵌套要么递归查询（MySQL 8 需要 CTE），要么多次查库
 *  3. 固定两级之后：一次查询取全部，内存里按 rootId 分组 ——
 *     查询次数恒定，代码也简单
 *
 *  ★ 第三级直接拒绝（43005），并提示"请回复到原评论下"，
 *    比默默折叠更好。
 *
 *  【★ parent_id 和 root_id 为什么都要？】
 *
 *  只存 parent_id（指向被回复的评论）时：
 *      要查"某条顶层评论下的所有回复"必须递归或自连接。
 *
 *  加 root_id 之后：
 *      WHERE post_id=? AND root_id=? 一次取全，
 *      或者 WHERE post_id=? 取整个帖子的评论再内存分组。
 *
 *  ★ 冗余一个字段，换来查询简单 —— 读多写少场景下的典型反范式。
 */
@Entity
@Table(name = "comment")
@Getter
@Setter
@NoArgsConstructor
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 父评论。null = 顶层评论 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 所属顶层评论。顶层评论自身为 null，回复为根评论 id */
    @Column(name = "root_id")
    private Long rootId;

    /**
     * 针对哪个板块。
     *
     * ★ 这是本项目的差异化设计：简历是分板块的，
     *   建议也该按板块提（技能/经历/项目/教育）。
     */
    @Column(nullable = false, length = 32)
    private String section = "OTHER";

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "useful_count", nullable = false)
    private Integer usefulCount = 0;

    /** 是否被帖子作者采纳 */
    @Column(nullable = false)
    private Boolean adopted = false;

    /** 采纳后生成的新简历版本 */
    @Column(name = "adopted_version_id")
    private Long adoptedVersionId;

    @Column(name = "adopted_at")
    private LocalDateTime adoptedAt;

    @Column(nullable = false, length = 16)
    private String status = "PUBLISHED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
