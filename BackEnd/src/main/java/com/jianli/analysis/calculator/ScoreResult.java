package com.jianli.analysis.calculator;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  【这个类是什么】
 *  一次评分的完整结果。会被拆开存进 analysis_report 的各个字段。
 * ═══════════════════════════════════════════════════════════════════════
 */
public record ScoreResult(
        /** 总分 0-100，保留 1 位小数 */
        double overallScore,

        /** 等级：EXCELLENT / GOOD / FAIR / WEAK */
        String grade,

        /** 六个维度的明细 */
        List<DimensionScore> dimensions,

        /** 生效权重（含不适用的维度列表） */
        EffectiveWeights weights,

        /** 技能匹配明细，用于生成"命中/部分/缺失"三组列表 */
        List<SkillMatch> skillMatches,

        /** 加分项（0-5） */
        double bonus
) {
    public java.util.Optional<DimensionScore> dimension(String key) {
        return dimensions.stream().filter(d -> d.key().equals(key)).findFirst();
    }
}
