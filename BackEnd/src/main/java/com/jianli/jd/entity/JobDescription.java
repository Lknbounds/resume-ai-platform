package com.jianli.jd.entity;

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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  目标岗位 JD。用户粘贴的岗位描述 + 解析结果。
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  【★ 为什么解析结果要存库（parsed_json）而不是每次实时算？】
 *
 *  1. 一次分析里要反复用（评分、建议、面试题），每次都重算浪费
 *  2. 要可追溯：报告里存"当时这份 JD 被解析成什么"，
 *     词典升级后历史仍可回看
 *  3. 加上 content_hash 后可以跨用户复用（同一段 JD 解析结果相同）
 *
 *  ★ 规则解析只要几毫秒，理论上可以每次重算。
 *    但存下来是为了将来接 AI 时能复用（AI 一次要几秒、要花钱）。
 *    现在的规则版用的是同一个字段，到时直接替换解析器即可。
 */
@Entity
@Table(name = "job_description")
@Getter
@Setter
@NoArgsConstructor
public class JobDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 岗位名称，如"Java 后端开发工程师" */
    @Column(nullable = false, length = 100)
    private String title;

    /** 公司名，选填 */
    @Column(length = 100)
    private String company;

    /** JD 原文 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** 内容 SHA-256，用于去重和缓存 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 解析状态：NONE / SUCCESS / FAILED */
    @Column(name = "parse_status", nullable = false, length = 16)
    private String parseStatus = "NONE";

    /** 解析来源：RULE / AI */
    @Column(name = "parsed_source", length = 8)
    private String parsedSource;

    /** 解析结果 JSON（JdParsed 的序列化） */
    @Column(name = "parsed_json", columnDefinition = "JSON")
    private String parsedJson;

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

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
