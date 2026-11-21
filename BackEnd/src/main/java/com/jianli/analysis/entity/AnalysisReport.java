package com.jianli.analysis.entity;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一次分析的报告。★ 这是整个产品的核心数据。
 *
 *  它记录了：某个简历版本 × 某个 JD → 分数 + 各维度明细 + 关键词 + 建议
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★★ 为什么要存这么多 JSON 快照？】
 *
 *  报告里同时存了四份"当时的样子"：
 *      jd_parsed_json      当时 JD 被解析成什么
 *      resume_parsed_json  当时简历被解析成什么
 *      dimension_json      当时的六个维度分数
 *      weights_json        当时用的权重
 *
 *  为什么？因为这些东西【会变】：
 *      词典升级 → 同样的 JD 抽出的技能会变
 *      权重调整 → 同样的维度分数算出不同的总分
 *      简历改版 → 内容变了
 *
 *  ★ 如果报告只存总分，三个月后用户问"为什么当时是 76 分"，
 *    你无法回答 —— 因为解析逻辑已经变了。
 *    存快照之后，报告永远能自证。
 *
 *  这就是"报告是一份不可变的历史记录"的设计。
 */
@Entity
@Table(name = "analysis_report")
@Getter
@Setter
@NoArgsConstructor
public class AnalysisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 被分析的简历版本 */
    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;

    /** 目标 JD */
    @Column(name = "jd_id", nullable = false)
    private Long jdId;

    /**
     * 状态：PENDING / RUNNING / SUCCESS / PARTIAL / FAILED
     *
     * ★ 现在用规则解析是同步的，创建完成就是 SUCCESS。
     *   但字段保留 —— 接 AI 之后会变成异步，
     *   这个状态机是"以后不用改表结构"的预留。
     */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    /** 总分 0-100 */
    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    /**
     * 等级：EXCELLENT / GOOD / FAIR / WEAK
     *
     * ★★ length 必须是 16！
     *   原来写 8，而 EXCELLENT 有 9 个字符 ——
     *   插入时报 Data too long for column 'grade'。
     *
     *   这个 bug 只在"打出优秀分"时才触发（低分是 GOOD/FAIR/WEAK，
     *   都 ≤6 字符），所以前面测试一直没暴露。
     *   见 V4__widen_analysis_grade.sql。
     *
     * ★ 教训：枚举字段的列宽按【最长的枚举值】定，不是按常见的那个。
     */
    @Column(length = 16)
    private String grade;

    /** 六维度明细 JSON */
    @Column(name = "dimension_json", columnDefinition = "JSON")
    private String dimensionJson;

    /** 关键词命中/部分/缺失 JSON */
    @Column(name = "keyword_json", columnDefinition = "JSON")
    private String keywordJson;

    /** 语义信号（现在恒为 NONE，接 AI 后会有数据） */
    @Column(name = "semantic_json", columnDefinition = "JSON")
    private String semanticJson;

    /** 权重快照 JSON */
    @Column(name = "weights_json", columnDefinition = "JSON")
    private String weightsJson;

    /** 评分算法版本。算法改动必须升版本（让历史分数可区分） */
    @Column(name = "scoring_version", nullable = false, length = 16)
    private String scoringVersion = "v1";

    /** JD 解析快照 */
    @Column(name = "jd_parsed_json", columnDefinition = "JSON")
    private String jdParsedJson;

    /** 简历解析快照 */
    @Column(name = "resume_parsed_json", columnDefinition = "JSON")
    private String resumeParsedJson;

    /** 本次是否启用了 AI（现在恒为 false） */
    @Column(name = "ai_enabled", nullable = false)
    private Boolean aiEnabled = false;

    /** 是否发生降级（AI 失败回落规则时置 true） */
    @Column(nullable = false)
    private Boolean degraded = false;

    /** 降级原因，逗号分隔 */
    @Column(name = "degraded_reasons", length = 255)
    private String degradedReasons;

    /** 实际使用的模型（AI 才有） */
    @Column(name = "ai_model", length = 64)
    private String aiModel;

    /**
     * 缓存/幂等键。
     *
     * ★ @JdbcTypeCode(SqlTypes.CHAR)：String 默认映射成 VARCHAR，
     *   而建表脚本里是 CHAR(64)（SHA-256 定长）。
     *   不加这个注解，启动时会报 Schema-validation 类型不匹配。
     *   （同样的坑在 ResumeVersion.contentHash 上也踩过）
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;

    /** 耗时毫秒 */
    @Column(name = "duration_ms")
    private Integer durationMs;

    /** 失败原因 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

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
