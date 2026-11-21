package com.jianli.privacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一次个人信息审核的记录。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么"审核不通过"也要落库？】
 *
 *  1. 合规审计：要能回答"这条帖子发布时审核过吗"
 *  2. 规则调优：统计各类命中量 ——
 *     如果某类突然暴增，说明规则写宽了，在误杀用户
 *  3. ★ 拒绝记录必须用【独立事务】（REQUIRES_NEW），
 *     否则外层发帖失败回滚时，审计记录也一起没了
 *
 *  【★★ 为什么表里不存原文？】
 *
 *  审核的目的是"不要泄露个人信息"。
 *  如果为了审计把原文存进数据库，等于在库里永久多了一份副本 ——
 *  和初衷直接冲突。
 *
 *  ★ 所以只存：
 *      content_hash   → 用于关联（不可逆，无法还原内容）
 *      hit_detail     → 脱敏后的片段（138****5678）
 */
@Entity
@Table(name = "privacy_review")
@Getter
@Setter
@NoArgsConstructor
public class PrivacyReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 场景：POST_PUBLISH / COMMENT_PUBLISH / PRECHECK */
    @Column(nullable = false, length = 24)
    private String scene;

    /** PASSED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 命中条目数 */
    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    /** 命中详情 JSON（★ 只存脱敏片段） */
    @Column(name = "hit_detail", columnDefinition = "JSON")
    private String hitDetail;

    /** 规则版本。规则改动必须升版本 */
    @Column(name = "rule_version", nullable = false, length = 16)
    private String ruleVersion;

    /** 是否启用了 AI 辅助复核 */
    @Column(name = "ai_checked", nullable = false)
    private Boolean aiChecked = false;

    /**
     * 被审核内容的 SHA-256。
     *
     * ★ CHAR(64) 必须配 @JdbcTypeCode(SqlTypes.CHAR)，
     *   否则 String 默认映射 VARCHAR，启动时 validate 报类型不匹配。
     *   （这个坑之前踩过两次了）
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 审核耗时（毫秒） */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
