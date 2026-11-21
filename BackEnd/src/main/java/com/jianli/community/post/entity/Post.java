package com.jianli.community.post.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  社区帖子。★ 用户把一次分析结果发布出来供他人评论。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么帖子要存【快照】而不是只存 reportId？】
 *
 *  帖子发出后，作者可能：
 *      改简历（内容变了）
 *      删报告（report_id 置 null）
 *      删简历（报告级联删除）
 *      注销（本项目不提供，但设计上要考虑）
 *
 *  ★ 如果帖子详情实时 JOIN 报告，上面任何一种情况都会让帖子变成空白。
 *
 *  所以发布时把两份数据【复制】进帖子：
 *      resume_content   → 简历正文（用户已抹去敏感信息）
 *      report_snapshot  → 分数、维度、关键词、建议的 JSON
 *
 *  ★ 社区的帖子应该是【不可变的历史记录】——
 *    你当时发了什么，它就是什么，不受后续操作影响。
 *
 *  【★★ 匿名怎么实现的？】
 *
 *  user_id 始终落库，【只对外脱敏】。
 *
 *  ★ 为什么不真的不记名？
 *    1. 作者要能删自己的帖子
 *    2. 不能给自己的帖子点赞
 *    3. 违规内容要能追溯
 *
 *  ★ 真正的匿名（不记名）会让社区治理彻底失能。
 *    所以选择"对外匿名、对内可追溯"。
 */
@Entity
@Table(name = "post")
@Getter
@Setter
@NoArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 作者。★ 匿名帖也存，仅对外隐藏 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 来源报告。
     *
     * ★ 可为 null：报告被删除后置 null（ON DELETE SET NULL）。
     *   帖子内容靠快照保留，不受影响。
     */
    @Column(name = "report_id")
    private Long reportId;

    /**
     * 发布前的审核记录。
     *
     * ★ 必须是 PASSED 的记录 —— Service 层保证。
     * ★ 不加外键约束（原因见 03 文档 3.1.1 ④）：
     *   审核记录和帖子生命周期不同，级联删除时会冲突。
     */
    @Column(name = "privacy_review_id", nullable = false)
    private Long privacyReviewId;

    @Column(nullable = false, length = 120)
    private String title;

    /** 作者补充说明 */
    @Column(length = 2000)
    private String content;

    /**
     * 简历正文快照。
     *
     * ★ 这是用户抹去敏感信息后提交的版本 ——
     *   系统已经审核过它不含个人信息。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "resume_content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String resumeContent;

    /** 报告快照 JSON：分数、维度、关键词、建议 */
    @Column(name = "report_snapshot", columnDefinition = "JSON", nullable = false)
    private String reportSnapshot;

    /**
     * 总分。★ 冗余字段。
     *
     * ★ 为什么冗余？因为列表要按分数排序/筛选。
     *   从 report_snapshot JSON 里取值需要 JSON 函数，无法建索引。
     *   存一列 DECIMAL 可以直接排序。
     */
    @Column(name = "overall_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal overallScore;

    /** 目标岗位。★ 冗余，用于筛选用 */
    @Column(name = "target_position", length = 64)
    private String targetPosition;

    /** 经验年限。★ 冗余，用于筛选用 */
    @Column(name = "years_of_experience", nullable = false)
    private Integer yearsOfExperience = 0;

    /** 是否匿名展示（只影响对外 API 的输出） */
    @Column(name = "is_anonymous", nullable = false)
    private Boolean isAnonymous = false;

    /** PUBLISHED / DELETED / HIDDEN */
    @Column(nullable = false, length = 16)
    private String status = "PUBLISHED";

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Column(name = "comment_count", nullable = false)
    private Integer commentCount = 0;

    @Column(name = "up_count", nullable = false)
    private Integer upCount = 0;

    @Column(name = "down_count", nullable = false)
    private Integer downCount = 0;

    /**
     * 热度分（时间衰减）。
     *
     * ★ 冗余存一列的原因：
     *   带筛选条件（岗位/年限）时无法用 Redis ZSET 排序，
     *   只能走 MySQL ORDER BY。这一列就是兜底。
     */
    @Column(name = "hot_score", nullable = false)
    private Double hotScore = 0.0;

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
