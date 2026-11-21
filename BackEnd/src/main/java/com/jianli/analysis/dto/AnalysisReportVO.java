package com.jianli.analysis.dto;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  分析报告详情。★ 前端报告页的全部数据都在这。
 *
 *  ★ 借鉴 getresumeiq.com 的报告结构：
 *      大分数 + 等级
 *      六个维度（带进度条）
 *      强项 / 命中技能 / 缺失技能
 *      改进建议
 * ═══════════════════════════════════════════════════════════════════════
 */
public record AnalysisReportVO(

        Long id,
        String status,

        /** 总分，如 76.8 */
        Double overallScore,

        /** 等级：EXCELLENT / GOOD / FAIR / WEAK */
        String grade,

        /** 一句话总结，如"与目标岗位有较好的匹配度，补齐 2 项核心技能后更有竞争力" */
        String summary,

        // ─────────── 关联信息（方便前端直接显示） ───────────

        Long resumeId,
        String resumeTitle,
        Integer resumeVersionNo,
        Long jdId,
        String jdTitle,

        // ─────────── 六维度 ───────────

        List<DimensionVO> dimensions,

        // ─────────── 关键词三态 ───────────

        List<KeywordVO> hits,
        List<KeywordVO> partials,
        List<KeywordVO> missing,

        /**
         * ★ 优势列表。这是"先肯定再建议"的关键。
         *
         * ★ 每条都是可验证的事实（"有 2 段工作经历，累计 3.0 年"），
         *   不是空话（"你很有潜力"）。
         */
        List<String> strengths,

        /**
         * 软技能（团队协作等）。★ 只做提示，不计入评分。
         *
         * ★ 为什么单独一个字段？
         *   因为软技能无法从简历文本可靠判断 ——
         *   "带过 5 人团队"能体现协作能力，但规则识别不出来。
         *   所以不参与打分，只提示"JD 提到了，可以在项目里体现"。
         */
        List<String> softSkills,

        // ─────────── 改进建议 ───────────

        List<SuggestionVO> suggestions,

        // ─────────── 元信息 ───────────

        boolean aiEnabled,
        boolean degraded,
        String scoringVersion,
        Integer durationMs,
        String createdAt
) {

    /**
     * 一个维度。
     *
     * @param key      英文标识，前端用来映射颜色/图标
     * @param name     中文名
     * @param score    0-100
     * @param weight   生效权重（已归一化）
     * @param detail   一句话说明分数怎么来的
     * @param applicable 是否参与计算（false 时不显示进度条）
     */
    public record DimensionVO(
            String key,
            String name,
            Double score,
            Double weight,
            String detail,
            Boolean applicable
    ) {}

    /**
     * 一条关键词。
     *
     * @param name        技能名
     * @param importance  HIGH / MEDIUM / LOW
     * @param matchedBy   EXACT / ALIAS / LEXICAL / MISSING
     * @param evidence    简历里的证据句（命中时有，缺失时为 null）
     */
    public record KeywordVO(
            String name,
            String importance,
            String matchedBy,
            String evidence
    ) {}

    /**
     * 一条改进建议。
     *
     * ★ 三段式的价值：
     *     issue      → 问题是什么
     *     evidence   → 证据（简历原文片段）
     *     suggestion → 怎么改
     *
     * 只有"问题"没有"证据"，用户会问"你说的是哪句？"
     * 只有"问题"没有"方向"，用户知道错了但不知道怎么改。
     */
    public record SuggestionVO(
            String section,
            String severity,
            String issue,
            String evidence,
            String suggestion
    ) {}
}
